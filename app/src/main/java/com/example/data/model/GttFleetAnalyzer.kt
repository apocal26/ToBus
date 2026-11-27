package com.example.data.model

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class GttDepot(
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Double,
)

data class VehicleHealthAssessment(
    val depotName: String?,
    val isStaleGps: Boolean,
    val isStuck: Boolean,
    val isOffRoute: Boolean,
    val gpsAgeSeconds: Long?,
    val stationarySeconds: Long?,
    val isAtTerminus: Boolean = false,
    val terminusStopName: String? = null,
) {
    val isInDepot: Boolean
        get() = depotName != null

    val isInactiveOrSuspicious: Boolean
        get() = isInDepot || isStaleGps || isStuck || isOffRoute

    val isReliableLive: Boolean
        get() = !isInDepot && !isStaleGps && !isStuck && !isOffRoute

    fun warningMessage(): String? {
        return when {
            depotName != null ->
                "Vettura al $depotName — in sosta o fuori servizio."
            isStaleGps && gpsAgeSeconds != null -> {
                val mins = (gpsAgeSeconds / 60L).coerceAtLeast(1L)
                "Segnale GPS vecchio ($mins min fa) — la posizione potrebbe non essere più reale."
            }
            isOffRoute ->
                "Vettura fuori percorso — probabile rientro in deposito o deviazione."
            isStuck ->
                "Questo pullman potrebbe non essere aggiornato"
            else -> null
        }
    }

    fun terminusMessage(): String? {
        if (!isAtTerminus || isInactiveOrSuspicious) return null
        val stopLabel = terminusStopName?.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName)
        return if (stopLabel != null) {
            "Al capolinea ($stopLabel) — in attesa della prossima partenza."
        } else {
            "Al capolinea — in attesa della prossima partenza."
        }
    }
}

data class VehicleSpatialStatus(
    val depotName: String?,
    val isOffRoute: Boolean,
    val isNearTerminus: Boolean = false,
    val distanceToTerminusMeters: Double = Double.POSITIVE_INFINITY,
    val terminusStopName: String? = null,
)

/**
 * Turin GTT domain intelligence:
 * - Real geofences for all major GTT bus & tram depots in Turin
 * - Terminus layover detection (cleanly separated from depot & mid-route stalls)
 * - Stale GPS & stationary/stuck detection
 * - Off-route check against active line geometry
 * - Real Turin GTT fleet series vehicle type decoder (electric BYD/Iveco, articulated, trams)
 */
object GttFleetAnalyzer {

    private const val STALE_GPS_SECONDS = 150L
    private const val STUCK_STATIONARY_SECONDS = 120L
    private const val MATO_PHANTOM_STATIONARY_SECONDS = 240L
    private const val OFF_ROUTE_METERS = 500.0
    private const val TERMINUS_NEAR_METERS = 125.0
    private const val TERMINUS_PHANTOM_GUARD_METERS = 160.0
    private const val TERMINUS_BAY_METERS = 65.0
    private const val TERMINUS_STATIONARY_SECONDS = 20L

    val TURIN_DEPOTS: List<GttDepot> = listOf(
        GttDepot("Deposito Gerbido", 45.0398, 7.6022, 290.0),
        GttDepot("Deposito San Paolo", 45.0598, 7.6463, 210.0),
        GttDepot("Deposito Venaria", 45.1072, 7.6525, 240.0),
        GttDepot("Deposito Tortona", 45.0757, 7.7078, 190.0),
        GttDepot("Deposito Nizza", 45.0252, 7.6656, 210.0),
        GttDepot("Deposito Fiochetto", 45.0846, 7.6844, 160.0),
    )

