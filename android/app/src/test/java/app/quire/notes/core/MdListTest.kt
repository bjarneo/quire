package app.quire.notes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** List numbers. A port of lists_number_again and preview_counts_on in desktop/src/md.rs. */
class MdListTest {
    private fun renumbered(t: String, at: Int): String {
        val sb = StringBuilder(t)
        for (e in Md.renumber(t, at).asReversed()) sb.replace(e.start, e.end, e.num)
        return sb.toString()
    }

    @Test
    fun listsNumberAgain() {
        // The numbers from the report: one list, numbered from its first item.
        assertEquals("1. a\n2. b\n3. c\n4. d\n5. e\n6. f", renumbered("1. a\n2. b\n3. c\n2. d\n2. e\n3. f", 4))
        // A new item in the middle moves the items under it down.
        assertEquals("1. a\n2. new\n3. b\n4. c", renumbered("1. a\n2. new\n2. b\n3. c", 1))
        // The first number stays, and `)` stays.
        assertEquals("5) a\n6) b", renumbered("5) a\n5) b", 1))
        // Nested items, text under an item and blank lines between items stay in the list.
        val t = "1. a\n   1. inner\n   1. inner\n   more\n\n1. b\nlazy\n1. c"
        assertEquals("1. a\n   1. inner\n   1. inner\n   more\n\n2. b\nlazy\n3. c", renumbered(t, 0))
        assertEquals("1. a\n   1. inner\n   2. inner\n   more\n\n1. b\nlazy\n1. c", renumbered(t, 1))
        // A paragraph, a heading or a bullet list ends the list.
        assertEquals("1. a\n\nText\n\n1. b", renumbered("1. a\n\nText\n\n1. b", 4))
        assertEquals("1. a\n# H\n1. b", renumbered("1. a\n# H\n1. b", 2))
        assertEquals("1. a\n- x\n1. b", renumbered("1. a\n- x\n1. b", 2))
        assertEquals("Intro\n3. a\n4. b", renumbered("Intro\n3. a\n3. b", 2))
        // A line that is no numbered item changes nothing.
        assertTrue(Md.renumber("- a\n- b", 0).isEmpty())
        assertTrue(Md.renumber("text", 5).isEmpty())
    }

    @Test
    fun enterMovesTheItemsDown() {
        val (t, caret) = Md.continueList("1. a\n2. b\n3. c\n2. d", 4)!!
        val (rt, rc) = Md.renumberAt(t, caret)
        assertEquals("1. a\n2. \n3. b\n4. c\n5. d", rt)
        assertEquals("1. a\n2. ".length, rc)
        // Ten items: the caret moves with a number that grows a digit.
        val (t2, c2) = Md.renumberAt("9. a\n9. b", 10)
        assertEquals("9. a\n10. b", t2)
        assertEquals(11, c2)
    }

    @Test
    fun previewCountsOn() {
        fun markers(t: String) = (Md.parseBlocks(t).single() as BList).items.map { it.mk }
        assertEquals(listOf("1.", "2.", "3.", "4.", "5.", "6."), markers("1. a\n2. b\n3. c\n2. d\n2. e\n3. f"))
        assertEquals(listOf("3)", "4)"), markers("3) a\n1) b"))
        assertEquals(listOf("1.", "1.", "2.", "2.", "1."), markers("1. a\n  1. x\n  1. y\n1. b\n  1. z"))
        assertEquals(listOf("1.", "-", "1."), markers("1. a\n- b\n1. c"))
    }
}
