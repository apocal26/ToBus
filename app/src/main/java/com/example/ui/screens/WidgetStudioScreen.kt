package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.FavoriteStop
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.MapStyle
import com.example.data.model.RefreshInterval
import com.example.data.model.StopArrival
import com.example.data.model.WidgetCameraMode
import com.example.data.model.shortTurinPlaceName
import com.example.ui.glass.AccentIconBadge
import com.example.ui.glass.DirectionColorBadge
import com.example.ui.glass.GlassBand
import com.example.ui.glass.GlassCard
import com.example.ui.glass.GlassCircleIconButton
import com.example.ui.glass.GlassModalDialog
import com.example.ui.glass.HeroEntranceText
import com.example.ui.glass.SlidingGlassSegmentedControl
import com.example.ui.glass.glassInteractive
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentEmerald
import com.example.ui.theme.AccentGold
import com.example.ui.theme.DarkInkOnAccent
import com.example.ui.theme.GlassBandBorder
import com.example.ui.theme.GlassPillBorder
import com.example.ui.theme.GlassPillFill
import com.example.ui.theme.GlassRowActiveBorder
import com.example.ui.theme.GlassRowActiveFill
import com.example.ui.theme.GlassRowBorder
import com.example.ui.theme.GlassRowFill
import com.example.ui.theme.OverlayPillBorder
import com.example.ui.theme.OverlayPillFill
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextHero
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import com.example.ui.viewmodel.ToBusUiState
import com.example.ui.viewmodel.WidgetPreviewSize
import com.example.widget.BusMapWidgetProvider
import com.example.widget.StopArrivalsWidgetProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WidgetStudioScreen(
    uiState: ToBusUiState,
    bottomNavClearance: Dp,
    leftRailClearance: Dp = 0.dp,
    onOpenLinePicker: () -> Unit,
    onSelectMapStyle: (MapStyle) -> Unit,
    onSelectCameraMode: (WidgetCameraMode) -> Unit,
    onCaptureCurrentMapArea: () -> Unit,
    onToggleVehicleLabels: (Boolean) -> Unit,
    onToggleRouteLine: (Boolean) -> Unit,
    onToggleColorByDirection: (Boolean) -> Unit,
    onToggleHideInactiveInWidget: (Boolean) -> Unit,
    onSelectRefreshInterval: (RefreshInterval) -> Unit,
    onSelectPreviewSize: (WidgetPreviewSize) -> Unit,
    onSyncWidgetNow: () -> Unit,
    onEnsurePreviewRendered: () -> Unit,
    onOpenFavoriteStop: (FavoriteStop) -> Unit,
    onRemoveFavoriteStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showManualGuideDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (uiState.widgetPreviewBitmap == null) {
            onEnsurePreviewRendered()
        }
    }

    if (showManualGuideDialog) {
        GlassModalDialog(
            onDismissRequest = { showManualGuideDialog = false },
            accentTint = AccentCyan,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccentIconBadge(
                    icon = Icons.Rounded.AddToHomeScreen,
                    accent = AccentCyan,
                )
                Text(
                    text = "Aggiungi sulla Home",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextHero,
                    modifier = Modifier.weight(1f),
                )
                GlassCircleIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    onClick = { showManualGuideDialog = false },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "1. Torna alla schermata Home del telefono o tablet.\n" +
                    "2. Tieni premuto su uno spazio vuoto dello sfondo.\n" +
                    "3. Apri \"Widget\" e cerca \"ToBus\".\n" +
                    "4. Scegli \"Mappa 2×2\", \"Mappa 4×2\" oppure \"Fermate Live\".",
                style = MaterialTheme.typography.bodyMedium,
                color = TextBody,
            )
        }
    }

    val pinSelectedWidget = {
        val ok = if (uiState.previewSize == WidgetPreviewSize.STOPS_LIVE) {
            StopArrivalsWidgetProvider.requestPinWidget(context)
        } else {
            BusMapWidgetProvider.requestPinWidget(context, square = uiState.previewSize.isSquare)
        }
        if (!ok) {
            showManualGuideDialog = true
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = leftRailClearance),
    ) {
        val isWideScreen = maxWidth >= 640.dp

        if (isWideScreen) {
            // Tablet / Landscape 2-pane Glass Studio
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.02f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StudioTopBar(
                        lineCode = uiState.settings.selectedLine,
                        onShowGuide = { showManualGuideDialog = true },
                    )
                    LiveWidgetShowcaseBand(
                        uiState = uiState,
                        onSelectPreviewSize = onSelectPreviewSize,
                        onRefreshPreview = onSyncWidgetNow,
                    )
                    PinWidgetActionButton(
                        previewSize = uiState.previewSize,
                        onPin = pinSelectedWidget,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Column(
                    modifier = Modifier
                        .weight(0.98f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    WidgetControlsColumn(
                        uiState = uiState,
                        onOpenLinePicker = onOpenLinePicker,
                        onSelectMapStyle = onSelectMapStyle,
                        onSelectCameraMode = onSelectCameraMode,
                        onCaptureCurrentMapArea = onCaptureCurrentMapArea,
                        onToggleVehicleLabels = onToggleVehicleLabels,
                        onToggleRouteLine = onToggleRouteLine,
                        onToggleColorByDirection = onToggleColorByDirection,
                        onToggleHideInactiveInWidget = onToggleHideInactiveInWidget,
                        onSelectRefreshInterval = onSelectRefreshInterval,
                        onOpenFavoriteStop = onOpenFavoriteStop,
                        onRemoveFavoriteStop = onRemoveFavoriteStop,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        } else {
            // Smartphone Compact Glass Studio
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 600.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    StudioTopBar(
                        lineCode = uiState.settings.selectedLine,
                        onShowGuide = { showManualGuideDialog = true },
                    )
                    LiveWidgetShowcaseBand(
                        uiState = uiState,
                        onSelectPreviewSize = onSelectPreviewSize,
                        onRefreshPreview = onSyncWidgetNow,
                    )
                    PinWidgetActionButton(
                        previewSize = uiState.previewSize,
                        onPin = pinSelectedWidget,
                    )
                    WidgetControlsColumn(
                        uiState = uiState,
                        onOpenLinePicker = onOpenLinePicker,
                        onSelectMapStyle = onSelectMapStyle,
                        onSelectCameraMode = onSelectCameraMode,
                        onCaptureCurrentMapArea = onCaptureCurrentMapArea,
                        onToggleVehicleLabels = onToggleVehicleLabels,
                        onToggleRouteLine = onToggleRouteLine,
                        onToggleColorByDirection = onToggleColorByDirection,
                        onToggleHideInactiveInWidget = onToggleHideInactiveInWidget,
                        onSelectRefreshInterval = onSelectRefreshInterval,
                        onOpenFavoriteStop = onOpenFavoriteStop,
                        onRemoveFavoriteStop = onRemoveFavoriteStop,
                    )
                    Spacer(modifier = Modifier.height(bottomNavClearance + 16.dp))
                }
            }
        }
    }
}

@Composable
private fun StudioTopBar(
    lineCode: String,
    onShowGuide: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            HeroEntranceText(
                text = "Impostazioni",
                key = "studio_$lineCode",
                style = MaterialTheme.typography.headlineLarge,
                color = TextHero,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Linea $lineCode · Widget Home e mappa",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSubtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        GlassCircleIconButton(
            icon = Icons.Rounded.Info,
            contentDescription = "Guida Home",
            onClick = onShowGuide,
            modifier = Modifier.testTag("widget_guide_button"),
        )
    }
}

