package com.example.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.SizeF
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.LiveFeedSnapshot
import com.example.data.model.RefreshInterval
import com.example.data.model.RoutePattern
import com.example.data.network.GttRealtimeRepository
import com.example.data.preferences.ToBusPreferences
import com.example.map.TransitMapBitmapRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

open class BusMapWidgetProvider : AppWidgetProvider() {

    protected open val isSquareVariant: Boolean = false

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return
        val appContext = context.applicationContext
        val interval = ToBusPreferences.getInstance(appContext).currentSettings().refreshInterval
        schedulePeriodicUpdates(appContext, interval)

        val pendingResult = goAsync()
        val square = isSquareVariant
        widgetScope.launch {
            try {
                val (snapshot, failed, patterns) = fetchWidgetDataFast(appContext, useCachedOnly = false)
                for (widgetId in appWidgetIds) {
                    updateSingleWidget(
                        context = appContext,
                        appWidgetManager = appWidgetManager,
                        appWidgetId = widgetId,
                        isSquare = square,
                        preloadedSnapshot = snapshot,
                        preloadedPatterns = patterns,
                        fetchFailed = failed,
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val appContext = context.applicationContext
        val interval = ToBusPreferences.getInstance(appContext).currentSettings().refreshInterval
        schedulePeriodicUpdates(appContext, interval)

        val pendingResult = goAsync()
        val square = isSquareVariant
        widgetScope.launch {
            try {
                updateSingleWidget(appContext, appWidgetManager, appWidgetId, newOptions, isSquare = square)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val appContext = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val interval = ToBusPreferences.getInstance(appContext).currentSettings().refreshInterval
                schedulePeriodicUpdates(appContext, interval)
                val isPackageReplaced = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
                val pendingResult = goAsync()
                widgetScope.launch {
                    try {
                        if (isPackageReplaced) {
                            com.example.data.network.AppUpdateDownloader.cleanupOldUpdates(appContext)
                        }
                        refreshAllInstalledWidgets(appContext, showLoadingFeedback = false, useCachedOnly = false)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_WIDGET_REFRESH -> {
                val manual = intent.getBooleanExtra(EXTRA_MANUAL_TAP, false)
                // Always re-arm the next self-rescheduling alarm first so a skipped or failed tick never breaks updates
                val interval = ToBusPreferences.getInstance(appContext).currentSettings().refreshInterval
                schedulePeriodicUpdates(appContext, interval)

                // Energy saving: skip automatic background alarm refresh if Battery Saver is active
                if (!manual) {
                    val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm?.isPowerSaveMode == true) return
                }

                val pendingResult = goAsync()
                widgetScope.launch {
                    try {
                        refreshAllInstalledWidgets(appContext, showLoadingFeedback = manual, useCachedOnly = false)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val interval = ToBusPreferences.getInstance(context).currentSettings().refreshInterval
        schedulePeriodicUpdates(context, interval)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val manager = AppWidgetManager.getInstance(context)
        val wideIds = manager.getAppWidgetIds(ComponentName(context, BusMapWidgetProvider::class.java))
        val squareIds = manager.getAppWidgetIds(ComponentName(context, BusMapWidgetSquareProvider::class.java))
        val stopIds = manager.getAppWidgetIds(ComponentName(context, StopArrivalsWidgetProvider::class.java))
        val alertIds = manager.getAppWidgetIds(ComponentName(context, FavoriteAlertsWidgetProvider::class.java))
        if (wideIds.isEmpty() && squareIds.isEmpty() && stopIds.isEmpty() && alertIds.isEmpty()) {
            cancelPeriodicUpdates(context)
        }
    }

    companion object {
        private const val TAG = "BusMapWidgetProvider"
        const val ACTION_WIDGET_REFRESH = "com.example.tobus.ACTION_WIDGET_REFRESH"
        private const val EXTRA_MANUAL_TAP = "extra_manual_tap"
        private const val PERIODIC_REQUEST_CODE = 4041
        private const val WIDGET_NETWORK_TIMEOUT_MS = 6_800L
        private const val WIDGET_RENDER_TIMEOUT_MS = 3_000L

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private data class WidgetFetchBundle(
            val snapshot: LiveFeedSnapshot?,
            val fetchFailed: Boolean,
            val patterns: List<RoutePattern>,
        )

        fun requestUpdateAllWidgets(context: Context) {
            val appContext = context.applicationContext
            widgetScope.launch {
                refreshAllInstalledWidgets(appContext, showLoadingFeedback = false, useCachedOnly = false)
            }
        }

        /**
         * Zero-network fast widget sync triggered when the foreground app just downloaded fresh live data.
         */
        fun syncWidgetsWithCachedSnapshot(context: Context) {
            val appContext = context.applicationContext
            val interval = ToBusPreferences.getInstance(appContext).currentSettings().refreshInterval
            schedulePeriodicUpdates(appContext, interval)
            widgetScope.launch {
                refreshAllInstalledWidgets(appContext, showLoadingFeedback = false, useCachedOnly = true)
            }
        }

        private suspend fun fetchWidgetDataFast(
            context: Context,
            useCachedOnly: Boolean,
        ): WidgetFetchBundle {
            val settings = ToBusPreferences.getInstance(context).currentSettings()
            val repo = GttRealtimeRepository.getInstance(context)
            val lineCode = settings.selectedLine.trim().uppercase()
            if (lineCode.isEmpty()) {
                return WidgetFetchBundle(snapshot = null, fetchFailed = false, patterns = emptyList())
            }

            if (useCachedOnly) {
                val cached = repo.getLastCachedSnapshot()
                if (cached != null) {
                    val patterns = if (settings.showRouteLine) {
                        withTimeoutOrNull(2_500L) {
                            runCatching { repo.fetchRoutePatterns(lineCode) }.getOrDefault(emptyList())
                        }.orEmpty()
                    } else {
                        emptyList()
                    }
                    return WidgetFetchBundle(snapshot = cached, fetchFailed = false, patterns = patterns)
                }
            }

            return coroutineScope {
                val feedDeferred = async {
                    withTimeoutOrNull(WIDGET_NETWORK_TIMEOUT_MS) {
                        repo.fetchLiveVehicles(
                            includeSecondaryFeeds = false,
                            widgetFastMode = true,
                            focusLine = lineCode,
                            dataSourceMode = settings.dataSourceMode,
                            includeAlerts = false,
                        )
                    }
                }
                val patternsDeferred = async {
                    if (settings.showRouteLine) {
                        withTimeoutOrNull(3_500L) {
                            runCatching { repo.fetchRoutePatterns(lineCode) }.getOrDefault(emptyList())
                        }.orEmpty()
                    } else {
                        emptyList()
                    }
                }
                val feedRes = feedDeferred.await()
                val patterns = patternsDeferred.await()
                val snapshot = feedRes?.getOrNull() ?: repo.getLastCachedSnapshot()
                val failed = feedRes == null || feedRes.isFailure
                WidgetFetchBundle(snapshot = snapshot, fetchFailed = failed, patterns = patterns)
            }
        }

        private suspend fun refreshAllInstalledWidgets(
            context: Context,
            showLoadingFeedback: Boolean,
            useCachedOnly: Boolean,
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val wideIds = manager.getAppWidgetIds(ComponentName(context, BusMapWidgetProvider::class.java))
            val squareIds = manager.getAppWidgetIds(ComponentName(context, BusMapWidgetSquareProvider::class.java))
            val stopIds = manager.getAppWidgetIds(ComponentName(context, StopArrivalsWidgetProvider::class.java))
            val alertIds = manager.getAppWidgetIds(ComponentName(context, FavoriteAlertsWidgetProvider::class.java))
            if (wideIds.isEmpty() && squareIds.isEmpty() && stopIds.isEmpty() && alertIds.isEmpty()) return

            if (showLoadingFeedback) {
                wideIds.forEach { id ->
                    val loading = RemoteViews(context.packageName, R.layout.widget_bus_map)
                    loading.setTextViewText(R.id.widget_status_text, context.getString(R.string.widget_loading))
                    manager.partiallyUpdateAppWidget(id, loading)
                }
                squareIds.forEach { id ->
                    val loading = RemoteViews(context.packageName, R.layout.widget_bus_map_square)
                    loading.setTextViewText(R.id.widget_status_text, "…")
                    manager.partiallyUpdateAppWidget(id, loading)
                }
            }

            // Single shared fast fetch for all map widgets (well within Android's 10s BroadcastReceiver limit)
            val hasMapWidgets = wideIds.isNotEmpty() || squareIds.isNotEmpty()
            if (hasMapWidgets) {
                val (snapshot, fetchFailed, patterns) = fetchWidgetDataFast(context, useCachedOnly = useCachedOnly)
                for (id in wideIds) {
                    updateSingleWidget(
                        context = context,
                        appWidgetManager = manager,
                        appWidgetId = id,
                        isSquare = false,
                        preloadedSnapshot = snapshot,
                        preloadedPatterns = patterns,
                        fetchFailed = fetchFailed,
                    )
                }
                for (id in squareIds) {
                    updateSingleWidget(
                        context = context,
                        appWidgetManager = manager,
                        appWidgetId = id,
                        isSquare = true,
                        preloadedSnapshot = snapshot,
                        preloadedPatterns = patterns,
                        fetchFailed = fetchFailed,
                    )
                }
            }

            if (stopIds.isNotEmpty()) {
                StopArrivalsWidgetProvider.refreshAllStopWidgets(context, showLoading = showLoadingFeedback)
            }
            if (alertIds.isNotEmpty()) {
                val cachedAlerts = if (useCachedOnly) {
                    GttRealtimeRepository.getInstance(context).getLastCachedSnapshot()?.alerts
                } else {
                    null
                }
                FavoriteAlertsWidgetProvider.refreshAllAlertWidgets(
                    context = context,
                    showLoading = showLoadingFeedback,
                    preloadedAlerts = cachedAlerts,
                )
            }
        }

        fun schedulePeriodicUpdates(context: Context, interval: RefreshInterval) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = buildPeriodicPendingIntent(appContext)
            alarmManager.cancel(pi)

            if (interval == RefreshInterval.MANUAL) return

            val manager = AppWidgetManager.getInstance(appContext)
            val hasWide = manager.getAppWidgetIds(ComponentName(appContext, BusMapWidgetProvider::class.java)).isNotEmpty()
            val hasSquare = manager.getAppWidgetIds(ComponentName(appContext, BusMapWidgetSquareProvider::class.java)).isNotEmpty()
            val hasStops = manager.getAppWidgetIds(ComponentName(appContext, StopArrivalsWidgetProvider::class.java)).isNotEmpty()
            val hasAlerts = manager.getAppWidgetIds(ComponentName(appContext, FavoriteAlertsWidgetProvider::class.java)).isNotEmpty()
            if (!hasWide && !hasSquare && !hasStops && !hasAlerts) return

            val intervalMillis = interval.minutes * 60_000L
            val triggerAtMillis = SystemClock.elapsedRealtime() + intervalMillis

            // Self-rescheduling non-wakeup alarm: never wakes a sleeping screen, requires no exact-alarm
            // permission, and uses setAndAllowWhileIdle on Android M+ so Doze batching doesn't starve updates.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME,
                        triggerAtMillis,
                        pi,
                    )
                } else {
                    val windowMillis = (intervalMillis / 3L).coerceIn(15_000L, 60_000L)
                    alarmManager.setWindow(
                        AlarmManager.ELAPSED_REALTIME,
                        triggerAtMillis,
                        windowMillis,
                        pi,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback AlarmManager.set: ${e.message}")
                alarmManager.set(AlarmManager.ELAPSED_REALTIME, triggerAtMillis, pi)
            }
        }

        fun cancelPeriodicUpdates(context: Context) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarmManager.cancel(buildPeriodicPendingIntent(appContext))
        }

        private fun buildPeriodicPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, BusMapWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_REFRESH
                putExtra(EXTRA_MANUAL_TAP, false)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, PERIODIC_REQUEST_CODE, intent, flags)
        }

        private suspend fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            providedOptions: Bundle? = null,
            isSquare: Boolean = false,
            preloadedSnapshot: LiveFeedSnapshot? = null,
            preloadedPatterns: List<RoutePattern>? = null,
            fetchFailed: Boolean = false,
        ) {
            val prefs = ToBusPreferences.getInstance(context)
            val settings = prefs.currentSettings()
            val repo = GttRealtimeRepository.getInstance(context)

            val options = providedOptions ?: appWidgetManager.getAppWidgetOptions(appWidgetId)
            val density = context.resources.displayMetrics.density.coerceIn(2.0f, 3.0f)
            val isLandscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            val defaultW = if (isSquare) 160 else 300
            val defaultH = if (isSquare) 160 else 170
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, defaultW).coerceAtLeast(110)
            val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW).coerceAtLeast(minW)
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, defaultH).coerceAtLeast(110)
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH).coerceAtLeast(minH)

            // Match the REAL displayed aspect ratio for the current orientation so ImageView centerCrop NEVER slices off the route!
            var targetDpW = if (isLandscape) maxW else minW
            var targetDpH = if (isLandscape) minH else maxH

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                if (!sizes.isNullOrEmpty()) {
                    val chosen = if (isLandscape) {
                        sizes.maxByOrNull { it.width }
                    } else {
                        sizes.minByOrNull { it.width }
                    }
                    if (chosen != null && chosen.width >= 90f && chosen.height >= 90f) {
                        targetDpW = chosen.width.toInt()
                        targetDpH = chosen.height.toInt()
                    }
                }
            }

