package com.yaniv.dailyspendoverlay.overlay

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.yaniv.dailyspendoverlay.R
import com.yaniv.dailyspendoverlay.core.Amount
import com.yaniv.dailyspendoverlay.core.BudgetStatus
import com.yaniv.dailyspendoverlay.core.Money
import kotlin.math.min

/** מה מציגים בחלונית */
data class OverlayInfo(
    /** העסקה האחרונה במטבע המקורי ($12.50 / 45.90 ₪) */
    val lastTx: Amount,
    /** אותה עסקה במטבע הראשי – רק אם היא במטבע זר (אחרת null) */
    val lastTxHomeMinor: Long?,
    /** true אם ההמרה לפי שער מהרשת, false אם לפי שער משוער מובנה */
    val rateIsOnline: Boolean = false,
    /** עסקת תשלומים: מספר התשלומים והסכום החודשי */
    val installments: Int? = null,
    val perInstallment: Amount? = null,
    /** אין שער המרה – העסקה לא נכללה בסכום היומי */
    val notCounted: Boolean = false,
    val totalMinor: Long,
    val budgetMinor: Long,
    val homeCurrency: String,
    /** הרגע עברנו את התקציב (בעסקה הזו) – מפעיל "פעימה" */
    val justCrossed: Boolean,
    /** עסקה בתשלומים שהוסרה מהסכום (המתג "לא לכלול עסקאות בתשלומים") */
    val reversed: Boolean = false,
    val isPreview: Boolean = false,
)

/**
 * החלונית הצפה.
 *  • מוצגת מעל כל האפליקציות (TYPE_APPLICATION_OVERLAY, דורש SYSTEM_ALERT_WINDOW).
 *  • לא לוקחת פוקוס ולא דורשת שום פעולה – נעלמת לבד אחרי 4 שניות.
 *  • כחול רגוע כל עוד הסכום היומי לא עבר את התקציב; אדום בולט (עם מסגרת לבנה) מעל התקציב.
 *  • אם מגיעה עסקה נוספת בזמן שהחלונית מוצגת – התוכן מתעדכן והטיימר מתחיל מחדש.
 */
object OverlayManager {

    private const val TAG = "DailySpend"
    private const val VISIBLE_MS = 4_000L
    private const val ANIM_MS = 220L

    private val main = Handler(Looper.getMainLooper())
    private var windowContext: Context? = null
    private var windowManager: WindowManager? = null
    private var root: View? = null
    private val hideRunnable = Runnable { hide() }