@Composable
private fun LiveWidgetShowcaseBand(
    uiState: ToBusUiState,
    onSelectPreviewSize: (WidgetPreviewSize) -> Unit,
    onRefreshPreview: () -> Unit,
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.ITALY) }
    val timeLabel = uiState.lastUpdatedMillis?.let { timeFormatter.format(Date(it)) } ?: "--:--"
    val aspectRatio = uiState.previewSize.widthDp.toFloat() / uiState.previewSize.heightDp.toFloat()
    val isSquare = uiState.previewSize.isSquare

    val wallpaperBrush = Brush.linearGradient(
        colors = listOf(Color(0xFF0E1626), Color(0xFF080A10), Color(0xFF142133)),
    )

    GlassBand(
        modifier = Modifier.testTag("widget_live_preview_card"),
        contentPadding = 14.dp,
    ) {
        // Simulated dark home screen backdrop
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(wallpaperBrush)
                .border(1.dp, GlassBandBorder, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.previewSize == WidgetPreviewSize.STOPS_LIVE) {
                StopsLiveWidgetPreviewCard(
                    uiState = uiState,
                    timeLabel = timeLabel,
                    onRefreshPreview = onRefreshPreview,
                )
            } else {
                val displayedCount = remember(
                    uiState.lineVehicles,
                    uiState.routePatterns,
                    uiState.settings.hideInactiveInWidget,
                    uiState.nowMillis,
                ) {
                    if (uiState.settings.hideInactiveInWidget) {
                        uiState.lineVehicles.count { bus ->
                            val health = GttFleetAnalyzer.assess(bus, uiState.routePatterns, uiState.nowMillis)
                            !health.isInDepot && !health.isStaleGps
                        }
                    } else {
                        uiState.lineVehicles.size
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth(
                            when (uiState.previewSize) {
                                WidgetPreviewSize.SQUARE_2X2 -> 0.56f
                                WidgetPreviewSize.WIDE_4X2 -> 0.96f
                                WidgetPreviewSize.TABLET_5X3 -> 1.0f
                                WidgetPreviewSize.STOPS_LIVE -> 0.96f
                            },
                        )
                        .aspectRatio(aspectRatio)
                        .shadow(elevation = 16.dp, shape = RoundedCornerShape(26.dp))
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF12151C))
                        .border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(26.dp)),
                ) {
                    val bmp = uiState.widgetPreviewBitmap
                    if (bmp != null && !bmp.isRecycled) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.widget_description),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                color = AccentCyan,
                                strokeWidth = 2.dp,
                            )
                        }
                    }

                    // Top-left Smoked Glass pill (matches actual 2x2 & 4x2 RemoteViews)
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(if (isSquare) 8.dp else 10.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(OverlayPillFill)
                            .border(1.dp, OverlayPillBorder, RoundedCornerShape(999.dp))
                            .padding(
                                start = if (isSquare) 3.dp else 4.dp,
                                top = if (isSquare) 3.dp else 4.dp,
                                end = if (isSquare) 8.dp else 10.dp,
                                bottom = if (isSquare) 3.dp else 4.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(AccentGold)
                                .padding(horizontal = if (isSquare) 6.dp else 8.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = uiState.settings.selectedLine,
                                style = MaterialTheme.typography.labelSmall,
                                color = DarkInkOnAccent,
                            )
                        }
                        Spacer(modifier = Modifier.width(if (isSquare) 6.dp else 8.dp))
                        Box(
                            modifier = Modifier
                                .size(if (isSquare) 6.dp else 7.dp)
                                .clip(CircleShape)
                                .background(AccentEmerald),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = when {
                                isSquare -> "$displayedCount"
                                displayedCount == 0 -> "Nessun mezzo"
                                displayedCount == 1 -> "1 mezzo"
                                else -> "$displayedCount mezzi"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Top-right refresh circle
                    val refreshInt = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(if (isSquare) 8.dp else 10.dp)
                            .size(if (isSquare) 30.dp else 34.dp)
                            .glassInteractive(refreshInt, onClick = onRefreshPreview)
                            .clip(CircleShape)
                            .background(OverlayPillFill)
                            .border(1.dp, OverlayPillBorder, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.widget_tap_to_refresh),
                            tint = TextPrimary,
                            modifier = Modifier.size(if (isSquare) 15.dp else 17.dp),
                        )
                    }

                    // Bottom-left timestamp pill
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(if (isSquare) 8.dp else 10.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(OverlayPillFill)
                            .border(1.dp, OverlayPillBorder, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSubtitle,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Spring Sliding Glass Segmented Control for 2x2 / 4x2 / 5x3 / Fermate preview
        val presets = WidgetPreviewSize.entries
        SlidingGlassSegmentedControl(
            items = presets,
            selectedIndex = presets.indexOf(uiState.previewSize),
            labelProvider = { it.labelIt },
            onSelect = { _, item -> onSelectPreviewSize(item) },
            tagPrefix = "preview_size",
        )
    }
}

@Composable
private fun StopsLiveWidgetPreviewCard(
    uiState: ToBusUiState,
    timeLabel: String,
    onRefreshPreview: () -> Unit,
) {
    val visibleFavorites = uiState.favoriteStops.take(3)
    val refreshInt = remember { MutableInteractionSource() }
    val clockFormat = remember { SimpleDateFormat("HH:mm", Locale.ITALY) }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.98f)
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(26.dp))
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xF011131B))
            .border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(26.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        // Header matching widget_stop_arrivals.xml
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                tint = AccentGold,
                modifier = Modifier.size(15.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (visibleFavorites.size == 1) {
                    shortTurinPlaceName(visibleFavorites.first().name)
                } else {
                    "Fermate Live"
                },
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtitle,
            )
            if (uiState.favoriteStops.size > 3) {
                val totalPages = ((uiState.favoriteStops.size - 1) / 3) + 1
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(OverlayPillFill)
                        .border(1.dp, OverlayPillBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "1/$totalPages ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextBody,
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .glassInteractive(refreshInt, onClick = onRefreshPreview)
                    .clip(CircleShape)
                    .background(OverlayPillFill)
                    .border(1.dp, OverlayPillBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.widget_tap_to_refresh),
                    tint = TextPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        if (visibleFavorites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlassRowFill)
                    .border(1.dp, GlassRowBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = "Tocca una o più fermate nella Mappa e premi ★ per vederle tutte qui insieme in tempo reale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtitle,
                )
            }
        } else if (uiState.isLoadingStopWidgetPreview && uiState.stopWidgetPreviewByStopId.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(GlassRowFill)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = AccentGold,
                    strokeWidth = 2.dp,
                )
                Text(
                    text = "Carico i passaggi delle tue fermate…",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextBody,
                )
            }
        } else if (visibleFavorites.size == 1) {
            // Single favorite -> grouped by line (up to 3 lines)
            val only = visibleFavorites.first()
            val allArrivals = uiState.stopWidgetPreviewByStopId[only.stopId].orEmpty()
            val grouped = allArrivals
                .groupBy { it.lineCode.uppercase() }
                .entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, List<StopArrival>>> {
                        it.key.equals(only.lineCode, ignoreCase = true)
                    }.thenBy { it.value.minOfOrNull { a -> a.arrivalEpochSeconds } ?: Long.MAX_VALUE },
                )
                .take(3)

            if (grouped.isEmpty()) {
                PreviewStopCardRow(
                    lineCode = only.lineCode,
                    badgeColor = if (uiState.settings.colorByDirection && only.directionId == 1) AccentCyan else AccentGold,
                    title = shortTurinPlaceName(only.name),
                    dirText = only.terminusName?.let { "→ ${shortTurinPlaceName(it)}" }.orEmpty(),
                    arrivals = emptyList(),
                    nowMillis = uiState.nowMillis,
                    clockFormat = clockFormat,
                )
            } else {
                grouped.forEach { (line, list) ->
                    val isSavedLine = line.equals(only.lineCode, ignoreCase = true)
                    val headsign = list.firstOrNull()?.headsign?.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName)
                        ?: only.terminusName?.let(::shortTurinPlaceName)
                        ?: "Linea $line"
                    PreviewStopCardRow(
                        lineCode = line,
                        badgeColor = if (isSavedLine && uiState.settings.colorByDirection && only.directionId == 1) AccentCyan else AccentGold,
                        title = headsign,
                        dirText = "",
                        arrivals = list.sortedBy { it.arrivalEpochSeconds }.take(3),
                        nowMillis = uiState.nowMillis,
                        clockFormat = clockFormat,
                    )
                }
            }
        } else {
            // Multiple favorites -> display 2 or 3 favorite stops simultaneously!
            visibleFavorites.forEach { fav ->
                val allForStop = uiState.stopWidgetPreviewByStopId[fav.stopId].orEmpty()
                val preferred = allForStop.filter { it.lineCode.equals(fav.lineCode, ignoreCase = true) }
                val chosen = (preferred.ifEmpty { allForStop })
                    .sortedBy { it.arrivalEpochSeconds }
                    .take(3)
                val badgeColor = if (uiState.settings.colorByDirection && fav.directionId == 1) AccentCyan else AccentGold
                val dirText = fav.terminusName?.takeIf { it.isNotBlank() }?.let { "→ ${shortTurinPlaceName(it)}" }.orEmpty()
                PreviewStopCardRow(
                    lineCode = chosen.firstOrNull()?.lineCode ?: fav.lineCode,
                    badgeColor = badgeColor,
                    title = shortTurinPlaceName(fav.name),
                    dirText = dirText,
                    arrivals = chosen,
                    nowMillis = uiState.nowMillis,
                    clockFormat = clockFormat,
                )
            }
        }
    }
}