            targetDpW = targetDpW.coerceIn(120, 560)
            targetDpH = targetDpH.coerceIn(120, 480)
            val compactVisual = isSquare || (targetDpW <= 200 && targetDpH <= 210)

            // Render at crisp HiDPI (2.5x–3x) while respecting RemoteViews Binder IPC pixel budget (~850k px = ~3.4MB)
            val desiredScale = if (compactVisual) {
                maxOf(density, 580f / minOf(targetDpW, targetDpH).toFloat()).coerceAtMost(3.5f)
            } else {
                density.coerceIn(2.25f, 3.0f)
            }
            var bitmapW = (targetDpW * desiredScale).toInt().coerceAtLeast(360)
            var bitmapH = (targetDpH * desiredScale).toInt().coerceAtLeast(320)
            val maxPixels = 850_000.0
            val totalPixels = bitmapW.toDouble() * bitmapH.toDouble()
            val effectiveDensity = if (totalPixels > maxPixels) {
                val factor = kotlin.math.sqrt(maxPixels / totalPixels)
                bitmapW = (bitmapW * factor).toInt().coerceAtLeast(320)
                bitmapH = (bitmapH * factor).toInt().coerceAtLeast(280)
                (desiredScale * factor).toFloat()
            } else {
                desiredScale
            }
            val renderDensity = effectiveDensity.coerceIn(1.75f, 3.0f)

