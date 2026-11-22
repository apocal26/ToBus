package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import com.example.data.model.BusVehicle
import com.example.data.model.MapStyle
import com.example.data.model.RoutePattern
import com.example.data.model.TransitStop
import com.example.data.model.VehicleHealthAssessment
import com.example.data.model.shortTurinPlaceName
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

internal object MapLibreTransitIds {
    const val MAJOR_LANDMARKS_LAYER = "tobus-major-landmarks"

    const val ROUTE_SOURCE = "tobus-routes-source"
    const val ROUTE_CASING_LAYER = "tobus-route-casing"
    const val ROUTE_GLOW_LAYER = "tobus-route-glow"
    const val ROUTE_CORE_LAYER = "tobus-route-core"

    const val STOPS_SOURCE = "tobus-stops-source"
    const val STOPS_HALO_LAYER = "tobus-stops-halo"
    const val STOPS_DOT_LAYER = "tobus-stops-dot"
    const val STOPS_SELECTED_LABEL_LAYER = "tobus-stops-selected-label"

    const val USER_SOURCE = "tobus-user-source"
    const val USER_PULSE_LAYER = "tobus-user-pulse"
    const val USER_DOT_LAYER = "tobus-user-dot"

    const val BUSES_SOURCE = "tobus-buses-source"
    const val BUSES_SELECTED_HALO_LAYER = "tobus-buses-selected-halo"
    const val BUSES_ICON_LAYER = "tobus-buses-icon"
    const val BUSES_LABEL_LAYER = "tobus-buses-label"

    // Dedicated isolated source & top-most layers exclusively for a focused bus of another line
    const val EXTERNAL_BUS_SOURCE = "tobus-external-bus-source"
    const val EXTERNAL_BUS_HALO_LAYER = "tobus-external-bus-halo"
    const val EXTERNAL_BUS_CORE_LAYER = "tobus-external-bus-core"
    const val EXTERNAL_BUS_ICON_LAYER = "tobus-external-bus-icon"
    const val EXTERNAL_BUS_LABEL_LAYER = "tobus-external-bus-label"

    const val ICON_GOLD = "tobus-bus-gold"
    const val ICON_GOLD_SEL = "tobus-bus-gold-sel"
    const val ICON_GOLD_NODIR = "tobus-bus-gold-nodir"
    const val ICON_GOLD_NODIR_SEL = "tobus-bus-gold-nodir-sel"

    const val ICON_CYAN = "tobus-bus-cyan"
    const val ICON_CYAN_SEL = "tobus-bus-cyan-sel"
    const val ICON_CYAN_NODIR = "tobus-bus-cyan-nodir"
    const val ICON_CYAN_NODIR_SEL = "tobus-bus-cyan-nodir-sel"

    const val ICON_EXT_ARROW = "tobus-bus-ext-arrow"
    const val ICON_EXT_NODIR = "tobus-bus-ext-nodir"

    const val ICON_STALE = "tobus-bus-stale"
    const val ICON_STALE_SEL = "tobus-bus-stale-sel"

    const val LM_PARK = "tobus-lm-park"
    const val LM_RAIL = "tobus-lm-rail"
    const val LM_CULTURE = "tobus-lm-culture"
    const val LM_HOSPITAL = "tobus-lm-hospital"
    const val LM_STADIUM = "tobus-lm-stadium"
    const val LM_DEFAULT = "tobus-lm-default"
}

/**
 * Adds a single curated Major Landmarks layer above the clean Turin vector basemap:
 * stations, hospitals, universities, stadiums, major parks, museums, castles, theatres, monuments.
 * Explicitly excludes retail shops, bakeries, bars, parking lots, and duplicate bus stops.
 */
