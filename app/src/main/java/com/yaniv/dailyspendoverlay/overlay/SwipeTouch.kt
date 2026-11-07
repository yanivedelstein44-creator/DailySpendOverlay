package com.yaniv.dailyspendoverlay.overlay

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import com.yaniv.dailyspendoverlay.core.SwipeDismiss
import com.yaniv.dailyspendoverlay.core.SwipeDismiss.Gesture

/**
 * גרירה של הבועה: ימינה היא זזה עם האצבע, נעשית שקופה ויוצאת מהמסך; שמאלה זזה מעט וחוזרת.
 * נגיעה רגילה (בלי תזוזה) = performClick.
 * ההחלטות עצמן (כמה לגרור, מהירות, "גומי") ב-core/SwipeDismiss.
 * בלי טיימרים: הכול קורה רק בזמן שהאצבע על הבועה, ועוד אנימציה קצרה בסוף.
 */
class SwipeTouch(private val listener: Listener) : View.OnTouchListener {

    interface Listener {
        /** האצבע נגעה בבועה – לא לסגור אותה אוטומטית באמצע גרירה */
        fun onTouchStart(view: View)

        /** שוחררה בלי להיסגר (חוזרת למקום) */
        fun onSettled(view: View)

        /** הוחלקה ימינה ויצאה מהמסך */
        fun onSwipedAway(view: View)
    }

    private var downX = 0f
    private var downY = 0f
    private var gesture = Gesture.UNDECIDED
    private var tracker: VelocityTracker? = null

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                gesture = Gesture.UNDECIDED
                v.animate().cancel()
                tracker?.recycle()
                tracker = VelocityTracker.obtain()
                track(event)
                listener.onTouchStart(v)
            }
            MotionEvent.ACTION_MOVE -> {
                track(event)
                val dx = event.rawX - downX
                if (gesture == Gesture.UNDECIDED) {
                    val slop = ViewConfiguration.get(v.context).scaledTouchSlop.toFloat()
                    gesture = SwipeDismiss.classify(dx, event.rawY - downY, slop)
                }
                if (gesture == Gesture.HORIZONTAL) {
                    val offset = SwipeDismiss.offsetFor(dx)
                    v.translationX = offset
                    v.alpha = SwipeDismiss.alphaFor(offset, v.width.toFloat())
                }
            }
            MotionEvent.ACTION_UP -> {
                track(event)
                val velocityX = tracker?.let {
                    it.computeCurrentVelocity(1000)
                    it.xVelocity
                } ?: 0f
                releaseTracker()
                val dx = event.rawX - downX
                val density = v.resources.displayMetrics.density
                when (gesture) {
                    Gesture.HORIZONTAL ->
                        if (SwipeDismiss.shouldDismiss(dx, velocityX, v.width.toFloat(), density)) flyAway(v)
                        else settle(v)
                    Gesture.UNDECIDED -> {
                        settle(v)
                        v.performClick() // נגיעה רגילה
                    }
                    Gesture.VERTICAL -> settle(v)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseTracker()
                settle(v)
            }
        }
        return true
    }

    /** המהירות לפי מיקום על המסך (rawX), כי הבועה עצמה זזה עם האצבע */
    private fun track(event: MotionEvent) {
        val copy = MotionEvent.obtain(event)
        copy.setLocation(event.rawX, event.rawY)
        tracker?.addMovement(copy)
        copy.recycle()
    }

    private fun releaseTracker() {
        tracker?.recycle()
        tracker = null
    }

    private fun settle(v: View) {
        v.animate()
            .translationX(0f)
            .translationY(0f)
            .alpha(1f)
            .setDuration(SETTLE_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        listener.onSettled(v)
    }

    private fun flyAway(v: View) {
        v.animate()
            .translationX(v.width + 24 * v.resources.displayMetrics.density)
            .alpha(0f)
            .setDuration(FLY_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { listener.onSwipedAway(v) } // לא רץ אם תשלום חדש עצר את האנימציה
            .start()
    }

    private companion object {
        const val SETTLE_MS = 180L
        const val FLY_MS = 170L
    }
}
