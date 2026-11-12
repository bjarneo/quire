package app.quire.notes.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.quire.notes.R
import app.quire.notes.core.Pal
import app.quire.notes.core.Rgb
import app.quire.notes.core.Themes

/** The colours that the UI draws with, made from a [Pal] of the picked theme. */
@Immutable
data class QColors(
    val desk: Color,
    val bg: Color,
    val side: Color,
    val ink: Color,
    val ink2: Color,
    val mute: Color,
    val mark: Color,
    val line: Color,
    val hover: Color,
    val act: Color,
    /** The accent for text. It reads on the background. */
    val acc: Color,
    val soft: Color,
    val code: Color,
    val err: Color,
    val dark: Boolean,
    /** Primary buttons and switches. */
    val button: Color,
    val buttonPressed: Color,
    /** Text and icons on the accent. */
    val onAcc: Color,
    val linkLine: Color,
    val linkLinePreview: Color,
    /** The selection in fields, and in the editor. */
    val sel: Color,
    val selEditor: Color,
    /** The scrim behind the drawer and the sheets. */
    val scrim: Color,
) {
    companion object {
        fun of(p: Pal): QColors {
            fun c(x: Rgb) = Color(x.argb())
            return QColors(
                desk = c(p.desk), bg = c(p.bg), side = c(p.side), ink = c(p.ink), ink2 = c(p.ink2), mute = c(p.mute),
                mark = c(p.mark), line = c(p.line), hover = c(p.hover), act = c(p.act), acc = c(p.acc), soft = c(p.soft),
                code = c(p.code), err = c(p.err), dark = p.dark, button = c(p.button), buttonPressed = c(p.buttonHover),
                onAcc = c(p.onAcc), linkLine = c(p.linkLine), linkLinePreview = c(p.linkLinePreview), sel = c(p.sel),
                selEditor = c(p.selEditor), scrim = c(p.scrimSettings),
            )
        }
    }
}

/** Paper and Ink, the palettes from applyTheme() in the prototype. */
val LightQ = QColors.of(Themes.PAPER)
val DarkQ = QColors.of(Themes.INK)

val LocalQ = staticCompositionLocalOf { LightQ }

val Shadow = Color(40, 28, 10)

object QFonts {
    @OptIn(ExperimentalTextApi::class)
    private fun vf(res: Int, w: Int, italic: Boolean, opsz: Float? = null): Font {
        val axes = if (opsz != null) {
            FontVariation.Settings(FontVariation.weight(w), FontVariation.opticalSizing(opsz.sp))
        } else {
            FontVariation.Settings(FontVariation.weight(w))
        }
        return Font(
            resId = res,
            weight = FontWeight(w),
            style = if (italic) FontStyle.Italic else FontStyle.Normal,
            variationSettings = axes,
        )
    }

    /** Instrument Sans, the UI face. */
    val sans = FontFamily(
        listOf(400, 500, 600, 700).flatMap { w ->
            listOf(vf(R.font.instrument_sans, w, false), vf(R.font.instrument_sans_italic, w, true))
        }
    )

    /** Newsreader at a fixed optical size. Browsers pick the optical size from the font size. */
    private fun newsreader(opsz: Float) = FontFamily(
        listOf(400, 500, 600, 700).flatMap { w ->
            listOf(vf(R.font.newsreader, w, false, opsz), vf(R.font.newsreader_italic, w, true, opsz))
        }
    )

    val serifText = newsreader(19f)
    val serifHead = newsreader(30f)

    /** IBM Plex Mono, the default editor face and the face of all small labels. */
    val plex = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.W400),
        Font(R.font.ibm_plex_mono_italic, FontWeight.W400, FontStyle.Italic),
        Font(R.font.ibm_plex_mono_medium, FontWeight.W500),
        Font(R.font.ibm_plex_mono_medium_italic, FontWeight.W500, FontStyle.Italic),
        Font(R.font.ibm_plex_mono_semibold, FontWeight.W600),
        Font(R.font.ibm_plex_mono_semibold_italic, FontWeight.W600, FontStyle.Italic),
        Font(R.font.ibm_plex_mono_bold, FontWeight.W700),
    )

    val courier = FontFamily(
        Font(R.font.courier_prime_regular, FontWeight.W400),
        Font(R.font.courier_prime_italic, FontWeight.W400, FontStyle.Italic),
        Font(R.font.courier_prime_bold, FontWeight.W700),
        Font(R.font.courier_prime_bold_italic, FontWeight.W700, FontStyle.Italic),
    )

    val spline = FontFamily(
        listOf(400, 500, 600, 700).flatMap { w ->
            listOf(vf(R.font.spline_sans_mono, w, false), vf(R.font.spline_sans_mono_italic, w, true))
        }
    )

    fun editor(id: String): FontFamily = when (id) {
        "courier" -> courier
        "spline" -> spline
        else -> plex
    }
}
