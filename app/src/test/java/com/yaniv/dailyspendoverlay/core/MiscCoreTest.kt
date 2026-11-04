package com.yaniv.dailyspendoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscCoreTest {

    // ---------------- Money ----------------

    @Test fun formatShekels() {
        assertEquals("340 ₪", Money.format(34000, "ILS"))
        assertEquals("45.90 ₪", Money.format(4590, "ILS"))
        assertEquals("1,234.50 ₪", Money.format(123450, "ILS"))
        assertEquals("$12.50", Money.format(1250, "USD"))
        assertEquals("¥1,200", Money.format(120000, "JPY"))
        assertEquals("45 CHF", Money.format(4500, "CHF"))
    }

    @Test fun parseUserBudget() {
        assertEquals(25000L, Money.parseToMinor("250"))
        assertEquals(25050L, Money.parseToMinor("250.5"))
        assertEquals(100000L, Money.parseToMinor("1,000"))
        assertNull(Money.parseToMinor("abc"))
        assertNull(Money.parseToMinor("-5"))
    }

    @Test fun parseFlexibleFormats() {
        assertEquals(123456L, Money.parseFlexibleToMinor("1,234.56"))
        assertEquals(123456L, Money.parseFlexibleToMinor("1.234,56"))
        assertEquals(123400L, Money.parseFlexibleToMinor("1.234"))
        assertEquals(1250L, Money.parseFlexibleToMinor("12,5"))
        assertEquals(12000L, Money.parseFlexibleToMinor("120"))
    }

    // ---------------- Sources ----------------

    @Test fun rulesMatching() {
        val rules = SourceRules.parse(
            """
            com.google.android.apps.walletnfcrel   # Google Wallet
            com.some.bank|max
            sms:max
            """.trimIndent()
        )
        assertTrue(rules.matches("com.google.android.apps.walletnfcrel", "anything"))
        assertTrue(rules.matches("com.some.bank", "MAX: עסקה"))
        assertFalse(rules.matches("com.some.bank", "הודעה כללית"))
        assertTrue(rules.matches("com.google.android.apps.messaging", "\u200Fmax"))
        assertFalse(rules.matches("com.google.android.apps.messaging", "Max Stock"))
        assertFalse(rules.matches("com.whatsapp", "max"))
    }

    @Test fun defaultRulesIncludeIsraeliCardApps() {
        val rules = SourceRules.parse(SourceRules.DEFAULT_TEXT)
        assertTrue(rules.matches("com.ideomobile.leumicard", "x"))   // max
        assertTrue(rules.matches("com.isracard.hatavot", "x"))
        assertTrue(rules.matches("com.onoapps.cal4u", "x"))
        assertTrue(rules.matches("com.google.android.apps.walletnfcrel", "x"))
    }

    @Test fun appendRuleOnce() {
        val once = SourceRules.appendRule("a.b.c", "com.bank.app", "Bank")
        assertEquals("a.b.c\ncom.bank.app   # Bank", once)
        assertEquals(once, SourceRules.appendRule(once, "com.bank.app", "Bank"))
        assertEquals("sms:max", SourceRules.ruleLineFor("com.google.android.apps.messaging", "max"))
    }

    // ---------------- Currencies & FX ----------------

    @Test fun currencyAliases() {
        assertEquals("ILS", Currencies.fromAlias("ש״ח"))
        assertEquals("USD", Currencies.fromAlias("usd"))
        assertEquals("EUR", Currencies.fromAlias("€"))
        assertNull(Currencies.fromAlias("xyz"))
        assertEquals("ILS", Currencies.defaultFor("ILS"))
        assertEquals("ILS", Currencies.defaultFor("XXX"))
        assertEquals("USD", Currencies.defaultFor("USD"))
    }

    @Test fun builtInConversionUsdToIls() {
        val c = FxRates.convert(Amount(1250, "USD"), "ILS", null)
        assertNotNull(c)
        // 12.50 USD × (3.495 / 1.1172) ≈ 39.10 ₪
        assertEquals(3910L, c!!.homeMinor)
        assertFalse(c.online)
    }

    @Test fun sameCurrencyNoConversion() {
        assertEquals(4590L, FxRates.convert(Amount(4590, "ILS"), "ILS", null)!!.homeMinor)
    }

    @Test fun unknownRateReturnsNull() {
        assertNull(FxRates.convert(Amount(1000, "THB"), "ILS", null))
    }

    @Test fun parsesEcbXml() {
        val xml = """
            <gesmes:Envelope><Cube><Cube time='2026-11-04'>
            <Cube currency='USD' rate='1.1200'/><Cube currency='JPY' rate='176.10'/>
            <Cube currency='GBP' rate='0.8350'/><Cube currency='ILS' rate='3.5000'/>
            <Cube currency='THB' rate='36.50'/><Cube currency='CHF' rate='0.9200'/>
            </Cube></Cube></gesmes:Envelope>
        """.trimIndent()
        val table = FxRates.parseEcbXml(xml)!!
        assertEquals("2026-11-04", table.date)
        assertTrue(table.online)
        assertEquals(36.5, table.perEur.getValue("THB"), 0.0001)
        // עם שערים מהרשת יש גם שער לבאט
        val c = FxRates.convert(Amount(10000, "THB"), "ILS", table)!!
        assertEquals(Math.round(10000 / 36.5 * 3.5), c.homeMinor)
        assertTrue(c.online)
        // קידוד ופענוח לשמירה
        assertEquals(table.perEur, FxRates.decode(FxRates.encode(table))!!.perEur)
    }

    // ---------------- Detection log ----------------

    @Test fun detectionLogRoundTrip() {
        val e = DetectionEntry(1L, "pkg", "App", "קפה\nהפינה", Amount(4590, "ILS"), DetectionStatus.COUNTED, "x")
        val decoded = DetectionLog.decode(DetectionLog.encode(listOf(e)))
        assertEquals(1, decoded.size)
        assertEquals("קפה הפינה", decoded[0].title)
        assertEquals(Amount(4590, "ILS"), decoded[0].amount)
    }
}
