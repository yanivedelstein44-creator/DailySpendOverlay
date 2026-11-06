package com.yaniv.dailyspendoverlay.overlay

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
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
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.yaniv.dailyspendoverlay.R
import com.yaniv.dailyspendoverlay.core.Amount
import com.yaniv.dailyspendoverlay.core.BudgetStatus
import com.yaniv.dailyspendoverlay.core.Money
import com.yaniv.dailyspendoverlay.core.Themes
import com.yaniv.dailyspendoverlay.data.SpendStore
import kotlin.math.min

/** מה מציגים בחלונית */
data class OverlayInfo(
    /** העסקה האחרונה במטבע המקורי ($12.50 / 45.90 ₪) */
    /** null = חלונית "סיכום בפתיחת הטלפון": רק הסכום היומי, בלי עסקה אחרונה */
    val lastTx: Amount?,
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
    /** "25 דקות" – כמה זמן עבודה העסקה שווה (רק אם הוגדר שכר שעתי) */
    val workText: String? = null,
    /** הסכום היומי לפני העסקה – המספר "רץ" ממנו לסכום החדש, כמו כסף שנספר */
    val previousTotalMinor: Long? = null,
)

/** מה קרה כשביקשו להציג חלונית */
enum class ShowResult {
    /** החלונית הוצגה */
    SHOWN,
    /** הטלפון נעול או המסך כבוי – לא מציגים עכשיו (אחרת היא "תקועה" עד הפתיחה) */
    DEFERRED,
    /** אין הרשאת "הצגה מעל אפליקציות אחרות" */
    NO_PERMISSION,
    /** המערכת סירבה להוסיף את החלון */
    FAILED,
}

/**
 * החלונית הצפה.
 *  • מוצגת מעל כל האפליקציות (TYPE_APPLICATION_OVERLAY, דורש SYSTEM_ALERT_WINDOW).
 *  • לא לוקחת פוקוס ולא דורשת שום פעולה – נעלמת לבד אחרי 7 שניות (או מיד בנגיעה בה).
 *  • אטומה לגמרי: חלונית שאי אפשר לגעת בה מקבלת מהמערכת 20% שקיפות, ולכן היא כן ניתנת לנגיעה.
 *  • כחול רגוע כל עוד הסכום היומי לא עבר את התקציב; אדום בולט (עם מסגרת לבנה) מעל התקציב.
 *  • אם מגיעה עסקה נוספת בזמן שהחלונית מוצגת – התוכן מתעדכן והטיימר מתחיל מחדש.
 *
 * (StaticFieldLeak: נשמר רק Context של האפליקציה/החלון – לא Activity – והחלונית משוחררת אחרי 7 שניות.)
 */
@SuppressLint("StaticFieldLeak")
object OverlayManager {

    private const val TAG = "DailySpend"
    /** כמה זמן החלונית (וגם ההתראה במסך הנעילה) נשארות על המסך */
    const val VISIBLE_MS = 7_000L
    private const val ANIM_MS = 220L
    /** הופעה קצרה: החלונית נראית כמעט מיד אחרי ההתראה */
    private const val APPEAR_MS = 120L
    private const val COUNT_UP_MS = 650L

    private val main = Handler(Looper.getMainLooper())
    private var windowContext: Context? = null
    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var attachedFull = false
    private val hideRunnable = Runnable { hide() }

    /** חלונית בנויה מראש (ב-warmUp, או זו שהוצגה בפעם הקודמת) – ברגע התשלום נשאר רק למלא ולהציג */
    private var spare: View? = null

    /**
     * הכנה מראש כשקליטת ההתראות מתחברת (אירוע של המערכת, פעם אחת – בלי טיימר):
     * טוען את המחלקות ובונה את החלונית מראש, כדי שבתשלום עצמו היא תופיע מהר יותר.
     */
    fun warmUp(context: Context) {
        if (Looper.myLooper() != Looper.getMainLooper() || spare != null || root != null) return
        try {
            spare = inflate(obtainWindowContext(context.applicationContext))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Overlay warm-up failed", e)
        }
    }

