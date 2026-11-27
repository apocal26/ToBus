package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Settings
import com.example.ui.icons.ToBusIcons
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.data.network.AppUpdateChecker
import com.example.data.network.AppUpdateDownloader
import com.example.data.network.UpdateInstallState
import com.example.ui.components.LinePickerSheet
import com.example.ui.components.UpdateAvailableDialog
import java.io.File
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

import com.example.notifications.FavoriteAlertNotifier
import com.example.widget.StopArrivalsWidgetProvider

private enum class AppDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    MAP("map_screen", R.string.tab_map, ToBusIcons.MapRounded, ToBusIcons.MapOutlined),
    WIDGET("widget_studio_screen", R.string.tab_widget, Icons.Rounded.Settings, Icons.Outlined.Settings),
}

class MainActivity : ComponentActivity() {
    private val pendingOpenStopId = mutableStateOf<String?>(null)
    private val pendingOpenAlertsLine = mutableStateOf<String?>(null)
    private val pendingOpenAlertsSeq = mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeLaunchIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            ToBusTheme {
                ToBusApp(
                    pendingOpenStopId = pendingOpenStopId.value,
                    onConsumedOpenStopId = { pendingOpenStopId.value = null },
                    pendingOpenAlertsLine = pendingOpenAlertsLine.value,
                    pendingOpenAlertsSeq = pendingOpenAlertsSeq.longValue,
                    onConsumedOpenAlerts = { pendingOpenAlertsLine.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeLaunchIntent(intent)
    }

    private fun consumeLaunchIntent(intent: Intent?) {
        val stopId = intent?.getStringExtra(StopArrivalsWidgetProvider.EXTRA_OPEN_STOP_ID)?.trim()
        if (!stopId.isNullOrEmpty()) {
            pendingOpenStopId.value = stopId
            intent.removeExtra(StopArrivalsWidgetProvider.EXTRA_OPEN_STOP_ID)
        }
        val openAlerts = intent?.getBooleanExtra(FavoriteAlertNotifier.EXTRA_OPEN_ALERTS_DIALOG, false) == true
        if (openAlerts) {
            pendingOpenAlertsLine.value = intent.getStringExtra(FavoriteAlertNotifier.EXTRA_OPEN_ALERTS_LINE)?.trim()
            pendingOpenAlertsSeq.longValue += 1L
            intent.removeExtra(FavoriteAlertNotifier.EXTRA_OPEN_ALERTS_DIALOG)
            intent.removeExtra(FavoriteAlertNotifier.EXTRA_OPEN_ALERTS_LINE)
        }
    }
}

@Composable
fun ToBusApp(
    viewModel: ToBusViewModel = viewModel(),
    pendingOpenStopId: String? = null,
    onConsumedOpenStopId: () -> Unit = {},
    pendingOpenAlertsLine: String? = null,
    pendingOpenAlertsSeq: Long = 0L,
    onConsumedOpenAlerts: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val appContext = LocalContext.current
    var isForegroundActive by remember { mutableStateOf(true) }
    var currentTab by rememberSaveable { mutableStateOf(AppDestination.MAP) }
    var showLinePicker by rememberSaveable { mutableStateOf(!uiState.settings.hasSelectedLine) }
    var openAlertsRequestSeq by rememberSaveable { mutableLongStateOf(0L) }
    val snackbarHostState = remember { SnackbarHostState() }
    val rootView = LocalView.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.setNotifyFavoriteLineAlerts(true)
        } else {
            viewModel.onNotificationPermissionDenied()
        }
    }

