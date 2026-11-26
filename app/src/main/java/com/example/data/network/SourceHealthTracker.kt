package com.example.data.network

import com.example.data.model.BusVehicle
import com.example.data.model.GttFleetAnalyzer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class SourceMotionStatus {
    /** Not enough observation duration or non-depot vehicles yet to classify. */
    WARMING_UP,

    /** Real vehicle movement (>= 20m) is actively observed in this source. */
    MOVING,

    /** Coordinates remain frozen across consecutive polls even if timestamps keep advancing. */
    FROZEN,
}

internal data class SourceHealthReport(
    val sourceName: String,
    val status: SourceMotionStatus,
    val fleetFrozen: Boolean,
    val focusLineFrozen: Boolean,
    val crossSourceFrozen: Boolean = false,
    val observedNonDepotCount: Int,
    val movingCount: Int,
    val frozenDurationSeconds: Long,
    /** Vehicle IDs in this source with confirmed real displacement (>= 20m) recently. */
    val confirmedMovingVehicleIds: Set<String>,
    /** Vehicle IDs in this source stuck at the same spot (< 20m) for >= 45s (2+ observations). */
    val stuckVehicleIds: Set<String>,
    /** Latest observed position per non-depot vehicleId in this source. */
    val latestPositions: Map<String, Pair<Double, Double>>,
) {
    val isFrozenOverall: Boolean
        get() = fleetFrozen || crossSourceFrozen

    fun isFrozenForLine(lineCode: String, focusLine: String?): Boolean {
        if (isFrozenOverall) return true
        if (focusLineFrozen && !focusLine.isNullOrBlank() && lineCode.equals(focusLine.trim(), ignoreCase = true)) {
            return true
        }
        return false
    }
}

/**
 * Tracks real physical GPS displacement *per individual feed source* (`MaTO Live`, `GTT Ufficiale`,
 * `BusOne Proxy`) across polls.
 *
 * Detects the classic Turin GTT/VSGTT failure mode where a feed keeps returning HTTP 200 and
 * advancing timestamps every poll, while actual vehicle coordinates are frozen in place for minutes.
 *
 * Pure Kotlin, O(N) per poll, bounded memory, zero network overhead, and instant auto-recovery
 * as soon as a previously frozen source starts moving again.
 */
internal class SourceHealthTracker {

    private data class VehicleObservation(
        val lat: Double,
        val lon: Double,
        val firstObservedInSourceMillis: Long,
        val firstSeenAtPositionMillis: Long,
        val lastMovedAtMillis: Long,
        val lastSeenAtMillis: Long,
        val hasEverMoved: Boolean,
        val observationCount: Int,
        val isInDepot: Boolean,
        val lineCode: String,
    )

    private class SourceMemory(
        var firstPollAtMillis: Long,
        var lastPollAtMillis: Long,
        var lastDistinctPollCountedAtMillis: Long,
        var distinctPollCount: Int,
        var lastAnyMovementAtMillis: Long,
    ) {
        val vehicles = HashMap<String, VehicleObservation>()
    }

    private val sources = ConcurrentHashMap<String, SourceMemory>()