    fun show(context: Context, info: OverlayInfo) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show(context, info) }
            return
        }
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) {
            showFallbackToast(app, info)
            return
        }
        try {
            val existing = root
            val view = if (existing != null) {
                existing.animate().cancel()
                existing.alpha = 1f
                existing.translationY = 0f
                existing
            } else {
                createAndAttach(app)
            }
            bind(view, info)
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, VISIBLE_MS)
        } catch (e: RuntimeException) {
            // BadTokenException / SecurityException – למשל אם ההרשאה בוטלה בזמן ריצה
            Log.w(TAG, "Overlay failed", e)
            root = null
            windowContext = null
            showFallbackToast(app, info)
        }
    }

    // ------------------------------------------------------------------

    @SuppressLint("InflateParams")
    private fun createAndAttach(app: Context): View {
        val ctx = obtainWindowContext(app)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val themed = ContextThemeWrapper(ctx, R.style.Theme_DailySpend_Overlay)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_spend, null, false)
        wm.addView(view, buildParams(ctx))
        windowManager = wm
        root = view

        view.alpha = 0f
        view.translationY = -dp(ctx, 24f)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        return view
    }

    /** Android 11+: חלון "שאינו Activity" צריך Context חזותי משלו (WindowContext) */
    private fun obtainWindowContext(app: Context): Context {
        windowContext?.let { return it }
        val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val display = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                app.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } catch (e: RuntimeException) {
                Log.w(TAG, "WindowContext unavailable, using app context", e)
                app
            }
        } else {
            app
        }
        windowContext = ctx
        return ctx
    }

    private fun buildParams(ctx: Context): WindowManager.LayoutParams {
        val metrics = ctx.resources.displayMetrics
        val width = min(metrics.widthPixels - dp(ctx, 24f).toInt(), dp(ctx, 440f).toInt())
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (metrics.heightPixels * 0.18f).toInt() // מתחת להתראה הקופצת של המערכת
            title = "DailySpendOverlay"
        }
    }

    private fun bind(view: View, info: OverlayInfo) {
        val ctx = view.context
        val status = BudgetStatus(info.totalMinor, info.budgetMinor)
        val card = view.findViewById<View>(R.id.overlayCard)
        card.background = cardBackground(ctx, status.isOver)

        view.findViewById<TextView>(R.id.overlayHeader).text = ctx.getString(
            when {
                info.reversed -> R.string.overlay_header_reversed
                status.isOver && info.justCrossed -> R.string.overlay_header_crossed
                status.isOver -> R.string.overlay_header_over
                info.isPreview -> R.string.overlay_header_preview
                else -> R.string.overlay_header_ok
            }
        )
        view.findViewById<TextView>(R.id.overlayLastAmount).text =
            ctx.getString(R.string.overlay_last_tx, Money.format(info.lastTx))

        val extra = view.findViewById<TextView>(R.id.overlayExtra)
        val extraLines = ArrayList<String>()
        if (info.installments != null && info.perInstallment != null) {
            extraLines += ctx.getString(
                if (info.reversed) R.string.overlay_installments_excluded else R.string.overlay_installments,
                info.installments,
                Money.format(info.perInstallment)
            )
        }
        if (info.notCounted) {
            extraLines += ctx.getString(R.string.overlay_not_counted)
        } else if (info.lastTxHomeMinor != null) {
            val converted = Money.format(info.lastTxHomeMinor, info.homeCurrency)
            extraLines += ctx.getString(
                if (info.rateIsOnline) R.string.overlay_converted_online else R.string.overlay_converted_builtin,
                converted
            )
        }
        extra.text = extraLines.joinToString("\n")
        extra.visibility = if (extraLines.isEmpty()) View.GONE else View.VISIBLE

        view.findViewById<TextView>(R.id.overlayTotal).text = Money.format(info.totalMinor, info.homeCurrency)

        val progress = view.findViewById<ProgressBar>(R.id.overlayProgress)
        applyProgress(ctx, progress, status)

        view.findViewById<TextView>(R.id.overlayBudgetLine).text = budgetLine(ctx, status, info.homeCurrency)

        if (status.isOver && info.justCrossed) pulse(card)
    }

    // ---- עזרים משותפים גם למסך הראשי ----

    /** רקע הכרטיס: כחול רגוע / אדום בולט עם מסגרת לבנה */
    fun cardBackground(ctx: Context, over: Boolean, cornerDp: Float = 28f): GradientDrawable {
        val colors = if (over) {
            intArrayOf(ctx.getColor(R.color.over_start), ctx.getColor(R.color.over_end))
        } else {
            intArrayOf(ctx.getColor(R.color.calm_start), ctx.getColor(R.color.calm_end))
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(ctx, cornerDp)
            if (over) setStroke(dp(ctx, 3f).toInt(), Color.WHITE)
        }
    }

    fun applyProgress(ctx: Context, progress: ProgressBar, status: BudgetStatus) {
        progress.max = 100
        progress.progress = status.percent.coerceIn(0, 100)
        progress.progressTintList = ColorStateList.valueOf(
            if (status.isOver) Color.WHITE else ctx.getColor(R.color.calm_progress)
        )
    }

    fun budgetLine(ctx: Context, status: BudgetStatus, currency: String): String =
        if (status.isOver) {
            ctx.getString(
                R.string.overlay_over_by,
                Money.format(-status.remainingMinor, currency),
                Money.format(status.budgetMinor, currency)
            )
        } else {
            ctx.getString(
                R.string.overlay_remaining,
                Money.format(status.remainingMinor, currency),
                Money.format(status.budgetMinor, currency)
            )
        }

    // ------------------------------------------------------------------

    private fun hide() {
        val view = root ?: return
        view.animate()
            .alpha(0f)
            .translationY(-dp(view.context, 16f))
            .setDuration(ANIM_MS)
            .withEndAction { removeNow(view) } // לא רץ אם האנימציה בוטלה ע"י עסקה חדשה
            .start()
    }

    private fun removeNow(view: View) {
        if (root !== view) return
        root = null
        try {
            windowManager?.removeViewImmediate(view)
        } catch (ignored: IllegalArgumentException) {
            // החלון כבר הוסר
        }
    }

    /** "פעימה" קצרה כשעוברים את התקציב */
    private fun pulse(card: View) {
        ObjectAnimator.ofPropertyValuesHolder(
            card,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f, 1f, 1.05f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f, 1f, 1.05f, 1f),
        ).apply {
            duration = 700
            startDelay = ANIM_MS
            start()
        }
    }

    /** אם אין הרשאת חלונית – לפחות הודעה קצרה (Toast) */
    private fun showFallbackToast(ctx: Context, info: OverlayInfo) {
        val text = ctx.getString(
            R.string.toast_fallback,
            Money.format(info.totalMinor, info.homeCurrency),
            Money.format(info.lastTx)
        )
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }

    private fun dp(ctx: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics)
}
