package app.quire.notes.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.quire.notes.core.Md

/** The inline pattern from inlineRe() in the prototype. */
val inlineRe = Regex(
    """(`[^`\n]+`)|(\[\[[^\]\n]+\]\])|(\*\*[^*\n]+\*\*|__[^_\n]+__)|(\*[^*\s][^*\n]*\*|(?<![\w])_[^_\s][^_\n]*_(?![\w]))|(~~[^~\n]+~~)|(\[[^\]\n]+\]\([^)\s]+\))|((?<![\w#&/])#[A-Za-z][\w/-]*)"""
)

private fun Color.dim(a: Float) = if (a >= 1f) this else copy(alpha = alpha * a)

/**
 * Live syntax highlighting for source mode. Follows hl(), hlLine() and hlInline() in the prototype.
 * The text stays the same, so the editor can use an identity offset mapping.
 */
object Highlighter {
    private val fenceLine = Regex("""^\s*(```|~~~)""")
    private val headLine = Regex("""^(#{1,6})(\s+)(.*)$""")
    private val hrLine = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
    private val quoteLine = Regex("""^(\s*>+\s?)(.*)$""")
    private val listLine = Regex("""^(\s*)([-*+]|\d+[.)])(\s+)(\[[ xX]\]\s)?(.*)$""")

    private class Ctx(
        val b: AnnotatedString.Builder,
        val decos: MutableList<Deco>,
        val c: QColors,
        val a: Float,
        val off: Int,
        val names: Map<String, String>,
    ) {
        fun sp(s: Int, e: Int, st: SpanStyle?) {
            if (st == null || e <= s) return
            b.addStyle(if (st.color != Color.Unspecified) st.copy(color = st.color.dim(a)) else st, off + s, off + e)
        }

        val mk = SpanStyle(color = c.mark, fontWeight = FontWeight.W400, fontStyle = FontStyle.Normal, textDecoration = TextDecoration.None)
        fun mk(s: Int, e: Int) = sp(s, e, mk)
        fun deco(s: Int, e: Int, color: Color, kind: DecoKind, dashed: Boolean = false, radius: Int = 0) {
            decos.add(Deco(off + s, off + e, color.dim(a), kind, dashed, radius.dp))
        }
    }

    private fun merge(base: SpanStyle?, st: SpanStyle) = base?.merge(st) ?: st

    private fun inline(s: String, from: Int, base: SpanStyle?, x: Ctx) {
        val c = x.c
        val sub = s.substring(from)
        var last = 0
        for (m in inlineRe.findAll(sub)) {
            val st = from + m.range.first
            val en = from + m.range.last + 1
            x.sp(from + last, st, base)
            val t = m.value
            when {
                m.groups[1] != null -> {
                    x.mk(st, st + 1)
                    x.sp(st + 1, en - 1, merge(base, SpanStyle(color = c.ink2)))
                    x.deco(st + 1, en - 1, c.code, DecoKind.Box)
                    x.mk(en - 1, en)
                }
                m.groups[2] != null -> {
                    val inner = t.substring(2, t.length - 2)
                    val ok = Md.resolve(x.names, inner.split('|')[0]) != null
                    x.mk(st, st + 2)
                    x.sp(st + 2, en - 2, merge(base, SpanStyle(color = if (ok) c.acc else c.mute)))
                    x.deco(st + 2, en - 2, if (ok) c.linkLine else c.mark, DecoKind.Underline, dashed = !ok)
                    x.mk(en - 2, en)
                }
                m.groups[3] != null -> {
                    x.mk(st, st + 2)
                    x.sp(st + 2, en - 2, merge(base, SpanStyle(fontWeight = FontWeight.W600, color = c.ink)))
                    x.mk(en - 2, en)
                }
                m.groups[4] != null -> {
                    x.mk(st, st + 1)
                    x.sp(st + 1, en - 1, merge(base, SpanStyle(fontStyle = FontStyle.Italic)))
                    x.mk(en - 1, en)
                }
                m.groups[5] != null -> {
                    x.mk(st, st + 2)
                    x.sp(st + 2, en - 2, merge(base, SpanStyle(textDecoration = TextDecoration.LineThrough, color = c.mute)))
                    x.mk(en - 2, en)
                }
                m.groups[6] != null -> {
                    val j = t.indexOf("](")
                    x.mk(st, st + 1)
                    x.sp(st + 1, st + j, merge(base, SpanStyle(color = c.acc)))
                    x.mk(st + j, en)
                }
                m.groups[7] != null -> {
                    x.sp(st, en, merge(base, SpanStyle(color = c.acc)))
                    x.deco(st, en, c.soft, DecoKind.Box, radius = 3)
                }
            }
            last = m.range.last + 1
        }
        x.sp(from + last, s.length, base)
    }

    private fun line(ln: String, x: Ctx) {
        val c = x.c
        headLine.find(ln)?.let { m ->
            val markEnd = m.groups[2]!!.range.last + 1
            x.sp(0, markEnd, SpanStyle(color = c.acc.copy(alpha = .6f)))
            inline(ln, markEnd, SpanStyle(fontWeight = FontWeight.W600, color = c.ink), x)
            return
        }
        if (hrLine.containsMatchIn(ln)) {
            x.sp(0, ln.length, SpanStyle(color = c.mark))
            return
        }
        quoteLine.find(ln)?.let { m ->
            val e = m.groups[1]!!.range.last + 1
            x.sp(0, e, SpanStyle(color = c.acc))
            inline(ln, e, SpanStyle(fontStyle = FontStyle.Italic, color = c.ink2), x)
            return
        }
        listLine.find(ln)?.let { m ->
            val g2 = m.groups[2]!!.range
            val g3 = m.groups[3]!!.range
            x.sp(g2.first, g3.last + 1, SpanStyle(color = c.acc))
            var base: SpanStyle? = null
            var restStart = g3.last + 1
            val box = m.groups[4]
            if (box != null) {
                val done = box.value.contains('x', ignoreCase = true)
                x.sp(box.range.first, box.range.last + 1, SpanStyle(color = if (done) c.mute else c.acc))
                if (done) base = SpanStyle(color = c.mute, textDecoration = TextDecoration.LineThrough)
                restStart = box.range.last + 1
            }
            inline(ln, restStart, base, x)
            return
        }
        inline(ln, 0, null, x)
    }

