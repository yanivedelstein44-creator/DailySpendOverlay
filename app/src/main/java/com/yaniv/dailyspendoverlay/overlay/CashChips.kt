package com.yaniv.dailyspendoverlay.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.yaniv.dailyspendoverlay.core.Amount
import com.yaniv.dailyspendoverlay.core.CashBreakdown
import com.yaniv.dailyspendoverlay.core.CashBreakdown.Kind

/**
 * "במזומן זה היה:" – השטרות והמטבעות של התשלום, בצבעים של הכסף האמיתי (לא העיצוב שלו):
 * שקל – 20 אדום, 50 ירוק, 100 כתום, 200 כחול; יורו ודולר – הצבעים של השטרות שלהם.
 * עד 3 שטרות זהים מוצגים אחד-אחד; יותר – שטר אחד ו"×N".
 */
object CashChips {

    private const val MAX_SAME = 3

    /** צבעים של שטר: התחלה → סוף של המעבר, וצבע הטקסט (כהה, לקריאות) */
    private class BillColors(val start: Int, val end: Int, val text: Int)

    private fun rgb(v: Int): Int = (0xFF shl 24) or v

    private val BILLS: Map<String, Map<Long, BillColors>> = mapOf(
        "ILS" to mapOf(
            20_000L to BillColors(rgb(0x2B8FD0), rgb(0x86D4EA), rgb(0x06263F)),
            10_000L to BillColors(rgb(0xF5922E), rgb(0xFFD35C), rgb(0x3B1F00)),
            5_000L to BillColors(rgb(0x4DAE4F), rgb(0xB5DE76), rgb(0x0B3311)),
            2_000L to BillColors(rgb(0xEC5A50), rgb(0xF7A1A1), rgb(0x3A0606)),
        ),
        "EUR" to mapOf(
            20_000L to BillColors(rgb(0xC9962E), rgb(0xF2D27E), rgb(0x3A2A00)),
            10_000L to BillColors(rgb(0x3DA35A), rgb(0x9FD9A6), rgb(0x0B3311)),
            5_000L to BillColors(rgb(0xF08A24), rgb(0xFFC873), rgb(0x3B1F00)),
            2_000L to BillColors(rgb(0x3C78C8), rgb(0x9CC3EE), rgb(0x0A2347)),
            1_000L to BillColors(rgb(0xD9564E), rgb(0xF2A39C), rgb(0x3A0606)),
            500L to BillColors(rgb(0x8E9A92), rgb(0xD3DBD5), rgb(0x1E2A22)),
        ),
        "USD" to mapOf(
            10_000L to BillColors(rgb(0x8FB08A), rgb(0xB9D9E6), rgb(0x142614)),
            5_000L to BillColors(rgb(0x8FB08A), rgb(0xE8BFCB), rgb(0x142614)),
            2_000L to BillColors(rgb(0x8FB08A), rgb(0xE8D5B0), rgb(0x142614)),
            1_000L to BillColors(rgb(0x8FB08A), rgb(0xEBC9A0), rgb(0x142614)),
            500L to BillColors(rgb(0x8FB08A), rgb(0xC9BEDF), rgb(0x142614)),
            100L to BillColors(rgb(0x8FB08A), rgb(0xD2E0CC), rgb(0x142614)),
        ),
    )

    private val SILVER = rgb(0xD7DCE3)
    private val SILVER_EDGE = rgb(0xAEB6C1)
    private val SILVER_RING = rgb(0xC9CFD8)
    private val GOLD = rgb(0xE7C46A)
    private val GOLD_EDGE = rgb(0xA8862A)
    private val DARK_ON_SILVER = rgb(0x1F2933)
    private val DARK_ON_GOLD = rgb(0x3B2A00)

    /**
     * ממלא את השורה (מנקה קודם – החלונית נשמרת ומשמשת שוב). מחזיר false אם אין מה להציג:
     * מטבע שלא נתמך, או סכום שבמזומן הוא 0.
     */
    fun fill(row: ViewGroup, amount: Amount, full: Boolean): Boolean {
        row.removeAllViews()
        val currency = amount.currency.uppercase()
        val pieces = CashBreakdown.of(amount.minor, currency) ?: return false
        if (pieces.isEmpty()) return false
        val ctx = row.context
        val scale = if (full) 1.2f else 1f
        for (piece in pieces) {
            val shown = if (piece.count > MAX_SAME) 1 else piece.count
            repeat(shown) {
                row.addView(
                    when (piece.kind) {
                        Kind.BILL -> bill(ctx, currency, piece.minor, 66f * scale, 36f * scale, 17f * scale)
                        Kind.COIN -> coin(ctx, currency, piece.minor, scale)
                        Kind.SMALL -> small(ctx, currency, piece.minor, scale)
                    }
                )
            }
            if (piece.count > MAX_SAME) row.addView(times(ctx, piece.count, scale))
        }
        return true
    }