    val handleToggleNotifyFavoriteAlerts: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            viewModel.setNotifyFavoriteLineAlerts(false)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.setNotifyFavoriteLineAlerts(true)
        }
    }

    val shouldKeepScreenOn = uiState.settings.keepScreenOn &&
        currentTab == AppDestination.MAP &&
        isForegroundActive &&
        !showLinePicker

    DisposableEffect(rootView, shouldKeepScreenOn) {
        rootView.keepScreenOn = shouldKeepScreenOn
        onDispose {
            rootView.keepScreenOn = false
        }
    }

    LaunchedEffect(pendingOpenStopId) {
        val targetStopId = pendingOpenStopId ?: return@LaunchedEffect
        currentTab = AppDestination.MAP
        showLinePicker = false
        viewModel.openFavoriteStopById(targetStopId)
        onConsumedOpenStopId()
    }

    LaunchedEffect(pendingOpenAlertsSeq) {
        if (pendingOpenAlertsSeq <= 0L) return@LaunchedEffect
        currentTab = AppDestination.MAP
        showLinePicker = false
        val line = pendingOpenAlertsLine?.trim().orEmpty()
        if (line.isNotEmpty() && !line.equals(uiState.settings.selectedLine, ignoreCase = true)) {
            viewModel.selectLine(line)
        }
        if (!uiState.settings.showServiceAlerts) {
            viewModel.setShowServiceAlerts(true)
        }
        openAlertsRequestSeq += 1L
        onConsumedOpenAlerts()
    }

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

    LaunchedEffect(showLinePicker) {
        viewModel.setLinePickerOpen(showLinePicker)
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

    val isLightMapVisible = currentTab == AppDestination.MAP && !uiState.settings.mapStyle.isDark && !showLinePicker

    val selectedLineKey = uiState.settings.selectedLine.trim().uppercase()
    val needsBlockingLoadOverlay = uiState.settings.hasSelectedLine &&
        ((uiState.isLoadingInitialRoute && uiState.routePatterns.isEmpty()) ||
            (uiState.isLoadingFeed && uiState.lastUpdatedMillis == null))

    var overlayTimedOutForKey by rememberSaveable(selectedLineKey) { mutableStateOf(false) }
    var overlayGraceElapsed by remember(selectedLineKey) { mutableStateOf(false) }

    LaunchedEffect(selectedLineKey, needsBlockingLoadOverlay) {
        if (!needsBlockingLoadOverlay) {
            overlayGraceElapsed = false
            return@LaunchedEffect
        }
        if (overlayTimedOutForKey) return@LaunchedEffect
        overlayGraceElapsed = false
        kotlinx.coroutines.delay(200L)
        overlayGraceElapsed = true
        kotlinx.coroutines.delay(8_500L)
        overlayTimedOutForKey = true
    }

    val showStartupGlassOverlay = needsBlockingLoadOverlay &&
        overlayGraceElapsed &&
        !overlayTimedOutForKey &&
        currentTab == AppDestination.MAP &&
        !showLinePicker
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(activity, isLightMapVisible) {
        activity?.enableEdgeToEdge(
            statusBarStyle = if (isLightMapVisible) {
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            } else {
                SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            },
            navigationBarStyle = if (isLightMapVisible) {
                SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
            } else {
                SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            },
        )
    }

    val openLinePicker = remember(viewModel) {
        {
            viewModel.setLinePickerOpen(true)
            showLinePicker = true
        }
    }
    val closeLinePicker = remember(viewModel) {
        {
            showLinePicker = false
            viewModel.setLinePickerOpen(false)
        }
    }
    val refreshLive = remember(viewModel) { { viewModel.refreshLiveData() } }
    val refreshStopArrivals = remember(viewModel) { { viewModel.refreshSelectedStopArrivals(silent = false) } }
    val ensureWidgetPreviewRendered = remember(viewModel) { { viewModel.regenerateWidgetPreview(force = true) } }
    val selectSearchedStop = remember(viewModel) {
        { entry: com.example.data.model.StopSearchEntry ->
            currentTab = AppDestination.MAP
            viewModel.openSearchedStop(entry)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Pure #FF000000 root + Dynamic Elliptical Ambient Glow Shader (smooth crossfade when entering Settings)
        AnimatedVisibility(
            visible = currentTab != AppDestination.MAP,
            enter = fadeIn(animationSpec = tween(durationMillis = 280)),
            exit = fadeOut(animationSpec = tween(durationMillis = 220)),
        ) {
            AmbientGlowBackground(accentColor = ambientAccent)
        }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 680.dp
            val isInspectingMapPopup = currentTab == AppDestination.MAP &&
                (uiState.selectedVehicle != null || uiState.selectedStop != null)
            val bottomClearance by animateDpAsState(
                targetValue = if (useNavigationRail || isInspectingMapPopup) 0.dp else 86.dp,
                animationSpec = tween(durationMillis = 220),
                label = "bottom_bar_clearance",
            )
            val snackbarBottomPadding by animateDpAsState(
                targetValue = when {
                    useNavigationRail -> 72.dp
                    isInspectingMapPopup -> 18.dp
                    else -> 88.dp
                },
                animationSpec = tween(durationMillis = 220),
                label = "snackbar_bottom_padding",
            )
            val leftRailClearance = if (useNavigationRail) 112.dp else 0.dp

            Box(modifier = Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = currentTab,
                    transitionSpec = {
                        if (targetState == AppDestination.WIDGET) {
                            (fadeIn(tween(240, easing = FastOutSlowInEasing)) +
                                slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 20 })
                                .togetherWith(fadeOut(tween(180)))
                        } else {
                            fadeIn(tween(220, easing = FastOutSlowInEasing))
                                .togetherWith(
                                    fadeOut(tween(170)) +
                                        slideOutVertically(tween(200)) { it / 20 },
                                )
                        }
                    },
                    label = "app_tab_transition",
                ) { destination ->
                    when (destination) {
                        AppDestination.MAP -> MapScreen(
                            uiState = uiState,
                            pollProgressFlow = viewModel.pollProgress,
                            isForegroundActive = isForegroundActive && !showLinePicker,
                            isWideScreen = useNavigationRail,
                            showStartupOverlay = showStartupGlassOverlay,
                            bottomNavClearance = bottomClearance,
                            onCameraChange = viewModel::updateCamera,
                            onViewportSizeChange = viewModel::updateViewportSize,
                            onOpenLinePicker = openLinePicker,
                            onRefresh = refreshLive,
                            onUserLocationClick = viewModel::onUserLocationButtonClicked,
                            onLocationPermissionResult = viewModel::onLocationPermissionResult,
                            onSelectVehicle = viewModel::selectVehicle,
                            onSelectStop = viewModel::selectStop,
                            onRefreshStopArrivals = refreshStopArrivals,
                            onToggleFavoriteStop = viewModel::toggleFavoriteStop,
                            onOpenFavoriteStop = viewModel::openFavoriteStop,
                            onFocusArrivalBus = viewModel::focusBusForArrival,
                            onSelectLine = viewModel::selectLine,
                            onSelectDataSourceMode = viewModel::setDataSourceMode,
                            onDismissGttOutage = viewModel::dismissGttOutageBanner,
                            openAlertsRequestSeq = openAlertsRequestSeq,
                            onAlertsDialogOpened = viewModel::markVisibleAlertsAsSeen,
                        )

                        AppDestination.WIDGET -> WidgetStudioScreen(
                            uiState = uiState,
                            bottomNavClearance = bottomClearance,
                            leftRailClearance = leftRailClearance,
                            onOpenLinePicker = openLinePicker,
                            onSelectMapStyle = viewModel::setMapStyle,
                            onSelectCameraMode = viewModel::setCameraMode,
                            onCaptureCurrentMapArea = viewModel::saveCurrentMapAsWidgetArea,
                            onToggleVehicleLabels = viewModel::setShowVehicleLabels,
                            onToggleRouteLine = viewModel::setShowRouteLine,
                            onToggleColorByDirection = viewModel::setColorByDirection,
                            onToggleHideInactiveInWidget = viewModel::setHideInactiveInWidget,
                            onToggleHideInactiveOnMap = viewModel::setHideInactiveOnMap,
                            onToggleShowServiceAlerts = viewModel::setShowServiceAlerts,
                            onToggleNotifyFavoriteLineAlerts = handleToggleNotifyFavoriteAlerts,
                            onToggleKeepScreenOn = viewModel::setKeepScreenOn,
                            onSelectDataSourceMode = viewModel::setDataSourceMode,
                            onSelectLivePollInterval = viewModel::setLivePollInterval,
                            onSelectRefreshInterval = viewModel::setRefreshInterval,
                            onSelectPreviewSize = viewModel::setPreviewSize,
                            onSyncWidgetNow = viewModel::triggerHomeWidgetSync,
                            onEnsurePreviewRendered = ensureWidgetPreviewRendered,
                            onOpenFavoriteStop = { fav ->
                                currentTab = AppDestination.MAP
                                viewModel.openFavoriteStop(fav)
                            },
                            onRemoveFavoriteStop = viewModel::removeFavoriteStop,
                            onCheckUpdates = viewModel::checkForUpdatesManually,
                            onSaveGithubRepo = viewModel::saveGithubRepoSlug,
                            onClearAppCaches = viewModel::clearAppCaches,
                            onDebugStateChanged = viewModel::syncOutageStateFromRepository,
                        )
                    }
                }

                // Floating Smoked Glass Navigation Rail over full-bleed content on Tablet / Foldable
                if (useNavigationRail) {
                    GlassNavigationRail(
                        selected = currentTab,
                        onSelect = { currentTab = it },
                        isLightMapVisible = isLightMapVisible,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                } else {
                    // Detached Floating Smoked Glass Bottom Bar on Smartphone (auto-hides while reading popups)
                    AnimatedVisibility(
                        visible = !isInspectingMapPopup,
                        enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { it },
                        exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                    ) {
                        FloatingGlassBottomBar(
                            selected = currentTab,
                            onSelect = { currentTab = it },
                            isLightMapVisible = isLightMapVisible,
                        )
                    }
                }

                // Smoked Glass Snackbar
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(
                            bottom = snackbarBottomPadding,
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

        AnimatedVisibility(
            visible = showLinePicker,
            enter = fadeIn(animationSpec = tween(durationMillis = 180)) +
                slideInVertically(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)) { it / 4 },
            exit = fadeOut(animationSpec = tween(durationMillis = 140)) +
                slideOutVertically(animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)) { it / 4 },
            modifier = Modifier.fillMaxSize(),
        ) {
            LinePickerHost(
                viewModel = viewModel,
                isInitialSetup = !uiState.settings.hasSelectedLine,
                onSelectStop = selectSearchedStop,
                onDismiss = closeLinePicker,
            )
        }

        val pendingUpdate = uiState.availableUpdate
        if (pendingUpdate != null) {
            val ctx = LocalContext.current
            val installState = uiState.updateInstallState
            val installPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult(),
            ) {
                viewModel.onInstallPermissionReturned()
            }

            var lastLaunchedInstallSeq by rememberSaveable { mutableLongStateOf(0L) }

            // Automatically trigger Android's system package installer confirmation dialog
            // once per downloaded/retriggered launchSeq (never re-triggering on rotation).
            LaunchedEffect(installState) {
                if (installState is UpdateInstallState.ReadyToInstall &&
                    installState.launchSeq != lastLaunchedInstallSeq
                ) {
                    val apkFile = File(installState.apkFilePath)
                    if (apkFile.exists()) {
                        lastLaunchedInstallSeq = installState.launchSeq
                        runCatching {
                            ctx.startActivity(AppUpdateDownloader.buildInstallApkIntent(ctx, apkFile))
                        }
                    }
                }
            }

            UpdateAvailableDialog(
                update = pendingUpdate,
                installState = installState,
                onDownloadClick = {
                    if (pendingUpdate.apkDownloadUrl != null) {
                        viewModel.startInAppUpdateDownload()
                    } else {
                        val url = pendingUpdate.preferredOpenUrl
                        if (AppUpdateChecker.isTrustedGithubUrl(url)) {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    },
                                )
                            }
                        }
                        viewModel.dismissUpdateDialog()
                    }
                },
                onCancelDownload = viewModel::cancelUpdateDownload,
                onOpenInstallPermission = {
                    runCatching {
                        installPermLauncher.launch(AppUpdateDownloader.buildUnknownSourcesSettingsIntent(ctx))
                    }
                },
                onInstallNow = viewModel::retriggerReadyApkInstall,
                onOpenBrowserFallback = {
                    val url = pendingUpdate.preferredOpenUrl
                    if (AppUpdateChecker.isTrustedGithubUrl(url)) {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                },
                            )
                        }
                    }
                    viewModel.dismissUpdateDialog()
                },
                onDismissLater = viewModel::dismissUpdateDialog,
                onIgnoreVersion = viewModel::ignoreCurrentUpdateVersion,
            )
        }
    }
}

