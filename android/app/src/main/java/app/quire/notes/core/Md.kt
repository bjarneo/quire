package app.quire.notes.core

import java.text.Collator
import java.util.Locale

data class Heading(val level: Int, val text: String, val line: Int)
data class Hit(val li: Int, val i: Int, val pos: Int, val ln: String)
data class SearchResult(val path: String, val hits: List<Hit>, val nameHit: Boolean)
data class TagCount(val tag: String, val n: Int)
data class Backlink(val path: String, val n: Int)
data class Fuzzy(val score: Double, val idx: List<Int>)

/** A block of the rendered preview. [line] is the first source line of the block. */
sealed class Block {
    abstract val line: Int
}

data class BCode(override val line: Int, val code: String) : Block()
data class BHead(override val line: Int, val level: Int, val text: String, val index: Int) : Block()
data class BHr(override val line: Int) : Block()
data class BQuote(override val line: Int, val text: String) : Block()
data class BPara(override val line: Int, val text: String) : Block()
data class LItem(val ind: Int, val mk: String, val task: Boolean, val done: Boolean, val text: String, val line: Int)
data class BList(override val line: Int, val ordered: Boolean, val items: List<LItem>) : Block()

/** A line of nothing but images. */
data class BImage(override val line: Int, val images: List<Img>) : Block()

/** The alignment of a table column, from the colons in its delimiter cell. */
enum class TAlign { LEFT, CENTER, RIGHT }

/** The part of a table that a line is. */
enum class TableLine { HEAD, DELIM, ROW }

/** A table: the alignment of each column, the header cells and the body rows. Each row has one cell for each column. */
data class BTable(override val line: Int, val align: List<TAlign>, val head: List<String>, val rows: List<List<String>>) : Block()

/**
 * An image in a note: `![alt](src)`, or `![[name]]` as other Markdown apps write an embed.
 * With [embed], [src] is a file name to find anywhere in the notes folder.
 */
data class Img(val alt: String, val src: String, val embed: Boolean)

/** Pure Markdown helpers. Each one follows the prototype in design/Quire.dc.html. */
object Md {
    val collator: Collator = Collator.getInstance(Locale.ENGLISH)

    private val mdExt = Regex("""\.md$""", RegexOption.IGNORE_CASE)
    private val badName = Regex("""[\\/:]""")

    fun base(p: String): String = p.substring(p.lastIndexOf('/') + 1).replace(mdExt, "")
    fun dir(p: String): String {
        val i = p.lastIndexOf('/')
        return if (i < 0) "" else p.substring(0, i)
    }

    fun join(d: String, n: String): String = if (d.isNotEmpty()) "$d/$n" else n
    fun stripExt(p: String): String = p.replace(mdExt, "")
    fun safeName(s: String): String = s.replace(badName, "-").trim()
    fun isNote(name: String): Boolean = name.endsWith(".md", ignoreCase = true)

    /** True when [path] is inside the folder [dir] or one of its subfolders. */
    fun inDir(path: String, dir: String): Boolean = dir.isNotEmpty() && path.startsWith("$dir/")

    /**
     * The name in .trash for a deleted folder: its path with " - " for "/", for example
     * "Drafts - Old" for Drafts/Old. When [taken] says the name is in use, " 2", " 3" and so on follow.
     */
    fun trashDirName(dir: String, taken: (String) -> Boolean): String {
        val base = dir.split('/').joinToString(" - ")
        var name = base
        var n = 2
        while (taken(name)) name = "$base ${n++}"
        return name
    }

    /** Maps lowercase note names and lowercase paths without .md to a path. */
    fun buildNames(paths: Collection<String>): Map<String, String> {
        val m = HashMap<String, String>()
        for (p in paths.sorted()) {
            val b = base(p).lowercase()
            if (b !in m) m[b] = p
            m[stripExt(p).lowercase()] = p
        }
        return m
    }

    fun resolve(names: Map<String, String>, name: String?): String? = names[(name ?: "").trim().lowercase()]

