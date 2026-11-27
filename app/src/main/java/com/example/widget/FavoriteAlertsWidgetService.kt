package com.example.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.R
import com.example.data.model.ServiceAlertCategory
import com.example.notifications.FavoriteAlertNotifier
import com.example.ui.theme.visualStyle
import org.json.JSONArray
import org.json.JSONObject

internal data class WidgetFavoriteAlertRow(
    val id: String,
    val primaryLine: String,
    val matchedLines: List<String>,
    val title: String,
    val body: String,
    val statusLabel: String,
    val isActiveNow: Boolean,
    val category: ServiceAlertCategory = ServiceAlertCategory.INFO,
)

/**
 * Lightweight synchronized persistence for rendered Favorite Alerts widget rows and per-widget
 * density flags so [FavoriteAlertsWidgetService] loads items in <2ms without network I/O on
 * launcher binder threads.
 */
internal object FavoriteAlertsWidgetCache {
    private const val PREFS_NAME = "tobus_alerts_widget_rows_v1"

    enum class DensityMode(val id: Int) {
        COMPACT(0),
        STANDARD(1),
        EXPANDED(2);

        companion object {
            fun fromId(id: Int): DensityMode = entries.firstOrNull { it.id == id } ?: STANDARD
        }
    }

    fun saveRows(
        context: Context,
        rows: List<WidgetFavoriteAlertRow>,
    ) {
        val arr = JSONArray()
        for (row in rows) {
            val linesArr = JSONArray()
            row.matchedLines.forEach { linesArr.put(it) }
            arr.put(
                JSONObject()
                    .put("id", row.id)
                    .put("pri", row.primaryLine)
                    .put("lines", linesArr)
                    .put("title", row.title)
                    .put("body", row.body)
                    .put("status", row.statusLabel)
                    .put("active", row.isActiveNow)
                    .put("cat", row.category.id),
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROWS, arr.toString())
            .apply()
    }

    fun loadRows(context: Context): List<WidgetFavoriteAlertRow> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ROWS, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            val out = ArrayList<WidgetFavoriteAlertRow>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val linesJson = obj.optJSONArray("lines")
                val lines = ArrayList<String>(linesJson?.length() ?: 0)
                if (linesJson != null) {
                    for (j in 0 until linesJson.length()) {
                        val line = linesJson.optString(j, "").trim()
                        if (line.isNotEmpty()) lines.add(line)
                    }
                }
                out.add(
                    WidgetFavoriteAlertRow(
                        id = obj.optString("id", "alert_$i"),
                        primaryLine = obj.optString("pri", lines.firstOrNull().orEmpty()),
                        matchedLines = lines,
                        title = obj.optString("title", ""),
                        body = obj.optString("body", ""),
                        statusLabel = obj.optString("status", ""),
                        isActiveNow = obj.optBoolean("active", true),
                        category = ServiceAlertCategory.fromId(obj.optString("cat", null)),
                    ),
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun saveDensity(context: Context, appWidgetId: Int, mode: DensityMode) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(densityKey(appWidgetId), mode.id)
            .apply()
    }

    fun loadDensity(context: Context, appWidgetId: Int): DensityMode {
        val id = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(densityKey(appWidgetId), DensityMode.STANDARD.id)
        return DensityMode.fromId(id)
    }

    fun clearWidget(context: Context, appWidgetId: Int) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(densityKey(appWidgetId))
            .apply()
    }

    private const val KEY_ROWS = "shared_alert_rows_v1"
    private fun densityKey(appWidgetId: Int): String = "density_$appWidgetId"
}

/**
 * Scrollable [RemoteViewsService] backing [FavoriteAlertsWidgetProvider]'s ListView so multiple
 * active GTT alerts on favorite lines stay scannable and scalable across every widget size.
 */
class FavoriteAlertsWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        return FavoriteAlertsRemoteViewsFactory(applicationContext, appWidgetId)
    }
}

