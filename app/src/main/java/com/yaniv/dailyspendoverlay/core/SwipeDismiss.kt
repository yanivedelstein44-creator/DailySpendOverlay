package com.yaniv.dailyspendoverlay.core

import kotlin.math.abs

/**
 * החלקה ימינה סוגרת את הבועה (כשהמתג "הבועה נשארת עד שמחליקים אותה" דלוק – ברירת המחדל).
 * רק ההחלטות, בלי אנדרואיד, כדי שאפשר לבדוק אותן. הגרירה עצמה: overlay/SwipeTouch.
 *
 * ימינה = ימינה על המסך (x עולה), בלי קשר לכיוון השפה.
 */
object SwipeDismiss {

    /** כמה מרוחב הבועה צריך לגרור ימינה (בלי תנועה מהירה) כדי לסגור */
    const val DISTANCE_FRACTION = 0.35f

    /** תנועה מהירה ימינה (dp לשנייה) סוגרת גם בגרירה קצרה */
    const val FLING_DP_PER_SEC = 800f

    /** גם בתנועה מהירה – לפחות כמה dp ימינה, שנגיעה רועדת לא תסגור */
    const val FLING_MIN_DP = 24f

    /** שמאלה הבועה זזה רק מעט ("גומי") וחוזרת למקום */
    const val LEFT_RESISTANCE = 0.2f

    /** הכי שקופה שהבועה נהיית תוך כדי גרירה */
    const val MIN_ALPHA = 0.15f

    enum class Gesture {
        /** עדיין לא זזה מספיק כדי לדעת (אם משחררים עכשיו – זו נגיעה) */
        UNDECIDED,
        /** גרירה לצדדים – הבועה זזה עם האצבע */
        HORIZONTAL,
        /** תנועה למעלה/למטה – לא החלקה ולא נגיעה; הבועה לא זזה */
        VERTICAL,
    }

    /** מה המשתמש עושה, לפי התזוזה מנקודת הנגיעה (slop = התזוזה שעדיין נחשבת נגיעה במקום) */
    fun classify(dx: Float, dy: Float, slop: Float): Gesture = when {
        abs(dx) > slop && abs(dx) >= abs(dy) -> Gesture.HORIZONTAL
        abs(dy) > slop -> Gesture.VERTICAL
        else -> Gesture.UNDECIDED
    }

    /** כמה הבועה זזה בפועל כשהאצבע זזה dx (ימינה חיובי) */
    fun offsetFor(dx: Float): Float = if (dx >= 0f) dx else dx * LEFT_RESISTANCE

    /** שקיפות תוך כדי גרירה ימינה: הבועה נעלמת בהדרגה; שמאלה נשארת אטומה */
    fun alphaFor(offset: Float, width: Float): Float {
        if (offset <= 0f || width <= 0f) return 1f
        return (1f - 0.85f * offset / width).coerceIn(MIN_ALPHA, 1f)
    }

    /**
     * לסגור כשמשחררים?
     * @param dx כמה האצבע זזה ימינה מנקודת הנגיעה (בפיקסלים)
     * @param velocityX מהירות ברגע השחרור (פיקסלים לשנייה, ימינה חיובי)
     * @param width רוחב הבועה בפיקסלים
     * @param density פיקסלים ל-dp
     */
    fun shouldDismiss(dx: Float, velocityX: Float, width: Float, density: Float): Boolean {
        if (dx <= 0f || width <= 0f) return false
        val fling = FLING_DP_PER_SEC * density
        if (velocityX <= -fling) return false // גררה ימינה ואז "זרקה" חזרה – משאירים
        if (dx >= width * DISTANCE_FRACTION) return true
        return velocityX >= fling && dx >= FLING_MIN_DP * density
    }
}