    fun assessSpatial(
        bus: BusVehicle,
        routePatterns: List<RoutePattern>,
    ): VehicleSpatialStatus {
        val depot = findDepot(bus.latitude, bus.longitude)
        val isOffRoute = if (depot == null && routePatterns.isNotEmpty()) {
            val minDist = minDistanceToRouteMeters(bus.latitude, bus.longitude, routePatterns)
            minDist != null && minDist > OFF_ROUTE_METERS
        } else {
            false
        }
        var bestTerminusDist = Double.POSITIVE_INFINITY
        var bestTerminusName: String? = null
        if (depot == null && !isOffRoute && routePatterns.isNotEmpty()) {
            for (pattern in routePatterns) {
                val firstStop = pattern.stops.firstOrNull()
                val lastStop = pattern.stops.lastOrNull()
                if (firstStop != null) {
                    val d = haversineMeters(bus.latitude, bus.longitude, firstStop.lat, firstStop.lon)
                    if (d < bestTerminusDist) {
                        bestTerminusDist = d
                        bestTerminusName = firstStop.name
                    }
                } else {
                    pattern.points.firstOrNull()?.let { pt ->
                        val d = haversineMeters(bus.latitude, bus.longitude, pt.lat, pt.lon)
                        if (d < bestTerminusDist) {
                            bestTerminusDist = d
                            bestTerminusName = null
                        }
                    }
                }
                if (lastStop != null) {
                    val d = haversineMeters(bus.latitude, bus.longitude, lastStop.lat, lastStop.lon)
                    if (d < bestTerminusDist) {
                        bestTerminusDist = d
                        bestTerminusName = lastStop.name
                    }
                } else {
                    pattern.points.lastOrNull()?.let { pt ->
                        val d = haversineMeters(bus.latitude, bus.longitude, pt.lat, pt.lon)
                        if (d < bestTerminusDist) {
                            bestTerminusDist = d
                            bestTerminusName = null
                        }
                    }
                }
            }
        }
        val isNearTerminus = bestTerminusDist <= TERMINUS_NEAR_METERS
        return VehicleSpatialStatus(
            depotName = depot?.name,
            isOffRoute = isOffRoute,
            isNearTerminus = isNearTerminus,
            distanceToTerminusMeters = bestTerminusDist,
            terminusStopName = if (isNearTerminus) bestTerminusName?.let(::formatTurinLabel) else null,
        )
    }

    fun assessWithSpatial(
        bus: BusVehicle,
        spatial: VehicleSpatialStatus,
        nowMillis: Long,
    ): VehicleHealthAssessment {
        val ageSec = bus.gpsAgeSeconds(nowMillis)
        val isStale = ageSec != null && ageSec > STALE_GPS_SECONDS
        val statSec = bus.stationarySinceMillis?.let { ((nowMillis - it) / 1000L).coerceAtLeast(0L) }
        val isMovingFast = bus.speedKmh != null && bus.speedKmh >= 12f
        val isAtTerminus = spatial.depotName == null &&
            !spatial.isOffRoute &&
            !isStale &&
            spatial.isNearTerminus &&
            !isMovingFast &&
            (
                spatial.distanceToTerminusMeters <= TERMINUS_BAY_METERS ||
                    (statSec != null && statSec >= TERMINUS_STATIONARY_SECONDS) ||
                    bus.bearing == null ||
                    (bus.speedKmh != null && bus.speedKmh < 4f)
                )
        // Never misclassify a bus waiting normally at its terminus as "stuck"
        val isStuck = !spatial.isNearTerminus && statSec != null && statSec >= STUCK_STATIONARY_SECONDS

        return VehicleHealthAssessment(
            depotName = spatial.depotName,
            isStaleGps = isStale,
            isStuck = isStuck,
            isOffRoute = spatial.isOffRoute,
            gpsAgeSeconds = ageSec,
            stationarySeconds = statSec,
            isAtTerminus = isAtTerminus,
            terminusStopName = if (isAtTerminus) spatial.terminusStopName else null,
        )
    }

    fun assess(
        bus: BusVehicle,
        routePatterns: List<RoutePattern>,
        nowMillis: Long,
    ): VehicleHealthAssessment {
        return assessWithSpatial(bus, assessSpatial(bus, routePatterns), nowMillis)
    }

