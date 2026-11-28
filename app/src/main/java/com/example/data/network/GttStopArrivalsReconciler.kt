package com.example.data.network

import com.example.data.model.BusVehicle
import com.example.data.model.GeoPoint
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.RoutePattern
import com.example.data.model.StopArrival
import com.example.data.model.TransitStop
import com.example.data.model.isGenericDirectionLabel
import kotlin.math.abs

/**
 * Pure physics-consistent reconciliation engine matching stop arrival predictions against live
 * GPS positions and route polylines across both the focused line and crossing Turin lines.
 *
 * Responsibilities:
 * - Resolves the true `directionId` of a physical stop on any line's route patterns.
 * - Matches tapped arrival rows strictly to their exact physical [BusVehicle] without guessing.
 * - Overrides wildly wrong feed ETAs when a tracked bus is physically right upstream.
 * - Eliminates post-passage ghosts, depot buses, and physically impossible phantom arrivals.
 */
internal object GttStopArrivalsReconciler {

    /**
     * Determines the exact `directionId` (0 or 1) that [linePatterns] uses when serving [stop].
     *
     * Crucial for Turin transit: physical stop #317 may be `directionId = 1` on Line 12,
     * while being `directionId = 0` on Line 55. Returns `null` when ambiguous on a two-way street
     * (never guesses direction).
     */
    fun resolveStopDirectionOnLinePatterns(
        stop: TransitStop,
        linePatterns: List<RoutePattern>,
    ): Int? {
        if (linePatterns.isEmpty()) return null
        val stopKeys = setOf(
            stop.stopId.trim().substringAfterLast(":"),
            stop.stopCode.trim().substringAfterLast(":"),
        ).filter { it.isNotEmpty() }.toSet()

        // 1. Authoritative exact stopId / stopCode match inside pattern.stops
        if (stopKeys.isNotEmpty()) {
            val exactDirs = linePatterns
                .filter { pat ->
                    pat.stops.any { st ->
                        st.stopId.trim().substringAfterLast(":") in stopKeys ||
                            st.stopCode.trim().substringAfterLast(":") in stopKeys
                    }
                }
                .map { it.directionId }
                .distinct()
            if (exactDirs.size == 1) return exactDirs.first()
        }

        // 2. Same physical pole match by coordinates (<= 35m)
        val bestStopDistByDir = HashMap<Int, Double>()
        for (pat in linePatterns) {
            for (st in pat.stops) {
                val d = GttFleetAnalyzer.haversineMeters(stop.lat, stop.lon, st.lat, st.lon)
                if (d <= 35.0) {
                    val prev = bestStopDistByDir[pat.directionId]
                    if (prev == null || d < prev) bestStopDistByDir[pat.directionId] = d
                }
            }
        }
        if (bestStopDistByDir.size == 1) return bestStopDistByDir.keys.first()
        if (bestStopDistByDir.size >= 2) {
            val sorted = bestStopDistByDir.entries.sortedBy { it.value }
            if (sorted[0].value <= 15.0 && sorted[1].value - sorted[0].value >= 12.0) {
                return sorted[0].key
            }
        }

        // 3. Single-direction (circular) line or physically separated one-way branches
        val distinctDirs = linePatterns.map { it.directionId }.distinct()
        if (distinctDirs.size == 1) return distinctDirs.first()

        val bestPolyDistByDir = HashMap<Int, Double>()
        for (pat in linePatterns) {
            if (pat.points.size < 2) continue
            val proj = GttFleetAnalyzer.projectAlongPolyline(stop.lat, stop.lon, pat.points) ?: continue
            val prev = bestPolyDistByDir[pat.directionId]
            if (prev == null || proj.perpMeters < prev) bestPolyDistByDir[pat.directionId] = proj.perpMeters
        }
        if (bestPolyDistByDir.size == 1) return bestPolyDistByDir.keys.first()
        if (bestPolyDistByDir.size >= 2) {
            val sorted = bestPolyDistByDir.entries.sortedBy { it.value }
            if (sorted[0].value <= 40.0 && sorted[1].value - sorted[0].value >= 30.0) {
                return sorted[0].key
            }
        }
        return null
    }