@Composable
private fun PreviewStopCardRow(
    lineCode: String,
    badgeColor: Color,
    title: String,
    dirText: String,
    arrivals: List<StopArrival>,
    nowMillis: Long,
    clockFormat: SimpleDateFormat,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(GlassRowFill)
            .border(1.dp, GlassRowBorder, RoundedCornerShape(13.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(badgeColor)
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            ) {
                Text(
                    text = lineCode,
                    style = MaterialTheme.typography.labelSmall,
                    color = DarkInkOnAccent,
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (dirText.isNotBlank()) {
                Text(
                    text = dirText,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSubtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (arrivals.isEmpty()) {
            Text(
                text = "Nessun passaggio ora",
                style = MaterialTheme.typography.labelSmall,
                color = TextSubtitle,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                arrivals.forEachIndexed { idx, arr ->
                    val mins = arr.minutesUntil(nowMillis).coerceAtLeast(0)
                    val clock = clockFormat.format(Date(arr.arrivalEpochSeconds * 1000L))
                    val prefix = if (arr.isRealtime) "● " else "○ "
                    val text = when (idx) {
                        0 -> "$prefix${if (mins <= 0) "Ora" else "$mins min"} · $clock"
                        1 -> "$prefix${if (mins <= 0) "Ora" else "${mins}'"} · $clock"
                        else -> "$prefix${if (mins <= 0) "Ora" else "${mins}'"}"
                    }
                    val highlight = arr.isRealtime && idx == 0
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (highlight) AccentEmerald.copy(alpha = 0.16f) else Color(0x18FFFFFF))
                            .border(
                                1.dp,
                                if (highlight) AccentEmerald.copy(alpha = 0.45f) else GlassRowBorder,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (highlight) AccentEmerald else if (arr.isRealtime) TextPrimary else TextSubtitle,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PinWidgetActionButton(
    previewSize: WidgetPreviewSize,
    onPin: () -> Unit,
) {
    val isSquare = previewSize.isSquare
    val isStops = previewSize == WidgetPreviewSize.STOPS_LIVE
    val label = when (previewSize) {
        WidgetPreviewSize.SQUARE_2X2 -> "Aggiungi widget 2×2 alla Home"
        WidgetPreviewSize.WIDE_4X2 -> "Aggiungi widget 4×2 alla Home"
        WidgetPreviewSize.TABLET_5X3 -> "Aggiungi widget 5×3 alla Home"
        WidgetPreviewSize.STOPS_LIVE -> "Aggiungi widget Fermate alla Home"
    }
    val interaction = remember { MutableInteractionSource() }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .glassInteractive(interaction, onClick = onPin)
            .testTag("pin_widget_button"),
        shape = RoundedCornerShape(999.dp),
        color = if (isStops) AccentGold else AccentCyan,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = when {
                    isStops -> Icons.Rounded.Star
                    isSquare -> Icons.Rounded.GridView
                    else -> Icons.Rounded.Widgets
                },
                contentDescription = stringResource(R.string.cd_pin_widget),
                tint = DarkInkOnAccent,
                modifier = Modifier.size(19.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = DarkInkOnAccent,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun WidgetControlsColumn(
    uiState: ToBusUiState,
    onOpenLinePicker: () -> Unit,
    onSelectMapStyle: (MapStyle) -> Unit,
    onSelectCameraMode: (WidgetCameraMode) -> Unit,
    onCaptureCurrentMapArea: () -> Unit,
    onToggleVehicleLabels: (Boolean) -> Unit,
    onToggleRouteLine: (Boolean) -> Unit,
    onToggleColorByDirection: (Boolean) -> Unit,
    onToggleHideInactiveInWidget: (Boolean) -> Unit,
    onSelectRefreshInterval: (RefreshInterval) -> Unit,
    onOpenFavoriteStop: (FavoriteStop) -> Unit,
    onRemoveFavoriteStop: (String) -> Unit,
) {
    val settings = uiState.settings

    // 1. Tracked Line Card (Rich Dark Card #1C1C22 inside Glass Band)
    GlassBand {
        Text(
            text = "Linea monitorata",
            style = MaterialTheme.typography.titleMedium,
            color = TextHero,
        )
        Spacer(modifier = Modifier.height(12.dp))
        val lineInt = remember { MutableInteractionSource() }
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .glassInteractive(lineInt, onClick = onOpenLinePicker)
                .testTag("studio_change_line_row"),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentIconBadge(
                    icon = Icons.Rounded.DirectionsBus,
                    accent = AccentGold,
                    size = 44.dp,
                    iconSize = 22.dp,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Linea ${settings.selectedLine}",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                    )
                    val activeReliableCount = remember(uiState.lineVehicles, uiState.routePatterns, uiState.nowMillis) {
                        uiState.lineVehicles.count { bus ->
                            GttFleetAnalyzer.assess(bus, uiState.routePatterns, uiState.nowMillis).isReliableLive
                        }
                    }
                    Text(
                        text = when {
                            uiState.lineVehicles.isEmpty() -> "Nessun mezzo attivo ora"
                            activeReliableCount == 0 -> "${uiState.lineVehicles.size} in deposito o fermi · ${uiState.onlineSourcesCount} fonti"
                            activeReliableCount == 1 -> "1 pullman attivo · ${uiState.onlineSourcesCount} fonti"
                            else -> "$activeReliableCount pullman attivi · ${uiState.onlineSourcesCount} fonti"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtitle,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(GlassPillFill)
                        .border(1.dp, GlassPillBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Cambia",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                    )
                }
            }
        }
    }

    // 1b. Favorite Stops Band (feeds both the map quick bar and the Stops Live widget)
    GlassBand {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Fermate preferite",
                style = MaterialTheme.typography.titleMedium,
                color = TextHero,
            )
            if (uiState.favoriteStops.isNotEmpty()) {
                Text(
                    text = "${uiState.favoriteStops.size}/6",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentGold,
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        if (uiState.favoriteStops.isEmpty()) {
            Text(
                text = "Tocca una fermata sulla mappa e premi ★ per salvarla e usarla nel widget Fermate Live.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtitle,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.favoriteStops.forEach { fav ->
                    val openInt = remember { MutableInteractionSource() }
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassInteractive(openInt, enableHoverScale = false) { onOpenFavoriteStop(fav) }
                            .clip(shape)
                            .background(GlassRowFill)
                            .border(1.dp, GlassRowBorder, shape)
                            .padding(start = 10.dp, top = 8.dp, end = 6.dp, bottom = 8.dp)
                            .testTag("studio_fav_stop_${fav.stopId}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AccentIconBadge(
                            icon = Icons.Rounded.Place,
                            accent = AccentGold,
                            size = 34.dp,
                            iconSize = 17.dp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = shortTurinPlaceName(fav.name),
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val favAccent = if (settings.colorByDirection && fav.directionId == 1) AccentCyan else AccentGold
                            val favTerminus = fav.terminusName?.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = "Linea ${fav.lineCode}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSubtitle,
                                )
                                if (favTerminus != null) {
                                    DirectionColorBadge(
                                        label = favTerminus,
                                        accent = favAccent,
                                        prefix = "→",
                                    )
                                }
                            }
                        }
                        GlassCircleIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = "Rimuovi fermata preferita",
                            onClick = { onRemoveFavoriteStop(fav.stopId) },
                            modifier = Modifier.testTag("remove_fav_stop_${fav.stopId}"),
                        )
                    }
                }
            }
        }
    }

    // 2. Map Style Selector (Sliding Glass Pill)
    GlassBand {
        Text(
            text = "Stile mappa",
            style = MaterialTheme.typography.titleMedium,
            color = TextHero,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Mappa pulita senza nomi invadenti sul widget, dettagliata nell'app.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSubtitle,
        )
        Spacer(modifier = Modifier.height(12.dp))
        val styles = MapStyle.entries
        SlidingGlassSegmentedControl(
            items = styles,
            selectedIndex = styles.indexOf(settings.mapStyle),
            labelProvider = { it.labelIt },
            onSelect = { _, item -> onSelectMapStyle(item) },
            tagPrefix = "map_style",
        )
    }

    // 3. Framing Mode
    GlassBand {
        Text(
            text = "Inquadratura widget",
            style = MaterialTheme.typography.titleMedium,
            color = TextHero,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetCameraMode.entries.forEach { mode ->
                val isSelected = settings.cameraMode == mode
                val interaction = remember { MutableInteractionSource() }
                val shape = RoundedCornerShape(14.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassInteractive(interaction) { onSelectCameraMode(mode) }
                        .clip(shape)
                        .background(if (isSelected) GlassRowActiveFill else GlassRowFill)
                        .border(1.dp, if (isSelected) GlassRowActiveBorder else GlassRowBorder, shape)
                        .padding(12.dp)
                        .testTag("camera_mode_${mode.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccentIconBadge(
                        icon = if (mode == WidgetCameraMode.AUTO_FIT_BUSES) {
                            Icons.Rounded.AutoAwesome
                        } else {
                            Icons.Rounded.MyLocation
                        },
                        accent = if (isSelected) AccentCyan else TextSubtitle,
                        size = 38.dp,
                        iconSize = 18.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.labelIt,
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                        )
                        Text(
                            text = mode.descriptionIt,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSubtitle,
                        )
                    }
                }
            }

            if (settings.cameraMode == WidgetCameraMode.FIXED_AREA) {
                val capInt = remember { MutableInteractionSource() }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .glassInteractive(capInt, onClick = onCaptureCurrentMapArea)
                        .testTag("capture_map_area_button"),
                    shape = RoundedCornerShape(999.dp),
                    color = GlassPillFill,
                    border = BorderStroke(1.dp, GlassPillBorder),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CropFree,
                            contentDescription = null,
                            tint = AccentGold,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Usa inquadratura attuale della Mappa",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary,
                        )
                    }
                }
            }
        }
    }

    // 4. Visual Details
    GlassBand {
        Text(
            text = "Dettagli grafici",
            style = MaterialTheme.typography.titleMedium,
            color = TextHero,
        )
        Spacer(modifier = Modifier.height(8.dp))
        GlassToggleRow(
            title = "Numero vettura sui pullman",
            subtitle = "Solo sulla mappa interattiva (widget sempre pulito)",
            checked = settings.showVehicleLabels,
            onCheckedChange = onToggleVehicleLabels,
            testTag = "toggle_vehicle_labels",
        )
        HorizontalDivider(color = GlassBandBorder)
        GlassToggleRow(
            title = "Nascondi mezzi in deposito o fermi",
            subtitle = "Esclude dal widget bus in deposito o con GPS vecchio",
            checked = settings.hideInactiveInWidget,
            onCheckedChange = onToggleHideInactiveInWidget,
            testTag = "toggle_hide_inactive_widget",
        )
        HorizontalDivider(color = GlassBandBorder)
        GlassToggleRow(
            title = "Percorso linea",
            subtitle = "Mostra il tracciato GTT sotto ai mezzi",
            checked = settings.showRouteLine,
            onCheckedChange = onToggleRouteLine,
            testTag = "toggle_route_line",
        )
        HorizontalDivider(color = GlassBandBorder)
        val dir0Short = uiState.terminusForDirection(0)?.let(::shortTurinPlaceName)
        val dir1Short = uiState.terminusForDirection(1)?.let(::shortTurinPlaceName)
        DirectionColorsToggleRow(
            dir0Name = dir0Short,
            dir1Name = dir1Short,
            checked = settings.colorByDirection,
            onCheckedChange = onToggleColorByDirection,
        )
    }

    // 5. Battery & Auto-Refresh Cadence
    GlassBand {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccentIconBadge(
                icon = Icons.Rounded.BatterySaver,
                accent = AccentEmerald,
                size = 40.dp,
                iconSize = 20.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Aggiornamento & Batteria",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextHero,
                )
                Text(
                    text = "Scegli ogni quanto aggiornare il widget sulla Home.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtitle,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        val intervals = RefreshInterval.entries
        SlidingGlassSegmentedControl(
            items = intervals,
            selectedIndex = intervals.indexOf(settings.refreshInterval),
            labelProvider = { it.labelIt },
            onSelect = { _, item -> onSelectRefreshInterval(item) },
            tagPrefix = "refresh_interval",
        )
    }
}

@Composable
private fun GlassToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassInteractive(interaction, enableHoverScale = false) { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtitle,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = DarkInkOnAccent,
                checkedTrackColor = AccentCyan,
                uncheckedThumbColor = TextSubtitle,
                uncheckedTrackColor = GlassRowFill,
                uncheckedBorderColor = GlassRowBorder,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectionColorsToggleRow(
    dir0Name: String?,
    dir1Name: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassInteractive(interaction, enableHoverScale = false) { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Colori per direzione",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            if (dir0Name != null && dir1Name != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DirectionColorBadge(
                        label = dir0Name,
                        accent = AccentGold,
                    )
                    DirectionColorBadge(
                        label = dir1Name,
                        accent = AccentCyan,
                    )
                }
            } else {
                Text(
                    text = "Colori distinti per i due capolinea",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtitle,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("toggle_color_direction"),
            colors = SwitchDefaults.colors(
                checkedThumbColor = DarkInkOnAccent,
                checkedTrackColor = AccentCyan,
                uncheckedThumbColor = TextSubtitle,
                uncheckedTrackColor = GlassRowFill,
                uncheckedBorderColor = GlassRowBorder,
            ),
        )
    }
}

