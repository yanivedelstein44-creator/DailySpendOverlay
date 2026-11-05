package com.yaniv.dailyspendoverlay.core

/**
 * סכום שנראה לאחרונה (במטבע המקורי) – בשביל מניעת כפילויות.
 * @param homeMinor כמה נוסף בגללו לסכום היומי
 * @param counted   false = עסקה שהוחרגה (למשל עסקה בתשלומים כשהמתג "לא לכלול" דלוק)
 */
data class RecentAmount(
    val key: String,
    val atMillis: Long,
    val homeMinor: Long = 0,
    val counted: Boolean = true,
)

/** מצב היום. נשמר ב-SharedPreferences (ראה data/SpendStore). */
data class LedgerState(
    /** התאריך שאליו שייך הסכום, בפורמט 2026-11-04 */
    val day: String = "",
    /** הסכום המצטבר של היום, במטבע הראשי (אגורות/סנטים) */
    val totalMinor: Long = 0,
    val count: Int = 0,
    /** העסקה האחרונה במטבע המקורי שלה (למשל $12.50) */
    val lastTx: Amount? = null,
    /** העסקה האחרונה אחרי המרה למטבע הראשי */
    val lastTxHomeMinor: Long = 0,
    /** סכומים מהשעה האחרונה (חלון הכפילויות) */
    val recentAmounts: List<RecentAmount> = emptyList(),
    /** טביעות של התראות שכבר טופלו (מזהה התראה + זמן + סכום) */
    val fingerprints: List<String> = emptyList(),
)

sealed interface LedgerResult {
    val state: LedgerState

    /** העסקה נוספה לסכום היומי */
    data class Counted(override val state: LedgerState, val previousTotalMinor: Long) : LedgerResult

    /** כפילות של עסקה שכבר טופלה – לא נוסף כלום */
    data class Duplicate(override val state: LedgerState, val reason: String) : LedgerResult

    /** העסקה הוחרגה (לא נספרת) */
    data class Excluded(override val state: LedgerState) : LedgerResult

    /** העסקה הוחרגה, ואותה קנייה כבר נספרה שניות קודם ממקור אחר – הספירה בוטלה */
    data class Reversed(override val state: LedgerState, val removedHomeMinor: Long) : LedgerResult
}

/** מצב התקציב לתצוגה (חלונית + מסך ראשי) */
data class BudgetStatus(val totalMinor: Long, val budgetMinor: Long) {
    /** תקציב 0 = לא הוגדר תקציב: אין חריגה, אין פס התקדמות */
    val hasBudget: Boolean get() = budgetMinor > 0

    /** "עובר את התקציב" = גדול ממש מהתקציב. בדיוק על התקציב עדיין רגוע. בלי תקציב – אף פעם לא. */
    val isOver: Boolean get() = hasBudget && totalMinor > budgetMinor

    /** חיובי = כמה נשאר; שלילי = בכמה עברנו */
    val remainingMinor: Long get() = budgetMinor - totalMinor

    val percent: Int
        get() = if (budgetMinor <= 0) 0 else ((totalMinor * 100) / budgetMinor).coerceIn(0, 999).toInt()
}

/**
 * הלוגיקה הטהורה של החישוב היומי (בלי אנדרואיד – נבדקת בבדיקות יחידה):
 *  • יום חדש → הסכום המצטבר מתאפס ל-0 לפני הוספת העסקה.
 *  • אותו סכום באותו מטבע שוב תוך שעה → כפילות
 *    (למשל Google Wallet + אפליקציית האשראי על אותה קנייה).
 *    החלון "מחליק": כל הופעה חוזרת מאריכה אותו, כך שגם הודעה שלישית (SMS) לא נספרת.
 *  • אותה התראה שעודכנה/פורסמה מחדש (אותה טביעה) → כפילות, גם אחרי שעה.
 */
object DailyLedger {

    const val DUPLICATE_WINDOW_MS = 3_600_000L
    private const val MAX_FINGERPRINTS = 40

    /** מחזיר את המצב של "היום": אם התאריך התחלף – הסכומים מתאפסים */
    fun rollover(state: LedgerState, today: String): LedgerState =
        if (state.day == today) state
        else state.copy(day = today, totalMinor = 0, count = 0, lastTx = null, lastTxHomeMinor = 0)