    fun isMatoOnlySource(bus: BusVehicle): Boolean {
        val sources = bus.contributingSources
        return if (sources.isNotEmpty()) {
            sources.all { it.equals("MaTO Live", ignoreCase = true) }
        } else {
            bus.sourceName.equals("MaTO Live", ignoreCase = true)
        }
    }

    /**
     * Returns true ONLY when a vehicle is exclusively tracked by MaTO Live, has been stationary
     * at the exact same coordinates for >= 4 minutes (240s), and is confirmed NOT at/near a terminus
     * (with loaded [routePatterns]) and NOT in a GTT depot. Multi-source vehicles and terminus layovers
     * are never flagged.
     */
    fun isConfirmedMatoPhantomWithSpatial(
        bus: BusVehicle,
        spatial: VehicleSpatialStatus,
        hasKnownRoutePatterns: Boolean,
        nowMillis: Long,
    ): Boolean {
        if (!hasKnownRoutePatterns) return false
        if (!isMatoOnlySource(bus)) return false
        if (spatial.depotName != null ||
            spatial.isNearTerminus ||
            spatial.distanceToTerminusMeters <= TERMINUS_PHANTOM_GUARD_METERS
        ) {
            return false
        }
        if (bus.speedKmh != null && bus.speedKmh >= 5f) return false
        val since = bus.stationarySinceMillis ?: return false
        val statSec = ((nowMillis - since) / 1000L).coerceAtLeast(0L)
        return statSec >= MATO_PHANTOM_STATIONARY_SECONDS
    }

    fun isConfirmedMatoPhantom(
        bus: BusVehicle,
        routePatterns: List<RoutePattern>,
        nowMillis: Long,
    ): Boolean {
        if (routePatterns.isEmpty()) return false
        val spatial = assessSpatial(bus, routePatterns)
        return isConfirmedMatoPhantomWithSpatial(
            bus = bus,
            spatial = spatial,
            hasKnownRoutePatterns = true,
            nowMillis = nowMillis,
        )
    }

    fun filterOutConfirmedMatoPhantoms(
        vehicles: List<BusVehicle>,
        routePatterns: List<RoutePattern>,
        nowMillis: Long,
    ): List<BusVehicle> {
        if (routePatterns.isEmpty() || vehicles.isEmpty()) return vehicles
        return vehicles.filterNot { bus ->
            isConfirmedMatoPhantom(bus, routePatterns, nowMillis)
        }
    }

    fun findDepot(lat: Double, lon: Double): GttDepot? {
        for (depot in TURIN_DEPOTS) {
            if (haversineMeters(lat, lon, depot.lat, depot.lon) <= depot.radiusMeters) {
                return depot
            }
        }
        return null
    }

    /**
     * Decodes common Turin GTT fleet serial numbers into recognizable vehicle categories.
     */
    fun decodeFleetModel(rawLabel: String): String? {
        val num = rawLabel.trim().filter { it.isDigit() }.toIntOrNull() ?: return null
        return when (num) {
            in 100..199 -> "Elettrico BYD / E-Way"
            in 790..879 -> "Autosnodato 18m"
            in 1000..1199 -> "Mercedes Conecto 12m"
            in 1200..1399 -> "Autosnodato 18m"
            in 2200..2499 -> "Bus elettrico BYD"
            in 2500..2699 -> "Iveco Urbanway"
            in 2700..2799 -> "Bus urbano 12m"
            in 2800..2899 -> "Tram serie 2800"
            in 3000..3199 -> "Iveco Crossway / Citelis"
            in 3200..3499 -> "Mercedes Citaro"
            in 3500..3699 -> "Iveco E-Way Elettrico"
            in 5000..5054 -> "Tram serie 5000"
            in 6000..6054 -> "Tram Alstom Cityway"
            in 8000..8099 -> "Tram Hitachi"
            in 9000..9199 -> "Bus elettrico GTT"
            else -> null
        }
    }