@Composable
private fun LinePickerHost(
    viewModel: ToBusViewModel,
    isInitialSetup: Boolean,
    onSelectStop: (com.example.data.model.StopSearchEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val pickerState by viewModel.linePickerState.collectAsStateWithLifecycle()
    LinePickerSheet(
        selectedLine = pickerState.selectedLine,
        activeLines = pickerState.activeLines,
        favoriteLines = pickerState.favoriteLines,
        stopSearchResults = pickerState.stopSearchResults,
        isLoadingStopIndex = pickerState.isLoadingStopIndex,
        isLoadingLines = pickerState.isLoadingLines,
        lineCatalogError = pickerState.lineCatalogError,
        userLocation = pickerState.userLocation,
        onSelectLine = viewModel::selectLine,
        onToggleFavoriteLine = viewModel::toggleFavoriteLine,
        onSearchStops = viewModel::searchStops,
        onSelectStop = onSelectStop,
        onRetryLoadLines = { viewModel.ensureLineCatalogLoaded(force = true) },
        onDismiss = onDismiss,
        isInitialSetup = isInitialSetup,
    )
}

private val NavSliderShape = RoundedCornerShape(999.dp)
private val NavRailShape = RoundedCornerShape(28.dp)
private val NavRailSliderShape = RoundedCornerShape(20.dp)
private val NavSliderBrush = Brush.verticalGradient(
    colors = listOf(GlassSliderTop, GlassSliderMid, GlassSliderBottom),
)
private val NavGlassRimBrush = com.example.ui.glass.liquidGlassRimBrush(
    topAlpha = 0.45f,
    midAlpha = 0.16f,
    bottomAlpha = 0.25f,
)

@Composable
private fun FloatingGlassBottomBar(
    selected: AppDestination,
    onSelect: (AppDestination) -> Unit,
    isLightMapVisible: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val entries = AppDestination.entries
    val selectedIdx = entries.indexOf(selected).coerceAtLeast(0)

    Surface(
        modifier = modifier
            .width(284.dp)
            .height(60.dp),
        shape = NavSliderShape,
        color = if (isLightMapVisible) com.example.ui.theme.OverlayPillLightMapFill else SmokedGlassFill,
        border = BorderStroke(1.2.dp, NavGlassRimBrush),
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
                    .background(NavSliderBrush, NavSliderShape)
                    .border(1.5.dp, GlassSliderBorder, NavSliderShape),
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
    isLightMapVisible: Boolean = false,
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
            shape = NavRailShape,
            color = if (isLightMapVisible) com.example.ui.theme.OverlayPillLightMapFill else SmokedGlassFill,
            border = BorderStroke(1.2.dp, NavGlassRimBrush),
        ) {
            Box(modifier = Modifier.padding(8.dp)) {
                Box(
                    modifier = Modifier
                        .offset(y = indicatorY)
                        .fillMaxWidth()
                        .height(itemHeight)
                        .background(NavSliderBrush, NavRailSliderShape)
                        .border(1.5.dp, GlassSliderBorder, NavRailSliderShape),
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
