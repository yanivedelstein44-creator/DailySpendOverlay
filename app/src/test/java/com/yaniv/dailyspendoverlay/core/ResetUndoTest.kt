package com.yaniv.dailyspendoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResetUndoTest {

    private val today = "2026-11-05"
    private fun ils(minor: Long) = Amount(minor, "ILS")

    private fun state(total: Long, count: Int, last: Amount? = null, day: String = today) =
        LedgerState(day = day, totalMinor = total, count = count, lastTx = last, lastTxHomeMinor = last?.minor ?: 0)

    @Test fun resetThenRestoreBringsEverythingBack() {
        val before = state(24535, 3, ils(17535))
        val backup = ResetUndo.backup(before, null, today)
        val afterReset = before.copy(totalMinor = 0, count = 0, lastTx = null, lastTxHomeMinor = 0)
        val restored = ResetUndo.restore(afterReset, backup, today)!!
        assertEquals(24535L, restored.totalMinor)
        assertEquals(3, restored.count)
        assertEquals(ils(17535), restored.lastTx)
    }

    @Test fun paymentsAfterResetAreKept() {
        val backup = ResetUndo.backup(state(10000, 2, ils(4000)), null, today)
        val afterNewPayment = state(1390, 1, ils(1390))
        val restored = ResetUndo.restore(afterNewPayment, backup, today)!!
        assertEquals(11390L, restored.totalMinor)
        assertEquals(3, restored.count)
        assertEquals(ils(1390), restored.lastTx) // העסקה האחרונה היא זו שאחרי האיפוס
    }

    @Test fun secondResetAccumulates() {
        val first = ResetUndo.backup(state(10000, 2, ils(4000)), null, today)
        val second = ResetUndo.backup(state(500, 1, ils(500)), first, today)!!
        assertEquals(10500L, second.totalMinor)
        assertEquals(3, second.count)
        assertEquals(ils(500), second.lastTx)
    }

    @Test fun resettingZeroKeepsExistingBackup() {
        val first = ResetUndo.backup(state(10000, 2), null, today)
        val second = ResetUndo.backup(state(0, 0), first, today)
        assertEquals(first, second)
    }

    @Test fun backupFromYesterdayIsNotRestored() {
        val old = ResetUndo.backup(state(10000, 2, day = "2026-11-04"), null, "2026-11-04")
        assertFalse(ResetUndo.canRestore(old, today))
        assertNull(ResetUndo.restore(state(0, 0), old, today))
    }

    @Test fun nothingToRestoreWithoutBackup() {
        assertFalse(ResetUndo.canRestore(null, today))
        assertNull(ResetUndo.restore(state(500, 1), null, today))
    }

    @Test fun restoreOnNewDayStartsFromZeroOfToday() {
        val backup = ResetUndo.backup(state(10000, 2, ils(4000)), null, today)
        val stale = state(999, 1, ils(999), day = "2026-11-04")
        val restored = ResetUndo.restore(stale, backup, today)!!
        assertEquals(10000L, restored.totalMinor)
        assertTrue(restored.day == today)
    }
}
