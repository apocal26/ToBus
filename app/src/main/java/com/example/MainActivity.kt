package com.example

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.MapStyle
import com.example.data.model.VehicleFreshness
import com.example.ui.components.LinePickerSheet
import com.example.ui.glass.AmbientGlowBackground
import com.example.ui.glass.glassSpring
import com.example.ui.screens.MapScreen
import com.example.ui.screens.WidgetStudioScreen
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentEmerald
import com.example.ui.theme.AccentGold
import com.example.ui.theme.AccentRed
import com.example.ui.theme.GlassSliderBorder
import com.example.ui.theme.GlassSliderBottom
import com.example.ui.theme.GlassSliderMid
import com.example.ui.theme.GlassSliderTop
import com.example.ui.theme.SmokedGlassBorder
import com.example.ui.theme.SmokedGlassFill
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import com.example.ui.theme.ToBusTheme
import com.example.ui.viewmodel.ToBusViewModel

private enum class AppDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    MAP("map_screen", R.string.tab_map, Icons.Rounded.Map, Icons.Outlined.Map),
    WIDGET("widget_studio_screen", R.string.tab_widget, Icons.Rounded.Settings, Icons.Outlined.Settings),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            ToBusTheme {
                ToBusApp()
            }
        }
    }
}

@Composable
fun ToBusApp(
    viewModel: ToBusViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var isForegroundActive by remember { mutableStateOf(true) }
    var currentTab by rememberSaveable { mutableStateOf(AppDestination.MAP) }
    var showLinePicker by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Energy efficiency: stop polling, clocks & animations automatically when app goes to background
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    isForegroundActive = true
                    viewModel.setForegroundActive(true)
                }
                Lifecycle.Event.ON_STOP -> {
                    isForegroundActive = false
                    viewModel.setForegroundActive(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setForegroundActive(false)
        }
    }

    BackHandler(enabled = currentTab != AppDestination.MAP) {
        currentTab = AppDestination.MAP
    }

    LaunchedEffect(currentTab) {
        viewModel.setStudioTabActive(currentTab == AppDestination.WIDGET)
    }

    LaunchedEffect(uiState.statusToast) {
        val message = uiState.statusToast ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearToast()
    }

    // Dynamic Ambient Glow accent shifts smoothly (500ms) with active screen / bus health / stop / map style
    val ambientAccent: Color = when {
        currentTab == AppDestination.MAP && uiState.selectedVehicle != null -> {
            when (uiState.selectedVehicle!!.freshness(uiState.nowMillis)) {
                VehicleFreshness.LIVE -> AccentEmerald
                VehicleFreshness.SLOW -> AccentGold
                VehicleFreshness.STALE -> AccentRed
            }
        }
        currentTab == AppDestination.MAP && uiState.selectedStop != null -> {
            if (uiState.settings.colorByDirection && uiState.selectedStop!!.directionId == 1) {
                AccentCyan
            } else {
                AccentGold
            }
        }
        currentTab == AppDestination.MAP -> AccentGold
        else -> when (uiState.settings.mapStyle) {
            MapStyle.DARK -> AccentCyan
            MapStyle.POSITRON -> Color(0xFF60A5FA)
            MapStyle.VOYAGER -> AccentGold
            MapStyle.SATELLITE -> AccentEmerald
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Pure #FF000000 root + Dynamic Elliptical Ambient Glow Shader (only drawn when visible, skipping hidden overdraw behind full-bleed map)
        if (currentTab != AppDestination.MAP) {
            AmbientGlowBackground(accentColor = ambientAccent)
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 680.dp
            val bottomClearance = if (useNavigationRail) 0.dp else 86.dp
            val leftRailClearance = if (useNavigationRail) 112.dp else 0.dp

            Box(modifier = Modifier.fillMaxSize()) {
                when (currentTab) {
                    AppDestination.MAP -> MapScreen(
                        uiState = uiState,
                        isForegroundActive = isForegroundActive,
                        isWideScreen = useNavigationRail,
                        bottomNavClearance = bottomClearance,
                        onCameraChange = viewModel::updateCamera,
                        onViewportSizeChange = viewModel::updateViewportSize,
                        onOpenLinePicker = { showLinePicker = true },
                        onRefresh = { viewModel.refreshLiveData() },
                        onUserLocationClick = viewModel::onUserLocationButtonClicked,
                        onLocationPermissionResult = viewModel::onLocationPermissionResult,
                        onSelectVehicle = viewModel::selectVehicle,
                        onSelectStop = viewModel::selectStop,
                        onRefreshStopArrivals = { viewModel.refreshSelectedStopArrivals(silent = false) },
                        onToggleFavoriteStop = viewModel::toggleFavoriteStop,
                        onOpenFavoriteStop = viewModel::openFavoriteStop,
                        onFocusArrivalBus = viewModel::focusBusForArrival,
                        onSelectLine = viewModel::selectLine,
                    )

                    AppDestination.WIDGET -> WidgetStudioScreen(
                        uiState = uiState,
                        bottomNavClearance = bottomClearance,
                        leftRailClearance = leftRailClearance,
                        onOpenLinePicker = { showLinePicker = true },
                        onSelectMapStyle = viewModel::setMapStyle,
                        onSelectCameraMode = viewModel::setCameraMode,
                        onCaptureCurrentMapArea = viewModel::saveCurrentMapAsWidgetArea,
                        onToggleVehicleLabels = viewModel::setShowVehicleLabels,
                        onToggleRouteLine = viewModel::setShowRouteLine,
                        onToggleColorByDirection = viewModel::setColorByDirection,
                        onToggleHideInactiveInWidget = viewModel::setHideInactiveInWidget,
                        onSelectRefreshInterval = viewModel::setRefreshInterval,
                        onSelectPreviewSize = viewModel::setPreviewSize,
                        onSyncWidgetNow = viewModel::triggerHomeWidgetSync,
                        onEnsurePreviewRendered = { viewModel.regenerateWidgetPreview(force = true) },
                        onOpenFavoriteStop = { fav ->
                            currentTab = AppDestination.MAP
                            viewModel.openFavoriteStop(fav)
                        },
                        onRemoveFavoriteStop = viewModel::removeFavoriteStop,
                    )
                }

                // Floating Smoked Glass Navigation Rail over full-bleed content on Tablet / Foldable
                if (useNavigationRail) {
                    GlassNavigationRail(
                        selected = currentTab,
                        onSelect = { currentTab = it },
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                } else {
                    // Detached Floating Smoked Glass Bottom Bar on Smartphone
                    FloatingGlassBottomBar(
                        selected = currentTab,
                        onSelect = { currentTab = it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    )
                }

                // Smoked Glass Snackbar
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(
                            bottom = if (useNavigationRail) 72.dp else 88.dp,
                            start = if (useNavigationRail) leftRailClearance else 16.dp,
                            end = 16.dp,
                        ),
                ) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(999.dp),
                        containerColor = SmokedGlassFill,
                        contentColor = TextPrimary,
                    )
                }
            }
        }
    }

    if (showLinePicker) {
        LinePickerSheet(
            selectedLine = uiState.settings.selectedLine,
            activeLines = uiState.allActiveLines,
            stopSearchResults = uiState.stopSearchResults,
            isLoadingStopIndex = uiState.isLoadingStopIndex,
            userLocation = uiState.userLocation,
            onSelectLine = viewModel::selectLine,
            onSearchStops = viewModel::searchStops,
            onSelectStop = { entry ->
                currentTab = AppDestination.MAP
                viewModel.openSearchedStop(entry)
            },
            onDismiss = { showLinePicker = false },
        )
    }
}

