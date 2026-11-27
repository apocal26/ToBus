package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.model.BusVehicle
import com.example.data.model.CameraState
import com.example.data.model.DataSourceMode
import com.example.data.model.FavoriteStop
import com.example.data.model.GttDataOutageReport
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.GttOutageKind
import com.example.data.model.StopArrival
import com.example.data.model.TransitStop
import com.example.data.model.highestAlertCategory
import com.example.data.model.shortTurinPlaceName
import com.example.ui.components.InteractiveTurinMap
import com.example.ui.glass.GlassCircleIconButton
import com.example.ui.glass.HeroEntranceText
import com.example.ui.glass.LightHapticKind
import com.example.ui.glass.LiquidGlassOverlaySurface
import com.example.ui.glass.MapOverlayGlassPill
import com.example.ui.glass.glassInteractive
import com.example.ui.glass.glassSpring
import com.example.ui.glass.liquidGlassRimBrush
import com.example.ui.glass.performLightHaptic
import com.example.ui.icons.ToBusIcons
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentEmerald
import com.example.ui.theme.AccentGold
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.AccentRed
import com.example.ui.theme.DarkInkOnAccent
import com.example.ui.theme.LightMapScrimBottom
import com.example.ui.theme.LightMapScrimTop
import com.example.ui.theme.OverlayPillFill
import com.example.ui.theme.OverlayPillLightMapFill
import com.example.ui.theme.ScrimBottomEnd
import com.example.ui.theme.ScrimTopStart
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextHero
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import com.example.ui.viewmodel.ToBusUiState
import kotlinx.coroutines.flow.StateFlow

private sealed interface MapInspectorTarget {
    data object None : MapInspectorTarget
    data class Bus(val vehicle: BusVehicle) : MapInspectorTarget
    data class Stop(val stop: TransitStop) : MapInspectorTarget
}