private class FavoriteAlertsRemoteViewsFactory(
    private val context: Context,
    private val appWidgetId: Int,
) : RemoteViewsService.RemoteViewsFactory {

    @Volatile
    private var rows: List<WidgetFavoriteAlertRow> = emptyList()

    @Volatile
    private var density: FavoriteAlertsWidgetCache.DensityMode = FavoriteAlertsWidgetCache.DensityMode.STANDARD

    override fun onCreate() {
        rows = FavoriteAlertsWidgetCache.loadRows(context)
        density = FavoriteAlertsWidgetCache.loadDensity(context, appWidgetId)
    }

    override fun onDataSetChanged() {
        rows = FavoriteAlertsWidgetCache.loadRows(context)
        density = FavoriteAlertsWidgetCache.loadDensity(context, appWidgetId)
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_favorite_alert_item)
        val views = RemoteViews(context.packageName, R.layout.widget_favorite_alert_item)

        val rowBgRes = when (row.category) {
            ServiceAlertCategory.CRITICAL -> R.drawable.widget_alert_row_critical
            ServiceAlertCategory.DISRUPTION -> R.drawable.widget_alert_row_disruption
            ServiceAlertCategory.NOTICE -> R.drawable.widget_alert_row_notice
            ServiceAlertCategory.POSITIVE -> R.drawable.widget_alert_row_positive
            ServiceAlertCategory.INFO -> R.drawable.widget_alert_row_info
        }
        views.setInt(
            R.id.widget_alert_card,
            "setBackgroundResource",
            if (row.isActiveNow) rowBgRes else R.drawable.widget_stop_row_bg,
        )

        // Show up to 2 line pills in compact width, or up to 3 pills + "+N" badge in standard/expanded
        val maxPills = if (density == FavoriteAlertsWidgetCache.DensityMode.COMPACT) 2 else 3
        val pillIds = intArrayOf(R.id.widget_alert_line1, R.id.widget_alert_line2, R.id.widget_alert_line3)
        for (i in pillIds.indices) {
            val line = if (i < maxPills) row.matchedLines.getOrNull(i) else null
            if (line == null) {
                views.setViewVisibility(pillIds[i], View.GONE)
            } else {
                views.setViewVisibility(pillIds[i], View.VISIBLE)
                views.setTextViewText(pillIds[i], line)
            }
        }
        val extraCount = (row.matchedLines.size - maxPills).coerceAtLeast(0)
        if (extraCount > 0) {
            views.setViewVisibility(R.id.widget_alert_line_more, View.VISIBLE)
            views.setTextViewText(R.id.widget_alert_line_more, "+$extraCount")
        } else {
            views.setViewVisibility(R.id.widget_alert_line_more, View.GONE)
        }

        views.setTextViewText(R.id.widget_alert_status, row.statusLabel)
        views.setTextColor(
            R.id.widget_alert_status,
            row.category.visualStyle().widgetStatusColorArgb,
        )

        views.setTextViewText(R.id.widget_alert_title, row.title)
        views.setInt(
            R.id.widget_alert_title,
            "setMaxLines",
            if (density == FavoriteAlertsWidgetCache.DensityMode.COMPACT) 2 else 2,
        )

        val showBody = row.body.isNotBlank() &&
            !row.body.equals(row.title, ignoreCase = true) &&
            density != FavoriteAlertsWidgetCache.DensityMode.COMPACT
        if (showBody) {
            views.setViewVisibility(R.id.widget_alert_body, View.VISIBLE)
            views.setTextViewText(R.id.widget_alert_body, row.body)
            views.setInt(
                R.id.widget_alert_body,
                "setMaxLines",
                if (density == FavoriteAlertsWidgetCache.DensityMode.EXPANDED) 3 else 2,
            )
        } else {
            views.setViewVisibility(R.id.widget_alert_body, View.GONE)
        }

        // Fill-in intent opens ToBus directly on the Alerts dialog for the primary affected line
        val fillIn = Intent().apply {
            putExtra(FavoriteAlertNotifier.EXTRA_OPEN_ALERTS_DIALOG, true)
            putExtra(FavoriteAlertNotifier.EXTRA_OPEN_ALERTS_LINE, row.primaryLine)
        }
        views.setOnClickFillInIntent(R.id.widget_alert_item_root, fillIn)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long {
        val row = rows.getOrNull(position) ?: return position.toLong()
        return row.id.hashCode().toLong()
    }

    override fun hasStableIds(): Boolean = true
}