    fun folderList(folders: Collection<String>, paths: Collection<String>): List<String> {
        val set = HashSet(folders)
        for (p in paths) {
            val d = dir(p)
            if (d.isNotEmpty()) set.add(d)
        }
        return set.sorted()
    }

    // Headings for the outline.
    private val fenceStart = Regex("""^\s*```""")
    private val headRe = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val headClean = Regex("""\*\*|__|[*_`]|\[\[|\]\]""")

    fun headings(text: String): List<Heading> {
        val out = ArrayList<Heading>()
        var fence = false
        text.split('\n').forEachIndexed { i, ln ->
            if (fenceStart.containsMatchIn(ln)) {
                fence = !fence
                return@forEachIndexed
            }
            if (fence) return@forEachIndexed
            val m = headRe.find(ln) ?: return@forEachIndexed
            out.add(Heading(m.groupValues[1].length, m.groupValues[2].replace(headClean, ""), i))
        }
        return out
    }

    // Tags.
    private val fencedBlock = Regex("""```[\s\S]*?```""")
    private val inlineCode = Regex("""`[^`\n]*`""")
    private val tagRe = Regex("""(?:^|[\s(])#[A-Za-z][\w/-]*""")

    fun tags(files: Map<String, String>): List<TagCount> {
        val m = HashMap<String, Int>()
        for (txt in files.values) {
            if (Lock.isLocked(txt)) continue
            val t = txt.replace(fencedBlock, "").replace(inlineCode, "")
            val seen = HashSet<String>()
            for (x in tagRe.findAll(t)) {
                val tag = x.value.trim().removePrefix("(").substring(1).lowercase()
                if (seen.add(tag)) m[tag] = (m[tag] ?: 0) + 1
            }
        }
        return m.keys
            .sortedWith(compareByDescending<String> { m[it] }.thenComparator { a, b -> collator.compare(a, b) })
            .map { TagCount(it, m.getValue(it)) }
    }

    // Full-text search.
    fun search(files: Map<String, String>, q: String): List<SearchResult> {
        val ql = q.lowercase()
        if (ql.isEmpty()) return emptyList()
        val out = ArrayList<SearchResult>()
        for ((p, txt) in files) {
            // The text of a locked note is sealed, so search has nothing to find in it.
            if (Lock.isLocked(txt)) continue
            val hits = ArrayList<Hit>()
            var off = 0
            txt.split('\n').forEachIndexed { li, ln ->
                val low = ln.lowercase()
                var i = low.indexOf(ql)
                while (i >= 0) {
                    hits.add(Hit(li, i, off + i, ln))
                    i = low.indexOf(ql, i + ql.length)
                }
                off += ln.length + 1
            }
            val nameHit = base(p).lowercase().contains(ql)
            if (hits.isNotEmpty() || nameHit) out.add(SearchResult(p, hits, nameHit))
        }
        return out.sortedWith(
            compareByDescending<SearchResult> { it.nameHit }
                .thenByDescending { it.hits.size }
                .thenComparator { a, b -> collator.compare(a.path, b.path) }
        )
    }

    // Backlinks.
    val wikiRe = Regex("""\[\[([^\]\n]+)\]\]""")

    fun backlinks(files: Map<String, String>, active: String): List<Backlink> {
        val b = base(active).lowercase()
        val full = stripExt(active).lowercase()
        val out = ArrayList<Backlink>()
        for (p in files.keys.sortedWith { x, y -> collator.compare(x, y) }) {
            if (p == active) continue
            val n = wikiRe.findAll(files.getValue(p)).count {
                val t = it.groupValues[1].split('|')[0].trim().lowercase()
                t == b || t == full
            }
            if (n > 0) out.add(Backlink(p, n))
        }
        return out
    }

    // Word count.
    private val fencedWithLang = Regex("""```[^\n]*\n[\s\S]*?```""")
    private val wordRe = Regex("""[\p{L}\p{N}][\p{L}\p{N}'’-]*""")

