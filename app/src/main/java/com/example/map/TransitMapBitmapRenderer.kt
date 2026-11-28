package com.example.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.example.data.model.BusVehicle
import com.example.data.model.CameraState
import com.example.data.model.FavoriteStop
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.MapStyle
import com.example.data.model.RoutePattern
import com.example.data.model.UserWidgetSettings
import com.example.data.model.VehicleFreshness
import com.example.data.model.WidgetCameraMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders ultra-clean, uncluttered Dark Translucent Glass map bitmaps for Home Screen Widgets:
 * - Uses keyless Esri Dark/Light Gray Canvas (ZERO giant neighborhood/district names)
 * - Frames the ENTIRE route geometry cleanly so the line is never clipped
 * - Clean directional bus markers WITHOUT noisy fleet number pills
 * - Automatically hides or dims depot/stale ghost buses
 * - Marks user's favorite stops on the line with subtle golden stars
 */
object TransitMapBitmapRenderer {

    private const val COLOR_GOLD = 0xFFFBBF24.toInt()
    private const val COLOR_CYAN = 0xFF38BDF8.toInt()
    private const val COLOR_RED_STALE = 0xFFEF4444.toInt()
    private const val COLOR_INK_DARK = 0xFF0D0D12.toInt()
    private const val COLOR_WHITE = 0xFFFFFFFF.toInt()
    private const val COLOR_DARK_RING = 0xFF14161F.toInt()

