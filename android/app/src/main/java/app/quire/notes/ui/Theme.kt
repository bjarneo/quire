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

/** The palette from applyTheme() in the prototype. */
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
    val acc: Color,
    val soft: Color,
    val code: Color,
    val err: Color,
    val dark: Boolean,
)

val LightQ = QColors(
    desk = Color(0xFFDCD6CA), bg = Color(0xFFF8F5EF), side = Color(0xFFEFEBE3), ink = Color(0xFF2A2723),
    ink2 = Color(0xFF5B554D), mute = Color(0xFF776F64), mark = Color(0xFFB2A999), line = Color(0xFFE1DBCF),
    hover = Color(70, 50, 20).copy(alpha = .06f), act = Color(70, 50, 20).copy(alpha = .09f),
    acc = Color(0xFF7A4FBA), soft = Color(0xFFEBE3F5), code = Color(0xFFEFE9DE), err = Color(0xFFB0432F), dark = false,
)

val DarkQ = QColors(
    desk = Color(0xFF0F0E0D), bg = Color(0xFF1F1D1A), side = Color(0xFF181715), ink = Color(0xFFE9E3D8),
    ink2 = Color(0xFFBDB5A7), mute = Color(0xFF948C7F), mark = Color(0xFF5F584E), line = Color(0xFF322E28),
    hover = Color(255, 240, 210).copy(alpha = .05f), act = Color(255, 240, 210).copy(alpha = .08f),
    acc = Color(0xFFB597E8), soft = Color(0xFF2F2839), code = Color(0xFF27241F), err = Color(0xFFE8907C), dark = true,
)

val LocalQ = staticCompositionLocalOf { LightQ }

/** Primary buttons and switches use this violet in both themes. */
val Primary = Color(0xFF7D52BD)
val PrimaryPressed = Color(0xFF6C44A8)

/** The link underline and the text selection color from the prototype. */
val LinkLine = Color(138, 95, 201).copy(alpha = .4f)
val LinkLinePreview = Color(138, 95, 201).copy(alpha = .38f)
val Selection = Color(138, 95, 201).copy(alpha = .26f)
val Overlay = Color(40, 30, 15).copy(alpha = .22f)

/** The prototype's overlay is too faint on the dark palette, so dark mode uses a deeper scrim. */
fun overlay(c: QColors) = if (c.dark) Color.Black.copy(alpha = .45f) else Overlay
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
