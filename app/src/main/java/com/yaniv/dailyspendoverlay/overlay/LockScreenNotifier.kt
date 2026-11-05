package com.yaniv.dailyspendoverlay.overlay

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yaniv.dailyspendoverlay.MainActivity
import com.yaniv.dailyspendoverlay.R
import com.yaniv.dailyspendoverlay.core.BudgetStatus
import com.yaniv.dailyspendoverlay.core.Money
import com.yaniv.dailyspendoverlay.data.SpendStore

/**
 * התראה קצרה במסך הנעילה.
 *
 * חלונית צפה (OverlayManager) לא יכולה להופיע מעל מסך הנעילה – אנדרואיד מסתיר אותה שם.
 * לכן, כשתשלום מזוהה והטלפון נעול, מפרסמים התראה אחת (תמיד באותו מזהה, כך שהיא מתעדכנת במקום)
 * עם הסכום היומי והעסקה האחרונה.
 *
 *  • נשלחת רק בתגובה לתשלום שזוהה, ורק כשהטלפון נעול. כשהוא פתוח – החלונית הצפה מטפלת.
 *  • נעלמת לבד אחרי אותו זמן של החלונית (VISIBLE_MS): ההסרה נעשית ע"י המערכת (setTimeoutAfter),
 *    לא ע"י טיימר באפליקציה, ולכן אין שום עבודה ברקע.
 *  • שקטה (בלי צליל ורטט), מקומית בלבד (לא עוברת לשעון חכם) ולא מדליקה את המסך.
 *  • דורשת הרשאת התראות (אנדרואיד 13+). בלי ההרשאה פשוט לא מציגים כלום.
 */
object LockScreenNotifier {

    private const val TAG = "DailySpend"
    private const val CHANNEL_ID = "lock_screen_total"
    private const val NOTIFICATION_ID = 1001

    /** האם אפשר להציג התראות (ההרשאה באנדרואיד 13+ וגם לא נחסם בהגדרות האפליקציה) */
    fun canPost(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled()
    }

    fun show(context: Context, info: OverlayInfo) {
        try {
            val app = context.applicationContext
            if (info.isPreview || info.lastTx == null) return
            if (!SpendStore.get(app).showOnLockScreen) return
            val keyguard = app.getSystemService(KeyguardManager::class.java) ?: return
            if (!keyguard.isKeyguardLocked) return // הטלפון פתוח – החלונית הצפה מטפלת בזה
            val manager = app.getSystemService(NotificationManager::class.java) ?: return
            if (!manager.areNotificationsEnabled()) return
            manager.createNotificationChannel(buildChannel(app))
            manager.notify(NOTIFICATION_ID, buildNotification(app, info))
        } catch (t: Throwable) {
            // לעולם לא להפיל את השירות בגלל התראה
            Log.w(TAG, "Lock screen notification failed", t)
        }
    }

    private fun buildChannel(ctx: Context): NotificationChannel =
        // חשיבות "רגילה" בלי צליל: מופיעה ברשימה הראשית במסך הנעילה (לא רק בין ההתראות ה"שקטות")
        NotificationChannel(CHANNEL_ID, ctx.getString(R.string.lock_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.lock_channel_desc)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

    private fun buildNotification(ctx: Context, info: OverlayInfo): Notification {
        val status = BudgetStatus(info.totalMinor, info.budgetMinor)
        val total = Money.format(info.totalMinor, info.homeCurrency)
        val title = ctx.getString(if (status.isOver) R.string.lock_title_over else R.string.lock_title, total)

        val lines = ArrayList<String>()
        info.lastTx?.let { lines += ctx.getString(R.string.overlay_last_tx, Money.format(it)) }
        lines += OverlayManager.detailLines(ctx, info)
        if (status.hasBudget) lines += OverlayManager.budgetLine(ctx, status, info.homeCurrency)

        val openApp = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_cash)
            .setContentTitle(title)
            .setContentText(lines.joinToString(" · "))
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setTimeoutAfter(OverlayManager.VISIBLE_MS)
            .build()
    }
}
