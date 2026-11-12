package app.quire.notes.core

/** A toolbar action. */
enum class Fmt { BOLD, ITALIC, STRIKE, CODE, LINK, WIKI, HEADING, BULLET, NUMBER, TASK, QUOTE }

/** Put [insert] in place of text[start until end], then select [selStart] to [selEnd] in the new text. */
data class FmtEdit(val start: Int, val end: Int, val insert: String, val selStart: Int, val selEnd: Int) {
    fun applyTo(text: String): String = text.substring(0, start) + insert + text.substring(end)
}

/**
 * The actions of the Markdown toolbar, as plain text edits. Each action looks at the text and
 * the selection, and gives back one replacement and the selection after it. A port of
 * desktop/src/format.rs, so both apps follow the same rules.
 */
object Format {
    fun apply(text: String, a: Int, b: Int, f: Fmt): FmtEdit {
        val s = minOf(a, b).coerceIn(0, text.length)
        val e = maxOf(a, b).coerceIn(0, text.length)
        return when (f) {
            Fmt.BOLD -> wrap(text, s, e, "**")
            Fmt.ITALIC -> wrap(text, s, e, "*")
            Fmt.STRIKE -> wrap(text, s, e, "~~")
            Fmt.CODE -> if (text.substring(s, e).contains('\n')) fence(text, s, e) else wrap(text, s, e, "`")
            Fmt.LINK -> link(text, s, e)
            Fmt.WIKI -> wiki(text, s, e)
            Fmt.HEADING, Fmt.BULLET, Fmt.NUMBER, Fmt.TASK, Fmt.QUOTE -> lines(text, s, e, f)
        }
    }

    /** [m] is the whole mark at [at]: a "*" that is not part of "**". */
    private fun markAt(text: String, at: Int, m: String): Boolean {
        if (at < 0 || at + m.length > text.length || !text.startsWith(m, at)) return false
        if (m != "*") return true
        val before = at > 0 && text[at - 1] == '*'
        val after = at + 1 < text.length && text[at + 1] == '*'
        return !before && !after
    }

    /** Puts [m] on both sides of the selection, or takes it away when it is already there. */
    private fun wrap(text: String, s: Int, e: Int, m: String): FmtEdit {
        val n = m.length
        // The marks sit just outside the selection.
        if (s >= n && markAt(text, s - n, m) && markAt(text, e, m)) {
            return FmtEdit(s - n, e + n, text.substring(s, e), s - n, e - n)
        }
        // The selection holds the marks.
        val inner = text.substring(s, e)
        if (inner.length >= 2 * n && inner.startsWith(m) && inner.endsWith(m) && (m != "*" || (!inner.startsWith("**") && !inner.endsWith("**")))) {
            return FmtEdit(s, e, inner.substring(n, inner.length - n), s, e - 2 * n)
        }
        return FmtEdit(s, e, m + inner + m, s + n, e + n)
    }

    private fun fence(text: String, s: Int, e: Int): FmtEdit {
        val lead = if (s > 0 && text[s - 1] != '\n') "\n" else ""
        val body = text.substring(s, e).trimEnd('\n')
        val insert = "$lead```\n$body\n```"
        val startBody = s + lead.length + 4
        return FmtEdit(s, e, insert, startBody, startBody + body.length)
    }

    private fun link(text: String, s: Int, e: Int): FmtEdit {
        val sel = text.substring(s, e)
        if (sel.startsWith("http://") || sel.startsWith("https://")) return FmtEdit(s, e, "[]($sel)", s + 1, s + 1)
        val insert = "[$sel](url)"
        val u = s + 1 + sel.length + 2
        if (sel.isEmpty()) return FmtEdit(s, e, insert, s + 1, s + 1)
        return FmtEdit(s, e, insert, u, u + 3)
    }

    private fun wiki(text: String, s: Int, e: Int): FmtEdit {
        if (s >= 2 && text.substring(0, s).endsWith("[[") && text.substring(e).startsWith("]]")) {
            return FmtEdit(s - 2, e + 2, text.substring(s, e), s - 2, e - 2)
        }
        return FmtEdit(s, e, "[[" + text.substring(s, e) + "]]", s + 2, e + 2)
    }

    /** The mark at the start of a line: a list marker, a task box, a quote mark or a heading mark. */
    private fun prefix(line: String): String {
        for (p in listOf("- [ ] ", "- [x] ", "- [X] ", "- ", "* ", "+ ", "> ")) if (line.startsWith(p)) return p
        val digits = line.takeWhile { it in '0'..'9' }.length
        if (digits > 0 && line.startsWith(". ", digits)) return line.substring(0, digits + 2)
        val hashes = line.takeWhile { it == '#' }.length
        if (hashes in 1..6 && line.startsWith(" ", hashes)) return line.substring(0, hashes + 1)
        return ""
    }

    private fun kind(p: String): Fmt? = when {
        p == "- [ ] " || p == "- [x] " || p == "- [X] " -> Fmt.TASK
        p == "- " || p == "* " || p == "+ " -> Fmt.BULLET
        p == "> " -> Fmt.QUOTE
        p.startsWith("#") -> Fmt.HEADING
        p.endsWith(". ") -> Fmt.NUMBER
        else -> null
    }

    /** Heading, lists, tasks and quotes work on whole lines: every line that the selection touches. */
    private fun lines(text: String, s: Int, e: Int, f: Fmt): FmtEdit {
        val ls = text.lastIndexOf('\n', s - 1).let { if (s == 0 || it < 0) 0 else it + 1 }
        // A selection that ends at the start of a line leaves that line alone.
        val eLine = if (e > s && text.substring(0, e).endsWith("\n")) e - 1 else e
        val le = text.indexOf('\n', eLine).let { if (it < 0) text.length else it }
        val block = text.substring(ls, le).split('\n')
        val many = block.size > 1
        val used = block.filter { !many || it.isNotBlank() }
        val all = used.isNotEmpty() && used.all { kind(prefix(it)) == f }
        fun level(l: String) = l.takeWhile { it == '#' }.length
        var n = 0
        val out = block.map { l ->
            if (many && l.isBlank()) return@map l
            val p = prefix(l)
            val rest = l.substring(p.length)
            when {
                f == Fmt.HEADING -> {
                    val next = if (kind(p) == Fmt.HEADING) (level(p) + 1) % 4 else 1
                    if (next == 0) rest else "#".repeat(next) + " " + rest
                }
                all -> rest
                f == Fmt.BULLET -> "- $rest"
                f == Fmt.TASK -> "- [ ] $rest"
                f == Fmt.QUOTE -> "> $l"
                f == Fmt.NUMBER -> {
                    n++
                    "$n. $rest"
                }
                else -> l
            }
        }
        val insert = out.joinToString("\n")
        if (many) return FmtEdit(ls, le, insert, ls, ls + insert.length)
        // One line: the caret keeps its place in the text of the line.
        val shift = insert.length - (le - ls)
        fun at(x: Int) = (x + shift).coerceAtLeast(ls).coerceAtMost(ls + insert.length)
        return FmtEdit(ls, le, insert, at(s), at(e))
    }
}
