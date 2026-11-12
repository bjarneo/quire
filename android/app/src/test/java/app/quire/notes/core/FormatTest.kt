package app.quire.notes.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The tests of desktop/src/format.rs. */
class FormatTest {
    /** The text after the edit, with "|" at the ends of the selection. */
    private fun run(text: String, f: Fmt): String {
        val a = text.indexOf('|')
        val t2 = text.replaceFirst("|", "")
        val b = t2.indexOf('|').let { if (it < 0) a else it }
        val t = t2.replaceFirst("|", "")
        val ed = Format.apply(t, a, b, f)
        val out = StringBuilder(ed.applyTo(t))
        out.insert(ed.selEnd, '|')
        if (ed.selStart != ed.selEnd) out.insert(ed.selStart, '|')
        return out.toString()
    }

    @Test
    fun marksWrapAndUnwrap() {
        assertEquals("a **|word|** b", run("a |word| b", Fmt.BOLD))
        assertEquals("a |word| b", run("a **|word|** b", Fmt.BOLD))
        assertEquals("a |word| b", run("a |**word**| b", Fmt.BOLD))
        assertEquals("a **|** b", run("a | b", Fmt.BOLD))
        assertEquals("a *|word|* b", run("a |word| b", Fmt.ITALIC))
        assertEquals("a |word| b", run("a *|word|* b", Fmt.ITALIC))
        assertEquals("italic inside bold adds a mark", "***|word|***", run("**|word|**", Fmt.ITALIC))
        assertEquals("~~|x|~~", run("|x|", Fmt.STRIKE))
        assertEquals("run `|ls|` now", run("run |ls| now", Fmt.CODE))
        assertEquals("Text\n```\n|a\nb|\n```", run("Text\n|a\nb|", Fmt.CODE))
    }

    @Test
    fun links() {
        assertEquals("see [docs](|url|)", run("see |docs|", Fmt.LINK))
        assertEquals("[|](https://x.io)", run("|https://x.io|", Fmt.LINK))
        assertEquals("[|](url)", run("|", Fmt.LINK))
        assertEquals("[[|Plan|]]", run("|Plan|", Fmt.WIKI))
        assertEquals("|Plan|", run("[[|Plan|]]", Fmt.WIKI))
    }

    @Test
    fun linesToggle() {
        assertEquals("# Ti|tle", run("Ti|tle", Fmt.HEADING))
        assertEquals("## Ti|tle", run("# Ti|tle", Fmt.HEADING))
        assertEquals("Ti|tle", run("### Ti|tle", Fmt.HEADING))
        assertEquals("|- a\n- b|", run("|a\nb|", Fmt.BULLET))
        assertEquals("|a\nb|", run("|- a\n- b|", Fmt.BULLET))
        assertEquals("|1. a\n\n2. b|", run("|a\n\nb|", Fmt.NUMBER))
        assertEquals("|1. a\n2. b|", run("|- a\n- b|", Fmt.NUMBER))
        assertEquals("- [ ] bu|y milk", run("bu|y milk", Fmt.TASK))
        assertEquals("bu|y milk", run("- [ ] bu|y milk", Fmt.TASK))
        assertEquals("- [ ] bu|y", run("- bu|y", Fmt.TASK))
        assertEquals("|> said\n> this|", run("|said\nthis|", Fmt.QUOTE))
        assertEquals("a selection that ends at a line start leaves that line", "one\n- |two|\nthree", run("one\n|two\n|three", Fmt.BULLET))
    }

    @Test
    fun theHeadingCycleEndsWithNoMark() {
        var t = "Plan|"
        val seen = ArrayList<String>()
        repeat(4) {
            t = run(t, Fmt.HEADING)
            seen.add(t)
        }
        assertEquals(listOf("# Plan|", "## Plan|", "### Plan|", "Plan|"), seen)
    }
}
