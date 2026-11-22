package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkTranslucentGlassScheme = darkColorScheme(
    primary = AccentCyan,
    onPrimary = DarkInkOnAccent,
    primaryContainer = GlassPillActiveFill,
    onPrimaryContainer = TextPrimary,
    secondary = AccentGold,
    onSecondary = DarkInkOnAccent,
    secondaryContainer = GlassCardFill,
    onSecondaryContainer = TextHero,
    tertiary = AccentEmerald,
    onTertiary = DarkInkOnAccent,
    error = AccentRed,
    onError = TextPrimary,
    background = GlassRootBlack,
    onBackground = TextPrimary,
    surface = GlassCardFill,
    onSurface = TextPrimary,
    surfaceVariant = SmokedGlassFill,
    onSurfaceVariant = TextSubtitle,
    outline = GlassPillBorder,
    outlineVariant = GlassBandBorder,
)

@Composable
fun ToBusTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkTranslucentGlassScheme,
        typography = AppTypography,
        content = content,
    )
}