internal fun installMajorLandmarksLayer(style: Style, mapStyle: MapStyle, density: Float) {
    if (style.getSource(TurinVectorStyleBuilder.VECTOR_SOURCE_ID) == null) return
    val isDark = mapStyle.isDark

    registerLandmarkDotSprites(style, isDark = isDark, density = density)
    if (style.getLayer(MapLibreTransitIds.MAJOR_LANDMARKS_LAYER) != null) return

    val landmarkClassFilter = Expression.any(
        Expression.eq(Expression.get("class"), Expression.literal("railway")),
        Expression.eq(Expression.get("class"), Expression.literal("hospital")),
        Expression.eq(Expression.get("class"), Expression.literal("university")),
        Expression.eq(Expression.get("class"), Expression.literal("college")),
        Expression.eq(Expression.get("class"), Expression.literal("stadium")),
        Expression.eq(Expression.get("class"), Expression.literal("park")),
        Expression.eq(Expression.get("class"), Expression.literal("museum")),
        Expression.eq(Expression.get("class"), Expression.literal("castle")),
        Expression.eq(Expression.get("class"), Expression.literal("monument")),
        Expression.eq(Expression.get("class"), Expression.literal("theatre")),
        Expression.eq(Expression.get("class"), Expression.literal("town_hall")),
        Expression.eq(Expression.get("class"), Expression.literal("attraction")),
        Expression.eq(Expression.get("class"), Expression.literal("library")),
    )
    val excludeSmallStops = Expression.all(
        Expression.neq(Expression.get("subclass"), Expression.literal("bus_stop")),
        Expression.neq(Expression.get("subclass"), Expression.literal("tram_stop")),
        Expression.neq(Expression.get("subclass"), Expression.literal("platform")),
        Expression.neq(Expression.get("subclass"), Expression.literal("parking")),
    )
    val landmarkFilter = Expression.all(
        Expression.has("name"),
        landmarkClassFilter,
        excludeSmallStops,
    )

    val landmarkIconExpr = Expression.match(
        Expression.get("class"),
        Expression.literal(MapLibreTransitIds.LM_DEFAULT),
        Expression.stop("park", Expression.literal(MapLibreTransitIds.LM_PARK)),
        Expression.stop("railway", Expression.literal(MapLibreTransitIds.LM_RAIL)),
        Expression.stop("hospital", Expression.literal(MapLibreTransitIds.LM_HOSPITAL)),
        Expression.stop("stadium", Expression.literal(MapLibreTransitIds.LM_STADIUM)),
        Expression.stop("university", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
        Expression.stop("college", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
        Expression.stop("museum", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
        Expression.stop("castle", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
        Expression.stop("monument", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
        Expression.stop("theatre", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
        Expression.stop("attraction", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
        Expression.stop("town_hall", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
    )

    val landmarkTextColorExpr = if (isDark) {
        Expression.match(
            Expression.get("class"),
            Expression.literal("#DAE4F2"),
            Expression.stop("park", Expression.literal("#8DE2B9")),
            Expression.stop("railway", Expression.literal("#84CEFF")),
            Expression.stop("hospital", Expression.literal("#F6A4AB")),
            Expression.stop("stadium", Expression.literal("#B5C4FF")),
            Expression.stop("university", Expression.literal("#F2D794")),
            Expression.stop("college", Expression.literal("#F2D794")),
            Expression.stop("museum", Expression.literal("#F2D794")),
            Expression.stop("castle", Expression.literal("#F2D794")),
            Expression.stop("monument", Expression.literal("#F2D794")),
            Expression.stop("theatre", Expression.literal("#F2D794")),
            Expression.stop("attraction", Expression.literal("#F2D794")),
            Expression.stop("town_hall", Expression.literal("#F2D794")),
        )
    } else {
        Expression.match(
            Expression.get("class"),
            Expression.literal("#2D3B4E"),
            Expression.stop("park", Expression.literal("#1F6B43")),
            Expression.stop("railway", Expression.literal("#1B5A8C")),
            Expression.stop("hospital", Expression.literal("#B53344")),
            Expression.stop("stadium", Expression.literal("#43479B")),
            Expression.stop("university", Expression.literal("#7A5011")),
            Expression.stop("college", Expression.literal("#7A5011")),
            Expression.stop("museum", Expression.literal("#7A5011")),
            Expression.stop("castle", Expression.literal("#7A5011")),
            Expression.stop("monument", Expression.literal("#7A5011")),
            Expression.stop("theatre", Expression.literal("#7A5011")),
            Expression.stop("attraction", Expression.literal("#7A5011")),
            Expression.stop("town_hall", Expression.literal("#7A5011")),
        )
    }

    val landmarkLayer = SymbolLayer(
        MapLibreTransitIds.MAJOR_LANDMARKS_LAYER,
        TurinVectorStyleBuilder.VECTOR_SOURCE_ID,
    )
        .withSourceLayer("poi")
        .withFilter(landmarkFilter)
        .withProperties(
            PropertyFactory.iconImage(landmarkIconExpr),
            PropertyFactory.iconSize(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(11.8, 0.82f),
                    Expression.stop(14.5, 0.98f),
                ),
            ),
            PropertyFactory.iconPadding(10f),
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.textField(
                Expression.coalesce(
                    Expression.get("name:it"),
                    Expression.get("name:latin"),
                    Expression.get("name"),
                ),
            ),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(11.8, 10.8f),
                    Expression.stop(14.5, 12.2f),
                    Expression.stop(17.0, 13.0f),
                ),
            ),
            PropertyFactory.textMaxWidth(9.5f),
            PropertyFactory.textVariableAnchor(
                arrayOf(
                    Property.TEXT_ANCHOR_TOP,
                    Property.TEXT_ANCHOR_BOTTOM,
                    Property.TEXT_ANCHOR_LEFT,
                    Property.TEXT_ANCHOR_RIGHT,
                ),
            ),
            PropertyFactory.textRadialOffset(0.75f),
            PropertyFactory.textColor(landmarkTextColorExpr),
            PropertyFactory.textHaloColor(
                if (isDark) "rgba(12, 16, 26, 0.94)" else "rgba(248, 250, 253, 0.96)",
            ),
            PropertyFactory.textHaloWidth(1.9f),
            PropertyFactory.textPadding(11f),
            PropertyFactory.textAllowOverlap(false),
            PropertyFactory.textOptional(false),
            PropertyFactory.symbolSortKey(
                Expression.toNumber(
                    Expression.coalesce(Expression.get("rank"), Expression.literal(50)),
                ),
            ),
        )
    landmarkLayer.minZoom = 11.8f
    style.addLayer(landmarkLayer)
}

private fun registerLandmarkDotSprites(style: Style, isDark: Boolean, density: Float) {
    val rim = if (isDark) AndroidColor.parseColor("#111722") else AndroidColor.WHITE
    val dpi = (density * 160f).roundToInt().coerceAtLeast(160)

    fun addDot(id: String, hex: String) {
        val fill = AndroidColor.parseColor(hex)
        val sizePx = (14f * density).roundToInt().coerceAtLeast(20)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bmp.density = dpi
        val canvas = Canvas(bmp)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.style = Paint.Style.FILL
        paint.color = fill
        paint.alpha = if (isDark) 76 else 54
        canvas.drawCircle(cx, cy, 5.8f * density, paint)

        paint.color = rim
        paint.alpha = 245
        canvas.drawCircle(cx, cy, 4.0f * density, paint)

        paint.color = fill
        paint.alpha = 250
        canvas.drawCircle(cx, cy, 2.9f * density, paint)

        style.addImage(id, bmp, false)
    }

    addDot(MapLibreTransitIds.LM_PARK, if (isDark) "#6CD6A2" else "#2B7A4F")
    addDot(MapLibreTransitIds.LM_RAIL, if (isDark) "#62BCFA" else "#25639B")
    addDot(MapLibreTransitIds.LM_CULTURE, if (isDark) "#EAC772" else "#A96E18")
    addDot(MapLibreTransitIds.LM_HOSPITAL, if (isDark) "#F0828C" else "#C23242")
    addDot(MapLibreTransitIds.LM_STADIUM, if (isDark) "#9AAEF8" else "#5056B8")
    addDot(MapLibreTransitIds.LM_DEFAULT, if (isDark) "#BFD0E4" else "#475569")
}

internal fun registerTransitSprites(style: Style, density: Float) {
    val gold = AndroidColor.parseColor("#FFB300")
    val cyan = AndroidColor.parseColor("#38BDF8")
    val emerald = AndroidColor.parseColor("#10B981")
    val stale = AndroidColor.parseColor("#64748B")
    val darkInk = AndroidColor.parseColor("#0B0F17")

    fun add(id: String, fill: Int, hasArrow: Boolean, selected: Boolean, muted: Boolean = false) {
        val bmp = createBusMarkerBitmap(
            fillArgb = fill,
            inkArgb = darkInk,
            hasArrow = hasArrow,
            selected = selected,
            muted = muted,
            density = density,
        )
        style.addImage(id, bmp, false)
    }

    add(MapLibreTransitIds.ICON_GOLD, gold, hasArrow = true, selected = false)
    add(MapLibreTransitIds.ICON_GOLD_SEL, gold, hasArrow = true, selected = true)
    add(MapLibreTransitIds.ICON_GOLD_NODIR, gold, hasArrow = false, selected = false)
    add(MapLibreTransitIds.ICON_GOLD_NODIR_SEL, gold, hasArrow = false, selected = true)

    add(MapLibreTransitIds.ICON_CYAN, cyan, hasArrow = true, selected = false)
    add(MapLibreTransitIds.ICON_CYAN_SEL, cyan, hasArrow = true, selected = true)
    add(MapLibreTransitIds.ICON_CYAN_NODIR, cyan, hasArrow = false, selected = false)
    add(MapLibreTransitIds.ICON_CYAN_NODIR_SEL, cyan, hasArrow = false, selected = true)

    add(MapLibreTransitIds.ICON_EXT_ARROW, emerald, hasArrow = true, selected = true)
    add(MapLibreTransitIds.ICON_EXT_NODIR, emerald, hasArrow = false, selected = true)

    add(MapLibreTransitIds.ICON_STALE, stale, hasArrow = false, selected = false, muted = true)
    add(MapLibreTransitIds.ICON_STALE_SEL, stale, hasArrow = false, selected = true, muted = true)
}

private fun createBusMarkerBitmap(
    fillArgb: Int,
    inkArgb: Int,
    hasArrow: Boolean,
    selected: Boolean,
    muted: Boolean,
    density: Float,
): Bitmap {
    val sizeDp = if (selected) 56f else 44f
    val sizePx = (sizeDp * density).roundToInt().coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    bitmap.density = (density * 160f).roundToInt().coerceAtLeast(160)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val baseRadius = (if (selected) 15.8f else 12.8f) * density

    // Soft glowing outer halo
    paint.style = Paint.Style.FILL
    paint.color = fillArgb
    paint.alpha = when {
        muted && selected -> 96
        muted -> 48
        selected -> 125
        else -> 68
    }
    canvas.drawCircle(cx, cy, baseRadius + (if (selected) 9.8f else 5.8f) * density, paint)

    // Crisp selection outer ring when selected
    if (selected) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f * density
        paint.color = AndroidColor.WHITE
        paint.alpha = 248
        canvas.drawCircle(cx, cy, baseRadius + 4.6f * density, paint)
    }

    // Dark obsidian rim for unmistakable separation over routes & labels
    paint.style = Paint.Style.FILL
    paint.color = inkArgb
    paint.alpha = if (muted) 195 else 250
    canvas.drawCircle(cx, cy, baseRadius + 2.2f * density, paint)

    // Vibrant core fill
    paint.color = fillArgb
    paint.alpha = if (muted) 185 else 255
    canvas.drawCircle(cx, cy, baseRadius, paint)

    if (!muted) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f * density
        paint.color = AndroidColor.WHITE
        paint.alpha = 135
        canvas.drawCircle(cx, cy, baseRadius - 0.8f * density, paint)
    }

    if (hasArrow) {
        // Sharp directional navigation chevron pointing North (0 deg), rotated via MapLibre iconRotate
        val arrowR = baseRadius * 0.66f
        val path = AndroidPath().apply {
            moveTo(cx, cy - arrowR)
            lineTo(cx + arrowR * 0.72f, cy + arrowR * 0.68f)
            lineTo(cx, cy + arrowR * 0.24f)
            lineTo(cx - arrowR * 0.72f, cy + arrowR * 0.68f)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = inkArgb
        paint.alpha = 255
        canvas.drawPath(path, paint)
    } else {
        // Crisp mini bus silhouette when GPS heading is not reported yet
        val glyphColor = if (muted) AndroidColor.parseColor("#E2E8F0") else inkArgb
        val bw = baseRadius * 0.92f
        val bh = baseRadius * 1.04f
        val body = RectF(cx - bw / 2f, cy - bh / 2f, cx + bw / 2f, cy + bh * 0.42f)
        paint.style = Paint.Style.FILL
        paint.color = glyphColor
        paint.alpha = 245
        canvas.drawRoundRect(body, bw * 0.22f, bw * 0.22f, paint)

        // Windshield cutout
        val wind = RectF(
            body.left + bw * 0.14f,
            body.top + bh * 0.16f,
            body.right - bw * 0.14f,
            body.top + bh * 0.50f,
        )
        paint.color = fillArgb
        paint.alpha = 255
        canvas.drawRoundRect(wind, bw * 0.10f, bw * 0.10f, paint)

        // Headlights
        val lightR = bw * 0.09f
        canvas.drawCircle(body.left + bw * 0.25f, body.bottom - bh * 0.16f, lightR, paint)
        canvas.drawCircle(body.right - bw * 0.25f, body.bottom - bh * 0.16f, lightR, paint)

        // Wheels
        paint.color = glyphColor
        val wheelW = bw * 0.18f
        val wheelH = bh * 0.14f
        canvas.drawRoundRect(
            RectF(body.left + bw * 0.12f, body.bottom - wheelH * 0.2f, body.left + bw * 0.12f + wheelW, body.bottom + wheelH),
            wheelW * 0.35f,
            wheelW * 0.35f,
            paint,
        )
        canvas.drawRoundRect(
            RectF(body.right - bw * 0.12f - wheelW, body.bottom - wheelH * 0.2f, body.right - bw * 0.12f, body.bottom + wheelH),
            wheelW * 0.35f,
            wheelW * 0.35f,
            paint,
        )
    }

    return bitmap
}

internal fun installTransitLayers(style: Style, isDarkBasemap: Boolean) {
    if (style.getSource(MapLibreTransitIds.ROUTE_SOURCE) == null) {
        style.addSource(GeoJsonSource(MapLibreTransitIds.ROUTE_SOURCE))
    }
    if (style.getSource(MapLibreTransitIds.STOPS_SOURCE) == null) {
        style.addSource(GeoJsonSource(MapLibreTransitIds.STOPS_SOURCE))
    }
    if (style.getSource(MapLibreTransitIds.USER_SOURCE) == null) {
        style.addSource(GeoJsonSource(MapLibreTransitIds.USER_SOURCE))
    }
    if (style.getSource(MapLibreTransitIds.BUSES_SOURCE) == null) {
        style.addSource(GeoJsonSource(MapLibreTransitIds.BUSES_SOURCE))
    }
    if (style.getSource(MapLibreTransitIds.EXTERNAL_BUS_SOURCE) == null) {
        style.addSource(GeoJsonSource(MapLibreTransitIds.EXTERNAL_BUS_SOURCE))
    }

    val routeColorExpr = Expression.match(
        Expression.get("dir"),
        Expression.literal("#FFB300"),
        Expression.stop(1, Expression.literal("#38BDF8")),
    )
    val routeGlowExpr = Expression.match(
        Expression.get("dir"),
        Expression.literal("rgba(255, 179, 0, 0.28)"),
        Expression.stop(1, Expression.literal("rgba(56, 189, 248, 0.28)")),
    )
    val routeOffsetExpr = Expression.match(
        Expression.get("offsetSign"),
        Expression.literal(0f),
        Expression.stop(-1, Expression.literal(-1.7f)),
        Expression.stop(1, Expression.literal(1.7f)),
    )

    // 1. Route Polylines (casing -> glow -> crisp core)
    if (style.getLayer(MapLibreTransitIds.ROUTE_CASING_LAYER) == null) {
        style.addLayer(
            LineLayer(MapLibreTransitIds.ROUTE_CASING_LAYER, MapLibreTransitIds.ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(
                    if (isDarkBasemap) "rgba(6, 9, 15, 0.85)" else "rgba(255, 255, 255, 0.84)",
                ),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOffset(routeOffsetExpr),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10.0, 5.2f),
                        Expression.stop(13.5, 7.6f),
                        Expression.stop(17.0, 11.2f),
                    ),
                ),
            ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.ROUTE_GLOW_LAYER) == null) {
        style.addLayer(
            LineLayer(MapLibreTransitIds.ROUTE_GLOW_LAYER, MapLibreTransitIds.ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(routeGlowExpr),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOffset(routeOffsetExpr),
                PropertyFactory.lineBlur(4f),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10.0, 7.5f),
                        Expression.stop(13.5, 11.5f),
                        Expression.stop(17.0, 16.0f),
                    ),
                ),
            ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.ROUTE_CORE_LAYER) == null) {
        style.addLayer(
            LineLayer(MapLibreTransitIds.ROUTE_CORE_LAYER, MapLibreTransitIds.ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(routeColorExpr),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOffset(routeOffsetExpr),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10.0, 2.6f),
                        Expression.stop(13.5, 4.0f),
                        Expression.stop(17.0, 5.8f),
                    ),
                ),
            ),
        )
    }

    // 2. Discreet, Clickable Stop Dots (strict boolean expressions!)
    val isStopSelected = Expression.eq(Expression.get("isSelected"), Expression.literal(true))
    val isStopFav = Expression.eq(Expression.get("isFav"), Expression.literal(true))
    val stopStrokeColor = Expression.match(
        Expression.get("dir"),
        Expression.literal("#FFB300"),
        Expression.stop(1, Expression.literal("#38BDF8")),
    )

    if (style.getLayer(MapLibreTransitIds.STOPS_HALO_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.STOPS_HALO_LAYER, MapLibreTransitIds.STOPS_SOURCE).withProperties(
                PropertyFactory.circleColor(
                    Expression.switchCase(
                        isStopSelected,
                        Expression.literal("#FFFFFF"),
                        Expression.literal("#FFB300"),
                    ),
                ),
                PropertyFactory.circleOpacity(
                    Expression.switchCase(
                        isStopSelected,
                        Expression.literal(0.32f),
                        isStopFav,
                        Expression.literal(0.28f),
                        Expression.literal(0f),
                    ),
                ),
                PropertyFactory.circleRadius(
                    Expression.switchCase(
                        isStopSelected,
                        Expression.literal(14f),
                        isStopFav,
                        Expression.literal(10.5f),
                        Expression.literal(0f),
                    ),
                ),
            ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.STOPS_DOT_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.STOPS_DOT_LAYER, MapLibreTransitIds.STOPS_SOURCE).withProperties(
                PropertyFactory.circleColor(
                    Expression.switchCase(
                        isStopSelected,
                        Expression.literal("#FFFFFF"),
                        isStopFav,
                        Expression.literal("#FFB300"),
                        Expression.literal(if (isDarkBasemap) "#0E131B" else "#FFFFFF"),
                    ),
                ),
                PropertyFactory.circleStrokeColor(
                    Expression.switchCase(
                        isStopSelected,
                        stopStrokeColor,
                        isStopFav,
                        Expression.literal("#0B0F17"),
                        stopStrokeColor,
                    ),
                ),
                PropertyFactory.circleStrokeWidth(
                    Expression.switchCase(
                        isStopSelected,
                        Expression.literal(2.8f),
                        Expression.literal(2.0f),
                    ),
                ),
                PropertyFactory.circleRadius(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(11.0, 2.6f),
                        Expression.stop(13.5, 4.0f),
                        Expression.stop(15.5, 5.5f),
                        Expression.stop(17.5, 7.0f),
                    ),
                ),
            ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.STOPS_SELECTED_LABEL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MapLibreTransitIds.STOPS_SELECTED_LABEL_LAYER, MapLibreTransitIds.STOPS_SOURCE)
                .withFilter(isStopSelected)
                .withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textOffset(arrayOf(0f, -1.65f)),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textHaloColor("rgba(8, 11, 16, 0.92)"),
                    PropertyFactory.textHaloWidth(2.0f),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
        )
    }

    // 3. Optional User GPS Dot (pulsing halo -> crisp cyan dot)
    if (style.getLayer(MapLibreTransitIds.USER_PULSE_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.USER_PULSE_LAYER, MapLibreTransitIds.USER_SOURCE).withProperties(
                PropertyFactory.circleColor("#38BDF8"),
                PropertyFactory.circleOpacity(0.24f),
                PropertyFactory.circleRadius(18f),
            ),
        )
    }
    if (style.getLayer(MapLibreTransitIds.USER_DOT_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.USER_DOT_LAYER, MapLibreTransitIds.USER_SOURCE).withProperties(
                PropertyFactory.circleColor("#38BDF8"),
                PropertyFactory.circleRadius(6.8f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.6f),
            ),
        )
    }

    // 4. Tracked Line Live Moving Buses
    if (style.getLayer(MapLibreTransitIds.BUSES_SELECTED_HALO_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.BUSES_SELECTED_HALO_LAYER, MapLibreTransitIds.BUSES_SOURCE)
                .withFilter(Expression.eq(Expression.get("isSelected"), Expression.literal(true)))
                .withProperties(
                    PropertyFactory.circleColor("#FFB300"),
                    PropertyFactory.circleOpacity(0.28f),
                    PropertyFactory.circleRadius(24f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeOpacity(0.62f),
                    PropertyFactory.circleStrokeWidth(1.6f),
                ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.BUSES_ICON_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MapLibreTransitIds.BUSES_ICON_LAYER, MapLibreTransitIds.BUSES_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconRotate(Expression.toNumber(Expression.get("bearing"))),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get("sortKey"))),
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10.5, 0.78f),
                        Expression.stop(13.5, 0.92f),
                        Expression.stop(16.5, 1.06f),
                    ),
                ),
            ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.BUSES_LABEL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MapLibreTransitIds.BUSES_LABEL_LAYER, MapLibreTransitIds.BUSES_SOURCE)
                .withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textOffset(arrayOf(0f, 1.85f)),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textColor(if (isDarkBasemap) "#F8FAFC" else "#0F172A"),
                    PropertyFactory.textHaloColor(
                        if (isDarkBasemap) "rgba(8, 11, 16, 0.90)" else "rgba(255, 255, 255, 0.92)",
                    ),
                    PropertyFactory.textHaloWidth(1.8f),
                    PropertyFactory.textAllowOverlap(false),
                ),
        )
    }

    // 5. Dedicated TOP-MOST Layers for a Focused External Bus (e.g. tapping Linea 46 or 55 inside a Stop card)
    // Pure static CircleLayers + isolated SymbolLayers so it is 100% immune to collision, filters, or line-bus animation!
    if (style.getLayer(MapLibreTransitIds.EXTERNAL_BUS_HALO_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.EXTERNAL_BUS_HALO_LAYER, MapLibreTransitIds.EXTERNAL_BUS_SOURCE)
                .withProperties(
                    PropertyFactory.circleColor("#10B981"),
                    PropertyFactory.circleOpacity(0.28f),
                    PropertyFactory.circleRadius(27f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeOpacity(0.75f),
                    PropertyFactory.circleStrokeWidth(1.8f),
                ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.EXTERNAL_BUS_CORE_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.EXTERNAL_BUS_CORE_LAYER, MapLibreTransitIds.EXTERNAL_BUS_SOURCE)
                .withProperties(
                    PropertyFactory.circleColor("#10B981"),
                    PropertyFactory.circleRadius(13.5f),
                    PropertyFactory.circleStrokeColor("#0B0F17"),
                    PropertyFactory.circleStrokeWidth(2.4f),
                ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.EXTERNAL_BUS_ICON_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MapLibreTransitIds.EXTERNAL_BUS_ICON_LAYER, MapLibreTransitIds.EXTERNAL_BUS_SOURCE)
                .withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconRotate(Expression.toNumber(Expression.get("bearing"))),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(1.06f),
                ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.EXTERNAL_BUS_LABEL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MapLibreTransitIds.EXTERNAL_BUS_LABEL_LAYER, MapLibreTransitIds.EXTERNAL_BUS_SOURCE)
                .withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(12.5f),
                    PropertyFactory.textOffset(arrayOf(0f, 2.25f)),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textColor("#34D399"),
                    PropertyFactory.textHaloColor("rgba(8, 11, 16, 0.94)"),
                    PropertyFactory.textHaloWidth(2.2f),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
        )
    }
}

