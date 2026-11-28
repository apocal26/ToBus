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
import com.example.data.model.ServiceAlertInfo
import com.example.data.network.DiagCategory
import com.example.data.network.DiagSeverity
import com.example.data.network.GttRealtimeRepository
import com.example.data.network.ToBusDiagnosticsLog
import com.example.data.preferences.ToBusPreferences
import com.example.notifications.FavoriteAlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scalable Dark-Glass Home Screen Widget ("ToBus · Avvisi Preferite"):
 * - Shows active & upcoming GTT service alerts strictly filtered to the user's favorite lines (★).
 * - Adapts automatically across small (2×2 / 4×1), standard (4×2), and tall/tablet (4×3 / 4×4+)
 *   sizes via [onAppWidgetOptionsChanged] so typography and pills stay crisp without clipping.
 * - Ultra-lightweight: fetches only the small GTFS-RT alerts feed and syncs at zero network cost
 *   whenever the app or background notification job already downloaded fresh alerts.
 * - Tapping any alert row or the header opens ToBus directly on the Alerts sheet.
 */
class FavoriteAlertsWidgetProvider : AppWidgetProvider() {

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
                refreshAllAlertWidgets(appContext, showLoading = false)
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
                // Re-render layout immediately from cached alerts for instant resize responsiveness
                val cachedAlerts = GttRealtimeRepository.getInstance(appContext).getLastCachedSnapshot()?.alerts
                refreshAllAlertWidgets(
                    context = appContext,
                    showLoading = false,
                    onlyWidgetId = appWidgetId,
                    preloadedAlerts = cachedAlerts,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val appContext = context.applicationContext
        for (id in appWidgetIds) {
            FavoriteAlertsWidgetCache.clearWidget(appContext, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val appContext = context.applicationContext
        if (intent.action == ACTION_ALERTS_WIDGET_REFRESH) {
            val interval = ToBusPreferences.getInstance(appContext).currentSettings().refreshInterval
            BusMapWidgetProvider.schedulePeriodicUpdates(appContext, interval)
            val targetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            ).takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }

            val pendingResult = goAsync()
            widgetScope.launch {
                try {
                    refreshAllAlertWidgets(appContext, showLoading = true, onlyWidgetId = targetId)
                } finally {
                    pendingResult.finish()
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
        private const val TAG = "FavoriteAlertsWidget"
        const val ACTION_ALERTS_WIDGET_REFRESH = "com.example.tobus.ACTION_ALERTS_WIDGET_REFRESH"

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun hasInstalledWidgets(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            return manager.getAppWidgetIds(ComponentName(context, FavoriteAlertsWidgetProvider::class.java)).isNotEmpty()
        }

        fun requestUpdateAll(context: Context) {
            val appContext = context.applicationContext
            if (!hasInstalledWidgets(appContext)) return
            widgetScope.launch {
                refreshAllAlertWidgets(appContext, showLoading = false)
            }
        }

        /**
         * Zero-network fast widget sync invoked whenever the foreground app or background
         * notification job already downloaded fresh GTT alerts or favorite lines changed.
         */
        fun syncWithAlerts(context: Context, alerts: List<ServiceAlertInfo>?) {
            val appContext = context.applicationContext
            if (!hasInstalledWidgets(appContext)) return
            widgetScope.launch {
                refreshAllAlertWidgets(
                    context = appContext,
                    showLoading = false,
                    preloadedAlerts = alerts,
                )
            }
        }

        /**
         * Pure selection & formatting helper that filters [alerts] to current/upcoming notices
         * affecting [favoriteLines], deduplicates repeated feed entities, and orders active-now
         * notices before upcoming scheduled notices.
         */
        internal fun buildAlertRows(
            alerts: List<ServiceAlertInfo>,
            favoriteLines: List<String>,
            nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        ): List<WidgetFavoriteAlertRow> {
            val favOrdered = favoriteLines
                .map { it.trim().uppercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .distinct()
            if (favOrdered.isEmpty() || alerts.isEmpty()) return emptyList()
            val favSet = favOrdered.toSet()

            val dayFmt = SimpleDateFormat("dd/MM", Locale.ITALY)
            val seenContent = HashSet<String>()
            val out = mutableListOf<WidgetFavoriteAlertRow>()

            for (alert in alerts) {
                if (!alert.isCurrentOrUpcoming(nowEpochSeconds = nowEpochSeconds, maxHorizonDays = 7)) {
                    continue
                }
                val affectedUpper = alert.affectedLines
                    .map { it.trim().uppercase(Locale.ROOT) }
                    .filter { it.isNotEmpty() }
                    .toSet()
                val matched = favOrdered.filter { it in affectedUpper && it in favSet }
                if (matched.isEmpty()) continue

                val contentFp = FavoriteAlertNotifier.computeContentFingerprint(alert)
                if (!seenContent.add(contentFp)) continue

                val isActiveNow = alert.isActiveNow(nowEpochSeconds)
                val category = alert.classifyCategory()
                val validity = alert.validityLabel(nowEpochSeconds)?.takeIf { it.isNotBlank() }
                val timePart = when {
                    isActiveNow && validity != null && !validity.equals("Attivo ora", ignoreCase = true) -> validity
                    isActiveNow -> "In corso"
                    validity != null -> validity
                    else -> "In programma"
                }
                val dot = if (isActiveNow) "●" else "○"
                val statusLabel = "$dot ${category.label} · $timePart"

                val cleanHeader = alert.headerText.trim().replace(Regex("\\s+"), " ")
                val cleanBody = alert.descriptionText.trim().replace(Regex("\\s+"), " ")
                val title = cleanHeader.ifBlank { cleanBody.ifBlank { "Avviso GTT" } }
                val body = if (cleanBody.equals(title, ignoreCase = true)) "" else cleanBody

                out.add(
                    WidgetFavoriteAlertRow(
                        id = contentFp,
                        primaryLine = matched.first(),
                        matchedLines = matched,
                        title = title,
                        body = body,
                        statusLabel = statusLabel,
                        isActiveNow = isActiveNow,
                        category = category,
                    ),
                )
            }

            return out.sortedWith(
                compareByDescending<WidgetFavoriteAlertRow> { it.isActiveNow }
                    .thenByDescending { it.category.priority }
                    .thenBy { favOrdered.indexOf(it.primaryLine).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } },
            )
        }

        suspend fun refreshAllAlertWidgets(
            context: Context,
            showLoading: Boolean,
            onlyWidgetId: Int? = null,
            preloadedAlerts: List<ServiceAlertInfo>? = null,
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val allIds = manager.getAppWidgetIds(ComponentName(context, FavoriteAlertsWidgetProvider::class.java))
            if (allIds.isEmpty()) return
            val targetIds = if (onlyWidgetId != null && allIds.contains(onlyWidgetId)) {
                intArrayOf(onlyWidgetId)
            } else {
                allIds
            }

            val prefs = ToBusPreferences.getInstance(context)
            val favorites = prefs.currentFavoriteLines()

            if (showLoading) {
                for (id in targetIds) {
                    val loading = RemoteViews(context.packageName, R.layout.widget_favorite_alerts)
                    loading.setTextViewText(R.id.alerts_widget_subtitle, "Aggiorno avvisi GTT…")
                    manager.partiallyUpdateAppWidget(id, loading)
                }
            }

            if (!showLoading && preloadedAlerts == null && favorites.isNotEmpty() && !BusMapWidgetProvider.isNetworkReadyForWidget(context)) {
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.WIDGET,
                    message = "Alerts widget rinviato (standby/rete non pronta)",
                    detail = "netReady=false favs=${favorites.size} -> JobScheduler retry",
                    severity = DiagSeverity.INFO,
                )
                WidgetRefreshJobScheduler.scheduleNetworkRetry(context)
                return
            }

            val repo = GttRealtimeRepository.getInstance(context)
            val fetchedAlerts: List<ServiceAlertInfo>? = when {
                favorites.isEmpty() -> emptyList()
                preloadedAlerts != null -> preloadedAlerts
                else -> withTimeoutOrNull(6_000L) {
                    runCatching { repo.fetchServiceAlertsNow() }.getOrNull()
                } ?: repo.getLastCachedSnapshot()?.alerts
            }

            // If a silent background refresh had no network AND no cache, leave existing rows intact
            if (!showLoading && fetchedAlerts == null && favorites.isNotEmpty()) {
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.WIDGET,
                    message = "Alerts widget preserva righe precedenti (rete/cache assenti)",
                    detail = "favs=${favorites.size}",
                    severity = DiagSeverity.WARN,
                )
                return
            }

            val nowMillis = System.currentTimeMillis()
            val timeStr = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(nowMillis))
            val rows = if (favorites.isEmpty()) {
                emptyList()
            } else {
                buildAlertRows(
                    alerts = fetchedAlerts.orEmpty(),
                    favoriteLines = favorites,
                    nowEpochSeconds = nowMillis / 1000L,
                )
            }

            ToBusDiagnosticsLog.record(
                category = DiagCategory.WIDGET,
                message = "Alerts widget aggiornato (${targetIds.size}w) → ${rows.size} avvisi preferite",
                detail = "favs=${favorites.size} rawAlerts=${fetchedAlerts?.size ?: -1} src=${if (preloadedAlerts != null) "sync" else "net/cache"}",
                severity = if (fetchedAlerts == null && favorites.isNotEmpty()) DiagSeverity.WARN else DiagSeverity.OK,
                timestampMillis = nowMillis,
            )

            FavoriteAlertsWidgetCache.saveRows(context, rows)

            val favPreview = favorites.take(4).joinToString(", ") + if (favorites.size > 4) "…" else ""
            val primaryOpenLine = rows.firstOrNull()?.primaryLine ?: favorites.firstOrNull().orEmpty()

            for (id in targetIds) {
                val options = manager.getAppWidgetOptions(id)
                val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 130)
                val density = resolveDensity(minWidthDp, minHeightDp)
                FavoriteAlertsWidgetCache.saveDensity(context, id, density)

                val views = buildWidgetRemoteViews(
                    context = context,
                    appWidgetId = id,
                    favorites = favorites,
                    favPreview = favPreview,
                    rows = rows,
                    primaryOpenLine = primaryOpenLine,
                    timeStr = timeStr,
                    minWidthDp = minWidthDp,
                    minHeightDp = minHeightDp,
                    density = density,
                    isOfflineError = fetchedAlerts == null && favorites.isNotEmpty(),
                )
                try {
                    manager.updateAppWidget(id, views)
                    manager.notifyAppWidgetViewDataChanged(id, R.id.alerts_widget_list)
                } catch (e: Exception) {
                    Log.e(TAG, "Errore update widget avvisi $id: ${e.message}")
                }
            }
        }

