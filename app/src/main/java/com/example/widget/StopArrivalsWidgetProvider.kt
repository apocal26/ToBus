package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.model.FavoriteStop
import com.example.data.model.StopArrival
import com.example.data.model.isGenericDirectionLabel
import com.example.data.model.shortTurinPlaceName
import com.example.data.network.DiagCategory
import com.example.data.network.DiagSeverity
import com.example.data.network.GttRealtimeRepository
import com.example.data.network.ToBusDiagnosticsLog
import com.example.data.preferences.ToBusPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-board Favorite Stop Home Screen Widget ("ToBus · Fermate Live"):
 * - Focuses on ONE favorite stop per widget instance (mirroring the in-app Stop popup) so ALL
 *   incoming lines at that stop are clearly separated in a scrollable Dark Glass list.
 * - Each placed widget tracks its own active favorite stop index independently; when 2+ favorite
 *   stops exist, a compact header switcher ("1/3 ›") lets the user cycle between stops in one tap.
 * - Tapping the header or any line row opens ToBus directly centered on that stop.
 */
class StopArrivalsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return
        val appContext = context.applicationContext
        val interval = ToBusPreferences.getInstance(appContext).currentSettings().refreshInterval
        BusMapWidgetProvider.schedulePeriodicUpdates(appContext, interval)

        val pendingResult = goAsync()
        widgetScope.launch {
            try {
                refreshAllStopWidgets(appContext, showLoading = false)
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
        val pendingResult = goAsync()
        widgetScope.launch {
            try {
                refreshAllStopWidgets(appContext, showLoading = false, onlyWidgetId = appWidgetId)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val prefs = ToBusPreferences.getInstance(context.applicationContext)
        for (id in appWidgetIds) {
            prefs.clearStopWidgetIndex(id)
            StopWidgetRowCache.clearWidget(context.applicationContext, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_STOP_WIDGET_REFRESH -> {
                val interval = ToBusPreferences.getInstance(appContext).currentSettings().refreshInterval
                BusMapWidgetProvider.schedulePeriodicUpdates(appContext, interval)
                val targetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                ).takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }

                val pendingResult = goAsync()
                widgetScope.launch {
                    try {
                        refreshAllStopWidgets(appContext, showLoading = true, onlyWidgetId = targetId)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }

            ACTION_STOP_WIDGET_NEXT_FAV -> {
                val targetId = intent.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID,
                )
                val pendingResult = goAsync()
                widgetScope.launch {
                    try {
                        val prefs = ToBusPreferences.getInstance(appContext)
                        if (targetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                            prefs.cycleStopWidgetIndex(targetId, +1)
                        } else {
                            prefs.cycleStopWidgetIndex(+1)
                        }
                        refreshAllStopWidgets(
                            appContext,
                            showLoading = true,
                            onlyWidgetId = targetId.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID },
                        )
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
        BusMapWidgetProvider.schedulePeriodicUpdates(context, interval)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        val manager = AppWidgetManager.getInstance(context)
        val hasWide = manager.getAppWidgetIds(ComponentName(context, BusMapWidgetProvider::class.java)).isNotEmpty()
        val hasSquare = manager.getAppWidgetIds(ComponentName(context, BusMapWidgetSquareProvider::class.java)).isNotEmpty()
        val hasStops = manager.getAppWidgetIds(ComponentName(context, StopArrivalsWidgetProvider::class.java)).isNotEmpty()
        val hasAlerts = manager.getAppWidgetIds(ComponentName(context, FavoriteAlertsWidgetProvider::class.java)).isNotEmpty()
        if (!hasWide && !hasSquare && !hasStops && !hasAlerts) {
            BusMapWidgetProvider.cancelPeriodicUpdates(context)
        }
    }

    companion object {
        private const val TAG = "StopArrivalsWidget"
        const val ACTION_STOP_WIDGET_REFRESH = "com.example.tobus.ACTION_STOP_WIDGET_REFRESH"
        const val ACTION_STOP_WIDGET_NEXT_FAV = "com.example.tobus.ACTION_STOP_WIDGET_NEXT_FAV"
        const val EXTRA_OPEN_STOP_ID = "extra_open_stop_id"

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun requestUpdateAll(context: Context) {
            val appContext = context.applicationContext
            widgetScope.launch {
                refreshAllStopWidgets(appContext, showLoading = false)
            }
        }

        suspend fun refreshAllStopWidgets(
            context: Context,
            showLoading: Boolean,
            onlyWidgetId: Int? = null,
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val allIds = manager.getAppWidgetIds(ComponentName(context, StopArrivalsWidgetProvider::class.java))
            if (allIds.isEmpty()) return
            val targetIds = if (onlyWidgetId != null && allIds.contains(onlyWidgetId)) {
                intArrayOf(onlyWidgetId)
            } else {
                allIds
            }

            val prefs = ToBusPreferences.getInstance(context)
            val favorites = prefs.currentFavoriteStops()
            val settings = prefs.currentSettings()

            if (favorites.isEmpty()) {
                for (id in targetIds) {
                    StopWidgetRowCache.clearWidget(context, id)
                    manager.updateAppWidget(id, buildEmptyFavoritesViews(context, id))
                }
                return
            }

            // Map each placed widget to its currently selected favorite stop
            val stopByWidgetId = HashMap<Int, Pair<Int, FavoriteStop>>(targetIds.size)
            for (id in targetIds) {
                val idx = prefs.getStopWidgetIndex(id) % favorites.size
                stopByWidgetId[id] = idx to favorites[idx]
            }

            if (showLoading) {
                for (id in targetIds) {
                    val (idx, fav) = stopByWidgetId[id] ?: continue
                    val loading = RemoteViews(context.packageName, R.layout.widget_stop_arrivals)
                    loading.setTextViewText(R.id.stop_widget_title, shortTurinPlaceName(fav.name))
                    loading.setTextViewText(R.id.stop_widget_subtitle, "#${fav.stopCode} · aggiorno…")
                    if (favorites.size > 1) {
                        loading.setViewVisibility(R.id.stop_widget_switch_btn, View.VISIBLE)
                        loading.setTextViewText(R.id.stop_widget_switch_btn, "${idx + 1}/${favorites.size} ›")
                    }
                    manager.partiallyUpdateAppWidget(id, loading)
                }
            }

            // On silent background ticks during standby/Doze offline, preserve the existing board
            // and let JobScheduler retry the instant connectivity returns.
            if (!showLoading && !BusMapWidgetProvider.isNetworkReadyForWidget(context)) {
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.WIDGET,
                    message = "Stops widget skip standby/offline (${targetIds.size}w)",
                    detail = "netReady=false -> JobScheduler retry",
                    severity = DiagSeverity.INFO,
                )
                WidgetRefreshJobScheduler.scheduleNetworkRetry(context)
                return
            }

            val distinctStops = stopByWidgetId.values.map { it.second }.distinctBy { it.stopId }
            val repo = GttRealtimeRepository.getInstance(context)
            val fetchNow = System.currentTimeMillis()
            val cached = repo.getLastCachedSnapshot()
            val primaryFocusLine = distinctStops.firstOrNull()?.lineCode?.ifBlank { settings.selectedLine }
                ?: settings.selectedLine
            val snap = if (cached == null || fetchNow - cached.fetchedAtMillis > 50_000L) {
                withTimeoutOrNull(4_800L) {
                    repo.fetchLiveVehicles(
                        includeSecondaryFeeds = false,
                        widgetFastMode = true,
                        focusLine = primaryFocusLine.takeIf { it.isNotBlank() },
                        dataSourceMode = settings.dataSourceMode,
                        includeAlerts = false,
                    ).getOrNull()
                } ?: cached
            } else {
                cached
            }

            // Fetch ALL lines arriving at each active widget's stop in parallel, distinguishing
            // network/timeout failures (null) from genuine 0-bus responses (emptyList)
            var anyStopTimedOut = false
            val arrivalsByStopId: Map<String, List<StopArrival>> = coroutineScope {
                distinctStops.map { fav ->
                    async {
                        val lineVehicles = snap?.allVehicles?.filter {
                            it.lineCode.equals(fav.lineCode, ignoreCase = true)
                        }.orEmpty()
                        val fetchedOrNull = withTimeoutOrNull(4_500L) {
                            runCatching {
                                repo.fetchStopArrivals(
                                    stop = fav.toTransitStop(),
                                    preferredLine = fav.lineCode.ifBlank { settings.selectedLine },
                                    liveVehicles = lineVehicles,
                                )
                            }.getOrNull()
                        }
                        if (fetchedOrNull == null) {
                            anyStopTimedOut = true
                        }
                        fav.stopId to fetchedOrNull.orEmpty()
                    }
                }.awaitAll().toMap()
            }

            // On silent background ticks, if the network/stop fetch timed out (even when an older in-memory
            // vehicle snapshot still existed), keep previous cached rows intact instead of blanking the widget.
            val allEmpty = arrivalsByStopId.values.all { it.isEmpty() }
            val snapStale = snap == null || (fetchNow - snap.fetchedAtMillis > 5 * 60_000L)
            if (!showLoading && allEmpty && (anyStopTimedOut || snapStale)) {
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.WIDGET,
                    message = "Stops widget preserva board precedente (timeout rete)",
                    detail = "stops=${distinctStops.joinToString(",") { it.stopCode }} timedOut=$anyStopTimedOut snapStale=$snapStale -> JobScheduler retry",
                    severity = DiagSeverity.WARN,
                )
                WidgetRefreshJobScheduler.scheduleNetworkRetry(context)
                return
            }

            val nowMillis = System.currentTimeMillis()
            val stopsSummary = distinctStops.joinToString(" ") { fav ->
                val arrs = arrivalsByStopId[fav.stopId].orEmpty()
                val live = arrs.count { it.isRealtime }
                "#${fav.stopCode}:${arrs.size}(live=$live)"
            }
            ToBusDiagnosticsLog.record(
                category = DiagCategory.WIDGET,
                message = "Stops widget aggiornato (${targetIds.size}w${if (showLoading) ",manual" else ",auto"})",
                detail = "$stopsSummary · snapVeh=${snap?.allVehicles?.size ?: 0}",
                severity = if (allEmpty && snap == null) DiagSeverity.WARN else DiagSeverity.OK,
                timestampMillis = nowMillis,
            )
            val timeStr = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(nowMillis))

            for (id in targetIds) {
                val (favIdx, fav) = stopByWidgetId[id] ?: continue
                val rawArrivals = arrivalsByStopId[fav.stopId].orEmpty()
                val groupedRows = buildGroupedLineRows(
                    fav = fav,
                    arrivals = rawArrivals,
                    colorByDirection = settings.colorByDirection,
                )

                StopWidgetRowCache.saveRows(context, id, fav.stopId, groupedRows)

                val views = RemoteViews(context.packageName, R.layout.widget_stop_arrivals)
                views.setTextViewText(R.id.stop_widget_title, shortTurinPlaceName(fav.name))

                val lineCountPart = when (groupedRows.size) {
                    0 -> "nessun bus"
                    1 -> "1 linea"
                    else -> "${groupedRows.size} linee"
                }
                views.setTextViewText(R.id.stop_widget_subtitle, "#${fav.stopCode} · $lineCountPart · $timeStr")

                // Switcher button between saved favorite stops (1/N ›)
                if (favorites.size > 1) {
                    views.setViewVisibility(R.id.stop_widget_switch_btn, View.VISIBLE)
                    views.setTextViewText(R.id.stop_widget_switch_btn, "${favIdx + 1}/${favorites.size} ›")
                    val nextIntent = Intent(context, StopArrivalsWidgetProvider::class.java).apply {
                        action = ACTION_STOP_WIDGET_NEXT_FAV
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    }
                    val nextPending = PendingIntent.getBroadcast(
                        context,
                        id + 3000,
                        nextIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    views.setOnClickPendingIntent(R.id.stop_widget_switch_btn, nextPending)
                } else {
                    views.setViewVisibility(R.id.stop_widget_switch_btn, View.GONE)
                }

                // Bind scrollable ListView adapter (unique data URI per widgetId so multiple widgets never collide)
                val serviceIntent = Intent(context, StopArrivalsWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    data = Uri.parse("tobus://stop-widget/$id")
                }
                @Suppress("DEPRECATION")
                views.setRemoteAdapter(R.id.stop_widget_list, serviceIntent)
                views.setEmptyView(R.id.stop_widget_list, R.id.stop_widget_empty_text)
                views.setTextViewText(
                    R.id.stop_widget_empty_text,
                    "Nessun passaggio imminente rilevato per ${shortTurinPlaceName(fav.name)}.",
                )

                val options = manager.getAppWidgetOptions(id)
                val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160)
                val visibleCapacity = ((minHeightDp - 66) / 64).coerceAtLeast(1)
                val hasOverflowLines = groupedRows.size > visibleCapacity
                views.setViewVisibility(
                    R.id.stop_widget_scroll_hint,
                    if (hasOverflowLines) View.VISIBLE else View.GONE,
                )

                // PendingIntent template for tapping any line row inside the ListView -> opens stop in ToBus
                val rowClickTemplateIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val templateFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val rowClickTemplate = PendingIntent.getActivity(
                    context,
                    id + 5000,
                    rowClickTemplateIntent,
                    templateFlags,
                )
                views.setPendingIntentTemplate(R.id.stop_widget_list, rowClickTemplate)

                // Tapping the widget header also opens ToBus directly on this favorite stop
                val openHeaderIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_OPEN_STOP_ID, fav.stopId)
                }
                val openHeaderPending = PendingIntent.getActivity(
                    context,
                    id + 2000,
                    openHeaderIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.stop_widget_header, openHeaderPending)
                views.setOnClickPendingIntent(R.id.stop_widget_empty_text, openHeaderPending)

                // Refresh circle button
                val refreshIntent = Intent(context, StopArrivalsWidgetProvider::class.java).apply {
                    action = ACTION_STOP_WIDGET_REFRESH
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                }
                val refreshPending = PendingIntent.getBroadcast(
                    context,
                    id + 4000,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.stop_widget_refresh_btn, refreshPending)

                try {
                    manager.updateAppWidget(id, views)
                    manager.notifyAppWidgetViewDataChanged(id, R.id.stop_widget_list)
                } catch (e: Exception) {
                    Log.e(TAG, "Errore update widget fermata $id: ${e.message}")
                }
            }
        }

        private fun buildGroupedLineRows(
            fav: FavoriteStop,
            arrivals: List<StopArrival>,
            colorByDirection: Boolean,
        ): List<WidgetStopLineRow> {
            if (arrivals.isEmpty()) return emptyList()
            val savedLine = fav.lineCode.trim().uppercase()
            val savedTerminus = fav.terminusName?.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName)

            return arrivals
                .groupBy { it.lineCode.trim().uppercase() }
                .entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, List<StopArrival>>> {
                        savedLine.isNotEmpty() && it.key.equals(savedLine, ignoreCase = true)
                    }.thenBy { entry ->
                        entry.value.minOfOrNull { it.arrivalEpochSeconds } ?: Long.MAX_VALUE
                    },
                )
                .map { (lineCode, lineList) ->
                    val sorted = lineList.sortedBy { it.arrivalEpochSeconds }.take(3)
                    val isPrimary = savedLine.isNotEmpty() && lineCode.equals(savedLine, ignoreCase = true)
                    val headsign = sorted
                        .firstOrNull { !isGenericDirectionLabel(it.headsign) }
                        ?.headsign
                        ?.let(::shortTurinPlaceName)
                        ?: (if (isPrimary) savedTerminus else null)
                        ?: sorted
                            .firstOrNull { it.headsign.isNotBlank() && !it.headsign.startsWith("Linea ", ignoreCase = true) }
                            ?.headsign
                            ?.let(::shortTurinPlaceName)
                        ?: ""
                    WidgetStopLineRow(
                        stopId = fav.stopId,
                        lineCode = lineCode,
                        headsign = headsign,
                        isPrimaryLine = isPrimary,
                        useCyanBadge = isPrimary && colorByDirection && fav.directionId == 1,
                        arrivals = sorted.map {
                            WidgetEtaChip(
                                arrivalEpochSeconds = it.arrivalEpochSeconds,
                                isRealtime = it.isRealtime,
                            )
                        },
                    )
                }
        }

        private fun buildEmptyFavoritesViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_stop_arrivals)
            views.setTextViewText(R.id.stop_widget_title, "Fermate Live")
            views.setTextViewText(R.id.stop_widget_subtitle, "Nessuna fermata preferita")
            views.setViewVisibility(R.id.stop_widget_switch_btn, View.GONE)
            views.setViewVisibility(R.id.stop_widget_list, View.GONE)
            views.setViewVisibility(R.id.stop_widget_scroll_hint, View.GONE)
            views.setViewVisibility(R.id.stop_widget_empty_text, View.VISIBLE)
            views.setTextViewText(
                R.id.stop_widget_empty_text,
                "Tocca una fermata nella Mappa di ToBus e premi ★ per vedere qui tutti i pullman in arrivo.",
            )

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPending = PendingIntent.getActivity(
                context,
                appWidgetId + 2000,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.stop_widget_root, openPending)
            return views
        }

        fun requestPinWidget(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val manager = AppWidgetManager.getInstance(context)
            if (!manager.isRequestPinAppWidgetSupported) return false
            val provider = ComponentName(context, StopArrivalsWidgetProvider::class.java)
            return manager.requestPinAppWidget(provider, null, null)
        }
    }
}
