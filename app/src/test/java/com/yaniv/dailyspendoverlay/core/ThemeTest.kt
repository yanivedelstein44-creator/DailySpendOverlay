package com.yaniv.dailyspendoverlay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {

    private fun blend(alpha: Double, color: Int): Int {
        fun ch(shift: Int) = (alpha * 255 + (1 - alpha) * ((color shr shift) and 0xFF)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    @Test fun defaultBrightnessKeepsOriginalColors() {
        for (t in Themes.ALL) {
            val g = Themes.gradient(t, Themes.DEFAULT_BRIGHTNESS)
            assertEquals(t.id, t.start, g[0])
            assertEquals(t.id, t.end, g[1])
        }
    }

    @Test fun brightnessIsMonotonic() {
        for (t in Themes.ALL) {
            var prev = -1.0
            for (p in 0..100 step 10) {
                val y = Themes.luminance(Themes.gradient(t, p)[1])
                assertTrue("${t.id} at $p", y >= prev - 1e-9)
                prev = y
            }
            assertTrue(t.id, Themes.luminance(Themes.gradient(t, 0)[1]) < Themes.luminance(t.end))
            assertTrue(t.id, Themes.luminance(Themes.gradient(t, 100)[1]) > Themes.luminance(t.end))
        }
    }

    @Test fun whiteTextStaysReadableAtMaxBrightness() {
        for (t in Themes.ALL) {
            for (stop in Themes.gradient(t, 100)) {
                // טקסט משני בלבן שקוף ~86% על הרקע
                val text = blend(0.86, stop)
                val a = Themes.luminance(text)
                val b = Themes.luminance(stop)
                val contrast = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
                assertTrue("${t.id} contrast $contrast", contrast >= 4.4)
            }
        }
    }

    @Test fun outOfRangeBrightnessIsClamped() {
        val t = Themes.ALL.first()
        assertEquals(Themes.gradient(t, 0)[0], Themes.gradient(t, -20)[0])
        assertEquals(Themes.gradient(t, 100)[1], Themes.gradient(t, 250)[1])
    }

    @Test fun unknownIdFallsBackToBlue() {
        assertEquals("blue", Themes.byId(null).id)
        assertEquals("blue", Themes.byId("nope").id)
        assertEquals("purple", Themes.byId("purple").id)
    }

    @Test fun idsAreUnique() {
        assertEquals(Themes.ALL.size, Themes.ALL.map { it.id }.toSet().size)
    }
}
