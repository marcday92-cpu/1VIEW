package com.iptv.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.luminance

const val DefaultAccentArgb = 0xFFC9A227L
const val LegacyBlueAccentArgb = 0xFF3D8BFFL

val Charcoal = Color(0xFF07111F)
val SurfaceDark = Color(0xFF122033)
/** Fill behind channel logos so square and wide art share one box. */
val LogoFill = Color(0xFF1A1C24)
val Panel = Color(0xCC0E1A2C)
val DefaultAccent = Color(DefaultAccentArgb)

/** Text on the dark background. */
val TextPrimary = Color(0xFFF2F2F5)
/** Supporting text: times, channel names, metadata. */
val TextSecondary = Color(0xFFB9B9C4)
/** Least important text. Still readable against Charcoal. */
val TextMuted = Color(0xFF8A8A96)

val LocalAccent = staticCompositionLocalOf { DefaultAccent }

@Composable
fun TvTheme(
    accent: Color = DefaultAccent,
    content: @Composable () -> Unit,
) {
    val onAccent = if (accent.luminance() > 0.45f) Color.Black else Color.White
    val scheme: ColorScheme = darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        secondary = accent,
        onSecondary = onAccent,
        background = Charcoal,
        onBackground = TextPrimary,
        surface = SurfaceDark,
        onSurface = TextPrimary,
        surfaceVariant = Color(0xFF1A2A40),
        onSurfaceVariant = TextSecondary,
        inverseSurface = Color(0xFFF2F2F5),
        inverseOnSurface = Color(0xFF15151A),
        border = Color(0x33FFFFFF),
    )
    // tv-material3 defaults LocalContentColor to black, which is invisible on our
    // dark surfaces whenever a Text has no explicit colour.
    CompositionLocalProvider(
        LocalAccent provides accent,
        LocalContentColor provides TextPrimary,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(8.dp),
                large = RoundedCornerShape(12.dp),
            ),
            content = content,
        )
    }
}