    /**
     * Records a non-empty vehicle batch from [sourceName] and returns its current [SourceHealthReport].
     * Empty batches are ignored here (handled as offline/empty upstream, not as frozen coordinates).
     */
    fun recordAndEvaluate(
        sourceName: String,
        vehicles: List<BusVehicle>,
        focusLine: String?,
        nowMillis: Long,
    ): SourceHealthReport {
        if (vehicles.isEmpty()) {
            return SourceHealthReport(
                sourceName = sourceName,
                status = SourceMotionStatus.WARMING_UP,
                fleetFrozen = false,
                focusLineFrozen = false,
                observedNonDepotCount = 0,
                movingCount = 0,
                frozenDurationSeconds = 0L,
                confirmedMovingVehicleIds = emptySet(),
                stuckVehicleIds = emptySet(),
                latestPositions = emptyMap(),
            )
        }

        val mem = sources.compute(sourceName) { _, existing ->
            if (existing == null || (nowMillis - existing.lastPollAtMillis) > SESSION_GAP_RESET_MS) {
                SourceMemory(
                    firstPollAtMillis = nowMillis,
                    lastPollAtMillis = nowMillis,
                    lastDistinctPollCountedAtMillis = nowMillis,
                    distinctPollCount = 1,
                    lastAnyMovementAtMillis = nowMillis,
                )
            } else {
                existing
            }
        }!!

        return synchronized(mem) {
            val isNewDistinctPoll = (nowMillis - mem.lastDistinctPollCountedAtMillis) >= MIN_DISTINCT_POLL_GAP_MS
            if (isNewDistinctPoll) {
                mem.distinctPollCount++
                mem.lastDistinctPollCountedAtMillis = nowMillis
            }
            mem.lastPollAtMillis = nowMillis

            val normFocus = focusLine?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            val currentNonDepotIds = HashSet<String>(vehicles.size)
            val confirmedMoving = HashSet<String>()
            val stuckIds = HashSet<String>()
            val latestPositions = HashMap<String, Pair<Double, Double>>(vehicles.size)

            var eligibleFleetCount = 0
            var recentlyMovedFleetCount = 0
            var eligibleFocusCount = 0
            var recentlyMovedFocusCount = 0

            for (v in vehicles) {
                val vid = v.vehicleId.trim()
                if (vid.isEmpty()) continue
                val inDepot = GttFleetAnalyzer.findDepot(v.latitude, v.longitude) != null
                val prev = mem.vehicles[vid]

                val updated = if (prev == null) {
                    VehicleObservation(
                        lat = v.latitude,
                        lon = v.longitude,
                        firstObservedInSourceMillis = nowMillis,
                        firstSeenAtPositionMillis = nowMillis,
                        lastMovedAtMillis = nowMillis,
                        lastSeenAtMillis = nowMillis,
                        hasEverMoved = false,
                        observationCount = 1,
                        isInDepot = inDepot,
                        lineCode = v.lineCode,
                    )
                } else {
                    val dist = haversineMeters(prev.lat, prev.lon, v.latitude, v.longitude)
                    val nextObsCount = if (isNewDistinctPoll || dist >= MOVEMENT_THRESHOLD_METERS) {
                        prev.observationCount + 1
                    } else {
                        prev.observationCount
                    }
                    if (dist >= MOVEMENT_THRESHOLD_METERS) {
                        if (!inDepot) {
                            mem.lastAnyMovementAtMillis = nowMillis
                        }
                        VehicleObservation(
                            lat = v.latitude,
                            lon = v.longitude,
                            firstObservedInSourceMillis = prev.firstObservedInSourceMillis,
                            firstSeenAtPositionMillis = nowMillis,
                            lastMovedAtMillis = nowMillis,
                            lastSeenAtMillis = nowMillis,
                            hasEverMoved = true,
                            observationCount = nextObsCount,
                            isInDepot = inDepot,
                            lineCode = v.lineCode,
                        )
                    } else {
                        prev.copy(
                            lastSeenAtMillis = nowMillis,
                            observationCount = nextObsCount,
                            isInDepot = inDepot,
                            lineCode = v.lineCode,
                        )
                    }
                }
                mem.vehicles[vid] = updated

                if (inDepot) continue
                currentNonDepotIds.add(vid)
                latestPositions[vid] = updated.lat to updated.lon

                val seenDurationMs = nowMillis - updated.firstObservedInSourceMillis
                val stationaryMs = nowMillis - updated.firstSeenAtPositionMillis
                val sinceLastMoveMs = nowMillis - updated.lastMovedAtMillis

                val isConfirmedMoving = updated.hasEverMoved && sinceLastMoveMs <= RECENT_MOVEMENT_WINDOW_MS
                if (isConfirmedMoving) {
                    confirmedMoving.add(vid)
                }

                val isRecentlyActiveMove = updated.hasEverMoved && sinceLastMoveMs <= FREEZE_MOVEMENT_WINDOW_MS
                if (isRecentlyActiveMove) {
                    recentlyMovedFleetCount++
                }

                val isStuck = updated.observationCount >= 2 &&
                    stationaryMs >= STUCK_VEHICLE_MIN_MS &&
                    (!updated.hasEverMoved || sinceLastMoveMs >= STUCK_AFTER_PREV_MOVE_MS)
                if (isStuck) {
                    stuckIds.add(vid)
                }

                val isEligibleForFreezeCheck = updated.observationCount >= MIN_POLLS_FOR_FREEZE &&
                    seenDurationMs >= FREEZE_DURATION_THRESHOLD_MS
                if (isEligibleForFreezeCheck) {
                    eligibleFleetCount++
                }

                if (normFocus != null && v.lineCode.equals(normFocus, ignoreCase = true)) {
                    if (isRecentlyActiveMove) recentlyMovedFocusCount++
                    if (isEligibleForFreezeCheck) eligibleFocusCount++
                }
            }

            // Prune vehicles gone from this source for > 8 minutes to keep memory tight
            if (mem.vehicles.size > 600) {
                val cutoff = nowMillis - VEHICLE_PRUNE_MS
                mem.vehicles.entries.removeIf { it.value.lastSeenAtMillis < cutoff }
            }

            val observedSpanMs = nowMillis - mem.firstPollAtMillis
            val frozenForMs = (nowMillis - mem.lastAnyMovementAtMillis).coerceAtLeast(0L)

            val fleetFrozen = mem.distinctPollCount >= MIN_POLLS_FOR_FREEZE &&
                observedSpanMs >= FREEZE_DURATION_THRESHOLD_MS &&
                eligibleFleetCount >= MIN_FLEET_VEHICLES_FOR_FREEZE &&
                frozenForMs >= FREEZE_DURATION_THRESHOLD_MS &&
                if (eligibleFleetCount <= 20) {
                    recentlyMovedFleetCount == 0
                } else {
                    (recentlyMovedFleetCount.toDouble() / eligibleFleetCount.toDouble()) < FROZEN_MOVING_RATIO_THRESHOLD
                }

            val focusLineFrozen = normFocus != null &&
                mem.distinctPollCount >= MIN_POLLS_FOR_FREEZE &&
                observedSpanMs >= FREEZE_DURATION_THRESHOLD_MS &&
                eligibleFocusCount >= MIN_FOCUS_LINE_VEHICLES_FOR_FREEZE &&
                recentlyMovedFocusCount == 0

            val status = when {
                fleetFrozen || focusLineFrozen -> SourceMotionStatus.FROZEN
                recentlyMovedFleetCount > 0 || confirmedMoving.isNotEmpty() -> SourceMotionStatus.MOVING
                else -> SourceMotionStatus.WARMING_UP
            }

            SourceHealthReport(
                sourceName = sourceName,
                status = status,
                fleetFrozen = fleetFrozen,
                focusLineFrozen = focusLineFrozen,
                observedNonDepotCount = currentNonDepotIds.size,
                movingCount = recentlyMovedFleetCount,
                frozenDurationSeconds = if (fleetFrozen || focusLineFrozen) frozenForMs / 1000L else 0L,
                confirmedMovingVehicleIds = confirmedMoving,
                stuckVehicleIds = stuckIds,
                latestPositions = latestPositions,
            )
        }
    }

