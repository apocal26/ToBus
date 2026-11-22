package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.model.StopArrival
import com.example.data.model.shortTurinPlaceName
import com.example.data.network.GttRealtimeRepository
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
 * Multi-stop Home Screen Widget ("ToBus · Fermate Live"):
 * - When 2+ favorite stops are saved: displays up to 3 favorite stops simultaneously in clean
 *   Dark Translucent Glass cards, each with colored line pill + grouped upcoming times (min + HH:mm + live/stimato).
 * - When 1 favorite stop is saved: displays up to 3 lines grouped at that stop.
 * - When >3 favorite stops are saved: includes a compact page switcher ("1–3 / N ›") in the header.
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

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_STOP_WIDGET_REFRESH -> {
                val interval = ToBusPreferences.getInstance(appContext).currentSettings().refreshInterval
                BusMapWidgetProvider.schedulePeriodicUpdates(appContext, interval)

                val pendingResult = goAsync()
                widgetScope.launch {
                    try {
                        refreshAllStopWidgets(appContext, showLoading = true)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ACTION_STOP_WIDGET_NEXT_FAV -> {
                val pendingResult = goAsync()
                widgetScope.launch {
                    try {
                        ToBusPreferences.getInstance(appContext).cycleStopWidgetIndex(+PAGE_SIZE)
                        refreshAllStopWidgets(appContext, showLoading = true)
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
        if (!hasWide && !hasSquare && !hasStops) {
            BusMapWidgetProvider.cancelPeriodicUpdates(context)
        }
    }

    private data class CardSlotIds(
        val containerId: Int,
        val lineId: Int,
        val titleId: Int,
        val dirId: Int,
        val eta1Id: Int,
        val eta2Id: Int,
        val eta3Id: Int,
    )

    private data class RenderedCard(
        val lineCode: String,
        val useCyanBadge: Boolean,
        val title: String,
        val dirLabel: String,
        val arrivals: List<StopArrival>,
    )

    companion object {
        private const val TAG = "StopArrivalsWidget"
        private const val PAGE_SIZE = 3
        const val ACTION_STOP_WIDGET_REFRESH = "com.example.tobus.ACTION_STOP_WIDGET_REFRESH"
        const val ACTION_STOP_WIDGET_NEXT_FAV = "com.example.tobus.ACTION_STOP_WIDGET_NEXT_FAV"

        private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val CARD_SLOTS = listOf(
            CardSlotIds(
                containerId = R.id.stop_card_1,
                lineId = R.id.stop_card_1_line,
                titleId = R.id.stop_card_1_title,
                dirId = R.id.stop_card_1_dir,
                eta1Id = R.id.stop_card_1_eta1,
                eta2Id = R.id.stop_card_1_eta2,
                eta3Id = R.id.stop_card_1_eta3,
            ),
            CardSlotIds(
                containerId = R.id.stop_card_2,
                lineId = R.id.stop_card_2_line,
                titleId = R.id.stop_card_2_title,
                dirId = R.id.stop_card_2_dir,
                eta1Id = R.id.stop_card_2_eta1,
                eta2Id = R.id.stop_card_2_eta2,
                eta3Id = R.id.stop_card_2_eta3,
            ),
            CardSlotIds(
                containerId = R.id.stop_card_3,
                lineId = R.id.stop_card_3_line,
                titleId = R.id.stop_card_3_title,
                dirId = R.id.stop_card_3_dir,
                eta1Id = R.id.stop_card_3_eta1,
                eta2Id = R.id.stop_card_3_eta2,
                eta3Id = R.id.stop_card_3_eta3,
            ),
        )

        fun requestUpdateAll(context: Context) {
            val appContext = context.applicationContext
            widgetScope.launch {
                refreshAllStopWidgets(appContext, showLoading = false)
            }
        }

        suspend fun refreshAllStopWidgets(context: Context, showLoading: Boolean) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StopArrivalsWidgetProvider::class.java))
            if (ids.isEmpty()) return

            val prefs = ToBusPreferences.getInstance(context)
            val favorites = prefs.currentFavoriteStops()
            val settings = prefs.currentSettings()

            if (favorites.isEmpty()) {
                for (id in ids) {
                    manager.updateAppWidget(id, buildEmptyViews(context, id))
                }
                return
            }

            if (showLoading) {
                for (id in ids) {
                    val loading = RemoteViews(context.packageName, R.layout.widget_stop_arrivals)
                    loading.setTextViewText(R.id.stop_widget_subtitle, "…")
                    manager.partiallyUpdateAppWidget(id, loading)
                }
            }

            val totalPages = ((favorites.size - 1) / PAGE_SIZE) + 1
            val currentPage = (prefs.getActiveStopWidgetIndex() / PAGE_SIZE) % totalPages
            val pageStart = currentPage * PAGE_SIZE
            val visibleFavorites = favorites.drop(pageStart).take(PAGE_SIZE)

            val repo = GttRealtimeRepository.getInstance(context)
            val fetchNow = System.currentTimeMillis()
            val cached = repo.getLastCachedSnapshot()
            val snap = if (cached == null || fetchNow - cached.fetchedAtMillis > 50_000L) {
                withTimeoutOrNull(4_000L) {
                    repo.fetchLiveVehicles(includeSecondaryFeeds = false, widgetFastMode = true).getOrNull()
                } ?: cached
            } else {
                cached
            }

            // Parallel fetch for all visible stops at once, bounded to never exceed Android's 10s goAsync() window
            val arrivalsByStopId: Map<String, List<StopArrival>> = coroutineScope {
                visibleFavorites.map { fav ->
                    async {
                        val lineVehicles = snap?.allVehicles?.filter {
                            it.lineCode.equals(fav.lineCode, ignoreCase = true)
                        }.orEmpty()
                        val list = withTimeoutOrNull(4_500L) {
                            runCatching {
                                repo.fetchStopArrivals(
                                    stop = fav.toTransitStop(),
                                    preferredLine = fav.lineCode.ifBlank { settings.selectedLine },
                                    liveVehicles = lineVehicles,
                                )
                            }.getOrDefault(emptyList())
                        }.orEmpty()
                        fav.stopId to list
                    }
                }.awaitAll().toMap()
            }

            val nowMillis = System.currentTimeMillis()
            val timeFormat = SimpleDateFormat("HH:mm", Locale.ITALY)
            val timeStr = timeFormat.format(Date(nowMillis))

            val headerTitle: String
            val cards: List<RenderedCard>

            if (visibleFavorites.size == 1) {
                // Single saved favorite -> group up to 3 lines cleanly at this stop!
                val only = visibleFavorites.first()
                headerTitle = shortTurinPlaceName(only.name)
                val rawArrivals = arrivalsByStopId[only.stopId].orEmpty()
                val grouped = rawArrivals
                    .groupBy { it.lineCode.uppercase() }
                    .entries
                    .sortedWith(
                        compareByDescending<Map.Entry<String, List<StopArrival>>> {
                            it.key.equals(only.lineCode, ignoreCase = true)
                        }.thenBy { it.value.minOfOrNull { a -> a.arrivalEpochSeconds } ?: Long.MAX_VALUE },
                    )
                    .take(PAGE_SIZE)

                cards = if (grouped.isEmpty()) {
                    listOf(
                        RenderedCard(
                            lineCode = only.lineCode,
                            useCyanBadge = settings.colorByDirection && only.directionId == 1,
                            title = shortTurinPlaceName(only.name),
                            dirLabel = only.terminusName?.let { "→ ${shortTurinPlaceName(it)}" }.orEmpty(),
                            arrivals = emptyList(),
                        ),
                    )
                } else {
                    grouped.map { (line, list) ->
                        val headsign = list.firstOrNull()?.headsign?.takeIf { it.isNotBlank() }
                            ?: only.terminusName.orEmpty()
                        val isSavedLine = line.equals(only.lineCode, ignoreCase = true)
                        RenderedCard(
                            lineCode = line,
                            useCyanBadge = isSavedLine && settings.colorByDirection && only.directionId == 1,
                            title = if (headsign.isNotBlank()) shortTurinPlaceName(headsign) else "Linea $line",
                            dirLabel = "",
                            arrivals = list.sortedBy { it.arrivalEpochSeconds }.take(3),
                        )
                    }
                }
            } else {
                // Multi-stop mode: display 2 or 3 favorite stops simultaneously!
                headerTitle = "Fermate Live"
                cards = visibleFavorites.map { fav ->
                    val allForStop = arrivalsByStopId[fav.stopId].orEmpty()
                    val preferred = allForStop.filter { it.lineCode.equals(fav.lineCode, ignoreCase = true) }
                    val chosenArrivals = (preferred.ifEmpty { allForStop })
                        .sortedBy { it.arrivalEpochSeconds }
                        .take(3)
                    val displayLine = chosenArrivals.firstOrNull()?.lineCode ?: fav.lineCode
                    val dirShort = fav.terminusName?.takeIf { it.isNotBlank() }?.let { "→ ${shortTurinPlaceName(it)}" }
                        ?: chosenArrivals.firstOrNull()?.headsign?.takeIf { it.isNotBlank() }?.let { "→ ${shortTurinPlaceName(it)}" }
                        ?: ""
                    RenderedCard(
                        lineCode = displayLine,
                        useCyanBadge = settings.colorByDirection && fav.directionId == 1,
                        title = shortTurinPlaceName(fav.name),
                        dirLabel = dirShort,
                        arrivals = chosenArrivals,
                    )
                }
            }

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_stop_arrivals)
                views.setTextViewText(R.id.stop_widget_title, headerTitle)
                views.setTextViewText(R.id.stop_widget_subtitle, timeStr)
                views.setViewVisibility(R.id.stop_widget_empty_text, View.GONE)
                views.setViewVisibility(R.id.stop_widget_rows_container, View.VISIBLE)

                if (totalPages > 1) {
                    views.setViewVisibility(R.id.stop_widget_switch_btn, View.VISIBLE)
                    views.setTextViewText(R.id.stop_widget_switch_btn, "${currentPage + 1}/$totalPages ›")
                    val nextIntent = Intent(context, StopArrivalsWidgetProvider::class.java).apply {
                        action = ACTION_STOP_WIDGET_NEXT_FAV
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

                CARD_SLOTS.forEachIndexed { idx, slot ->
                    val card = cards.getOrNull(idx)
                    if (card == null) {
                        views.setViewVisibility(slot.containerId, View.GONE)
                    } else {
                        views.setViewVisibility(slot.containerId, View.VISIBLE)
                        views.setTextViewText(slot.lineId, card.lineCode)
                        views.setInt(
                            slot.lineId,
                            "setBackgroundResource",
                            if (card.useCyanBadge) R.drawable.widget_badge_cyan else R.drawable.widget_badge_amber,
                        )
                        views.setTextViewText(slot.titleId, card.title)
                        if (card.dirLabel.isNotBlank()) {
                            views.setViewVisibility(slot.dirId, View.VISIBLE)
                            views.setTextViewText(slot.dirId, card.dirLabel)
                        } else {
                            views.setViewVisibility(slot.dirId, View.GONE)
                        }

                        bindEtaChips(views, slot, card.arrivals, nowMillis, timeFormat)
                    }
                }

                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val openPending = PendingIntent.getActivity(
                    context,
                    id + 2000,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.stop_widget_root, openPending)

                val refreshIntent = Intent(context, StopArrivalsWidgetProvider::class.java).apply {
                    action = ACTION_STOP_WIDGET_REFRESH
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
                } catch (e: Exception) {
                    Log.e(TAG, "Errore update widget fermata $id: ${e.message}")
                }
            }
        }

        private fun bindEtaChips(
            views: RemoteViews,
            slot: CardSlotIds,
            arrivals: List<StopArrival>,
            nowMillis: Long,
            timeFormat: SimpleDateFormat,
        ) {
            val etaIds = listOf(slot.eta1Id, slot.eta2Id, slot.eta3Id)
            if (arrivals.isEmpty()) {
                views.setViewVisibility(slot.eta1Id, View.VISIBLE)
                views.setInt(slot.eta1Id, "setBackgroundResource", R.drawable.widget_eta_chip_sched)
                views.setTextViewText(slot.eta1Id, "Nessun passaggio ora")
                views.setTextColor(slot.eta1Id, 0x99FFFFFF.toInt())
                views.setViewVisibility(slot.eta2Id, View.GONE)
                views.setViewVisibility(slot.eta3Id, View.GONE)
                return
            }

            etaIds.forEachIndexed { idx, viewId ->
                val item = arrivals.getOrNull(idx)
                if (item == null) {
                    views.setViewVisibility(viewId, View.GONE)
                } else {
                    views.setViewVisibility(viewId, View.VISIBLE)
                    val mins = item.minutesUntil(nowMillis).coerceAtLeast(0)
                    val clock = timeFormat.format(Date(item.arrivalEpochSeconds * 1000L))
                    val prefix = if (item.isRealtime) "● " else "○ "
                    val label = when (idx) {
                        0 -> {
                            val minText = if (mins <= 0) "Ora" else "$mins min"
                            "$prefix$minText · $clock"
                        }
                        1 -> {
                            val minText = if (mins <= 0) "Ora" else "${mins}'"
                            "$prefix$minText · $clock"
                        }
                        else -> {
                            val minText = if (mins <= 0) "Ora" else "${mins}'"
                            "$prefix$minText"
                        }
                    }
                    views.setTextViewText(viewId, label)
                    val useLiveChip = item.isRealtime && idx == 0
                    views.setInt(
                        viewId,
                        "setBackgroundResource",
                        if (useLiveChip) R.drawable.widget_eta_chip_live else R.drawable.widget_eta_chip_sched,
                    )
                    val textColor = when {
                        item.isRealtime && idx == 0 -> 0xFF34D399.toInt()
                        item.isRealtime -> 0xFFFFFFFF.toInt()
                        else -> 0xB8FFFFFF.toInt()
                    }
                    views.setTextColor(viewId, textColor)
                }
            }
        }

        private fun buildEmptyViews(context: Context, appWidgetId: Int): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_stop_arrivals)
            views.setTextViewText(R.id.stop_widget_title, "Fermate Live")
            views.setTextViewText(R.id.stop_widget_subtitle, "")
            views.setViewVisibility(R.id.stop_widget_switch_btn, View.GONE)
            views.setViewVisibility(R.id.stop_widget_rows_container, View.GONE)
            views.setViewVisibility(R.id.stop_widget_empty_text, View.VISIBLE)

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