    fun words(t: String): Int = wordRe.findAll(t.replace(fencedWithLang, " ")).count()

    /** The first and last line of the paragraph that holds [pos]. */
    fun paraRange(text: String, pos: Int): IntRange {
        val lines = text.split('\n')
        var li = 0
        var acc = 0
        while (li < lines.size - 1) {
            if (pos <= acc + lines[li].length) break
            acc += lines[li].length + 1
            li++
        }
        if (lines[li].isBlank()) return li..li
        var a = li
        var b = li
        while (a > 0 && lines[a - 1].isNotBlank()) a--
        while (b < lines.size - 1 && lines[b + 1].isNotBlank()) b++
        return a..b
    }

    // Fuzzy match for the quick switcher and the command palette.
    private fun isSep(ch: Char) = ch.isWhitespace() || ch == '/' || ch == '_' || ch == '-'

    fun fuzzy(q: String, s: String): Fuzzy? {
        val ql = q.lowercase()
        val sl = s.lowercase()
        val sub = sl.indexOf(ql)
        if (sub >= 0) {
            val score = 1000 - sub * 2 - s.length * 0.1 + (if (sub == 0) 50 else 0)
            return Fuzzy(score, (sub until sub + ql.length).toList())
        }
        var j = 0
        var prev = -2
        var score = 0.0
        val idx = ArrayList<Int>()
        var i = 0
        while (i < sl.length && j < ql.length) {
            if (sl[i] == ql[j]) {
                idx.add(i)
                score += (if (i == prev + 1) 4 else 1) + (if (i == 0 || isSep(sl[i - 1])) 3 else 0)
                prev = i
                j++
            }
            i++
        }
        return if (j < ql.length) null else Fuzzy(score, idx)
    }

    // List continuation on Enter.
    private val listCont = Regex("""^(\s*)([-*+]|(\d+)([.)]))(\s+)(\[[ xX]\]\s+)?(.*)$""")

    /**
     * Applies list continuation for an Enter press at [pos] in [v].
     * Returns the new text and caret, or null when Enter inserts a plain line break.
     */
    fun continueList(v: String, pos: Int): Pair<String, Int>? {
        val ls = if (pos <= 0) 0 else v.lastIndexOf('\n', pos - 1) + 1
        val le = v.indexOf('\n', pos)
        val line = v.substring(ls, if (le < 0) v.length else le)
        val m = listCont.find(line) ?: return null
        val content = m.groupValues[7]
        if (pos - ls < line.length - content.length) return null
        if (content.isBlank()) {
            return (v.substring(0, ls) + v.substring(ls + line.length)) to ls
        }
        val num = m.groups[3]
        val mk = if (num != null) {
            val n = num.value.toLongOrNull() ?: return null
            "${n + 1}${m.groupValues[4]}"
        } else {
            m.groupValues[2]
        }
        val ins = "\n" + m.groupValues[1] + mk + m.groupValues[5] + (if (m.groups[6] != null) "[ ] " else "")
        return (v.substring(0, pos) + ins + v.substring(pos)) to (pos + ins.length)
    }

    /** A new number for the list marker at [start] until [end] in the text. */
    data class NumEdit(val start: Int, val end: Int, val num: String)

    private enum class LKind { ITEM, INNER, BLANK, TEXT, STOP }

    private val numRe = Regex("""^(\s*)(\d+)[.)]\s""")

