package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

@OptIn(ExperimentalTextApi::class)
private fun outfitFamily(varWeight: Int, fallback: FontWeight): FontFamily {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        FontFamily(
            Font(
                resId = R.font.outfit,
                weight = fallback,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(varWeight),
                ),
            ),
        )
    } else {
        FontFamily(Font(R.font.outfit, fallback))
    }
}

val OutfitUltraThin = outfitFamily(120, FontWeight.Thin)
val OutfitHeroLight = outfitFamily(250, FontWeight.ExtraLight)
val OutfitSubtitle = outfitFamily(300, FontWeight.Light)
val OutfitRegular = outfitFamily(400, FontWeight.Normal)
val OutfitMedium = outfitFamily(500, FontWeight.Medium)
val OutfitSemiBold = outfitFamily(600, FontWeight.SemiBold)

private val CompactPlatformStyle = PlatformTextStyle(includeFontPadding = false)

val AppTypography = Typography(
    // Display / Giant KPIs / Clocks — Outfit 120
    displayLarge = TextStyle(
        fontFamily = OutfitUltraThin,
        fontSize = 48.sp,
        lineHeight = 52.sp,
        letterSpacing = (-1.0).sp,
        color = TextPrimary,
        platformStyle = CompactPlatformStyle,
    ),
    displaySmall = TextStyle(
        fontFamily = OutfitUltraThin,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
        color = TextPrimary,
        platformStyle = CompactPlatformStyle,
    ),
    // Hero Titles / Screen Headers (28sp-38sp) — Outfit 250
    headlineLarge = TextStyle(
        fontFamily = OutfitHeroLight,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.4).sp,
        color = TextHero,
        platformStyle = CompactPlatformStyle,
    ),
    headlineMedium = TextStyle(
        fontFamily = OutfitHeroLight,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.3).sp,
        color = TextHero,
        platformStyle = CompactPlatformStyle,
    ),
    headlineSmall = TextStyle(
        fontFamily = OutfitHeroLight,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        color = TextHero,
        platformStyle = CompactPlatformStyle,
    ),
    // Subtitles & Metadata (15sp-18sp) — Outfit 300
    titleLarge = TextStyle(
        fontFamily = OutfitSubtitle,
        fontSize = 19.sp,
        lineHeight = 23.sp,
        color = TextHero,
        platformStyle = CompactPlatformStyle,
    ),
    titleMedium = TextStyle(
        fontFamily = OutfitSubtitle,
        fontSize = 16.sp,
        lineHeight = 20.sp,
        color = TextBody,
        platformStyle = CompactPlatformStyle,
    ),
    titleSmall = TextStyle(
        fontFamily = OutfitRegular,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = TextBody,
        platformStyle = CompactPlatformStyle,
    ),
    // Body / Labels / Chips / Buttons (12sp-15sp) — Outfit 400
    bodyLarge = TextStyle(
        fontFamily = OutfitRegular,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = TextBody,
    ),
    bodyMedium = TextStyle(
        fontFamily = OutfitSubtitle,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        color = TextSubtitle,
    ),
    bodySmall = TextStyle(
        fontFamily = OutfitRegular,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = TextSubtitle,
    ),
    // Micro-badges & High-contrast CTAs — Outfit 500-600
    labelLarge = TextStyle(
        fontFamily = OutfitMedium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = TextPrimary,
        platformStyle = CompactPlatformStyle,
    ),
    labelMedium = TextStyle(
        fontFamily = OutfitRegular,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = TextBody,
        platformStyle = CompactPlatformStyle,
    ),
    labelSmall = TextStyle(
        fontFamily = OutfitMedium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp,
        color = TextSubtitle,
        platformStyle = CompactPlatformStyle,
    ),
)