    private const val CURRENT_STOP_PROXIMITY_METERS = 85.0
    private const val DECLARED_STOP_VERIFY_METERS = 135.0

    private data class DirectionGeoMatch(
        val directionId: Int,
        val distMeters: Double,
        val angleDiffDeg: Double?,
        val score: Double,
    )

    /**
     * Resolves a bus's real travel direction (`0` = Gold, `1` = Cyan) using route geometry and heading.
     * - Fixes missing `direction_id == null` (very frequent in GTT GTFS-RT VehiclePositions)
     * - Corrects stale/inverted `direction_id` when the vehicle's live heading clearly opposes
     *   the declared polyline tangent and aligns with the opposite branch.
     */
    fun resolveDirectionId(
        bus: BusVehicle,
        routePatterns: List<RoutePattern>,
    ): Int? {
        if (routePatterns.isEmpty()) return bus.directionId

        val patternsByDir = routePatterns
            .filter { p -> p.segments.any { it.size >= 2 } }
            .groupBy { it.directionId }

        val dir0 = patternsByDir[0]
        val dir1 = patternsByDir[1]
        if (dir0.isNullOrEmpty() || dir1.isNullOrEmpty()) {
            return bus.directionId ?: patternsByDir.keys.firstOrNull()
        }

        val m0 = bestMatchForPatterns(bus, dir0, 0)
        val m1 = bestMatchForPatterns(bus, dir1, 1)
        if (m0 == null || m1 == null) return bus.directionId

        // If the bus is far off-route (e.g. heading to depot), do not guess from route geometry
        if (minOf(m0.distMeters, m1.distMeters) > 350.0) {
            return bus.directionId
        }

        val bearing = bus.bearing
        val declared = bus.directionId?.takeIf { it == 0 || it == 1 }

        if (bearing != null && m0.angleDiffDeg != null && m1.angleDiffDeg != null) {
            val dir0StronglyAligned = m0.angleDiffDeg <= 65.0 && m1.angleDiffDeg >= 115.0 && m0.distMeters <= 180.0
            val dir1StronglyAligned = m1.angleDiffDeg <= 65.0 && m0.angleDiffDeg >= 115.0 && m1.distMeters <= 180.0

            if (declared != null) {
                val declaredMatch = if (declared == 0) m0 else m1
                val oppositeMatch = if (declared == 0) m1 else m0
                val headingStrictlyInverted =
                    (declaredMatch.angleDiffDeg ?: 0.0) >= 120.0 &&
                        (oppositeMatch.angleDiffDeg ?: 180.0) <= 60.0 &&
                        oppositeMatch.distMeters <= 160.0
                val clearlyOnOppositeOneWayBranch =
                    declaredMatch.distMeters >= 110.0 &&
                        oppositeMatch.distMeters <= 35.0 &&
                        (oppositeMatch.angleDiffDeg ?: 0.0) <= 85.0
                return if (headingStrictlyInverted || clearlyOnOppositeOneWayBranch) {
                    oppositeMatch.directionId
                } else {
                    declared
                }
            }

            return when {
                dir0StronglyAligned -> 0
                dir1StronglyAligned -> 1
                abs(m0.score - m1.score) >= 28.0 -> if (m0.score < m1.score) 0 else 1
                else -> null
            }
        }

        // Fallback when no heading is available (e.g. freshly polled stationary bus)
        if (declared != null) {
            val declaredMatch = if (declared == 0) m0 else m1
            val oppositeMatch = if (declared == 0) m1 else m0
            return if (declaredMatch.distMeters >= 110.0 && oppositeMatch.distMeters <= 35.0) {
                oppositeMatch.directionId
            } else {
                declared
            }
        }

        // Check if bus.stopId uniquely identifies a nearby stop on one direction
        val stopKey = bus.stopId?.trim()?.takeIf { it.isNotEmpty() }
        if (stopKey != null) {
            val match0 = dir0.flatMap { it.stops }.firstOrNull {
                (it.stopId.equals(stopKey, ignoreCase = true) || it.stopCode.equals(stopKey, ignoreCase = true)) &&
                    haversineMeters(bus.latitude, bus.longitude, it.lat, it.lon) <= 200.0
            }
            val match1 = dir1.flatMap { it.stops }.firstOrNull {
                (it.stopId.equals(stopKey, ignoreCase = true) || it.stopCode.equals(stopKey, ignoreCase = true)) &&
                    haversineMeters(bus.latitude, bus.longitude, it.lat, it.lon) <= 200.0
            }
            if (match0 != null && match1 == null) return 0
            if (match1 != null && match0 == null) return 1
        }

        // One-way corridor separation without bearing
        if (abs(m0.distMeters - m1.distMeters) >= 25.0 && minOf(m0.distMeters, m1.distMeters) <= 140.0) {
            return if (m0.distMeters < m1.distMeters) 0 else 1
        }

        return null
    }

