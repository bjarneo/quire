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

    /** Finds the wiki link that holds [col] on [line]. Returns the link range in the line and the target. */
    fun linkAt(line: String, col: Int): Pair<IntRange, String>? {
        for (m in wikiRe.findAll(line)) {
            if (col >= m.range.first && col <= m.range.last + 1) {
                return m.range to m.groupValues[1].split('|')[0].trim()
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
    private fun isStart(l: String) = isStartRe.containsMatchIn(l) || isList(l) || isHr(l)

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
            if (quoteRe.containsMatchIn(ln)) {
                val buf = ArrayList<String>()
                while (i < lines.size && quoteRe.containsMatchIn(lines[i])) buf.add(lines[i++].replace(quoteStrip, ""))
                out.add(BQuote(start, buf.joinToString(" ")))
                continue
            }
            if (isList(ln)) {
                val items = ArrayList<LItem>()
                while (i < lines.size && isList(lines[i])) {
                    val mm = itemRe.find(lines[i])
                    if (mm == null) {
                        i++
                        continue
                    }
                    val box = mm.groups[3]?.value
                    items.add(
                        LItem(
                            ind = mm.groupValues[1].replace("\t", "  ").length,
                            mk = mm.groupValues[2],
                            task = box != null,
                            done = box != null && box != " ",
                            text = mm.groupValues[4],
                            line = i
                        )
                    )
                    i++
                }
                if (items.isNotEmpty()) out.add(BList(start, items[0].mk.any { it.isDigit() }, items))
                continue
            }
            val buf = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank() && (buf.isEmpty() || !isStart(lines[i]))) buf.add(lines[i++])
            out.add(BPara(start, buf.joinToString(" ")))
        }
        return out
    }
}