    /**
     * Strictly resolves the EXACT live [BusVehicle] for a tapped [StopArrival] on ANY line
     * (tracked line or another line like 55/5).
     *
     * NEVER falls back to blind "nearest straight-line bus" guessing. A bus is returned ONLY when:
     * 1. Exact `vehicleId` matches (and is not in depot / opposite direction / already passed), OR
     * 2. Exact `tripId` matches (same safety checks), OR
     * 3. `arrival.isRealtime` AND [targetLinePatterns] resolves the line's exact direction at [stop]
     *    AND an unclaimed upstream bus along that exact polyline has an ETA matching `arrival`'s ETA.
     */
    fun findExactBusForArrival(
        arrival: StopArrival,
        stop: TransitStop,
        allLivePool: List<BusVehicle>,
        targetLinePatterns: List<RoutePattern>,
        claimedVehicleIds: Set<String> = emptySet(),
        nowMillis: Long,
    ): BusVehicle? {
        val nowSec = nowMillis / 1000L
        val targetLine = arrival.lineCode.trim().uppercase()
        val resolvedStopDir = resolveStopDirectionOnLinePatterns(stop, targetLinePatterns)
        val stopOnTargetLine = resolvedStopDir?.let { stop.copy(directionId = it) }

        fun isValidMatchedBus(bus: BusVehicle): Boolean {
            val health = GttFleetAnalyzer.assess(bus, targetLinePatterns, nowMillis)
            if (health.isInDepot ||
                health.isStaleGps ||
                GttFleetAnalyzer.isConfirmedMatoPhantom(bus, targetLinePatterns, nowMillis)
            ) {
                return false
            }
            if (stopOnTargetLine != null && targetLinePatterns.isNotEmpty()) {
                val busDir = GttFleetAnalyzer.resolveDirectionId(bus, targetLinePatterns)
                if (busDir != null && busDir != stopOnTargetLine.directionId) return false
                val approach = GttFleetAnalyzer.estimateBusApproachToStop(
                    bus = bus,
                    stop = stopOnTargetLine,
                    routePatterns = targetLinePatterns,
                    nowMillis = nowMillis,
                    allowTrafficPaused = true,
                )
                if (approach != null && approach.alreadyPassed) return false
            }
            return true
        }

        // 1. Canonical vehicleId match
        val vid = GtfsRtProtobufParser.canonicalFleetId(arrival.vehicleId).ifBlank { null }
        if (vid != null) {
            val exactVeh = allLivePool.firstOrNull {
                (GtfsRtProtobufParser.canonicalFleetId(it.vehicleId).equals(vid, ignoreCase = true) ||
                    GtfsRtProtobufParser.canonicalFleetId(it.label).equals(vid, ignoreCase = true)) &&
                    (it.lineCode.equals(targetLine, ignoreCase = true) || it.rawRouteId.equals(targetLine, ignoreCase = true))
            }
            if (exactVeh != null && isValidMatchedBus(exactVeh)) return exactVeh
        }

        // 2. Normalized tripId match
        val cleanTrip = GtfsRtProtobufParser.normalizeTripId(arrival.tripId)
        if (cleanTrip != null) {
            val exactTripBus = allLivePool.firstOrNull { bus ->
                val busTrip = GtfsRtProtobufParser.normalizeTripId(bus.tripId)
                busTrip != null && busTrip.equals(cleanTrip, ignoreCase = true) &&
                    (bus.lineCode.equals(targetLine, ignoreCase = true) || bus.rawRouteId.equals(targetLine, ignoreCase = true))
            }
            if (exactTripBus != null && isValidMatchedBus(exactTripBus)) return exactTripBus
        }

        // 3. Strict polyline-verified same-direction ETA match ONLY for realtime arrivals
        if (!arrival.isRealtime || stopOnTargetLine == null || targetLinePatterns.isEmpty()) {
            return null
        }

        val targetEtaSec = (arrival.arrivalEpochSeconds - nowSec).coerceAtLeast(0L)
        val maxEtaErrorSec = maxOf(210L, (targetEtaSec * 0.45).toLong())

        val candidates = allLivePool.mapNotNull { bus ->
            if (!bus.lineCode.equals(targetLine, ignoreCase = true) &&
                !bus.rawRouteId.equals(targetLine, ignoreCase = true)
            ) {
                return@mapNotNull null
            }
            if (bus.vehicleId in claimedVehicleIds) return@mapNotNull null
            val candidateHealth = GttFleetAnalyzer.assess(bus, targetLinePatterns, nowMillis)
            if (candidateHealth.isInDepot ||
                candidateHealth.isStaleGps ||
                GttFleetAnalyzer.isConfirmedMatoPhantom(bus, targetLinePatterns, nowMillis)
            ) {
                return@mapNotNull null
            }
            val approach = GttFleetAnalyzer.estimateBusApproachToStop(
                bus = bus,
                stop = stopOnTargetLine,
                routePatterns = targetLinePatterns,
                nowMillis = nowMillis,
                allowTrafficPaused = true,
            ) ?: return@mapNotNull null
            if (approach.alreadyPassed) return@mapNotNull null
            val etaDiff = abs(approach.estimatedEtaSeconds - targetEtaSec)
            if (etaDiff > maxEtaErrorSec) return@mapNotNull null
            bus to etaDiff
        }

        return candidates.minByOrNull { it.second }?.first
    }