    /**
     * Returns the stop the bus is ACTUALLY at right now, or `null` if the bus is mid-route between stops.
     * Deliberately ignores distant departure/origin stop IDs that GTT feeds often keep attached to a trip.
     */
    fun findCurrentStop(
        bus: BusVehicle,
        routePatterns: List<RoutePattern>,
        resolvedDirectionId: Int? = null,
    ): TransitStop? {
        if (routePatterns.isEmpty()) return null

        val preferredStops = if (resolvedDirectionId != null) {
            routePatterns
                .filter { it.directionId == resolvedDirectionId }
                .flatMap { it.stops }
                .ifEmpty { routePatterns.flatMap { it.stops } }
        } else {
            routePatterns.flatMap { it.stops }
        }
        if (preferredStops.isEmpty()) return null

        // 1. If the feed explicitly reports a current stopId, verify the bus is actually near it
        val reportedStopId = bus.stopId?.trim()?.takeIf { it.isNotEmpty() }
        if (reportedStopId != null) {
            val matched = preferredStops.firstOrNull {
                it.stopId.equals(reportedStopId, ignoreCase = true) ||
                    it.stopCode.equals(reportedStopId, ignoreCase = true)
            }
            if (matched != null) {
                val d = haversineMeters(bus.latitude, bus.longitude, matched.lat, matched.lon)
                if (d <= DECLARED_STOP_VERIFY_METERS) return matched
            }
        }

        // 2. Otherwise check physical proximity to stops along the bus's direction
        var closest: TransitStop? = null
        var closestDist = Double.POSITIVE_INFINITY
        for (stop in preferredStops) {
            val d = haversineMeters(bus.latitude, bus.longitude, stop.lat, stop.lon)
            if (d < closestDist) {
                closestDist = d
                closest = stop
            }
        }
        return if (closest != null && closestDist <= CURRENT_STOP_PROXIMITY_METERS) closest else null
    }

    data class PolylineProjection(
        val alongMeters: Double,
        val perpMeters: Double,
        val tangentBearingDeg: Double,
        val totalLengthMeters: Double,
    )

    data class BusStopApproach(
        val bus: BusVehicle,
        val remainingMeters: Double,
        val intermediateStops: Int,
        val estimatedEtaSeconds: Long,
        val alreadyPassed: Boolean,
        val isTrafficPaused: Boolean = false,
    )

