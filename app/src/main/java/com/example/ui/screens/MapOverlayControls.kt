package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.model.BusVehicle
import com.example.data.model.ServiceAlertCategory
import com.example.data.model.VehicleFreshness
import com.example.data.model.VehicleHealthAssessment
import com.example.ui.glass.GlassProgressBar
import com.example.ui.glass.GlassShimmerBox
import com.example.ui.glass.LiquidGlassOverlaySurface
import com.example.ui.glass.glassInteractive
import com.example.ui.glass.liquidGlassRimBrush
import com.example.ui.glass.rememberGlassShimmerBrush
import com.example.ui.icons.ToBusIcons
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentEmerald
import com.example.ui.theme.AccentGold
import com.example.ui.theme.AccentRed
import com.example.ui.theme.DarkInkOnAccent
import com.example.ui.theme.OverlayBusPillLightBottom
import com.example.ui.theme.OverlayBusPillLightTop
import com.example.ui.theme.OverlayPillBorder
import com.example.ui.theme.OverlayPillFill
import com.example.ui.theme.OverlayPillLightMapFill
import com.example.ui.theme.TextHero
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import com.example.ui.theme.visualStyle
import kotlinx.coroutines.flow.StateFlow

/**
 * Floating Liquid Glass controls overlaid on [MapScreen]:
 * - [StartupGlassLoadingOverlay] shown while cold-boot route and live feeds hydrate.
 * - [ActiveBusDirectionGlassPill] bottom fleet chips colored by direction and health.
 * - [GttAlertsGlassButton], [UserLocationGlassButton], and [RefreshProgressGlassButton] top-right actions.
 */
