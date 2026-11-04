package com.yaniv.dailyspendoverlay.core

import kotlin.math.abs

/**
 * חילוץ סכום העסקה ומטבע העסקה מתוך טקסט של התראה – בעזרת ביטויים רגולריים.
 *
 * נתמך:
 *  • כל המטבעות שב-[Currencies]: סימן (₪ $ € £ ¥ …), קוד (ILS USD EUR …) או מילה ("ש״ח", "דולר", "יורו").
 *  • סימן לפני או אחרי המספר: "₪34.90" · "34.90 ₪" · "USD 12.50" · "12,50 €".
 *  • פורמטים של מספרים: 1,234.56 · 1.234,56 · 1 234,56 · 12,5 · 120.
 *  • תווי כיווניות בלתי נראים (RLM/LRM וכו') שאפליקציות בעברית שותלות סביב מספרים.
 *  • עסקה בתשלומים ("ב-12 תשלומים"): נבחר הסכום המלא, ומחושב סכום התשלום החודשי.
 *
 * כללי בטיחות (כדי לא לספור דברים שאינם הוצאה):
 *  • סכום שלפניו "יתרה" / "מסגרת" / "balance" מדולג – זו יתרה, לא העסקה.
 *  • התראה עם "זיכוי" / "קיבלת" / "בוטל" / "הנחה" … מסומנת "סוננה" ולא נספרת.
 *  • סכום שלילי (-50 ₪) = החזר, לא נספר.
 *  • ספרות של כרטיס, תאריכים ושעות לא נתפסים – חייב סימן מטבע צמוד למספר.
 */
object AmountParser {

    sealed interface Result {
        /** הוצאה תקינה. בעסקת תשלומים: amount = הסכום המלא, perInstallment = תשלום חודשי. */
        data class Expense(
            val amount: Amount,
            val installments: Int? = null,
            val perInstallment: Amount? = null,
        ) : Result

        /** נמצא סכום, אבל ההתראה אינה הוצאה (זיכוי / כסף נכנס / פרסומת / סכום שלילי) */
        data class Excluded(val amount: Amount, val reason: String) : Result

        /** אין בטקסט סכום עם מטבע */
        data object NoAmount : Result
    }

    /** הגנה מגרסת מספרים מופרכים (מספר כרטיס שנדבק למטבע וכו') */
    private const val MAX_MINOR = 100_000_000_000L

    // תווים בלתי נראים + רווחים שמותר שיופיעו בין המספר לסימן המטבע
    private const val INVISIBLE = "\\u200E\\u200F\\u061C\\u202A-\\u202E\\u2066-\\u2069"
    private const val GAP = "[ \\t\\r\\n\\u00A0\\u2007\\u2009\\u202F$INVISIBLE]*"

    // מפריד אלפים: , . ' ’ ורווחים צרים (צרפתית/שווייצרית). ספרות ASCII בלבד – זהה ב-JVM ובאנדרואיד.
    private const val SEP = "[,.'’\\u00A0\\u202F\\u2009]"
    private const val NUMBER = "([0-9]{1,3}(?:$SEP[0-9]{3})+(?:[.,][0-9]{1,2})?|[0-9]+(?:[.,][0-9]{1,2})?)"

    private val CURRENCY: String = "(" + Currencies.aliasesLongestFirst.joinToString("|") { escape(it) } + ")"

    // בלי IGNORE_CASE: קודים כמו TRY/RON נתפסים רק באותיות גדולות (כדי ש-"try 5" לא ייחשב לירה טורקית)

    /** "₪34.90" · "USD 12.50" · "€-20" → קבוצות: 1=מטבע, 2=מינוס, 3=מספר */
    private val CURRENCY_FIRST = Regex("(?<!\\p{L})$CURRENCY$GAP([-−]$GAP)?$NUMBER(?![.,]?[0-9])")

    /** "34.90 ₪" · "125 ש\"ח" · "12,50 €" → קבוצות: 1=מספר, 2=מטבע */
    private val NUMBER_FIRST = Regex("(?<![0-9.,'’])$NUMBER$GAP$CURRENCY(?!\\p{L})")

    /** "ב-12 תשלומים" · "תשלומים: 3" · "6 installments" */
    private val INSTALLMENTS = Regex(
        "(?<![0-9])([0-9]{1,2})[ \\u00A0]*(?:תשלומים|installments)|(?:תשלומים|installments)[ \\u00A0]*[:\\-]?[ \\u00A0]*([0-9]{1,2})(?![0-9])",
        RegexOption.IGNORE_CASE
    )

    /** "תשלום 3 מתוך 12" – חיוב חודשי של עסקה ישנה (כבר נספרה במלואה ביום הקנייה) */
    private val LATER_INSTALLMENT = Regex("תשלום[ \\u00A0]*([0-9]{1,2})[ \\u00A0]*(?:מתוך|/)[ \\u00A0]*([0-9]{1,2})")

    /** מילים שמסמנות שהסכום שאחריהן הוא יתרה / מסגרת ולא העסקה עצמה */
    private val BALANCE_HINTS = listOf(
        "יתרה", "יתרת", "יתרתך", "מסגרת", "נותרו", "נותר",
        "balance", "available", "limit"
    )