    /**
     * Numbers the ordered list that holds line [at] again, one step apart from its first item.
     * Only the items at the indent of line [at] change. A port of renumber in desktop/src/md.rs.
     */
    fun renumber(text: String, at: Int): List<NumEdit> {
        val lines = text.split('\n')
        if (at !in lines.indices) return emptyList()
        val me = numRe.find(lines[at]) ?: return emptyList()
        val ind = me.groupValues[1].length
        fun kind(l: String): LKind {
            val m = isListRe.find(l)
            if (m != null) {
                val i = l.length - l.trimStart().length
                return when {
                    i == ind && m.groupValues[1][0].isDigit() -> LKind.ITEM
                    i > ind -> LKind.INNER
                    else -> LKind.STOP
                }
            }
            if (l.isBlank()) return LKind.BLANK
            // A line indented past the marker belongs to an item, also a code fence.
            if (l.length - l.trimStart().length > ind) return LKind.INNER
            return if (isStart(l)) LKind.STOP else LKind.TEXT
        }
        // A blank line stays in the list when an item, or text indented under one, comes next.
        fun nextIsItem(from: Int, up: Boolean): Boolean {
            var i = from
            while (true) {
                i += if (up) -1 else 1
                if (i !in lines.indices) return false
                when (kind(lines[i])) {
                    LKind.BLANK -> continue
                    LKind.ITEM, LKind.INNER -> return true
                    LKind.TEXT -> return up
                    LKind.STOP -> return false
                }
            }
        }
        var first = at
        var below = LKind.ITEM
        var i = at - 1
        while (i >= 0) {
            val k = kind(lines[i])
            val ok = when (k) {
                LKind.ITEM, LKind.INNER, LKind.TEXT -> true
                // Text under a blank line starts a paragraph after the list.
                LKind.BLANK -> below != LKind.TEXT && nextIsItem(i, true)
                LKind.STOP -> false
            }
            if (!ok) break
            if (k == LKind.ITEM) first = i
            below = k
            i--
        }
        var last = at
        i = at + 1
        while (i < lines.size) {
            val ok = when (kind(lines[i])) {
                LKind.ITEM, LKind.INNER, LKind.TEXT -> true
                LKind.BLANK -> nextIsItem(i, false)
                LKind.STOP -> false
            }
            if (!ok) break
            last = i
            i++
        }
        var off = 0
        val starts = IntArray(lines.size)
        lines.forEachIndexed { n, l ->
            starts[n] = off
            off += l.length + 1
        }
        val out = ArrayList<NumEdit>()
        var next: Long? = null
        for (n in first..last) {
            if (kind(lines[n]) != LKind.ITEM) continue
            val m = numRe.find(lines[n]) ?: continue
            val num = m.groupValues[2].toLongOrNull() ?: continue
            val want = next ?: num
            next = want + 1
            if (num != want) {
                val a = starts[n] + m.groupValues[1].length
                out.add(NumEdit(a, a + m.groupValues[2].length, want.toString()))
            }
        }
        return out
    }

    /** Numbers the ordered list at [caret] again. The caret keeps its place in the text. */
    fun renumberAt(text: String, caret: Int): Pair<String, Int> {
        val line = text.substring(0, caret.coerceIn(0, text.length)).count { it == '\n' }
        val edits = renumber(text, line)
        if (edits.isEmpty()) return text to caret
        val sb = StringBuilder(text)
        var c = caret
        for (e in edits.asReversed()) {
            sb.replace(e.start, e.end, e.num)
            if (e.start < caret) c += e.num.length - (e.end - e.start)
        }
        return sb.toString() to c
    }

    /** Rewrites [[old]] and [[old|alias]] to the new note name. */
    fun rewriteLinks(text: String, oldBase: String, newBase: String): String {
        val re = Regex("""\[\[""" + Regex.escape(oldBase) + """(\|[^\]]*)?\]\]""", RegexOption.IGNORE_CASE)
        return re.replace(text) { "[[" + newBase + (it.groups[1]?.value ?: "") + "]]" }
    }

    private val taskBox = Regex("""\[([ xX])\]""")

    fun toggleTaskLine(line: String): String {
        val m = taskBox.find(line) ?: return line
        return line.replaceRange(m.range, if (m.groupValues[1] == " ") "[x]" else "[ ]")
    }