    /**
     * Reconciles raw stop arrivals from GTT Palina / MATO / TripUpdates with live GPS vehicle
     * positions across ALL Turin lines:
     * 1. Corrects wildly erroneous feed ETAs when a tracked live bus is physically right upstream.
     * 2. Drops stale ghost predictions for buses that already passed the stop or are heading away.
     * 3. Absorbs delayed-bus timetable slots (-3 min..+8 min) so a delayed bus never shows twice.
     * 4. Links exact verified `vehicleId` onto BOTH [preferredLine] and other-line arrivals using
     *    each line's TRUE direction and polyline at this stop.
     * 5. Drops physically impossible unlinked phantom arrivals on BOTH [preferredLine] and other lines.
     */
    fun reconcileLiveVehiclesWithArrivals(
        stop: TransitStop,
        preferredLine: String,
        liveVehicles: List<BusVehicle>,
        allLivePool: List<BusVehicle>,
        routePatterns: List<RoutePattern>,
        rawArrivals: List<StopArrival>,
        nowMillis: Long,
        otherLinePatterns: Map<String, List<RoutePattern>> = emptyMap(),
    ): List<StopArrival> {
        val nowSec = nowMillis / 1000L
        val normPref = preferredLine.trim().uppercase()
        val dirPattern = routePatterns
            .filter { it.directionId == stop.directionId && it.points.size >= 2 }
            .firstOrNull { pat ->
                pat.stops.any {
                    it.stopId.equals(stop.stopId, ignoreCase = true) ||
                        it.stopCode.equals(stop.stopCode, ignoreCase = true)
                }
            }
            ?: routePatterns.firstOrNull { it.directionId == stop.directionId && it.points.size >= 2 }
        val dirTerminus = dirPattern?.terminusName()
        val stopAlongMeters = dirPattern?.let { pat ->
            GttFleetAnalyzer.projectAlongPolyline(stop.lat, stop.lon, pat.points)
                ?.takeIf { it.perpMeters <= 220.0 }
                ?.alongMeters
        }
        val terminusFloorSec: Long? = stopAlongMeters?.let { along ->
            if (along < 250.0) 90L else (along / 5.8).toLong().coerceIn(180L, 1_200L)
        }
        val prefOriginPoint = dirPattern?.stops?.firstOrNull()?.let { GeoPoint(it.lat, it.lon) }
            ?: dirPattern?.points?.firstOrNull()

        val prefLineVehicles = GttFleetAnalyzer.filterOutConfirmedMatoPhantoms(
            vehicles = liveVehicles.filter {
                it.lineCode.equals(normPref, ignoreCase = true) ||
                    it.rawRouteId.equals(normPref, ignoreCase = true)
            },
            routePatterns = routePatterns,
            nowMillis = nowMillis,
        )
        val approachByVehicleId = LinkedHashMap<String, GttFleetAnalyzer.BusStopApproach>()
        for (bus in prefLineVehicles) {
            val approach = GttFleetAnalyzer.estimateBusApproachToStop(
                bus = bus,
                stop = stop,
                routePatterns = routePatterns,
                nowMillis = nowMillis,
                allowTrafficPaused = true,
            ) ?: continue
            val canonVid = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId }
            approachByVehicleId[canonVid] = approach
        }
        val approachingUpstream = approachByVehicleId.values
            .filter { !it.alreadyPassed && it.remainingMeters <= 10_000.0 }
            .sortedBy { it.remainingMeters }
        val prefRecentlyPassed = approachByVehicleId.values
            .filter { it.alreadyPassed && it.remainingMeters in -950.0..-75.0 }

        val allLiveById = allLivePool.associateBy {
            GtfsRtProtobufParser.canonicalFleetId(it.vehicleId).ifBlank { it.vehicleId }
        }
        val reliableLiveByLine = HashMap<String, MutableList<BusVehicle>>()
        for (bus in allLivePool) {
            val h = GttFleetAnalyzer.assess(bus, emptyList(), nowMillis)
            if (h.isInDepot || h.isStaleGps) continue
            val key = bus.lineCode.trim().uppercase()
            if (key.isNotEmpty()) {
                reliableLiveByLine.getOrPut(key) { ArrayList() }.add(bus)
            }
        }
        val hasCityWideLiveFeed = allLivePool.size >= 25

