package com.example.data.network

import com.example.data.model.BusVehicle
import com.example.data.model.LineSummary
import com.example.data.model.TripDelayInfo
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure, stateless engine responsible for multi-source GTT vehicle merging, TripUpdates enrichment,
 * line catalog reconciliation, and incremental line-summary updates.
 *
 * Kept free of Android framework state so all merging, zombie-filtering, and catalog deduplication
 * rules remain deterministically unit-testable on the JVM.
 */
internal object GttVehicleMergeEngine {

    const val MAX_VEHICLE_ZOMBIE_AGE_SECONDS = 360L
    const val MATO_PRECEDENCE_WINDOW_SECONDS = 25L
    const val OVERLAY_HTTP_CACHE_TTL_MS = 120_000L

    val METRO_LINE_SUMMARY = LineSummary(
        lineCode = GtfsRtProtobufParser.LINE_CODE_METRO,
        rawRouteIds = setOf("METRO", "M1", "M1U"),
        activeVehicleCount = 0,
    )

    /**
     * Returns [rawVehicles] only while the cached HTTP poll snapshot is still within [ttlMillis],
     * preventing stale GTFS-RT snapshots from contaminating high-frequency MaTO MQTT overlays.
     */
    fun filterFreshOverlayVehicles(
        rawVehicles: List<BusVehicle>,
        fetchedAtMillis: Long,
        nowMillis: Long,
        ttlMillis: Long = OVERLAY_HTTP_CACHE_TTL_MS,
    ): List<BusVehicle> {
        if (rawVehicles.isEmpty() || fetchedAtMillis <= 0L) return emptyList()
        return if ((nowMillis - fetchedAtMillis) <= ttlMillis) rawVehicles else emptyList()
    }

