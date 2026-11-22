package com.craft.capturespace.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Accent colours read from the original's colour table (see MECHANICS.md). */
object Palette {
    val Red = Color(0xFFD71921)     // button_delete, cell_mediaplayer_color, accent
    val Yellow = Color(0xFFFFC700)  // essential_yellow
    val YellowTint = Color(0x19FFC700) // detail_hint_bg_color
    val Blue = Color(0xFF4C94EB)    // content_accent_3
    val InputBar = Color(0xFF323232) // input_bar_bg_color
    val InputText = Color(0xFFF2F2F2) // input_bar_text_color
}

/** Timings read from the original's integer resources (base.apk, 2.2.0). */
object Motion {
    const val NAV_MS = 150          // config_navAnimTime, used by nav_default_*_anim (alpha 0↔1)
    const val TAB_INDICATOR_MS = 300 // design_tab_indicator_anim_duration_ms
    const val CARD_MS = 120         // mtrl_card_anim_duration_ms
    const val BUTTON_MS = 100       // m3_btn_anim_duration_ms
    const val SHEET_MS = 150        // bottom_sheet_slide_duration
}

/** Sizes read from the original's dimension resources (border_radius_*, ref_spacer_*, spectrum_*). */
object Dimens {
    val RadiusXs = 8.dp
    val RadiusS = 16.dp
    val RadiusM = 20.dp   // capture and section cards
    val RadiusL = 24.dp
    val PageMargin = 24.dp
    val MarginDense = 16.dp
    val InputBarMin = 64.dp
    val SpectrumBar = 2.dp
    val SpectrumMaxHalf = 16.dp
    const val LABEL_TRACKING = 0.1f // ref_tracking_loose_100 (em)
}

@Immutable
data class Tones(
    val dark: Boolean,
    val bg: Color,
    val card: Color,
    val card2: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val divider: Color,
    val chip: Color,
)

val LightTones = Tones(
    dark = false,
    bg = Color(0xFFF2F2F2), card = Color(0xFFFFFFFF), card2 = Color(0xFFE8E8E8),
    text = Color(0xFF1C1C1C), text2 = Color(0xFF4A4A4A), text3 = Color(0xFF777777),
    divider = Color(0xFFDDDDDD), chip = Color(0xFFE4E4E4),
)

val DarkTones = Tones(
    dark = true,
    bg = Color(0xFF000000), card = Color(0xFF1B1B1B), card2 = Color(0xFF262626),
    text = Color(0xFFF2F2F2), text2 = Color(0xFFBDBDBD), text3 = Color(0xFF8A8A8A),
    divider = Color(0xFF2C2C2C), chip = Color(0xFF2A2A2A),
)

val LocalTones = staticCompositionLocalOf { LightTones }