@Composable
private fun FloatingGlassBottomBar(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = AppDestination.entries
    val selectedIdx = entries.indexOf(selected).coerceAtLeast(0)

    Surface(
        modifier = modifier
            .width(284.dp)
            .height(60.dp),
        shape = RoundedCornerShape(999.dp),
        color = SmokedGlassFill,
        border = BorderStroke(1.2.dp, SmokedGlassBorder),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(5.dp),
        ) {
            val slotW = maxWidth / entries.size
            val indicatorX by animateDpAsState(
                targetValue = slotW * selectedIdx,
                animationSpec = glassSpring(),
                label = "bottom_nav_slider_x",
            )

            // Sliding Glass Pill with vertical reflection & bright luminous border
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(slotW)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(GlassSliderTop, GlassSliderMid, GlassSliderBottom),
                        ),
                    )
                    .border(1.5.dp, GlassSliderBorder, RoundedCornerShape(999.dp)),
            )

            Row(modifier = Modifier.fillMaxSize()) {
                entries.forEach { dest ->
                    val isSelected = dest == selected
                    val label = stringResource(dest.labelRes)
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag("nav_${dest.route}")
                            .clickable(
                                interactionSource = interaction,
                                indication = null,
                            ) { onSelect(dest) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (isSelected) dest.selectedIcon else dest.unselectedIcon,
                            contentDescription = label,
                            tint = if (isSelected) TextPrimary else TextSubtitle,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = label,
                            style = if (isSelected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                            color = if (isSelected) TextPrimary else TextSubtitle,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassNavigationRail(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = AppDestination.entries
    val selectedIdx = entries.indexOf(selected).coerceAtLeast(0)
    val itemHeight = 62.dp
    val itemSpacing = 8.dp
    val indicatorY by animateDpAsState(
        targetValue = (itemHeight + itemSpacing) * selectedIdx,
        animationSpec = glassSpring(),
        label = "rail_slider_y",
    )

    Box(
        modifier = modifier
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(88.dp),
            shape = RoundedCornerShape(28.dp),
            color = SmokedGlassFill,
            border = BorderStroke(1.2.dp, SmokedGlassBorder),
        ) {
            Box(modifier = Modifier.padding(8.dp)) {
                Box(
                    modifier = Modifier
                        .offset(y = indicatorY)
                        .fillMaxWidth()
                        .height(itemHeight)
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(GlassSliderTop, GlassSliderMid, GlassSliderBottom),
                            ),
                        )
                        .border(1.5.dp, GlassSliderBorder, RoundedCornerShape(20.dp)),
                )

                Column(verticalArrangement = Arrangement.spacedBy(itemSpacing)) {
                    entries.forEach { dest ->
                        val isSelected = dest == selected
                        val label = stringResource(dest.labelRes)
                        val interaction = remember { MutableInteractionSource() }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                                .testTag("nav_rail_${dest.route}")
                                .clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                ) { onSelect(dest) },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                imageVector = if (isSelected) dest.selectedIcon else dest.unselectedIcon,
                                contentDescription = label,
                                tint = if (isSelected) TextPrimary else TextSubtitle,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) TextPrimary else TextSubtitle,
                            )
                        }
                    }
                }
            }
        }
    }
}