    /**
     * Finds the wiki link that holds [col] on [line]. Returns the link range in the line and the
     * target. An embed of an image file is no link, so following it never makes a note.
     */
    fun linkAt(line: String, col: Int): Pair<IntRange, String>? {
        for (m in wikiRe.findAll(line)) {
            if (col >= m.range.first && col <= m.range.last + 1) {
                val target = m.groupValues[1].split('|')[0].trim()
                return if (isImage(target)) null else m.range to target
            }
            if (m.range.first > col) break
        }
        return null
    }

    // Preview blocks.
    private val isListRe = Regex("""^\s*([-*+]|\d+[.)])\s+""")
    private val isHrRe = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val isStartRe = Regex("""^\s*(```|#{1,6}\s|>)""")
    private val blockHead = Regex("""^(#{1,6})\s+(.*)$""")
    private val quoteRe = Regex("""^\s*>""")
    private val quoteStrip = Regex("""^\s*>+\s?""")
    private val itemRe = Regex("""^(\s*)([-*+]|\d+[.)])\s+(?:\[([ xX])\]\s+)?(.*)$""")

    fun isHr(l: String) = isHrRe.containsMatchIn(l)
    private fun isList(l: String) = isListRe.containsMatchIn(l)
    private fun isStart(l: String) = isStartRe.containsMatchIn(l) || isList(l) || isHr(l) || imageLine(l) != null

    // Tables (GitHub Flavored Markdown). A port of the table functions in desktop/src/md.rs.

    /** True when the character at [i] comes after an odd number of backslashes. */
    private fun escaped(s: String, i: Int): Boolean {
        var n = 0
        var k = i - 1
        while (k >= 0 && s[k] == '\\') {
            n++
            k--
        }
        return n % 2 == 1
    }

    /** The offsets of the pipes that split a table row. A backslash before a pipe makes it text. */
    fun tablePipes(ln: String): List<Int> = ln.indices.filter { ln[it] == '|' && !escaped(ln, it) }

    /**
     * The cells of a table row, as ranges without the spaces around them. A pipe at the start or at
     * the end of the line is a border, not a cell.
     */
    fun tableCells(ln: String): List<IntRange> {
        var start = ln.length - ln.trimStart().length
        var end = ln.trimEnd().length
        if (start < end && ln[start] == '|') start++
        if (end > start && ln[end - 1] == '|' && !escaped(ln, end - 1)) end--
        fun trim(a: Int, z: Int): IntRange {
            var x = a
            var y = z
            while (x < y && ln[x].isWhitespace()) x++
            while (y > x && ln[y - 1].isWhitespace()) y--
            return x until y
        }
        val out = ArrayList<IntRange>()
        var a = start
        for (i in start until end) {
            if (ln[i] == '|' && !escaped(ln, i)) {
                out.add(trim(a, i))
                a = i + 1
            }
        }
        out.add(trim(a, end))
        return out
    }

    /** The column alignments of a delimiter row such as `| :--- | :---: | ---: |`, or null when the line is no delimiter row. */
    fun tableDelim(ln: String): List<TAlign>? {
        if (!ln.contains('|')) return null
        return tableCells(ln).map { r ->
            val c = ln.substring(r.first, r.last + 1)
            val left = c.startsWith(":")
            val rest = if (left) c.substring(1) else c
            val right = rest.endsWith(":")
            val dashes = if (right) rest.dropLast(1) else rest
            if (dashes.isEmpty() || dashes.any { it != '-' }) return null
            when {
                left && right -> TAlign.CENTER
                right -> TAlign.RIGHT
                else -> TAlign.LEFT
            }
        }
    }

    /** The column alignments when a table starts at line [i]: a row with a pipe, then a delimiter row with the same number of cells. */
    private fun tableAt(lines: List<String>, i: Int): List<TAlign>? {
        val head = lines[i]
        if (isStart(head) || tablePipes(head).isEmpty()) return null
        val align = tableDelim(lines.getOrNull(i + 1) ?: return null) ?: return null
        return if (tableCells(head).size == align.size) align else null
    }

