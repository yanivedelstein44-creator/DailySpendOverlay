package com.yaniv.dailyspendoverlay.core

/**
 * מטבע נתמך.
 * @param symbolFirst  "$12.50" (true) או "12.50 ₪" (false)
 * @param aliases      איך המטבע מופיע בטקסט של התראה: סימן, קוד ISO או מילה בעברית
 * @param decimals     כמה ספרות אחרי הנקודה מציגים (ין/וון בלי אגורות)
 */
data class CurrencyInfo(
    val code: String,
    val symbol: String,
    val symbolFirst: Boolean,
    val nameHe: String,
    val aliases: List<String>,
    val decimals: Int = 2,
)

object Currencies {

    val ALL: List<CurrencyInfo> = listOf(
        CurrencyInfo("ILS", "₪", false, "שקל חדש",
            listOf("₪", "ש\"ח", "ש״ח", "ש”ח", "ש'ח", "ש׳ח", "שח", "שקלים", "שקל", "ILS", "NIS")),
        CurrencyInfo("USD", "$", true, "דולר אמריקאי", listOf("US$", "USD", "$", "דולרים", "דולר")),
        CurrencyInfo("EUR", "€", true, "אירו", listOf("€", "EUR", "יורו", "אירו")),
        CurrencyInfo("GBP", "£", true, "לירה שטרלינג",
            listOf("£", "GBP", "ליש\"ט", "ליש״ט", "לירה שטרלינג", "פאונד")),
        CurrencyInfo("CHF", "CHF", false, "פרנק שווייצרי", listOf("CHF", "פרנק")),
        CurrencyInfo("JPY", "¥", true, "ין יפני", listOf("JPY", "¥", "ין"), decimals = 0),
        CurrencyInfo("CAD", "C$", true, "דולר קנדי", listOf("CA$", "C$", "CAD")),
        CurrencyInfo("AUD", "A$", true, "דולר אוסטרלי", listOf("AU$", "A$", "AUD")),
        CurrencyInfo("NZD", "NZ$", true, "דולר ניו זילנדי", listOf("NZ$", "NZD")),
        CurrencyInfo("HKD", "HK$", true, "דולר הונג קונגי", listOf("HK$", "HKD")),
        CurrencyInfo("SGD", "S$", true, "דולר סינגפורי", listOf("S$", "SGD")),
        CurrencyInfo("CNY", "CN¥", true, "יואן סיני", listOf("CN¥", "CNY", "RMB", "יואן")),
        CurrencyInfo("THB", "฿", true, "באט תאילנדי", listOf("฿", "THB", "באט")),
        CurrencyInfo("TRY", "₺", true, "לירה טורקית", listOf("₺", "TRY", "לירה טורקית")),
        CurrencyInfo("INR", "₹", true, "רופי הודי", listOf("₹", "INR")),
        CurrencyInfo("KRW", "₩", true, "וון דרום קוריאני", listOf("₩", "KRW"), decimals = 0),
        CurrencyInfo("PLN", "zł", false, "זלוטי פולני", listOf("PLN", "zł")),
        CurrencyInfo("CZK", "Kč", false, "קורונה צ׳כית", listOf("CZK", "Kč")),
        CurrencyInfo("HUF", "HUF", false, "פורינט הונגרי", listOf("HUF"), decimals = 0),
        CurrencyInfo("RON", "RON", false, "לאו רומני", listOf("RON")),
        CurrencyInfo("SEK", "SEK", false, "קרונה שוודית", listOf("SEK")),
        CurrencyInfo("NOK", "NOK", false, "קרונה נורווגית", listOf("NOK")),
        CurrencyInfo("DKK", "DKK", false, "קרונה דנית", listOf("DKK")),
        CurrencyInfo("ISK", "ISK", false, "קרונה איסלנדית", listOf("ISK"), decimals = 0),
        CurrencyInfo("MXN", "MX$", true, "פסו מקסיקני", listOf("MX$", "MXN")),
        CurrencyInfo("BRL", "R$", true, "ריאל ברזילאי", listOf("R$", "BRL")),
        CurrencyInfo("ZAR", "ZAR", false, "ראנד דרום אפריקאי", listOf("ZAR")),
    )

    private val byCode: Map<String, CurrencyInfo> = ALL.associateBy { it.code }

    /** alias (באותיות קטנות) → קוד מטבע */
    private val byAlias: Map<String, String> = buildMap {
        ALL.forEach { c -> c.aliases.forEach { put(it.lowercase(), c.code) } }
    }

    fun get(code: String): CurrencyInfo = byCode[code] ?: byCode.getValue("ILS")

    fun isSupported(code: String): Boolean = code in byCode

    /** מזהה את המטבע לפי הטוקן שנתפס בטקסט ("₪", "usd", "דולר"...) */
    fun fromAlias(token: String): String? = byAlias[token.lowercase()]

    /** כל הכינויים, הארוכים קודם – כדי ש-"US$" ייתפס לפני "$" */
    val aliasesLongestFirst: List<String> = ALL.flatMap { it.aliases }.distinct().sortedByDescending { it.length }

    /** מטבע ברירת מחדל לפי הגדרות הטלפון (ישראל → ₪), או ₪ אם המטבע לא נתמך */
    fun defaultFor(localeCurrencyCode: String?): String =
        if (localeCurrencyCode != null && isSupported(localeCurrencyCode)) localeCurrencyCode else "ILS"
}