    /**
     * Evaluates a previously recorded source without adding a new poll (used by fast MQTT overlay
     * merges between HTTP polls so frozen GTFS-RT/BusOne reports stay accurate without double-counting).
     */
    fun peekReport(
        sourceName: String,
        currentVehicles: List<BusVehicle>,
        focusLine: String?,
        nowMillis: Long,
    ): SourceHealthReport {
        val mem = sources[sourceName] ?: return SourceHealthReport(
            sourceName = sourceName,
            status = SourceMotionStatus.WARMING_UP,
            fleetFrozen = false,
            focusLineFrozen = false,
            observedNonDepotCount = 0,
            movingCount = 0,
            frozenDurationSeconds = 0L,
            confirmedMovingVehicleIds = emptySet(),
            stuckVehicleIds = emptySet(),
            latestPositions = emptyMap(),
        )

        return synchronized(mem) {
            if (currentVehicles.isEmpty() || (nowMillis - mem.lastPollAtMillis) > SESSION_GAP_RESET_MS) {
                return@synchronized SourceHealthReport(
                    sourceName = sourceName,
                    status = SourceMotionStatus.WARMING_UP,
                    fleetFrozen = false,
                    focusLineFrozen = false,
                    observedNonDepotCount = 0,
                    movingCount = 0,
                    frozenDurationSeconds = 0L,
                    confirmedMovingVehicleIds = emptySet(),
                    stuckVehicleIds = emptySet(),
                    latestPositions = emptyMap(),
                )
            }

            val normFocus = focusLine?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            val confirmedMoving = HashSet<String>()
            val stuckIds = HashSet<String>()
            val latestPositions = HashMap<String, Pair<Double, Double>>(currentVehicles.size)
            var nonDepotCount = 0
            var eligibleFleetCount = 0
            var recentlyMovedFleetCount = 0
            var eligibleFocusCount = 0
            var recentlyMovedFocusCount = 0

            for (v in currentVehicles) {
                val vid = v.vehicleId.trim()
                if (vid.isEmpty()) continue
                val rec = mem.vehicles[vid] ?: continue
                if (rec.isInDepot) continue
                nonDepotCount++
                latestPositions[vid] = rec.lat to rec.lon

                val seenDurationMs = nowMillis - rec.firstObservedInSourceMillis
                val stationaryMs = nowMillis - rec.firstSeenAtPositionMillis
                val sinceLastMoveMs = nowMillis - rec.lastMovedAtMillis

                if (rec.hasEverMoved && sinceLastMoveMs <= RECENT_MOVEMENT_WINDOW_MS) {
                    confirmedMoving.add(vid)
                }
                val isRecentlyActiveMove = rec.hasEverMoved && sinceLastMoveMs <= FREEZE_MOVEMENT_WINDOW_MS
                if (isRecentlyActiveMove) recentlyMovedFleetCount++
                if (rec.observationCount >= 2 &&
                    stationaryMs >= STUCK_VEHICLE_MIN_MS &&
                    (!rec.hasEverMoved || sinceLastMoveMs >= STUCK_AFTER_PREV_MOVE_MS)
                ) {
                    stuckIds.add(vid)
                }
                val isEligible = rec.observationCount >= MIN_POLLS_FOR_FREEZE &&
                    seenDurationMs >= FREEZE_DURATION_THRESHOLD_MS
                if (isEligible) eligibleFleetCount++
                if (normFocus != null && v.lineCode.equals(normFocus, ignoreCase = true)) {
                    if (isRecentlyActiveMove) recentlyMovedFocusCount++
                    if (isEligible) eligibleFocusCount++
                }
            }

            val observedSpanMs = nowMillis - mem.firstPollAtMillis
            val frozenForMs = (nowMillis - mem.lastAnyMovementAtMillis).coerceAtLeast(0L)
            val fleetFrozen = mem.distinctPollCount >= MIN_POLLS_FOR_FREEZE &&
                observedSpanMs >= FREEZE_DURATION_THRESHOLD_MS &&
                eligibleFleetCount >= MIN_FLEET_VEHICLES_FOR_FREEZE &&
                frozenForMs >= FREEZE_DURATION_THRESHOLD_MS &&
                if (eligibleFleetCount <= 20) {
                    recentlyMovedFleetCount == 0
                } else {
                    (recentlyMovedFleetCount.toDouble() / eligibleFleetCount.toDouble()) < FROZEN_MOVING_RATIO_THRESHOLD
                }
            val focusLineFrozen = normFocus != null &&
                mem.distinctPollCount >= MIN_POLLS_FOR_FREEZE &&
                observedSpanMs >= FREEZE_DURATION_THRESHOLD_MS &&
                eligibleFocusCount >= MIN_FOCUS_LINE_VEHICLES_FOR_FREEZE &&
                recentlyMovedFocusCount == 0

            val status = when {
                fleetFrozen || focusLineFrozen -> SourceMotionStatus.FROZEN
                recentlyMovedFleetCount > 0 || confirmedMoving.isNotEmpty() -> SourceMotionStatus.MOVING
                else -> SourceMotionStatus.WARMING_UP
            }

            SourceHealthReport(
                sourceName = sourceName,
                status = status,
                fleetFrozen = fleetFrozen,
                focusLineFrozen = focusLineFrozen,
                observedNonDepotCount = nonDepotCount,
                movingCount = recentlyMovedFleetCount,
                frozenDurationSeconds = if (fleetFrozen || focusLineFrozen) frozenForMs / 1000L else 0L,
                confirmedMovingVehicleIds = confirmedMoving,
                stuckVehicleIds = stuckIds,
                latestPositions = latestPositions,
            )
        }
    }