            val lineCode = settings.selectedLine.trim().uppercase()
            val snapshot: LiveFeedSnapshot?
            val failed: Boolean
            val patterns: List<RoutePattern>
            if (lineCode.isEmpty()) {
                snapshot = null
                failed = false
                patterns = emptyList()
            } else if (preloadedSnapshot != null || fetchFailed) {
                snapshot = preloadedSnapshot ?: repo.getLastCachedSnapshot()
                failed = fetchFailed
                patterns = preloadedPatterns ?: if (settings.showRouteLine) {
                    withTimeoutOrNull(2_500L) {
                        runCatching { repo.fetchRoutePatterns(lineCode) }
                            .getOrDefault(emptyList())
                    }.orEmpty()
                } else {
                    emptyList()
                }
            } else {
                val bundle = fetchWidgetDataFast(context, useCachedOnly = false)
                snapshot = bundle.snapshot
                failed = bundle.fetchFailed
                patterns = bundle.patterns
            }

            var vehicles = if (lineCode.isEmpty()) {
                emptyList()
            } else {
                snapshot?.allVehicles?.filter {
                    it.lineCode.equals(lineCode, ignoreCase = true) ||
                        it.rawRouteId.equals(lineCode, ignoreCase = true)
                }.orEmpty()
            }
            var effectiveTimestampMillis: Long? = snapshot?.fetchedAtMillis
            var usedDiskCacheFallback = false

