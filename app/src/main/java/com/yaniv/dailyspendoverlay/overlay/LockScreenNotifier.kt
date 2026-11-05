package com.yaniv.dailyspendoverlay.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.yaniv.dailyspendoverlay.MainActivity
import com.yaniv.dailyspendoverlay.R
import com.yaniv.dailyspendoverlay.core.BudgetStatus
import com.yaniv.dailyspendoverlay.core.Money
import com.yaniv.dailyspendoverlay.data.SpendStore

/**
 * מה מוצג כשהטלפון נעול (או המסך כבוי) בזמן תשלום.
 *
 * חלונית צפה (OverlayManager) לא יכולה להופיע מעל מסך הנעילה – אנדרואיד מסתיר אותה שם.
 * לכן שולחים התראה אחת (תמיד באותו מזהה, כך שהיא מתעדכנת במקום):
 *  • אם מותר "התראה במסך מלא" – המערכת מדליקה את המסך ופותחת מעל הנעילה את LockScreenBubbleActivity:
 *    הבועה עצמה (כרטיס, או מסך מלא לפי המתג), ל-4 שניות. כמו שעון מעורר או שיחה נכנסת.
 *  • אחרת – התראה רגילה ושקטה במסך הנעילה, שנעלמת אחרי אותן 4 שניות.
 * בשני המקרים החלונית מופיעה שוב כשפותחים את הנעילה (ראה SpendNotificationListener).
 *
 *  • נשלחת רק בתגובה לתשלום שזוהה. אין טיימר באפליקציה: ההתראה נעלמת דרך setTimeoutAfter של המערכת.
 *  • בלי צליל ורטט, מקומית בלבד (לא עוברת לשעון חכם).
 *  • דורשת הרשאת התראות (אנדרואיד 13+). בלי ההרשאה לא מציגים כלום.
 */
object LockScreenNotifier {

    private const val TAG = "DailySpend"
    private const val CHANNEL_ID = "lock_screen_total"
    private const val BUBBLE_CHANNEL_ID = "lock_screen_bubble"
    private const val NOTIFICATION_ID = 1001

    /** מה להציג בבועה שמעל הנעילה (נלקח פעם אחת ע"י LockScreenBubbleActivity) */
    @Volatile
    private var bubbleInfo: OverlayInfo? = null

    fun takeBubbleInfo(): OverlayInfo? {
        val info = bubbleInfo
        bubbleInfo = null
        return info
    }

    enum class Result { BUBBLE, POSTED, NO_PERMISSION, FAILED, SKIPPED }

    /** האם אפשר להציג התראות (ההרשאה באנדרואיד 13+ וגם לא נחסם בהגדרות האפליקציה) */
    fun canPost(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled()
    }

    /** "התראות במסך מלא": באנדרואיד 14+ המשתמש מאשר בהגדרות; לפני כן מותר תמיד */
    fun canUseFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }

    /**
     * מי שקורא אחראי לקרוא רק כשהחלונית הצפה לא יכולה להיראות (טלפון נעול / מסך כבוי).
     */
    fun show(context: Context, info: OverlayInfo): Result {
        return try {
            val app = context.applicationContext
            if (info.isPreview || info.lastTx == null) return Result.SKIPPED
            if (!SpendStore.get(app).showOnLockScreen) return Result.SKIPPED
            val manager = app.getSystemService(NotificationManager::class.java) ?: return Result.FAILED
            if (!manager.areNotificationsEnabled()) return Result.NO_PERMISSION
            if (canUseFullScreen(app)) {
                manager.createNotificationChannel(buildChannel(app, bubble = true))
                bubbleInfo = info
                manager.notify(NOTIFICATION_ID, buildNotification(app, info, bubble = true))
                Result.BUBBLE
            } else {
                manager.createNotificationChannel(buildChannel(app, bubble = false))
                manager.notify(NOTIFICATION_ID, buildNotification(app, info, bubble = false))
                Result.POSTED
            }
        } catch (t: Throwable) {
            // לעולם לא להפיל את השירות בגלל התראה
            Log.w(TAG, "Lock screen notification failed", t)
            Result.FAILED
        }
    }

    /** ברגע שהבועה מוצגת (מעל הנעילה או אחרי הפתיחה) ההתראה כבר מיותרת */
    fun cancel(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to cancel lock screen notification", t)
        }
    }

    private fun buildChannel(ctx: Context, bubble: Boolean): NotificationChannel =
        if (bubble) {
            // "התראה במסך מלא" נפתחת רק מערוץ בחשיבות גבוהה. בלי צליל ורטט.
            NotificationChannel(
                BUBBLE_CHANNEL_ID, ctx.getString(R.string.lock_bubble_channel_name), NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = ctx.getString(R.string.lock_bubble_channel_desc)
                quiet()
            }
        } else {
            // חשיבות "רגילה" בלי צליל: מופיעה ברשימה הראשית במסך הנעילה (לא רק בין ההתראות ה"שקטות")
            NotificationChannel(
                CHANNEL_ID, ctx.getString(R.string.lock_channel_name), NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = ctx.getString(R.string.lock_channel_desc)
                quiet()
            }
        }

    private fun NotificationChannel.quiet() {
        setSound(null, null)
        enableVibration(false)
        enableLights(false)
        setShowBadge(false)
        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }

    private fun buildNotification(ctx: Context, info: OverlayInfo, bubble: Boolean): Notification {
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

        val builder = Notification.Builder(ctx, if (bubble) BUBBLE_CHANNEL_ID else CHANNEL_ID)
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
        if (bubble) {
            val bubbleIntent = PendingIntent.getActivity(
                ctx, 1,
                Intent(ctx, LockScreenBubbleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setFullScreenIntent(bubbleIntent, true)
        }
        return builder.build()
    }
}