    /**
     * Projects a geographic coordinate onto an ordered polyline and returns exact cumulative
     * distance along the route (`alongMeters`), perpendicular offset (`perpMeters`), and
     * forward tangent bearing (`tangentBearingDeg`).
     */
    fun projectAlongPolyline(
        lat: Double,
        lon: Double,
        points: List<GeoPoint>,
    ): PolylineProjection? {
        if (points.size < 2) return null
        val cosLat = cos(Math.toRadians(lat))
        val metersPerDegLat = 110_540.0
        val metersPerDegLon = 111_320.0 * cosLat

        var cumulative = 0.0
        var bestAlong = 0.0
        var bestPerp = Double.POSITIVE_INFINITY
        var bestTangent = 0.0

        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val ax = (a.lon - lon) * metersPerDegLon
            val ay = (a.lat - lat) * metersPerDegLat
            val bx = (b.lon - lon) * metersPerDegLon
            val by = (b.lat - lat) * metersPerDegLat
            val dx = bx - ax
            val dy = by - ay
            val lenSq = dx * dx + dy * dy
            val segLen = sqrt(lenSq)
            if (lenSq < 0.25) {
                cumulative += segLen
                continue
            }

            val t = (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0)
            val cx = ax + t * dx
            val cy = ay + t * dy
            val perp = hypot(cx, cy)

            if (perp < bestPerp) {
                bestPerp = perp
                bestAlong = cumulative + t * segLen
                bestTangent = if (lenSq >= 64.0) {
                    initialBearingDeg(a.lat, a.lon, b.lat, b.lon)
                } else {
                    val pStart = points[(i - 1).coerceAtLeast(0)]
                    val pEnd = points[(i + 2).coerceAtMost(points.lastIndex)]
                    initialBearingDeg(pStart.lat, pStart.lon, pEnd.lat, pEnd.lon)
                }
            }
            cumulative += segLen
        }

