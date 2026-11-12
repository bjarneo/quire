package app.quire.notes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {
    private fun contrast(a: Rgb, b: Rgb) = Rgb.contrast(a, b)
    private fun hex(c: Rgb) = String.format("#%06x", c.argb() and 0xffffff)

    @Test
    fun everyThemeReads() {
        assertEquals(12, Themes.list.size)
        assertEquals(5, Themes.list.count { !it.dark })
        for (t in Themes.list) {
            val p = Themes.themePal(t.id, t.dark)
            assertEquals(t.id, t.dark, p.dark)
            // WCAG AA asks for 4.5 for body text. Muted text is for labels and hints.
            assertTrue("${t.id} ink ${contrast(p.ink, p.bg)}", contrast(p.ink, p.bg) >= 4.5f)
            assertTrue("${t.id} ink2 ${contrast(p.ink2, p.bg)}", contrast(p.ink2, p.bg) >= 4.5f)
            assertTrue("${t.id} mute ${contrast(p.mute, p.bg)}", contrast(p.mute, p.bg) >= 3.0f)
            assertTrue("${t.id} accent ${contrast(p.acc, p.bg)}", contrast(p.acc, p.bg) >= 3.0f)
        }
    }

    @Test
    fun paperAndInkKeepTheirPalettes() {
        val p = Themes.themePal("paper", false)
        assertEquals("#f8f5ef", hex(p.bg))
        assertEquals("#e1dbcf", hex(p.line))
        assertEquals("#7a4fba", hex(p.acc))
        assertEquals("#7d52bd", hex(p.button))
        assertEquals(0x0F463214, p.hover.argb())
        val i = Themes.themePal("ink", true)
        assertEquals("#1f1d1a", hex(i.bg))
        assertEquals("#b597e8", hex(i.acc))
        assertEquals("#2f2839", hex(i.soft))
        // An unknown id gives Paper or Ink.
        assertSame(Themes.PAPER, Themes.themePal("omarchy", false))
        assertSame(Themes.INK, Themes.themePal("nope", true))
    }

    @Test
    fun builtThemesMixTheirTones() {
        val t = Themes.def("nord")!!
        val p = Themes.themePal("nord", true)
        val bg = Rgb.of(t.colors[0])
        val ink = Rgb.of(t.colors[2])
        val acc = Rgb.of(t.colors[6])
        assertEquals(bg.mix(ink, 0.1f), p.line)
        assertEquals(bg.mix(ink, 0.045f), p.code)
        assertEquals(ink.alpha(0.05f), p.hover)
        assertEquals(ink.alpha(0.08f), p.act)
        assertEquals(acc, p.button)
        assertEquals(acc.mix(Rgb.BLACK, 0.14f), p.buttonHover)
        assertEquals(bg.mix(acc, 0.2f), p.soft)
        assertEquals(acc.alpha(0.4f), p.linkLine)
        assertEquals(acc.alpha(0.22f), p.sel)
        assertEquals(acc.alpha(0.26f), p.selEditor)
        assertEquals(Rgb.BLACK.alpha(0.38f), p.scrimSettings)
        // Black reads better than white on the light blue of Nord.
        assertEquals(Rgb.BLACK, p.onAcc)
        val light = Themes.themePal("solarized-light", false)
        val lb = Rgb.of("#fdf6e3")
        assertEquals(lb.mix(Rgb.of("#073642"), 0.11f), light.line)
        assertEquals(lb.mix(Rgb.of("#268bd2"), 0.12f), light.soft)
        assertEquals(Rgb.of("#281e0f", 0.22f), light.scrimSettings)
    }

    @Test
    fun aDarkAccentGetsLighterOnADarkTheme() {
        val p = Themes.themed("ink", true, "#3a1f6e")
        assertTrue(contrast(p.acc, p.bg) >= 3.0f)
        assertTrue("the text accent moved toward white", p.acc.luminance() > Rgb.of("#3a1f6e").luminance())
        assertEquals("#3a1f6e", hex(p.button))
        assertEquals(Rgb.WHITE, p.onAcc)
        // A light accent on Paper gets darker for text, and the button keeps the raw colour.
        val q = Themes.themed("paper", false, "#ffd75e")
        assertTrue(contrast(q.acc, q.bg) >= 3.0f)
        assertEquals("#ffd75e", hex(q.button))
        assertEquals(Rgb.BLACK, q.onAcc)
        // No accent gives the theme as it is.
        assertSame(Themes.themePal("nord", true), Themes.themed("nord", true, ""))
        assertSame(Themes.themePal("nord", true), Themes.themed("nord", true, "not a colour"))
    }

    @Test
    fun parseAccentTakesTheSameInputsAsTheDesktop() {
        assertNotNull(Themes.parseAccent("#abc"))
        assertNotNull(Themes.parseAccent("12ab9f"))
        assertNotNull(Themes.parseAccent("  #12AB9F "))
        assertNull(Themes.parseAccent("#12ab9"))
        assertNull(Themes.parseAccent("blue"))
        assertNull(Themes.parseAccent(""))
        assertNull(Themes.parseAccent("#"))
        assertNull(Themes.parseAccent("#12ab9fff"))
        assertNull(Themes.parseAccent("#ggg"))
        assertEquals(Rgb.of("#aabbcc"), Themes.parseAccent("abc"))
        for ((h, _) in Themes.accents.drop(1)) assertEquals(h, hex(Themes.parseAccent(h)!!))
    }
}
