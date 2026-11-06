package com.yaniv.dailyspendoverlay.overlay

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.yaniv.dailyspendoverlay.core.CashBreakdown
import com.yaniv.dailyspendoverlay.core.CashBreakdown.Kind

/**
 * הארנק שבבועה (רק כשמוגדר תקציב יומי): ארנק חום, ומציצים ממנו השטרות שנשארו להיום,
 * עם המטבעות לפניו. בחריגה – ארנק ריק, ובמקום השטרות רק קווי מתאר מקווקווים.
 * הכול נבנה מחדש בכל הצגה (החלונית עצמה נשמרת ומשמשת שוב).
 */
object WalletViews {

    private const val MAX_BILLS = 4
    private const val MAX_COINS = 3

    /** סיבובים קלים, כאילו השטרות נדחפו לארנק ביד */
    private val ROTATIONS = floatArrayOf(-5f, 4f, -3f, 5f)

    fun fill(frame: FrameLayout, remainingMinor: Long, currency: String, over: Boolean, full: Boolean) {
        frame.removeAllViews()
        val ctx = frame.context
        val scale = if (full) 1.2f else 1f
        frame.layoutParams = frame.layoutParams.apply { height = dp(ctx, 150f * scale).toInt() }

        val billW = 120f * scale
        val billH = 62f * scale
        val pieces = if (over || remainingMinor <= 0) emptyList() else CashBreakdown.of(remainingMinor, currency).orEmpty()
        val bills = pieces.filter { it.kind == Kind.BILL }.flatMap { p -> List(p.count) { p.minor } }.take(MAX_BILLS)
        val coins = pieces.filter { it.kind == Kind.COIN }.flatMap { p -> List(p.count) { p.minor } }.take(MAX_COINS)

        // השטרות (או קווי המתאר שלהם) – מאחורי הארנק, מפוזרים מימין לשמאל
        val shownBills = if (over) 2 else bills.size
        val width = frame.width.takeIf { it > 0 } ?: (ctx.resources.displayMetrics.widthPixels - dp(ctx, 72f).toInt())
        val step = if (shownBills <= 1) 0f else ((width / density(ctx)) - billW - 40f) / (shownBills - 1)
        for (i in 0 until shownBills) {
            val view = if (over) ghostBill(ctx, billW, billH) else CashChips.bill(ctx, currency, bills[i], billW, billH, 20f * scale)
            view.rotation = ROTATIONS[i % ROTATIONS.size]
            view.layoutParams = FrameLayout.LayoutParams(dp(ctx, billW).toInt(), dp(ctx, billH).toInt(), Gravity.TOP or Gravity.RIGHT).apply {
                rightMargin = dp(ctx, 20f + i * step.coerceAtLeast(0f).coerceAtMost(110f)).toInt()
                topMargin = dp(ctx, if (i % 2 == 0) 4f else 12f).toInt()
            }
            frame.addView(view)
        }

        // הארנק עצמו – מכסה את החצי התחתון של השטרות
        val front = View(ctx).apply {
            background = walletDrawable(ctx)
            elevation = dp(ctx, 4f)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(ctx, 96f * scale).toInt(), Gravity.BOTTOM)
        }
        frame.addView(front)

        // המטבעות – לפני הארנק, בצד השמאלי
        coins.forEachIndexed { i, minor ->
            val coin = CashChips.coin(ctx, currency, minor, 1.05f * scale)
            val size = coin.layoutParams.width
            coin.elevation = dp(ctx, 6f)
            coin.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.LEFT).apply {
                leftMargin = dp(ctx, 22f).toInt() + i * (size + dp(ctx, 6f).toInt())
                bottomMargin = dp(ctx, 28f * scale).toInt()
            }
            frame.addView(coin)
        }
    }

    /** קו מתאר מקווקו של שטר שכבר לא בארנק */
    private fun ghostBill(ctx: Context, wDp: Float, hDp: Float): View =
        View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 6f)
                setStroke(dp(ctx, 2f).toInt(), 0x8CFFFFFF.toInt(), dp(ctx, 6f), dp(ctx, 4f))
            }
            layoutParams = FrameLayout.LayoutParams(dp(ctx, wDp).toInt(), dp(ctx, hDp).toInt())
        }

    /** עור חום עם תפר מקווקו בפנים */
    private fun walletDrawable(ctx: Context): Drawable {
        val leather = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF7A4B2A.toInt(), 0xFF4E2F18.toInt()),
        ).apply { cornerRadius = dp(ctx, 18f) }
        val stitch = GradientDrawable().apply {
            cornerRadius = dp(ctx, 12f)
            setStroke(dp(ctx, 2f).toInt(), 0x47FFFFFF, dp(ctx, 8f), dp(ctx, 6f))
        }
        val inset = dp(ctx, 8f).toInt()
        return LayerDrawable(arrayOf<Drawable>(leather, stitch)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }

    private fun density(ctx: Context): Float = ctx.resources.displayMetrics.density

    private fun dp(ctx: Context, v: Float): Float = v * density(ctx)
}