        if (!bestPerp.isFinite()) return null
        return PolylineProjection(
            alongMeters = bestAlong,
            perpMeters = bestPerp,
            tangentBearingDeg = bestTangent,
            totalLengthMeters = cumulative,
        )
    }

    /**
     * Computes a bus's exact street-by-street approach to [stop] along the matching [RoutePattern] polyline.
     * Distinguishes accurately between a bus 150m upstream (arriving in ~1 min) vs a bus 150m past the stop
     * or travelling in the opposite direction — even around sharp urban corners where straight-line bearing fails.
     */
    fun estimateBusApproachToStop(
        bus: BusVehicle,
        stop: TransitStop,
        routePatterns: List<RoutePattern>,
        nowMillis: Long,
        allowTrafficPaused: Boolean = false,
    ): BusStopApproach? {
        val spatial = assessSpatial(bus, routePatterns)
        val health = assessWithSpatial(bus, spatial, nowMillis)
        if (health.isInDepot ||
            health.isStaleGps ||
            health.isOffRoute ||
            isConfirmedMatoPhantomWithSpatial(bus, spatial, routePatterns.isNotEmpty(), nowMillis)
        ) {
            return null
        }
        val isTrafficPaused = health.isStuck
        if (isTrafficPaused && !allowTrafficPaused) return null

        val resolvedDir = resolveDirectionId(bus, routePatterns)
        if (resolvedDir != null && resolvedDir != stop.directionId) return null

        val matchingPattern = routePatterns
            .filter { it.directionId == stop.directionId && it.points.size >= 2 }
            .firstOrNull { pat ->
                pat.stops.any {
                    it.stopId.equals(stop.stopId, ignoreCase = true) ||
                        it.stopCode.equals(stop.stopCode, ignoreCase = true)
                }
            }
            ?: routePatterns.firstOrNull { it.directionId == stop.directionId && it.points.size >= 2 }

        if (matchingPattern != null) {
            val poly = matchingPattern.points
            val busProj = projectAlongPolyline(bus.latitude, bus.longitude, poly) ?: return null
            val stopProj = projectAlongPolyline(stop.lat, stop.lon, poly) ?: return null

            // Bus must actually be near this route branch
            if (busProj.perpMeters > 230.0 || stopProj.perpMeters > 200.0) return null

            // If we have BOTH directions on this route, never guess that a stationary bus with
            // unknown direction AND unknown bearing is approaching this stop unless its reported
            // stopId explicitly matches a stop on this exact direction.
            val hasOppositeDirection = routePatterns.any { it.directionId != stop.directionId && it.points.size >= 2 }
            if (resolvedDir == null && bus.bearing == null && hasOppositeDirection) {
                val reportedStop = bus.stopId?.trim()?.takeIf { it.isNotEmpty() }
                val matchesThisDirStop = reportedStop != null && matchingPattern.stops.any {
                    it.stopId.equals(reportedStop, ignoreCase = true) ||
                        it.stopCode.equals(reportedStop, ignoreCase = true)
                }
                if (!matchesThisDirStop) return null
            }

            // Reject if live heading clearly opposes this polyline's forward direction
            bus.bearing?.let { b ->
                if (angleDiffDeg(b.toDouble(), busProj.tangentBearingDeg) > 105.0) return null
            }

            val remaining = stopProj.alongMeters - busProj.alongMeters
            if (remaining < -75.0) {
                return BusStopApproach(
                    bus = bus,
                    remainingMeters = remaining,
                    intermediateStops = 0,
                    estimatedEtaSeconds = 0L,
                    alreadyPassed = true,
                    isTrafficPaused = isTrafficPaused,
                )
            }

            // Count intermediate stops along this pattern between bus and target stop
            var intermediateStops = 0
            if (remaining > 100.0 && matchingPattern.stops.isNotEmpty()) {
                for (st in matchingPattern.stops) {
                    if (st.stopId.equals(stop.stopId, ignoreCase = true)) continue
                    val p = projectAlongPolyline(st.lat, st.lon, poly) ?: continue
                    if (p.perpMeters <= 120.0 &&
                        p.alongMeters > busProj.alongMeters + 50.0 &&
                        p.alongMeters < stopProj.alongMeters - 50.0
                    ) {
                        intermediateStops++
                    }
                }
            }

            val etaSec = computeUrbanEtaSeconds(
                remainingMeters = remaining,
                intermediateStops = intermediateStops,
                speedKmh = bus.speedKmh,
            )
            return BusStopApproach(
                bus = bus,
                remainingMeters = remaining,
                intermediateStops = intermediateStops,
                estimatedEtaSeconds = etaSec,
                alreadyPassed = false,
                isTrafficPaused = isTrafficPaused,
            )
        }

        // Geometric fallback if route polyline is not yet loaded
        val distMeters = haversineMeters(bus.latitude, bus.longitude, stop.lat, stop.lon)
        if (distMeters > 5500.0) return null
        val bearing = bus.bearing
        if (bearing != null && distMeters > 120.0) {
            val toStop = initialBearingDeg(bus.latitude, bus.longitude, stop.lat, stop.lon)
            val diff = angleDiffDeg(bearing.toDouble(), toStop)
            if (diff > 95.0) {
                return BusStopApproach(
                    bus = bus,
                    remainingMeters = -distMeters,
                    intermediateStops = 0,
                    estimatedEtaSeconds = 0L,
                    alreadyPassed = true,
                    isTrafficPaused = isTrafficPaused,
                )
            }
        } else if (resolvedDir == null && distMeters > 220.0) {
            return null
        }

        val roadMeters = distMeters * 1.18
        val estStops = (roadMeters / 360.0).toInt().coerceAtLeast(0)
        val etaSec = computeUrbanEtaSeconds(roadMeters, estStops, bus.speedKmh)
        return BusStopApproach(
            bus = bus,
            remainingMeters = roadMeters,
            intermediateStops = estStops,
            estimatedEtaSeconds = etaSec,
            alreadyPassed = false,
            isTrafficPaused = isTrafficPaused,
        )
    }

    private fun computeUrbanEtaSeconds(
        remainingMeters: Double,
        intermediateStops: Int,
        speedKmh: Float?,
    ): Long {
        if (remainingMeters <= 95.0) return 20L
        // Turin urban moving speed ~18.5 km/h (5.15 m/s) + 17s avg dwell/signals per intermediate stop
        val movingSpeedMs = speedKmh
            ?.takeIf { it in 10f..44f }
            ?.let { ((it / 3.6) * 0.72).coerceIn(3.8, 7.2) }
            ?: 5.15
        val rawSec = (remainingMeters / movingSpeedMs) + (intermediateStops * 17.0)
        return rawSec.toLong().coerceIn(25L, 48L * 60L)
    }

    private fun bestMatchForPatterns(
        bus: BusVehicle,
        patterns: List<RoutePattern>,
        directionId: Int,
    ): DirectionGeoMatch? {
        val cosLat = cos(Math.toRadians(bus.latitude))
        val metersPerDegLat = 110_540.0
        val metersPerDegLon = 111_320.0 * cosLat
        val busBearing = bus.bearing?.toDouble()

        var best: DirectionGeoMatch? = null

        for (pattern in patterns) {
            for (seg in pattern.segments) {
                if (seg.size < 2) continue
                for (i in 0 until seg.size - 1) {
                    val a = seg[i]
                    val b = seg[i + 1]
                    val ax = (a.lon - bus.longitude) * metersPerDegLon
                    val ay = (a.lat - bus.latitude) * metersPerDegLat
                    val bx = (b.lon - bus.longitude) * metersPerDegLon
                    val by = (b.lat - bus.latitude) * metersPerDegLat
                    val dx = bx - ax
                    val dy = by - ay
                    val lenSq = dx * dx + dy * dy
                    if (lenSq < 1.0) continue

                    val t = (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0)
                    val cx = ax + t * dx
                    val cy = ay + t * dy
                    val dist = hypot(cx, cy)

                    // For very short segments (< 8m), widen tangent span slightly to smooth GPS/OSM jitter
                    val segBearing = if (lenSq >= 64.0) {
                        initialBearingDeg(a.lat, a.lon, b.lat, b.lon)
                    } else {
                        val pStart = seg[(i - 1).coerceAtLeast(0)]
                        val pEnd = seg[(i + 2).coerceAtMost(seg.lastIndex)]
                        initialBearingDeg(pStart.lat, pStart.lon, pEnd.lat, pEnd.lon)
                    }

                    val angleDiff = busBearing?.let { angleDiffDeg(it, segBearing) }
                    val score = dist + (angleDiff?.let { it * 1.6 } ?: 0.0)
                    if (best == null || score < best.score) {
                        best = DirectionGeoMatch(
                            directionId = directionId,
                            distMeters = dist,
                            angleDiffDeg = angleDiff,
                            score = score,
                        )
                    }
                }
            }
        }
        return best
    }

    private fun minDistanceToRouteMeters(
        lat: Double,
        lon: Double,
        patterns: List<RoutePattern>,
    ): Double? {
        val cosLat = cos(Math.toRadians(lat))
        val metersPerDegLat = 110_540.0
        val metersPerDegLon = 111_320.0 * cosLat
        var best = Double.POSITIVE_INFINITY
        var any = false
        for (pattern in patterns) {
            for (segment in pattern.segments) {
                if (segment.isEmpty()) continue
                if (segment.size == 1) {
                    val d = haversineMeters(lat, lon, segment[0].lat, segment[0].lon)
                    if (d < best) best = d
                    any = true
                    continue
                }
                for (i in 0 until segment.size - 1) {
                    val a = segment[i]
                    val b = segment[i + 1]
                    val ax = (a.lon - lon) * metersPerDegLon
                    val ay = (a.lat - lat) * metersPerDegLat
                    val bx = (b.lon - lon) * metersPerDegLon
                    val by = (b.lat - lat) * metersPerDegLat
                    val dx = bx - ax
                    val dy = by - ay
                    val lenSq = dx * dx + dy * dy
                    val d = if (lenSq < 0.25) {
                        hypot(ax, ay)
                    } else {
                        val t = (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0)
                        hypot(ax + t * dx, ay + t * dy)
                    }
                    if (d < best) best = d
                    any = true
                }
            }
        }
        return if (any) best else null
    }

    fun initialBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun angleDiffDeg(a: Double, b: Double): Double =
        abs(((a - b + 540.0) % 360.0) - 180.0)

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        return r * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }
}
