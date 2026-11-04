package com.yaniv.dailyspendoverlay.core

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** צבע לחלונית: התחלה וסוף של הגרדיאנט, וצבע פס ההתקדמות (הכל ב-ARGB, בהירות 50% = הצבעים האלה בדיוק) */
data class ThemeColor(
    val id: String,
    val nameHe: String,
    val start: Int,
    val end: Int,
    val progress: Int,
)

/**
 * צבעי החלונית ובהירות באחוזים (0–100, ברירת מחדל 50 = המראה המקורי).
 * לוגיקה טהורה, בלי תלות באנדרואיד.
 *
 * הבהירות משנה את הזוהר (luminance) של הרקע בצורה אחידה בין כל הצבעים:
 *  • 0%  – כהה מאוד (רבע מהזוהר המקורי),
 *  • 50% – הצבע המקורי,
 *  • 100% – בהיר, אבל עדיין כהה מספיק כדי שטקסט לבן יישאר קריא.
 * חריגה מהתקציב תמיד נשארת באדום בולט, בלי קשר לבהירות.
 */
object Themes {

    const val DEFAULT_ID = "blue"
    const val DEFAULT_BRIGHTNESS = 50

    private const val DIM_FACTOR = 0.25
    private const val START_MAX_LUM = 0.06
    private const val END_MAX_LUM = 0.14

    private fun rgb(v: Int): Int = (0xFF shl 24) or v

    val ALL: List<ThemeColor> = listOf(
        ThemeColor("blue", "כחול", rgb(0x0B2545), rgb(0x13507A), rgb(0x7FDBFF)),
        ThemeColor("teal", "טורקיז", rgb(0x00292E), rgb(0x005553), rgb(0x72ECE4)),
        ThemeColor("green", "ירוק", rgb(0x002B1B), rgb(0x1A5731), rgb(0x96F0AA)),
        ThemeColor("purple", "סגול", rgb(0x281C4D), rgb(0x5F3A89), rgb(0xD9BAFF)),
        ThemeColor("pink", "ורוד", rgb(0x411233), rgb(0x832B59), rgb(0xFFAFD1)),
        ThemeColor("gray", "אפור", rgb(0x21252A), rgb(0x474C54), rgb(0xCFD5DC)),
    )

    /** מזהה לא מוכר (או null) → כחול */
    fun byId(id: String?): ThemeColor = ALL.firstOrNull { it.id == id } ?: ALL.first()

    /** שני צבעי הגרדיאנט (התחלה, סוף) אחרי החלת הבהירות */
    fun gradient(theme: ThemeColor, brightnessPercent: Int): IntArray = intArrayOf(
        shade(theme.start, brightnessPercent, START_MAX_LUM),
        shade(theme.end, brightnessPercent, END_MAX_LUM),
    )

    /** הצבע אחרי הבהירות: 50% = ללא שינוי, מתחת – מוחשך, מעל – מוארה עד זוהר מרבי נתון */
    fun shade(color: Int, brightnessPercent: Int, maxLuminance: Double): Int {
        val p = brightnessPercent.coerceIn(0, 100)
        val lin = doubleArrayOf(
            toLinear((color shr 16) and 0xFF),
            toLinear((color shr 8) and 0xFF),
            toLinear(color and 0xFF),
        )
        val y0 = luminanceOfLinear(lin)
        if (y0 <= 0.0) return color
        val target = if (p <= 50) {
            cubeLerp(y0 * DIM_FACTOR, y0, p / 50.0)
        } else {
            cubeLerp(y0, max(y0, maxLuminance), (p - 50) / 50.0)
        }
        val k = target / y0
        return rgb(
            (toSrgb(lin[0] * k) shl 16) or (toSrgb(lin[1] * k) shl 8) or toSrgb(lin[2] * k)
        )
    }

    /** זוהר יחסי (WCAG) של צבע ARGB, 0..1 */
    fun luminance(color: Int): Double = luminanceOfLinear(
        doubleArrayOf(
            toLinear((color shr 16) and 0xFF),
            toLinear((color shr 8) and 0xFF),
            toLinear(color and 0xFF),
        )
    )

    private fun luminanceOfLinear(l: DoubleArray) = 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2]

    private fun toLinear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun toSrgb(linear: Double): Int {
        val x = linear.coerceIn(0.0, 1.0)
        val v = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055
        return (v * 255).roundToInt().coerceIn(0, 255)
    }

    /** אינטרפולציה במרחב שורש-שלישי: שינוי בהירות שנראה אחיד לעין */
    private fun cubeLerp(a: Double, b: Double, t: Double): Double {
        val ca = Math.cbrt(a)
        val cb = Math.cbrt(b)
        val c = ca + (cb - ca) * t
        return c * c * c
    }
}
