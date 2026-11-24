package app.quire.notes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tables. A port of table_tests in desktop/src/md.rs. */
class MdTableTest {
    private fun table(t: String): BTable = Md.parseBlocks(t).filterIsInstance<BTable>().firstOrNull()
        ?: throw AssertionError("no table in $t: ${Md.parseBlocks(t)}")

    @Test
    fun tablesParse() {
        val t = table("| Name | Qty | Note |\n| :--- | ---: | :-: |\n| **Tea** | 2 | a \\| b |\n| Cake |")
        assertEquals(listOf(TAlign.LEFT, TAlign.RIGHT, TAlign.CENTER), t.align)
        assertEquals(listOf("Name", "Qty", "Note"), t.head)
        // A backslash and a pipe is a pipe in the text, and a short row gets empty cells.
        assertEquals(listOf(listOf("**Tea**", "2", "a | b"), listOf("Cake", "", "")), t.rows)
        // The pipes at the ends are optional, and a row with more cells loses the extra ones.
        val u = table("a | b\n--- | ---\n1 | 2 | 3")
        assertEquals(listOf("a", "b"), u.head)
        assertEquals(listOf(listOf("1", "2")), u.rows)
        // One column needs the pipes.
        assertEquals(listOf(listOf("1")), table("| One |\n| --- |\n| 1 |").rows)
        // A table without rows is a header.
        assertTrue(table("| a |\n|---|").rows.isEmpty())
    }

    @Test
    fun tablesEndAndInterrupt() {
        // A blank line or a new block ends the table. A paragraph above it stays a paragraph.
        val b = Md.parseBlocks("Intro\n| a | b |\n|---|---|\n| 1 | 2 |\n\nAfter\n| x |\n|---|\n- item")
        assertEquals(listOf(BPara::class, BTable::class, BPara::class, BTable::class, BList::class), b.map { it::class })
        assertEquals(listOf(0, 1, 5, 6, 8), b.map { it.line })
        // The delimiter row needs as many cells as the header.
        assertEquals(listOf(BPara::class), Md.parseBlocks("| a | b |\n| --- |").map { it::class })
        // Without a pipe a line of dashes is a rule.
        assertEquals(listOf(BPara::class, BHr::class), Md.parseBlocks("a\n---").map { it::class })
        // A table in code stays code.
        assertEquals(listOf(BCode::class), Md.parseBlocks("```\n| a |\n|---|\n```").map { it::class })
        // A list item ends where a table starts.
        assertEquals(listOf(BList::class, BTable::class), Md.parseBlocks("- item\n| a |\n| - |").map { it::class })
    }

    @Test
    fun tableLinesMatchBlocks() {
        val t = "Text\n| a | b |\n| - | - |\n| 1 | 2 |\n\n```\n| x |\n|---|\n```\n| c |\n|:-:|"
        val h = TableLine.HEAD
        val d = TableLine.DELIM
        val r = TableLine.ROW
        assertEquals(listOf(null, h, d, r, null, null, null, null, null, h, d), Md.tableLines(t).toList())
        assertEquals(listOf(2 until 3, 6 until 9, 13 until 13), Md.tableCells("| a | `b` |  |"))
        assertEquals(listOf(0, 9), Md.tablePipes("| a \\| b |"))
        assertEquals(listOf(TAlign.LEFT, TAlign.RIGHT, TAlign.CENTER, TAlign.LEFT), Md.tableDelim("|:--|--:|:-:|---|"))
        assertNull(Md.tableDelim("| -x- |"))
        assertNull(Md.tableDelim("---"))
    }
}