internal fun buildRoutesFeatureCollection(
    routePatterns: List<RoutePattern>,
    colorByDirection: Boolean,
): FeatureCollection {
    if (routePatterns.isEmpty()) return FeatureCollection.fromFeatures(emptyArray())
    val hasBothDirs = routePatterns.map { it.directionId }.distinct().size > 1
    val features = routePatterns.mapNotNull { pattern ->
        if (pattern.points.size < 2) return@mapNotNull null
        val coords = pattern.points.map { Point.fromLngLat(it.lon, it.lat) }
        val feature = Feature.fromGeometry(LineString.fromLngLats(coords))
        val dir = if (colorByDirection) pattern.directionId else 0
        val offsetSign = when {
            !hasBothDirs -> 0
            pattern.directionId == 1 -> 1
            else -> -1
        }
        feature.addNumberProperty("dir", dir)
        feature.addNumberProperty("offsetSign", offsetSign)
        feature
    }
    return FeatureCollection.fromFeatures(features)
}

internal fun buildStopsFeatureCollection(
    stops: List<TransitStop>,
    favoriteStopIds: Set<String>,
    selectedStopId: String?,
    colorByDirection: Boolean,
): FeatureCollection {
    if (stops.isEmpty()) return FeatureCollection.fromFeatures(emptyArray())
    val features = stops.map { stop ->
        val feature = Feature.fromGeometry(Point.fromLngLat(stop.lon, stop.lat))
        val dir = if (colorByDirection) stop.directionId else 0
        val isFav = favoriteStopIds.contains(stop.stopId)
        val isSelected = stop.stopId == selectedStopId
        feature.addStringProperty("stopId", stop.stopId)
        feature.addNumberProperty("dir", dir)
        feature.addBooleanProperty("isFav", isFav)
        feature.addBooleanProperty("isSelected", isSelected)
        feature.addStringProperty("label", shortTurinPlaceName(stop.name))
        feature
    }
    return FeatureCollection.fromFeatures(features)
}

