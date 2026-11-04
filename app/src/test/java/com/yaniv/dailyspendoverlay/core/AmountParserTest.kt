package com.yaniv.dailyspendoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmountParserTest {

    private fun expense(text: String): AmountParser.Result.Expense {
        val r = AmountParser.parse(text)
        assertTrue("expected Expense for: $text but got $r", r is AmountParser.Result.Expense)
        return r as AmountParser.Result.Expense
    }

    private fun assertExpense(text: String, minor: Long, currency: String = "ILS") {
        val e = expense(text)
        assertEquals("amount for: $text", minor, e.amount.minor)
        assertEquals("currency for: $text", currency, e.amount.currency)
    }

    private fun assertExcluded(text: String) {
        val r = AmountParser.parse(text)
        assertTrue("expected Excluded for: $text but got $r", r is AmountParser.Result.Excluded)
    }

    private fun assertNoAmount(text: String) {
        assertEquals("expected NoAmount for: $text", AmountParser.Result.NoAmount, AmountParser.parse(text))
    }

    // ---------------- שקלים ----------------

    @Test fun shekelSignAfterNumberWithRtlMarks() = assertExpense("‏125.90 ‏₪ ב-SHUFERSAL", 12590)
    @Test fun shekelSignBeforeNumber() = assertExpense("₪34.90 with Visa ••1234", 3490)
    @Test fun hebrewBankSentence() = assertExpense("בוצעה עסקה בכרטיס המסתיים ב-1234 בסך 125.90 ש\"ח בבית העסק שופרסל", 12590)
    @Test fun thousandsSeparator() = assertExpense("חיוב בסך 1,234.50 ₪ בכרטיס 5678", 123450)
    @Test fun gershayim() = assertExpense("עסקה בסך 99 ש״ח", 9900)
    @Test fun isoCodeIls() = assertExpense("Payment of ILS 45.00 at Cafe", 4500)
    @Test fun nis() = assertExpense("NIS 12.5 charged", 1250)
    @Test fun shekelWord() = assertExpense("שילמת 30 שקל לדני", 3000)
    @Test fun shekelsWord() = assertExpense("העברת 250 שקלים", 25000)
    @Test fun noSpaceBeforeSign() = assertExpense("סה״כ 45.5₪", 4550)
    @Test fun hyphenPrefixIsNotMinus() = assertExpense("בוצעה עסקה ב-120 ₪", 12000)
    @Test fun nbspAndThinSpaces() = assertExpense("סכום: 89.90 ₪", 8990)

    @Test fun skipsBalanceBeforeTransaction() = assertExpense("יתרה: 3,000 ₪ | חיוב: 120 ₪", 12000)
    @Test fun firstAmountWinsOverLaterBalance() = assertExpense("עסקה בסך 120 ₪. יתרה זמינה: 3,000 ₪", 12000)
    @Test fun skipsZero() = assertExpense("אימות 0.00 ₪ ואז חיוב 15 ₪", 1500)

    // ---------------- מטבעות זרים ----------------

    @Test fun usdSymbol() = assertExpense("$12.50 · Visa ••4417", 1250, "USD")
    @Test fun usdCode() = assertExpense("עסקה בחו\"ל בסך 25.00 USD", 2500, "USD")
    @Test fun usdHebrewWord() = assertExpense("שילמת 40 דולר", 4000, "USD")
    @Test fun euroEuropeanFormat() = assertExpense("Zahlung 1.234,56 € bei REWE", 123456, "EUR")
    @Test fun euroCommaDecimal() = assertExpense("12,50 € at Café", 1250, "EUR")
    @Test fun euroSymbolFirst() = assertExpense("€8.40 at Boulangerie", 840, "EUR")
    @Test fun euroHebrew() = assertExpense("רכישה ב-15 יורו", 1500, "EUR")
    @Test fun gbp() = assertExpense("£3.20 at Tesco", 320, "GBP")
    @Test fun canadianBeforeUsd() = assertExpense("C$20.00 at Tim Hortons", 2000, "CAD")
    @Test fun usDollarPrefix() = assertExpense("US$9.99 subscription", 999, "USD")
    @Test fun yenNoDecimals() = assertExpense("¥1,200 at Lawson", 120000, "JPY")
    @Test fun frenchNarrowNbsp() = assertExpense("Paiement de 1 234,50 €", 123450, "EUR")
    @Test fun swissApostrophe() = assertExpense("CHF 1'234.50 Migros", 123450, "CHF")

    @Test fun lowercaseCodeIsNotCurrency() = assertNoAmount("Please try 5 more times")

    // ---------------- תשלומים ----------------

    @Test fun installmentsFullAmountFirst() {
        val e = expense("עסקה בסך 1,200 ₪ ב-12 תשלומים אושרה")
        assertEquals(120000L, e.amount.minor)
        assertEquals(12, e.installments)
        assertEquals(10000L, e.perInstallment?.minor)
    }

    @Test fun installmentsPicksFullWhenMonthlyShownFirst() {
        val e = expense("12 תשלומים של 100 ₪, סה״כ 1,200 ₪")
        assertEquals(120000L, e.amount.minor)
        assertEquals(10000L, e.perInstallment?.minor)
    }

    @Test fun installmentsRoundingTolerance() {
        val e = expense("3 תשלומים: 333.33 ₪ לחודש, סה״כ עסקה 1,000 ₪")
        assertEquals(100000L, e.amount.minor)
        assertEquals(3, e.installments)
    }

    @Test fun singlePaymentWordIsNotInstallments() {
        val e = expense("תשלום בסך 50 ₪ בוצע")
        assertEquals(null, e.installments)
    }

    @Test fun laterInstallmentExcluded() = assertExcluded("חיוב תשלום 3 מתוך 12 בסך 100 ₪")

    // ---------------- סינונים ----------------

    @Test fun refundExcluded() = assertExcluded("זיכוי בסך 50 ₪ לכרטיסך")
    @Test fun incomingExcluded() = assertExcluded("קיבלת 30 ₪ מדני")
    @Test fun negativeExcluded() = assertExcluded("-20.00 ₪ החזר")
    @Test fun negativeAfterSignExcluded() = assertExcluded("₪-20.00")
    @Test fun promoExcluded() = assertExcluded("קבלו 50 ₪ הנחה בקנייה הבאה!")
    @Test fun requestExcluded() = assertExcluded("דני מבקש ממך 50 ₪")
    @Test fun englishRefundExcluded() = assertExcluded("Refund of $12.00 processed")

    // ---------------- בלי סכום ----------------

    @Test fun otpHasNoAmount() = assertNoAmount("קוד האימות שלך: 123456")
    @Test fun cardDigitsOnly() = assertNoAmount("הכרטיס המסתיים ב-1234 נוסף לארנק")
    @Test fun dateAndTime() = assertNoAmount("פגישה ב-04/11 בשעה 15:49")
    @Test fun wordsContainingShach() = assertNoAmount("5 שחקנים הצטרפו")
    @Test fun ambiguousDotNumberInsideWord() = assertNoAmount("גרסה 1.234")
}