            // Protect against process-death + Doze cold-wake timeouts or transient single-source empty glitches:
            // if live fetch failed OR returned 0 vehicles on the selected line while not all feeds answered cleanly,
            // fall back to fresh (<12 min) disk-cached vehicles rather than wiping the widget to "0 mezzi".
            val onlineSourceCount = snapshot?.sourceStatuses?.count { it.isOnline } ?: 0
            if (vehicles.isEmpty() && (failed || snapshot == null || onlineSourceCount < 2)) {
                val diskCached = repo.loadFreshWidgetLineVehicles(lineCode)
                if (diskCached != null && diskCached.second.isNotEmpty()) {
                    effectiveTimestampMillis = diskCached.first
                    vehicles = diskCached.second
                    usedDiskCacheFallback = true
                }
            }

            val favorites = prefs.currentFavoriteStops()
            val nowMillis = System.currentTimeMillis()
            vehicles = GttFleetAnalyzer.filterOutConfirmedMatoPhantoms(vehicles, patterns, nowMillis)
            val displayedVehiclesCount = if (settings.hideInactiveInWidget) {
                vehicles.count { bus ->
                    val health = GttFleetAnalyzer.assess(bus, patterns, nowMillis)
                    !health.isInDepot && !health.isStaleGps
                }
            } else {
                vehicles.size
            }

