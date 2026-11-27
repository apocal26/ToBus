package com.example.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.data.model.ServiceAlertCategory
import com.example.ui.icons.ToBusIcons

/**
 * Calibrated Dark Liquid Glass visual tokens for each [ServiceAlertCategory]:
 * - [ServiceAlertCategory.CRITICAL]   -> Red (strikes, line/station suspensions)
 * - [ServiceAlertCategory.DISRUPTION] -> Orange (detours, roadworks, moved/suppressed stops, delays)
 * - [ServiceAlertCategory.NOTICE]     -> Amber/Gold (elevators, escalators, ticket machines, minor warnings)
 * - [ServiceAlertCategory.POSITIVE]   -> Emerald (extra buses/trains, service boosts, extended hours, reopenings)
 * - [ServiceAlertCategory.INFO]       -> Cyan (general info, timetables, fares, events)
 */
@Immutable
data class AlertVisualStyle(
    val accent: Color,
    val softText: Color,
    val onAccentInk: Color,
    val bellGradientTop: Color,
    val bellGradientBottom: Color,
    val icon: ImageVector,
    val widgetStatusColorArgb: Int,
)

fun ServiceAlertCategory.visualStyle(): AlertVisualStyle = when (this) {
    ServiceAlertCategory.CRITICAL -> AlertVisualStyle(
        accent = AccentRed,
        softText = Color(0xFFFFA3A3),
        onAccentInk = Color.White,
        bellGradientTop = Color(0xEE5C1420),
        bellGradientBottom = Color(0xEB2F0A11),
        icon = Icons.Rounded.Warning,
        widgetStatusColorArgb = 0xFFFF8A8A.toInt(),
    )
    ServiceAlertCategory.DISRUPTION -> AlertVisualStyle(
        accent = AccentOrange,
        softText = Color(0xFFFDBA74),
        onAccentInk = DarkInkOnAccent,
        bellGradientTop = Color(0xEE5A260C),
        bellGradientBottom = Color(0xEB2E1205),
        icon = ToBusIcons.WarningAmber,
        widgetStatusColorArgb = 0xFFFB923C.toInt(),
    )
    ServiceAlertCategory.NOTICE -> AlertVisualStyle(
        accent = AccentGold,
        softText = Color(0xFFFDE68A),
        onAccentInk = DarkInkOnAccent,
        bellGradientTop = Color(0xEE4A3712),
        bellGradientBottom = Color(0xEB241B08),
        icon = ToBusIcons.WarningAmber,
        widgetStatusColorArgb = 0xFFFBBF24.toInt(),
    )
    ServiceAlertCategory.POSITIVE -> AlertVisualStyle(
        accent = AccentEmerald,
        softText = Color(0xFF6EE7B7),
        onAccentInk = DarkInkOnAccent,
        bellGradientTop = Color(0xEE0D4433),
        bellGradientBottom = Color(0xEB07231A),
        icon = ToBusIcons.AutoAwesome,
        widgetStatusColorArgb = 0xFF34D399.toInt(),
    )
    ServiceAlertCategory.INFO -> AlertVisualStyle(
        accent = AccentCyan,
        softText = Color(0xFF7DD3FC),
        onAccentInk = DarkInkOnAccent,
        bellGradientTop = Color(0xEE0E3A54),
        bellGradientBottom = Color(0xEB071F2E),
        icon = Icons.Rounded.Info,
        widgetStatusColorArgb = 0xFF38BDF8.toInt(),
    )
}