@Composable
fun SpaceTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val t = if (dark) DarkTones else LightTones
    val scheme = if (dark) darkColorScheme(
        primary = t.text, onPrimary = t.bg, background = t.bg, onBackground = t.text,
        surface = t.card, onSurface = t.text, surfaceVariant = t.card2, onSurfaceVariant = t.text2,
        error = Palette.Red, outline = t.divider, surfaceContainerHigh = t.card, surfaceContainer = t.card,
    ) else lightColorScheme(
        primary = t.text, onPrimary = t.bg, background = t.bg, onBackground = t.text,
        surface = t.card, onSurface = t.text, surfaceVariant = t.card2, onSurfaceVariant = t.text2,
        error = Palette.Red, outline = t.divider, surfaceContainerHigh = t.card, surfaceContainer = t.card,
    )
    val f = FontFamily.SansSerif
    val type = Typography(
        headlineSmall = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = TextStyle(fontFamily = f, fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = TextStyle(fontFamily = f, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontFamily = f, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = 14.sp),
        labelMedium = TextStyle(fontFamily = f, fontSize = 12.sp, letterSpacing = 0.6.sp),
        labelSmall = TextStyle(fontFamily = f, fontSize = 11.sp, letterSpacing = 1.sp),
    )
    CompositionLocalProvider(LocalTones provides t) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

/** A 5x7 dot-matrix alphabet drawn as dots (my own glyph set). */
private val Glyphs: Map<Char, IntArray> = mapOf(
    'A' to intArrayOf(0x0E, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
    'B' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x11, 0x11, 0x1E),
    'C' to intArrayOf(0x0E, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0E),
    'D' to intArrayOf(0x1E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x1E),
    'E' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x1F),
    'F' to intArrayOf(0x1F, 0x10, 0x10, 0x1E, 0x10, 0x10, 0x10),
    'G' to intArrayOf(0x0E, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0F),
    'H' to intArrayOf(0x11, 0x11, 0x11, 0x1F, 0x11, 0x11, 0x11),
    'I' to intArrayOf(0x0E, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0E),
    'J' to intArrayOf(0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0C),
    'K' to intArrayOf(0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11),
    'L' to intArrayOf(0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1F),
    'M' to intArrayOf(0x11, 0x1B, 0x15, 0x15, 0x11, 0x11, 0x11),
    'N' to intArrayOf(0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11),
    'O' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
    'P' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x10, 0x10, 0x10),
    'Q' to intArrayOf(0x0E, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0D),
    'R' to intArrayOf(0x1E, 0x11, 0x11, 0x1E, 0x14, 0x12, 0x11),
    'S' to intArrayOf(0x0F, 0x10, 0x10, 0x0E, 0x01, 0x01, 0x1E),
    'T' to intArrayOf(0x1F, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04),
    'U' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0E),
    'V' to intArrayOf(0x11, 0x11, 0x11, 0x11, 0x11, 0x0A, 0x04),
    'W' to intArrayOf(0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0A),
    'X' to intArrayOf(0x11, 0x11, 0x0A, 0x04, 0x0A, 0x11, 0x11),
    'Y' to intArrayOf(0x11, 0x11, 0x0A, 0x04, 0x04, 0x04, 0x04),
    'Z' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1F),
    '0' to intArrayOf(0x0E, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0E),
    '1' to intArrayOf(0x04, 0x0C, 0x04, 0x04, 0x04, 0x04, 0x0E),
    '2' to intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1F),
    '3' to intArrayOf(0x1F, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0E),
    '4' to intArrayOf(0x02, 0x06, 0x0A, 0x12, 0x1F, 0x02, 0x02),
    '5' to intArrayOf(0x1F, 0x10, 0x1E, 0x01, 0x01, 0x11, 0x0E),
    '6' to intArrayOf(0x06, 0x08, 0x10, 0x1E, 0x11, 0x11, 0x0E),
    '7' to intArrayOf(0x1F, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08),
    '8' to intArrayOf(0x0E, 0x11, 0x11, 0x0E, 0x11, 0x11, 0x0E),
    '9' to intArrayOf(0x0E, 0x11, 0x11, 0x0F, 0x01, 0x02, 0x0C),
    ' ' to intArrayOf(0, 0, 0, 0, 0, 0, 0),
    '.' to intArrayOf(0, 0, 0, 0, 0, 0x0C, 0x0C),
    '-' to intArrayOf(0, 0, 0, 0x1F, 0, 0, 0),
    ':' to intArrayOf(0, 0x0C, 0x0C, 0, 0x0C, 0x0C, 0),
    '/' to intArrayOf(0x01, 0x01, 0x02, 0x04, 0x08, 0x10, 0x10),
    '&' to intArrayOf(0x0C, 0x12, 0x14, 0x08, 0x15, 0x12, 0x0D),
    '!' to intArrayOf(0x04, 0x04, 0x04, 0x04, 0x04, 0, 0x04),
    '?' to intArrayOf(0x0E, 0x11, 0x01, 0x02, 0x04, 0, 0x04),
)

/** Text drawn as dots, for headings and empty states. */
@Composable
fun DotText(
    text: String,
    modifier: Modifier = Modifier,
    dot: Dp = 3.dp,
    gap: Dp = 1.3.dp,
    color: Color = LocalTones.current.text,
) {
    val up = text.uppercase()
    val cols = if (up.isEmpty()) 0 else up.length * 6 - 1
    val step = dot + gap
    Canvas(modifier.size(width = step * cols, height = step * 7)) {
        val s = step.toPx()
        val r = dot.toPx() / 2f
        up.forEachIndexed { i, ch ->
            val g = Glyphs[ch] ?: Glyphs['?']!!
            for (row in 0 until 7) for (c in 0 until 5) {
                if ((g[row] shr (4 - c)) and 1 == 1) {
                    drawCircle(color, r, Offset((i * 6 + c) * s + r, row * s + r))
                }
            }
        }
    }
}