            val mapBitmap = withTimeoutOrNull(WIDGET_RENDER_TIMEOUT_MS) {
                runCatching {
                    TransitMapBitmapRenderer.renderWidgetMap(
                        context = context,
                        widthPx = bitmapW,
                        heightPx = bitmapH,
                        density = renderDensity,
                        settings = settings,
                        vehicles = vehicles,
                        routePatterns = patterns,
                        favoriteStops = favorites,
                        isCompactSquare = compactVisual,
                    )
                }.getOrNull()
            }

            val layoutRes = if (compactVisual) R.layout.widget_bus_map_square else R.layout.widget_bus_map
            val views = RemoteViews(context.packageName, layoutRes)

            if (mapBitmap != null) {
                views.setImageViewBitmap(R.id.widget_map_image, mapBitmap)
            }

            views.setTextViewText(R.id.widget_line_badge, lineCode.ifEmpty { "—" })

            val statusLabel = when {
                lineCode.isEmpty() -> if (compactVisual) "—" else "Scegli linea"
                failed && snapshot == null && !usedDiskCacheFallback -> context.getString(R.string.widget_error)
                compactVisual -> "$displayedVehiclesCount"
                displayedVehiclesCount == 0 -> context.getString(R.string.widget_no_buses)
                displayedVehiclesCount == 1 -> "1 mezzo"
                else -> "$displayedVehiclesCount mezzi"
            }
            views.setTextViewText(R.id.widget_status_text, statusLabel)

            val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALY)
            val timestampText = if (effectiveTimestampMillis != null) {
                timeFormat.format(Date(effectiveTimestampMillis))
            } else {
                "--:--"
            }
            views.setTextViewText(R.id.widget_time_pill, timestampText)

            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                context,
                appWidgetId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_map_image, openPending)
            views.setOnClickPendingIntent(R.id.widget_header_pill, openPending)

            val refreshIntent = Intent(context, BusMapWidgetProvider::class.java).apply {
                action = ACTION_WIDGET_REFRESH
                putExtra(EXTRA_MANUAL_TAP, true)
            }
            val refreshPending = PendingIntent.getBroadcast(
                context,
                appWidgetId + 1000,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_btn, refreshPending)

            try {
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } catch (e: Exception) {
                Log.e(TAG, "Errore aggiornamento widget $appWidgetId: ${e.message}")
            }
        }

        fun requestPinWidget(context: Context, square: Boolean = false): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val manager = AppWidgetManager.getInstance(context)
            if (!manager.isRequestPinAppWidgetSupported) return false
            val targetClass = if (square) BusMapWidgetSquareProvider::class.java else BusMapWidgetProvider::class.java
            val provider = ComponentName(context, targetClass)
            return manager.requestPinAppWidget(provider, null, null)
        }
    }
}