internal fun buildBusesFeatureCollection(
    vehicles: List<BusVehicle>,
    poseForBus: (BusVehicle) -> Triple<Double, Double, Float?>,
    selectedVehicleId: String?,
    busHealthById: Map<String, VehicleHealthAssessment>,
    resolvedDirectionById: Map<String, Int?>,
    colorByDirection: Boolean,
    showVehicleLabels: Boolean,
): FeatureCollection {
    if (vehicles.isEmpty()) return FeatureCollection.fromFeatures(emptyArray())
    val features = vehicles.mapNotNull { bus ->
        val (lat, lon, rawBearing) = poseForBus(bus)
        if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
        val bearing = rawBearing?.takeIf { it.isFinite() }
        val isSelected = bus.vehicleId == selectedVehicleId
        val health = busHealthById[bus.vehicleId]
        val isStaleOrDepot = health != null && !health.isReliableLive
        val resolvedDir = resolvedDirectionById[bus.vehicleId] ?: bus.directionId
        val isCyan = colorByDirection && resolvedDir == 1
        val hasArrow = bearing != null && !isStaleOrDepot

        val iconId = when {
            isStaleOrDepot -> if (isSelected) MapLibreTransitIds.ICON_STALE_SEL else MapLibreTransitIds.ICON_STALE
            isCyan -> when {
                hasArrow && isSelected -> MapLibreTransitIds.ICON_CYAN_SEL
                hasArrow -> MapLibreTransitIds.ICON_CYAN
                isSelected -> MapLibreTransitIds.ICON_CYAN_NODIR_SEL
                else -> MapLibreTransitIds.ICON_CYAN_NODIR
            }
            else -> when {
                hasArrow && isSelected -> MapLibreTransitIds.ICON_GOLD_SEL
                hasArrow -> MapLibreTransitIds.ICON_GOLD
                isSelected -> MapLibreTransitIds.ICON_GOLD_NODIR_SEL
                else -> MapLibreTransitIds.ICON_GOLD_NODIR
            }
        }

        val label = if (showVehicleLabels) "#${bus.vehicleId}" else ""
        val sortKey = when {
            isSelected -> 100
            isStaleOrDepot -> 1
            else -> 10
        }

        val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
        feature.addStringProperty("id", bus.vehicleId)
        feature.addStringProperty("icon", iconId)
        feature.addNumberProperty("bearing", (bearing ?: 0f).toDouble())
        feature.addStringProperty("label", label)
        feature.addNumberProperty("sortKey", sortKey)
        feature.addBooleanProperty("isSelected", isSelected)
        feature
    }
    return FeatureCollection.fromFeatures(features)
}

internal fun buildExternalBusFeatureCollection(
    bus: BusVehicle?,
    pose: Triple<Double, Double, Float?>? = null,
): FeatureCollection {
    if (bus == null) return FeatureCollection.fromFeatures(emptyArray())
    val lat = pose?.first ?: bus.latitude
    val lon = pose?.second ?: bus.longitude
    val rawBearing = pose?.third ?: bus.bearing
    if (!lat.isFinite() || !lon.isFinite()) return FeatureCollection.fromFeatures(emptyArray())

    val bearing = rawBearing?.takeIf { it.isFinite() }
    val iconId = if (bearing != null) {
        MapLibreTransitIds.ICON_EXT_ARROW
    } else {
        MapLibreTransitIds.ICON_EXT_NODIR
    }
    val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
    feature.addStringProperty("id", bus.vehicleId)
    feature.addStringProperty("icon", iconId)
    feature.addNumberProperty("bearing", (bearing ?: 0f).toDouble())
    feature.addStringProperty("label", "Linea ${bus.lineCode} · #${bus.label}")
    return FeatureCollection.fromFeature(feature)
}
