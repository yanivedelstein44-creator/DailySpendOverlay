package com.yaniv.dailyspendoverlay.overlay

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import com.yaniv.dailyspendoverlay.R
import com.yaniv.dailyspendoverlay.core.WorkTime
import com.yaniv.dailyspendoverlay.data.SpendStore
import kotlin.math.min

/**
 * הבועה מעל מסך הנעילה.
 *
 * חלונית צפה לא יכולה להופיע מעל מסך הנעילה. לכן, כשתשלום מזוהה והטלפון נעול, LockScreenNotifier
 * שולח "התראה במסך מלא" – והמערכת עצמה פותחת את המסך הזה מעל הנעילה (כמו שעון מעורר).
 * הוא מדליק את המסך, מציג את אותו כרטיס כמו החלונית (או מסך מלא, לפי אותו מתג), ונסגר לבד אחרי אותן 6 שניות (או בנגיעה).
 * הטיימר רץ רק כשהמסך הזה פתוח וגלוי. אין כאן כפתורים או דרך להיכנס לאפליקציה בלי לפתוח את הנעילה.
 */
class LockScreenBubbleActivity : Activity() {

    companion object {
        /** מתי הבועה הוצגה לאחרונה – כדי לא להציג אותה שוב מיד כשפותחים את הנעילה */
        @Volatile
        var lastShownAt = 0L
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val finishRunnable = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        if (!render()) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // תשלום נוסף בזמן שהבועה מוצגת: מעדכנים את התוכן ומתחילים את 6 השניות מחדש
        if (render()) restartTimer() else finish()
    }

    override fun onResume() {
        super.onResume()
        lastShownAt = System.currentTimeMillis()
        restartTimer()
    }

    override fun onPause() {
        // כשהמסך נכבה המסך הזה "מושהה" ולא נסגר: כשהמסך יידלק שוב הוא יוצג עוד 6 שניות.
        // (Activity שנפתח כשהמסך כבוי עובר רגע דרך onPause לפני שהמסך נדלק – אסור לסגור אותו כאן.)
        handler.removeCallbacks(finishRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        super.onDestroy()
    }

    private fun restartTimer() {
        handler.removeCallbacks(finishRunnable)
        handler.postDelayed(finishRunnable, OverlayManager.VISIBLE_MS)
    }

    /** בונה את הכרטיס. false = אין מה להציג */
    private fun render(): Boolean {
        val store = SpendStore.get(this)
        val info = LockScreenNotifier.takeBubbleInfo() ?: infoFromStore(store) ?: return false
        LockScreenNotifier.cancel(this) // ההתראה עצמה כבר מיותרת – הבועה מוצגת

        val full = store.overlayFull // כמו החלונית: כרטיס, ומסך מלא רק אם המתג דלוק
        val root = FrameLayout(this)
        root.setOnClickListener { dismissSeen(store) }
        val themed = ContextThemeWrapper(this, R.style.Theme_DailySpend_Overlay)
        val card = LayoutInflater.from(themed).inflate(R.layout.overlay_spend, root, false)
        card.setOnClickListener { dismissSeen(store) }

        val metrics = resources.displayMetrics
        val density = metrics.density
        val width = if (full) ViewGroup.LayoutParams.MATCH_PARENT
        else min(metrics.widthPixels - (24 * density).toInt(), (440 * density).toInt())
        val params = FrameLayout.LayoutParams(
            width,
            if (full) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (full) Gravity.CENTER else Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        )
        if (!full) params.topMargin = (metrics.heightPixels * 0.18f).toInt()
        root.addView(card, params)
        setContentView(root)
        OverlayManager.bindCard(card, info, full)
        return true
    }

    /** נגיעה = ראיתי. לכן לא מציגים את אותו תשלום שוב כשפותחים את הנעילה */
    private fun dismissSeen(store: SpendStore) {
        store.pendingUnlockMillis = 0L
        finish()
    }

    /** אם התהליך נסגר בינתיים – בונים את הכרטיס מהסכום השמור של היום */
    private fun infoFromStore(store: SpendStore): OverlayInfo? {
        val today = store.todaySnapshot()
        val last = today.lastTx ?: return null
        val home = store.homeCurrency
        return OverlayInfo(
            lastTx = last,
            lastTxHomeMinor = if (last.currency != home) today.lastTxHomeMinor else null,
            totalMinor = today.totalMinor,
            budgetMinor = store.budgetMinor,
            homeCurrency = home,
            justCrossed = false,
            workText = WorkTime.describe(today.lastTxHomeMinor, store.hourlyWageMinor),
            previousTotalMinor = today.totalMinor - today.lastTxHomeMinor,
        )
    }
}
