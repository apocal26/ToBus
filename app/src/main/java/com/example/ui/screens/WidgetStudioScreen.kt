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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import com.example.ui.icons.ToBusIcons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.example.BuildConfig
import com.example.R
import com.example.data.model.FavoriteStop
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.MapStyle
import com.example.data.model.RefreshInterval
import com.example.data.model.StopArrival
import com.example.data.model.WidgetCameraMode
import com.example.data.model.shortTurinPlaceName
import com.example.ui.components.ConfigureGithubRepoDialog
import com.example.ui.glass.AccentIconBadge
import com.example.ui.glass.DirectionColorBadge
import com.example.ui.glass.GlassBand
import com.example.ui.glass.GlassCard
import com.example.ui.glass.GlassCircleIconButton
import com.example.ui.glass.GlassModalDialog
import com.example.ui.glass.GlassShimmerBox
import com.example.ui.glass.HeroEntranceText
import com.example.ui.glass.LiquidGlassSwitch
import com.example.ui.glass.SlidingGlassSegmentedControl
import com.example.ui.glass.glassInteractive
import com.example.ui.glass.rememberGlassShimmerBrush
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
    onCheckUpdates: () -> Unit = {},
    onSaveGithubRepo: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showManualGuideDialog by remember { mutableStateOf(false) }
    var showGithubRepoDialog by remember { mutableStateOf(false) }

    if (showGithubRepoDialog) {
        ConfigureGithubRepoDialog(
            initialRepo = uiState.githubRepoSlug,
            onSave = { slug ->
                showGithubRepoDialog = false
                onSaveGithubRepo(slug)
            },
            onDismiss = { showGithubRepoDialog = false },
        )
    }

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
                    icon = ToBusIcons.AddToHomeScreen,
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
                        onCheckUpdates = {
                            if (uiState.githubRepoSlug.isBlank()) {
                                showGithubRepoDialog = true
                            } else {
                                onCheckUpdates()
                            }
                        },
                        onEditGithubRepo = { showGithubRepoDialog = true },
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
                        onCheckUpdates = {
                            if (uiState.githubRepoSlug.isBlank()) {
                                showGithubRepoDialog = true
                            } else {
                                onCheckUpdates()
                            }
                        },
                        onEditGithubRepo = { showGithubRepoDialog = true },
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

    val wallpaperBrush = remember {
        Brush.linearGradient(
            colors = listOf(Color(0xFF0E1626), Color(0xFF080A10), Color(0xFF142133)),
        )
    }

    GlassBand(
        modifier = Modifier.testTag("widget_live_preview_card"),
        contentPadding = 14.dp,
    ) {
        // Simulated dark home screen backdrop
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(wallpaperBrush, RoundedCornerShape(20.dp))
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
                val spatialById = remember(uiState.lineVehicles, uiState.routePatterns) {
                    uiState.lineVehicles.associate { bus ->
                        bus.vehicleId to GttFleetAnalyzer.assessSpatial(bus, uiState.routePatterns)
                    }
                }
                val displayedCount = remember(
                    uiState.lineVehicles,
                    spatialById,
                    uiState.settings.hideInactiveInWidget,
                    uiState.nowMillis,
                ) {
                    if (uiState.settings.hideInactiveInWidget) {
                        uiState.lineVehicles.count { bus ->
                            val spatial = spatialById[bus.vehicleId]
                                ?: GttFleetAnalyzer.assessSpatial(bus, uiState.routePatterns)
                            val health = GttFleetAnalyzer.assessWithSpatial(bus, spatial, uiState.nowMillis)
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
                        if (uiState.isRefreshing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(rememberGlassShimmerBrush()),
                            )
                        }
                    } else {
                        val shimmerBrush = rememberGlassShimmerBrush()
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(shimmerBrush)
                                .padding(14.dp),
                        ) {
                            Column(
                                modifier = Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                GlassShimmerBox(
                                    modifier = Modifier
                                        .width(96.dp)
                                        .height(14.dp),
                                    shape = RoundedCornerShape(999.dp),
                                    brush = shimmerBrush,
                                )
                                GlassShimmerBox(
                                    modifier = Modifier
                                        .width(64.dp)
                                        .height(10.dp),
                                    shape = RoundedCornerShape(999.dp),
                                    brush = shimmerBrush,
                                )
                            }
                        }
                    }

                    // Top-left Smoked Glass pill (matches actual 2x2 & 4x2 RemoteViews)
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(if (isSquare) 8.dp else 10.dp)
                            .background(OverlayPillFill, RoundedCornerShape(999.dp))
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
                                .background(AccentGold, RoundedCornerShape(999.dp))
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
                                .background(AccentEmerald, CircleShape),
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
                            .background(OverlayPillFill, CircleShape)
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
                            .background(OverlayPillFill, RoundedCornerShape(999.dp))
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
    val favorites = uiState.favoriteStops
    var previewStopIdx by remember(favorites.size) { mutableStateOf(0) }
    val activeFav = favorites.getOrNull(previewStopIdx.coerceIn(0, (favorites.size - 1).coerceAtLeast(0)))
    val refreshInt = remember { MutableInteractionSource() }
    val switchInt = remember { MutableInteractionSource() }
    val clockFormat = remember { SimpleDateFormat("HH:mm", Locale.ITALY) }

    val groupedLines = remember(activeFav, uiState.stopWidgetPreviewByStopId) {
        if (activeFav == null) {
            emptyList()
        } else {
            val savedLine = activeFav.lineCode.trim().uppercase()
            uiState.stopWidgetPreviewByStopId[activeFav.stopId]
                .orEmpty()
                .groupBy { it.lineCode.trim().uppercase() }
                .entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, List<StopArrival>>> {
                        savedLine.isNotEmpty() && it.key.equals(savedLine, ignoreCase = true)
                    }.thenBy { entry ->
                        entry.value.minOfOrNull { it.arrivalEpochSeconds } ?: Long.MAX_VALUE
                    },
                )
                .map { (code, list) -> code to list.sortedBy { it.arrivalEpochSeconds }.take(3) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth(0.98f)
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(26.dp))
            .background(Color(0xF011131B), RoundedCornerShape(26.dp))
            .border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(26.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header mirroring widget_stop_arrivals.xml (Single Stop + #code · N linee · clock + Switcher + Refresh)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                tint = AccentGold,
                modifier = Modifier.size(17.dp),
            )
            Spacer(modifier = Modifier.width(7.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = activeFav?.let { shortTurinPlaceName(it.name) } ?: "Fermate Live",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val lineCountPart = when (groupedLines.size) {
                    0 -> "nessun bus"
                    1 -> "1 linea"
                    else -> "${groupedLines.size} linee"
                }
                Text(
                    text = if (activeFav != null) {
                        "#${activeFav.stopCode} · $lineCountPart · $timeLabel"
                    } else {
                        "Nessuna fermata preferita"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSubtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (favorites.size > 1) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .glassInteractive(switchInt) {
                            previewStopIdx = (previewStopIdx + 1) % favorites.size
                        }
                        .background(OverlayPillFill, RoundedCornerShape(999.dp))
                        .border(1.dp, OverlayPillBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "${previewStopIdx + 1}/${favorites.size} ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextPrimary,
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .glassInteractive(refreshInt, onClick = onRefreshPreview)
                    .background(OverlayPillFill, CircleShape)
                    .border(1.dp, OverlayPillBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.widget_tap_to_refresh),
                    tint = TextPrimary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        if (activeFav == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassRowFill, RoundedCornerShape(14.dp))
                    .border(1.dp, GlassRowBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = "Tocca una fermata nella Mappa e premi ★ per vedere qui tutte le linee in arrivo in tempo reale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtitle,
                )
            }
        } else if (uiState.isLoadingStopWidgetPreview && groupedLines.isEmpty()) {
            val shimmerBrush = rememberGlassShimmerBrush()
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(2) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(GlassRowFill, RoundedCornerShape(14.dp))
                            .border(1.dp, GlassRowBorder, RoundedCornerShape(14.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            GlassShimmerBox(
                                modifier = Modifier
                                    .width(28.dp)
                                    .height(16.dp),
                                shape = RoundedCornerShape(999.dp),
                                brush = shimmerBrush,
                            )
                            GlassShimmerBox(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(14.dp),
                                shape = RoundedCornerShape(6.dp),
                                brush = shimmerBrush,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(3) {
                                GlassShimmerBox(
                                    modifier = Modifier
                                        .width(66.dp)
                                        .height(18.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    brush = shimmerBrush,
                                )
                            }
                        }
                    }
                }
            }
        } else if (groupedLines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassRowFill, RoundedCornerShape(14.dp))
                    .border(1.dp, GlassRowBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = "Nessun passaggio imminente rilevato per ${shortTurinPlaceName(activeFav.name)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtitle,
                )
            }
        } else {
            val savedLine = activeFav.lineCode.trim().uppercase()
            val savedTerminus = activeFav.terminusName?.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                groupedLines.take(4).forEach { (lineCode, arrivals) ->
                    val isPrimary = savedLine.isNotEmpty() && lineCode.equals(savedLine, ignoreCase = true)
                    val headsign = arrivals
                        .firstOrNull { !com.example.data.model.isGenericDirectionLabel(it.headsign) }
                        ?.headsign
                        ?.let(::shortTurinPlaceName)
                        ?: (if (isPrimary) savedTerminus else null)
                        ?: arrivals
                            .firstOrNull { it.headsign.isNotBlank() && !it.headsign.startsWith("Linea ", true) }
                            ?.headsign
                            ?.let(::shortTurinPlaceName)
                        ?: ""
                    val badgeColor = when {
                        isPrimary && uiState.settings.colorByDirection && activeFav.directionId == 1 -> AccentCyan
                        isPrimary -> AccentGold
                        else -> Color(0x2EFFFFFF)
                    }
                    PreviewStopCardRow(
                        lineCode = lineCode,
                        isPrimaryLine = isPrimary,
                        badgeColor = badgeColor,
                        headsign = headsign,
                        arrivals = arrivals,
                        nowMillis = uiState.nowMillis,
                        clockFormat = clockFormat,
                    )
                }
                if (groupedLines.size > 4) {
                    Text(
                        text = "+ altre ${groupedLines.size - 4} linee scorrevoli nel widget",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSubtitle,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewStopCardRow(
    lineCode: String,
    isPrimaryLine: Boolean,
    badgeColor: Color,
    headsign: String,
    arrivals: List<StopArrival>,
    nowMillis: Long,
    clockFormat: SimpleDateFormat,
) {
    val hasAnyLive = arrivals.any { it.isRealtime }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isPrimaryLine) Color(0x22FFFFFF) else GlassRowFill, RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = if (isPrimaryLine) AccentGold.copy(alpha = 0.45f) else GlassRowBorder,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .background(badgeColor, RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = lineCode,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isPrimaryLine) DarkInkOnAccent else TextPrimary,
                )
            }
            Text(
                text = if (headsign.isNotBlank()) "→ $headsign" else "Linea $lineCode",
                style = MaterialTheme.typography.labelMedium,
                color = TextBody,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (hasAnyLive) "● Live" else "○ Orario",
                style = MaterialTheme.typography.labelSmall,
                color = if (hasAnyLive) AccentEmerald else TextSubtitle,
                maxLines = 1,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                        .background(
                            color = if (highlight) AccentEmerald.copy(alpha = 0.16f) else Color(0x18FFFFFF),
                            shape = RoundedCornerShape(9.dp),
                        )
                        .border(
                            1.dp,
                            if (highlight) AccentEmerald.copy(alpha = 0.45f) else GlassRowBorder,
                            RoundedCornerShape(9.dp),
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
                    isSquare -> ToBusIcons.GridView
                    else -> ToBusIcons.Widgets
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
    onCheckUpdates: () -> Unit,
    onEditGithubRepo: () -> Unit,
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
                    icon = ToBusIcons.DirectionsBus,
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
                    val spatialById = remember(uiState.lineVehicles, uiState.routePatterns) {
                        uiState.lineVehicles.associate { bus ->
                            bus.vehicleId to GttFleetAnalyzer.assessSpatial(bus, uiState.routePatterns)
                        }
                    }
                    val activeReliableCount = remember(uiState.lineVehicles, spatialById, uiState.nowMillis) {
                        uiState.lineVehicles.count { bus ->
                            val spatial = spatialById[bus.vehicleId]
                                ?: GttFleetAnalyzer.assessSpatial(bus, uiState.routePatterns)
                            GttFleetAnalyzer.assessWithSpatial(bus, spatial, uiState.nowMillis).isReliableLive
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
                        .background(GlassPillFill, RoundedCornerShape(999.dp))
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
                    val openInt = remember(fav.stopId) { MutableInteractionSource() }
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassInteractive(openInt, enableHoverScale = false) { onOpenFavoriteStop(fav) }
                            .background(GlassRowFill, shape)
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
                        .background(if (isSelected) GlassRowActiveFill else GlassRowFill, shape)
                        .border(1.dp, if (isSelected) GlassRowActiveBorder else GlassRowBorder, shape)
                        .padding(12.dp)
                        .testTag("camera_mode_${mode.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccentIconBadge(
                        icon = if (mode == WidgetCameraMode.AUTO_FIT_BUSES) {
                            ToBusIcons.AutoAwesome
                        } else {
                            ToBusIcons.MyLocation
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
                            imageVector = ToBusIcons.CropFree,
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
                icon = ToBusIcons.BatterySaver,
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

    // 6. Lightweight Daily GitHub Updates (zero background services)
    GlassBand {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccentIconBadge(
                icon = ToBusIcons.SystemUpdate,
                accent = AccentCyan,
                size = 40.dp,
                iconSize = 20.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Aggiornamenti app",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextHero,
                )
                Text(
                    text = if (uiState.githubRepoSlug.isNotBlank()) {
                        "v${BuildConfig.VERSION_NAME} · ${uiState.githubRepoSlug}"
                    } else {
                        "v${BuildConfig.VERSION_NAME} · Controllo quotidiano su GitHub"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val checkInt = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .glassInteractive(checkInt, enableHoverScale = false, onClick = onCheckUpdates)
                    .background(GlassPillFill, RoundedCornerShape(999.dp))
                    .border(1.dp, GlassPillBorder, RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("check_updates_button"),
            ) {
                Text(
                    text = when {
                        uiState.isCheckingUpdate -> "Controllo…"
                        uiState.githubRepoSlug.isBlank() -> "Configura"
                        else -> "Controlla"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary,
                )
            }
        }
        if (uiState.githubRepoSlug.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            val editInt = remember { MutableInteractionSource() }
            Text(
                text = "Cambia repository GitHub",
                style = MaterialTheme.typography.labelSmall,
                color = AccentCyan,
                modifier = Modifier
                    .glassInteractive(editInt, enableHoverScale = false, onClick = onEditGithubRepo)
                    .padding(vertical = 4.dp)
                    .testTag("edit_github_repo_button"),
            )
        }
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
        LiquidGlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
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
        LiquidGlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("toggle_color_direction"),
        )
    }
}

