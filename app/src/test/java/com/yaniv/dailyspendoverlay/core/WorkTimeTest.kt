package com.yaniv.dailyspendoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkTimeTest {

    @Test fun exampleFromSpec() {
        // 45.90 ₪ בשכר 110 ₪ לשעה = 25 דקות
        assertEquals(25L, WorkTime.minutes(4590, 11000))
        assertEquals("25 דקות", WorkTime.describe(4590, 11000))
    }

    @Test fun noWageMeansNoText() {
        assertNull(WorkTime.describe(4590, 0))
        assertNull(WorkTime.minutes(0, 11000))
    }

    @Test fun formatting() {
        assertEquals("פחות מדקה", WorkTime.format(0))
        assertEquals("דקה", WorkTime.format(1))
        assertEquals("שעה", WorkTime.format(60))
        assertEquals("שעה ו-20 דקות", WorkTime.format(80))
        assertEquals("שעתיים", WorkTime.format(120))
        assertEquals("3 שעות ו-5 דקות", WorkTime.format(185))
        assertEquals("שעתיים ודקה", WorkTime.format(121))
    }

    @Test fun smallAmountRoundsToLessThanAMinute() {
        assertEquals("פחות מדקה", WorkTime.describe(50, 11000)) // 0.27 דקות
    }
}
