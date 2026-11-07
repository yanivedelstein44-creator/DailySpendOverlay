package com.yaniv.dailyspendoverlay.core

import com.yaniv.dailyspendoverlay.core.SwipeDismiss.Gesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeDismissTest {

    // טלפון רגיל: 2.625 פיקסלים ל-dp, בועה ברוחב 1030 פיקסלים
    private val density = 2.625f
    private val width = 1030f

    @Test fun slowDragPastAThirdCloses() {
        assertTrue(SwipeDismiss.shouldDismiss(dx = 400f, velocityX = 0f, width = width, density = density))
    }

    @Test fun shortSlowDragStays() {
        assertFalse(SwipeDismiss.shouldDismiss(dx = 200f, velocityX = 300f, width = width, density = density))
    }

    @Test fun quickFlickRightCloses() {
        // 30dp ימינה, אבל מהר
        assertTrue(SwipeDismiss.shouldDismiss(dx = 80f, velocityX = 3_000f, width = width, density = density))
    }

    @Test fun shakyTouchDoesNotClose() {
        // מהיר אבל כמעט בלי תזוזה – נגיעה רועדת
        assertFalse(SwipeDismiss.shouldDismiss(dx = 20f, velocityX = 5_000f, width = width, density = density))
    }

    @Test fun leftNeverCloses() {
        assertFalse(SwipeDismiss.shouldDismiss(dx = -600f, velocityX = -5_000f, width = width, density = density))
        assertFalse(SwipeDismiss.shouldDismiss(dx = -600f, velocityX = 0f, width = width, density = density))
    }

    @Test fun draggedRightThenThrownBackStays() {
        assertFalse(SwipeDismiss.shouldDismiss(dx = 500f, velocityX = -4_000f, width = width, density = density))
    }

    @Test fun leftDragIsRubberBand() {
        assertEquals(100f, SwipeDismiss.offsetFor(100f), 0.001f)
        assertEquals(-20f, SwipeDismiss.offsetFor(-100f), 0.001f)
    }

    @Test fun fadesWhileDraggingRight() {
        assertEquals(1f, SwipeDismiss.alphaFor(0f, width), 0.001f)
        assertEquals(1f, SwipeDismiss.alphaFor(-50f, width), 0.001f)
        val half = SwipeDismiss.alphaFor(width / 2, width)
        assertTrue(half in 0.5f..0.6f)
        assertEquals(SwipeDismiss.MIN_ALPHA, SwipeDismiss.alphaFor(width * 2, width), 0.001f)
    }

    @Test fun gestureClassification() {
        val slop = 21f
        assertEquals(Gesture.UNDECIDED, SwipeDismiss.classify(10f, 5f, slop))
        assertEquals(Gesture.HORIZONTAL, SwipeDismiss.classify(40f, 10f, slop))
        assertEquals(Gesture.HORIZONTAL, SwipeDismiss.classify(-40f, 10f, slop))
        assertEquals(Gesture.VERTICAL, SwipeDismiss.classify(10f, 40f, slop))
        assertEquals(Gesture.VERTICAL, SwipeDismiss.classify(30f, 60f, slop))
    }
}
