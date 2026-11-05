package com.yaniv.dailyspendoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceEventsTest {

    @Test fun pendingWithinAnHourIsFresh() {
        assertTrue(DeviceEvents.isPendingFresh(1_000_000, 1_000_000 + 59 * 60_000L))
    }

    @Test fun pendingOlderThanAnHourIsStale() {
        assertFalse(DeviceEvents.isPendingFresh(1_000_000, 1_000_000 + 61 * 60_000L))
    }

    @Test fun noPendingIsNeverFresh() {
        assertFalse(DeviceEvents.isPendingFresh(0, 5_000))
    }

    @Test fun pendingInTheFutureIsIgnored() {
        assertFalse(DeviceEvents.isPendingFresh(10_000, 5_000))
    }

    @Test fun shortDelayIsNotLate() {
        assertNull(DeviceEvents.lateText(19_000))
        assertNull(DeviceEvents.lateText(0))
        assertNull(DeviceEvents.lateText(-5_000))
    }

    @Test fun lateTextUsesReadableUnits() {
        assertEquals("45 שניות", DeviceEvents.lateText(45_000))
        assertEquals("95 שניות", DeviceEvents.lateText(95_000))
        assertEquals("7 דקות", DeviceEvents.lateText(7 * 60_000L))
        assertEquals("שעה", DeviceEvents.lateText(61 * 60_000L))
        assertEquals("3 שעות", DeviceEvents.lateText(3 * 3_600_000L))
    }

    @Test fun unlockRightAfterLockBubbleDoesNotRepeat() {
        val paid = 1_000_000L
        assertFalse(DeviceEvents.showPendingOnUnlock(paid, paid + 2_000, paid + 8_000))
    }

    @Test fun unlockLaterShowsAgain() {
        val paid = 1_000_000L
        assertTrue(DeviceEvents.showPendingOnUnlock(paid, paid + 2_000, paid + 120_000))
    }

    @Test fun unlockWithoutLockBubbleShows() {
        val paid = 1_000_000L
        assertTrue(DeviceEvents.showPendingOnUnlock(paid, 0, paid + 5_000))
        assertTrue(DeviceEvents.showPendingOnUnlock(paid, paid - 60_000, paid + 5_000)) // בועה ישנה של תשלום קודם
    }
}