    /**
     * מילים שמסמנות שההתראה כולה אינה הוצאה.
     * אפשר להוסיף/להוריד כאן מילים לפי מה שרואים ביומן הזיהוי במסך ההגדרות.
     */
    val EXCLUDE_KEYWORDS: List<String> = listOf(
        // זיכוי / החזר / ביטול / סירוב
        "זיכוי", "זוכה", "זוכית", "החזר", "הוחזר", "ביטול", "בוטל", "בוטלה",
        "נדחה", "נדחתה", "סורב", "סורבה", "לא אושר",
        // כסף נכנס / בקשות תשלום
        "קיבלת", "התקבלה העברה", "התקבל תשלום", "העברה נכנסת", "הועבר אליך", "הועברו אליך",
        "נכנסו לחשבון", "הפקדה", "הופקד", "מבקש ממך", "מבקשת ממך", "בקשת תשלום", "בקשה לתשלום",
        // פרסומות / הטבות
        "הנחה", "הטבה", "הטבות", "מבצע", "קופון", "זכית", "מתנה",
        // סיכומים / תזכורות (לא עסקה של היום)
        "חיוב חודשי", "החיוב החודשי", "סך החיובים", "סה\"כ חיובים", "סה״כ חיובים", "תזכורת",
        // English
        "refund", "reversed", "reversal", "declined", "cancelled", "canceled", "credited",
        "you received", "received from", "deposit", "requested", "promo", "coupon",
        "cashback", "reminder", "statement", "top-up", "top up", "topped up", "exchanged"
    )

    fun parse(text: String): Result {
        if (text.isBlank()) return Result.NoAmount
        val found = findAmounts(text)
        if (found.isEmpty()) return Result.NoAmount
        val first = found.first()

        if (first.negative) return Result.Excluded(first.amount, "סכום שלילי (החזר)")

        val lower = text.lowercase()
        EXCLUDE_KEYWORDS.firstOrNull { lower.contains(it) }?.let {
            return Result.Excluded(first.amount, it)
        }

        LATER_INSTALLMENT.find(text)?.let { m ->
            val index = m.groupValues[1].toIntOrNull() ?: 1
            if (index >= 2) return Result.Excluded(first.amount, "תשלום ${m.groupValues[1]} של עסקה קודמת")
        }

        val installments = installmentCount(text)
        if (installments != null) {
            val sameCurrency = found.filter { it.amount.currency == first.amount.currency && !it.negative }
            // ההתראה מציגה גם את הסכום המלא וגם את התשלום החודשי? בוחרים את המלא.
            for (big in sameCurrency) {
                for (small in sameCurrency) {
                    if (big === small) continue
                    val expected = small.amount.minor * installments
                    val tolerance = maxOf(installments.toLong(), big.amount.minor / 100)
                    if (abs(big.amount.minor - expected) <= tolerance) {
                        return Result.Expense(big.amount, installments, small.amount)
                    }
                }
            }
            val per = Amount(Math.round(first.amount.minor.toDouble() / installments), first.amount.currency)
            return Result.Expense(first.amount, installments, per)
        }

        return Result.Expense(first.amount)
    }

    /** מספר התשלומים אם זו עסקת תשלומים (2–60), אחרת null */
    fun installmentCount(text: String): Int? {
        val m = INSTALLMENTS.find(text) ?: return null
        val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() ?: return null
        return n.takeIf { it in 2..60 }
    }

    private data class Found(val start: Int, val end: Int, val amount: Amount, val negative: Boolean)

    /** כל הסכומים התקינים בטקסט, לפי הסדר, בלי יתרות ובלי 0 */
    private fun findAmounts(text: String): List<Found> {
        val all = ArrayList<Found>()
        CURRENCY_FIRST.findAll(text).forEach { m ->
            val currency = Currencies.fromAlias(m.groupValues[1]) ?: return@forEach
            val minor = Money.parseFlexibleToMinor(m.groupValues[3]) ?: return@forEach
            val negative = m.groupValues[2].isNotEmpty() || precededByMinus(text, m.range.first)
            all += Found(m.range.first, m.range.last + 1, Amount(minor, currency), negative)
        }
        NUMBER_FIRST.findAll(text).forEach { m ->
            val currency = Currencies.fromAlias(m.groupValues[2]) ?: return@forEach
            val minor = Money.parseFlexibleToMinor(m.groupValues[1]) ?: return@forEach
            all += Found(m.range.first, m.range.last + 1, Amount(minor, currency), precededByMinus(text, m.range.first))
        }
        all.sortBy { it.start }

        val result = ArrayList<Found>()
        var previousEnd = 0
        for (f in all) {
            if (f.start < previousEnd) continue // חפיפה בין שתי התבניות (למשל "₪ 50 ₪")
            val windowStart = maxOf(previousEnd, f.start - 24)
            val before = text.substring(windowStart, f.start).lowercase()
            previousEnd = f.end
            if (BALANCE_HINTS.any { before.contains(it) }) continue
            if (f.amount.minor <= 0 || f.amount.minor > MAX_MINOR) continue
            result += f
        }
        return result
    }

    /**
     * האם יש מינוס צמוד לפני הסכום ("-50 ₪", "סה״כ: −50 ₪").
     * "ב-120 ₪" (מקף אחרי אות עברית) או "100-120" (טווח) אינם מינוס.
     */
    private fun precededByMinus(text: String, start: Int): Boolean {
        var i = start - 1
        while (i >= 0 && isGap(text[i])) i--
        if (i < 0 || (text[i] != '-' && text[i] != '−')) return false
        val before = i - 1
        return before < 0 || !Character.isLetterOrDigit(text[before])
    }

    private fun isGap(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u00A0' || c == '\u2007' ||
            c == '\u2009' || c == '\u202F' || c == '\u200E' || c == '\u200F' || c == '\u061C' ||
            c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'

    /** בריחה של תווים מיוחדים ב-Regex (כמו $) – עובד זהה ב-JVM ובאנדרואיד */
    private fun escape(s: String): String = buildString {
        for (c in s) {
            if (c in "\\^$.|?*+()[]{}") append('\\')
            append(c)
        }
    }
}
