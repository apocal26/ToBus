package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.R
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class WidgetEtaChip(
    val arrivalEpochSeconds: Long,
    val isRealtime: Boolean,
)

internal data class WidgetStopLineRow(
    val stopId: String,
    val lineCode: String,
    val headsign: String,
    val isPrimaryLine: Boolean,
    val useCyanBadge: Boolean,
    val arrivals: List<WidgetEtaChip>,
)

/**
 * Lightweight synchronized persistence for rendered stop widget lines so the
 * [StopArrivalsWidgetService] RemoteViewsFactory reads fresh rows in <2ms without
 * ever blocking the launcher binder thread on network I/O.
 */
internal object StopWidgetRowCache {
    private const val PREFS_NAME = "tobus_stop_widget_rows_v1"

    fun saveRows(context: Context, appWidgetId: Int, stopId: String, rows: List<WidgetStopLineRow>) {
        val arr = JSONArray()
        for (row in rows) {
            val etaArr = JSONArray()
            for (eta in row.arrivals) {
                etaArr.put(
                    JSONObject()
                        .put("t", eta.arrivalEpochSeconds)
                        .put("rt", eta.isRealtime),
                )
            }
            arr.put(
                JSONObject()
                    .put("sid", row.stopId)
                    .put("line", row.lineCode)
                    .put("head", row.headsign)
                    .put("pri", row.isPrimaryLine)
                    .put("cyan", row.useCyanBadge)
                    .put("etas", etaArr),
            )
        }
        val payload = JSONObject()
            .put("stopId", stopId)
            .put("rows", arr)
            .toString()
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(keyForWidget(appWidgetId), payload)
            .apply()
    }

    fun loadRows(context: Context, appWidgetId: Int): List<WidgetStopLineRow> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(keyForWidget(appWidgetId), null)
            ?: return emptyList()
        return runCatching {
            val obj = JSONObject(raw)
            val arr = obj.optJSONArray("rows") ?: return emptyList()
            val out = ArrayList<WidgetStopLineRow>(arr.length())
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val etasJson = item.optJSONArray("etas")
                val etas = ArrayList<WidgetEtaChip>(etasJson?.length() ?: 0)
                if (etasJson != null) {
                    for (j in 0 until etasJson.length()) {
                        val e = etasJson.optJSONObject(j) ?: continue
                        etas.add(
                            WidgetEtaChip(
                                arrivalEpochSeconds = e.optLong("t", 0L),
                                isRealtime = e.optBoolean("rt", false),
                            ),
                        )
                    }
                }
                out.add(
                    WidgetStopLineRow(
                        stopId = item.optString("sid", ""),
                        lineCode = item.optString("line", ""),
                        headsign = item.optString("head", ""),
                        isPrimaryLine = item.optBoolean("pri", false),
                        useCyanBadge = item.optBoolean("cyan", false),
                        arrivals = etas,
                    ),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun clearWidget(context: Context, appWidgetId: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(keyForWidget(appWidgetId))
            .apply()
    }

    private fun keyForWidget(appWidgetId: Int): String = "w_$appWidgetId"
}

/**
 * Scrollable RemoteViewsService backing [StopArrivalsWidgetProvider]'s ListView so a single
 * favorite stop widget clearly displays ALL incoming lines and their real-time/scheduled arrivals.
 */
class StopArrivalsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return StopArrivalsRemoteViewsFactory(applicationContext, appWidgetId)
    }
}

private class StopArrivalsRemoteViewsFactory(
    private val context: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    @Volatile
    private var rows: List<WidgetStopLineRow> = emptyList()
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.ITALY)

    override fun onCreate() {
        rows = StopWidgetRowCache.loadRows(context, appWidgetId)
    }

    override fun onDataSetChanged() {
        rows = StopWidgetRowCache.loadRows(context, appWidgetId)
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_stop_line_item)
        val views = RemoteViews(context.packageName, R.layout.widget_stop_line_item)

        views.setInt(
            R.id.widget_line_card,
            "setBackgroundResource",
            if (row.isPrimaryLine) R.drawable.widget_stop_row_highlight_bg else R.drawable.widget_stop_row_bg,
        )

        views.setTextViewText(R.id.widget_line_badge, row.lineCode)
        val badgeBg = when {
            row.isPrimaryLine && row.useCyanBadge -> R.drawable.widget_badge_cyan
            row.isPrimaryLine -> R.drawable.widget_badge_amber
            else -> R.drawable.widget_badge_neutral
        }
        val badgeTextColor = if (row.isPrimaryLine) 0xFF0D0D12.toInt() else 0xFFFFFFFF.toInt()
        views.setInt(R.id.widget_line_badge, "setBackgroundResource", badgeBg)
        views.setTextColor(R.id.widget_line_badge, badgeTextColor)

        val headsignLabel = if (row.headsign.isNotBlank()) "→ ${row.headsign}" else "Linea ${row.lineCode}"
        views.setTextViewText(R.id.widget_line_headsign, headsignLabel)

        val hasAnyLive = row.arrivals.any { it.isRealtime }
        views.setTextViewText(R.id.widget_line_status, if (hasAnyLive) "● Live" else "○ Orario")
        views.setTextColor(
            R.id.widget_line_status,
            if (hasAnyLive) 0xFF34D399.toInt() else 0x8AFFFFFF.toInt(),
        )

        val nowMillis = System.currentTimeMillis()
        val etaIds = intArrayOf(R.id.widget_line_eta1, R.id.widget_line_eta2, R.id.widget_line_eta3)
        for (idx in etaIds.indices) {
            val viewId = etaIds[idx]
            val item = row.arrivals.getOrNull(idx)
            if (item == null) {
                views.setViewVisibility(viewId, View.GONE)
            } else {
                views.setViewVisibility(viewId, View.VISIBLE)
                val diffSec = item.arrivalEpochSeconds - (nowMillis / 1000L)
                val mins = (diffSec / 60L).toInt().coerceAtLeast(0)
                val clock = synchronized(clockFormat) {
                    clockFormat.format(Date(item.arrivalEpochSeconds * 1000L))
                }
                val dot = if (item.isRealtime) "● " else "○ "
                val label = when (idx) {
                    0 -> {
                        val minText = if (mins <= 0) "Ora" else "$mins min"
                        "$dot$minText · $clock"
                    }
                    1 -> {
                        val minText = if (mins <= 0) "Ora" else "${mins}'"
                        "$dot$minText · $clock"
                    }
                    else -> {
                        val minText = if (mins <= 0) "Ora" else "${mins}'"
                        "$dot$minText"
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

        // Fill-in intent so tapping any line row opens ToBus directly on this favorite stop
        val fillIn = Intent().apply {
            putExtra(StopArrivalsWidgetProvider.EXTRA_OPEN_STOP_ID, row.stopId)
        }
        views.setOnClickFillInIntent(R.id.widget_line_item_root, fillIn)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        val r = rows.getOrNull(position) ?: return position.toLong()
        return "${r.stopId}_${r.lineCode}".hashCode().toLong()
    }

    override fun hasStableIds(): Boolean = true
}
