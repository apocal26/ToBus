package com.example

import com.example.data.model.BusVehicle
import com.example.data.model.GeoPoint
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.RoutePattern
import com.example.data.model.TransitStop
import com.example.data.model.VehicleStopStatus
import com.example.data.network.BusDiagnosticTracker
import com.example.data.network.DiagCategory
import com.example.data.network.GttVehicleMergeEngine
import com.example.data.network.PipelineBusDecision
import com.example.data.network.ToBusDiagnosticsLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BusDiagnosticsForensicsTest {

    private val baseNowMillis = 1_760_000_000_000L
    private val baseNowSec = baseNowMillis / 1000L

    private val sampleRoute = listOf(
        RoutePattern(
            id = "12:0",
            name = "Linea 12 -> Allason Cap",
            directionId = 0,
            segments = listOf(
                listOf(
                    GeoPoint(45.0600, 7.6600),
                    GeoPoint(45.0650, 7.6600),
                    GeoPoint(45.0700, 7.6600),
                ),
            ),
            stops = listOf(
                TransitStop("S1", "1001", "Partenza Cap", 45.0600, 7.6600, 0),
                TransitStop("S2", "1002", "Fermata Media", 45.0650, 7.6600, 0),
                TransitStop("S3", "1003", "Allason Cap", 45.0700, 7.6600, 0),
            ),
        ),
    )

    @Before
    fun setUp() {
        ToBusDiagnosticsLog.clear()
        GttFleetAnalyzer.resetDepotsForTest()
    }

    private fun makeBus(
        id: String,
        line: String = "12",
        lat: Double = 45.0650,
        lon: Double = 7.6600,
        ageSec: Long = 10L,
        stationarySec: Long? = null,
        movedAgoSec: Long? = 15L,
        sourceName: String = "GTT Ufficiale",
        sources: Set<String> = setOf("GTT Ufficiale", "BusOne Proxy"),
        isRetained: Boolean = false,
        speedKmh: Float? = 22f,
    ): BusVehicle {
        return BusVehicle(
            entityId = "e_$id",
            vehicleId = id,
            label = id,
            rawRouteId = "${line}U",
            lineCode = line,
            tripId = "T_$id",
            directionId = 0,
            latitude = lat,
            longitude = lon,
            bearing = 0f,
            speedKmh = speedKmh,
            timestampSeconds = baseNowSec - ageSec,
            stopId = "S2",
            status = VehicleStopStatus.IN_TRANSIT_TO,
            sourceName = sourceName,
            contributingSources = sources,
            lastMovedAtMillis = movedAgoSec?.let { baseNowMillis - it * 1000L },
            stationarySinceMillis = stationarySec?.let { baseNowMillis - it * 1000L },
            fetchedAtMillis = baseNowMillis,
            isMqttRetained = isRetained,
        )
    }

    @Test
    fun diagnosticsLog_supports600RingBufferVehicleAndWidgetCategoriesAndLongDetails() {
        val longDetail = "src=GTT+BusOne+MaTO raw=[GTT(12s),BusOne(12s),MaTOmq(age=3s,sil=2s)] " +
            "age=12s stat=n/d movAgo=8s spd=24kmh brg=182° pos=45.06500,7.66000 dRoute=5m dTerm=550m " +
            "officialOk=true routeLoaded=true extra=" + "x".repeat(120)
        ToBusDiagnosticsLog.record(
            category = DiagCategory.VEHICLE,
            message = "#9066 L.12 DROPPED_GHOST: mato_terminus_uncorroborated(stat=140s,officialOnline=true)",
            detail = longDetail,
        )
        ToBusDiagnosticsLog.record(
            category = DiagCategory.WIDGET,
            message = "Map #42 L.12 [auto,wide] → 2 mezzi",
            detail = "raw=3 kept=2 shown=2 ghostDrop=[#9066:mato_terminus_uncorroborated]",
        )
        val busOnly = ToBusDiagnosticsLog.snapshot(categories = setOf(DiagCategory.VEHICLE))
        assertEquals(1, busOnly.size)
        assertTrue(busOnly.first().detail!!.length > 250)

        val widgetOnly = ToBusDiagnosticsLog.snapshot(categories = setOf(DiagCategory.WIDGET))
        assertEquals(1, widgetOnly.size)

        // Fill beyond MAX_ENTRIES (600) and verify bounded ring eviction
        for (i in 1..650) {
            ToBusDiagnosticsLog.record(DiagCategory.POLL, "Poll #$i")
        }
        assertEquals(ToBusDiagnosticsLog.MAX_ENTRIES, ToBusDiagnosticsLog.count())
        val report = ToBusDiagnosticsLog.formatReport(headerLines = listOf("Header"))
        assertTrue(report.contains("--- Event Log (220 eventi) ---"))
    }

    @Test
    fun fleetAnalyzer_explainsExactDropReasonForPhantomsAndDeadGhosts() {
        val movingHealthy = makeBus("3401", ageSec = 10L, stationarySec = null, movedAgoSec = 5L)
        val retainedGhost = makeBus(
            "9001",
            sourceName = "MaTO Live",
            sources = setOf("MaTO Live"),
            isRetained = true,
        )
        val matoTerminusGhost = makeBus(
            "9066",
            lat = 45.0700,
            lon = 7.6600,
            ageSec = 40L,
            stationarySec = 150L,
            movedAgoSec = null,
            sourceName = "MaTO Live",
            sources = setOf("MaTO Live"),
            speedKmh = 0f,
        )
        val overstayTerminus = makeBus(
            "3410",
            lat = 45.0700,
            lon = 7.6600,
            ageSec = 20L,
            stationarySec = 1_900L,
            movedAgoSec = null,
            speedKmh = 0f,
        )
        val midrouteDead = makeBus(
            "3411",
            lat = 45.0650,
            lon = 7.6600,
            ageSec = 20L,
            stationarySec = 500L,
            movedAgoSec = null,
            speedKmh = 0f,
        )

        val audit = GttFleetAnalyzer.filterOutConfirmedPhantomsAndDeadGhostsWithAudit(
            vehicles = listOf(movingHealthy, retainedGhost, matoTerminusGhost, overstayTerminus, midrouteDead),
            routePatterns = sampleRoute,
            nowMillis = baseNowMillis,
            healthyOfficialGtfsOnline = true,
        )

        assertEquals(listOf("3401"), audit.kept.map { it.vehicleId })
        val reasons = audit.dropped.associate { (b, r) -> b.vehicleId to r }
        assertEquals("mato_retained_uncorroborated", reasons["9001"])
        assertTrue(reasons["9066"]!!.startsWith("mato_terminus_uncorroborated"))
        assertTrue(reasons["3410"]!!.startsWith("universal_stationary_over_30m"))
        assertTrue(reasons["3411"]!!.startsWith("midroute_stationary_8m"))
    }

    @Test
    fun busDiagnosticTracker_logsOnlyTransitionsAndCapturesDropsAndDisappearances() {
        val tracker = BusDiagnosticTracker()
        val liveBus = makeBus("3429", ageSec = 8L, stationarySec = null, movedAgoSec = 5L)
        val retainedGhost = makeBus(
            "9066",
            sourceName = "MaTO Live",
            sources = setOf("MaTO Live"),
            isRetained = true,
        )

        // Cycle 1: #3429 appears live, #9066 is staged retained
        tracker.evaluateCycle(
            focusLine = "12",
            trigger = "POLL",
            rawGtt = listOf(liveBus),
            rawBusOne = listOf(liveBus),
            rawMatoLive = emptyList(),
            rawMatoGql = emptyList(),
            rawMatoRetained = listOf(retainedGhost),
            mergedAndGraceVehicles = listOf(liveBus),
            routePatterns = sampleRoute,
            healthyOfficialGtfsOnline = true,
            pipelineDecisions = listOf(
                PipelineBusDecision(
                    vehicleId = "9066",
                    lineCode = "12",
                    stage = "RETAINED_STAGE",
                    action = "STAGED",
                    reason = "retained_no_official_match",
                    detail = "notInActiveGttOrBusOne",
                ),
            ),
            nowMillis = baseNowMillis,
        )
        val countAfterCycle1 = ToBusDiagnosticsLog.snapshot(setOf(DiagCategory.VEHICLE)).size
        assertEquals(2, countAfterCycle1) // #3429 APPEARED + #9066 STAGED_RETAINED

        val traces1 = tracker.getLatestFocusedBusTraces()
        assertEquals(2, traces1.size)
        assertTrue(traces1.first { it.vehicleId == "3429" }.isKeptByPipeline)
        assertFalse(traces1.first { it.vehicleId == "9066" }.isKeptByPipeline)
        assertEquals("STAGED_RETAINED", traces1.first { it.vehicleId == "9066" }.verdictCode)

        // Cycle 2: identical state -> ZERO new transition log entries!
        tracker.evaluateCycle(
            focusLine = "12",
            trigger = "POLL",
            rawGtt = listOf(liveBus),
            rawBusOne = listOf(liveBus),
            rawMatoLive = emptyList(),
            rawMatoGql = emptyList(),
            rawMatoRetained = listOf(retainedGhost),
            mergedAndGraceVehicles = listOf(liveBus),
            routePatterns = sampleRoute,
            healthyOfficialGtfsOnline = true,
            pipelineDecisions = listOf(
                PipelineBusDecision(
                    vehicleId = "9066",
                    lineCode = "12",
                    stage = "RETAINED_STAGE",
                    action = "STAGED",
                    reason = "retained_no_official_match",
                    detail = "notInActiveGttOrBusOne",
                ),
            ),
            nowMillis = baseNowMillis + 25_000L,
        )
        assertEquals(countAfterCycle1, ToBusDiagnosticsLog.snapshot(setOf(DiagCategory.VEHICLE)).size)

        // Cycle 3: #3429 becomes mid-route stuck (260s -> WARN_ON) and loses BusOne (SRC_SHIFT)
        val stuckBus = makeBus(
            "3429",
            ageSec = 12L,
            stationarySec = 260L,
            movedAgoSec = null,
            sourceName = "GTT Ufficiale",
            sources = setOf("GTT Ufficiale"),
            speedKmh = 0f,
        )
        tracker.evaluateCycle(
            focusLine = "12",
            trigger = "POLL",
            rawGtt = listOf(stuckBus),
            rawBusOne = emptyList(),
            rawMatoLive = emptyList(),
            rawMatoGql = emptyList(),
            rawMatoRetained = emptyList(),
            mergedAndGraceVehicles = listOf(stuckBus),
            routePatterns = sampleRoute,
            healthyOfficialGtfsOnline = true,
            pipelineDecisions = emptyList(),
            nowMillis = baseNowMillis + 50_000L,
        )
        val logsAfter3 = ToBusDiagnosticsLog.snapshot(setOf(DiagCategory.VEHICLE)).map { it.message }
        assertTrue(logsAfter3.any { it.contains("#3429") && it.contains("WARN_ON") })
        assertTrue(logsAfter3.any { it.contains("#3429") && it.contains("SRC_SHIFT") })

        val warnTrace = tracker.getLatestFocusedBusTraces().first { it.vehicleId == "3429" }
        assertTrue(warnTrace.formatForensicLine(hideInactiveOnMap = true).contains("UI=HIDDEN(hideInactive:blockedWarn)"))
        assertTrue(warnTrace.formatForensicLine(hideInactiveOnMap = false).contains("UI=SHOW"))

        // Cycle 4: #3429 vanishes from ALL feeds -> DISAPPEARED recorded in log and recentDrops
        tracker.evaluateCycle(
            focusLine = "12",
            trigger = "POLL",
            rawGtt = emptyList(),
            rawBusOne = emptyList(),
            rawMatoLive = emptyList(),
            rawMatoGql = emptyList(),
            rawMatoRetained = emptyList(),
            mergedAndGraceVehicles = emptyList(),
            routePatterns = sampleRoute,
            healthyOfficialGtfsOnline = true,
            pipelineDecisions = emptyList(),
            nowMillis = baseNowMillis + 75_000L,
        )
        val logsAfter4 = ToBusDiagnosticsLog.snapshot(setOf(DiagCategory.VEHICLE)).map { it.message }
        assertTrue(logsAfter4.any { it.contains("#3429") && it.contains("DISAPPEARED") })
        assertTrue(tracker.getRecentDroppedBuses().any { it.vehicleId == "3429" && it.stage == "FEED_GONE" })
    }

    @Test
    fun mergeEngine_emitsForensicDecisionsForRetainedZombieAndGrace() {
        val decisions = mutableListOf<PipelineBusDecision>()
        val freshGtt = makeBus("3401", ageSec = 5L)
        val zombieGtt = makeBus("3402", ageSec = 500L)
        val retainedUnmatched = makeBus(
            "9099",
            sourceName = "MaTO Live",
            sources = setOf("MaTO Live"),
            isRetained = true,
        )

        val merged = GttVehicleMergeEngine.mergeMultiSourceVehicles(
            matoVehicles = emptyList(),
            gttVehicles = listOf(freshGtt, zombieGtt),
            busOneVehicles = emptyList(),
            nowMillis = baseNowMillis,
            focusLine = "12",
            matoRetainedCandidates = listOf(retainedUnmatched),
            onDecision = { decisions.add(it) },
        )
        assertEquals(listOf("3401"), merged.map { it.vehicleId })
        assertNotNull(decisions.firstOrNull { it.vehicleId == "9099" && it.stage == "RETAINED_STAGE" && it.action == "STAGED" })
        assertNotNull(decisions.firstOrNull { it.vehicleId == "3402" && it.stage == "MERGE_ZOMBIE" && it.action == "DROPPED" })

        // Verify grace bridge & expiration callbacks
        val graceCache = HashMap<String, GttVehicleMergeEngine.VerifiedBusGraceEntry>()
        GttVehicleMergeEngine.applyVerifiedDropoutGrace(
            freshVehicles = listOf(freshGtt),
            graceCache = graceCache,
            focusLine = "12",
            nowMillis = baseNowMillis,
            onDecision = { decisions.add(it) },
        )
        val bridged = GttVehicleMergeEngine.applyVerifiedDropoutGrace(
            freshVehicles = emptyList(),
            graceCache = graceCache,
            focusLine = "12",
            nowMillis = baseNowMillis + 25_000L,
            onDecision = { decisions.add(it) },
        )
        assertEquals(1, bridged.size)
        assertTrue(bridged.first().isHoldover)
        assertNotNull(decisions.firstOrNull { it.vehicleId == "3401" && it.action == "GRACE_BRIDGE" })

        val afterExpiry = GttVehicleMergeEngine.applyVerifiedDropoutGrace(
            freshVehicles = emptyList(),
            graceCache = graceCache,
            focusLine = "12",
            nowMillis = baseNowMillis + 70_000L,
            onDecision = { decisions.add(it) },
        )
        assertTrue(afterExpiry.isEmpty())
        assertNotNull(decisions.firstOrNull { it.vehicleId == "3401" && it.action == "GRACE_EXPIRED" })
    }

    @Test
    fun movingBeatsStuck_neverTeleportsLiveBusBackwardsToStaleBusOneSnapshot() {
        // Reproduces exact #9624 bug from real Linea 2 log:
        // GTT (age 64s) & MaTO (age 78s, briefly paused -> marked stuck), while BusOne is 197s old & 520m behind
        // (still inside SourceHealthTracker's 150s moving window). Stale BusOne must NEVER teleport #9624 backwards!
        val gtt9624 = makeBus(
            id = "9624",
            line = "2",
            lat = 45.0900,
            lon = 7.6700,
            ageSec = 64L,
            sourceName = "GTT Ufficiale",
            sources = setOf("GTT Ufficiale"),
        )
        val mato9624 = makeBus(
            id = "9624",
            line = "2",
            lat = 45.0901,
            lon = 7.6701,
            ageSec = 78L,
            sourceName = "MaTO Live",
            sources = setOf("MaTO Live"),
        )
        val staleBusOne9624 = makeBus(
            id = "9624",
            line = "2",
            lat = 45.0854, // ~512m behind!
            lon = 7.6700,
            ageSec = 197L,
            sourceName = "BusOne Proxy",
            sources = setOf("BusOne Proxy"),
        )
        val reports = mapOf(
            "MaTO Live" to com.example.data.network.SourceHealthReport(
                sourceName = "MaTO Live",
                status = com.example.data.network.SourceMotionStatus.MOVING,
                fleetFrozen = false,
                focusLineFrozen = false,
                crossSourceFrozen = false,
                observedNonDepotCount = 10,
                movingCount = 8,
                frozenDurationSeconds = 0L,
                confirmedMovingVehicleIds = emptySet(),
                stuckVehicleIds = setOf("9624"),
                latestPositions = mapOf("9624" to (45.0901 to 7.6701)),
            ),
            "BusOne Proxy" to com.example.data.network.SourceHealthReport(
                sourceName = "BusOne Proxy",
                status = com.example.data.network.SourceMotionStatus.MOVING,
                fleetFrozen = false,
                focusLineFrozen = false,
                crossSourceFrozen = false,
                observedNonDepotCount = 100,
                movingCount = 80,
                frozenDurationSeconds = 0L,
                confirmedMovingVehicleIds = setOf("9624"),
                stuckVehicleIds = emptySet(),
                latestPositions = mapOf("9624" to (45.0854 to 7.6700)),
            ),
        )
        val decisions = mutableListOf<PipelineBusDecision>()
        val merged = GttVehicleMergeEngine.mergeMultiSourceVehicles(
            matoVehicles = listOf(mato9624),
            gttVehicles = listOf(gtt9624),
            busOneVehicles = listOf(staleBusOne9624),
            nowMillis = baseNowMillis,
            focusLine = "2",
            sourceReports = reports,
            onDecision = { decisions.add(it) },
        )
        assertEquals(1, merged.size)
        val winner = merged.first()
        assertTrue(
            "Stale 197s BusOne must NOT beat fresher 64s/78s GTT/MaTO via moving_beats_stuck",
            winner.sourceName != "BusOne Proxy",
        )
        assertEquals(45.0900, winner.latitude, 0.0005)
        assertFalse(decisions.any { it.reason.contains("moving_beats_stuck") })
    }

    @Test
    fun kinematicEvaluator_survivesOlderSourceFallbackAndDuplicateCachedPacketWithoutFalseQuarantine() {
        val poses = HashMap<String, GttVehicleMergeEngine.VehicleKinematicPose>()
        val quarantined = mutableListOf<String>()

        // 1. #8008 tracked live on GTT (age 10s)
        val gttLive = makeBus(
            id = "8008",
            line = "4",
            lat = 45.0700,
            lon = 7.6600,
            ageSec = 10L,
            sourceName = "GTT Ufficiale",
            sources = setOf("GTT Ufficiale"),
        )
        val s1 = GttVehicleMergeEngine.evaluateKinematicAnomalies(
            vehicles = listOf(gttLive),
            kinematicPoses = poses,
            nowMillis = baseNowMillis,
        ) { b, _ -> quarantined.add(b.vehicleId) }
        assertEquals(1, s1.size)

        // 2. 1 second later, GTT transiently omits #8008 so BusOne (130s older, 550m behind) becomes sole source!
        // Because BusOne timestamp is 120s older than GTT, effective dtSec = 120s (~16 km/h) -> NO quarantine!
        val busOneFallback = makeBus(
            id = "8008",
            line = "4",
            lat = 45.0650,
            lon = 7.6600,
            ageSec = 130L,
            sourceName = "BusOne Proxy",
            sources = setOf("BusOne Proxy"),
        )
        val s2 = GttVehicleMergeEngine.evaluateKinematicAnomalies(
            vehicles = listOf(busOneFallback),
            kinematicPoses = poses,
            nowMillis = baseNowMillis + 1_000L,
        ) { b, _ -> quarantined.add(b.vehicleId) }
        assertEquals(1, s2.size)
        assertTrue("Older-source fallback must NOT trigger kinematic quarantine", quarantined.isEmpty())

        // 3. Real glitch jump (same source, +1s timestamp, 600m jump) gets HELD on strike 1;
        //    re-evaluating the EXACT SAME cached packet 1s later must NOT double-strike into quarantine!
        val glitchBus = makeBus(
            id = "8009",
            line = "4",
            lat = 45.0700,
            lon = 7.6600,
            ageSec = 10L,
            sourceName = "BusOne Proxy",
            sources = setOf("BusOne Proxy"),
        )
        GttVehicleMergeEngine.evaluateKinematicAnomalies(
            vehicles = listOf(glitchBus),
            kinematicPoses = poses,
            nowMillis = baseNowMillis,
        ) { b, _ -> quarantined.add(b.vehicleId) }

        val jumpedPacket = glitchBus.copy(
            latitude = 45.0755, // ~611m away
            timestampSeconds = baseNowSec - 8L,
        )
        val hold1 = GttVehicleMergeEngine.evaluateKinematicAnomalies(
            vehicles = listOf(jumpedPacket),
            kinematicPoses = poses,
            nowMillis = baseNowMillis + 2_000L,
        ) { b, _ -> quarantined.add(b.vehicleId) }
        assertEquals(1, hold1.size)
        assertEquals(45.0700, hold1.first().latitude, 0.0001)

        // Exact same unchanged cached packet evaluated 1s later (e.g. during overlay tick)
        val hold2 = GttVehicleMergeEngine.evaluateKinematicAnomalies(
            vehicles = listOf(jumpedPacket),
            kinematicPoses = poses,
            nowMillis = baseNowMillis + 3_000L,
        ) { b, _ -> quarantined.add(b.vehicleId) }
        assertEquals(1, hold2.size)
        assertEquals(45.0700, hold2.first().latitude, 0.0001)
        assertFalse("Duplicate cached packet must NEVER double-strike into quarantine", "8009" in quarantined)
    }

    @Test
    fun retainedCorroboration_rejectsDistantRetainedGhostAndDeduplicatesPromotedLogs() {
        // Reproduces exact #9710 L.2 bug: official GTT bus is parked 1465m away from stale MaTO retained packet.
        val gtt9710 = makeBus(
            id = "9710",
            line = "2",
            lat = 45.0642,
            lon = 7.6328,
            ageSec = 20L,
            sourceName = "GTT Ufficiale",
            sources = setOf("GTT Ufficiale"),
        )
        val farRetained9710 = makeBus(
            id = "9710",
            line = "2",
            lat = 45.0774, // ~1467m north!
            lon = 7.6328,
            ageSec = 5L,
            sourceName = "MaTO Live",
            sources = setOf("MaTO Live"),
            isRetained = true,
        )
        val decisions = mutableListOf<PipelineBusDecision>()
        val merged = GttVehicleMergeEngine.mergeMultiSourceVehicles(
            matoVehicles = emptyList(),
            gttVehicles = listOf(gtt9710),
            busOneVehicles = emptyList(),
            nowMillis = baseNowMillis,
            focusLine = "2",
            matoRetainedCandidates = listOf(farRetained9710),
            onDecision = { decisions.add(it) },
        )
        assertEquals(1, merged.size)
        assertEquals(setOf("GTT Ufficiale"), merged.first().contributingSources)
        assertTrue(
            "Distant retained packet must be rejected with retained_spatial_mismatch",
            decisions.any { it.vehicleId == "9710" && it.action == "STAGED" && it.reason.startsWith("retained_spatial_mismatch") },
        )
        assertFalse(decisions.any { it.vehicleId == "9710" && it.action == "PROMOTED" })

        // Now verify BusDiagnosticTracker deduplicates repeated PROMOTED logs across rapid 1.5s cycles
        val tracker = BusDiagnosticTracker()
        val promoDecision = PipelineBusDecision(
            vehicleId = "9711",
            lineCode = "2",
            stage = "RETAINED_STAGE",
            action = "PROMOTED",
            reason = "retained_corroborated_by_GTT",
            detail = "dist=45m",
        )
        val bus9711 = makeBus("9711", line = "2")
        for (i in 0 until 5) {
            tracker.evaluateCycle(
                focusLine = "2",
                trigger = "MQTT_OVERLAY",
                rawGtt = listOf(bus9711),
                rawBusOne = emptyList(),
                rawMatoLive = emptyList(),
                rawMatoGql = emptyList(),
                rawMatoRetained = emptyList(),
                mergedAndGraceVehicles = listOf(bus9711),
                routePatterns = sampleRoute,
                healthyOfficialGtfsOnline = true,
                pipelineDecisions = listOf(promoDecision),
                nowMillis = baseNowMillis + i * 2_000L,
            )
        }
        val promoLogs = ToBusDiagnosticsLog.snapshot(setOf(DiagCategory.VEHICLE))
            .count { it.message.contains("#9711") && it.message.contains("PROMOTED") }
        assertEquals("PROMOTED must be logged once per cooldown window, not spammed every tick", 1, promoLogs)
    }

    @Test
    fun peekReport_doesNotFalselyAgeUnpolledHttpSourceIntoStuckBetweenPolls() {
        val tracker = com.example.data.network.SourceHealthTracker()
        val t0 = baseNowMillis
        val t1 = t0 + 25_000L
        val gttBus = makeBus(
            id = "3438",
            line = "56",
            lat = 45.0600,
            lon = 7.6150,
            ageSec = 5L,
            sourceName = "GTT Ufficiale",
            sources = setOf("GTT Ufficiale"),
        )
        tracker.recordAndEvaluate("GTT Ufficiale", listOf(gttBus), "56", t0)
        val repAtPoll = tracker.recordAndEvaluate("GTT Ufficiale", listOf(gttBus), "56", t1)
        assertFalse("After only 25s across 2 polls, bus is NOT stuck", "3438" in repAtPoll.stuckVehicleIds)

        // 22s later (before the next 25s poll!), MQTT_OVERLAY peeks GTT health:
        // must NOT falsely age the unpolled HTTP source into stuckVehicleIds!
        val peekRep = tracker.peekReport("GTT Ufficiale", listOf(gttBus), "56", t1 + 22_000L)
        assertFalse(
            "peekReport must evaluate against lastPollAtMillis, not wall clock between polls",
            "3438" in peekRep.stuckVehicleIds,
        )
    }

    @Test
    fun mergeConflictLog_suppressesRoutineLatencyDriftAndMqttUnsubscribeEncodesValidHeader() {
        // MaTO (age 2s) vs lagging BusOne (age 175s), 600m apart = normal ~12 km/h travel -> NOT a conflict
        val mato = makeBus("3440", line = "56", lat = 45.0600, lon = 7.6450, ageSec = 2L, sourceName = "MaTO Live")
        val laggingBusOne = makeBus("3440", line = "56", lat = 45.0546, lon = 7.6450, ageSec = 175L, sourceName = "BusOne Proxy")
        val routineDecisions = mutableListOf<PipelineBusDecision>()
        GttVehicleMergeEngine.mergeMultiSourceVehicles(
            matoVehicles = listOf(mato),
            gttVehicles = emptyList(),
            busOneVehicles = listOf(laggingBusOne),
            nowMillis = baseNowMillis,
            focusLine = "56",
            onDecision = { routineDecisions.add(it) },
        )
        assertFalse(
            "Routine 173s latency travel drift (600m) must not spam MERGE_CONFLICT logs",
            routineDecisions.any { it.stage == "MERGE" },
        )

        // Genuine same-timestamp spatial disagreement (5s apart, 600m apart) MUST still log!
        val divergentGtt = makeBus("3440", line = "56", lat = 45.0546, lon = 7.6450, ageSec = 7L, sourceName = "GTT Ufficiale")
        val conflictDecisions = mutableListOf<PipelineBusDecision>()
        GttVehicleMergeEngine.mergeMultiSourceVehicles(
            matoVehicles = listOf(mato),
            gttVehicles = listOf(divergentGtt),
            busOneVehicles = emptyList(),
            nowMillis = baseNowMillis,
            focusLine = "56",
            onDecision = { conflictDecisions.add(it) },
        )
        assertTrue(
            "Genuine same-timestamp 600m spatial conflict must still be logged",
            conflictDecisions.any { it.stage == "MERGE" && it.vehicleId == "3440" },
        )

        // Verify MQTT UNSUBSCRIBE packet encoding (0xA2 header + packetId + length-prefixed topics)
        val pkt = com.example.data.network.MatoMqttLiveClient.encodeUnsubscribePacket(
            packetId = 0x002A,
            topics = listOf("/12/#", "/56/#"),
        )
        assertEquals(0xA2.toByte(), pkt[0])
        assertEquals(0x00.toByte(), pkt[2])
        assertEquals(0x2A.toByte(), pkt[3])
        assertTrue(pkt.size == 2 + 2 + (2 + 5) + (2 + 5))
    }

    @Test
    fun diagnosticTracker_logsDroppedGhostOnceEvenWhenStationarySecondsTickUp() {
        val tracker = BusDiagnosticTracker()
        // #3402 parked off-route stationary for 14m+, dropped by midroute_stationary_8m(stat=Ns,spd=-1)
        for (tick in 0 until 6) {
            val now = baseNowMillis + tick * 2_000L
            val stuckOffRoute = makeBus(
                id = "3402",
                line = "56",
                lat = 45.07191,
                lon = 7.71380,
                ageSec = 25L,
                stationarySec = 900L,
                movedAgoSec = null,
                speedKmh = null,
                sourceName = "GTT Ufficiale",
                sources = setOf("GTT Ufficiale", "BusOne Proxy"),
            )
            tracker.evaluateCycle(
                focusLine = "56",
                trigger = "MQTT_OVERLAY",
                rawGtt = listOf(stuckOffRoute),
                rawBusOne = listOf(stuckOffRoute),
                rawMatoLive = emptyList(),
                rawMatoGql = emptyList(),
                rawMatoRetained = emptyList(),
                mergedAndGraceVehicles = listOf(stuckOffRoute),
                routePatterns = sampleRoute,
                healthyOfficialGtfsOnline = true,
                pipelineDecisions = emptyList(),
                nowMillis = now,
            )
        }
        val dropEvents = ToBusDiagnosticsLog.snapshot(setOf(DiagCategory.VEHICLE))
            .count { it.message.contains("#3402") && it.message.contains("DROPPED_GHOST") }
        assertEquals("Dropped ghost must be logged once on transition, not every 2s as stat=Ns ticks", 1, dropEvents)
        val recentDropEntries = tracker.getRecentDroppedBuses().count { it.vehicleId == "3402" }
        assertEquals("Recent drops list must not be flooded by the same dropped ghost", 1, recentDropEntries)
    }
}
