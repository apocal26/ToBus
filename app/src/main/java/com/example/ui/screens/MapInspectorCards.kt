package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.BusVehicle
import com.example.data.model.RouteColorPalette
import com.example.data.model.StopArrival
import com.example.data.model.TransitStop
import com.example.data.model.VehicleFreshness
import com.example.data.model.VehicleHealthAssessment
import com.example.data.model.isGenericDirectionLabel
import com.example.data.model.shortTurinPlaceName
import com.example.ui.glass.AccentIconBadge
import com.example.ui.glass.DirectionColorBadge
import com.example.ui.glass.GlassCircleIconButton
import com.example.ui.glass.LightHapticKind
import com.example.ui.glass.LiquidGlassOverlaySurface
import com.example.ui.glass.RecessedMetricPill
import com.example.ui.glass.glassInteractive
import com.example.ui.glass.performLightHaptic
import com.example.ui.icons.ToBusIcons
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentEmerald
import com.example.ui.theme.AccentGold
import com.example.ui.theme.AccentRed
import com.example.ui.theme.DarkInkOnAccent
import com.example.ui.theme.GlassRowBorder
import com.example.ui.theme.GlassRowFill
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Floating Liquid Glass inspector cards shown when tapping a vehicle or stop on [MapScreen]:
 * - [SelectedBusInspectorCard]: vehicle telemetry, health warnings, delay, and source attribution.
 * - [SelectedStopArrivalsCard]: grouped stop arrivals with custom scrollbar, edge fade, and scroll chevron cue.
 */