        private fun resolveDensity(minWidthDp: Int, minHeightDp: Int): FavoriteAlertsWidgetCache.DensityMode {
            return when {
                minWidthDp < 205 || minHeightDp < 115 -> FavoriteAlertsWidgetCache.DensityMode.COMPACT
                minHeightDp >= 220 -> FavoriteAlertsWidgetCache.DensityMode.EXPANDED
                else -> FavoriteAlertsWidgetCache.DensityMode.STANDARD
            }
        }

        private fun buildWidgetRemoteViews(
            context: Context,
            appWidgetId: Int,
            favorites: List<String>,
            favPreview: String,
            rows: List<WidgetFavoriteAlertRow>,
            primaryOpenLine: String,
            timeStr: String,
            minWidthDp: Int,
            minHeightDp: Int,
            density: FavoriteAlertsWidgetCache.DensityMode,
            isOfflineError: Boolean,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_favorite_alerts)
            val isNarrow = minWidthDp < 215
            views.setViewVisibility(R.id.alerts_widget_scroll_hint, View.GONE)

            views.setTextViewText(R.id.alerts_widget_title, if (isNarrow) "Avvisi ★" else "Avvisi Preferite")

            val hasAlerts = rows.isNotEmpty()
            when {
                favorites.isEmpty() -> {
                    views.setTextViewText(R.id.alerts_widget_icon, "★")
                    views.setInt(R.id.alerts_widget_icon, "setBackgroundResource", R.drawable.widget_alert_count_pill)
                    views.setTextColor(R.id.alerts_widget_icon, 0xFFFBBF24.toInt())
                    views.setTextViewText(R.id.alerts_widget_subtitle, "Nessuna linea preferita")
                    views.setViewVisibility(R.id.alerts_widget_count_pill, View.GONE)
                    views.setViewVisibility(R.id.alerts_widget_list, View.GONE)
                    views.setViewVisibility(R.id.alerts_widget_empty_container, View.VISIBLE)
                    views.setInt(R.id.alerts_widget_empty_container, "setBackgroundResource", R.drawable.widget_stop_row_bg)
                    views.setTextViewText(R.id.alerts_widget_empty_title, "★ Aggiungi le tue linee")
                    views.setTextColor(R.id.alerts_widget_empty_title, 0xFFFBBF24.toInt())
                    views.setTextViewText(
                        R.id.alerts_widget_empty_body,
                        "Apri ToBus e tocca ★ sulle linee che usi per vedere qui deviazioni e avvisi GTT.",
                    )
                }

                isOfflineError -> {
                    views.setTextViewText(R.id.alerts_widget_icon, "!")
                    views.setInt(R.id.alerts_widget_icon, "setBackgroundResource", R.drawable.widget_pill_dark)
                    views.setTextColor(R.id.alerts_widget_icon, 0xCCFFFFFF.toInt())
                    views.setTextViewText(R.id.alerts_widget_subtitle, "Offline · $timeStr")
                    views.setViewVisibility(R.id.alerts_widget_count_pill, View.GONE)
                    views.setViewVisibility(R.id.alerts_widget_list, View.GONE)
                    views.setViewVisibility(R.id.alerts_widget_empty_container, View.VISIBLE)
                    views.setInt(R.id.alerts_widget_empty_container, "setBackgroundResource", R.drawable.widget_stop_row_bg)
                    views.setTextViewText(R.id.alerts_widget_empty_title, "Feed GTT non raggiungibile")
                    views.setTextColor(R.id.alerts_widget_empty_title, 0xE6FFFFFF.toInt())
                    views.setTextViewText(
                        R.id.alerts_widget_empty_body,
                        "Tocca aggiorna appena torna la connessione.",
                    )
                }

                hasAlerts -> {
                    views.setTextViewText(R.id.alerts_widget_icon, "!")
                    views.setInt(R.id.alerts_widget_icon, "setBackgroundResource", R.drawable.widget_alert_count_pill)
                    views.setTextColor(R.id.alerts_widget_icon, 0xFFFBBF24.toInt())
                    views.setTextViewText(R.id.alerts_widget_subtitle, "$favPreview · $timeStr")
                    if (!isNarrow) {
                        views.setViewVisibility(R.id.alerts_widget_count_pill, View.VISIBLE)
                        views.setTextViewText(
                            R.id.alerts_widget_count_pill,
                            if (rows.size == 1) "1 attivo" else "${rows.size} attivi",
                        )
                    } else {
                        views.setViewVisibility(R.id.alerts_widget_count_pill, View.GONE)
                    }
                    views.setViewVisibility(R.id.alerts_widget_list, View.VISIBLE)
                    views.setViewVisibility(R.id.alerts_widget_empty_container, View.GONE)
                    val rowHeightDp = when (density) {
                        FavoriteAlertsWidgetCache.DensityMode.COMPACT -> 58
                        FavoriteAlertsWidgetCache.DensityMode.STANDARD -> 72
                        FavoriteAlertsWidgetCache.DensityMode.EXPANDED -> 86
                    }
                    val visibleCapacity = ((minHeightDp - 66) / rowHeightDp).coerceAtLeast(1)
                    views.setViewVisibility(
                        R.id.alerts_widget_scroll_hint,
                        if (rows.size > visibleCapacity) View.VISIBLE else View.GONE,
                    )

                    val serviceIntent = Intent(context, FavoriteAlertsWidgetService::class.java).apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        data = Uri.parse("tobus://alerts-widget/$appWidgetId")
                    }
                    @Suppress("DEPRECATION")
                    views.setRemoteAdapter(R.id.alerts_widget_list, serviceIntent)
                    views.setEmptyView(R.id.alerts_widget_list, R.id.alerts_widget_empty_container)

                    val rowTemplateIntent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    val templateFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val rowTemplatePending = PendingIntent.getActivity(
                        context,
                        appWidgetId + 8000,
                        rowTemplateIntent,
                        templateFlags,
                    )
                    views.setPendingIntentTemplate(R.id.alerts_widget_list, rowTemplatePending)
                }