        data class OtherLineContext(
            val patterns: List<RoutePattern>,
            val stopDirectionId: Int,
            val terminusName: String?,
            val terminusFloorSec: Long?,
            val originPoint: GeoPoint?,
            val activeBuses: List<BusVehicle>,
            val approachByVehId: Map<String, GttFleetAnalyzer.BusStopApproach>,
            val approachingUpstream: List<GttFleetAnalyzer.BusStopApproach>,
            val recentlyPassedDownstream: List<GttFleetAnalyzer.BusStopApproach>,
        )
        val otherLineContexts = HashMap<String, OtherLineContext>()
        for ((rawKey, patterns) in otherLinePatterns) {
            val lineKey = rawKey.trim().uppercase()
            if (lineKey.isEmpty() || lineKey == normPref || patterns.isEmpty()) continue
            val otherDir = resolveStopDirectionOnLinePatterns(stop, patterns) ?: continue
            val stopForOther = stop.copy(directionId = otherDir)
            val otherDirPat = patterns
                .filter { it.directionId == otherDir && it.points.size >= 2 }
                .firstOrNull { pat ->
                    pat.stops.any {
                        it.stopId.equals(stop.stopId, ignoreCase = true) ||
                            it.stopCode.equals(stop.stopCode, ignoreCase = true)
                    }
                }
                ?: patterns.firstOrNull { it.directionId == otherDir && it.points.size >= 2 }
            val otherAlong = otherDirPat?.let { pat ->
                GttFleetAnalyzer.projectAlongPolyline(stop.lat, stop.lon, pat.points)
                    ?.takeIf { it.perpMeters <= 220.0 }
                    ?.alongMeters
            }
            val otherFloor = otherAlong?.let { along ->
                if (along < 250.0) 90L else (along / 5.8).toLong().coerceIn(180L, 1_200L)
            }
            val otherOriginPoint = otherDirPat?.stops?.firstOrNull()?.let { GeoPoint(it.lat, it.lon) }
                ?: otherDirPat?.points?.firstOrNull()
            val otherBuses = GttFleetAnalyzer.filterOutConfirmedMatoPhantoms(
                vehicles = reliableLiveByLine[lineKey].orEmpty(),
                routePatterns = patterns,
                nowMillis = nowMillis,
            )
            val appMap = LinkedHashMap<String, GttFleetAnalyzer.BusStopApproach>()
            for (bus in otherBuses) {
                val app = GttFleetAnalyzer.estimateBusApproachToStop(
                    bus = bus,
                    stop = stopForOther,
                    routePatterns = patterns,
                    nowMillis = nowMillis,
                    allowTrafficPaused = true,
                ) ?: continue
                val canonBusId = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId }
                appMap[canonBusId] = app
            }
            val upstream = appMap.values
                .filter { !it.alreadyPassed && it.remainingMeters <= 10_000.0 }
                .sortedBy { it.remainingMeters }
            val recentlyPassed = appMap.values
                .filter { it.alreadyPassed && it.remainingMeters in -950.0..-75.0 }
            otherLineContexts[lineKey] = OtherLineContext(
                patterns = patterns,
                stopDirectionId = otherDir,
                terminusName = otherDirPat?.terminusName(),
                terminusFloorSec = otherFloor,
                originPoint = otherOriginPoint,
                activeBuses = otherBuses,
                approachByVehId = appMap,
                approachingUpstream = upstream,
                recentlyPassedDownstream = recentlyPassed,
            )
        }

        // Process already-linked arrivals first, then unlinked realtime arrivals, then scheduled slots
        val orderedRawArrivals = rawArrivals.sortedWith(
            compareByDescending<StopArrival> { !it.vehicleId.isNullOrBlank() }
                .thenByDescending { it.isRealtime }
                .thenBy { it.arrivalEpochSeconds },
        )

        val coveredVehicleIds = HashSet<String>()
        val out = mutableListOf<StopArrival>()

        for (arr in orderedRawArrivals) {
            val lineKey = arr.lineCode.trim().uppercase()
            val isPref = lineKey.equals(normPref, ignoreCase = true)
            val vid = GtfsRtProtobufParser.canonicalFleetId(arr.vehicleId).ifBlank { null }
            val feedEtaSec = arr.arrivalEpochSeconds - nowSec

            if (isPref && vid != null) {
                val approach = approachByVehicleId[vid]
                if (approach != null) {
                    if (approach.alreadyPassed) {
                        if (feedEtaSec <= 15L * 60L) {
                            continue
                        } else {
                            out.add(
                                arr.copy(
                                    isRealtime = false,
                                    vehicleId = null,
                                    sourceLabel = "Orario",
                                ),
                            )
                            continue
                        }
                    } else {
                        val gpsEtaSec = approach.estimatedEtaSeconds
                        val maxToleranceSec = maxOf(300L, (gpsEtaSec * 0.65).toLong())
                        val wildlyWrongFeed = abs(feedEtaSec - gpsEtaSec) > maxToleranceSec
                        val nearStopButFeedHuge = approach.remainingMeters <= 1_400.0 && feedEtaSec > gpsEtaSec + 240L
                        coveredVehicleIds.add(vid)
                        if (wildlyWrongFeed || nearStopButFeedHuge) {
                            out.add(
                                arr.copy(
                                    arrivalEpochSeconds = nowSec + gpsEtaSec,
                                    isRealtime = true,
                                    vehicleId = vid,
                                    headsign = arr.headsign.takeUnless { it.startsWith("Linea ", true) }
                                        ?: dirTerminus
                                        ?: arr.headsign,
                                    sourceLabel = "GPS Live",
                                ),
                            )
                        } else {
                            out.add(arr)
                        }
                        continue
                    }
                } else {
                    val knownBus = prefLineVehicles.firstOrNull {
                        GtfsRtProtobufParser.canonicalFleetId(it.vehicleId) == vid
                    }
                    if (knownBus != null) {
                        val resolvedDir = GttFleetAnalyzer.resolveDirectionId(knownBus, routePatterns)
                        val health = GttFleetAnalyzer.assess(knownBus, routePatterns, nowMillis)
                        if (health.isInDepot || (resolvedDir != null && resolvedDir != stop.directionId)) {
                            if (feedEtaSec <= 12L * 60L) {
                                continue
                            } else {
                                out.add(arr.copy(isRealtime = false, vehicleId = null, sourceLabel = "Orario"))
                                continue
                            }
                        }
                    }
                }
            } else if (!isPref) {
                val otherCtx = otherLineContexts[lineKey]
                if (vid != null) {
                    val otherApproach = otherCtx?.approachByVehId?.get(vid)
                    if (otherCtx != null && otherApproach != null) {
                        if (otherApproach.alreadyPassed) {
                            if (feedEtaSec <= 15L * 60L) {
                                continue
                            } else {
                                out.add(arr.copy(isRealtime = false, vehicleId = null, sourceLabel = "Orario"))
                                continue
                            }
                        } else {
                            val gpsEtaSec = otherApproach.estimatedEtaSeconds
                            val maxToleranceSec = maxOf(300L, (gpsEtaSec * 0.65).toLong())
                            val wildlyWrongFeed = abs(feedEtaSec - gpsEtaSec) > maxToleranceSec
                            val nearStopButFeedHuge = otherApproach.remainingMeters <= 1_400.0 && feedEtaSec > gpsEtaSec + 240L
                            coveredVehicleIds.add(vid)
                            if (wildlyWrongFeed || nearStopButFeedHuge) {
                                out.add(
                                    arr.copy(
                                        arrivalEpochSeconds = nowSec + gpsEtaSec,
                                        isRealtime = true,
                                        vehicleId = vid,
                                        headsign = arr.headsign.takeUnless { it.startsWith("Linea ", true) }
                                            ?: otherCtx.terminusName
                                            ?: arr.headsign,
                                        sourceLabel = "GPS Live",
                                    ),
                                )
                            } else {
                                out.add(arr)
                            }
                            continue
                        }
                    } else if (otherCtx != null) {
                        val knownOther = allLiveById[vid]
                        if (knownOther != null) {
                            val busDir = GttFleetAnalyzer.resolveDirectionId(knownOther, otherCtx.patterns)
                            val h = GttFleetAnalyzer.assess(knownOther, otherCtx.patterns, nowMillis)
                            if (h.isInDepot || (busDir != null && busDir != otherCtx.stopDirectionId)) {
                                if (feedEtaSec <= 12L * 60L) {
                                    continue
                                } else {
                                    out.add(arr.copy(isRealtime = false, vehicleId = null, sourceLabel = "Orario"))
                                    continue
                                }
                            }
                        }
                    } else if (arr.isRealtime) {
                        val otherBus = allLiveById[vid]
                        if (otherBus != null) {
                            val distM = GttFleetAnalyzer.haversineMeters(
                                otherBus.latitude,
                                otherBus.longitude,
                                stop.lat,
                                stop.lon,
                            )
                            val bearing = otherBus.bearing
                            val toStop = GttFleetAnalyzer.initialBearingDeg(
                                otherBus.latitude,
                                otherBus.longitude,
                                stop.lat,
                                stop.lon,
                            )
                            val angleDiff = bearing?.let {
                                GttFleetAnalyzer.angleDiffDeg(it.toDouble(), toStop)
                            }
                            if (distM <= 260.0 && (angleDiff == null || angleDiff <= 80.0) && feedEtaSec > 8L * 60L) {
                                val fixedSec = ((distM / 4.8).toLong()).coerceIn(20L, 90L)
                                out.add(arr.copy(arrivalEpochSeconds = nowSec + fixedSec, sourceLabel = "GPS Live"))
                                continue
                            }
                            if (distM >= 450.0 && angleDiff != null && angleDiff >= 135.0 && feedEtaSec <= 120L) {
                                continue
                            }
                        }
                    }
                } else if (arr.isRealtime && otherCtx != null && otherCtx.approachingUpstream.isNotEmpty()) {
                    val maxGap = maxOf(240L, (feedEtaSec.coerceAtLeast(0L) * 0.50).toLong())
                    val bestMatch = otherCtx.approachingUpstream
                        .filter {
                            val cid = GtfsRtProtobufParser.canonicalFleetId(it.bus.vehicleId).ifBlank { it.bus.vehicleId }
                            cid !in coveredVehicleIds
                        }
                        .minByOrNull { abs(it.estimatedEtaSeconds - feedEtaSec) }
                    if (bestMatch != null && abs(bestMatch.estimatedEtaSeconds - feedEtaSec) <= maxGap) {
                        val cid = GtfsRtProtobufParser.canonicalFleetId(bestMatch.bus.vehicleId).ifBlank { bestMatch.bus.vehicleId }
                        coveredVehicleIds.add(cid)
                        out.add(
                            arr.copy(
                                vehicleId = bestMatch.bus.vehicleId,
                                tripId = arr.tripId ?: bestMatch.bus.tripId,
                            ),
                        )
                        continue
                    }
                }
            }

            if (isPref && vid == null && arr.isRealtime && approachingUpstream.isNotEmpty()) {
                val bestMatch = approachingUpstream
                    .filter {
                        val cid = GtfsRtProtobufParser.canonicalFleetId(it.bus.vehicleId).ifBlank { it.bus.vehicleId }
                        cid !in coveredVehicleIds
                    }
                    .minByOrNull { abs(it.estimatedEtaSeconds - feedEtaSec) }
                if (bestMatch != null) {
                    val maxGap = maxOf(300L, (bestMatch.estimatedEtaSeconds * 0.65).toLong())
                    if (abs(bestMatch.estimatedEtaSeconds - feedEtaSec) <= maxGap) {
                        val cid = GtfsRtProtobufParser.canonicalFleetId(bestMatch.bus.vehicleId).ifBlank { bestMatch.bus.vehicleId }
                        coveredVehicleIds.add(cid)
                        out.add(arr.copy(vehicleId = bestMatch.bus.vehicleId))
                        continue
                    }
                }
            }

            out.add(arr)
        }

        // Always ensure EVERY approaching reliable live bus on preferredLine is represented
        for (approach in approachingUpstream) {
            val cid = GtfsRtProtobufParser.canonicalFleetId(approach.bus.vehicleId).ifBlank { approach.bus.vehicleId }
            if (cid in coveredVehicleIds) continue
            val existingCloseIdx = out.withIndex()
                .filter { (_, existing) ->
                    if (!existing.lineCode.equals(normPref, ignoreCase = true) || existing.vehicleId != null) {
                        return@filter false
                    }
                    val existingEta = existing.arrivalEpochSeconds - nowSec
                    val delta = approach.estimatedEtaSeconds - existingEta
                    if (existing.isRealtime) {
                        abs(delta) <= 270L
                    } else {
                        delta in -240L..540L
                    }
                }
                .minByOrNull { (_, existing) ->
                    abs((existing.arrivalEpochSeconds - nowSec) - approach.estimatedEtaSeconds)
                }
                ?.index

            if (existingCloseIdx != null) {
                val existing = out[existingCloseIdx]
                val existingEta = existing.arrivalEpochSeconds - nowSec
                val shouldUpdateEta = !existing.isRealtime || abs(existingEta - approach.estimatedEtaSeconds) > 180L
                out[existingCloseIdx] = existing.copy(
                    arrivalEpochSeconds = if (shouldUpdateEta) nowSec + approach.estimatedEtaSeconds else existing.arrivalEpochSeconds,
                    isRealtime = true,
                    vehicleId = approach.bus.vehicleId,
                    tripId = existing.tripId ?: approach.bus.tripId,
                    headsign = existing.headsign.takeUnless { it.startsWith("Linea ", true) }
                        ?: dirTerminus
                        ?: existing.headsign,
                    sourceLabel = if (shouldUpdateEta) "GPS Live" else existing.sourceLabel,
                )
                coveredVehicleIds.add(cid)
            } else if (!approach.isTrafficPaused && approach.remainingMeters <= 6_500.0) {
                out.add(
                    StopArrival(
                        lineCode = normPref,
                        headsign = dirTerminus ?: "Linea $normPref",
                        arrivalEpochSeconds = nowSec + approach.estimatedEtaSeconds,
                        isRealtime = true,
                        delaySeconds = approach.bus.delaySeconds,
                        vehicleId = approach.bus.vehicleId,
                        tripId = approach.bus.tripId,
                        sourceLabel = "GPS Live",
                    ),
                )
                coveredVehicleIds.add(cid)
            }
        }

        // For OTHER lines with resolved route geometry, absorb nearby unlinked scheduled slots
        for ((lineKey, otherCtx) in otherLineContexts) {
            for (approach in otherCtx.approachingUpstream) {
                val cid = GtfsRtProtobufParser.canonicalFleetId(approach.bus.vehicleId).ifBlank { approach.bus.vehicleId }
                if (cid in coveredVehicleIds) continue
                val existingCloseIdx = out.withIndex()
                    .filter { (_, existing) ->
                        if (!existing.lineCode.equals(lineKey, ignoreCase = true) || existing.vehicleId != null) {
                            return@filter false
                        }
                        val existingEta = existing.arrivalEpochSeconds - nowSec
                        val delta = approach.estimatedEtaSeconds - existingEta
                        if (existing.isRealtime) {
                            abs(delta) <= 270L
                        } else {
                            delta in -240L..540L
                        }
                    }
                    .minByOrNull { (_, existing) ->
                        abs((existing.arrivalEpochSeconds - nowSec) - approach.estimatedEtaSeconds)
                    }
                    ?.index
                if (existingCloseIdx != null) {
                    val existing = out[existingCloseIdx]
                    val existingEta = existing.arrivalEpochSeconds - nowSec
                    val shouldUpdateEta = !existing.isRealtime || abs(existingEta - approach.estimatedEtaSeconds) > 180L
                    out[existingCloseIdx] = existing.copy(
                        arrivalEpochSeconds = if (shouldUpdateEta) nowSec + approach.estimatedEtaSeconds else existing.arrivalEpochSeconds,
                        isRealtime = true,
                        vehicleId = approach.bus.vehicleId,
                        tripId = existing.tripId ?: approach.bus.tripId,
                        headsign = existing.headsign.takeUnless { it.startsWith("Linea ", true) }
                            ?: otherCtx.terminusName
                            ?: existing.headsign,
                        sourceLabel = if (shouldUpdateEta) "GPS Live" else existing.sourceLabel,
                    )
                    coveredVehicleIds.add(cid)
                }
            }
        }

        val verifiedLiveByLine = out
            .filter { it.isRealtime && !it.vehicleId.isNullOrBlank() }
            .groupBy { it.lineCode.trim().uppercase() }

        fun countOriginTerminusBuses(
            lineBuses: List<BusVehicle>,
            originPoint: GeoPoint?,
        ): Int {
            if (originPoint == null || lineBuses.isEmpty()) return 0
            return lineBuses.count { bus ->
                val cid = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId }
                if (cid in coveredVehicleIds) return@count false
                GttFleetAnalyzer.haversineMeters(
                    bus.latitude,
                    bus.longitude,
                    originPoint.lat,
                    originPoint.lon,
                ) <= 320.0
            }
        }

        fun hasSecondPhysicalBusForSlot(
            unclaimedUpstream: List<GttFleetAnalyzer.BusStopApproach>,
            originTerminusCount: Int,
            floorSec: Long?,
            slotEtaSec: Long,
        ): Boolean {
            val toleranceSec = maxOf(360L, (slotEtaSec.coerceAtLeast(0L) * 0.55).toLong())
            if (unclaimedUpstream.any { abs(it.estimatedEtaSeconds - slotEtaSec) <= toleranceSec }) {
                return true
            }
            if (originTerminusCount > 0 && floorSec != null && slotEtaSec >= floorSec) {
                return true
            }
            return false
        }

        val prefUnclaimedUpstream = approachingUpstream.filter {
            val cid = GtfsRtProtobufParser.canonicalFleetId(it.bus.vehicleId).ifBlank { it.bus.vehicleId }
            cid !in coveredVehicleIds
        }
        val prefOriginTerminusCount = countOriginTerminusBuses(prefLineVehicles, prefOriginPoint)

        val hasPrefLiveTelemetry = prefLineVehicles.isNotEmpty() && terminusFloorSec != null
        return out.filter { arr ->
            val vid = arr.vehicleId?.trim()?.takeIf { it.isNotEmpty() }
            if (vid != null) return@filter true

            val lineKey = arr.lineCode.trim().uppercase()
            val isPref = lineKey.equals(normPref, ignoreCase = true)
            val feedEtaSec = arr.arrivalEpochSeconds - nowSec
            val verifiedSameLine = verifiedLiveByLine[lineKey].orEmpty()

            val lineHasLiveAndGeo: Boolean
            val lineFloorSec: Long?
            val lineUnclaimedUpstream: List<GttFleetAnalyzer.BusStopApproach>
            val lineRecentlyPassed: List<GttFleetAnalyzer.BusStopApproach>
            val lineOriginCount: Int

            if (isPref) {
                lineHasLiveAndGeo = hasPrefLiveTelemetry
                lineFloorSec = terminusFloorSec
                lineUnclaimedUpstream = prefUnclaimedUpstream
                lineRecentlyPassed = prefRecentlyPassed
                lineOriginCount = prefOriginTerminusCount
            } else {
                val otherCtx = otherLineContexts[lineKey]
                lineFloorSec = otherCtx?.terminusFloorSec
                lineHasLiveAndGeo = otherCtx != null && otherCtx.activeBuses.isNotEmpty() && lineFloorSec != null
                lineUnclaimedUpstream = otherCtx?.approachingUpstream.orEmpty().filter {
                    val cid = GtfsRtProtobufParser.canonicalFleetId(it.bus.vehicleId).ifBlank { it.bus.vehicleId }
                    cid !in coveredVehicleIds
                }
                lineRecentlyPassed = otherCtx?.recentlyPassedDownstream.orEmpty()
                lineOriginCount = if (otherCtx != null) {
                    countOriginTerminusBuses(otherCtx.activeBuses, otherCtx.originPoint)
                } else {
                    0
                }
            }

            if (arr.sourceLabel == "GTT Palina" && arr.isRealtime) {
                if (lineHasLiveAndGeo) {
                    val hasSecondBus = hasSecondPhysicalBusForSlot(
                        unclaimedUpstream = lineUnclaimedUpstream,
                        originTerminusCount = lineOriginCount,
                        floorSec = lineFloorSec,
                        slotEtaSec = feedEtaSec,
                    )
                    val isDuplicateNearTracked = verifiedSameLine.any { tracked ->
                        abs(tracked.arrivalEpochSeconds - arr.arrivalEpochSeconds) <= 300L
                    }
                    if (isDuplicateNearTracked && !hasSecondBus) {
                        return@filter false
                    }
                    val isPostPassageGhost = feedEtaSec <= 180L &&
                        lineRecentlyPassed.isNotEmpty() &&
                        lineUnclaimedUpstream.isEmpty() &&
                        verifiedSameLine.none { abs(it.arrivalEpochSeconds - arr.arrivalEpochSeconds) <= 180L }
                    if (isPostPassageGhost) {
                        return@filter false
                    }
                }
                return@filter true
            }

            if (isPref && hasPrefLiveTelemetry) {
                if (arr.isRealtime && feedEtaSec <= 25L * 60L) return@filter false
                if (!arr.isRealtime) {
                    if (feedEtaSec < terminusFloorSec) return@filter false
                    val nearVerifiedLive = verifiedSameLine.any { tracked ->
                        val diff = tracked.arrivalEpochSeconds - arr.arrivalEpochSeconds
                        abs(diff) <= 360L || diff in -240L..540L
                    }
                    if (nearVerifiedLive && !hasSecondPhysicalBusForSlot(
                            unclaimedUpstream = lineUnclaimedUpstream,
                            originTerminusCount = lineOriginCount,
                            floorSec = terminusFloorSec,
                            slotEtaSec = feedEtaSec,
                        )
                    ) {
                        return@filter false
                    }
                }
                return@filter true
            }

            if (!isPref) {
                val otherBuses = reliableLiveByLine[lineKey].orEmpty()
                if (lineHasLiveAndGeo && lineFloorSec != null) {
                    if (arr.isRealtime && feedEtaSec <= 25L * 60L) return@filter false
                    if (!arr.isRealtime) {
                        if (feedEtaSec < lineFloorSec) return@filter false
                        val nearVerifiedLive = verifiedSameLine.any { tracked ->
                            val diff = tracked.arrivalEpochSeconds - arr.arrivalEpochSeconds
                            abs(diff) <= 360L || diff in -240L..540L
                        }
                        if (nearVerifiedLive && !hasSecondPhysicalBusForSlot(
                                unclaimedUpstream = lineUnclaimedUpstream,
                                originTerminusCount = lineOriginCount,
                                floorSec = lineFloorSec,
                                slotEtaSec = feedEtaSec,
                            )
                        ) {
                            return@filter false
                        }
                    }
                } else if (otherBuses.isNotEmpty() && feedEtaSec <= 12L * 60L) {
                    val maxReachableMeters = maxOf(700.0, feedEtaSec * 7.2 + 500.0)
                    val anyReachable = otherBuses.any { bus ->
                        val dist = GttFleetAnalyzer.haversineMeters(
                            bus.latitude,
                            bus.longitude,
                            stop.lat,
                            stop.lon,
                        )
                        if (dist > maxReachableMeters) return@any false
                        if (dist <= 300.0) return@any true
                        val b = bus.bearing ?: return@any true
                        val toStop = GttFleetAnalyzer.initialBearingDeg(
                            bus.latitude,
                            bus.longitude,
                            stop.lat,
                            stop.lon,
                        )
                        GttFleetAnalyzer.angleDiffDeg(b.toDouble(), toStop) <= 130.0
                    }
                    if (!anyReachable) return@filter false
                } else if (otherBuses.isEmpty() && hasCityWideLiveFeed && feedEtaSec <= 15L * 60L) {
                    return@filter false
                }
            }

            true
        }.map { arr ->
            if (!isGenericDirectionLabel(arr.headsign)) {
                arr
            } else {
                val lineKey = arr.lineCode.trim().uppercase()
                val exactTerminus = if (lineKey.equals(normPref, ignoreCase = true)) {
                    dirTerminus
                } else {
                    otherLineContexts[lineKey]?.terminusName
                }
                if (!exactTerminus.isNullOrBlank() && !isGenericDirectionLabel(exactTerminus)) {
                    arr.copy(headsign = exactTerminus)
                } else {
                    arr
                }
            }
        }
    }
}