    /** True when line [i] is a table row: not blank, and no start of another block. */
    private fun tableRowAt(lines: List<String>, i: Int) = i < lines.size && lines[i].isNotBlank() && !isStart(lines[i])

    /** The table part of each line, for source highlighting. It finds the same tables as [parseBlocks]. */
    fun tableLines(text: String): Array<TableLine?> {
        val lines = text.split('\n')
        val out = arrayOfNulls<TableLine>(lines.size)
        var fence = false
        var i = 0
        while (i < lines.size) {
            if (fenceStart.containsMatchIn(lines[i])) {
                fence = !fence
            } else if (!fence && tableAt(lines, i) != null) {
                out[i] = TableLine.HEAD
                out[i + 1] = TableLine.DELIM
                i += 2
                while (tableRowAt(lines, i)) out[i++] = TableLine.ROW
                continue
            }
            i++
        }
        return out
    }

    /** The text of each cell in a row, with a backslash and a pipe as a pipe, as many cells as the table has columns. */
    private fun rowCells(ln: String, n: Int): List<String> {
        val cells = tableCells(ln).take(n).map { ln.substring(it.first, it.last + 1).replace("\\|", "|") }
        return cells + List(n - cells.size) { "" }
    }

    /** The file types that the preview shows as images, and that sync carries next to the notes. */
    val IMAGE_EXT = listOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "avif")

    /** True when [path] names an image file, by its extension. */
    fun isImage(path: String): Boolean {
        val i = path.lastIndexOf('.')
        if (i < 0) return false
        val e = path.substring(i + 1)
        return IMAGE_EXT.any { it.equals(e, ignoreCase = true) }
    }

    /** The images of a line that holds only images, with spaces between them. */
    fun imageLine(l: String): List<Img>? {
        var rest = l.trim()
        if (!rest.startsWith("![")) return null
        val out = ArrayList<Img>()
        while (rest.isNotEmpty()) {
            val (img, used) = imageAt(rest) ?: return null
            out.add(img)
            rest = rest.substring(used).trimStart()
        }
        return out
    }

    /** One image at the start of [s], and how many characters it takes. */
    fun imageAt(s: String): Pair<Img, Int>? {
        if (s.startsWith("![[")) {
            val inner = s.substring(3)
            val end = inner.indexOf("]]")
            if (end < 0) return null
            val name = inner.substring(0, end).split('|')[0].trim()
            if (name.isEmpty() || !isImage(name)) return null
            val file = name.substringAfterLast('/')
            val alt = if (file.contains('.')) file.substringBeforeLast('.') else file
            return Img(alt, name, true) to 3 + end + 2
        }
        if (!s.startsWith("![")) return null
        val inner = s.substring(2)
        val close = inner.indexOf("](")
        if (close < 0) return null
        val alt = inner.substring(0, close)
        if (alt.any { it == '[' || it == ']' || it == '\n' }) return null
        val after = inner.substring(close + 2)
        val end = if (after.startsWith("<")) {
            val k = after.indexOf(">)")
            if (k < 0) return null
            k + 1
        } else {
            val k = after.indexOf(')')
            if (k < 0) return null
            k
        }
        val src = imageSrc(after.substring(0, end))
        if (src.isEmpty()) return null
        return Img(alt, src, false) to 2 + close + 2 + end + 1
    }

    /** The path or URL of `![alt](…)`: without `<…>`, without a `"title"`, with %20 and the other escapes decoded for paths. */
    fun imageSrc(raw: String): String {
        val t0 = raw.trim()
        if (t0.startsWith("<") && t0.endsWith(">") && t0.length >= 2) return t0.substring(1, t0.length - 1)
        val i = t0.indexOf(" \"")
        val t = if (i >= 0) t0.substring(0, i) else t0
        if (t.contains("://")) return t
        return percentDecode(t)
    }

    fun percentDecode(s: String): String {
        val b = s.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(b.size)
        fun hex(c: Byte): Int? = Character.digit(c.toInt().toChar(), 16).takeIf { it >= 0 && c.toInt() in 0..127 }
        var i = 0
        while (i < b.size) {
            if (b[i] == '%'.code.toByte() && i + 2 < b.size) {
                val h = hex(b[i + 1])
                val l = hex(b[i + 2])
                if (h != null && l != null) {
                    out.write(h * 16 + l)
                    i += 3
                    continue
                }
            }
            out.write(b[i].toInt())
            i++
        }
        val bytes = out.toByteArray()
        // Bytes that are not UTF-8 give the text back as it was.
        val dec = Charsets.UTF_8.newDecoder()
        return try {
            dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            s
        }
    }

    /** A path for a Markdown link: spaces and the characters that end a link become escapes. */
    fun linkPath(p: String): String {
        val out = StringBuilder(p.length)
        for (c in p) {
            when (c) {
                ' ' -> out.append("%20")
                '(' -> out.append("%28")
                ')' -> out.append("%29")
                '<' -> out.append("%3C")
                '>' -> out.append("%3E")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** [to] written relative to the folder [fromDir], both relative to the notes folder, with ../ to go up. */
    fun relative(fromDir: String, to: String): String {
        val a = fromDir.split('/').filter { it.isNotEmpty() }
        val b = to.split('/').filter { it.isNotEmpty() }
        var same = 0
        while (same < a.size && same < b.size && a[same] == b[same]) same++
        return (List(a.size - same) { ".." } + b.drop(same)).joinToString("/")
    }

    /** [rel] inside the folder [dir], with . and .. resolved. Null when it leaves the notes folder. */
    fun resolvePath(dir: String, rel: String): String? {
        val parts = if (rel.startsWith("/")) ArrayList() else ArrayList(dir.split('/').filter { it.isNotEmpty() })
        for (seg in rel.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> {
                    if (parts.isEmpty()) return null
                    parts.removeAt(parts.size - 1)
                }
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    /** [name] with a number before the extension, "name 2.png", until [taken] says no file has it. */
    fun uniqueName(name: String, taken: (String) -> Boolean): String {
        val i = name.lastIndexOf('.')
        val stem = if (i >= 0) name.substring(0, i) else name
        val ext = if (i >= 0) name.substring(i + 1) else ""
        var n = 1
        while (true) {
            val cand = when {
                n == 1 -> name
                ext.isEmpty() -> "$stem $n"
                else -> "$stem $n.$ext"
            }
            if (!taken(cand)) return cand
            n++
        }
    }

    /**
     * The name in .trash for a deleted file: its path with " - " for "/", for example
     * "Drafts - Plan.md". When [taken] says the name is in use, a number goes before the extension.
     */
    fun trashFileName(path: String, taken: (String) -> Boolean): String {
        val name = path.replace("/", " - ")
        val i = name.lastIndexOf('.')
        val stem = if (i >= 0) name.substring(0, i) else name
        val ext = if (i >= 0) name.substring(i + 1) else "md"
        var cand = name
        var n = 2
        while (taken(cand)) cand = "$stem ${n++}.$ext"
        return cand
    }

    /**
     * The lines of a paragraph, a quote or a list item, joined so that each one keeps its own
     * line, as in the source. Spaces around a line go. A backslash or two spaces that end a line
     * mark a line break in Markdown, and the line breaks anyway, so the mark goes too. A backslash
     * at the end of the last line stays, as Markdown keeps it. Follows join_lines in desktop/src/md.rs.
     */
    fun joinLines(lines: List<String>): String {
        val out = StringBuilder()
        lines.forEachIndexed { i, l ->
            var t = l.trim()
            if (i + 1 < lines.size && t.endsWith("\\") && !t.dropLast(1).endsWith("\\")) t = t.dropLast(1).trimEnd()
            if (i > 0) out.append('\n')
            out.append(t)
        }
        return out.toString()
    }

    fun parseBlocks(text: String): List<Block> {
        val lines = text.split('\n')
        val out = ArrayList<Block>()
        var i = 0
        var hi = 0
        while (i < lines.size) {
            val ln = lines[i]
            val start = i
            if (ln.isBlank()) {
                i++
                continue
            }
            if (fenceStart.containsMatchIn(ln)) {
                val buf = ArrayList<String>()
                i++
                while (i < lines.size && !fenceStart.containsMatchIn(lines[i])) buf.add(lines[i++])
                i++
                out.add(BCode(start, buf.joinToString("\n")))
                continue
            }
            val m = blockHead.find(ln)
            if (m != null) {
                out.add(BHead(start, minOf(m.groupValues[1].length, 3), m.groupValues[2], hi++))
                i++
                continue
            }
            if (isHr(ln)) {
                out.add(BHr(start))
                i++
                continue
            }
            val images = imageLine(ln)
            if (images != null) {
                out.add(BImage(start, images))
                i++
                continue
            }
            if (quoteRe.containsMatchIn(ln)) {
                val buf = ArrayList<String>()
                while (i < lines.size && quoteRe.containsMatchIn(lines[i])) buf.add(lines[i++].replace(quoteStrip, ""))
                out.add(BQuote(start, joinLines(buf)))
                continue
            }
            if (isList(ln)) {
                val items = ArrayList<LItem>()
                // The next number at each open level. A numbered list counts on from its first
                // number, the way CommonMark does, so `1. 1. 1.` shows as 1, 2 and 3.
                val levels = ArrayList<LongArray>()
                while (i < lines.size && isList(lines[i])) {
                    val mm = itemRe.find(lines[i])
                    if (mm == null) {
                        i++
                        continue
                    }
                    val box = mm.groups[3]?.value
                    val itemLine = i
                    val own = arrayListOf(mm.groupValues[4])
                    i++
                    // Lines under an item that start no new block belong to the item, indented or not.
                    while (i < lines.size && lines[i].isNotBlank() && !isStart(lines[i]) && tableAt(lines, i) == null) own.add(lines[i++])
                    val ind = mm.groupValues[1].replace("\t", "  ").length
                    while (levels.isNotEmpty() && levels.last()[0] > ind) levels.removeAt(levels.size - 1)
                    val raw = mm.groupValues[2]
                    val n = if (raw[0].isDigit()) raw.dropLast(1).toLongOrNull() else null
                    val mk = if (n != null) {
                        val top = levels.lastOrNull()
                        val shown = if (top != null && top[0] == ind.toLong()) top[1]++ else n.also { levels.add(longArrayOf(ind.toLong(), n + 1)) }
                        "$shown${raw.last()}"
                    } else {
                        if (levels.lastOrNull()?.get(0) == ind.toLong()) levels.removeAt(levels.size - 1)
                        raw
                    }
                    items.add(
                        LItem(
                            ind = ind,
                            mk = mk,
                            task = box != null,
                            done = box != null && box != " ",
                            text = joinLines(own),
                            // The line of the marker, so a tap on the task box finds the line.
                            line = itemLine,
                        )
                    )
                }
                if (items.isNotEmpty()) out.add(BList(start, items[0].mk.any { it.isDigit() }, items))
                continue
            }
            val align = tableAt(lines, i)
            if (align != null) {
                val head = rowCells(ln, align.size)
                i += 2
                val rows = ArrayList<List<String>>()
                while (tableRowAt(lines, i)) rows.add(rowCells(lines[i++], align.size))
                out.add(BTable(start, align, head, rows))
                continue
            }
            val buf = ArrayList<String>()
            // A table can start under the lines of a paragraph, as GitHub reads it.
            while (i < lines.size && lines[i].isNotBlank() && (buf.isEmpty() || (!isStart(lines[i]) && tableAt(lines, i) == null))) buf.add(lines[i++])
            out.add(BPara(start, joinLines(buf)))
        }
        return out
    }
}
