package com.example.map

import com.example.data.model.BusVehicle
import com.example.data.model.CameraState
import com.example.data.model.GeoPoint
import com.example.data.model.RoutePattern
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

object WebMercator {

    const val TILE_SIZE = 256.0

    fun latLonToWorldPixel(lat: Double, lon: Double, zoom: Double): Pair<Double, Double> {
        val clampedLat = lat.coerceIn(-85.05112878, 85.05112878)
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val x = (lon + 180.0) / 360.0 * scale
        val latRad = Math.toRadians(clampedLat)
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * scale
        return x to y
    }

    fun worldPixelToLatLon(worldX: Double, worldY: Double, zoom: Double): GeoPoint {
        val scale = TILE_SIZE * 2.0.pow(zoom)
        val lon = worldX / scale * 360.0 - 180.0
        val n = PI - 2.0 * PI * worldY / scale
        val lat = Math.toDegrees(atan(sinh(n)))
        return GeoPoint(lat.coerceIn(-85.05112878, 85.05112878), lon.coerceIn(-180.0, 180.0))
    }

    fun latLonToScreenOffset(
        lat: Double,
        lon: Double,
        camera: CameraState,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
    ): Pair<Float, Float> {
        val (cx, cy) = latLonToWorldPixel(camera.centerLat, camera.centerLon, camera.zoom)
        val (px, py) = latLonToWorldPixel(lat, lon, camera.zoom)
        val sx = (px - cx + viewportWidthPx / 2.0).toFloat()
        val sy = (py - cy + viewportHeightPx / 2.0).toFloat()
        return sx to sy
    }

    /**
     * Computes a clean camera framing the full route geometry AND active on-route buses
     * (ignoring far-away depot outliers when route geometry exists), so neither the route
     * nor live buses get clipped on the widget or map.
     */
    fun computeAutoFitCamera(
        vehicles: List<BusVehicle>,
        patterns: List<RoutePattern>,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        paddingFraction: Double = 0.16,
        maxZoom: Double = 14.8,
        topPaddingFraction: Double = paddingFraction,
        bottomPaddingFraction: Double = paddingFraction,
    ): CameraState {
        val routePoints = patterns.flatMap { it.points }
        val nonDepotVehicles = vehicles.filter {
            com.example.data.model.GttFleetAnalyzer.findDepot(it.latitude, it.longitude) == null
        }
        val activeVehiclePoints = (nonDepotVehicles.ifEmpty { vehicles }).map {
            GeoPoint(it.latitude, it.longitude)
        }

        val points = when {
            routePoints.isNotEmpty() -> routePoints
            activeVehiclePoints.isNotEmpty() -> activeVehiclePoints
            else -> return CameraState()
        }

        if (points.size == 1) {
            return CameraState(
                centerLat = points[0].lat,
                centerLon = points[0].lon,
                zoom = min(14.2, maxZoom),
            )
        }

        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        for (p in points) {
            minLat = min(minLat, p.lat)
            maxLat = max(maxLat, p.lat)
            minLon = min(minLon, p.lon)
            maxLon = max(maxLon, p.lon)
        }

        // Expand gently to include nearby active buses (within ~800m of route bounds)
        if (routePoints.isNotEmpty() && activeVehiclePoints.isNotEmpty()) {
            val marginDeg = 0.008
            for (v in activeVehiclePoints) {
                if (v.lat in (minLat - marginDeg)..(maxLat + marginDeg) &&
                    v.lon in (minLon - marginDeg)..(maxLon + marginDeg)
                ) {
                    minLat = min(minLat, v.lat)
                    maxLat = max(maxLat, v.lat)
                    minLon = min(minLon, v.lon)
                    maxLon = max(maxLon, v.lon)
                }
            }
        }

        // Compute normalized Mercator span at zoom 0 (width=256)
        val (x1, y1) = latLonToWorldPixel(maxLat, minLon, 0.0)
        val (x2, y2) = latLonToWorldPixel(minLat, maxLon, 0.0)
        val spanX = max(x2 - x1, 0.0005)
        val spanY = max(y2 - y1, 0.0005)

        val usableW = max(viewportWidthPx * (1.0 - paddingFraction * 2.0), 64.0)
        val usableH = max(viewportHeightPx * (1.0 - topPaddingFraction - bottomPaddingFraction), 64.0)

        val zoomX = log2(usableW / spanX)
        val zoomY = log2(usableH / spanY)
        val zoom = min(zoomX, zoomY).coerceIn(10.5, maxZoom)

        // Exact Mercator midpoint shifted to center inside the safe box between top & bottom overlays
        val scale = 2.0.pow(zoom)
        val midWorldX = ((x1 + x2) / 2.0) * scale
        val verticalShiftPx = ((topPaddingFraction - bottomPaddingFraction) / 2.0) * viewportHeightPx
        val midWorldY = ((y1 + y2) / 2.0) * scale - verticalShiftPx
        val centerGeo = worldPixelToLatLon(midWorldX, midWorldY, zoom)

        return CameraState(
            centerLat = centerGeo.lat,
            centerLon = centerGeo.lon,
            zoom = zoom,
        )
    }
}