@Composable
internal fun SelectedBusInspectorCard(
    bus: BusVehicle,
    isExternal: Boolean,
    isLightBasemap: Boolean,
    resolvedDirectionId: Int?,
    health: VehicleHealthAssessment,
    terminusName: String?,
    currentStopName: String?,
    nowMillis: Long,
    colorByDirection: Boolean,
    routeColorPalette: RouteColorPalette = RouteColorPalette.TURIN_GOLD,
    onSwitchToLine: (() -> Unit)?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val freshness = bus.freshness(nowMillis)
    val freshnessColor = when {
        health.isInDepot || health.isOffRoute -> AccentGold
        health.showBlockedWarningOnMap -> AccentRed
        freshness == VehicleFreshness.LIVE -> AccentEmerald
        freshness == VehicleFreshness.SLOW -> AccentGold
        else -> AccentGold
    }
    val warningText = remember(health) { health.warningMessage() }
    val terminusText = remember(health) { health.terminusMessage() }
    val ageSeconds = bus.gpsAgeSeconds(nowMillis)
    val isAltDir = colorByDirection && resolvedDirectionId == 1
    val badgeAccent = when {
        isExternal -> AccentEmerald
        isAltDir -> Color(routeColorPalette.secondaryArgb)
        else -> Color(routeColorPalette.primaryArgb)
    }
    val shortTerminus = remember(terminusName) { terminusName?.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName) }
    val shortCurrentStop = remember(currentStopName) {
        currentStopName?.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName)
    }

    val stationarySeconds = remember(bus.stationarySinceMillis, nowMillis) {
        val since = bus.stationarySinceMillis ?: return@remember 0L
        ((nowMillis - since) / 1000L).coerceAtLeast(0L)
    }
    val isActuallyStationary = stationarySeconds >= 25L
    val movementText = when {
        health.isInDepot -> "In deposito"
        health.isAtTerminus -> "Al capolinea"
        isActuallyStationary -> "Fermo da ${formatCompactAge(stationarySeconds)}"
        else -> "In movimento"
    }

    val delayText = remember(bus.delaySeconds) {
        val d = bus.delaySeconds ?: return@remember "Non disp."
        val mins = (abs(d) / 60)
        when {
            abs(d) < 45 -> "In orario"
            d > 0 -> "+${mins.coerceAtLeast(1)} min ritardo"
            else -> "-${mins.coerceAtLeast(1)} min anticipo"
        }
    }
    val delayColor = remember(bus.delaySeconds) {
        val d = bus.delaySeconds ?: return@remember TextSubtitle
        when {
            abs(d) < 45 -> AccentEmerald
            d >= 180 -> AccentRed
            d > 0 -> AccentGold
            else -> AccentCyan
        }
    }

    LiquidGlassOverlaySurface(
        isLightBasemap = isLightBasemap,
        accentTint = badgeAccent,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.testTag("selected_bus_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header: Line + Fleet + Direction badge + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentIconBadge(
                    icon = ToBusIcons.DirectionsBus,
                    accent = badgeAccent,
                    size = 44.dp,
                    iconSize = 22.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Vettura #${bus.label}",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                        )
                        if (onSwitchToLine != null) {
                            val switchInteraction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .glassInteractive(switchInteraction, onClick = onSwitchToLine)
                                    .background(badgeAccent.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
                                    .border(1.dp, badgeAccent.copy(alpha = 0.65f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 9.dp, vertical = 3.dp)
                                    .testTag("switch_to_external_line_button"),
                            ) {
                                Text(
                                    text = "Passa a ${bus.lineCode} ↗",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeAccent,
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .background(badgeAccent.copy(alpha = 0.2f), RoundedCornerShape(999.dp))
                                    .border(1.dp, badgeAccent.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "Linea ${bus.lineCode}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeAccent,
                                )
                            }
                        }
                    }
                    if (shortTerminus != null) {
                        DirectionColorBadge(
                            label = shortTerminus,
                            accent = badgeAccent,
                            prefix = "→",
                        )
                    }
                    if (shortCurrentStop != null) {
                        Row(
                            modifier = Modifier
                                .background(GlassRowFill, RoundedCornerShape(999.dp))
                                .border(1.dp, GlassRowBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                .testTag("bus_current_stop_pill"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Place,
                                contentDescription = null,
                                tint = badgeAccent,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = shortCurrentStop,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                GlassCircleIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    onClick = onClose,
                )
            }

            // Smart Depot / Stale / Stuck / Off-Route Alert Strip OR calm Capolinea info strip
            if (warningText != null) {
                val warnColor = if (health.showBlockedWarningOnMap) AccentRed else AccentGold
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(warnColor.copy(alpha = 0.16f), RoundedCornerShape(14.dp))
                        .border(1.dp, warnColor.copy(alpha = 0.50f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .testTag("bus_health_warning"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = ToBusIcons.WarningAmber,
                        contentDescription = null,
                        tint = warnColor,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = warningText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else if (terminusText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(badgeAccent.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                        .border(1.dp, badgeAccent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                        .testTag("bus_terminus_info"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Place,
                        contentDescription = null,
                        tint = badgeAccent,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = terminusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Compact inline Live GPS indicator: pulsing dot + status + elapsed age
            val statusShortLabel = when {
                health.isInDepot -> "Deposito"
                health.isOffRoute -> "Fuori linea"
                health.isAtTerminus -> "Capolinea"
                health.showBlockedWarningOnMap -> "Segnale bloccato"
                freshness == VehicleFreshness.LIVE -> "Live"
                freshness == VehicleFreshness.SLOW -> "Segnale lento"
                else -> "Segnale lento"
            }
            val shouldPulse = !health.isInDepot && !health.isOffRoute && !health.showBlockedWarningOnMap && freshness == VehicleFreshness.LIVE
            val pulseAlpha: Float
            val pulseScale: Float
            if (shouldPulse) {
                val pulseTransition = rememberInfiniteTransition(label = "bus_live_dot_pulse")
                val animAlpha by pulseTransition.animateFloat(
                    initialValue = 0.22f,
                    targetValue = 0.72f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1050, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "bus_live_halo_alpha",
                )
                val animScale by pulseTransition.animateFloat(
                    initialValue = 1.0f,
                    targetValue = 1.95f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1050, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "bus_live_halo_scale",
                )
                pulseAlpha = animAlpha
                pulseScale = animScale
            } else {
                pulseAlpha = 0f
                pulseScale = 1f
            }
            val ageDisplay = ageSeconds?.let { "agg. ${formatDetailedAge(it)}" } ?: "agg. N/D"

            Row(
                modifier = Modifier
                    .background(freshnessColor.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
                    .border(1.dp, freshnessColor.copy(alpha = 0.34f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier.size(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (shouldPulse) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .graphicsLayer {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                    alpha = pulseAlpha
                                }
                                .background(freshnessColor, CircleShape),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(freshnessColor, CircleShape),
                    )
                }
                Text(
                    text = statusShortLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = freshnessColor,
                )
                Text(
                    text = "·",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSubtitle,
                )
                Text(
                    text = ageDisplay,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextBody,
                    modifier = Modifier.testTag("selected_bus_age_text"),
                )
            }

            // Clean metrics: Movement & Delay
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RecessedMetricPill(
                    label = "Posizione",
                    value = movementText,
                    valueColor = if (isActuallyStationary) AccentGold else AccentEmerald,
                    modifier = Modifier.weight(1f),
                )
                RecessedMetricPill(
                    label = "Ritardo",
                    value = delayText,
                    valueColor = delayColor,
                    modifier = Modifier.weight(1f),
                )
            }

            // Discreet 1-line data sources micro-footer (compact, non-intrusive)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .testTag("bus_sources_footer"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .background(AccentCyan.copy(alpha = 0.72f), CircleShape),
                )
                Text(
                    text = "Fonti: ${bus.contributingSources.joinToString(" · ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSubtitle.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun formatCompactAge(seconds: Long): String {
    return when {
        seconds < 60L -> "${seconds}s"
        seconds < 3600L -> "${seconds / 60}m"
        else -> "${seconds / 3600}h"
    }
}

internal fun formatDetailedAge(seconds: Long): String {
    return when {
        seconds < 60L -> "${seconds}s fa"
        seconds < 3600L -> {
            val m = seconds / 60L
            val s = seconds % 60L
            "${m}m ${s}s fa"
        }
        else -> "${seconds / 3600L}h fa"
    }
}

@Composable
internal fun SelectedStopArrivalsCard(
    stop: TransitStop,
    terminusName: String?,
    selectedLine: String,
    arrivals: List<StopArrival>,
    isLoading: Boolean,
    isFavorite: Boolean,
    isLightBasemap: Boolean,
    nowMillis: Long,
    colorByDirection: Boolean,
    routeColorPalette: RouteColorPalette = RouteColorPalette.TURIN_GOLD,
    maxScrollHeight: Dp = 246.dp,
    onToggleFavorite: () -> Unit,
    onRefresh: () -> Unit,
    onArrivalTap: (StopArrival, Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAltDir = colorByDirection && stop.directionId == 1
    val badgeAccent = if (isAltDir) {
        Color(routeColorPalette.secondaryArgb)
    } else {
        Color(routeColorPalette.primaryArgb)
    }
    val clockFormat = remember { SimpleDateFormat("HH:mm", Locale.ITALY) }
    val displayStopName = remember(stop.name) { shortTurinPlaceName(stop.name) }
    val shortTerminus = remember(terminusName) { terminusName?.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName) }

    // Group arrivals cleanly by line (tracked line first, then by earliest arrival)
    val groupedLines = remember(arrivals, selectedLine) {
        arrivals
            .groupBy { it.lineCode.trim().uppercase() }
            .entries
            .map { (line, list) -> line to list.sortedBy { it.arrivalEpochSeconds }.take(4) }
            .sortedWith(
                compareByDescending<Pair<String, List<StopArrival>>> {
                    it.first.equals(selectedLine, ignoreCase = true)
                }.thenBy { it.second.firstOrNull()?.arrivalEpochSeconds ?: Long.MAX_VALUE },
            )
    }

    LiquidGlassOverlaySurface(
        isLightBasemap = isLightBasemap,
        accentTint = badgeAccent,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier.testTag("selected_stop_card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: Stop icon, clean name, colored direction pill, Star + Refresh + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentIconBadge(
                    icon = Icons.Rounded.Place,
                    accent = badgeAccent,
                    size = 42.dp,
                    iconSize = 21.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = displayStopName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "#${stop.displayCode}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSubtitle,
                        )
                        if (shortTerminus != null) {
                            DirectionColorBadge(
                                label = shortTerminus,
                                accent = badgeAccent,
                                prefix = "→",
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    GlassCircleIconButton(
                        icon = if (isFavorite) Icons.Rounded.Star else ToBusIcons.StarOutline,
                        contentDescription = stringResource(R.string.cd_favorite_stop),
                        onClick = onToggleFavorite,
                        tint = if (isFavorite) AccentGold else TextSubtitle,
                        modifier = Modifier.testTag("favorite_stop_button"),
                    )
                    GlassCircleIconButton(
                        icon = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh_map),
                        onClick = onRefresh,
                        spinning = isLoading,
                        modifier = Modifier.testTag("refresh_stop_arrivals_button"),
                    )
                    GlassCircleIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_close),
                        onClick = onClose,
                        modifier = Modifier.testTag("close_stop_card_button"),
                    )
                }
            }

            // Grouped arrivals content (one crisp card per line with time + minutes + Live/Stimato)
            when {
                isLoading && groupedLines.isEmpty() -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GlassRowFill, RoundedCornerShape(14.dp))
                            .border(1.dp, GlassRowBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = badgeAccent,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = "Cerco i pullman in arrivo…",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBody,
                        )
                    }
                }

                groupedLines.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GlassRowFill, RoundedCornerShape(14.dp))
                            .border(1.dp, GlassRowBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    ) {
                        Text(
                            text = "Nessun passaggio imminente rilevato per questa fermata.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSubtitle,
                        )
                    }
                }

                else -> {
                    val scrollState = rememberScrollState()
                    val scrollScope = rememberCoroutineScope()
                    LaunchedEffect(stop.displayCode) {
                        scrollState.scrollTo(0)
                    }
                    val isScrollable by remember(scrollState) {
                        derivedStateOf { scrollState.maxValue > 0 }
                    }
                    val canScrollDown by remember(scrollState) {
                        derivedStateOf { scrollState.canScrollForward }
                    }
                    val canScrollUp by remember(scrollState) {
                        derivedStateOf { scrollState.canScrollBackward }
                    }
                    val chevronTransition = rememberInfiniteTransition(label = "stop_scroll_chevron")
                    val chevronBobDp by chevronTransition.animateFloat(
                        initialValue = -1.2f,
                        targetValue = 1.6f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 1250, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "stop_scroll_chevron_bob",
                    )

                    Text(
                        text = "Tocca un arrivo Live · mirino = posizione GPS attiva",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSubtitle,
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxScrollHeight)
                            .drawWithContent {
                                drawContent()
                                val maxScroll = scrollState.maxValue
                                if (maxScroll > 0 && size.height > 24f) {
                                    val barWidth = 2.75.dp.toPx()
                                    val vPad = 6.dp.toPx()
                                    val trackHeight = (size.height - vPad * 2f).coerceAtLeast(16f)
                                    val totalContentHeight = size.height + maxScroll.toFloat()
                                    val thumbFraction = (size.height / totalContentHeight).coerceIn(0.18f, 0.82f)
                                    val thumbHeight = (trackHeight * thumbFraction)
                                        .coerceAtLeast(24.dp.toPx())
                                        .coerceAtMost(trackHeight)
                                    val progress = (scrollState.value.toFloat() / maxScroll.toFloat()).coerceIn(0f, 1f)
                                    val thumbY = vPad + (trackHeight - thumbHeight) * progress
                                    val barX = size.width - barWidth
                                    val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
                                    drawRoundRect(
                                        color = Color.White.copy(alpha = 0.10f),
                                        topLeft = Offset(barX, vPad),
                                        size = Size(barWidth, trackHeight),
                                        cornerRadius = radius,
                                    )
                                    drawRoundRect(
                                        color = badgeAccent.copy(alpha = 0.72f),
                                        topLeft = Offset(barX, thumbY),
                                        size = Size(barWidth, thumbHeight),
                                        cornerRadius = radius,
                                    )
                                }
                            },
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                                .drawWithContent {
                                    drawContent()
                                    val topFadePx = 18.dp.toPx().coerceAtMost(size.height * 0.25f)
                                    val bottomFadePx = 38.dp.toPx().coerceAtMost(size.height * 0.35f)
                                    if (canScrollUp && topFadePx > 0f) {
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(Color.Transparent, Color.Black),
                                                startY = 0f,
                                                endY = topFadePx,
                                            ),
                                            topLeft = Offset.Zero,
                                            size = Size(size.width, topFadePx),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                    if (canScrollDown && bottomFadePx > 0f) {
                                        drawRect(
                                            brush = Brush.verticalGradient(
                                                colors = listOf(Color.Black, Color.Transparent),
                                                startY = size.height - bottomFadePx,
                                                endY = size.height,
                                            ),
                                            topLeft = Offset(0f, size.height - bottomFadePx),
                                            size = Size(size.width, bottomFadePx),
                                            blendMode = BlendMode.DstIn,
                                        )
                                    }
                                }
                                .verticalScroll(scrollState)
                                .padding(
                                    end = if (isScrollable) 8.dp else 0.dp,
                                    bottom = if (isScrollable) 26.dp else 0.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            groupedLines.forEach { (lineCode, lineArrivals) ->
                                val isTrackedLine = lineCode.equals(selectedLine, ignoreCase = true)
                                val headsign = lineArrivals
                                    .firstOrNull { !isGenericDirectionLabel(it.headsign) }
                                    ?.headsign
                                    ?.let(::shortTurinPlaceName)
                                    ?: (if (isTrackedLine) shortTerminus else null)
                                    ?: lineArrivals
                                        .firstOrNull { it.headsign.isNotBlank() && !it.headsign.startsWith("Linea ", true) }
                                        ?.headsign
                                        ?.let(::shortTurinPlaceName)

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (isTrackedLine) Color(0x1EFFFFFF) else GlassRowFill, RoundedCornerShape(16.dp))
                                        .border(
                                            width = 1.dp,
                                            color = if (isTrackedLine) badgeAccent.copy(alpha = 0.45f) else GlassRowBorder,
                                            shape = RoundedCornerShape(16.dp),
                                        )
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    // Line header row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    color = if (isTrackedLine) badgeAccent else Color(0x28FFFFFF),
                                                    shape = RoundedCornerShape(999.dp),
                                                )
                                                .padding(horizontal = 10.dp, vertical = 3.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = lineCode,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = if (isTrackedLine) DarkInkOnAccent else TextPrimary,
                                            )
                                        }
                                        if (!headsign.isNullOrBlank()) {
                                            Text(
                                                text = "→ $headsign",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = TextBody,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }

                                    // Horizontal clickable chips: Minutes + Exact Clock + Live / Stimato -> tap to locate bus!
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        lineArrivals.forEachIndexed { indexInLine, arrival ->
                                            val mins = arrival.minutesUntil(nowMillis).coerceAtLeast(0)
                                            val countdownText = when {
                                                mins <= 0 -> "Ora"
                                                mins == 1 -> "1 min"
                                                else -> "$mins min"
                                            }
                                            val clockText = remember(arrival.arrivalEpochSeconds, clockFormat) {
                                                clockFormat.format(Date(arrival.arrivalEpochSeconds * 1000L))
                                            }
                                            val chipBg = if (arrival.isRealtime) {
                                                AccentEmerald.copy(alpha = 0.14f)
                                            } else {
                                                Color(0x14FFFFFF)
                                            }
                                            val chipBorder = if (arrival.isRealtime) {
                                                AccentEmerald.copy(alpha = 0.48f)
                                            } else {
                                                GlassRowBorder
                                            }
                                            val chipInteraction = remember { MutableInteractionSource() }

                                            Column(
                                                modifier = Modifier
                                                    .heightIn(min = 48.dp)
                                                    .glassInteractive(chipInteraction) {
                                                        onArrivalTap(arrival, indexInLine)
                                                    }
                                                    .background(chipBg, RoundedCornerShape(12.dp))
                                                    .border(1.dp, chipBorder, RoundedCornerShape(12.dp))
                                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                                                    .testTag("arrival_chip_${lineCode}_$indexInLine"),
                                                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                ) {
                                                    Text(
                                                        text = countdownText,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = if (mins <= 2) AccentEmerald else TextPrimary,
                                                    )
                                                    Text(
                                                        text = clockText,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = TextSubtitle,
                                                    )
                                                }
                                                val linkedVid = arrival.vehicleId?.trim()?.takeIf { it.isNotEmpty() }
                                                val canLocateLive = arrival.isRealtime && linkedVid != null
                                                val statusLabel = when {
                                                    arrival.isRealtime && linkedVid != null -> "Live · #$linkedVid"
                                                    arrival.isRealtime -> "Live"
                                                    else -> "Stimato"
                                                }
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(5.dp)
                                                            .background(
                                                                color = if (arrival.isRealtime) AccentEmerald else TextSubtitle,
                                                                shape = CircleShape,
                                                            ),
                                                    )
                                                    Text(
                                                        text = statusLabel,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (arrival.isRealtime) AccentEmerald else TextSubtitle,
                                                    )
                                                    if (canLocateLive) {
                                                        Icon(
                                                            imageVector = ToBusIcons.MyLocation,
                                                            contentDescription = "Mostra pullman sulla mappa",
                                                            tint = AccentEmerald.copy(alpha = 0.85f),
                                                            modifier = Modifier.size(11.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Minimal floating frosted chevron micro-pill (zero text; auto-hides at bottom)
                        androidx.compose.animation.AnimatedVisibility(
                            visible = canScrollDown,
                            enter = fadeIn(tween(180)),
                            exit = fadeOut(tween(150)),
                            modifier = Modifier.align(Alignment.BottomCenter),
                        ) {
                            val scrollMoreInteraction = remember { MutableInteractionSource() }
                            val view = LocalView.current
                            Box(
                                modifier = Modifier
                                    .size(width = 56.dp, height = 48.dp)
                                    .clickable(
                                        interactionSource = scrollMoreInteraction,
                                        indication = null,
                                    ) {
                                        performLightHaptic(view, LightHapticKind.SWITCH_STOP)
                                        scrollScope.launch {
                                            val nextTarget = (scrollState.value + 240)
                                                .coerceAtMost(scrollState.maxValue)
                                            scrollState.animateScrollTo(nextTarget)
                                        }
                                    }
                                    .testTag("stop_arrivals_scroll_more_hint"),
                                contentAlignment = Alignment.BottomCenter,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 32.dp, height = 17.dp)
                                        .background(Color(0xD8141A26), CircleShape)
                                        .border(1.dp, badgeAccent.copy(alpha = 0.45f), CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.cd_scroll_more_lines),
                                        tint = badgeAccent,
                                        modifier = Modifier
                                            .size(15.dp)
                                            .graphicsLayer {
                                                translationY = chevronBobDp.dp.toPx()
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
