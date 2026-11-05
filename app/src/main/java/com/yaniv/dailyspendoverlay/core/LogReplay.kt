package com.yaniv.dailyspendoverlay.core

/**
 * "שחזר מהיומן": חישוב מחדש של הסכום של היום מתוך יומן הזיהוי.
 * משמש כשאין גיבוי של איפוס (למשל איפוס שנעשה בגרסה ישנה, לפני שהגיבוי נוסף).
 *
 * התשלומים "מושמעים" מחדש לפי סדר הזמן, עם הכללים של היום:
 *  • אותו סכום תוך 10 דקות = כפילות, נספר פעם אחת.
 *  • "עסקה בתשלומים הוחרגה" מבטלת את אותו סכום שנספר ממש לפני (כמו שקורה בזמן אמת).
 */
object LogReplay {

    data class Item(
        val timeMillis: Long,
        val amount: Amount,
        /** הסכום במטבע הראשי (לרשומת החרגה לא משנה) */
        val homeMinor: Long,
        /** true = רשומה של עסקה בתשלומים שהוחרגה */
        val installmentExclusion: Boolean = false,
    )

    fun rebuild(items: List<Item>, today: String): LedgerState {
        var state = LedgerState(day = today)
        for (item in items.sortedBy { it.timeMillis }) {
            if (item.amount.minor <= 0) continue
            state = if (item.installmentExclusion) {
                DailyLedger.exclude(state, item.amount, null, item.timeMillis, today).state
            } else {
                DailyLedger.register(state, item.amount, item.homeMinor.coerceAtLeast(0), null, item.timeMillis, today).state
            }
        }
        return state
    }
}
