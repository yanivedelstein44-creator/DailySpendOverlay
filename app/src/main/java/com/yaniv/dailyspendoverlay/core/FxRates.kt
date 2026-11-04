package com.yaniv.dailyspendoverlay.core

/** שערי חליפין ביחס לאירו: כמה יחידות מטבע שוות 1 EUR (בדיוק כמו בפרסום של ה-ECB) */
data class FxTable(val perEur: Map<String, Double>, val date: String, val online: Boolean) {

    /** המרה בין מטבעות דרך האירו. null אם חסר שער לאחד המטבעות. */
    fun convert(minor: Long, from: String, to: String): Long? {
        if (from == to) return minor
        val f = rate(from) ?: return null
        val t = rate(to) ?: return null
        return Math.round(minor.toDouble() / f * t)
    }

    private fun rate(code: String): Double? = if (code == "EUR") 1.0 else perEur[code]
}

/** תוצאת המרה לסכום במטבע הראשי */
data class Conversion(val homeMinor: Long, val online: Boolean, val rateDate: String)

object FxRates {

    /** קובץ השערים היומי הרשמי של הבנק המרכזי האירופי (ציבורי, בלי הרשמה, ~2KB) */
    const val ECB_DAILY_URL = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml"

    /**
     * שערים משוערים מובנים (שערי ECB מסוף אוקטובר 2026).
     * משמשים רק כשאין שערים עדכניים מהרשת – ואז ההמרה מסומנת "משוער".
     */
    val BUILT_IN = FxTable(
        perEur = mapOf(
            "USD" to 1.1172, "ILS" to 3.495, "GBP" to 0.8338, "JPY" to 175.68, "CHF" to 0.9194,
            "CAD" to 1.5606, "AUD" to 1.5904, "CNY" to 7.4326, "HKD" to 8.7652, "INR" to 106.084,
            "KRW" to 1491.76, "MXN" to 21.1237, "BRL" to 6.0692, "CZK" to 24.353, "DKK" to 7.4735,
            "HUF" to 366.33, "MYR" to 4.5325, "IDR" to 20275.17, "NOK" to 10.81, "NZD" to 1.978,
            "PLN" to 4.3265, "RON" to 5.2534, "SEK" to 11.1245,
        ),
        date = "2026-10-30",
        online = false,
    )

    /** פענוח ה-XML היומי של ה-ECB: <Cube time='2026-11-04'> … <Cube currency='USD' rate='1.1172'/> */
    fun parseEcbXml(xml: String): FxTable? {
        val date = Regex("time=['\"]([0-9]{4}-[0-9]{2}-[0-9]{2})['\"]").find(xml)?.groupValues?.get(1) ?: return null
        val rates = Regex("currency=['\"]([A-Z]{3})['\"]\\s+rate=['\"]([0-9.]+)['\"]")
            .findAll(xml)
            .mapNotNull { m -> m.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 }?.let { m.groupValues[1] to it } }
            .toMap()
        if (rates.size < 5) return null
        return FxTable(rates, date, online = true)
    }

    /** המרה למטבע הראשי: קודם שערים מהרשת (אם יש), ואם חסר מטבע – השערים המובנים */
    fun convert(amount: Amount, home: String, online: FxTable?): Conversion? {
        if (amount.currency == home) return Conversion(amount.minor, online = false, rateDate = "")
        online?.convert(amount.minor, amount.currency, home)?.let { return Conversion(it, true, online.date) }
        BUILT_IN.convert(amount.minor, amount.currency, home)?.let { return Conversion(it, false, BUILT_IN.date) }
        return null
    }

    fun encode(table: FxTable): String =
        table.date + "|" + table.perEur.entries.joinToString(";") { "${it.key}=${it.value}" }

    fun decode(raw: String): FxTable? {
        val date = raw.substringBefore('|', "")
        if (date.isEmpty()) return null
        val rates = raw.substringAfter('|').split(';').mapNotNull { part ->
            val code = part.substringBefore('=', "")
            val value = part.substringAfter('=', "").toDoubleOrNull()
            if (code.length == 3 && value != null && value > 0) code to value else null
        }.toMap()
        return if (rates.isEmpty()) null else FxTable(rates, date, online = true)
    }
}
