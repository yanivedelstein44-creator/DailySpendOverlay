package com.yaniv.dailyspendoverlay.core

/**
 * "כמה זה שווה בזמן עבודה": סכום חלקי שכר שעתי. לוגיקה טהורה, בלי תלות באנדרואיד.
 * בלי שכר שעתי מוגדר (0) – אין חישוב.
 */
object WorkTime {

    /** דקות עבודה שהסכום שווה להן (מעוגל), או null אם אין שכר שעתי / אין סכום */
    fun minutes(amountMinor: Long, hourlyWageMinor: Long): Long? {
        if (hourlyWageMinor <= 0 || amountMinor <= 0) return null
        return Math.round(amountMinor * 60.0 / hourlyWageMinor)
    }

    /** "25 דקות" / "דקה" / "פחות מדקה" / "שעה ו-20 דקות" / "2 שעות" / "3 שעות ו-5 דקות" */
    fun format(minutes: Long): String {
        if (minutes <= 0) return "פחות מדקה"
        val h = minutes / 60
        val m = minutes % 60
        val hours = when (h) {
            0L -> ""
            1L -> "שעה"
            2L -> "שעתיים"
            else -> "$h שעות"
        }
        val mins = when (m) {
            0L -> ""
            1L -> "דקה"
            else -> "$m דקות"
        }
        return when {
            hours.isEmpty() -> mins
            mins.isEmpty() -> hours
            // לפני מספר כותבים "ו-20 דקות", לפני מילה "ודקה"
            else -> if (m == 1L) "$hours ו$mins" else "$hours ו-$mins"
        }
    }

    /** הטקסט להצגה, או null אם אין שכר שעתי מוגדר */
    fun describe(amountMinor: Long, hourlyWageMinor: Long): String? =
        minutes(amountMinor, hourlyWageMinor)?.let { format(it) }
}
