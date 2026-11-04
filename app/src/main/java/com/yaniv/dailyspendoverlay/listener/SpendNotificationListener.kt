package com.yaniv.dailyspendoverlay.listener

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.yaniv.dailyspendoverlay.R
import com.yaniv.dailyspendoverlay.core.Amount
import com.yaniv.dailyspendoverlay.core.AmountParser
import com.yaniv.dailyspendoverlay.core.DetectionEntry
import com.yaniv.dailyspendoverlay.core.DetectionStatus
import com.yaniv.dailyspendoverlay.core.LedgerResult
import com.yaniv.dailyspendoverlay.data.SpendStore
import com.yaniv.dailyspendoverlay.overlay.OverlayInfo
import com.yaniv.dailyspendoverlay.overlay.OverlayManager

/**
 * קליטת נתונים פסיבית: המערכת קוראת ל-onNotificationPosted רק כשמגיעה התראה חדשה.
 * אין לולאות, אין טיימרים, אין WakeLock ואין רשת – בין התראה להתראה השירות לא עושה כלום.
 *
 * זרימה: התראה → מקור ברשימה? → Regex לסכום + מטבע → סינון זיכוי/כפילות →
 *        המרה למטבע הראשי → חישוב יומי → חלונית.
 */
class SpendNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "DailySpend"

        /** צ'אטים ומיילים פרטיים – לא נבדקים ולא נרשמים ביומן, אלא אם הוספת אותם במפורש לרשימה */
        private val PRIVATE_APPS = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.telegram.messenger.web",
            "org.thoughtcrime.securesms", "com.facebook.orca", "com.instagram.android", "com.viber.voip",
            "com.google.android.gm", "com.microsoft.office.outlook", "com.discord", "com.snapchat.android",
            "com.google.android.apps.dynamite", "com.slack", "com.microsoft.teams",
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (t: Throwable) {
            // לעולם לא להפיל את השירות – אחרת המערכת מנתקת אותו
            Log.w(TAG, "Failed to handle notification from ${sbn.packageName}", t)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val notification = sbn.notification ?: return
        if (sbn.isOngoing) return
        // התראת "סיכום קבוצה" חוזרת על התוכן של ההתראות שבתוכה – מדלגים כדי לא לספור פעמיים
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val store = SpendStore.get(this)
        val content = NotificationText.extract(notification)
        val listed = store.rules().matches(pkg, content.title)
        if (!listed && pkg in PRIVATE_APPS) return

        val parsed = AmountParser.parse(content.fullText)
        val amount: Amount = when (parsed) {
            is AmountParser.Result.Expense -> parsed.amount
            is AmountParser.Result.Excluded -> parsed.amount
            AmountParser.Result.NoAmount -> return // רוב ההתראות בעולם נגמרות כאן
        }

        fun log(status: DetectionStatus, detail: String = "") = store.appendLog(
            DetectionEntry(
                timeMillis = System.currentTimeMillis(),
                packageName = pkg,
                appLabel = appLabel(pkg),
                title = content.title,
                amount = amount,
                status = status,
                detail = detail,
            )
        )

        if (!listed) {
            log(DetectionStatus.NOT_LISTED)
            return
        }
        if (parsed !is AmountParser.Result.Expense) {
            if (parsed is AmountParser.Result.Excluded) log(DetectionStatus.EXCLUDED, parsed.reason)
            return
        }

        val home = store.homeCurrency
        val budget = store.budgetMinor

        // ----- מתג "להציג רק הוצאות יומיות": עסקה בתשלומים לא נספרת בכלל -----
        if (parsed.installments != null && store.excludeInstallments) {
            val fp = "${sbn.key}|${notification.`when`}|${amount.key}"
            val result = store.excludeTransaction(amount, fp)
            log(DetectionStatus.EXCLUDED, getString(R.string.log_installments_excluded))
            if (result is LedgerResult.Reversed) {
                // Google Wallet (בלי פרטי תשלומים) כבר ספר את הקנייה – מראים את הסכום המתוקן
                OverlayManager.show(
                    this,
                    OverlayInfo(
                        lastTx = amount, lastTxHomeMinor = null,
                        installments = parsed.installments, perInstallment = parsed.perInstallment,
                        totalMinor = result.state.totalMinor, budgetMinor = budget, homeCurrency = home,
                        justCrossed = false, reversed = true,
                    )
                )
            }
            return
        }

        val conversion = store.convertToHome(amount)
        if (conversion == null) {
            // מטבע בלי שום שער ידוע: מראים את העסקה, אבל לא מוסיפים לסכום היומי
            log(DetectionStatus.NO_RATE, amount.currency)
            val today = store.todaySnapshot()
            OverlayManager.show(
                this,
                OverlayInfo(
                    lastTx = amount, lastTxHomeMinor = null, notCounted = true,
                    installments = parsed.installments, perInstallment = parsed.perInstallment,
                    totalMinor = today.totalMinor, budgetMinor = budget, homeCurrency = home, justCrossed = false,
                )
            )
            return
        }

        // טביעה: אותה התראה (מפתח + זמן יצירה) עם אותו סכום = עדכון של אותה עסקה, לא עסקה חדשה
        val fingerprint = "${sbn.key}|${notification.`when`}|${amount.key}"

        when (val result = store.registerTransaction(amount, conversion.homeMinor, fingerprint)) {
            is LedgerResult.Counted -> {
                log(DetectionStatus.COUNTED, parsed.installments?.let { "$it תשלומים" } ?: "")
                val total = result.state.totalMinor
                OverlayManager.show(
                    this,
                    OverlayInfo(
                        lastTx = amount,
                        lastTxHomeMinor = if (amount.currency != home) conversion.homeMinor else null,
                        rateIsOnline = conversion.online,
                        installments = parsed.installments,
                        perInstallment = parsed.perInstallment,
                        totalMinor = total,
                        budgetMinor = budget,
                        homeCurrency = home,
                        justCrossed = budget > 0 && result.previousTotalMinor <= budget && total > budget,
                    )
                )
            }
            is LedgerResult.Duplicate -> log(DetectionStatus.DUPLICATE, result.reason)
            is LedgerResult.Excluded, is LedgerResult.Reversed -> Unit // לא קורה ב-register
        }
    }

    /** שם האפליקציה ששלחה את ההתראה (ליומן בלבד) */
    @Suppress("DEPRECATION")
    private fun appLabel(pkg: String): String {
        return try {
            val info: ApplicationInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                packageManager.getApplicationInfo(pkg, 0)
            }
            packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }
    }
}