@Composable
internal fun StartupGlassLoadingOverlay(
    selectedLine: String,
    isRouteReady: Boolean,
    isFeedReady: Boolean,
) {
    val shimmerBrush = rememberGlassShimmerBrush()
    val backdropBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0x9C070A12),
                Color(0xB405070D),
            ),
        )
    }
    val stepProgressTarget = when {
        isRouteReady && isFeedReady -> 1f
        isRouteReady || isFeedReady -> 0.76f
        else -> 0.40f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = stepProgressTarget,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "startup_glass_progress",
    )
    val cardShape = remember { RoundedCornerShape(28.dp) }
    val statusText = when {
        !isRouteReady && !isFeedReady -> "Caricamento percorso e fonti live…"
        !isRouteReady -> "Caricamento percorso e fermate…"
        else -> "Sincronizzazione mezzi live…"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backdropBrush)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 24.dp)
            .testTag("startup_loading_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        LiquidGlassOverlaySurface(
            isLightBasemap = false,
            accentTint = AccentGold,
            shape = cardShape,
            modifier = Modifier
                .widthIn(max = 316.dp)
                .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(shimmerBrush),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (selectedLine.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .background(AccentGold, RoundedCornerShape(999.dp))
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = selectedLine,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = DarkInkOnAccent,
                                )
                            }
                        }
                        Text(
                            text = "Preparazione mappa",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextHero,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Widget-style frosted glass shimmer preview block
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x14FFFFFF), RoundedCornerShape(18.dp))
                            .border(1.dp, Color(0x1EFFFFFF), RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlassShimmerBox(
                                modifier = Modifier
                                    .width(120.dp)
                                    .height(12.dp),
                                shape = RoundedCornerShape(999.dp),
                                brush = shimmerBrush,
                            )
                            GlassShimmerBox(
                                modifier = Modifier
                                    .width(42.dp)
                                    .height(12.dp),
                                shape = RoundedCornerShape(999.dp),
                                brush = shimmerBrush,
                            )
                        }
                        GlassShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth(0.76f)
                                .height(10.dp),
                            shape = RoundedCornerShape(999.dp),
                            brush = shimmerBrush,
                        )
                    }

                    GlassProgressBar(progress = animatedProgress)

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StartupStepChip(
                            label = "Percorso",
                            done = isRouteReady,
                            modifier = Modifier.weight(1f),
                        )
                        StartupStepChip(
                            label = "Fonti live",
                            done = isFeedReady,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StartupStepChip(
    label: String,
    done: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = if (done) AccentEmerald else AccentGold
    Row(
        modifier = modifier
            .background(
                color = if (done) AccentEmerald.copy(alpha = 0.14f) else Color(0x14FFFFFF),
                shape = RoundedCornerShape(999.dp),
            )
            .border(
                width = 1.dp,
                color = if (done) AccentEmerald.copy(alpha = 0.45f) else Color(0x22FFFFFF),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (done) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = AccentEmerald,
                modifier = Modifier.size(14.dp),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.8.dp,
                color = accent,
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (done) TextPrimary else TextSubtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ActiveBusDirectionGlassPill(
    bus: BusVehicle,
    health: VehicleHealthAssessment,
    nowMillis: Long,
    directionAccent: Color?,
    isSelected: Boolean,
    isLightBasemap: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val freshness = bus.freshness(nowMillis)
    val indicatorColor = when {
        health.isInDepot -> Color(0xFFF59E0B)
        health.isOffRoute -> Color(0xFFF59E0B)
        health.isStaleGps -> AccentRed
        health.isStuck -> AccentGold
        health.isAtTerminus -> directionAccent ?: AccentCyan
        freshness == VehicleFreshness.LIVE -> AccentEmerald
        freshness == VehicleFreshness.SLOW -> AccentGold
        else -> AccentRed
    }
    val statusTag = when {
        health.isInDepot -> "Deposito"
        health.isOffRoute -> "Fuori linea"
        health.isStaleGps -> bus.gpsAgeSeconds(nowMillis)?.let {
            "agg. ${formatCompactAge(it)} fa"
        } ?: "GPS vecchio"
        health.isStuck -> health.stationarySeconds?.let {
            "fermo ${formatCompactAge(it)}"
        } ?: "fermo"
        health.isAtTerminus -> "Capolinea"
        else -> bus.gpsAgeSeconds(nowMillis)?.let {
            "agg. ${formatCompactAge(it)} fa"
        } ?: "agg. N/D"
    }

    val isDimmed = health.isInDepot || health.isStuck || health.isStaleGps || health.isOffRoute
    val pillShape = RoundedCornerShape(999.dp)
    val interaction = remember { MutableInteractionSource() }

    val smokedBaseBrush = remember(isSelected, isLightBasemap) {
        when {
            isSelected && isLightBasemap -> Brush.verticalGradient(
                colors = listOf(Color(0xE0212A3B), Color(0xD8151C29)),
            )
            isSelected -> Brush.verticalGradient(
                colors = listOf(Color(0xEB1D2230), Color(0xE811141E)),
            )
            isLightBasemap -> Brush.verticalGradient(
                colors = listOf(OverlayBusPillLightTop, OverlayBusPillLightBottom),
            )
            else -> Brush.verticalGradient(
                colors = listOf(Color(0xDD171B26), Color(0xD80E1119)),
            )
        }
    }

    val glassTintBrush = remember(directionAccent, isSelected, isDimmed, isLightBasemap) {
        if (directionAccent == null) {
            Brush.verticalGradient(
                colors = listOf(
                    Color(if (isSelected || isLightBasemap) 0x1EFFFFFF else 0x12FFFFFF),
                    Color.Transparent,
                ),
            )
        } else {
            val topAlpha = when {
                isSelected && !isDimmed -> 0.28f
                isSelected -> 0.18f
                !isDimmed -> if (isLightBasemap) 0.20f else 0.16f
                else -> 0.08f
            }
            val endAlpha = when {
                isSelected && !isDimmed -> 0.06f
                else -> 0.01f
            }
            Brush.verticalGradient(
                colors = listOf(
                    directionAccent.copy(alpha = topAlpha),
                    directionAccent.copy(alpha = endAlpha),
                ),
            )
        }
    }

    val borderBrush = remember(directionAccent, isSelected, isDimmed, isLightBasemap) {
        when {
            directionAccent == null -> liquidGlassRimBrush(
                topAlpha = if (isSelected) 0.72f else if (isLightBasemap) 0.56f else 0.46f,
                midAlpha = 0.16f,
                bottomAlpha = 0.26f,
            )
            isSelected -> Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.85f),
                    directionAccent.copy(alpha = 0.82f),
                ),
            )
            else -> Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (isDimmed) 0.28f else if (isLightBasemap) 0.58f else 0.46f),
                    directionAccent.copy(alpha = if (isDimmed) 0.18f else 0.44f),
                ),
            )
        }
    }

    Box(
        modifier = modifier
            .graphicsLayer { alpha = if (isDimmed && !isSelected) 0.68f else 1f }
            .glassInteractive(interaction, onClick = onClick)
            .background(smokedBaseBrush, pillShape)
            .background(glassTintBrush, pillShape)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                brush = borderBrush,
                shape = pillShape,
            )
            .testTag("bus_chip_${bus.vehicleId}"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(directionAccent ?: indicatorColor, CircleShape),
            )
            Text(
                text = "#${bus.label}",
                style = MaterialTheme.typography.labelLarge,
                color = if (health.isInDepot) TextSubtitle else TextPrimary,
            )
            Text(
                text = "· $statusTag",
                style = MaterialTheme.typography.labelSmall,
                color = indicatorColor,
            )
        }
    }
}

