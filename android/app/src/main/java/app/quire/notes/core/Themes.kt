package app.quire.notes.core

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A colour in linear sRGB with straight alpha, as the desktop app keeps colours. Mixes happen in
 * linear light, so the tones match the desktop app.
 */
data class Rgb(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    fun alpha(a: Float) = copy(a = a)

    /** [t] of the way from this colour to [o], straight in linear light. */
    fun mix(o: Rgb, t: Float): Rgb {
        fun m(x: Float, y: Float) = x + (y - x) * t
        return Rgb(m(r, o.r), m(g, o.g), m(b, o.b), m(a, o.a))
    }

    fun luminance(): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** The colour as an ARGB int in sRGB, for Compose. */
    fun argb(): Int {
        fun enc(c: Float) = (encode(c.coerceIn(0f, 1f)) * 255f).roundToInt()
        return ((a.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or (enc(r) shl 16) or (enc(g) shl 8) or enc(b)
    }

    companion object {
        val WHITE = Rgb(1f, 1f, 1f)
        val BLACK = Rgb(0f, 0f, 0f)

        fun decode(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        fun encode(c: Float): Float = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

        /** "#rgb" or "#rrggbb" in sRGB, or null. */
        fun hex(s: String): Rgb? {
            if (!s.startsWith("#")) return null
            val h = s.substring(1)
            val parts = when (h.length) {
                3 -> h.map { "$it$it" }
                6 -> listOf(h.substring(0, 2), h.substring(2, 4), h.substring(4, 6))
                else -> return null
            }
            val v = parts.map { it.toIntOrNull(16) ?: return null }
            return Rgb(decode(v[0] / 255f), decode(v[1] / 255f), decode(v[2] / 255f))
        }

        fun of(h: String, a: Float = 1f): Rgb = (hex(h) ?: BLACK).alpha(a)

        /** The WCAG contrast ratio of two colours. */
        fun contrast(x: Rgb, y: Rgb): Float {
            val la = x.luminance() + 0.05f
            val lb = y.luminance() + 0.05f
            return maxOf(la, lb) / minOf(la, lb)
        }
    }
}

/** Every colour that Quire draws with. Follows Pal in desktop/src/theme.rs. */
data class Pal(
    val dark: Boolean,
    val desk: Rgb,
    val bg: Rgb,
    val side: Rgb,
    val ink: Rgb,
    val ink2: Rgb,
    val mute: Rgb,
    val mark: Rgb,
    val line: Rgb,
    val hover: Rgb,
    val act: Rgb,
    /** The accent for text: links, tags and marks. It always reads on the background. */
    val acc: Rgb,
    val soft: Rgb,
    val code: Rgb,
    val err: Rgb,
    /** The raw accent of primary buttons and switches. */
    val button: Rgb,
    val buttonHover: Rgb,
    val linkLine: Rgb,
    /** The underline of a resolved link in preview. */
    val linkLinePreview: Rgb,
    /** Text and icons on the accent. */
    val onAcc: Rgb,
    /** The selection in single-line fields, and in the editor. */
    val sel: Rgb,
    val selEditor: Rgb,
    val scrimPalette: Rgb,
    val scrimSettings: Rgb,
)

/** A built-in theme: background, sidebar, ink, second ink, muted text, faint marks, accent and error. */
class ThemeDef(val id: String, val name: String, val dark: Boolean, val colors: List<String>)

/**
 * The built-in themes, the accents and the palette math of desktop/src/theme.rs. Paper and Ink
 * are the palettes that the app had before themes. The others take the main colours of known
 * editor themes, and the other tones mix from those.
 */
object Themes {
    val list = listOf(
        ThemeDef("paper", "Paper", false, listOf("#f8f5ef", "#efebe3", "#2a2723", "#5b554d", "#776f64", "#b2a999", "#7a4fba", "#b0432f")),
        ThemeDef("white", "White", false, listOf("#ffffff", "#f5f5f3", "#1f1e1c", "#474540", "#6b6863", "#aeaaa3", "#7a4fba", "#b0432f")),
        ThemeDef("solarized-light", "Solarized Light", false, listOf("#fdf6e3", "#eee8d5", "#073642", "#586e75", "#657b83", "#93a1a1", "#268bd2", "#dc322f")),
        ThemeDef("gruvbox-light", "Gruvbox Light", false, listOf("#fbf1c7", "#f2e5bc", "#3c3836", "#504945", "#7c6f64", "#a89984", "#af3a03", "#9d0006")),
        ThemeDef("catppuccin-latte", "Catppuccin Latte", false, listOf("#eff1f5", "#e6e9ef", "#4c4f69", "#5c5f77", "#6c6f85", "#9ca0b0", "#8839ef", "#d20f39")),
        ThemeDef("rose-pine-dawn", "Rosé Pine Dawn", false, listOf("#faf4ed", "#f2e9e1", "#575279", "#686388", "#797593", "#9893a5", "#907aa9", "#b4637a")),
        ThemeDef("ink", "Ink", true, listOf("#1f1d1a", "#181715", "#e9e3d8", "#bdb5a7", "#948c7f", "#5f584e", "#b597e8", "#e8907c")),
        ThemeDef("black", "Black", true, listOf("#000000", "#0a0a0a", "#e8e6e3", "#b8b5b0", "#8c8984", "#4a4845", "#b597e8", "#e8907c")),
        ThemeDef("nord", "Nord", true, listOf("#2e3440", "#272c36", "#eceff4", "#d8dee9", "#9aa3b5", "#4c566a", "#88c0d0", "#bf616a")),
        ThemeDef("gruvbox-dark", "Gruvbox Dark", true, listOf("#282828", "#1d2021", "#ebdbb2", "#d5c4a1", "#a89984", "#665c54", "#fe8019", "#fb4934")),
        ThemeDef("catppuccin-mocha", "Catppuccin Mocha", true, listOf("#1e1e2e", "#181825", "#cdd6f4", "#bac2de", "#9399b2", "#585b70", "#cba6f7", "#f38ba8")),
        ThemeDef("tokyo-night", "Tokyo Night", true, listOf("#1a1b26", "#16161e", "#c0caf5", "#a9b1d6", "#7a7fa0", "#414868", "#7aa2f7", "#f7768e")),
        ThemeDef("rose-pine", "Rosé Pine", true, listOf("#1f1d2e", "#191724", "#e0def4", "#bcb9d6", "#908caa", "#6e6a86", "#c4a7e7", "#eb6f92")),
        ThemeDef("solarized-dark", "Solarized Dark", true, listOf("#002b36", "#00232c", "#eee8d5", "#93a1a1", "#839496", "#586e75", "#268bd2", "#dc322f")),
    )

    /** The accents that the settings offer, with their names. The first one keeps the accent of the theme. */
    val accents = listOf(
        "" to "Theme accent",
        "#7a4fba" to "Violet",
        "#2f6fdf" to "Blue",
        "#1f8a80" to "Teal",
        "#3f8f3a" to "Green",
        "#c27c0e" to "Amber",
        "#d4622a" to "Orange",
        "#c8414b" to "Red",
        "#c2417f" to "Pink",
        "#6b6b6b" to "Graphite",
    )

    /** The light palette that the app had before themes. */
    val PAPER = Pal(
        dark = false,
        desk = Rgb.of("#dcd6ca"), bg = Rgb.of("#f8f5ef"), side = Rgb.of("#efebe3"), ink = Rgb.of("#2a2723"),
        ink2 = Rgb.of("#5b554d"), mute = Rgb.of("#776f64"), mark = Rgb.of("#b2a999"), line = Rgb.of("#e1dbcf"),
        hover = Rgb.of("#463214", .06f), act = Rgb.of("#463214", .09f), acc = Rgb.of("#7a4fba"), soft = Rgb.of("#ebe3f5"),
        code = Rgb.of("#efe9de"), err = Rgb.of("#b0432f"), button = Rgb.of("#7d52bd"), buttonHover = Rgb.of("#6c44a8"),
        linkLine = Rgb.of("#8a5fc9", .4f), linkLinePreview = Rgb.of("#8a5fc9", .38f), onAcc = Rgb.WHITE,
        sel = Rgb.of("#8a5fc9", .26f), selEditor = Rgb.of("#8a5fc9", .26f),
        scrimPalette = Rgb.of("#281e0f", .16f), scrimSettings = Rgb.of("#281e0f", .22f),
    )

    /** The dark palette that the app had before themes. */
    val INK = Pal(
        dark = true,
        desk = Rgb.of("#0f0e0d"), bg = Rgb.of("#1f1d1a"), side = Rgb.of("#181715"), ink = Rgb.of("#e9e3d8"),
        ink2 = Rgb.of("#bdb5a7"), mute = Rgb.of("#948c7f"), mark = Rgb.of("#5f584e"), line = Rgb.of("#322e28"),
        hover = Rgb.of("#fff0d2", .05f), act = Rgb.of("#fff0d2", .08f), acc = Rgb.of("#b597e8"), soft = Rgb.of("#2f2839"),
        code = Rgb.of("#27241f"), err = Rgb.of("#e8907c"), button = Rgb.of("#7d52bd"), buttonHover = Rgb.of("#6c44a8"),
        linkLine = Rgb.of("#8a5fc9", .4f), linkLinePreview = Rgb.of("#8a5fc9", .38f), onAcc = Rgb.WHITE,
        sel = Rgb.of("#8a5fc9", .26f), selEditor = Rgb.of("#8a5fc9", .26f),
        // The faint scrim of the design does not show on this dark palette, so the app uses a deeper one.
        scrimPalette = Rgb.BLACK.alpha(.45f), scrimSettings = Rgb.BLACK.alpha(.45f),
    )

    fun def(id: String): ThemeDef? = list.find { it.id == id }

    /** A "#rgb" or "#rrggbb" colour, with or without "#", or null. */
    fun parseAccent(s: String): Rgb? {
        val t0 = s.trim()
        val t = if (t0.startsWith("#")) t0 else "#$t0"
        if (t.length != 4 && t.length != 7) return null
        if (!t.substring(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return Rgb.hex(t)
    }

    /**
     * [c] moved toward white on a dark background, or toward black on a light one, in steps of
     * 0.05, until text in it has a contrast of at least [min] on [bg].
     */
    fun readable(c: Rgb, bg: Rgb, dark: Boolean, min: Float): Rgb {
        val toward = if (dark) Rgb.WHITE else Rgb.BLACK
        var out = c
        var t = 0f
        while (Rgb.contrast(out, bg) < min && t < 1f) {
            t += 0.05f
            out = c.mix(toward, t)
        }
        return out
    }

    /** A palette with another accent: links, tags, buttons, the selection and the soft fills. */
    fun withAccent(base: Pal, c: Rgb): Pal = base.copy(
        acc = readable(c, base.bg, base.dark, 3f),
        button = c,
        buttonHover = c.mix(Rgb.BLACK, 0.14f),
        soft = base.bg.mix(c, if (base.dark) 0.2f else 0.12f),
        linkLine = c.alpha(0.4f),
        linkLinePreview = c.alpha(0.38f),
        onAcc = if (Rgb.contrast(Rgb.WHITE, c) > Rgb.contrast(Rgb.BLACK, c)) Rgb.WHITE else Rgb.BLACK,
        sel = c.alpha(0.22f),
        selEditor = c.alpha(0.26f),
    )

    fun build(t: ThemeDef): Pal {
        val (bg, side, ink, ink2, mute) = t.colors.take(5).map { Rgb.of(it) }
        val mark = Rgb.of(t.colors[5])
        val acc = Rgb.of(t.colors[6])
        val err = Rgb.of(t.colors[7])
        val dark = t.dark
        val hoverA = if (dark) 0.05f else 0.06f
        val base = Pal(
            dark = dark, desk = side, bg = bg, side = side, ink = ink, ink2 = ink2, mute = mute, mark = mark,
            line = bg.mix(ink, if (dark) 0.1f else 0.11f),
            hover = ink.alpha(hoverA), act = ink.alpha(hoverA + 0.03f),
            acc = acc, soft = bg, code = bg.mix(ink, 0.045f), err = err,
            button = acc, buttonHover = acc, linkLine = acc, linkLinePreview = acc, onAcc = Rgb.WHITE, sel = acc, selEditor = acc,
            scrimPalette = if (dark) Rgb.BLACK.alpha(0.3f) else Rgb.of("#281e0f", 0.16f),
            scrimSettings = if (dark) Rgb.BLACK.alpha(0.38f) else Rgb.of("#281e0f", 0.22f),
        )
        return withAccent(base, acc)
    }

    private val built: Map<String, Pal> by lazy { list.filter { it.id != "paper" && it.id != "ink" }.associate { it.id to build(it) } }

    /** The palette of a theme id. An unknown id gives Paper or Ink. */
    fun themePal(id: String, dark: Boolean): Pal = when (id) {
        "paper" -> PAPER
        "ink" -> INK
        else -> built[id] ?: if (dark) INK else PAPER
    }

    private val cache = HashMap<Triple<String, String, Boolean>, Pal>()

    /** [themePal] with the accent from the settings, when one is set. */
    fun themed(id: String, dark: Boolean, accent: String): Pal {
        val base = themePal(id, dark)
        if (accent.isBlank()) return base
        val c = parseAccent(accent) ?: return base
        val key = Triple(id, accent.trim().lowercase(), dark)
        return synchronized(cache) { cache.getOrPut(key) { withAccent(base, c) } }
    }
}