    /**
     * Enriches live [vehicles] with real-time schedule delay and next-stop metadata from
     * GTFS-RT [activeTripUpdates], matching by normalized `tripId` first and canonical fleet ID second.
     */
    fun enrichWithTripUpdates(
        vehicles: List<BusVehicle>,
        activeTripUpdates: List<TripDelayInfo>,
    ): List<BusVehicle> {
        if (vehicles.isEmpty() || activeTripUpdates.isEmpty()) return vehicles
        val tripByTripId = HashMap<String, TripDelayInfo>(activeTripUpdates.size)
        val tripByVehicleId = HashMap<String, TripDelayInfo>(activeTripUpdates.size)
        for (tu in activeTripUpdates) {
            GtfsRtProtobufParser.normalizeTripId(tu.tripId)?.let { tripByTripId[it] = tu }
            val rawVid = tu.vehicleId?.trim()?.takeIf { it.isNotEmpty() }
            if (rawVid != null) {
                tripByVehicleId[rawVid] = tu
                val canonVid = GtfsRtProtobufParser.canonicalFleetId(rawVid)
                if (canonVid.isNotEmpty()) {
                    tripByVehicleId[canonVid] = tu
                }
            }
        }
        return vehicles.map { bus ->
            val normTrip = GtfsRtProtobufParser.normalizeTripId(bus.tripId)
            val canonVid = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId.trim() }
            val tripMatch = normTrip?.let { tripByTripId[it] }?.takeIf { tu ->
                tu.routeId.isNullOrBlank() ||
                    GtfsRtProtobufParser.normalizeGttLineCode(tu.routeId).equals(bus.lineCode, ignoreCase = true)
            }
            val vehMatch = (tripByVehicleId[canonVid] ?: tripByVehicleId[bus.vehicleId.trim()])?.takeIf { tu ->
                tu.routeId.isNullOrBlank() ||
                    GtfsRtProtobufParser.normalizeGttLineCode(tu.routeId).equals(bus.lineCode, ignoreCase = true)
            }
            val tu = tripMatch ?: vehMatch
            if (tu == null) {
                bus
            } else {
                bus.copy(
                    contributingSources = bus.contributingSources + "TripUpdates",
                    delaySeconds = tu.delaySeconds ?: bus.delaySeconds,
                    nextStopId = tu.nextStopId ?: bus.nextStopId ?: bus.stopId,
                )
            }
        }
    }

    /**
     * Incrementally updates ONLY the focused line's summary entry during high-frequency MaTO MQTT frames,
     * avoiding a full O(N log N) regrouping + sort over all ~400 citywide vehicles every ~2 seconds.
     */
    fun updateLineSummariesForFocusLine(
        baseSummaries: List<LineSummary>,
        vehicles: List<BusVehicle>,
        focusLine: String?,
    ): List<LineSummary> {
        val target = focusLine?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            ?: return GtfsRtProtobufParser.buildLineSummaries(vehicles)
        var count = 0
        val rawIds = HashSet<String>(4)
        for (v in vehicles) {
            if (v.lineCode.equals(target, ignoreCase = true) || v.rawRouteId.equals(target, ignoreCase = true)) {
                count++
                if (v.rawRouteId.isNotEmpty()) rawIds.add(v.rawRouteId)
            }
        }
        val existingIdx = baseSummaries.indexOfFirst { it.lineCode.equals(target, ignoreCase = true) }
        if (count == 0) {
            return if (existingIdx < 0) baseSummaries else baseSummaries.filterIndexed { i, _ -> i != existingIdx }
        }
        if (existingIdx >= 0) {
            val existing = baseSummaries[existingIdx]
            if (existing.activeVehicleCount == count && (rawIds.isEmpty() || existing.rawRouteIds.containsAll(rawIds))) {
                return baseSummaries
            }
            val copy = ArrayList<LineSummary>(baseSummaries.size)
            copy.addAll(baseSummaries)
            copy[existingIdx] = existing.copy(
                rawRouteIds = if (rawIds.isNotEmpty()) existing.rawRouteIds + rawIds else existing.rawRouteIds,
                activeVehicleCount = count,
            )
            return copy
        }
        val added = LineSummary(
            lineCode = target,
            rawRouteIds = rawIds.ifEmpty { setOf(target) },
            activeVehicleCount = count,
        )
        val merged = ArrayList<LineSummary>(baseSummaries.size + 1)
        merged.addAll(baseSummaries)
        merged.add(added)
        merged.sortWith(GtfsRtProtobufParser.lineCodeComparator)
        return merged
    }

    /**
     * Merges live line summaries with a cached/fallback catalog, preserving non-zero counts from
     * the catalog when live data only covers a subset of lines (e.g. single-line MaTO mode).
     */
    fun mergeLineSummariesWithCatalog(
        liveSummaries: List<LineSummary>,
        catalogSummaries: List<LineSummary>,
    ): List<LineSummary> {
        return mergeCatalogAndLive(
            liveSummaries = liveSummaries,
            catalogSummaries = catalogSummaries,
            resetCatalogCountsToZero = false,
        )
    }

    /**
     * Combines authoritative citywide [liveSummaries] with the full [staticCatalog] so every
     * known Turin GTT line appears (including Turin's METRO), while lines with no live buses
     * right now accurately report `activeVehicleCount = 0` (never preserving stale counts).
     */
    fun mergeLiveSummariesWithStaticCatalog(
        liveSummaries: List<LineSummary>,
        staticCatalog: List<LineSummary>,
    ): List<LineSummary> {
        return mergeCatalogAndLive(
            liveSummaries = liveSummaries,
            catalogSummaries = staticCatalog,
            resetCatalogCountsToZero = true,
        )
    }

    private fun mergeCatalogAndLive(
        liveSummaries: List<LineSummary>,
        catalogSummaries: List<LineSummary>,
        resetCatalogCountsToZero: Boolean,
    ): List<LineSummary> {
        val byCode = LinkedHashMap<String, LineSummary>(catalogSummaries.size + liveSummaries.size + 1)
        byCode[GtfsRtProtobufParser.LINE_CODE_METRO] = METRO_LINE_SUMMARY

        for (entry in catalogSummaries) {
            val key = GtfsRtProtobufParser.normalizeGttLineCode(entry.lineCode)
            if (key.isEmpty() || key == "?") continue
            val existing = byCode[key]
            val count = if (resetCatalogCountsToZero) 0 else entry.activeVehicleCount
            byCode[key] = if (existing == null) {
                entry.copy(lineCode = key, activeVehicleCount = count)
            } else {
                existing.copy(
                    rawRouteIds = existing.rawRouteIds + entry.rawRouteIds,
                    activeVehicleCount = if (resetCatalogCountsToZero) 0 else maxOf(existing.activeVehicleCount, count),
                )
            }
        }

        // Cross-link any static catalog entry whose rawRouteIds (e.g. GTFS route_id = "13PU"
        // paired with route_short_name = "13+") normalize to a live lineCode so mismatched
        // route_id/shortName pairs in GTT feeds never spawn 0-vehicle duplicate entries.
        val aliasToCatalogKey = HashMap<String, String>(byCode.size * 2)
        for ((catalogKey, summary) in byCode) {
            for (rawId in summary.rawRouteIds) {
                val normAlias = GtfsRtProtobufParser.normalizeGttLineCode(rawId)
                if (normAlias.isNotEmpty() && normAlias != "?" && normAlias != catalogKey && !byCode.containsKey(normAlias)) {
                    aliasToCatalogKey.putIfAbsent(normAlias, catalogKey)
                }
            }
        }

        for (entry in liveSummaries) {
            val key = GtfsRtProtobufParser.normalizeGttLineCode(entry.lineCode)
            if (key.isEmpty() || key == "?") continue
            var existing = byCode[key]
            if (existing == null) {
                val aliasedKey = aliasToCatalogKey[key]
                    ?: entry.rawRouteIds.firstNotNullOfOrNull { raw ->
                        val normRaw = GtfsRtProtobufParser.normalizeGttLineCode(raw)
                        aliasToCatalogKey[normRaw]
                    }
                if (aliasedKey != null && aliasedKey != GtfsRtProtobufParser.LINE_CODE_METRO) {
                    val removed = byCode.remove(aliasedKey)
                    if (removed != null) {
                        existing = removed.copy(lineCode = key)
                    }
                }
            }
            byCode[key] = if (existing == null) {
                entry.copy(lineCode = key)
            } else {
                existing.copy(
                    rawRouteIds = existing.rawRouteIds + entry.rawRouteIds,
                    activeVehicleCount = if (resetCatalogCountsToZero) {
                        entry.activeVehicleCount
                    } else {
                        maxOf(existing.activeVehicleCount, entry.activeVehicleCount)
                    },
                )
            }
        }
        return byCode.values.sortedWith(GtfsRtProtobufParser.lineCodeComparator)
    }

    /**
     * Merges live vehicles from MaTO Live, GTT Official GTFS-RT, and BusOne Proxy strictly
     * by physical `vehicleId`:
     * 1. Non-frozen sources ALWAYS defeat frozen sources (immune to fake-fresh timestamps from
     *    stalled VSGTT/GTFS-RT feeds!).
     * 2. Per-vehicle confirmed movement ALWAYS defeats a stuck competitor when coordinates diverge.
     * 3. Never drops unique vehicles from a frozen source when no healthier source reports them
     *    (zero bus loss during partial fallbacks).
     * 4. Prevents cross-line ghost vehicles when a physical bus switches line (e.g., 12 -> 14)
     *    and avoids copying stale heading/stop metadata from a frozen/diverged losing feed.
     */
    fun mergeMultiSourceVehicles(
        matoVehicles: List<BusVehicle>,
        gttVehicles: List<BusVehicle>,
        busOneVehicles: List<BusVehicle>,
        nowMillis: Long,
        focusLine: String? = null,
        sourceReports: Map<String, SourceHealthReport> = emptyMap(),
    ): List<BusVehicle> {
        val nowSec = nowMillis / 1000L
        val allRaw = buildList(matoVehicles.size + gttVehicles.size + busOneVehicles.size) {
            addAll(matoVehicles)
            addAll(gttVehicles)
            addAll(busOneVehicles)
        }
        if (allRaw.isEmpty()) return emptyList()

        val hasAnyFreshTelemetry = allRaw.any { v ->
            val ts = if (v.timestampSeconds > 1_000_000_000_000L) v.timestampSeconds / 1000L else v.timestampSeconds
            ts > 0L && (nowSec - ts) in -120L..MAX_VEHICLE_ZOMBIE_AGE_SECONDS
        }

        val mergedByVehicleId = LinkedHashMap<String, BusVehicle>()
        val keyByLineAndTrip = HashMap<String, String>()

        fun normalizeIncoming(v: BusVehicle): BusVehicle {
            val canonVid = GtfsRtProtobufParser.canonicalFleetId(v.vehicleId)
            val canonLabel = GtfsRtProtobufParser.canonicalFleetId(v.label).ifBlank { canonVid }
            val normTrip = GtfsRtProtobufParser.normalizeTripId(v.tripId)
            val tsRaw = if (v.timestampSeconds > 1_000_000_000_000L) v.timestampSeconds / 1000L else v.timestampSeconds
            val clampedTs = if (tsRaw > nowSec + 120L) nowSec else tsRaw
            return if (canonVid == v.vehicleId &&
                canonLabel == v.label &&
                normTrip == v.tripId &&
                clampedTs == v.timestampSeconds
            ) {
                v
            } else {
                v.copy(
                    vehicleId = canonVid,
                    label = canonLabel,
                    tripId = normTrip,
                    timestampSeconds = clampedTs,
                )
            }
        }

        fun lineTripKey(lineCode: String, tripId: String?): String? {
            val cleanTrip = GtfsRtProtobufParser.normalizeTripId(tripId) ?: return null
            val cleanLine = lineCode.trim().uppercase()
            if (cleanLine.isEmpty()) return null
            return "$cleanLine|${cleanTrip.uppercase()}"
        }

        for (rawV in allRaw) {
            val rawVidTrimmed = rawV.vehicleId.trim()
            val v = normalizeIncoming(rawV)
            val vid = v.vehicleId
            if (vid.isEmpty()) continue

            // Drop zombie vehicles older than 6 minutes across ALL sources (including MaTO GraphQL)
            // whenever fresher telemetry is present
            if (hasAnyFreshTelemetry && v.timestampSeconds > 0L &&
                (nowSec - v.timestampSeconds) > MAX_VEHICLE_ZOMBIE_AGE_SECONDS
            ) {
                continue
            }

            val isMato = v.sourceName == "MaTO Live"
            var existingKey: String? = if (mergedByVehicleId.containsKey(vid)) vid else null
            if (existingKey == null) {
                val ltKey = lineTripKey(v.lineCode, v.tripId)
                val candidateKey = ltKey?.let { keyByLineAndTrip[it] }
                if (candidateKey != null && mergedByVehicleId.containsKey(candidateKey)) {
                    // Only merge by (lineCode, tripId) when at least one record used a non-physical fallback ID
                    // (never collapse two distinct physical fleet buses like #9320 and #9321!)
                    val bothPhysical = GtfsRtProtobufParser.isPhysicalFleetId(candidateKey) &&
                        GtfsRtProtobufParser.isPhysicalFleetId(vid)
                    if (!bothPhysical) {
                        existingKey = candidateKey
                    }
                }
            }

            val existing = existingKey?.let { mergedByVehicleId[it] }
            if (existing == null || existingKey == null) {
                mergedByVehicleId[vid] = v
                lineTripKey(v.lineCode, v.tripId)?.let { keyByLineAndTrip[it] = vid }
                continue
            }

            val vReport = sourceReports[v.sourceName]
            val existingReport = sourceReports[existing.sourceName]
            val vFrozen = vReport?.isFrozenForLine(v.lineCode, focusLine) == true
            val existingFrozen = existingReport?.isFrozenForLine(existing.lineCode, focusLine) == true

            val vMoving = vReport?.confirmedMovingVehicleIds?.let {
                vid in it || rawVidTrimmed in it
            } == true
            val existingMoving = existingReport?.confirmedMovingVehicleIds?.let {
                existingKey in it || existing.vehicleId in it
            } == true
            val vStuck = vReport?.stuckVehicleIds?.let {
                vid in it || rawVidTrimmed in it
            } == true
            val existingStuck = existingReport?.stuckVehicleIds?.let {
                existingKey in it || existing.vehicleId in it
            } == true

            val distMeters = SourceHealthTracker.haversineMeters(
                v.latitude,
                v.longitude,
                existing.latitude,
                existing.longitude,
            )
            val coordsDiverge = distMeters >= 40.0

            val existingIsMato = existing.sourceName == "MaTO Live"
            val incomingWins = when {
                // Priority 1: Any non-frozen source beats a frozen source regardless of fake timestamps
                existingFrozen && !vFrozen -> true
                vFrozen && !existingFrozen -> false
                // Priority 2: Confirmed real movement on this vehicle beats a stuck competitor
                vMoving && existingStuck && coordsDiverge -> true
                existingMoving && vStuck && coordsDiverge -> false
                // Priority 3: Healthy MaTO Live precedence window over healthy GTFS-RT/BusOne
                isMato && !existingIsMato ->
                    existing.timestampSeconds <= 0L ||
                        v.timestampSeconds >= existing.timestampSeconds - MATO_PRECEDENCE_WINDOW_SECONDS
                existingIsMato && !isMato ->
                    v.timestampSeconds > existing.timestampSeconds + MATO_PRECEDENCE_WINDOW_SECONDS
                // Priority 4: Fresher timestamp, then source quality tie-breaker
                v.timestampSeconds != existing.timestampSeconds ->
                    v.timestampSeconds > existing.timestampSeconds
                existing.sourceName == "MaTO Live" -> false
                v.sourceName == "MaTO Live" -> true
                existing.sourceName == "GTT Ufficiale" -> false
                v.sourceName == "GTT Ufficiale" -> true
                else -> false
            }

            val winner = if (incomingWins) v else existing
            val loser = if (incomingWins) existing else v
            val loserFrozen = if (incomingWins) existingFrozen else vFrozen
            val winnerMoving = if (incomingWins) vMoving else existingMoving
            val loserStuck = if (incomingWins) existingStuck else vStuck
            val loserStaleDiverged = (loserFrozen || (winnerMoving && loserStuck)) && coordsDiverge

            // Prefer the real physical fleet matricola over a tripId fallback whenever both describe one bus
            val preferredPhysicalId = when {
                GtfsRtProtobufParser.isPhysicalFleetId(winner.vehicleId) -> winner.vehicleId
                winner.lineCode.equals(loser.lineCode, ignoreCase = true) &&
                    GtfsRtProtobufParser.isPhysicalFleetId(loser.vehicleId) -> loser.vehicleId
                else -> winner.vehicleId
            }
            val preferredLabel = if (GtfsRtProtobufParser.isPhysicalFleetId(preferredPhysicalId)) {
                preferredPhysicalId
            } else {
                winner.label.ifBlank { preferredPhysicalId }
            }

            val mergedBus = if (winner.lineCode.equals(loser.lineCode, ignoreCase = true)) {
                if (loserStaleDiverged) {
                    val allowTripBorrow = distMeters < 800.0
                    winner.copy(
                        vehicleId = preferredPhysicalId,
                        label = preferredLabel,
                        contributingSources = winner.contributingSources,
                        directionId = winner.directionId ?: if (allowTripBorrow) loser.directionId else null,
                        tripId = winner.tripId ?: if (allowTripBorrow) loser.tripId else null,
                    )
                } else {
                    winner.copy(
                        vehicleId = preferredPhysicalId,
                        label = preferredLabel,
                        contributingSources = winner.contributingSources + loser.contributingSources,
                        directionId = winner.directionId ?: loser.directionId,
                        bearing = winner.bearing ?: loser.bearing,
                        speedKmh = winner.speedKmh ?: loser.speedKmh,
                        stopId = winner.stopId ?: loser.stopId,
                        nextStopId = winner.nextStopId ?: loser.nextStopId,
                        tripId = winner.tripId ?: loser.tripId,
                        delaySeconds = winner.delaySeconds ?: loser.delaySeconds,
                    )
                }
            } else {
                // Physical vehicle switched lines: keep ONLY the fresher/moving line record and never
                // bleed the old line's tripId, stopId, or directionId onto the new line!
                winner.copy(vehicleId = preferredPhysicalId, label = preferredLabel)
            }

            if (existingKey != preferredPhysicalId) {
                mergedByVehicleId.remove(existingKey)
            }
            mergedByVehicleId[preferredPhysicalId] = mergedBus
            lineTripKey(mergedBus.lineCode, mergedBus.tripId)?.let {
                keyByLineAndTrip[it] = preferredPhysicalId
            }
        }

        return mergedByVehicleId.values.toList()
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        return r * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    fun initialBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
