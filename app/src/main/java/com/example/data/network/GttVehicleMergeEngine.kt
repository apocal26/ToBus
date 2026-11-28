package com.example.data.network

import com.example.data.model.BusVehicle
import com.example.data.model.GttFleetAnalyzer
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
    const val MATO_UNCORROBORATED_SILENT_TTL_MS = 75_000L
    const val MATO_UNCORROBORATED_MAX_GPS_AGE_SECONDS = 120L
    const val VERIFIED_DROPOUT_GRACE_MS = 55_000L
    private const val KINEMATIC_MAX_URBAN_SPEED_KMH = 135.0
    private const val KINEMATIC_EXTREME_WARP_KMH = 350.0
    private const val KINEMATIC_QUARANTINE_MS = 75_000L
    private const val KINEMATIC_POSE_TTL_MS = 20 * 60_000L
    private const val MOVING_BEATS_STUCK_MAX_LAG_SECONDS = 55L
    private const val RETAINED_CORROBORATION_MAX_DIST_METERS = 500.0

    data class VehicleKinematicPose(
        val lineCode: String,
        val lat: Double,
        val lon: Double,
        val timestampSeconds: Long,
        val observedAtMillis: Long,
        val sourceName: String = "",
        val strikeCount: Int = 0,
        val lastOutlierAtMillis: Long = 0L,
        val lastOutlierLat: Double? = null,
        val lastOutlierLon: Double? = null,
        val lastOutlierTimestampSeconds: Long = 0L,
        val lastOutlierSourceName: String = "",
        val quarantinedUntilMillis: Long = 0L,
    )

    data class VerifiedBusGraceEntry(
        val bus: BusVehicle,
        val confirmedAtMillis: Long,
    )

    private fun effectiveKinematicDtSeconds(
        prev: VehicleKinematicPose,
        next: BusVehicle,
        nowMillis: Long,
    ): Long {
        val wallDtSec = ((nowMillis - prev.observedAtMillis) / 1000L).coerceAtLeast(1L)
        val hasBothTs = prev.timestampSeconds > 0L && next.timestampSeconds > 0L
        // When falling back to a lagged source (e.g. live GTT/MaTO dropped for 1 poll -> 2m-older BusOne),
        // next.timestampSeconds < prev.timestampSeconds. Use the true absolute GPS time separation so an
        // older-source snapshot 500m behind is evaluated at realistic urban speed, never as a 2000 km/h warp!
        if (hasBothTs && next.timestampSeconds < prev.timestampSeconds) {
            val lagSec = prev.timestampSeconds - next.timestampSeconds
            return maxOf(lagSec, wallDtSec)
        }
        val tsDtSec = if (hasBothTs && next.timestampSeconds > prev.timestampSeconds) {
            next.timestampSeconds - prev.timestampSeconds
        } else {
            0L
        }
        val sourceChanged = prev.sourceName.isNotEmpty() &&
            !prev.sourceName.equals(next.sourceName, ignoreCase = true)
        return when {
            tsDtSec <= 0L -> wallDtSec
            // Cross-source handoffs (e.g. lagged GTT -> live MaTO) or synthesized timestamps must
            // NEVER shrink dtSec below real monotonic wall-clock observation elapsed time!
            sourceChanged || !next.hasExplicitTimestamp -> maxOf(tsDtSec, wallDtSec)
            // Also protect tunnel/GPS-shadow catch-ups where feed timestamp barely advanced over a long wall gap
            wallDtSec > tsDtSec * 2L -> wallDtSec
            else -> tsDtSec
        }
    }

    fun isKinematicJumpImpossible(
        prev: VehicleKinematicPose,
        next: BusVehicle,
        nowMillis: Long,
    ): Boolean {
        if (!prev.lineCode.equals(next.lineCode, ignoreCase = true)) return false
        val distM = haversineMeters(prev.lat, prev.lon, next.latitude, next.longitude)
        if (distM <= 380.0) return false
        val dtSec = effectiveKinematicDtSeconds(prev, next, nowMillis)
        if (dtSec !in 1L..60L) return false
        val impliedKmh = (distM / dtSec.toDouble()) * 3.6
        return impliedKmh > KINEMATIC_MAX_URBAN_SPEED_KMH
    }

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
     * Trims silent/stale MaTO-only records whenever healthy official GTFS-RT feeds (GTT Ufficiale
     * or BusOne Proxy) are online and actively tracking the network without reporting that bus.
     *
     * - When [healthyOfficialGtfsOnline] is `false` (official feeds offline/frozen or fixed MaTO mode),
     *   all [matoVehicles] are preserved at full TTL so MaTO fallback remains seamless.
     * - When [healthyOfficialGtfsOnline] is `true`, any MaTO vehicle corroborated by an active
     *   non-zombie GTT/BusOne entry is always kept; an uncorroborated MaTO-only vehicle is dropped
     *   if it has been silent for > 75s, has GPS age > 120s, or lacks an explicit timestamp in
     *   [isWidgetFastMode] (preventing stale GraphQL entries from causing widget count swings).
     */
    fun filterStaleUncorroboratedMato(
        matoVehicles: List<BusVehicle>,
        gttVehicles: List<BusVehicle>,
        busOneVehicles: List<BusVehicle>,
        healthyOfficialGtfsOnline: Boolean,
        nowMillis: Long,
        isWidgetFastMode: Boolean = false,
        onDecision: ((PipelineBusDecision) -> Unit)? = null,
    ): List<BusVehicle> {
        if (matoVehicles.isEmpty() || !healthyOfficialGtfsOnline) return matoVehicles
        val nowSec = nowMillis / 1000L
        val activeOfficialIds = HashSet<String>(gttVehicles.size + busOneVehicles.size)
        for (v in gttVehicles) {
            val ts = if (v.timestampSeconds > 1_000_000_000_000L) v.timestampSeconds / 1000L else v.timestampSeconds
            if (ts > 0L && (nowSec - ts) > MAX_VEHICLE_ZOMBIE_AGE_SECONDS) continue
            val cid = GtfsRtProtobufParser.canonicalFleetId(v.vehicleId)
            if (cid.isNotEmpty()) activeOfficialIds.add(cid)
        }
        for (v in busOneVehicles) {
            val ts = if (v.timestampSeconds > 1_000_000_000_000L) v.timestampSeconds / 1000L else v.timestampSeconds
            if (ts > 0L && (nowSec - ts) > MAX_VEHICLE_ZOMBIE_AGE_SECONDS) continue
            val cid = GtfsRtProtobufParser.canonicalFleetId(v.vehicleId)
            if (cid.isNotEmpty()) activeOfficialIds.add(cid)
        }
        return matoVehicles.filter { bus ->
            val cid = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId.trim() }
            if (cid.isNotEmpty() && cid in activeOfficialIds) return@filter true
            if (isWidgetFastMode && !bus.hasExplicitTimestamp) {
                onDecision?.invoke(
                    PipelineBusDecision(
                        vehicleId = cid,
                        lineCode = bus.lineCode,
                        stage = "MATO_FILTER",
                        action = "DROPPED",
                        reason = "mato_widget_no_explicit_ts",
                        detail = "officialOnline=true widgetFast=true",
                    ),
                )
                return@filter false
            }
            val silentAgeMs = (nowMillis - bus.fetchedAtMillis).coerceAtLeast(0L)
            if (silentAgeMs > MATO_UNCORROBORATED_SILENT_TTL_MS) {
                onDecision?.invoke(
                    PipelineBusDecision(
                        vehicleId = cid,
                        lineCode = bus.lineCode,
                        stage = "MATO_FILTER",
                        action = "DROPPED",
                        reason = "mato_uncorroborated_silent_ttl(${silentAgeMs / 1000L}s>${MATO_UNCORROBORATED_SILENT_TTL_MS / 1000L}s)",
                        detail = "officialOnline=true notInOfficialFeeds=true",
                    ),
                )
                return@filter false
            }
            val gpsAgeSec = bus.gpsAgeSeconds(nowMillis)
            if (gpsAgeSec != null && gpsAgeSec > MATO_UNCORROBORATED_MAX_GPS_AGE_SECONDS) {
                onDecision?.invoke(
                    PipelineBusDecision(
                        vehicleId = cid,
                        lineCode = bus.lineCode,
                        stage = "MATO_FILTER",
                        action = "DROPPED",
                        reason = "mato_uncorroborated_gps_age(${gpsAgeSec}s>${MATO_UNCORROBORATED_MAX_GPS_AGE_SECONDS}s)",
                        detail = "officialOnline=true silent=${silentAgeMs / 1000L}s",
                    ),
                )
                return@filter false
            }
            true
        }
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
        previousPoses: Map<String, VehicleKinematicPose> = emptyMap(),
        matoRetainedCandidates: List<BusVehicle> = emptyList(),
        onDecision: ((PipelineBusDecision) -> Unit)? = null,
    ): List<BusVehicle> {
        val nowSec = nowMillis / 1000L
        val normFocus = focusLine?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        fun isFocusedLine(line: String): Boolean = normFocus != null && line.equals(normFocus, ignoreCase = true)

        // Retained MaTO candidates are NEVER injected standalone! Promote ONLY those strictly
        // corroborated by an active non-zombie GTT Ufficiale or BusOne Proxy vehicle on the same line,
        // and cap their timestamp so a synthetic retained packet can never defeat official coordinates.
        val corroboratedRetained = if (matoRetainedCandidates.isEmpty() || (gttVehicles.isEmpty() && busOneVehicles.isEmpty())) {
            if (onDecision != null && normFocus != null) {
                for (candidate in matoRetainedCandidates) {
                    if (isFocusedLine(candidate.lineCode)) {
                        val cid = GtfsRtProtobufParser.canonicalFleetId(candidate.vehicleId).ifBlank { candidate.vehicleId.trim() }
                        onDecision(
                            PipelineBusDecision(
                                vehicleId = cid,
                                lineCode = candidate.lineCode,
                                stage = "RETAINED_STAGE",
                                action = "STAGED",
                                reason = "retained_waiting_official_feeds",
                                detail = "gtt=${gttVehicles.size} busOne=${busOneVehicles.size}",
                            ),
                        )
                    }
                }
            }
            emptyList()
        } else {
            val officialById = HashMap<String, BusVehicle>(gttVehicles.size + busOneVehicles.size)
            for (official in gttVehicles) {
                val ts = if (official.timestampSeconds > 1_000_000_000_000L) official.timestampSeconds / 1000L else official.timestampSeconds
                if (ts > 0L && (nowSec - ts) > MAX_VEHICLE_ZOMBIE_AGE_SECONDS) continue
                val cid = GtfsRtProtobufParser.canonicalFleetId(official.vehicleId)
                if (cid.isNotEmpty()) officialById.putIfAbsent(cid, official)
            }
            for (official in busOneVehicles) {
                val ts = if (official.timestampSeconds > 1_000_000_000_000L) official.timestampSeconds / 1000L else official.timestampSeconds
                if (ts > 0L && (nowSec - ts) > MAX_VEHICLE_ZOMBIE_AGE_SECONDS) continue
                val cid = GtfsRtProtobufParser.canonicalFleetId(official.vehicleId)
                if (cid.isNotEmpty()) officialById.putIfAbsent(cid, official)
            }
            matoRetainedCandidates.mapNotNull { candidate ->
                val cid = GtfsRtProtobufParser.canonicalFleetId(candidate.vehicleId).ifBlank { candidate.vehicleId.trim() }
                val match = officialById[cid]
                if (match == null) {
                    if (onDecision != null && isFocusedLine(candidate.lineCode)) {
                        onDecision(
                            PipelineBusDecision(
                                vehicleId = cid,
                                lineCode = candidate.lineCode,
                                stage = "RETAINED_STAGE",
                                action = "STAGED",
                                reason = "retained_no_official_match",
                                detail = "notInActiveGttOrBusOne",
                            ),
                        )
                    }
                    return@mapNotNull null
                }
                if (!match.lineCode.equals(candidate.lineCode, ignoreCase = true)) {
                    if (onDecision != null && (isFocusedLine(candidate.lineCode) || isFocusedLine(match.lineCode))) {
                        onDecision(
                            PipelineBusDecision(
                                vehicleId = cid,
                                lineCode = candidate.lineCode,
                                stage = "RETAINED_STAGE",
                                action = "STAGED",
                                reason = "retained_line_mismatch(ret=${candidate.lineCode},off=${match.lineCode})",
                                detail = "officialSrc=${match.sourceName}",
                            ),
                        )
                    }
                    return@mapNotNull null
                }
                val retDistMeters = SourceHealthTracker.haversineMeters(
                    candidate.latitude,
                    candidate.longitude,
                    match.latitude,
                    match.longitude,
                )
                if (retDistMeters > RETAINED_CORROBORATION_MAX_DIST_METERS) {
                    if (onDecision != null && isFocusedLine(candidate.lineCode)) {
                        onDecision(
                            PipelineBusDecision(
                                vehicleId = cid,
                                lineCode = candidate.lineCode,
                                stage = "RETAINED_STAGE",
                                action = "STAGED",
                                reason = "retained_spatial_mismatch(dist=${retDistMeters.toInt()}m)",
                                detail = "officialSrc=${compactSourceTag(match.sourceName)} maxDist=${RETAINED_CORROBORATION_MAX_DIST_METERS.toInt()}m",
                            ),
                        )
                    }
                    return@mapNotNull null
                }
                val matchTs = if (match.timestampSeconds > 1_000_000_000_000L) match.timestampSeconds / 1000L else match.timestampSeconds
                // Keep timestamp strictly older than official so official pose wins while enriching metadata
                val cappedTs = if (matchTs > 0L) (matchTs - MATO_PRECEDENCE_WINDOW_SECONDS - 1L).coerceAtLeast(1L) else candidate.timestampSeconds
                if (onDecision != null && isFocusedLine(candidate.lineCode)) {
                    onDecision(
                        PipelineBusDecision(
                            vehicleId = cid,
                            lineCode = candidate.lineCode,
                            stage = "RETAINED_STAGE",
                            action = "PROMOTED",
                            reason = "retained_corroborated_by_${compactSourceTag(match.sourceName)}",
                            detail = "dist=${retDistMeters.toInt()}m cappedTs=$cappedTs matchTs=$matchTs",
                        ),
                    )
                }
                candidate.copy(
                    timestampSeconds = cappedTs,
                    isMqttRetained = false,
                )
            }
        }

        val allRaw = buildList(matoVehicles.size + corroboratedRetained.size + gttVehicles.size + busOneVehicles.size) {
            addAll(matoVehicles.filterNot { it.isMqttRetained })
            addAll(corroboratedRetained)
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
                if (onDecision != null && isFocusedLine(v.lineCode)) {
                    val ageSec = nowSec - v.timestampSeconds
                    onDecision(
                        PipelineBusDecision(
                            vehicleId = vid,
                            lineCode = v.lineCode,
                            stage = "MERGE_ZOMBIE",
                            action = "DROPPED",
                            reason = "zombie_gps_age(${compactSourceTag(v.sourceName)}:${ageSec}s>${MAX_VEHICLE_ZOMBIE_AGE_SECONDS}s)",
                            detail = "pos=${ToBusDiagnosticsLog.formatCoordPair(v.latitude, v.longitude)}",
                        ),
                    )
                }
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
            if (existing == null) {
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

            val prevPose = previousPoses[vid] ?: previousPoses[existingKey]
            val vImplausible = prevPose != null && isKinematicJumpImpossible(prevPose, v, nowMillis)
            val existingImplausible = prevPose != null && isKinematicJumpImpossible(prevPose, existing, nowMillis)

            val vNotStaleVsExisting = v.timestampSeconds <= 0L || existing.timestampSeconds <= 0L ||
                (existing.timestampSeconds - v.timestampSeconds) <= MOVING_BEATS_STUCK_MAX_LAG_SECONDS
            val existingNotStaleVsV = existing.timestampSeconds <= 0L || v.timestampSeconds <= 0L ||
                (v.timestampSeconds - existing.timestampSeconds) <= MOVING_BEATS_STUCK_MAX_LAG_SECONDS

            val existingIsMato = existing.sourceName == "MaTO Live"
            var winRule = "tie_breaker"
            val incomingWins = when {
                // Priority 0: Kinematical plausibility against recent pose defeats a teleporting glitch source
                existingImplausible && !vImplausible -> { winRule = "kinematic_plausibility"; true }
                vImplausible && !existingImplausible -> { winRule = "kinematic_plausibility"; false }
                // Priority 1: Any non-frozen source beats a frozen source regardless of fake timestamps
                existingFrozen && !vFrozen -> { winRule = "non_frozen_beats_frozen"; true }
                vFrozen && !existingFrozen -> { winRule = "non_frozen_beats_frozen"; false }
                // Priority 2: Confirmed real movement on this vehicle beats a stuck competitor ONLY if
                // the moving source is not dramatically older (> 55s stale) than the competitor.
                vMoving && existingStuck && coordsDiverge && vNotStaleVsExisting -> {
                    winRule = "moving_beats_stuck"
                    true
                }
                existingMoving && vStuck && coordsDiverge && existingNotStaleVsV -> {
                    winRule = "moving_beats_stuck"
                    false
                }
                // Priority 3: Healthy MaTO Live precedence window over healthy GTFS-RT/BusOne
                isMato && !existingIsMato -> {
                    winRule = "mato_precedence_window"
                    existing.timestampSeconds <= 0L ||
                        v.timestampSeconds >= existing.timestampSeconds - MATO_PRECEDENCE_WINDOW_SECONDS
                }
                existingIsMato && !isMato -> {
                    winRule = "mato_precedence_window"
                    v.timestampSeconds > existing.timestampSeconds + MATO_PRECEDENCE_WINDOW_SECONDS
                }
                // Priority 4: Fresher timestamp, then source quality tie-breaker
                v.timestampSeconds != existing.timestampSeconds -> {
                    winRule = "fresher_timestamp"
                    v.timestampSeconds > existing.timestampSeconds
                }
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
            val loserImplausible = if (incomingWins) existingImplausible else vImplausible
            val movingBeatStuckHere = winRule == "moving_beats_stuck" && winnerMoving && loserStuck
            val loserStaleDiverged = (loserFrozen || movingBeatStuckHere || loserImplausible) && coordsDiverge

            val sameLine = winner.lineCode.equals(loser.lineCode, ignoreCase = true)
            val ageDeltaSec = if (winner.timestampSeconds > 0L && loser.timestampSeconds > 0L) {
                kotlin.math.abs(winner.timestampSeconds - loser.timestampSeconds)
            } else {
                0L
            }
            // Normal travel drift between a live feed (e.g. MaTO 2s) and a lagging HTTP feed
            // (e.g. BusOne 175s) can easily reach 500–1200m at urban bus speeds (~36 km/h).
            val expectedNormalDriftMeters = 350.0 + ageDeltaSec * 10.0
            val isSignificantConflict = !sameLine ||
                loserStaleDiverged ||
                winRule == "kinematic_plausibility" ||
                winRule == "non_frozen_beats_frozen" ||
                winRule == "moving_beats_stuck" ||
                distMeters >= expectedNormalDriftMeters
            if (onDecision != null && (isFocusedLine(winner.lineCode) || isFocusedLine(loser.lineCode)) && isSignificantConflict) {
                val wAge = winner.gpsAgeSeconds(nowMillis)
                val lAge = loser.gpsAgeSeconds(nowMillis)
                onDecision(
                    PipelineBusDecision(
                        vehicleId = winner.vehicleId,
                        lineCode = winner.lineCode,
                        stage = "MERGE",
                        action = "MERGE_CONFLICT",
                        reason = "${compactSourceTag(winner.sourceName)}>${compactSourceTag(loser.sourceName)}($winRule)",
                        detail = "dist=${distMeters.toInt()}m winAge=${wAge ?: -1}s loseAge=${lAge ?: -1}s loserStale=$loserStaleDiverged${if (!sameLine) " lineSwitch=${loser.lineCode}->${winner.lineCode}" else ""}",
                    ),
                )
            }

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

    /**
     * Evaluates merged vehicles against recent kinematic history [kinematicPoses] to detect
     * physically impossible jumps or rapid ping-pong glitches.
     *
     * Rules (designed for 100% certainty before hiding any bus):
     * - Independent multi-source corroboration (e.g., MaTO Live + GTT/BusOne agreeing) resets erratic strikes.
     * - First moderate uncorroborated jump is held cautiously at the previous valid coordinates for 1 tick
     *   in case the bus just caught up from a temporary GPS shadow.
     * - If a vehicle commits >= 2 impossible jumps within 90s (rapid flip-flop / erratic bounce) OR an
     *   extreme solo-feed warp (> 220 km/h over > 650m in <= 25s), it is quarantined for 120s and hidden
     *   until multi-source corroboration or consecutive coherent poses confirm stability.
     */
    fun evaluateKinematicAnomalies(
        vehicles: List<BusVehicle>,
        kinematicPoses: MutableMap<String, VehicleKinematicPose>,
        nowMillis: Long,
        onDecision: ((PipelineBusDecision) -> Unit)? = null,
        onQuarantine: ((BusVehicle, String) -> Unit)? = null,
    ): List<BusVehicle> {
        if (vehicles.isEmpty()) return emptyList()
        val surviving = ArrayList<BusVehicle>(vehicles.size)

        for (bus in vehicles) {
            val vid = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId.trim() }
            if (vid.isEmpty()) continue

            val gpsSources = bus.gpsSourceNames()
            val gpsFeedCount = gpsSources.size.coerceAtLeast(1)
            val hasIndependentCorroboration = gpsFeedCount >= 2 && (
                gpsSources.any { it.equals("MaTO Live", ignoreCase = true) } ||
                    (gpsSources.any { it.equals("GTT Ufficiale", ignoreCase = true) } &&
                        gpsSources.any { it.equals("BusOne Proxy", ignoreCase = true) })
                )

            val prev = kinematicPoses[vid]
            if (prev == null || !prev.lineCode.equals(bus.lineCode, ignoreCase = true)) {
                kinematicPoses[vid] = VehicleKinematicPose(
                    lineCode = bus.lineCode,
                    lat = bus.latitude,
                    lon = bus.longitude,
                    timestampSeconds = bus.timestampSeconds,
                    observedAtMillis = nowMillis,
                    sourceName = bus.sourceName,
                )
                surviving.add(bus)
                continue
            }

            // If previously quarantined, lift early if corroborated OR coherent with last outlier / prev pose
            if (nowMillis < prev.quarantinedUntilMillis) {
                val coherentWithOutlier = prev.lastOutlierLat != null && prev.lastOutlierLon != null &&
                    haversineMeters(prev.lastOutlierLat, prev.lastOutlierLon, bus.latitude, bus.longitude) <= 120.0 &&
                    (nowMillis - prev.lastOutlierAtMillis) >= 10_000L
                val coherentWithPrev = haversineMeters(prev.lat, prev.lon, bus.latitude, bus.longitude) <= 140.0
                if (hasIndependentCorroboration || coherentWithOutlier || coherentWithPrev) {
                    val liftWhy = when {
                        hasIndependentCorroboration -> "multi_source_corroborated"
                        coherentWithOutlier -> "coherent_with_outlier"
                        else -> "coherent_with_prev"
                    }
                    onDecision?.invoke(
                        PipelineBusDecision(
                            vehicleId = vid,
                            lineCode = bus.lineCode,
                            stage = "KINEMATIC",
                            action = "QUARANTINE_LIFT",
                            reason = liftWhy,
                            detail = "src=${compactSourceTag(bus.sourceName)}",
                        ),
                    )
                    kinematicPoses[vid] = VehicleKinematicPose(
                        lineCode = bus.lineCode,
                        lat = bus.latitude,
                        lon = bus.longitude,
                        timestampSeconds = bus.timestampSeconds,
                        observedAtMillis = nowMillis,
                        sourceName = bus.sourceName,
                    )
                    surviving.add(bus)
                } else {
                    val remSec = ((prev.quarantinedUntilMillis - nowMillis) / 1000L).coerceAtLeast(0L)
                    onDecision?.invoke(
                        PipelineBusDecision(
                            vehicleId = vid,
                            lineCode = bus.lineCode,
                            stage = "KINEMATIC",
                            action = "QUARANTINE",
                            reason = "quarantine_active(${remSec}s_left)",
                            detail = "src=${compactSourceTag(bus.sourceName)} strikes=${prev.strikeCount}",
                        ),
                    )
                    kinematicPoses[vid] = prev.copy(
                        lastOutlierAtMillis = nowMillis,
                        lastOutlierLat = bus.latitude,
                        lastOutlierLon = bus.longitude,
                    )
                }
                continue
            }

            val distM = haversineMeters(prev.lat, prev.lon, bus.latitude, bus.longitude)
            val dtSec = effectiveKinematicDtSeconds(prev, bus, nowMillis)
            val impliedKmh = (distM / dtSec.toDouble()) * 3.6
            val isJumpImpossible = isKinematicJumpImpossible(prev, bus, nowMillis)

            if (!isJumpImpossible) {
                val decayedStrikes = if (nowMillis - prev.lastOutlierAtMillis > 90_000L) 0 else prev.strikeCount
                kinematicPoses[vid] = VehicleKinematicPose(
                    lineCode = bus.lineCode,
                    lat = bus.latitude,
                    lon = bus.longitude,
                    timestampSeconds = bus.timestampSeconds,
                    observedAtMillis = nowMillis,
                    sourceName = bus.sourceName,
                    strikeCount = decayedStrikes,
                    lastOutlierAtMillis = prev.lastOutlierAtMillis,
                )
                surviving.add(bus)
                continue
            }

            // Never double-strike the exact same unchanged cached packet (e.g. an MQTT overlay tick 1s later
            // re-evaluating the identical HTTP BusOne/GTT record that was just held on strike 1).
            val isDuplicateCachedOutlier = prev.strikeCount >= 1 &&
                prev.lastOutlierLat != null && prev.lastOutlierLon != null &&
                haversineMeters(prev.lastOutlierLat, prev.lastOutlierLon, bus.latitude, bus.longitude) < 5.0 &&
                prev.lastOutlierSourceName.equals(bus.sourceName, ignoreCase = true) &&
                (
                    (bus.timestampSeconds > 0L && bus.timestampSeconds == prev.lastOutlierTimestampSeconds) ||
                        (nowMillis - prev.lastOutlierAtMillis) < 4_000L
                )
            if (isDuplicateCachedOutlier) {
                surviving.add(
                    bus.copy(
                        latitude = prev.lat,
                        longitude = prev.lon,
                        timestampSeconds = maxOf(prev.timestampSeconds, bus.timestampSeconds),
                    ),
                )
                continue
            }

            // Check if a held 1-tick jump is actually corroborated by a second coherent frame near that new position
            val nearPrevOutlier = prev.lastOutlierLat != null && prev.lastOutlierLon != null &&
                haversineMeters(prev.lastOutlierLat, prev.lastOutlierLon, bus.latitude, bus.longitude) <= 180.0 &&
                (nowMillis - prev.lastOutlierAtMillis) in 4_000L..65_000L

            if (hasIndependentCorroboration && impliedKmh <= KINEMATIC_EXTREME_WARP_KMH) {
                kinematicPoses[vid] = VehicleKinematicPose(
                    lineCode = bus.lineCode,
                    lat = bus.latitude,
                    lon = bus.longitude,
                    timestampSeconds = bus.timestampSeconds,
                    observedAtMillis = nowMillis,
                    sourceName = bus.sourceName,
                )
                surviving.add(bus)
                continue
            }

            val sourceChanged = prev.sourceName.isNotEmpty() &&
                !prev.sourceName.equals(bus.sourceName, ignoreCase = true)
            val recentWindow = (nowMillis - prev.lastOutlierAtMillis) <= 90_000L
            val nextStrikes = if (recentWindow) prev.strikeCount + 1 else 1
            // Only instant-quarantine on a truly extreme single-source multi-km teleport (never on a
            // cross-source handoff or tunnel catch-up!)
            val isExtremeSoloWarp = !hasIndependentCorroboration &&
                !sourceChanged &&
                distM > 1_800.0 &&
                dtSec in 1L..15L &&
                impliedKmh > KINEMATIC_EXTREME_WARP_KMH

            if (nearPrevOutlier && !isExtremeSoloWarp && prev.strikeCount == 1) {
                // Coherent catch-up from temporary GPS blackhole -> accept smoothly
                kinematicPoses[vid] = VehicleKinematicPose(
                    lineCode = bus.lineCode,
                    lat = bus.latitude,
                    lon = bus.longitude,
                    timestampSeconds = bus.timestampSeconds,
                    observedAtMillis = nowMillis,
                    sourceName = bus.sourceName,
                )
                surviving.add(bus)
                continue
            }

            if (nextStrikes >= 2 || isExtremeSoloWarp) {
                val reason = "${distM.toInt()}m in ${dtSec}s (${impliedKmh.toInt()} km/h, strikes=$nextStrikes)"
                kinematicPoses[vid] = prev.copy(
                    strikeCount = nextStrikes,
                    lastOutlierAtMillis = nowMillis,
                    lastOutlierLat = bus.latitude,
                    lastOutlierLon = bus.longitude,
                    lastOutlierTimestampSeconds = bus.timestampSeconds,
                    lastOutlierSourceName = bus.sourceName,
                    quarantinedUntilMillis = nowMillis + KINEMATIC_QUARANTINE_MS,
                )
                onDecision?.invoke(
                    PipelineBusDecision(
                        vehicleId = vid,
                        lineCode = bus.lineCode,
                        stage = "KINEMATIC",
                        action = "QUARANTINE",
                        reason = if (isExtremeSoloWarp) "extreme_solo_warp" else "kinematic_2_strikes",
                        detail = "$reason src=${compactSourceTag(bus.sourceName)} prevSrc=${compactSourceTag(prev.sourceName)}",
                    ),
                )
                onQuarantine?.invoke(bus, reason)
            } else {
                // First uncertain outlier: hold stable pose for 1 poll instead of teleporting or hiding prematurely
                onDecision?.invoke(
                    PipelineBusDecision(
                        vehicleId = vid,
                        lineCode = bus.lineCode,
                        stage = "KINEMATIC",
                        action = "HOLD",
                        reason = "hold_1tick_jump(${distM.toInt()}m/${dtSec}s=${impliedKmh.toInt()}kmh)",
                        detail = "src=${compactSourceTag(bus.sourceName)} prevSrc=${compactSourceTag(prev.sourceName)}",
                    ),
                )
                kinematicPoses[vid] = prev.copy(
                    strikeCount = nextStrikes,
                    lastOutlierAtMillis = nowMillis,
                    lastOutlierLat = bus.latitude,
                    lastOutlierLon = bus.longitude,
                    lastOutlierTimestampSeconds = bus.timestampSeconds,
                    lastOutlierSourceName = bus.sourceName,
                )
                surviving.add(
                    bus.copy(
                        latitude = prev.lat,
                        longitude = prev.lon,
                        timestampSeconds = maxOf(prev.timestampSeconds, bus.timestampSeconds),
                    ),
                )
            }
        }

        if (kinematicPoses.size > 900) {
            val cutoff = nowMillis - KINEMATIC_POSE_TTL_MS
            kinematicPoses.entries.removeIf { it.value.observedAtMillis < cutoff }
        }
        return surviving
    }

    /**
     * Bridges single-poll GTFS-RT dropouts (up to [graceWindowMillis], default 55s) EXCLUSIVELY
     * for vehicles on [focusLine] that were recently confirmed live & non-stuck.
     *
     * Safety invariants:
     * - Retained, stuck (>= 2m stationary), stale (> 150s GPS age), depot, or already-holdover
     *   records NEVER receive or extend grace.
     * - If the vehicle does not return within [graceWindowMillis], it expires deterministically.
     */
    fun applyVerifiedDropoutGrace(
        freshVehicles: List<BusVehicle>,
        graceCache: MutableMap<String, VerifiedBusGraceEntry>,
        focusLine: String?,
        nowMillis: Long,
        graceWindowMillis: Long = VERIFIED_DROPOUT_GRACE_MS,
        quarantinedIds: Set<String> = emptySet(),
        onDecision: ((PipelineBusDecision) -> Unit)? = null,
    ): List<BusVehicle> {
        val targetLine = focusLine?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val presentIds = HashSet<String>(freshVehicles.size * 2)

        for (bus in freshVehicles) {
            val cid = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId.trim() }
            if (cid.isEmpty()) continue
            presentIds.add(cid)

            if (cid in quarantinedIds) {
                graceCache.remove(cid)
                continue
            }

            val gpsAgeSec = bus.gpsAgeSeconds(nowMillis)
            val statSec = bus.stationarySinceMillis?.let { ((nowMillis - it) / 1000L).coerceAtLeast(0L) }
            val inDepot = GttFleetAnalyzer.findDepot(bus.latitude, bus.longitude) != null
            val isEligibleLive = !bus.isMqttRetained &&
                !bus.isHoldover &&
                !inDepot &&
                (gpsAgeSec == null || gpsAgeSec <= 150L) &&
                (statSec == null || statSec < 120L) &&
                (bus.hasOfficialGtfsCorroboration() || bus.hasRecentPhysicalMovement(nowMillis, 120L))

            if (isEligibleLive) {
                graceCache[cid] = VerifiedBusGraceEntry(bus = bus, confirmedAtMillis = nowMillis)
            } else {
                graceCache.remove(cid)
            }
        }

        // Prune expired or quarantined grace entries
        val expireCutoff = nowMillis - graceWindowMillis
        val iter = graceCache.entries.iterator()
        while (iter.hasNext()) {
            val (cid, entry) = iter.next()
            val expired = entry.confirmedAtMillis < expireCutoff
            val quarantined = cid in quarantinedIds
            if (expired || quarantined) {
                if (onDecision != null && targetLine != null &&
                    (entry.bus.lineCode.equals(targetLine, ignoreCase = true) ||
                        entry.bus.rawRouteId.equals(targetLine, ignoreCase = true)) &&
                    cid !in presentIds
                ) {
                    val missedSec = ((nowMillis - entry.confirmedAtMillis) / 1000L).coerceAtLeast(0L)
                    onDecision(
                        PipelineBusDecision(
                            vehicleId = cid,
                            lineCode = entry.bus.lineCode,
                            stage = "GRACE",
                            action = "GRACE_EXPIRED",
                            reason = if (quarantined) "quarantined_evict" else "dropout_grace_expired(${missedSec}s>${graceWindowMillis / 1000L}s)",
                            detail = "lastSrc=${compactSourceTag(entry.bus.sourceName)} lastPos=${ToBusDiagnosticsLog.formatCoordPair(entry.bus.latitude, entry.bus.longitude)}",
                        ),
                    )
                }
                iter.remove()
            }
        }

        if (targetLine == null || graceCache.isEmpty()) return freshVehicles

        val bridged = ArrayList<BusVehicle>(freshVehicles.size + 4)
        bridged.addAll(freshVehicles)
        for ((cid, entry) in graceCache) {
            if (cid in presentIds) continue
            val cachedBus = entry.bus
            if (!cachedBus.lineCode.equals(targetLine, ignoreCase = true) &&
                !cachedBus.rawRouteId.equals(targetLine, ignoreCase = true)
            ) {
                continue
            }
            val missedSec = ((nowMillis - entry.confirmedAtMillis) / 1000L).coerceAtLeast(0L)
            onDecision?.invoke(
                PipelineBusDecision(
                    vehicleId = cid,
                    lineCode = cachedBus.lineCode,
                    stage = "GRACE",
                    action = "GRACE_BRIDGE",
                    reason = "bridged_single_poll_dropout(${missedSec}s/${graceWindowMillis / 1000L}s)",
                    detail = "lastSrc=${compactSourceTag(cachedBus.sourceName)} pos=${ToBusDiagnosticsLog.formatCoordPair(cachedBus.latitude, cachedBus.longitude)}",
                ),
            )
            bridged.add(cachedBus.copy(isHoldover = true, fetchedAtMillis = nowMillis))
        }
        return bridged
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