@Composable
fun MapScreen(
    uiState: ToBusUiState,
    pollProgressFlow: StateFlow<Float>,
    isForegroundActive: Boolean,
    isWideScreen: Boolean = false,
    showStartupOverlay: Boolean = false,
    isMapReady: Boolean = true,
    bottomNavClearance: Dp,
    onCameraChange: (CameraState) -> Unit,
    onViewportSizeChange: (Int, Int) -> Unit,
    onCameraFlyConsumed: (Long) -> Unit = {},
    onMapReadyChanged: (Boolean) -> Unit = {},
    onOpenLinePicker: () -> Unit,
    onRefresh: () -> Unit,
    onUserLocationClick: () -> Unit,
    onLocationPermissionResult: (Boolean) -> Unit,
    onSelectVehicle: (BusVehicle?) -> Unit,
    onSelectStop: (TransitStop?) -> Unit,
    onRefreshStopArrivals: () -> Unit,
    onToggleFavoriteStop: (TransitStop) -> Unit,
    onOpenFavoriteStop: (FavoriteStop) -> Unit,
    onFocusArrivalBus: (StopArrival, Int) -> Unit,
    onSelectLine: (String) -> Unit,
    onSelectDataSourceMode: (DataSourceMode) -> Unit = {},
    onDismissGttOutage: () -> Unit = {},
    openAlertsRequestSeq: Long = 0L,
    onAlertsDialogOpened: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    var showAlertsDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(openAlertsRequestSeq) {
        if (openAlertsRequestSeq > 0L) {
            showAlertsDialog = true
        }
    }

    LaunchedEffect(showAlertsDialog) {
        if (showAlertsDialog) {
            onAlertsDialogOpened()
        }
    }

    val latestSelectedVehicleId by rememberUpdatedState(uiState.selectedVehicleId)
    val latestSelectedStopId by rememberUpdatedState(uiState.selectedStop?.stopId)
    val latestOnSelectVehicle by rememberUpdatedState(onSelectVehicle)
    val latestOnSelectStop by rememberUpdatedState(onSelectStop)
    val latestOnOpenFavoriteStop by rememberUpdatedState(onOpenFavoriteStop)
    val latestOnFocusArrivalBus by rememberUpdatedState(onFocusArrivalBus)
    val latestOnUserLocationClick by rememberUpdatedState(onUserLocationClick)

    val handleSelectVehicle: (BusVehicle?) -> Unit = remember(view) {
        { vehicle ->
            if (vehicle != null && vehicle.vehicleId != latestSelectedVehicleId) {
                performLightHaptic(view, LightHapticKind.SELECT_BUS)
            }
            latestOnSelectVehicle(vehicle)
        }
    }
    val handleSelectStop: (TransitStop?) -> Unit = remember(view) {
        { stop ->
            if (stop != null && !stop.stopId.equals(latestSelectedStopId, ignoreCase = true)) {
                performLightHaptic(view, LightHapticKind.SWITCH_STOP)
            }
            latestOnSelectStop(stop)
        }
    }
    val handleOpenFavoriteStop: (FavoriteStop) -> Unit = remember(view) {
        { fav ->
            if (!fav.stopId.equals(latestSelectedStopId, ignoreCase = true)) {
                performLightHaptic(view, LightHapticKind.SWITCH_STOP)
            }
            latestOnOpenFavoriteStop(fav)
        }
    }
    val handleFocusArrivalBus: (StopArrival, Int) -> Unit = remember(view) {
        { arrival, indexInLine ->
            val hasLiveTarget = arrival.isRealtime &&
                (!arrival.vehicleId.isNullOrBlank() || !arrival.tripId.isNullOrBlank())
            if (hasLiveTarget) {
                performLightHaptic(view, LightHapticKind.SELECT_BUS)
            }
            latestOnFocusArrivalBus(arrival, indexInLine)
        }
    }

    BackHandler(enabled = uiState.selectedVehicle != null || uiState.selectedStop != null) {
        if (uiState.selectedVehicle != null) onSelectVehicle(null)
        if (uiState.selectedStop != null) onSelectStop(null)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        onLocationPermissionResult(granted)
    }

    val handleUserLocationTap = remember(context, locationPermissionLauncher) {
        {
            val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            if (hasFine || hasCoarse) {
                latestOnUserLocationClick()
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }
    }

    val spatialByVehicleId = remember(uiState.lineVehicles, uiState.routePatterns) {
        uiState.lineVehicles.associate { bus ->
            bus.vehicleId to GttFleetAnalyzer.assessSpatial(bus, uiState.routePatterns)
        }
    }
    val healthByVehicleId = remember(uiState.lineVehicles, spatialByVehicleId, uiState.nowMillis) {
        uiState.lineVehicles.associate { bus ->
            val spatial = spatialByVehicleId[bus.vehicleId]
                ?: GttFleetAnalyzer.assessSpatial(bus, uiState.routePatterns)
            bus.vehicleId to GttFleetAnalyzer.assessWithSpatial(bus, spatial, uiState.nowMillis)
        }
    }
    val resolvedDirectionById = remember(uiState.lineVehicles, uiState.routePatterns) {
        uiState.lineVehicles.associate { bus ->
            bus.vehicleId to GttFleetAnalyzer.resolveDirectionId(bus, uiState.routePatterns)
        }
    }
    val activeReliableCount = remember(healthByVehicleId) {
        healthByVehicleId.values.count { it.isReliableLive }
    }
    val depotCount = remember(healthByVehicleId) {
        healthByVehicleId.values.count { it.isInDepot }
    }
    val offRouteCount = remember(healthByVehicleId) {
        healthByVehicleId.values.count { !it.isInDepot && !it.isStaleGps && !it.isStuck && it.isOffRoute }
    }
    val staleOrStuckCount = remember(healthByVehicleId) {
        healthByVehicleId.values.count { !it.isInDepot && (it.isStaleGps || it.isStuck) }
    }
    var focusedDirectionFilter by remember(uiState.settings.selectedLine) { mutableStateOf<Int?>(null) }
    val directionTermini = remember(uiState.routePatterns) {
        uiState.routePatterns
            .groupBy { it.directionId }
            .mapValues { (_, patterns) ->
                val raw = patterns.firstNotNullOfOrNull { pat ->
                    pat.terminusName()?.takeIf { it.isNotBlank() && !it.startsWith("Linea ") }
                } ?: patterns.firstOrNull()?.terminusName().orEmpty()
                shortTurinPlaceName(raw)
            }
            .filterValues { it.isNotBlank() }
    }
    val visibleMapVehicles = remember(
        uiState.lineVehicles,
        healthByVehicleId,
        uiState.settings.hideInactiveOnMap,
        uiState.selectedVehicleId,
    ) {
        if (!uiState.settings.hideInactiveOnMap) {
            uiState.lineVehicles
        } else {
            uiState.lineVehicles.filter { bus ->
                bus.vehicleId == uiState.selectedVehicleId ||
                    healthByVehicleId[bus.vehicleId]?.let { !it.isInDepot && !it.isStaleGps } != false
            }
        }
    }
    val sortedVehicles = remember(
        visibleMapVehicles,
        healthByVehicleId,
        resolvedDirectionById,
        focusedDirectionFilter,
        uiState.selectedVehicleId,
    ) {
        visibleMapVehicles
            .filter { bus ->
                focusedDirectionFilter == null ||
                    bus.vehicleId == uiState.selectedVehicleId ||
                    (resolvedDirectionById[bus.vehicleId] ?: bus.directionId) == focusedDirectionFilter
            }
            .sortedWith(
                compareBy<BusVehicle> { healthByVehicleId[it.vehicleId]?.isInDepot == true }
                    .thenBy { healthByVehicleId[it.vehicleId]?.isOffRoute == true }
                    .thenBy { healthByVehicleId[it.vehicleId]?.isStaleGps == true }
                    .thenBy { healthByVehicleId[it.vehicleId]?.isStuck == true }
                    .thenBy { it.vehicleId },
            )
    }

    val startupBlurDp by animateDpAsState(
        targetValue = if (showStartupOverlay) 14.dp else 0.dp,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "startup_map_blur",
    )

    Box(modifier = modifier.fillMaxSize()) {
        // 1. Full-bleed interactive Torino slippy map (clean street tiles, smooth bus motion & optional GPS dot)
        InteractiveTurinMap(
            camera = uiState.camera,
            mapStyle = uiState.settings.mapStyle,
            vehicles = visibleMapVehicles,
            focusedExternalVehicle = uiState.focusedExternalVehicle,
            focusedExternalRoutePatterns = uiState.focusedExternalRoutePatterns,
            userLocation = uiState.userLocation,
            cameraFlyRequest = uiState.cameraFlyRequest,
            routePatterns = uiState.routePatterns,
            selectedVehicleId = uiState.selectedVehicleId,
            selectedStop = uiState.selectedStop,
            selectedStopId = uiState.selectedStop?.stopId,
            favoriteStopIds = uiState.favoriteStopIds,
            nowMillis = uiState.nowMillis,
            isForegroundActive = isForegroundActive,
            showVehicleLabels = uiState.settings.showVehicleLabels,
            showRouteLine = uiState.settings.showRouteLine,
            colorByDirection = uiState.settings.colorByDirection,
            focusedDirectionFilter = focusedDirectionFilter,
            shiftCameraForBottomSheet = !isWideScreen,
            precomputedHealthById = healthByVehicleId,
            precomputedDirectionById = resolvedDirectionById,
            onCameraChange = onCameraChange,
            onViewportSizeChange = onViewportSizeChange,
            onCameraFlyConsumed = onCameraFlyConsumed,
            onMapReadyChanged = onMapReadyChanged,
            onVehicleTap = handleSelectVehicle,
            onStopTap = handleSelectStop,
            modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && startupBlurDp > 0.5.dp) {
                Modifier.blur(startupBlurDp)
            } else {
                Modifier
            },
        )

        val isLightBasemap = !uiState.settings.mapStyle.isDark

        // 2. Calibrated Top & Bottom Scrims (lighter & airy on Positron/Voyager, deeper on Dark/Satellite)
        val topScrimBrush = remember(isLightBasemap) {
            Brush.verticalGradient(
                listOf(
                    if (isLightBasemap) LightMapScrimTop else ScrimTopStart,
                    Color.Transparent,
                ),
            )
        }
        val bottomScrimBrush = remember(isLightBasemap) {
            Brush.verticalGradient(
                listOf(
                    Color.Transparent,
                    if (isLightBasemap) LightMapScrimBottom else ScrimBottomEnd,
                ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isWideScreen) 104.dp else 124.dp)
                .align(Alignment.TopCenter)
                .background(topScrimBrush),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isWideScreen) 104.dp else 150.dp + bottomNavClearance)
                .align(Alignment.BottomCenter)
                .background(bottomScrimBrush),
        )

        // 3. Top Floating Liquid Glass Bar + Adaptive Direction & Favorite Stops Row
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // High-contrast Liquid Glass Line Selector Pill
                MapOverlayGlassPill(
                    onClick = onOpenLinePicker,
                    isLightBasemap = isLightBasemap,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 360.dp)
                        .testTag("open_line_picker_pill"),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 6.dp, top = 5.dp, end = 12.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .background(AccentGold, RoundedCornerShape(999.dp))
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = uiState.settings.selectedLine.ifBlank { "—" },
                                style = MaterialTheme.typography.labelLarge,
                                color = DarkInkOnAccent,
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(
                                            color = when {
                                                uiState.errorMessage != null -> AccentRed
                                                !uiState.settings.hasSelectedLine -> AccentGold
                                                activeReliableCount == 0 -> AccentGold
                                                else -> AccentEmerald
                                            },
                                            shape = CircleShape,
                                        ),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                HeroEntranceText(
                                    text = when {
                                        !uiState.settings.hasSelectedLine -> "Scegli una linea"
                                        uiState.isLoadingFeed -> "Connessione…"
                                        uiState.lineVehicles.isEmpty() -> "Nessun mezzo"
                                        activeReliableCount == 0 && depotCount > 0 -> "$depotCount in deposito"
                                        activeReliableCount == 0 && offRouteCount > 0 -> "$offRouteCount fuori linea"
                                        activeReliableCount == 1 -> "1 pullman attivo"
                                        else -> "$activeReliableCount pullman attivi"
                                    },
                                    key = "${uiState.settings.selectedLine}_${activeReliableCount}_${depotCount}_$offRouteCount",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = TextPrimary,
                                )
                            }
                            Text(
                                text = when {
                                    !uiState.settings.hasSelectedLine ->
                                        "Tocca per scegliere linea o fermata"
                                    uiState.settings.dataSourceMode.isFixed ->
                                        "Solo ${uiState.settings.dataSourceMode.labelIt} · Cambia linea"
                                    depotCount > 0 && staleOrStuckCount > 0 ->
                                        "$depotCount deposito · $staleOrStuckCount fermo · Cambia"
                                    depotCount > 0 && activeReliableCount > 0 ->
                                        "+$depotCount in deposito · Cambia linea"
                                    offRouteCount > 0 && activeReliableCount > 0 ->
                                        "+$offRouteCount fuori percorso · Cambia linea"
                                    staleOrStuckCount == 1 ->
                                        "1 non aggiornato · Cambia linea"
                                    staleOrStuckCount > 1 ->
                                        "$staleOrStuckCount non aggiornati · Cambia linea"
                                    uiState.onlineSourcesCount > 0 ->
                                        "${uiState.onlineSourcesCount} fonti attive · Linee e fermate"
                                    else -> "Linee e fermate GTT"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.settings.dataSourceMode.isFixed) AccentGold else TextSubtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.cd_select_line),
                            tint = TextSubtitle,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Glowing Glass GTT Alerts Button (optional via Settings)
                    if (uiState.settings.showServiceAlerts) {
                        val highestLineAlertCategory = remember(uiState.lineAlerts) {
                            uiState.lineAlerts.highestAlertCategory()
                        }
                        GttAlertsGlassButton(
                            lineAlertCategory = highestLineAlertCategory,
                            hasGeneralAlerts = uiState.allAlerts.isNotEmpty(),
                            badgeCount = if (uiState.lineAlerts.isNotEmpty()) {
                                uiState.lineAlerts.size
                            } else {
                                uiState.allAlerts.size
                            },
                            isLightBasemap = isLightBasemap,
                            onClick = { showAlertsDialog = true },
                        )
                    }

                    // Discreet optional GPS Location button right beside Refresh
                    UserLocationGlassButton(
                        isEnabled = uiState.isUserLocationEnabled,
                        isLocating = uiState.isLocatingUser && isForegroundActive,
                        isLightBasemap = isLightBasemap,
                        onClick = handleUserLocationTap,
                    )

                    // Refresh button with integrated circular live-countdown progress ring
                    RefreshProgressGlassButton(
                        pollProgressFlow = pollProgressFlow,
                        isRefreshing = uiState.isRefreshing && isForegroundActive,
                        isLightBasemap = isLightBasemap,
                        onClick = onRefresh,
                        modifier = Modifier.testTag("refresh_map_button"),
                    )
                }
            }

            // Unobtrusive Direction Filter + Favorite Stops micro-pills bar
            val hasTwoDirections = directionTermini.size >= 2 && uiState.settings.showRouteLine
            if (hasTwoDirections || uiState.favoriteStops.isNotEmpty()) {
                val neutralPillRim = remember {
                    liquidGlassRimBrush(topAlpha = 0.46f, midAlpha = 0.14f, bottomAlpha = 0.24f)
                }
                val neutralPillFillBrush = remember {
                    Brush.verticalGradient(listOf(Color(0x16FFFFFF), Color.Transparent))
                }
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("favorite_stops_bar"),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (hasTwoDirections) {
                        item(key = "direction_filter_chip") {
                            val dir0Name = directionTermini[0] ?: "Andata"
                            val dir1Name = directionTermini[1] ?: "Ritorno"
                            val activeAccent = when (focusedDirectionFilter) {
                                0 -> AccentGold
                                1 -> if (uiState.settings.colorByDirection) AccentCyan else AccentGold
                                else -> TextPrimary
                            }
                            val isFiltered = focusedDirectionFilter != null
                            val dirInteraction = remember { MutableInteractionSource() }
                            val pillBaseFill = if (isLightBasemap) OverlayPillLightMapFill else OverlayPillFill
                            val dirBorderBrush = remember(isFiltered, activeAccent) {
                                if (isFiltered) {
                                    Brush.verticalGradient(
                                        listOf(
                                            Color.White.copy(alpha = 0.56f),
                                            activeAccent.copy(alpha = 0.80f),
                                        ),
                                    )
                                } else {
                                    neutralPillRim
                                }
                            }
                            val dirFillBrush = remember(isFiltered, activeAccent) {
                                if (isFiltered) {
                                    Brush.verticalGradient(
                                        listOf(activeAccent.copy(alpha = 0.22f), activeAccent.copy(alpha = 0.05f)),
                                    )
                                } else {
                                    neutralPillFillBrush
                                }
                            }
                            Surface(
                                modifier = Modifier
                                    .glassInteractive(dirInteraction) {
                                        focusedDirectionFilter = when (focusedDirectionFilter) {
                                            null -> 0
                                            0 -> 1
                                            else -> null
                                        }
                                    }
                                    .testTag("direction_focus_chip"),
                                shape = RoundedCornerShape(999.dp),
                                color = pillBaseFill,
                                border = BorderStroke(
                                    width = if (isFiltered) 1.3.dp else 1.dp,
                                    brush = dirBorderBrush,
                                ),
                            ) {
                                Box(modifier = Modifier.background(dirFillBrush)) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (focusedDirectionFilter == null) {
                                            // Dual Gold + Cyan dots indicating both directions active
                                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .background(AccentGold, CircleShape),
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .size(7.dp)
                                                        .background(
                                                            color = if (uiState.settings.colorByDirection) AccentCyan else AccentGold,
                                                            shape = CircleShape,
                                                        ),
                                                )
                                            }
                                            Text(
                                                text = "2 direzioni",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextPrimary,
                                                maxLines = 1,
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .background(activeAccent, CircleShape),
                                            )
                                            Text(
                                                text = "→ ${if (focusedDirectionFilter == 0) dir0Name else dir1Name}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = activeAccent,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 280.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    items(uiState.favoriteStops, key = { it.stopId }) { fav ->
                        val isSelectedFav = uiState.selectedStop?.stopId.equals(fav.stopId, ignoreCase = true)
                        val favInteraction = remember { MutableInteractionSource() }
                        val favLabel = remember(fav.name) { shortTurinPlaceName(fav.name) }
                        val favBorderBrush = remember(isSelectedFav) {
                            if (isSelectedFav) {
                                Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.60f), AccentGold.copy(alpha = 0.85f)),
                                )
                            } else {
                                neutralPillRim
                            }
                        }
                        val favFillBrush = remember(isSelectedFav) {
                            if (isSelectedFav) {
                                Brush.verticalGradient(
                                    listOf(AccentGold.copy(alpha = 0.24f), AccentGold.copy(alpha = 0.06f)),
                                )
                            } else {
                                neutralPillFillBrush
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .glassInteractive(favInteraction) { handleOpenFavoriteStop(fav) }
                                .testTag("favorite_stop_chip_${fav.stopId}"),
                            shape = RoundedCornerShape(999.dp),
                            color = if (isLightBasemap) OverlayPillLightMapFill else OverlayPillFill,
                            border = BorderStroke(
                                width = if (isSelectedFav) 1.25.dp else 1.dp,
                                brush = favBorderBrush,
                            ),
                        ) {
                            Box(modifier = Modifier.background(favFillBrush)) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Star,
                                        contentDescription = null,
                                        tint = AccentGold,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = favLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 128.dp),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .background(AccentGold.copy(alpha = 0.20f), RoundedCornerShape(999.dp))
                                            .padding(horizontal = 5.dp, vertical = 1.dp),
                                    ) {
                                        Text(
                                            text = fav.lineCode,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AccentGold,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val visibleOutage: GttDataOutageReport? = uiState.gttDataOutage?.takeUnless { uiState.isGttOutageDismissed }

        val outageBannerContent: @Composable (Modifier) -> Unit = { cardModifier ->
            AnimatedVisibility(
                visible = visibleOutage != null,
                enter = if (isWideScreen) {
                    fadeIn() + slideInHorizontally(animationSpec = glassSpring()) { it / 2 }
                } else {
                    fadeIn() + slideInVertically { it / 2 }
                },
                exit = if (isWideScreen) {
                    fadeOut() + slideOutHorizontally { it / 2 }
                } else {
                    fadeOut() + slideOutVertically { it / 2 }
                },
            ) {
                val report = visibleOutage ?: return@AnimatedVisibility
                val accent = when (report.kind) {
                    GttOutageKind.SERVERS_UNREACHABLE -> AccentRed
                    GttOutageKind.CITYWIDE_FROZEN_FLEET -> AccentOrange
                    GttOutageKind.CITYWIDE_STALE_FEED -> AccentGold
                }
                LiquidGlassOverlaySurface(
                    isLightBasemap = isLightBasemap,
                    accentTint = accent,
                    shape = RoundedCornerShape(20.dp),
                    modifier = cardModifier.testTag("gtt_outage_banner"),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = ToBusIcons.WarningAmber,
                                contentDescription = "Avviso disservizio dati GTT",
                                tint = accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = report.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextHero,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (report.isSimulated) {
                                Box(
                                    modifier = Modifier
                                        .background(AccentGold.copy(alpha = 0.22f), RoundedCornerShape(999.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Text(
                                        text = "TEST",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AccentGold,
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            GlassCircleIconButton(
                                icon = Icons.Rounded.Close,
                                contentDescription = "Nascondi avviso disservizio GTT",
                                onClick = onDismissGttOutage,
                                modifier = Modifier.testTag("dismiss_gtt_outage_button"),
                            )
                        }
                        Text(
                            text = report.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBody,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Rete dispositivo verificata · problema lato server GTT",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSubtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = onRefresh,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("retry_gtt_outage_button"),
                            ) {
                                Text("Riprova", color = AccentCyan)
                            }
                        }
                    }
                }
            }
        }

        val errorCardContent: @Composable (Modifier) -> Unit = { cardModifier ->
            AnimatedVisibility(
                visible = uiState.errorMessage != null && visibleOutage == null,
                enter = if (isWideScreen) {
                    fadeIn() + slideInHorizontally(animationSpec = glassSpring()) { it / 2 }
                } else {
                    fadeIn() + slideInVertically { it / 2 }
                },
                exit = if (isWideScreen) {
                    fadeOut() + slideOutHorizontally { it / 2 }
                } else {
                    fadeOut() + slideOutVertically { it / 2 }
                },
            ) {
                LiquidGlassOverlaySurface(
                    isLightBasemap = isLightBasemap,
                    accentTint = AccentRed,
                    shape = RoundedCornerShape(20.dp),
                    modifier = cardModifier,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = ToBusIcons.WarningAmber,
                            contentDescription = null,
                            tint = AccentRed,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = uiState.errorMessage.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBody,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRefresh) {
                            Text("Riprova", color = AccentCyan)
                        }
                    }
                }
            }
        }

        val selBus = uiState.selectedVehicle
        val selStop = uiState.selectedStop
        val inspectorTarget = remember(selBus, selStop) {
            when {
                selBus != null -> MapInspectorTarget.Bus(selBus)
                selStop != null -> MapInspectorTarget.Stop(selStop)
                else -> MapInspectorTarget.None
            }
        }

        val inspectorCardSlot: @Composable (Modifier, Dp) -> Unit = { cardModifier, maxScrollHeight ->
            var renderedTarget by remember { mutableStateOf<MapInspectorTarget>(MapInspectorTarget.None) }
            if (inspectorTarget !is MapInspectorTarget.None) {
                renderedTarget = inspectorTarget
            }

            AnimatedVisibility(
                visible = inspectorTarget !is MapInspectorTarget.None,
                enter = fadeIn(tween(190, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(230, easing = FastOutSlowInEasing)) { it / 6 },
                exit = fadeOut(tween(145)) +
                    slideOutVertically(tween(180, easing = FastOutSlowInEasing)) { it / 6 },
            ) {
                AnimatedContent(
                    targetState = renderedTarget,
                    contentAlignment = if (isWideScreen) Alignment.BottomEnd else Alignment.BottomCenter,
                    contentKey = { target ->
                        when (target) {
                            MapInspectorTarget.None -> "none"
                            is MapInspectorTarget.Bus -> "bus_${target.vehicle.vehicleId}"
                            is MapInspectorTarget.Stop -> "stop_${target.stop.stopId}"
                        }
                    },
                    transitionSpec = {
                        val enterAnim = fadeIn(tween(190, delayMillis = 35, easing = FastOutSlowInEasing)) +
                            scaleIn(initialScale = 0.985f, animationSpec = tween(210, easing = FastOutSlowInEasing))
                        val exitAnim = fadeOut(tween(120))
                        enterAnim.togetherWith(exitAnim).using(
                            SizeTransform(clip = false) { _, _ ->
                                tween(durationMillis = 210, easing = FastOutSlowInEasing)
                            },
                        )
                    },
                    label = "map_inspector_card_transition",
                ) { target ->
                    when (target) {
                        MapInspectorTarget.None -> Unit

                        is MapInspectorTarget.Bus -> {
                            val bus = target.vehicle
                            val isExternal = uiState.focusedExternalVehicle?.vehicleId == bus.vehicleId &&
                                uiState.lineVehicles.none { it.vehicleId == bus.vehicleId }
                            val activePatterns = if (isExternal) uiState.focusedExternalRoutePatterns else uiState.routePatterns
                            val resolvedDirectionId = if (isExternal) {
                                GttFleetAnalyzer.resolveDirectionId(bus, activePatterns) ?: bus.directionId
                            } else {
                                resolvedDirectionById[bus.vehicleId]
                                    ?: GttFleetAnalyzer.resolveDirectionId(bus, activePatterns)
                            }
                            val currentStop = remember(bus, activePatterns, resolvedDirectionId) {
                                GttFleetAnalyzer.findCurrentStop(bus, activePatterns, resolvedDirectionId)
                            }
                            val health = if (isExternal) {
                                GttFleetAnalyzer.assess(bus, activePatterns, uiState.nowMillis)
                            } else {
                                healthByVehicleId[bus.vehicleId]
                                    ?: GttFleetAnalyzer.assess(bus, activePatterns, uiState.nowMillis)
                            }
                            SelectedBusInspectorCard(
                                bus = bus,
                                isExternal = isExternal,
                                isLightBasemap = isLightBasemap,
                                resolvedDirectionId = resolvedDirectionId,
                                health = health,
                                terminusName = if (isExternal) {
                                    uiState.focusedExternalTerminus
                                        ?: activePatterns.firstOrNull { it.directionId == resolvedDirectionId }?.terminusName()
                                } else {
                                    uiState.terminusForDirection(resolvedDirectionId)
                                },
                                currentStopName = currentStop?.name,
                                nowMillis = uiState.nowMillis,
                                colorByDirection = uiState.settings.colorByDirection,
                                onSwitchToLine = if (isExternal) {
                                    { onSelectLine(bus.lineCode) }
                                } else {
                                    null
                                },
                                onClose = { onSelectVehicle(null) },
                                modifier = cardModifier,
                            )
                        }

                        is MapInspectorTarget.Stop -> {
                            val stop = target.stop
                            val isStopOnTrackedLine = remember(stop.stopId, stop.stopCode, uiState.routePatterns) {
                                uiState.routePatterns.any { pat ->
                                    pat.stops.any {
                                        it.stopId.equals(stop.stopId, ignoreCase = true) ||
                                            it.stopCode.equals(stop.stopCode, ignoreCase = true)
                                    }
                                }
                            }
                            val stopTerminusName = if (isStopOnTrackedLine) {
                                uiState.terminusForDirection(stop.directionId)
                            } else {
                                uiState.stopArrivals.firstOrNull {
                                    it.headsign.isNotBlank() && !it.headsign.startsWith("Linea ", ignoreCase = true)
                                }?.headsign
                            }
                            SelectedStopArrivalsCard(
                                stop = stop,
                                terminusName = stopTerminusName,
                                selectedLine = uiState.settings.selectedLine,
                                arrivals = uiState.stopArrivals,
                                isLoading = uiState.isLoadingStopArrivals,
                                isFavorite = uiState.isFavoriteStop(stop.stopId),
                                isLightBasemap = isLightBasemap,
                                nowMillis = uiState.nowMillis,
                                colorByDirection = uiState.settings.colorByDirection,
                                maxScrollHeight = maxScrollHeight,
                                onToggleFavorite = { onToggleFavoriteStop(stop) },
                                onRefresh = onRefreshStopArrivals,
                                onArrivalTap = handleFocusArrivalBus,
                                onClose = { onSelectStop(null) },
                                modifier = cardModifier,
                            )
                        }
                    }
                }
            }
        }

        val activeBusChipsBar: @Composable () -> Unit = {
            if (sortedVehicles.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sortedVehicles, key = { "${it.vehicleId}_${it.lineCode}" }) { bus ->
                        val isSelected = bus.vehicleId == uiState.selectedVehicleId
                        val health = healthByVehicleId[bus.vehicleId]
                            ?: GttFleetAnalyzer.assess(bus, uiState.routePatterns, uiState.nowMillis)
                        val resolvedDir = resolvedDirectionById[bus.vehicleId]
                        val directionAccent = when {
                            health.isInDepot || health.isOffRoute -> null
                            uiState.settings.colorByDirection && resolvedDir == 1 -> AccentCyan
                            else -> AccentGold
                        }
                        ActiveBusDirectionGlassPill(
                            bus = bus,
                            health = health,
                            nowMillis = uiState.nowMillis,
                            directionAccent = directionAccent,
                            isSelected = isSelected,
                            isLightBasemap = isLightBasemap,
                            onClick = { handleSelectVehicle(bus) },
                        )
                    }
                }
            } else if (uiState.settings.hasSelectedLine && !uiState.isLoadingFeed && uiState.errorMessage == null) {
                val isNonLiveLine = com.example.data.network.GtfsRtProtobufParser.isNonLiveTrackedLine(
                    uiState.settings.selectedLine,
                )
                val isFixedMato = !isNonLiveLine && uiState.settings.dataSourceMode == DataSourceMode.MATO_LIVE
                val matoStatusDetail = if (isFixedMato) {
                    uiState.sourceStatuses.firstOrNull { it.id == "mato_live" }?.detail
                } else {
                    null
                }
                MapOverlayGlassPill(
                    isLightBasemap = isLightBasemap,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = ToBusIcons.DirectionsBus,
                            contentDescription = null,
                            tint = if (isNonLiveLine) AccentCyan else AccentGold,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                text = when {
                                    isNonLiveLine ->
                                        "La linea ${uiState.settings.selectedLine} non viene tracciata in tempo reale"
                                    uiState.settings.hideInactiveOnMap && uiState.lineVehicles.isNotEmpty() ->
                                        "${uiState.lineVehicles.size} mezzi in deposito o fermi nascosti"
                                    isFixedMato ->
                                        "MaTO Live · 0 mezzi sulla linea ${uiState.settings.selectedLine}"
                                    else ->
                                        "Nessun mezzo attivo ora sulla linea ${uiState.settings.selectedLine}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextBody,
                            )
                            val secondaryLine = when {
                                isNonLiveLine -> "Fermate, arrivi stimati e avvisi GTT disponibili"
                                !matoStatusDetail.isNullOrBlank() -> matoStatusDetail
                                else -> null
                            }
                            if (secondaryLine != null) {
                                Text(
                                    text = secondaryLine,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextBody.copy(alpha = 0.75f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (isFixedMato) {
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                onClick = { onSelectDataSourceMode(DataSourceMode.AUTO) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("switch_to_auto_source_button"),
                            ) {
                                Text(
                                    text = "Usa Auto",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = AccentCyan,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }

        val isInspectingCard = uiState.selectedVehicle != null || uiState.selectedStop != null

        if (isWideScreen) {
            // Tablet / Foldable Layout:
            // - Inspector & stop arrivals dock cleanly to the bottom-end side panel so the map stays unobstructed
            // - Active bus pills float neatly above the system gesture bar along the bottom edge
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 74.dp)
                    .widthIn(max = 408.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End,
            ) {
                outageBannerContent(Modifier.fillMaxWidth())
                errorCardContent(Modifier.fillMaxWidth())
                inspectorCardSlot(Modifier.fillMaxWidth(), 356.dp)
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 14.dp),
            ) {
                activeBusChipsBar()
            }
        } else {
            // Smartphone Compact Layout: centered vertical stack above FloatingGlassBottomBar
            // Automatically collapses bottom bus pills while reading a Stop/Bus card to free vertical map space!
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = bottomNavClearance + 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val compactCardModifier = Modifier
                    .padding(horizontal = 16.dp)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                outageBannerContent(compactCardModifier)
                errorCardContent(compactCardModifier)
                inspectorCardSlot(compactCardModifier, 332.dp)
                AnimatedVisibility(
                    visible = !isInspectingCard,
                    enter = fadeIn(tween(200)) + expandVertically(tween(220)) + slideInVertically(tween(220)) { it / 2 },
                    exit = fadeOut(tween(150)) + shrinkVertically(tween(180)) + slideOutVertically(tween(180)) { it / 2 },
                ) {
                    activeBusChipsBar()
                }
            }
        }

        AnimatedVisibility(
            visible = showStartupOverlay,
            enter = fadeIn(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)) +
                scaleIn(initialScale = 0.97f, animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)) +
                scaleOut(targetScale = 1.02f, animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)),
            modifier = Modifier.fillMaxSize(),
        ) {
            StartupGlassLoadingOverlay(
                selectedLine = uiState.settings.selectedLine,
                isRouteReady = uiState.routePatterns.isNotEmpty() || !uiState.isLoadingInitialRoute,
                isFeedReady = uiState.lastUpdatedMillis != null || !uiState.isLoadingFeed,
                isMapReady = isMapReady,
            )
        }
    }

    if (showAlertsDialog && uiState.settings.showServiceAlerts) {
        GttAlertsDialog(
            selectedLine = uiState.settings.selectedLine,
            lineAlerts = uiState.lineAlerts,
            allAlerts = uiState.allAlerts,
            onDismiss = { showAlertsDialog = false },
        )
    }
}
