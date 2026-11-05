package com.yaniv.dailyspendoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Test

class LogReplayTest {

    private val today = "2026-11-05"
    private val base = 1_762_327_320_000L // בוקר
    private fun ils(minor: Long) = Amount(minor, "ILS")
    private fun at(minutes: Int, minor: Long) = LogReplay.Item(base + minutes * 60_000L, ils(minor), minor)

    @Test fun sumsTodaysPayments() {
        val state = LogReplay.rebuild(listOf(at(0, 4590), at(30, 7010), at(60, 25000)), today)
        assertEquals(36600L, state.totalMinor)
        assertEquals(3, state.count)
        assertEquals(ils(25000), state.lastTx)
    }

    @Test fun duplicatesWithinTenMinutesCountOnce() {
        // מהיומן האמיתי: 70.10 ב-08:42 ושוב ב-08:47, 175.35 ב-08:42 ושוב ב-08:48
        val state = LogReplay.rebuild(
            listOf(at(0, 7010), at(0, 17535), at(5, 7010), at(5, 1390), at(6, 17535), at(163, 25000)),
            today,
        )
        assertEquals(7010L + 17535 + 1390 + 25000, state.totalMinor)
        assertEquals(4, state.count)
    }

    @Test fun orderDoesNotMatter() {
        val sorted = LogReplay.rebuild(listOf(at(0, 4590), at(3, 4590), at(20, 1000)), today)
        val shuffled = LogReplay.rebuild(listOf(at(20, 1000), at(3, 4590), at(0, 4590)), today)
        assertEquals(sorted.totalMinor, shuffled.totalMinor)
        assertEquals(5590L, shuffled.totalMinor)
    }

    @Test fun installmentExclusionReversesTheWalletCount() {
        val wallet = at(0, 120000)
        val excluded = LogReplay.Item(base + 60_000L, ils(120000), 0, installmentExclusion = true)
        val state = LogReplay.rebuild(listOf(at(-30, 4590), wallet, excluded), today)
        assertEquals(4590L, state.totalMinor)
        assertEquals(1, state.count)
    }

    @Test fun emptyLogIsZero() {
        val state = LogReplay.rebuild(emptyList(), today)
        assertEquals(0L, state.totalMinor)
        assertEquals(0, state.count)
    }
}
