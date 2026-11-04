package com.yaniv.dailyspendoverlay.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * סכום במטבע מסוים. minor = הסכום כפול 100 (אגורות / סנטים), תמיד Long –
 * כך אין שגיאות עיגול של Double גם אחרי מאות עסקאות.
 */
data class Amount(val minor: Long, val currency: String) {
    /** מפתח לזיהוי כפילויות: "USD:1250" */
    val key: String get() = "$currency:$minor"
}

object Money {

    /** "1,234.5" → 123450. מחזיר null לטקסט לא תקין או שלילי. (משמש לקלט מהמשתמש) */
    fun parseToMinor(raw: String): Long? {
        val clean = raw.trim().replace(",", "")
        if (clean.isEmpty()) return null
        val value: BigDecimal = clean.toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        return toMinor(value)
    }

    /**
     * מספר כפי שהופיע בהתראה, בכל פורמט נפוץ:
     * "1,234.56" · "1.234,56" · "1 234,56" · "1'234.50" · "12,5" · "120".
     * הכלל: מפריד אחרון ('.' או ',') שאחריו 1–2 ספרות בסוף = נקודה עשרונית; כל השאר = אלפים.
     */
    fun parseFlexibleToMinor(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        val lastSep = maxOf(s.lastIndexOf('.'), s.lastIndexOf(','))
        val decimalDigits = if (lastSep >= 0) s.length - lastSep - 1 else 0
        val normalized = if (lastSep >= 0 && decimalDigits in 1..2 && s.substring(lastSep + 1).all { it.isDigit() }) {
            s.substring(0, lastSep).filter { it.isDigit() } + "." + s.substring(lastSep + 1)
        } else {
            s.filter { it.isDigit() }
        }
        if (normalized.isEmpty() || normalized.startsWith(".")) return null
        val value = normalized.toBigDecimalOrNull() ?: return null
        return toMinor(value)
    }

    private fun toMinor(value: BigDecimal): Long? = try {
        value.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
    } catch (e: ArithmeticException) {
        null
    }

    /** 34000 → "340" · 4590 → "45.90" · 123450 → "1,234.50" (ין: בלי אגורות) */
    fun formatNumber(minor: Long, decimals: Int = 2): String {
        val abs = kotlin.math.abs(minor)
        val body = if (decimals == 0) {
            String.format(Locale.US, "%,d", Math.round(abs / 100.0))
        } else {
            val whole = String.format(Locale.US, "%,d", abs / 100)
            val cents = abs % 100
            if (cents == 0L) whole else whole + "." + cents.toString().padStart(2, '0')
        }
        return if (minor < 0) "-$body" else body
    }

    /** "340 ₪" · "$12.50" · "45 CHF" */
    fun format(minor: Long, currency: String): String {
        val info = Currencies.get(currency)
        val number = formatNumber(minor, info.decimals)
        return when {
            info.symbolFirst -> info.symbol + number
            else -> "$number ${info.symbol}"
        }
    }

    fun format(amount: Amount): String = format(amount.minor, amount.currency)
}