    // ------------------------------------------------------------------ שטר

    /** שטר בגודל נתון (dp) – גם לשורת התשלום וגם לשטרות שמציצים מהארנק */
    fun bill(ctx: Context, currency: String, minor: Long, widthDp: Float, heightDp: Float, textSp: Float): View {
        val colors = BILLS[currency]?.get(minor) ?: BillColors(SILVER, SILVER, DARK_ON_SILVER)
        val units = (minor / 100).toString()
        val symbol = symbolOf(currency)
        // "100₪" / "20€" / "$20" – הסמל קטן יותר
        val text = if (currency == "USD") "$symbol$units" else "$units$symbol"
        val span = SpannableString(text)
        val start = text.indexOf(symbol)
        span.setSpan(RelativeSizeSpan(0.7f), start, start + symbol.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return TextView(ctx).apply {
            this.text = span
            setTextColor(colors.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            includeFontPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(colors.start, colors.end)).apply {
                cornerRadius = dp(ctx, 6f)
                setStroke(dp(ctx, 1f).toInt(), 0x2E000000)
            }
            layoutParams = ViewGroup.LayoutParams(dp(ctx, widthDp).toInt(), dp(ctx, heightDp).toInt())
        }
    }

    // ------------------------------------------------------------------ מטבע

    fun coin(ctx: Context, currency: String, minor: Long, scale: Float): View {
        val units = (minor / 100).toString()
        val (label, sizeDp, background) = when {
            // 10 ₪ – כסוף מבחוץ, זהוב בפנים
            currency == "ILS" && minor == 1_000L -> Triple(units, 38f, bimetal(ctx, SILVER_RING, GOLD, 4f))
            currency == "ILS" -> Triple(units, if (minor >= 500L) 36f else if (minor >= 200L) 34f else 32f, plain(ctx, SILVER, SILVER_EDGE))
            // 2€ – כסוף מבחוץ, זהוב בפנים; 1€ – הפוך
            currency == "EUR" && minor == 200L -> Triple("$units€", 40f, bimetal(ctx, SILVER_RING, GOLD, 5f))
            currency == "EUR" -> Triple("$units€", 38f, bimetal(ctx, GOLD, SILVER, 5f))
            else -> Triple(units, 34f, plain(ctx, SILVER, SILVER_EDGE))
        }
        val goldCenter = (currency == "ILS" && minor == 1_000L) || (currency == "EUR" && minor == 200L)
        return TextView(ctx).apply {
            text = label
            setTextColor(if (goldCenter) DARK_ON_GOLD else DARK_ON_SILVER)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (if (label.length > 2) 12f else 14f) * scale)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            includeFontPadding = false
            this.background = background
            val size = dp(ctx, sizeDp * scale).toInt()
            layoutParams = ViewGroup.LayoutParams(size, size)
        }
    }

    // ------------------------------------------------------------ אגורות / סנטים

    private fun small(ctx: Context, currency: String, minor: Long, scale: Float): View =
        TextView(ctx).apply {
            text = if (currency == "ILS") "$minor אג׳" else "$minor סנט"
            val gold = currency != "USD"
            setTextColor(if (gold) DARK_ON_GOLD else DARK_ON_SILVER)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            includeFontPadding = false
            val pad = dp(ctx, 10f * scale).toInt()
            setPadding(pad, 0, pad, 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 14f * scale)
                setColor(if (gold) GOLD else SILVER)
            }
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 28f * scale).toInt())
        }

    /** "×5" אחרי שטר שחוזר יותר מ-3 פעמים */
    private fun times(ctx: Context, count: Int, scale: Float): View =
        TextView(ctx).apply {
            text = "×$count"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * scale)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36f * scale).toInt())
        }

    // ------------------------------------------------------------------ עזר

    private fun symbolOf(currency: String): String = when (currency) {
        "ILS" -> "₪"
        "EUR" -> "€"
        "USD" -> "$"
        else -> ""
    }

    private fun plain(ctx: Context, fill: Int, edge: Int): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            setStroke(dp(ctx, 2f).toInt(), edge)
        }

    /** מטבע דו-מתכתי: טבעת חיצונית בצבע אחד ומרכז בצבע אחר */
    private fun bimetal(ctx: Context, ring: Int, core: Int, ringDp: Float): Drawable {
        val outer = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ring)
            setStroke(dp(ctx, 1f).toInt(), SILVER_EDGE)
        }
        val inner = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(core)
            setStroke(dp(ctx, 1f).toInt(), GOLD_EDGE)
        }
        val inset = dp(ctx, ringDp).toInt()
        return LayerDrawable(arrayOf<Drawable>(outer, inner)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
}