    suspend fun renderWidgetMap(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        settings: UserWidgetSettings,
        vehicles: List<BusVehicle>,
        routePatterns: List<RoutePattern>,
        favoriteStops: List<FavoriteStop> = emptyList(),
        isCompactSquare: Boolean = false,
        cornerRadiusDp: Float = 26f,
    ): Bitmap {
        val safeW = widthPx.coerceIn(280, 1280)
        val safeH = heightPx.coerceIn(220, 1080)
        val scale = density.coerceIn(1.5f, 3.0f)
        val nowMillis = System.currentTimeMillis()

        // Filter out depot / genuinely blocked vehicles in widget when requested
        val visibleVehicles = if (settings.hideInactiveInWidget) {
            vehicles.filter { bus ->
                val health = GttFleetAnalyzer.assess(bus, routePatterns, nowMillis)
                !health.isInDepot && !health.showBlockedWarningOnMap
            }
        } else {
            vehicles
        }

        // Frame full route + active buses cleanly inside the safe area between top & bottom glass pills
        val camera = when (settings.cameraMode) {
            WidgetCameraMode.AUTO_FIT_BUSES -> WebMercator.computeAutoFitCamera(
                vehicles = visibleVehicles,
                patterns = if (settings.showRouteInWidget) routePatterns else emptyList(),
                viewportWidthPx = safeW,
                viewportHeightPx = safeH,
                paddingFraction = if (isCompactSquare) 0.18 else 0.14,
                maxZoom = if (isCompactSquare) 13.6 else 14.2,
                topPaddingFraction = if (isCompactSquare) 0.27 else 0.24,
                bottomPaddingFraction = if (isCompactSquare) 0.19 else 0.17,
            )
            WidgetCameraMode.FIXED_AREA -> settings.fixedCamera
        }

        val bitmap = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val clipPath = Path().apply {
            val r = cornerRadiusDp * scale
            addRoundRect(RectF(0f, 0f, safeW.toFloat(), safeH.toFloat()), r, r, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)

        // 1. Minimalist base background
        drawBaseCanvas(canvas, safeW, safeH, settings.mapStyle)

        // 2. Clean label-free architectural tiles
        drawCleanMapTiles(context, canvas, safeW, safeH, camera, settings.mapStyle)

        val palette = settings.routeColorPalette

        // 3. Crisp glowing route polylines
        if (settings.showRouteInWidget && routePatterns.isNotEmpty()) {
            drawRoutePolylines(
                canvas = canvas,
                widthPx = safeW,
                heightPx = safeH,
                camera = camera,
                patterns = routePatterns,
                isDark = settings.mapStyle.isDark,
                colorByDirection = settings.colorByDirection,
                primaryArgb = palette.primaryArgb,
                secondaryArgb = palette.secondaryArgb,
                scale = scale,
            )
        }

        // 4. Subtle favorite stop markers on the current line
        val lineFavs = if (settings.showFavoriteStopsInWidget) {
            favoriteStops.filter {
                it.lineCode.equals(settings.selectedLine, ignoreCase = true)
            }
        } else {
            emptyList()
        }
        if (lineFavs.isNotEmpty()) {
            drawFavoriteStops(
                canvas = canvas,
                widthPx = safeW,
                heightPx = safeH,
                camera = camera,
                favorites = lineFavs,
                accentArgb = palette.primaryArgb,
                scale = scale,
            )
        }

        // 5. Clean directional bus markers (ZERO fleet label clutter on widget!)
        drawBusMarkers(
            canvas = canvas,
            widthPx = safeW,
            heightPx = safeH,
            camera = camera,
            vehicles = visibleVehicles,
            routePatterns = routePatterns,
            settings = settings,
            nowMillis = nowMillis,
            isCompactSquare = isCompactSquare,
            scale = scale,
        )

        // 6. Subtle top & bottom Smoked Glass scrims so floating pills always stay legible
        drawEdgeScrims(canvas, safeW, safeH, scale)

        canvas.restore()
        return bitmap
    }

    private fun drawBaseCanvas(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        style: MapStyle,
    ) {
        val bg = when (style) {
            MapStyle.DARK -> 0xFF0E1117.toInt()
            MapStyle.POSITRON -> 0xFFECEFF3.toInt()
            MapStyle.VOYAGER -> 0xFFF2EFE9.toInt()
            MapStyle.SATELLITE -> 0xFF0B1218.toInt()
        }
        canvas.drawColor(bg)
    }

    private suspend fun drawCleanMapTiles(
        context: Context,
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        camera: CameraState,
        style: MapStyle,
    ) {
        val tileManager = MapTileManager.getInstance(context)
        // Pick higher tile zoom so 256px tiles are downsampled (razor-sharp HiDPI) instead of upscaled/blurry
        val tileZoom = (camera.zoom + 0.65).roundToInt().coerceIn(11, 16)
        val zoomScale = 2.0.pow(camera.zoom - tileZoom)
        val drawnTileSize = WebMercator.TILE_SIZE * zoomScale

        val (centerWorldX, centerWorldY) = WebMercator.latLonToWorldPixel(
            camera.centerLat,
            camera.centerLon,
            camera.zoom,
        )
        val topLeftWorldX = centerWorldX - widthPx / 2.0
        val topLeftWorldY = centerWorldY - heightPx / 2.0

        val minTileX = floor(topLeftWorldX / drawnTileSize).toInt()
        val maxTileX = ceil((topLeftWorldX + widthPx) / drawnTileSize).toInt()
        val minTileY = floor(topLeftWorldY / drawnTileSize).toInt()
        val maxTileY = ceil((topLeftWorldY + heightPx) / drawnTileSize).toInt()

        val coords = mutableListOf<Pair<Int, Int>>()
        for (ty in minTileY..maxTileY) {
            for (tx in minTileX..maxTileX) {
                coords.add(tx to ty)
            }
        }

        val tiles = coroutineScope {
            coords.map { (tx, ty) ->
                async {
                    val loaded = withTimeoutOrNull(1_800L) {
                        tileManager.loadCleanWidgetTile(tileZoom, tx, ty, style)
                    }
                    QuadTile(tx, ty, loaded?.first, loaded?.second ?: false)
                }
            }.awaitAll()
        }

        val nativePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            alpha = if (style.isDark) 215 else 245
        }
        val filteredPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            alpha = if (style.isDark) 195 else 235
            MapTileManager.colorMatrixForStyle(style)?.let { matrix ->
                colorFilter = ColorMatrixColorFilter(matrix)
            }
        }