@Composable
internal fun GttAlertsGlassButton(
    lineAlertCategory: ServiceAlertCategory?,
    hasGeneralAlerts: Boolean,
    badgeCount: Int,
    isLightBasemap: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasLineAlert = lineAlertCategory != null
    val lineStyle = remember(lineAlertCategory) { lineAlertCategory?.visualStyle() }
    val interaction = remember { MutableInteractionSource() }
    val bgBrush = remember(lineStyle, isLightBasemap) {
        val neutralFill = if (isLightBasemap) OverlayPillLightMapFill else OverlayPillFill
        if (lineStyle != null) {
            Brush.verticalGradient(listOf(lineStyle.bellGradientTop, lineStyle.bellGradientBottom))
        } else {
            // Keep general (non-current-line) alerts calm smoked glass so active-line alerts stand out unmistakably.
            Brush.verticalGradient(listOf(neutralFill, neutralFill))
        }
    }
    val borderBrush = remember(lineStyle, hasGeneralAlerts) {
        when {
            lineStyle != null -> Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), lineStyle.accent))
            hasGeneralAlerts -> liquidGlassRimBrush(topAlpha = 0.56f, midAlpha = 0.22f, bottomAlpha = 0.36f)
            else -> liquidGlassRimBrush(topAlpha = 0.46f, midAlpha = 0.15f, bottomAlpha = 0.26f)
        }
    }
    val iconTint = when {
        lineStyle != null -> lineStyle.softText
        hasGeneralAlerts -> TextPrimary.copy(alpha = 0.90f)
        else -> TextSubtitle
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .glassInteractive(interaction, onClick = onClick)
            .testTag("gtt_alerts_button"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(bgBrush, CircleShape)
                .border(
                    width = if (hasLineAlert) 1.5.dp else 1.dp,
                    brush = borderBrush,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (hasLineAlert || hasGeneralAlerts) {
                    ToBusIcons.NotificationsActive
                } else {
                    ToBusIcons.NotificationsNone
                },
                contentDescription = stringResource(R.string.cd_gtt_alerts),
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }

        if (hasLineAlert || hasGeneralAlerts) {
            val dotColor = lineStyle?.accent ?: Color(0xFF334155)
            val badgeTextColor = lineStyle?.onAccentInk ?: Color(0xFFE2E8F0)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .background(dotColor, CircleShape)
                    .border(
                        width = 1.dp,
                        color = if (hasLineAlert) Color(0xFF0A0A0E) else Color(0x66FFFFFF),
                        shape = CircleShape,
                    )
                    .padding(horizontal = if (badgeCount > 9) 4.dp else 5.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (badgeCount > 9) "9+" else "$badgeCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeTextColor,
                )
            }
        }
    }
}

