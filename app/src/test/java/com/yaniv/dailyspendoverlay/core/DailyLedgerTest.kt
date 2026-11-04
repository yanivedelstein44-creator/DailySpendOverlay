package com.yaniv.dailyspendoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyLedgerTest {

    private val day1 = "2026-11-04"
    private val day2 = "2026-11-05"
    private val t0 = 1_000_000L

    private fun ils(minor: Long) = Amount(minor, "ILS")

    private fun count(state: LedgerState, amount: Amount, at: Long, day: String = day1, fp: String? = null, home: Long = amount.minor) =
        DailyLedger.register(state, amount, home, fp, at, day)

    @Test fun accumulatesWithinTheDay() {
        val a = count(LedgerState(), ils(4590), t0) as LedgerResult.Counted
        val b = count(a.state, ils(12000), t0 + 120_000) as LedgerResult.Counted
        assertEquals(16590L, b.state.totalMinor)
        assertEquals(2, b.state.count)
        assertEquals(4590L, b.previousTotalMinor)
    }

    @Test fun newDayResetsBeforeAdding() {
        val a = count(LedgerState(), ils(30000), t0) as LedgerResult.Counted
        val b = count(a.state, ils(1000), t0 + 86_400_000, day = day2) as LedgerResult.Counted
        assertEquals(1000L, b.state.totalMinor)
        assertEquals(1, b.state.count)
        assertEquals(day2, b.state.day)
        assertEquals(0L, b.previousTotalMinor)
    }

    @Test fun rolloverForDisplay() {
        val a = count(LedgerState(), ils(5000), t0) as LedgerResult.Counted
        val shown = DailyLedger.rollover(a.state, day2)
        assertEquals(0L, shown.totalMinor)
        assertNull(shown.lastTx)
    }

    @Test fun sameAmountWithin60SecondsIsDuplicate() {
        val a = count(LedgerState(), ils(4590), t0, fp = "wallet")
        val b = count(a.state, ils(4590), t0 + 59_000, fp = "card")
        assertTrue(b is LedgerResult.Duplicate)
        assertEquals(4590L, b.state.totalMinor)
    }

    @Test fun sameAmountAfter60SecondsCounts() {
        val a = count(LedgerState(), ils(1200), t0)
        val b = count(a.state, ils(1200), t0 + 61_000)
        assertTrue(b is LedgerResult.Counted)
        assertEquals(2400L, b.state.totalMinor)
    }

    @Test fun slidingWindowCatchesThirdReport() {
        val a = count(LedgerState(), ils(4590), t0)
        val b = count(a.state, ils(4590), t0 + 40_000)   // כפילות – מאריכה את החלון
        val c = count(b.state, ils(4590), t0 + 95_000)   // 55 שניות אחרי הכפילות
        assertTrue(b is LedgerResult.Duplicate)
        assertTrue(c is LedgerResult.Duplicate)
        assertEquals(4590L, c.state.totalMinor)
    }

    @Test fun differentAmountWithin60SecondsCounts() {
        val a = count(LedgerState(), ils(4590), t0)
        val b = count(a.state, ils(3000), t0 + 10_000)
        assertTrue(b is LedgerResult.Counted)
        assertEquals(7590L, b.state.totalMinor)
    }

    @Test fun sameNumberDifferentCurrencyIsNotDuplicate() {
        val a = count(LedgerState(), ils(1250), t0)
        val b = count(a.state, Amount(1250, "USD"), t0 + 5_000, home = 3910)
        assertTrue(b is LedgerResult.Counted)
        assertEquals(1250L + 3910L, b.state.totalMinor)
    }

    @Test fun repostedNotificationIsDuplicateEvenLater() {
        val a = count(LedgerState(), ils(9900), t0, fp = "key|123|ILS:9900")
        val b = count(a.state, ils(9900), t0 + 600_000, fp = "key|123|ILS:9900")
        assertTrue(b is LedgerResult.Duplicate)
        assertEquals(9900L, b.state.totalMinor)
    }

    @Test fun foreignAmountAddsConvertedValue() {
        val r = count(LedgerState(), Amount(1250, "USD"), t0, home = 3910) as LedgerResult.Counted
        assertEquals(3910L, r.state.totalMinor)
        assertEquals(Amount(1250, "USD"), r.state.lastTx)
    }

    // ---------------- "להציג רק הוצאות יומיות" (החרגת תשלומים) ----------------

    @Test fun excludedInstallmentReversesWalletCount() {
        val wallet = count(LedgerState(), ils(120000), t0)                    // Google Wallet: 1,200 ₪ בלי פרטי תשלומים
        val card = DailyLedger.exclude(wallet.state, ils(120000), "card", t0 + 8_000, day1)
        assertTrue(card is LedgerResult.Reversed)
        assertEquals(0L, card.state.totalMinor)
        assertEquals(0, card.state.count)
        assertEquals(120000L, (card as LedgerResult.Reversed).removedHomeMinor)
    }

    @Test fun excludedInstallmentBlocksLaterWalletReport() {
        val card = DailyLedger.exclude(LedgerState(), ils(120000), "card", t0, day1)
        assertTrue(card is LedgerResult.Excluded)
        val wallet = count(card.state, ils(120000), t0 + 5_000)
        assertTrue(wallet is LedgerResult.Duplicate)
        assertEquals(0L, wallet.state.totalMinor)
    }

    @Test fun exclusionKeepsOtherPurchases() {
        val coffee = count(LedgerState(), ils(1500), t0)
        val tv = count(coffee.state, ils(300000), t0 + 1_000)
        val excluded = DailyLedger.exclude(tv.state, ils(300000), "card", t0 + 3_000, day1)
        assertEquals(1500L, excluded.state.totalMinor)
        assertEquals(1, excluded.state.count)
    }

    // ---------------- תקציב ----------------

    @Test fun budgetStatus() {
        assertTrue(!BudgetStatus(25000, 25000).isOver)   // בדיוק על התקציב – עדיין רגוע
        assertTrue(BudgetStatus(25001, 25000).isOver)
        assertEquals(74, BudgetStatus(18590, 25000).percent)
        assertEquals(-6200L, BudgetStatus(31200, 25000).remainingMinor)
    }

    @Test fun noBudgetMeansNeverOver() {
        val s = BudgetStatus(999_999, 0)
        assertTrue(!s.hasBudget)
        assertTrue(!s.isOver)
        assertEquals(0, s.percent)
        assertTrue(BudgetStatus(1, 25000).hasBudget)
    }

    @Test fun recentEncodingRoundTrip() {
        val list = listOf(RecentAmount("ILS:4590", 123L, 4590L, true), RecentAmount("USD:1250", 456L, 0L, false))
        assertEquals(list, DailyLedger.decodeRecent(DailyLedger.encodeRecent(list)))
    }
}