    fun reset() {
        sources.clear()
    }

    companion object {
        /** Minimum GPS displacement to consider a bus genuinely moving (exceeds GPS jitter). */
        internal const val MOVEMENT_THRESHOLD_METERS = 20.0

        /** Minimum coordinate separation between two sources to prove one source is stuck behind. */
        internal const val CROSS_SOURCE_DIVERGENCE_METERS = 45.0

        /** Minimum time between two counted polls so bursts don't trigger premature freeze detection. */
        private const val MIN_DISTINCT_POLL_GAP_MS = 8_000L

        /** Minimum distinct polls required before flagging a source frozen standalone. */
        private const val MIN_POLLS_FOR_FREEZE = 3

        /** Standalone freeze duration window (~1.75 min of zero movement across active fleet). */
        internal const val FREEZE_DURATION_THRESHOLD_MS = 105_000L

        /** Window in which a vehicle's displacement counts as active motion for fleet health. */
        private const val FREEZE_MOVEMENT_WINDOW_MS = 115_000L

        /** Window in which a vehicle's confirmed displacement beats a stuck competitor. */
        private const val RECENT_MOVEMENT_WINDOW_MS = 150_000L

        /** Minimum duration at the exact same spot (>= 2 polls) to flag an individual vehicle stuck. */
        internal const val STUCK_VEHICLE_MIN_MS = 45_000L

        /** If a vehicle previously moved, require 90s stationary before flagging it stuck. */
        private const val STUCK_AFTER_PREV_MOVE_MS = 90_000L

        /** Minimum non-depot vehicles required to flag a city-wide fleet freeze. */
        private const val MIN_FLEET_VEHICLES_FOR_FREEZE = 4

        /** Minimum non-depot vehicles on the focused line to flag a focused-line freeze. */
        private const val MIN_FOCUS_LINE_VEHICLES_FOR_FREEZE = 3

        /** Large fleets (>20 buses) with <3% moving buses are treated as frozen (ignores 1-2 jittery units). */
        private const val FROZEN_MOVING_RATIO_THRESHOLD = 0.03

        /** Reset per-source tracking if the app was paused/backgrounded for > 5 minutes. */
        private const val SESSION_GAP_RESET_MS = 5 * 60_000L

        /** Drop unseen vehicles after 8 minutes to keep RAM footprint tiny. */
        private const val VEHICLE_PRUNE_MS = 8 * 60_000L

        /**
         * Fast cross-source freeze detection (~45s): if Source A has >= 2 shared non-depot vehicles
         * stuck at old coordinates while Source B proves those SAME vehicles are actively moving
         * (>= 45m away) and Source A shows 0 movement on those shared vehicles, Source A is flagged
         * `crossSourceFrozen = true` immediately — even before the 105s standalone window elapses!
         */
        internal fun refineWithCrossSourceComparison(
            reports: Map<String, SourceHealthReport>,
        ): Map<String, SourceHealthReport> {
            if (reports.size < 2) return reports
            val refined = HashMap<String, SourceHealthReport>(reports.size)

            for ((nameA, reportA) in reports) {
                var detectedCrossFrozen = reportA.crossSourceFrozen
                if (!reportA.fleetFrozen && !detectedCrossFrozen && reportA.stuckVehicleIds.isNotEmpty()) {
                    for ((nameB, reportB) in reports) {
                        if (nameA == nameB) continue
                        if (reportB.fleetFrozen || reportB.confirmedMovingVehicleIds.isEmpty()) continue

                        var sharedDivergentStuckCount = 0
                        var sharedMovingInACount = 0

                        for (vid in reportB.confirmedMovingVehicleIds) {
                            val posA = reportA.latestPositions[vid] ?: continue
                            val posB = reportB.latestPositions[vid] ?: continue
                            if (vid in reportA.confirmedMovingVehicleIds) {
                                sharedMovingInACount++
                                continue
                            }
                            if (vid in reportA.stuckVehicleIds) {
                                val dist = haversineMeters(posA.first, posA.second, posB.first, posB.second)
                                if (dist >= CROSS_SOURCE_DIVERGENCE_METERS) {
                                    sharedDivergentStuckCount++
                                }
                            }
                        }

                        if (sharedDivergentStuckCount >= 2 && sharedMovingInACount == 0) {
                            detectedCrossFrozen = true
                            break
                        }
                    }
                }

                refined[nameA] = if (detectedCrossFrozen && !reportA.crossSourceFrozen) {
                    reportA.copy(
                        crossSourceFrozen = true,
                        status = SourceMotionStatus.FROZEN,
                    )
                } else {
                    reportA
                }
            }
            return refined
        }

        internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2.0) * sin(dLon / 2.0)
            return r * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        }
    }
}