                else -> {
                    views.setTextViewText(R.id.alerts_widget_icon, "✓")
                    views.setInt(R.id.alerts_widget_icon, "setBackgroundResource", R.drawable.widget_alert_ok_pill)
                    views.setTextColor(R.id.alerts_widget_icon, 0xFF34D399.toInt())
                    views.setTextViewText(R.id.alerts_widget_subtitle, "$favPreview · $timeStr")
                    views.setViewVisibility(R.id.alerts_widget_count_pill, View.GONE)
                    views.setViewVisibility(R.id.alerts_widget_list, View.GONE)
                    views.setViewVisibility(R.id.alerts_widget_empty_container, View.VISIBLE)
                    views.setInt(R.id.alerts_widget_empty_container, "setBackgroundResource", R.drawable.widget_alert_ok_bg)
                    views.setTextViewText(R.id.alerts_widget_empty_title, "✓ Servizio regolare")
                    views.setTextColor(R.id.alerts_widget_empty_title, 0xFF34D399.toInt())
                    views.setTextViewText(
                        R.id.alerts_widget_empty_body,
                        "Nessun avviso o deviazione attiva sulle linee $favPreview.",
                    )
                }
            }

            // Header & empty container open ToBus directly on the Alerts dialog
            val openAlertsIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (favorites.isNotEmpty()) {
                    putExtra(FavoriteAlertNotifier.EXTRA_OPEN_ALERTS_DIALOG, true)
                    if (primaryOpenLine.isNotEmpty()) {
                        putExtra(FavoriteAlertNotifier.EXTRA_OPEN_ALERTS_LINE, primaryOpenLine)
                    }
                }
            }
            val openPending = PendingIntent.getActivity(
                context,
                appWidgetId + 7000,
                openAlertsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.alerts_widget_header, openPending)
            views.setOnClickPendingIntent(R.id.alerts_widget_empty_container, openPending)

            // Refresh circle button
            val refreshIntent = Intent(context, FavoriteAlertsWidgetProvider::class.java).apply {
                action = ACTION_ALERTS_WIDGET_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val refreshPending = PendingIntent.getBroadcast(
                context,
                appWidgetId + 9000,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.alerts_widget_refresh_btn, refreshPending)

            return views
        }

        fun requestPinWidget(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val manager = AppWidgetManager.getInstance(context)
            if (!manager.isRequestPinAppWidgetSupported) return false
            val provider = ComponentName(context, FavoriteAlertsWidgetProvider::class.java)
            return manager.requestPinAppWidget(provider, null, null)
        }
    }
}
