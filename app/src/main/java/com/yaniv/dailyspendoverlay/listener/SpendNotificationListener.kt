package com.yaniv.dailyspendoverlay.listener

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.yaniv.dailyspendoverlay.R
import com.yaniv.dailyspendoverlay.core.Amount
import com.yaniv.dailyspendoverlay.core.AmountParser
import com.yaniv.dailyspendoverlay.core.WorkTime
import com.yaniv.dailyspendoverlay.core.DetectionEntry
import com.yaniv.dailyspendoverlay.core.DeviceEvents
import com.yaniv.dailyspendoverlay.core.DetectionStatus
import com.yaniv.dailyspendoverlay.core.LedgerResult
import com.yaniv.dailyspendoverlay.core.SourceCategory
import com.yaniv.dailyspendoverlay.data.SpendStore
import com.yaniv.dailyspendoverlay.overlay.OverlayInfo
import com.yaniv.dailyspendoverlay.overlay.LockScreenBubbleActivity
import com.yaniv.dailyspendoverlay.overlay.LockScreenNotifier
import com.yaniv.dailyspendoverlay.overlay.OverlayManager
import com.yaniv.dailyspendoverlay.overlay.ShowResult

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

    private var unlockReceiver: BroadcastReceiver? = null

    /**
     * "סיכום בפתיחת הטלפון": האזנה לאירוע שחרור הנעילה (ACTION_USER_PRESENT) בלבד.
     * אין טיימר ואין שירות נוסף – המערכת מעירה את הקוד רק ברגע הפתיחה, והוא מסתיים מיד.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        registerUnlockReceiver()
        warmUp()
        catchUpMissed()
    }

    /**
     * הכנה מראש, פעם אחת בכל חיבור: ה-Regex, ההגדרות והחלונית עצמה.
     * כך בתשלום הבא נשאר רק לזהות את הסכום ולהציג – והבועה מופיעה מהר יותר אחרי ההתראה.
     */
    private fun warmUp() {
        try {
            AmountParser.parse("₪1")
            SpendStore.get(this).rules()
            OverlayManager.warmUp(this)
        } catch (t: Throwable) {
            Log.w(TAG, "warm-up failed", t)
        }
    }

    /**
     * השלמה: אם הטלפון עצר את הקליטה (למשל "מצב שינה" של סמסונג), תשלום שהגיע בינתיים לא נספר.
     * כשהקליטה חוזרת עוברים פעם אחת על ההתראות שעדיין מוצגות מהשעה האחרונה, וסופרים רק את מה שלא טופל.
     * רץ רק בתגובה לחיבור (אירוע של המערכת) – בלי טיימר ובלי סריקה מחזורית. בלי חלונית: זה כבר באיחור.
     */
    private fun catchUpMissed() {
        val now = System.currentTimeMillis()
        val active = try {
            activeNotifications
        } catch (t: Throwable) {
            Log.w(TAG, "catch-up: no active notifications", t)
            null
        } ?: return
        active.filter { DeviceEvents.isCatchUpCandidate(it.postTime, now) }
            .sortedBy { it.postTime }
            .forEach { sbn ->
                try {
                    handle(sbn, catchUp = true)
                } catch (t: Throwable) {
                    Log.w(TAG, "catch-up failed for ${sbn.packageName}", t)
                }
            }
    }

    override fun onListenerDisconnected() {
        unregisterUnlockReceiver()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        unregisterUnlockReceiver()
        super.onDestroy()
    }

    private fun registerUnlockReceiver() {
        if (unlockReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    val store = SpendStore.get(context)
                    val today = store.todaySnapshot()
                    val pendingAt = store.pendingUnlockMillis
                    if (pendingAt > 0L) store.pendingUnlockMillis = 0L
                    val hasPending = DeviceEvents.showPendingOnUnlock(
                        pendingAt, LockScreenBubbleActivity.lastShownAt, System.currentTimeMillis(),
                    )
                    Log.i(TAG, "unlock: pending=${pendingAt > 0L} show=$hasPending")
                    if (today.totalMinor <= 0L) return // עוד לא הוצאת היום – אין מה להראות

                    val home = store.homeCurrency
                    if (hasPending) {
                        // תשלום שזוהה כשהטלפון היה נעול: החלונית שלו מופיעה עכשיו, ברגע שפותחים את הנעילה
                        LockScreenNotifier.cancel(context)
                        val last = today.lastTx
                        OverlayManager.show(
                            context,
                            OverlayInfo(
                                lastTx = last,
                                lastTxHomeMinor = if (last != null && last.currency != home) today.lastTxHomeMinor else null,
                                totalMinor = today.totalMinor, budgetMinor = store.budgetMinor,
                                homeCurrency = home, justCrossed = false,
                                workText = if (last != null) WorkTime.describe(today.lastTxHomeMinor, store.hourlyWageMinor) else null,
                                previousTotalMinor = if (last != null) today.totalMinor - today.lastTxHomeMinor else null,
                            ),
                            ignoreLock = true,
                        )
                    } else if (store.showOnUnlock) {
                        OverlayManager.show(
                            context,
                            OverlayInfo(
                                lastTx = null, lastTxHomeMinor = null,
                                totalMinor = today.totalMinor, budgetMinor = store.budgetMinor,
                                homeCurrency = home, justCrossed = false,
                            ),
                            ignoreLock = true,
                        )
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to show unlock summary", t)
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // EXPORTED בכוונה: את USER_PRESENT שולח ממשק המערכת (SystemUI), לא "המערכת" עצמה,
            // ו-NOT_EXPORTED חוסם אותו (נמצא בבדיקת האמולטור). זה Broadcast מוגן – אפליקציה אחרת לא יכולה לזייף אותו.
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        unlockReceiver = receiver
    }

    private fun unregisterUnlockReceiver() {
        val receiver = unlockReceiver ?: return
        unlockReceiver = null
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (t: Throwable) {
            // לעולם לא להפיל את השירות – אחרת המערכת מנתקת אותו
            Log.w(TAG, "Failed to handle notification from ${sbn.packageName}", t)
        }
    }

    /** @param catchUp השלמה בחיבור מחדש: סופרים רק מה שלא טופל, בלי חלונית ובלי רישום של מה שלא נספר */
    private fun handle(sbn: StatusBarNotification, catchUp: Boolean = false) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        val notification = sbn.notification ?: return
        if (sbn.isOngoing) return
        // התראת "סיכום קבוצה" חוזרת על התוכן של ההתראות שבתוכה – מדלגים כדי לא לספור פעמיים
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val store = SpendStore.get(this)
        val startedAt = SystemClock.uptimeMillis()
        val arrivedAfterMs = System.currentTimeMillis() - sbn.postTime
        val lateText = DeviceEvents.lateText(arrivedAfterMs)
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
            // מקור שלא ברשימה אף פעם לא נספר. נרשם ביומן רק אם המשתמש ביקש (כדי לגלות מקורות חדשים)
            if (store.logUnlisted && !catchUp) log(DetectionStatus.NOT_LISTED)
            return
        }
        if (!SourceCategory.isCounted(SourceCategory.of(pkg, content.title)) { store.isSourceEnabled(it) }) {
            if (!catchUp) log(DetectionStatus.EXCLUDED, getString(R.string.log_source_off))
            return
        }
        if (parsed !is AmountParser.Result.Expense) {
            if (parsed is AmountParser.Result.Excluded && !catchUp) log(DetectionStatus.EXCLUDED, parsed.reason)
            return
        }

        // טביעה: אותה התראה (מפתח + זמן יצירה) עם אותו סכום = עדכון של אותה עסקה, לא עסקה חדשה
        val fingerprint = "${sbn.key}|${notification.`when`}|${amount.key}"
        // השלמה: התראה שכבר טופלה (נספרה / כפילות / הוחרגה) לא נבדקת שוב
        if (catchUp && store.isKnownFingerprint(fingerprint)) return

        val home = store.homeCurrency
        val budget = store.budgetMinor

        // ----- מתג "להציג רק הוצאות יומיות": עסקה בתשלומים לא נספרת בכלל -----
        if (parsed.installments != null && store.excludeInstallments) {
            val result = store.excludeTransaction(amount, fingerprint)
            log(DetectionStatus.EXCLUDED, getString(R.string.log_installments_excluded))
            if (result is LedgerResult.Reversed && !catchUp) {
                // Google Wallet (בלי פרטי תשלומים) כבר ספר את הקנייה – מראים את הסכום המתוקן
                announce(
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
            if (catchUp) return // לא נספר בכל מקרה, ובאיחור אין טעם בחלונית
            // מטבע בלי שום שער ידוע: מראים את העסקה, אבל לא מוסיפים לסכום היומי
            log(DetectionStatus.NO_RATE, amount.currency)
            val today = store.todaySnapshot()
            announce(
                OverlayInfo(
                    lastTx = amount, lastTxHomeMinor = null, notCounted = true,
                    installments = parsed.installments, perInstallment = parsed.perInstallment,
                    totalMinor = today.totalMinor, budgetMinor = budget, homeCurrency = home, justCrossed = false,
                )
            )
            return
        }

        when (val result = store.registerTransaction(amount, conversion.homeMinor, fingerprint)) {
            is LedgerResult.Counted -> {
                val total = result.state.totalMinor
                val announced = if (catchUp) null else announce(
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
                        workText = WorkTime.describe(conversion.homeMinor, store.hourlyWageMinor),
                        previousTotalMinor = result.previousTotalMinor,
                    )
                )
                val shownInMs = SystemClock.uptimeMillis() - startedAt
                // ביומן רואים מה קרה עם החלונית, תוך כמה זמן ההתראה הגיעה לאפליקציה ותוך כמה הבועה הוצגה
                val detail = listOfNotNull(
                    parsed.installments?.let { "$it תשלומים" },
                    announced?.text ?: getString(R.string.diag_catch_up),
                    lateText?.let { getString(R.string.diag_late, it) }
                        ?: getString(R.string.diag_arrived, DeviceEvents.secondsText(arrivedAfterMs)),
                    if (announced?.overlayShown == true) {
                        getString(R.string.diag_shown_in, DeviceEvents.secondsText(shownInMs))
                    } else null,
                ).joinToString(" · ")
                log(DetectionStatus.COUNTED, detail)
            }
            is LedgerResult.Duplicate -> if (!catchUp) log(DetectionStatus.DUPLICATE, result.reason)
            is LedgerResult.Excluded, is LedgerResult.Reversed -> Unit // לא קורה ב-register
        }
    }

    /**
     * תשלום זוהה.
     *  • טלפון פתוח → חלונית צפה.
     *  • טלפון נעול / מסך כבוי → התראה קצרה במסך הנעילה, והחלונית תופיע כשפותחים את הנעילה.
     * מחזיר טקסט קצר לאבחון ביומן, והאם החלונית הוצגה עכשיו.
     */
    private fun announce(info: OverlayInfo): Announced {
        val store = SpendStore.get(this)
        return when (OverlayManager.show(this, info)) {
            ShowResult.SHOWN -> Announced(getString(R.string.diag_overlay_ok), overlayShown = true)
            ShowResult.NO_PERMISSION -> Announced(getString(R.string.diag_overlay_no_permission))
            ShowResult.FAILED -> Announced(getString(R.string.diag_overlay_failed))
            ShowResult.DEFERRED -> Announced(
                if (!store.showOnLockScreen) {
                    getString(R.string.diag_overlay_locked_off)
                } else {
                    if (!info.notCounted) store.pendingUnlockMillis = System.currentTimeMillis()
                    val lock = when (LockScreenNotifier.show(this, info)) {
                        LockScreenNotifier.Result.BUBBLE -> getString(R.string.diag_lock_bubble)
                        LockScreenNotifier.Result.POSTED -> getString(R.string.diag_lock_ok)
                        LockScreenNotifier.Result.NO_PERMISSION -> getString(R.string.diag_lock_no_permission)
                        LockScreenNotifier.Result.FAILED -> getString(R.string.diag_lock_failed)
                        LockScreenNotifier.Result.SKIPPED -> null
                    }
                    listOfNotNull(getString(R.string.diag_overlay_deferred), lock).joinToString(" · ")
                }
            )
        }
    }

    private class Announced(val text: String, val overlayShown: Boolean = false)

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