    fun highlight(text: String, c: QColors, names: Map<String, String>, dim: IntRange?, hit: TextRange?): Rich {
        val b = AnnotatedString.Builder(text.length + 16)
        b.append(text)
        val decos = ArrayList<Deco>()
        var fence = false
        var off = 0
        val lines = text.split('\n')
        for ((i, ln) in lines.withIndex()) {
            val a = if (dim != null && (i < dim.first || i > dim.last)) 0.28f else 1f
            val x = Ctx(b, decos, c, a, off, names)
            val isF = fenceLine.containsMatchIn(ln)
            if (isF || fence) {
                decos.add(Deco(off, off + ln.length, c.code.dim(a), DecoKind.LineBg))
                x.sp(0, ln.length, SpanStyle(color = if (isF) c.mark else c.ink2))
                if (isF) fence = !fence
            } else {
                if (a < 1f) x.sp(0, ln.length, SpanStyle(color = c.ink))
                line(ln, x)
            }
            off += ln.length + 1
        }
        if (hit != null && hit.max <= text.length && hit.max > hit.min) {
            decos.add(Deco(hit.min, hit.max, c.soft, DecoKind.Box, radius = 3.dp))
        }
        return Rich(b.toAnnotatedString(), decos)
    }
}

/** What preview links and tags do when tapped. */
interface PreviewActions {
    fun openLink(target: String)
    fun searchTag(tag: String)
    fun openUrl(url: String)
}

/**
 * Inline Markdown for preview mode. Follows inl() in the prototype.
 * Tags and inline code get a narrow no-break space on each side, so their rounded background has room.
 */
object Inline {
    private const val PAD = ' '

    fun build(s: String, c: QColors, names: Map<String, String>, mono: FontFamily, act: PreviewActions): Rich {
        val b = AnnotatedString.Builder()
        val decos = ArrayList<Deco>()
        inl(s, b, decos, c, names, mono, act, 1f)
        return Rich(b.toAnnotatedString(), decos)
    }

    private fun inl(
        s: String,
        b: AnnotatedString.Builder,
        decos: MutableList<Deco>,
        c: QColors,
        names: Map<String, String>,
        mono: FontFamily,
        act: PreviewActions,
        scale: Float,
    ) {
        var last = 0
        for (m in inlineRe.findAll(s)) {
            if (m.range.first > last) b.append(s.substring(last, m.range.first))
            val t = m.value
            when {
                m.groups[1] != null -> {
                    val st = b.length
                    b.append(PAD)
                    b.withStyle(SpanStyle(fontFamily = mono, fontSize = 0.8.em)) { append(t.substring(1, t.length - 1)) }
                    b.append(PAD)
                    decos.add(Deco(st, b.length, c.code, DecoKind.Box, radius = 4.dp, em = 0.8f * scale, padV = 0.12f))
                }
                m.groups[2] != null -> {
                    val parts = t.substring(2, t.length - 2).split('|')
                    val tg = parts[0]
                    val label = (parts.getOrNull(1) ?: tg).trim()
                    val ok = Md.resolve(names, tg) != null
                    val st = b.length
                    b.withLink(LinkAnnotation.Clickable("wiki") { act.openLink(tg) }) {
                        withStyle(SpanStyle(color = if (ok) c.acc else c.mute)) { append(label) }
                    }
                    decos.add(Deco(st, b.length, if (ok) c.linkLinePreview else c.mark, DecoKind.Underline, dashed = !ok))
                }
                m.groups[3] != null -> b.withStyle(SpanStyle(fontWeight = FontWeight.W600)) {
                    inl(t.substring(2, t.length - 2), this, decos, c, names, mono, act, scale)
                }
                m.groups[4] != null -> b.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    inl(t.substring(1, t.length - 1), this, decos, c, names, mono, act, scale)
                }
                m.groups[5] != null -> b.withStyle(SpanStyle(color = c.mute, textDecoration = TextDecoration.LineThrough)) {
                    append(t.substring(2, t.length - 2))
                }
                m.groups[6] != null -> {
                    val j = t.indexOf("](")
                    val url = t.substring(j + 2, t.length - 1)
                    b.withLink(LinkAnnotation.Clickable("url") { act.openUrl(url) }) {
                        withStyle(SpanStyle(color = c.acc, textDecoration = TextDecoration.Underline)) { append(t.substring(1, j)) }
                    }
                }
                m.groups[7] != null -> {
                    val st = b.length
                    b.append(PAD)
                    b.withLink(LinkAnnotation.Clickable("tag") { act.searchTag(t.substring(1)) }) {
                        withStyle(SpanStyle(fontFamily = mono, fontSize = 0.72.em, fontStyle = FontStyle.Normal, color = c.acc)) { append(t) }
                    }
                    b.append(PAD)
                    decos.add(Deco(st, b.length, c.soft, DecoKind.Box, radius = 4.dp, em = 0.72f * scale, padV = 0.18f))
                }
            }
            last = m.range.last + 1
        }
        if (last < s.length) b.append(s.substring(last))
    }
}