    /**
     * @param original  הסכום כפי שהופיע בהתראה (משמש לזיהוי כפילויות)
     * @param homeMinor אותו סכום אחרי המרה למטבע הראשי (מה שנוסף לסכום היומי)
     */
    fun register(
        state: LedgerState,
        original: Amount,
        homeMinor: Long,
        fingerprint: String?,
        nowMillis: Long,
        today: String,
    ): LedgerResult {
        require(original.minor > 0 && homeMinor >= 0) { "amount must be positive" }
        val current = rollover(state, today)
        val recent = prune(current.recentAmounts, nowMillis)

        if (fingerprint != null && fingerprint in current.fingerprints) {
            return LedgerResult.Duplicate(current.copy(recentAmounts = recent), "אותה התראה כבר נספרה")
        }

        val dupIndex = recent.indexOfFirst { it.key == original.key }
        if (dupIndex >= 0) {
            val previous = recent[dupIndex]
            val refreshed = recent.toMutableList().apply { this[dupIndex] = previous.copy(atMillis = nowMillis) }
            return LedgerResult.Duplicate(
                current.copy(recentAmounts = refreshed, fingerprints = addFingerprint(current.fingerprints, fingerprint)),
                if (previous.counted) "אותו סכום תוך שעה" else "שייך לעסקה בתשלומים שהוחרגה",
            )
        }

        val next = current.copy(
            totalMinor = current.totalMinor + homeMinor,
            count = current.count + 1,
            lastTx = original,
            lastTxHomeMinor = homeMinor,
            recentAmounts = recent + RecentAmount(original.key, nowMillis, homeMinor, counted = true),
            fingerprints = addFingerprint(current.fingerprints, fingerprint),
        )
        return LedgerResult.Counted(next, current.totalMinor)
    }

    /**
     * עסקה שלא נספרת (למשל עסקה בתשלומים כשהמתג "לא לכלול" דלוק).
     * אם אותה קנייה כבר נספרה שניות קודם ממקור אחר (Google Wallet לא מציין תשלומים) – הספירה מבוטלת.
     * ההחרגה נכנסת לחלון הכפילויות, כך שגם דיווח מאוחר יותר על אותו סכום לא ייספר.
     */
    fun exclude(
        state: LedgerState,
        original: Amount,
        fingerprint: String?,
        nowMillis: Long,
        today: String,
    ): LedgerResult {
        val current = rollover(state, today)
        val recent = prune(current.recentAmounts, nowMillis).toMutableList()
        val marker = RecentAmount(original.key, nowMillis, 0, counted = false)
        val fingerprints = addFingerprint(current.fingerprints, fingerprint)
        val index = recent.indexOfFirst { it.key == original.key }

        if (index >= 0 && recent[index].counted) {
            val removed = recent[index].homeMinor
            recent[index] = marker
            val wasLast = current.lastTx?.key == original.key
            val next = current.copy(
                totalMinor = (current.totalMinor - removed).coerceAtLeast(0),
                count = (current.count - 1).coerceAtLeast(0),
                lastTx = if (wasLast) null else current.lastTx,
                lastTxHomeMinor = if (wasLast) 0 else current.lastTxHomeMinor,
                recentAmounts = recent,
                fingerprints = fingerprints,
            )
            return LedgerResult.Reversed(next, removed)
        }

        if (index >= 0) recent[index] = marker else recent += marker
        return LedgerResult.Excluded(current.copy(recentAmounts = recent, fingerprints = fingerprints))
    }

    private fun prune(list: List<RecentAmount>, nowMillis: Long): List<RecentAmount> =
        list.filter { (nowMillis - it.atMillis) in 0 until DUPLICATE_WINDOW_MS }

    private fun addFingerprint(list: List<String>, fingerprint: String?): List<String> =
        if (fingerprint == null) list else (list + fingerprint).takeLast(MAX_FINGERPRINTS)

    // ---- קידוד לשמירה ב-SharedPreferences ----

    fun encodeRecent(list: List<RecentAmount>): String =
        list.joinToString(";") { "${it.key}@${it.atMillis}@${it.homeMinor}@${if (it.counted) 1 else 0}" }

    fun decodeRecent(raw: String): List<RecentAmount> =
        raw.split(';').mapNotNull { part ->
            val f = part.split('@')
            val key = f.getOrNull(0).orEmpty()
            val at = f.getOrNull(1)?.toLongOrNull()
            if (key.isEmpty() || at == null) return@mapNotNull null
            RecentAmount(
                key = key,
                atMillis = at,
                homeMinor = f.getOrNull(2)?.toLongOrNull() ?: 0L,
                counted = f.getOrNull(3) != "0",
            )
        }
}