    /**
     * @param ignoreLock true רק ברגע שחרור הנעילה (ה-Broadcast מגיע לפעמים רגע לפני שהמערכת מעדכנת את מצב הנעילה)
     */
    fun show(context: Context, info: OverlayInfo, ignoreLock: Boolean = false): ShowResult {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { show(context, info, ignoreLock) }
            return ShowResult.SHOWN
        }
        val app = context.applicationContext
        if (!ignoreLock && !ScreenState.canShowOverlay(app)) return ShowResult.DEFERRED
        if (!Settings.canDrawOverlays(app)) {
            showFallbackToast(app, info)
            return ShowResult.NO_PERMISSION
        }
        try {
            val full = SpendStore.get(app).overlayFull
            var existing = root
            if (existing != null && attachedFull != full) {
                // המשתמש החליף בין כרטיס למסך מלא בזמן שהחלונית מוצגת – בונים מחדש
                existing.animate().cancel()
                removeNow(existing)
                existing = null
            }
            val view = if (existing != null) {
                existing.animate().cancel()
                existing.alpha = 1f
                existing.translationY = 0f
                existing
            } else {
                createAndAttach(app, full)
            }
            bind(view, info)
            main.removeCallbacks(hideRunnable)
            main.postDelayed(hideRunnable, VISIBLE_MS)
            return ShowResult.SHOWN
        } catch (e: RuntimeException) {
            // BadTokenException / SecurityException – למשל אם ההרשאה בוטלה בזמן ריצה
            Log.w(TAG, "Overlay failed", e)
            root = null
            spare = null
            windowContext = null
            showFallbackToast(app, info)
            return ShowResult.FAILED
        }
    }

    // ------------------------------------------------------------------

    @SuppressLint("InflateParams")
    private fun inflate(ctx: Context): View {
        val themed = ContextThemeWrapper(ctx, R.style.Theme_DailySpend_Overlay)
        return LayoutInflater.from(themed).inflate(R.layout.overlay_spend, null, false)
    }

    private fun createAndAttach(app: Context, full: Boolean): View {
        val ctx = obtainWindowContext(app)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val view = spare?.takeIf { it.parent == null } ?: inflate(ctx)
        spare = null
        view.animate().cancel()
        view.setOnClickListener { dismissNow() } // נגיעה בחלונית סוגרת אותה מיד
        wm.addView(view, buildParams(ctx, full))
        windowManager = wm
        root = view
        attachedFull = full

        // מתחילה כבר חצי-נראית ונכנסת מהר – בלי "השהיה" מורגשת אחרי ההתראה
        view.alpha = 0.25f
        view.translationY = -dp(ctx, 12f)
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(APPEAR_MS)
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

    private fun buildParams(ctx: Context, full: Boolean): WindowManager.LayoutParams {
        val metrics = ctx.resources.displayMetrics
        val width = if (full) WindowManager.LayoutParams.MATCH_PARENT
        else min(metrics.widthPixels - dp(ctx, 24f).toInt(), dp(ctx, 440f).toInt())
        return WindowManager.LayoutParams(
            width,
            if (full) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // בכוונה בלי FLAG_NOT_TOUCHABLE: אנדרואיד 12+ מוסיפה אוטומטית 20% שקיפות לחלונית כזו.
            // NOT_TOUCH_MODAL = נגיעות מחוץ לחלונית ממשיכות לאפליקציה שמאחור.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (full) {
                gravity = Gravity.CENTER
                y = 0
            } else {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (metrics.heightPixels * 0.18f).toInt() // מתחת להתראה הקופצת של המערכת
            }
            title = "DailySpendOverlay"
        }
    }

    private fun bind(view: View, info: OverlayInfo) = bindCard(view, info, attachedFull)

    /** ממלא את הכרטיס (layout/overlay_spend). משמש גם את הבועה שמעל מסך הנעילה */
    fun bindCard(view: View, info: OverlayInfo, full: Boolean) {
        val ctx = view.context
        val status = BudgetStatus(info.totalMinor, info.budgetMinor)
        val card = view.findViewById<LinearLayout>(R.id.overlayCard)
        card.background = cardBackground(ctx, status.isOver, cornerDp = if (full) 0f else 28f)
        card.layoutParams = card.layoutParams.apply {
            height = if (full) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        card.gravity = if (full) Gravity.CENTER_VERTICAL else Gravity.TOP
        view.setPadding(if (full) 0 else dp(ctx, 6f).toInt(), if (full) 0 else dp(ctx, 6f).toInt(),
            if (full) 0 else dp(ctx, 6f).toInt(), if (full) 0 else dp(ctx, 6f).toInt())

        view.findViewById<TextView>(R.id.overlayHeader).text = ctx.getString(
            when {
                info.lastTx == null && !status.isOver -> R.string.overlay_header_unlock
                info.reversed -> R.string.overlay_header_reversed
                status.isOver && info.justCrossed -> R.string.overlay_header_crossed
                status.isOver -> R.string.overlay_header_over
                info.isPreview -> R.string.overlay_header_preview
                else -> R.string.overlay_header_ok
            }
        )
        val lastView = view.findViewById<TextView>(R.id.overlayLastAmount)
        if (info.lastTx != null) {
            lastView.text = ctx.getString(R.string.overlay_last_tx, Money.format(info.lastTx))
            lastView.visibility = View.VISIBLE
        } else {
            lastView.visibility = View.GONE
        }

        val extra = view.findViewById<TextView>(R.id.overlayExtra)
        val extraLines = detailLines(ctx, info)
        extra.text = extraLines.joinToString("\n")
        extra.visibility = if (extraLines.isEmpty()) View.GONE else View.VISIBLE

        val totalView = view.findViewById<TextView>(R.id.overlayTotal)
        totalView.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (full) 76f else 56f)
        countUp(totalView, info)

        val progress = view.findViewById<ProgressBar>(R.id.overlayProgress)
        applyProgress(ctx, progress, status)
        progress.visibility = if (status.hasBudget) View.VISIBLE else View.GONE

        val budgetView = view.findViewById<TextView>(R.id.overlayBudgetLine)
        budgetView.visibility = if (status.hasBudget) View.VISIBLE else View.GONE
        if (status.hasBudget) budgetView.text = budgetLine(ctx, status, info.homeCurrency)

        if (status.isOver && info.justCrossed) pulse(card) else popIn(card)
    }

    /**
     * תופס את העין: הסכום היומי "רץ" מהסכום הקודם לסכום החדש (כמו שטרות שנספרים).
     * האנימציה רצה רק כשהחלונית גלויה על המסך, ונעצרת לפני עדכון הבא.
     */
    private fun countUp(totalView: TextView, info: OverlayInfo) {
        (totalView.tag as? ValueAnimator)?.cancel()
        val target = info.totalMinor
        val from = info.previousTotalMinor
        totalView.text = Money.format(target, info.homeCurrency)
        if (from == null || from < 0 || from >= target) return
        totalView.text = Money.format(from, info.homeCurrency)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = COUNT_UP_MS // מתחיל מיד, יחד עם ההופעה
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                val value = from + ((target - from) * fraction).toLong()
                totalView.text = Money.format(value, info.homeCurrency)
            }
        }
        totalView.tag = animator
        animator.start()
    }

    /** "קפיצה" קטנה בכל הופעה */
    private fun popIn(card: View) {
        ObjectAnimator.ofPropertyValuesHolder(
            card,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.92f, 1.04f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.92f, 1.04f, 1f),
        ).apply {
            duration = 420
            start()
        }
    }

    // ---- עזרים משותפים גם למסך הראשי ולהתראה במסך הנעילה ----

    /** שורות הפירוט מתחת לעסקה: תשלומים, המרת מטבע, זמן עבודה */
    fun detailLines(ctx: Context, info: OverlayInfo): List<String> {
        val lines = ArrayList<String>()
        if (info.installments != null && info.perInstallment != null) {
            lines += ctx.getString(
                if (info.reversed) R.string.overlay_installments_excluded else R.string.overlay_installments,
                info.installments,
                Money.format(info.perInstallment)
            )
        }
        if (info.notCounted) {
            lines += ctx.getString(R.string.overlay_not_counted)
        } else if (info.lastTxHomeMinor != null) {
            val converted = Money.format(info.lastTxHomeMinor, info.homeCurrency)
            lines += ctx.getString(
                if (info.rateIsOnline) R.string.overlay_converted_online else R.string.overlay_converted_builtin,
                converted
            )
        }
        if (info.workText != null && !info.notCounted && !info.reversed) {
            lines += ctx.getString(R.string.overlay_work_time, info.workText)
        }
        return lines
    }

    /**
     * רקע הכרטיס: הצבע שבחר המשתמש (בבהירות שבחר) כל עוד לא חרגנו; אדום בולט עם מסגרת לבנה בחריגה.
     * האדום לא מושפע מהבהירות – אזהרה אף פעם לא מוחשכת.
     */
    fun cardBackground(ctx: Context, over: Boolean, cornerDp: Float = 28f): GradientDrawable {
        val colors = if (over) {
            intArrayOf(ctx.getColor(R.color.over_start), ctx.getColor(R.color.over_end))
        } else {
            val store = SpendStore.get(ctx)
            Themes.gradient(store.themeColor, store.brightnessPercent)
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
            if (status.isOver) Color.WHITE else SpendStore.get(ctx).themeColor.progress
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

    private fun dismissNow() {
        main.removeCallbacks(hideRunnable)
        hide()
    }

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
            spare = view // נשמרת לתשלום הבא, כדי לא לבנות אותה מחדש
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
            start()
        }
    }

    /** אם אין הרשאת חלונית – לפחות הודעה קצרה (Toast) */
    private fun showFallbackToast(ctx: Context, info: OverlayInfo) {
        if (info.lastTx == null) return // סיכום בפתיחת הטלפון: בלי הרשאה לא מציגים כלום
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