@Composable
internal fun UserLocationGlassButton(
    isEnabled: Boolean,
    isLocating: Boolean,
    isLightBasemap: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val borderColor by animateColorAsState(
        targetValue = if (isEnabled) AccentCyan.copy(alpha = 0.85f) else OverlayPillBorder,
        animationSpec = tween(durationMillis = 240),
        label = "gps_border_color",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isEnabled) AccentCyan else TextPrimary.copy(alpha = 0.88f),
        animationSpec = tween(durationMillis = 240),
        label = "gps_icon_tint",
    )
    val fillBrush = remember(isEnabled, isLightBasemap) {
        val neutralFill = if (isLightBasemap) OverlayPillLightMapFill else OverlayPillFill
        if (isEnabled) {
            Brush.verticalGradient(listOf(Color(0xEE103044), Color(0xEB091C2A)))
        } else {
            Brush.verticalGradient(listOf(neutralFill, neutralFill))
        }
    }
    val inactiveRimBrush = remember {
        liquidGlassRimBrush(topAlpha = 0.46f, midAlpha = 0.15f, bottomAlpha = 0.26f)
    }

    val locatingAlpha = if (isLocating) {
        val transition = rememberInfiniteTransition(label = "gps_locating_pulse")
        val a by transition.animateFloat(
            initialValue = 0.42f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "gps_locating_alpha",
        )
        a
    } else {
        1f
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .glassInteractive(interaction, onClick = onClick)
            .testTag("user_location_button"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(fillBrush, CircleShape)
                .border(
                    width = if (isEnabled) 1.4.dp else 1.dp,
                    brush = if (isEnabled) {
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), borderColor))
                    } else {
                        inactiveRimBrush
                    },
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = ToBusIcons.MyLocation,
                contentDescription = stringResource(R.string.cd_user_location),
                tint = iconTint,
                modifier = Modifier
                    .size(19.dp)
                    .graphicsLayer { alpha = locatingAlpha },
            )
        }
    }
}

@Composable
internal fun RefreshProgressGlassButton(
    pollProgressFlow: StateFlow<Float>,
    isRefreshing: Boolean,
    isLightBasemap: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by pollProgressFlow.collectAsStateWithLifecycle()
    val interaction = remember { MutableInteractionSource() }
    val clampedProgress = progress.coerceIn(0f, 1f)
    val fillColor = if (isLightBasemap) OverlayPillLightMapFill else OverlayPillFill
    val smoothProgress = remember { Animatable(clampedProgress) }

    LaunchedEffect(clampedProgress) {
        if (clampedProgress < smoothProgress.value - 0.12f) {
            smoothProgress.snapTo(clampedProgress)
        } else {
            smoothProgress.animateTo(
                targetValue = clampedProgress,
                animationSpec = tween(durationMillis = 960, easing = LinearEasing),
            )
        }
    }

    val rotation = if (isRefreshing) {
        val transition = rememberInfiniteTransition(label = "refresh_icon_spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "refresh_spin_angle",
        )
        angle
    } else {
        0f
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .glassInteractive(interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val topSpecularBrush = remember {
            Brush.verticalGradient(
                listOf(
                    Color(0x16FFFFFF),
                    Color.Transparent,
                ),
            )
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(fillColor, CircleShape)
                .background(topSpecularBrush, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = 2.dp.toPx()
                val inset = strokePx / 2f
                val arcSize = Size(
                    width = size.width - strokePx,
                    height = size.height - strokePx,
                )
                // Subtle glass outer rim track
                drawArc(
                    color = Color.White.copy(alpha = 0.16f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = 1.dp.toPx()),
                )
                if (isRefreshing) {
                    // Fluid rotating cyan/specular arc while live feeds are fetching
                    drawArc(
                        color = AccentCyan.copy(alpha = 0.28f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx),
                    )
                    drawArc(
                        color = AccentCyan,
                        startAngle = -90f + rotation,
                        sweepAngle = 135f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = Color.White.copy(alpha = 0.90f),
                        startAngle = -90f + rotation + 92f,
                        sweepAngle = 42f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                } else {
                    // Silky 60fps interpolated countdown sweep along the circular button rim
                    val sweep = smoothProgress.value * 360f
                    if (sweep > 0.5f) {
                        drawArc(
                            color = Color.White.copy(alpha = 0.86f),
                            startAngle = -90f,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round),
                        )
                    }
                }
            }
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = stringResource(R.string.cd_refresh_map),
                tint = if (isRefreshing) AccentCyan else TextPrimary,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
    }
}
