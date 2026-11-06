package com.yaniv.dailyspendoverlay.core

import com.yaniv.dailyspendoverlay.core.CashBreakdown.Kind
import com.yaniv.dailyspendoverlay.core.CashBreakdown.Piece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CashBreakdownTest {

    @Test fun shekelPaymentLikeTheMockup() {
        // 187.43 ₪ → 100 + 50 + 20 + 10 + 5 + 2 + 40 אג׳ (מעוגל ל-187.40)
        assertEquals(
            listOf(
                Piece(Kind.BILL, 10_000, 1), Piece(Kind.BILL, 5_000, 1), Piece(Kind.BILL, 2_000, 1),
                Piece(Kind.COIN, 1_000, 1), Piece(Kind.COIN, 500, 1), Piece(Kind.COIN, 200, 1),
                Piece(Kind.SMALL, 40, 1),
            ),
            CashBreakdown.of(18_743, "ILS"),
        )
    }

    @Test fun repeatedBillsAreCounted() {
        // 45.90 ₪ → 20 × 2 + 5 + 90 אג׳
        assertEquals(
            listOf(Piece(Kind.BILL, 2_000, 2), Piece(Kind.COIN, 500, 1), Piece(Kind.SMALL, 90, 1)),
            CashBreakdown.of(4_590, "ILS"),
        )
        // 950 ₪ → 200 × 4 + 100 + 50
        assertEquals(
            listOf(Piece(Kind.BILL, 20_000, 4), Piece(Kind.BILL, 10_000, 1), Piece(Kind.BILL, 5_000, 1)),
            CashBreakdown.of(95_000, "ILS"),
        )
    }

    @Test fun shekelCashRoundingToTenAgorot() {
        assertEquals(2_740L, CashBreakdown.roundForCash(2_743, "ILS"))
        assertEquals(2_750L, CashBreakdown.roundForCash(2_745, "ILS"))
        assertEquals(2_750L, CashBreakdown.roundForCash(2_749, "ILS"))
        // 4 אגורות – אין מה להוציא מהארנק
        assertEquals(emptyList<Piece>(), CashBreakdown.of(4, "ILS"))
    }

    @Test fun euroPaymentLikeTheMockup() {
        // €37.80 → 20 + 10 + 5 + 2€ + 80 סנט
        assertEquals(
            listOf(
                Piece(Kind.BILL, 2_000, 1), Piece(Kind.BILL, 1_000, 1), Piece(Kind.BILL, 500, 1),
                Piece(Kind.COIN, 200, 1), Piece(Kind.SMALL, 80, 1),
            ),
            CashBreakdown.of(Amount(3_780, "EUR")),
        )
    }

    @Test fun dollarPaymentLikeTheMockup() {
        // $42.65 → 20 × 2 + 1 × 2 + 65 סנט
        assertEquals(
            listOf(Piece(Kind.BILL, 2_000, 2), Piece(Kind.BILL, 100, 2), Piece(Kind.SMALL, 65, 1)),
            CashBreakdown.of(4_265, "usd"),
        )
    }

    @Test fun describeForTheWalletLine() {
        // 75 ₪ שנשארו בארנק → "50 + 20 + 5"
        assertEquals("50 + 20 + 5", CashBreakdown.describe(CashBreakdown.of(7_500, "ILS")!!, "ILS"))
        // 45.90 ₪ → "20 + 20 + 5 + 90 אג׳"
        assertEquals("20 + 20 + 5 + 90 אג׳", CashBreakdown.describe(CashBreakdown.of(4_590, "ILS")!!, "ILS"))
        // 950 ₪ → "200 ×4 + 100 + 50"
        assertEquals("200 ×4 + 100 + 50", CashBreakdown.describe(CashBreakdown.of(95_000, "ILS")!!, "ILS"))
        // €37.80 → "20 + 10 + 5 + 2 + 80 סנט"
        assertEquals("20 + 10 + 5 + 2 + 80 סנט", CashBreakdown.describe(CashBreakdown.of(3_780, "EUR")!!, "EUR"))
    }

    @Test fun unsupportedCurrencyAndZero() {
        assertNull(CashBreakdown.of(1_000, "GBP"))
        assertFalse(CashBreakdown.supports("JPY"))
        assertTrue(CashBreakdown.supports("eur"))
        assertEquals(emptyList<Piece>(), CashBreakdown.of(0, "ILS"))
    }

    @Test fun piecesAlwaysAddUpToTheRoundedAmount() {
        for (minor in listOf(1L, 99L, 105L, 1_999L, 12_345L, 87_654L, 1_000_001L)) {
            for (currency in listOf("ILS", "EUR", "USD")) {
                val sum = CashBreakdown.of(minor, currency)!!.sumOf { it.minor * it.count }
                assertEquals("$minor $currency", CashBreakdown.roundForCash(minor, currency), sum)
            }
        }
    }
}
