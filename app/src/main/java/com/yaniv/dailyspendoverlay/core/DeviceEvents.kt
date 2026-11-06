package com.yaniv.dailyspendoverlay.core

import java.time.Instant
import java.time.ZoneId

/**
 * לוגיקה טהורה (בלי תלות באנדרואיד) לשני מצבים מיוחדים של תשלום:
 *  • תשלום שזוהה כשהטלפון נעול – החלונית נדחית עד שחרור הנעילה.
 *  • התראה שהגיעה לאפליקציה באיחור – אבחון בלבד, מוצג ביומן.
 *  • השלמה: כשהקליטה חוזרת אחרי שהטלפון עצר אותה – אילו התראות עוד רלוונטיות.
 */
object DeviceEvents {

    /** תשלום שנדחה לפתיחת הנעילה מוצג רק אם הנעילה נפתחה תוך שעה; אחרת הוא כבר לא רלוונטי */
    const val PENDING_MAX_AGE_MS = 60L * 60 * 1000

    /** מתחת לסף הזה לא מציינים איחור (עיכוב רגיל של המערכת) */
    const val LATE_THRESHOLD_SEC = 20L

    /** אם הבועה הוצגה מעל מסך הנעילה ממש עכשיו, לא מציגים אותה שוב מיד בפתיחה */
    const val RECENT_LOCK_BUBBLE_MS = 15_000L

    /** השלמה בחיבור מחדש: רק התראות מהשעה האחרונה */
    const val CATCH_UP_WINDOW_MS = 60L * 60 * 1000

    /**
     * התראה שעדיין מוצגת כשהקליטה חוזרת – לבדוק אותה שוב?
     * רק מהשעה האחרונה, ורק מאותו יום (תשלום של אתמול לא נכנס לסכום של היום).
     */
    fun isCatchUpCandidate(postTimeMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val age = nowMillis - postTimeMillis
        if (postTimeMillis <= 0 || age !in 0..CATCH_UP_WINDOW_MS) return false
        return Instant.ofEpochMilli(postTimeMillis).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    }

    fun isPendingFresh(pendingAtMillis: Long, nowMillis: Long): Boolean =
        pendingAtMillis > 0 && (nowMillis - pendingAtMillis) in 0..PENDING_MAX_AGE_MS

    /** להציג בפתיחת הנעילה את התשלום שנדחה? */
    fun showPendingOnUnlock(pendingAtMillis: Long, lockBubbleShownAtMillis: Long, nowMillis: Long): Boolean {
        if (!isPendingFresh(pendingAtMillis, nowMillis)) return false
        val bubbleJustShown = lockBubbleShownAtMillis >= pendingAtMillis &&
            (nowMillis - lockBubbleShownAtMillis) in 0 until RECENT_LOCK_BUBBLE_MS
        return !bubbleJustShown
    }

    /** "45 שניות" / "7 דקות" / "שעה" – או null אם ההתראה לא הגיעה באיחור משמעותי */
    fun lateText(lateMillis: Long): String? {
        val seconds = lateMillis / 1000
        if (seconds < LATE_THRESHOLD_SEC) return null
        if (seconds < 120) return "$seconds שניות"
        val minutes = (seconds + 30) / 60
        if (minutes < 60) return "$minutes דקות"
        val hours = minutes / 60
        return if (hours == 1L) "שעה" else "$hours שעות"
    }
}