        for (t in tiles) {
            val bmp = t.bitmap
            if (bmp == null || bmp.isRecycled) continue
            val left = (t.tx * drawnTileSize - topLeftWorldX).toFloat()
            val top = (t.ty * drawnTileSize - topLeftWorldY).toFloat()
            val dst = RectF(
                left - 0.5f,
                top - 0.5f,
                (left + drawnTileSize).toFloat() + 0.5f,
                (top + drawnTileSize).toFloat() + 0.5f,
            )
            canvas.drawBitmap(bmp, null, dst, if (t.needsFilter) filteredPaint else nativePaint)
        }
    }

    private data class QuadTile(
        val tx: Int,
        val ty: Int,
        val bitmap: Bitmap?,
        val needsFilter: Boolean,
    )

    private fun drawRoutePolylines(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        camera: CameraState,
        patterns: List<RoutePattern>,
        isDark: Boolean,
        colorByDirection: Boolean,
        primaryArgb: Int,
        secondaryArgb: Int,
        scale: Float,
    ) {
        val casingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 4.6f * scale
            color = if (isDark) 0xCC06080E.toInt() else 0xD9FFFFFF.toInt()
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.4f * scale
        }

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 8.0f * scale
        }

        val primaryRoute = (primaryArgb and 0x00FFFFFF) or 0xE6000000.toInt()
        val secondaryRoute = (secondaryArgb and 0x00FFFFFF) or 0xE6000000.toInt()

        for (pattern in patterns) {
            val baseColor = when {
                !colorByDirection -> primaryRoute
                pattern.directionId == 1 -> secondaryRoute
                else -> primaryRoute
            }
            strokePaint.color = baseColor
            glowPaint.color = (baseColor and 0x00FFFFFF) or 0x36000000

            for (segment in pattern.segments) {
                if (segment.size < 2) continue
                val path = Path()
                segment.forEachIndexed { idx, pt ->
                    val (sx, sy) = WebMercator.latLonToScreenOffset(
                        pt.lat,
                        pt.lon,
                        camera,
                        widthPx.toFloat(),
                        heightPx.toFloat(),
                    )
                    if (idx == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                }
                if (isDark) canvas.drawPath(path, glowPaint)
                canvas.drawPath(path, casingPaint)
                canvas.drawPath(path, strokePaint)
            }
        }
    }

    private fun drawFavoriteStops(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        camera: CameraState,
        favorites: List<FavoriteStop>,
        accentArgb: Int,
        scale: Float,
    ) {
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = (accentArgb and 0x00FFFFFF) or 0x44000000
        }
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_DARK_RING
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_WHITE
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.4f * scale
            color = accentArgb
        }

        for (fav in favorites) {
            val (sx, sy) = WebMercator.latLonToScreenOffset(
                fav.lat,
                fav.lon,
                camera,
                widthPx.toFloat(),
                heightPx.toFloat(),
            )
            if (sx < -16f * scale || sx > widthPx + 16f * scale ||
                sy < -16f * scale || sy > heightPx + 16f * scale
            ) {
                continue
            }
            canvas.drawCircle(sx, sy, 8.0f * scale, haloPaint)
            canvas.drawCircle(sx, sy, 4.6f * scale, ringPaint)
            canvas.drawCircle(sx, sy, 3.6f * scale, borderPaint)
            canvas.drawCircle(sx, sy, 1.8f * scale, dotPaint)
        }
    }

    private fun drawBusMarkers(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        camera: CameraState,
        vehicles: List<BusVehicle>,
        routePatterns: List<RoutePattern>,
        settings: UserWidgetSettings,
        nowMillis: Long,
        isCompactSquare: Boolean,
        scale: Float,
    ) {
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (settings.mapStyle.isDark) COLOR_DARK_RING else COLOR_WHITE
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val staleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = COLOR_RED_STALE
        }

        val radius = (if (isCompactSquare) 6.4f else 7.2f) * scale
        val ringRadius = radius + 1.8f * scale
        val haloRadius = radius * 1.95f

        for (bus in vehicles) {
            val (sx, sy) = WebMercator.latLonToScreenOffset(
                bus.latitude,
                bus.longitude,
                camera,
                widthPx.toFloat(),
                heightPx.toFloat(),
            )
            if (sx < -24f * scale || sx > widthPx + 24f * scale ||
                sy < -24f * scale || sy > heightPx + 24f * scale
            ) {
                continue
            }

            val freshness = bus.freshness(nowMillis)
            val health = GttFleetAnalyzer.assess(bus, routePatterns, nowMillis)
            val resolvedDirection = GttFleetAnalyzer.resolveDirectionId(bus, routePatterns)
            val isAltDir = settings.colorByDirection && resolvedDirection == 1
            val accentColor = if (isAltDir) {
                settings.routeColorPalette.secondaryArgb
            } else {
                settings.routeColorPalette.primaryArgb
            }
            val alphaInt = if (health.isInactiveOrSuspicious) 150 else 255

            // Soft ambient halo
            haloPaint.color = (accentColor and 0x00FFFFFF) or (if (health.isInactiveOrSuspicious) 0x20000000 else 0x42000000)
            canvas.drawCircle(sx, sy, haloRadius, haloPaint)

            // Crisp ring + fill (Terminus uses dark center + direction-colored upright pause bars)
            ringPaint.alpha = alphaInt
            canvas.drawCircle(sx, sy, ringRadius, ringPaint)

            if (health.isAtTerminus && !health.isInactiveOrSuspicious) {
                fillPaint.color = accentColor
                fillPaint.alpha = 255
                canvas.drawCircle(sx, sy, radius, fillPaint)
                fillPaint.color = COLOR_INK_DARK
                canvas.drawCircle(sx, sy, radius * 0.78f, fillPaint)

                arrowPaint.color = accentColor
                arrowPaint.alpha = 255
                val bw = radius * 0.20f
                val bh = radius * 0.72f
                val gap = radius * 0.14f
                canvas.drawRoundRect(sx - gap - bw, sy - bh / 2f, sx - gap, sy + bh / 2f, bw * 0.4f, bw * 0.4f, arrowPaint)
                canvas.drawRoundRect(sx + gap, sy - bh / 2f, sx + gap + bw, sy + bh / 2f, bw * 0.4f, bw * 0.4f, arrowPaint)
            } else {
                fillPaint.color = accentColor
                fillPaint.alpha = alphaInt
                canvas.drawCircle(sx, sy, radius, fillPaint)

                // Direction chevron ONLY for moving active buses; upright dot otherwise
                arrowPaint.color = COLOR_INK_DARK
                arrowPaint.alpha = alphaInt
                val bearing = bus.bearing
                if (bearing != null && !health.isInactiveOrSuspicious) {
                    drawDirectionalChevron(canvas, sx, sy, bearing, radius * 0.62f, arrowPaint)
                } else {
                    canvas.drawCircle(sx, sy, radius * 0.30f, arrowPaint)
                }
            }

            // Red corner dot ONLY if genuinely blocked (4+ min immobile), in depot, or off-route
            if (health.showBlockedWarningOnMap || health.isInDepot || health.isOffRoute) {
                canvas.drawCircle(sx + radius * 0.72f, sy - radius * 0.72f, 2.7f * scale, ringPaint)
                canvas.drawCircle(sx + radius * 0.72f, sy - radius * 0.72f, 1.9f * scale, staleDotPaint)
            }
        }
    }

    private fun drawEdgeScrims(canvas: Canvas, widthPx: Int, heightPx: Int, scale: Float) {
        val topH = (40f * scale).coerceAtMost(heightPx * 0.32f)
        val topPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, topH,
                0x80000000.toInt(), 0x00000000,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, widthPx.toFloat(), topH, topPaint)

        val botH = (30f * scale).coerceAtMost(heightPx * 0.26f)
        val botPaint = Paint().apply {
            shader = LinearGradient(
                0f, heightPx - botH, 0f, heightPx.toFloat(),
                0x00000000, 0x80000000.toInt(),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, heightPx - botH, widthPx.toFloat(), heightPx.toFloat(), botPaint)
    }

    private fun drawDirectionalChevron(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        bearingDeg: Float,
        size: Float,
        paint: Paint,
    ) {
        val rad = Math.toRadians(bearingDeg.toDouble())
        val sinA = sin(rad).toFloat()
        val cosA = cos(rad).toFloat()

        fun rotate(dx: Float, dy: Float): Pair<Float, Float> {
            val rx = dx * cosA - dy * sinA
            val ry = dx * sinA + dy * cosA
            return (cx + rx) to (cy + ry)
        }

        val tip = rotate(0f, -size)
        val right = rotate(size * 0.72f, size * 0.76f)
        val notch = rotate(0f, size * 0.25f)
        val left = rotate(-size * 0.72f, size * 0.76f)

        val path = Path().apply {
            moveTo(tip.first, tip.second)
            lineTo(right.first, right.second)
            lineTo(notch.first, notch.second)
            lineTo(left.first, left.second)
            close()
        }
        canvas.drawPath(path, paint)
    }
}
