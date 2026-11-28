package com.example

import com.example.data.model.BusVehicle
import com.example.data.model.GeoPoint
import com.example.data.model.GttDepot
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.RoutePattern
import com.example.data.model.TransitStop
import com.example.data.model.VehicleFreshness
import com.example.data.model.VehicleStopStatus
import com.example.data.network.GttDepotGeometryService
import com.example.data.network.GttVehicleMergeEngine
import com.example.data.network.MatoMqttLiveClient
import com.example.notifications.FavoriteAlertsScheduler
import com.example.ui.components.buildGttDepotsLabelCollection
import com.example.ui.components.buildGttDepotsPolygonCollection
import com.example.widget.WidgetRefreshJobScheduler
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Polygon
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Comprehensive JVM lifecycle tests validating the deterministic ToBus vehicle tracking engine:
 * - MQTT PUBLISH RETAIN bit decoding and staging pool isolation
 * - Corroboration-gated promotion of retained packets (GTT/BusOne or fresh live PUBLISH)
 * - Immunity to re-subscribe retained bursts
 * - TripUpdates non-corroboration invariant (#9252 bug)
 * - Movement-first health & map `!` badge rules (no `!` on moving buses or <4m stops; `!` on >=4m stuck)
 * - Terminus layover rules (<=30m corroborated visible; >30m or uncorroborated hidden)
 * - Cross-source handoff & tunnel exit kinematic safety (no false quarantines)
 * - Single-poll GTFS-RT verified dropout grace window (55s) with strict ghost exclusions
 */
class VehicleTrackingLifecycleTest {

    private val nowMillis = 1_765_000_000_000L
    private val nowSec = nowMillis / 1000L

    private fun line12Pattern(): RoutePattern {
        val allasonCap = TransitStop(
            stopId = "3001",
            stopCode = "3001",
            name = "ALLASON CAP",
            lat = 45.0330,
            lon = 7.6190,
            directionId = 0,
            sequence = 0,
        )
        val midStop = TransitStop(
            stopId = "3050",
            stopCode = "3050",
            name = "CORSO ORBASSANO",
            lat = 45.0500,
            lon = 7.6500,
            directionId = 0,
            sequence = 12,
        )
        val vittorioCap = TransitStop(
            stopId = "3099",
            stopCode = "3099",
            name = "VITTORIO EMANUELE II CAP",
            lat = 45.0660,
            lon = 7.6820,
            directionId = 0,
            sequence = 25,
        )
        return RoutePattern(
            id = "12_0",
            name = "VITTORIO EMANUELE II CAP",
            directionId = 0,
            segments = listOf(
                listOf(
                    GeoPoint(45.0330, 7.6190),
                    GeoPoint(45.0500, 7.6500),
                    GeoPoint(45.0660, 7.6820),
                ),
            ),
            stops = listOf(allasonCap, midStop, vittorioCap),
        )
    }

    private fun bus(
        id: String,
        lineCode: String = "12",
        lat: Double = 45.0500,
        lon: Double = 7.6500,
        tsSec: Long = nowSec - 10L,
        source: String = "GTT Ufficiale",
        sources: Set<String> = setOf(source),
        stationarySinceMillis: Long? = null,
        lastMovedAtMillis: Long? = null,
        speedKmh: Float? = 22f,
        isMqttRetained: Boolean = false,
        hasExplicitTimestamp: Boolean = true,
        fetchedAtMillis: Long = nowMillis,
    ) = BusVehicle(
        entityId = "${source}_$id",
        vehicleId = id,
        label = id,
        rawRouteId = "${lineCode}U",
        lineCode = lineCode,
        tripId = "TRIP_$id",
        directionId = 0,
        latitude = lat,
        longitude = lon,
        bearing = 60f,
        speedKmh = speedKmh,
        timestampSeconds = tsSec,
        stopId = null,
        status = VehicleStopStatus.IN_TRANSIT_TO,
        sourceName = source,
        contributingSources = sources,
        lastMovedAtMillis = lastMovedAtMillis,
        stationarySinceMillis = stationarySinceMillis,
        fetchedAtMillis = fetchedAtMillis,
        isMqttRetained = isMqttRetained,
        hasExplicitTimestamp = hasExplicitTimestamp,
    )

    private fun encodeMqttPublishFrame(
        topic: String,
        payload: String,
        isRetained: Boolean,
        qos: Int = 0,
    ): ByteArray {
        val topicBytes = topic.toByteArray(StandardCharsets.UTF_8)
        val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
        val body = ByteArrayOutputStream()
        body.write((topicBytes.size ushr 8) and 0xFF)
        body.write(topicBytes.size and 0xFF)
        body.write(topicBytes)
        if (qos > 0) {
            body.write(0x00)
            body.write(0x07)
        }
        body.write(payloadBytes)
        val bodyBytes = body.toByteArray()

        val out = ByteArrayOutputStream()
        val header = 0x30 or ((qos and 0x03) shl 1) or (if (isRetained) 0x01 else 0x00)
        out.write(header)
        var rem = bodyBytes.size
        do {
            var digit = rem % 128
            rem /= 128
            if (rem > 0) digit = digit or 0x80
            out.write(digit)
        } while (rem > 0)
        out.write(bodyBytes)
        return out.toByteArray()
    }

    @Test
    fun scenario1_mqttDecoderExtractsRetainBitAndIsolatesRetainedFromLiveSnapshot() {
        val accumulator = MatoMqttLiveClient.MqttFrameAccumulator()
        val retainedFrame = encodeMqttPublishFrame(
            topic = "/12/9024",
            payload = """{"veh":"9024","lat":45.0500,"lon":7.6500,"dir":0}""",
            isRetained = true,
        )
        val liveFrame = encodeMqttPublishFrame(
            topic = "/12/3429",
            payload = """{"veh":"3429","lat":45.0510,"lon":7.6520,"dir":0}""",
            isRetained = false,
        )

        val packets = accumulator.appendAndDecode(retainedFrame + liveFrame)
        assertEquals(2, packets.size)
        val p0 = packets[0] as MatoMqttLiveClient.MqttPacket.Publish
        val p1 = packets[1] as MatoMqttLiveClient.MqttPacket.Publish
        assertTrue("Expected RETAIN=1 on first packet", p0.isRetained)
        assertFalse("Expected RETAIN=0 on second packet", p1.isRetained)

        val client = MatoMqttLiveClient { OkHttpClient() }
        val retainedBuses = MatoMqttLiveClient.parseMatoMessage(p0.topic, p0.payload, nowMillis, isRetained = p0.isRetained)
        val liveBuses = MatoMqttLiveClient.parseMatoMessage(p1.topic, p1.payload, nowMillis, isRetained = p1.isRetained)
        assertEquals(1, retainedBuses.size)
        assertEquals(1, liveBuses.size)
        assertTrue(retainedBuses.first().isMqttRetained)
        assertFalse(retainedBuses.first().hasExplicitTimestamp)
        assertFalse(liveBuses.first().isMqttRetained)

        client.upsertMqttVehicle(retainedBuses.first(), isRetained = true)
        client.upsertMqttVehicle(liveBuses.first(), isRetained = false)

        val liveSnapshot = client.snapshotFreshVehicles(nowMillis)
        val stagedRetained = client.snapshotRetainedCandidates(nowMillis)

        assertEquals(listOf("3429"), liveSnapshot.map { it.vehicleId })
        assertEquals(listOf("9024"), stagedRetained.map { it.vehicleId })
    }

    @Test
    fun scenario2_retainedBusPromotedOnlyByOfficialCorroborationOrLivePublish() {
        val client = MatoMqttLiveClient { OkHttpClient() }
        val retained9024 = bus(
            id = "9024",
            lat = 45.0330,
            lon = 7.6190,
            tsSec = nowSec,
            source = "MaTO Live",
            isMqttRetained = true,
            hasExplicitTimestamp = false,
            fetchedAtMillis = nowMillis,
        )
        val retained9059 = bus(
            id = "9059",
            lat = 45.0410,
            lon = 7.6300,
            tsSec = nowSec,
            source = "MaTO Live",
            isMqttRetained = true,
            hasExplicitTimestamp = false,
            fetchedAtMillis = nowMillis,
        )
        client.upsertMqttVehicle(retained9024, isRetained = true)
        client.upsertMqttVehicle(retained9059, isRetained = true)

        // Official GTT confirms ONLY #9024 at a newer position; #9059 is absent from GTT/BusOne
        val gtt9024 = bus(
            id = "9024",
            lat = 45.0525,
            lon = 7.6540,
            tsSec = nowSec - 12L,
            source = "GTT Ufficiale",
        )
        val merged = GttVehicleMergeEngine.mergeMultiSourceVehicles(
            matoVehicles = client.snapshotFreshVehicles(nowMillis),
            gttVehicles = listOf(gtt9024),
            busOneVehicles = emptyList(),
            nowMillis = nowMillis,
            focusLine = "12",
            matoRetainedCandidates = client.snapshotRetainedCandidates(nowMillis),
        )
        // Only #9024 survives, and GTT official coordinates MUST win (never overwritten by stale retained coords)
        assertEquals(listOf("9024"), merged.map { it.vehicleId })
        assertEquals(45.0525, merged.first().latitude, 0.00001)
        assertEquals("GTT Ufficiale", merged.first().sourceName)

        // Now #9059 sends a genuine non-retained live PUBLISH -> promoted out of staging!
        val live9059 = retained9059.copy(
            latitude = 45.0420,
            longitude = 7.6315,
            isMqttRetained = false,
            fetchedAtMillis = nowMillis + 5_000L,
        )
        client.upsertMqttVehicle(live9059, isRetained = false)
        assertEquals(listOf("9059"), client.snapshotFreshVehicles(nowMillis + 5_000L).map { it.vehicleId })
        assertFalse(client.snapshotRetainedCandidates(nowMillis + 5_000L).any { it.vehicleId == "9059" })
    }

    @Test
    fun scenario3_resubscribeDuplicateRetainedNeverRefreshesTimestampOrLeaksIntoLive() {
        val client = MatoMqttLiveClient { OkHttpClient() }
        val firstArrival = nowMillis - 120_000L
        val retained9066 = bus(
            id = "9066",
            lat = 45.0330,
            lon = 7.6190,
            tsSec = firstArrival / 1000L,
            source = "MaTO Live",
            isMqttRetained = true,
            fetchedAtMillis = firstArrival,
        )
        client.upsertMqttVehicle(retained9066, isRetained = true)

        // Simulate a reconnect/re-subscribe 2 minutes later delivering the exact same retained coords
        val duplicateRetained = retained9066.copy(
            timestampSeconds = nowSec,
            fetchedAtMillis = nowMillis,
        )
        client.upsertMqttVehicle(duplicateRetained, isRetained = true)

        assertTrue(client.snapshotFreshVehicles(nowMillis).isEmpty())
        val staged = client.snapshotRetainedCandidates(nowMillis)
        assertEquals(1, staged.size)
        assertEquals(
            "Duplicate retained delivery must NOT refresh original arrival timestamp",
            firstArrival,
            staged.first().fetchedAtMillis,
        )
    }

    @Test
    fun scenario4_tripUpdatesNeverDisguisesMatoOnlyGhostLikeBus9252() {
        val patterns = listOf(line12Pattern())
        // #9252: MaTO Live + TripUpdates (-10 min), stationary 3m30s mid-route while healthy GTT is online
        val bus9252 = bus(
            id = "9252",
            lat = 45.0500,
            lon = 7.6500,
            tsSec = nowSec - 61L,
            source = "MaTO Live",
            sources = setOf("MaTO Live", "TripUpdates"),
            stationarySinceMillis = nowMillis - 210_000L,
            speedKmh = 0f,
        )
        assertEquals(setOf("MaTO Live"), bus9252.gpsSourceNames())
        assertFalse(bus9252.hasOfficialGtfsCorroboration())
        assertTrue(
            "TripUpdates must NOT shield a MaTO-only stationary phantom when healthy GTT/BusOne is online",
            GttFleetAnalyzer.isConfirmedMatoPhantom(
                bus = bus9252,
                routePatterns = patterns,
                nowMillis = nowMillis,
                healthyOfficialGtfsOnline = true,
            ),
        )
    }

    @Test
    fun scenario5_movingBusWithLaggedTimestampNeverGetsStaleOrMapWarningBadge() {
        val patterns = listOf(line12Pattern())
        // Bus physically moved 18 seconds ago, even though GTT feed timestamp is 180s or 300s behind
        val movingLagged180 = bus(
            id = "3429",
            lat = 45.0500,
            lon = 7.6500,
            tsSec = nowSec - 180L,
            source = "GTT Ufficiale",
            lastMovedAtMillis = nowMillis - 18_000L,
            stationarySinceMillis = null,
            speedKmh = 28f,
        )
        val health180 = GttFleetAnalyzer.assess(movingLagged180, patterns, nowMillis)
        assertFalse(health180.isStaleGps)
        assertFalse(health180.isStuck)
        assertFalse(health180.showBlockedWarningOnMap)
        assertEquals(VehicleFreshness.SLOW, movingLagged180.freshness(nowMillis))

        val movingLagged300 = movingLagged180.copy(timestampSeconds = nowSec - 300L)
        val health300 = GttFleetAnalyzer.assess(movingLagged300, patterns, nowMillis)
        assertFalse("Actively moving bus must NEVER show stale GPS", health300.isStaleGps)
        assertFalse("Actively moving bus must NEVER show ! warning on map", health300.showBlockedWarningOnMap)
        assertEquals(VehicleFreshness.SLOW, movingLagged300.freshness(nowMillis))
    }

    @Test
    fun scenario6_trafficLightStopShowsNoWarningWhile4MinMidRouteStuckShowsMapWarning() {
        val patterns = listOf(line12Pattern())
        // 2m30s stationary at a traffic light -> calm, NO ! on map
        val trafficLightBus = bus(
            id = "3430",
            lat = 45.0500,
            lon = 7.6500,
            tsSec = nowSec - 15L,
            source = "GTT Ufficiale",
            stationarySinceMillis = nowMillis - 150_000L,
            speedKmh = 0f,
        )
        val hLight = GttFleetAnalyzer.assess(trafficLightBus, patterns, nowMillis)
        assertFalse(hLight.isStuck)
        assertFalse(hLight.showBlockedWarningOnMap)

        // 4m30s stationary mid-route -> isStuck = true AND showBlockedWarningOnMap = true
        val blockedBus = trafficLightBus.copy(
            vehicleId = "3431",
            stationarySinceMillis = nowMillis - 270_000L,
        )
        val hBlocked = GttFleetAnalyzer.assess(blockedBus, patterns, nowMillis)
        assertTrue(hBlocked.isStuck)
        assertTrue(hBlocked.showBlockedWarningOnMap)
    }

    @Test
    fun scenario7_terminusLayoverRulesEnforce30MinCapAndDropUncorroboratedGhosts() {
        val patterns = listOf(line12Pattern())
        // 1. Corroborated GTT bus at Allason Cap for 15 min -> VISIBLE with Capolinea, NO !
        val legit15m = bus(
            id = "3429",
            lat = 45.0330,
            lon = 7.6190,
            tsSec = nowSec - 20L,
            source = "GTT Ufficiale",
            stationarySinceMillis = nowMillis - 15 * 60_000L,
            speedKmh = 0f,
        )
        val hLegit = GttFleetAnalyzer.assess(legit15m, patterns, nowMillis)
        assertTrue(hLegit.isAtTerminus)
        assertFalse(hLegit.showBlockedWarningOnMap)

        // 2. Corroborated GTT bus stuck at Allason Cap for 35 min -> HIDDEN (> 30 min cap)
        val overstay35m = legit15m.copy(
            vehicleId = "9066",
            stationarySinceMillis = nowMillis - 35 * 60_000L,
        )

        // 3. Uncorroborated MaTO-only bus at Allason Cap for 3 min while healthy GTT is online -> HIDDEN
        val uncorroboratedTerminus = bus(
            id = "9067",
            lat = 45.0330,
            lon = 7.6190,
            tsSec = nowSec - 15L,
            source = "MaTO Live",
            sources = setOf("MaTO Live"),
            stationarySinceMillis = nowMillis - 180_000L,
            speedKmh = 0f,
        )

        val surviving = GttFleetAnalyzer.filterOutConfirmedPhantomsAndDeadGhosts(
            vehicles = listOf(legit15m, overstay35m, uncorroboratedTerminus),
            routePatterns = patterns,
            nowMillis = nowMillis,
            healthyOfficialGtfsOnline = true,
        )
        assertEquals(listOf("3429"), surviving.map { it.vehicleId })
    }

    @Test
    fun scenario8_crossSourceHandoffAndTunnelExitNeverCauseFalseQuarantine() {
        val poses = HashMap<String, GttVehicleMergeEngine.VehicleKinematicPose>()
        val quarantined = mutableListOf<String>()

        // Bus observed by GTT Ufficiale at t - 40s
        val beforeTunnel = bus(
            id = "3429",
            lat = 45.0500,
            lon = 7.6500,
            tsSec = nowSec - 43L,
            source = "GTT Ufficiale",
        )
        val s1 = GttVehicleMergeEngine.evaluateKinematicAnomalies(
            vehicles = listOf(beforeTunnel),
            kinematicPoses = poses,
            nowMillis = nowMillis - 40_000L,
        ) { b, _ -> quarantined.add(b.vehicleId) }
        assertEquals(1, s1.size)

        // 40 seconds later, bus emerges 450m north on MaTO Live with only a 3s timestamp skew!
        // Because wallDtSec = 40s (450m / 40s = 40.5 km/h), this MUST be accepted immediately.
        val afterTunnel = bus(
            id = "3429",
            lat = 45.0540,
            lon = 7.6500,
            tsSec = nowSec - 40L,
            source = "MaTO Live",
        )
        val s2 = GttVehicleMergeEngine.evaluateKinematicAnomalies(
            vehicles = listOf(afterTunnel),
            kinematicPoses = poses,
            nowMillis = nowMillis,
        ) { b, _ -> quarantined.add(b.vehicleId) }

        assertEquals(1, s2.size)
        assertEquals(45.0540, s2.first().latitude, 0.00001)
        assertTrue("Cross-source tunnel exit must NEVER trigger quarantine", quarantined.isEmpty())
    }

    @Test
    fun scenario9_verifiedDropoutGraceBridgesSinglePollBlipAndExcludesGhosts() {
        val graceCache = HashMap<String, GttVehicleMergeEngine.VerifiedBusGraceEntry>()
        val liveMoving = bus(
            id = "3429",
            lat = 45.0510,
            lon = 7.6510,
            tsSec = nowSec - 10L,
            source = "GTT Ufficiale",
            lastMovedAtMillis = nowMillis - 10_000L,
        )
        val stuckBus = bus(
            id = "9050",
            lat = 45.0480,
            lon = 7.6480,
            tsSec = nowSec - 10L,
            source = "GTT Ufficiale",
            stationarySinceMillis = nowMillis - 180_000L,
            speedKmh = 0f,
        )
        val retainedGhost = bus(
            id = "9024",
            lat = 45.0490,
            lon = 7.6490,
            tsSec = nowSec - 5L,
            source = "MaTO Live",
            isMqttRetained = true,
        )

        // Poll 1: all 3 seen -> only liveMoving qualifies for grace
        val poll1 = GttVehicleMergeEngine.applyVerifiedDropoutGrace(
            freshVehicles = listOf(liveMoving, stuckBus, retainedGhost),
            graceCache = graceCache,
            focusLine = "12",
            nowMillis = nowMillis,
        )
        assertEquals(3, poll1.size)

        // Poll 2 (+25s): transient GTFS-RT dropout returns empty -> ONLY liveMoving is bridged as holdover
        val poll2 = GttVehicleMergeEngine.applyVerifiedDropoutGrace(
            freshVehicles = emptyList(),
            graceCache = graceCache,
            focusLine = "12",
            nowMillis = nowMillis + 25_000L,
        )
        assertEquals(listOf("3429"), poll2.map { it.vehicleId })
        assertTrue(poll2.first().isHoldover)

        // Poll 3 (+60s): grace window (55s) expired -> cleanly removed
        val poll3 = GttVehicleMergeEngine.applyVerifiedDropoutGrace(
            freshVehicles = emptyList(),
            graceCache = graceCache,
            focusLine = "12",
            nowMillis = nowMillis + 60_000L,
        )
        assertTrue(poll3.isEmpty())
    }

    @Test
    fun scenario10_coldStartStaleBusWithoutMovementHistoryIsProperlyFlagged() {
        val patterns = listOf(line12Pattern())
        // Cold start: no lastMovedAtMillis yet, GPS timestamp 5 minutes old -> stale & flagged on map
        val coldStale = bus(
            id = "9099",
            lat = 45.0500,
            lon = 7.6500,
            tsSec = nowSec - 300L,
            source = "GTT Ufficiale",
            lastMovedAtMillis = null,
            stationarySinceMillis = null,
        )
        val hCold = GttFleetAnalyzer.assess(coldStale, patterns, nowMillis)
        assertTrue(hCold.isStaleGps)
        assertTrue(hCold.showBlockedWarningOnMap)

        // Cold start fresh bus (10s old GPS, no movement history yet) -> healthy live!
        val coldFresh = coldStale.copy(vehicleId = "3429", timestampSeconds = nowSec - 10L)
        val hFresh = GttFleetAnalyzer.assess(coldFresh, patterns, nowMillis)
        assertFalse(hFresh.isStaleGps)
        assertFalse(hFresh.showBlockedWarningOnMap)
        assertTrue(hFresh.isReliableLive)
    }

    @Test
    fun scenario11_preRouteLoadFilterStillDropsUncorroboratedRetainedAnd30MinAbandoned() {
        val retained = bus(
            id = "9024",
            source = "MaTO Live",
            isMqttRetained = true,
        )
        val abandoned35m = bus(
            id = "9066",
            source = "GTT Ufficiale",
            stationarySinceMillis = nowMillis - 35 * 60_000L,
            speedKmh = 0f,
        )
        val healthy = bus(
            id = "3429",
            source = "GTT Ufficiale",
            tsSec = nowSec - 8L,
        )

        val surviving = GttFleetAnalyzer.filterOutConfirmedPhantomsAndDeadGhosts(
            vehicles = listOf(retained, abandoned35m, healthy),
            routePatterns = emptyList(),
            nowMillis = nowMillis,
            healthyOfficialGtfsOnline = true,
        )
        assertEquals(listOf("3429"), surviving.map { it.vehicleId })
    }

    @Test
    fun scenario12_filterStaleUncorroboratedMatoDropsSilentAndWidgetSyntheticButKeepsCorroboratedAndFallback() {
        val corroboratedSilent = bus(
            id = "3429",
            source = "MaTO Live",
            tsSec = nowSec - 90L,
            fetchedAtMillis = nowMillis - 90_000L,
        )
        val uncorroboratedSilent = bus(
            id = "9011",
            source = "MaTO Live",
            tsSec = nowSec - 90L,
            fetchedAtMillis = nowMillis - 90_000L,
        )
        val uncorroboratedFresh = bus(
            id = "9012",
            source = "MaTO Live",
            tsSec = nowSec - 15L,
            fetchedAtMillis = nowMillis - 15_000L,
        )
        val uncorroboratedNoTs = bus(
            id = "9013",
            source = "MaTO Live",
            tsSec = nowSec,
            hasExplicitTimestamp = false,
            fetchedAtMillis = nowMillis - 5_000L,
        )
        val gtt3429 = bus(
            id = "3429",
            source = "GTT Ufficiale",
            tsSec = nowSec - 10L,
        )

        // Foreground when healthy official GTFS is online: keep corroborated + fresh live MaTO, drop >75s silent MaTO-only
        val fgFiltered = GttVehicleMergeEngine.filterStaleUncorroboratedMato(
            matoVehicles = listOf(corroboratedSilent, uncorroboratedSilent, uncorroboratedFresh, uncorroboratedNoTs),
            gttVehicles = listOf(gtt3429),
            busOneVehicles = emptyList(),
            healthyOfficialGtfsOnline = true,
            nowMillis = nowMillis,
            isWidgetFastMode = false,
        )
        assertEquals(listOf("3429", "9012", "9013"), fgFiltered.map { it.vehicleId })

        // Widget fast mode when healthy official GTFS is online: ALSO drop synthetic-timestamp uncorroborated MaTO GQL
        val widgetFiltered = GttVehicleMergeEngine.filterStaleUncorroboratedMato(
            matoVehicles = listOf(corroboratedSilent, uncorroboratedSilent, uncorroboratedFresh, uncorroboratedNoTs),
            gttVehicles = listOf(gtt3429),
            busOneVehicles = emptyList(),
            healthyOfficialGtfsOnline = true,
            nowMillis = nowMillis,
            isWidgetFastMode = true,
        )
        assertEquals(listOf("3429", "9012"), widgetFiltered.map { it.vehicleId })

        // Official feeds offline/frozen: preserve ALL MaTO vehicles for seamless fallback
        val fallbackFiltered = GttVehicleMergeEngine.filterStaleUncorroboratedMato(
            matoVehicles = listOf(corroboratedSilent, uncorroboratedSilent, uncorroboratedFresh, uncorroboratedNoTs),
            gttVehicles = emptyList(),
            busOneVehicles = emptyList(),
            healthyOfficialGtfsOnline = false,
            nowMillis = nowMillis,
            isWidgetFastMode = true,
        )
        assertEquals(4, fallbackFiltered.size)
    }

    @Test
    fun scenario13_verifiedDropoutGraceExcludesVehiclesInsideGttDepots() {
        val graceCache = HashMap<String, GttVehicleMergeEngine.VerifiedBusGraceEntry>()
        val gerbido = GttFleetAnalyzer.TURIN_DEPOTS.first()
        val depotBus = bus(
            id = "7701",
            lat = gerbido.lat,
            lon = gerbido.lon,
            tsSec = nowSec - 5L,
            source = "GTT Ufficiale",
        )
        val streetBus = bus(
            id = "7702",
            lat = 45.0500,
            lon = 7.6500,
            tsSec = nowSec - 5L,
            source = "GTT Ufficiale",
        )

        GttVehicleMergeEngine.applyVerifiedDropoutGrace(
            freshVehicles = listOf(depotBus, streetBus),
            graceCache = graceCache,
            focusLine = "12",
            nowMillis = nowMillis,
        )
        val nextPoll = GttVehicleMergeEngine.applyVerifiedDropoutGrace(
            freshVehicles = emptyList(),
            graceCache = graceCache,
            focusLine = "12",
            nowMillis = nowMillis + 20_000L,
        )
        assertEquals(
            "Only non-depot active street bus should receive dropout grace",
            listOf("7702"),
            nextPoll.map { it.vehicleId },
        )
    }

    @Test
    fun scenario14_gttDepotsPolygonCollectionEmitsOnlyVerifiedOsmPolygonsNeverCircles() {
        GttFleetAnalyzer.resetDepotsForTest()
        // Fallback seeds without real OSM polygons MUST emit zero map overlays (no guessed circles!)
        val emptyPolys = buildGttDepotsPolygonCollection(GttFleetAnalyzer.DEFAULT_TURIN_DEPOTS)
        val emptyLabels = buildGttDepotsLabelCollection(GttFleetAnalyzer.DEFAULT_TURIN_DEPOTS)
        assertEquals(0, emptyPolys.features().orEmpty().size)
        assertEquals(0, emptyLabels.features().orEmpty().size)

        // Verified OSM polygons emit exact closed GeoJSON rings + centroid labels
        val verifiedSanPaolo = GttDepot(
            name = "Deposito San Paolo",
            lat = 45.0642,
            lon = 7.6328,
            radiusMeters = 105.0,
            polygon = listOf(
                GeoPoint(45.0637, 7.6321),
                GeoPoint(45.0637, 7.6335),
                GeoPoint(45.0647, 7.6335),
                GeoPoint(45.0647, 7.6321),
                GeoPoint(45.0637, 7.6321),
            ),
        )
        val polys = buildGttDepotsPolygonCollection(listOf(verifiedSanPaolo) + GttFleetAnalyzer.DEFAULT_TURIN_DEPOTS)
        val labels = buildGttDepotsLabelCollection(listOf(verifiedSanPaolo) + GttFleetAnalyzer.DEFAULT_TURIN_DEPOTS)
        val polyFeatures = polys.features().orEmpty()
        val labelFeatures = labels.features().orEmpty()

        assertEquals(1, polyFeatures.size)
        assertEquals(1, labelFeatures.size)
        val feature = polyFeatures.first()
        assertEquals("Deposito San Paolo", feature.getStringProperty("name"))
        val polygon = feature.geometry() as? Polygon
        assertTrue("Depot feature geometry must be a Polygon", polygon != null)
        val ring = polygon!!.coordinates().first()
        assertEquals(5, ring.size)
        assertEquals(ring.first().latitude(), ring.last().latitude(), 1e-9)
        assertEquals(ring.first().longitude(), ring.last().longitude(), 1e-9)
    }

    @Test
    fun scenario15_overpassDepotParserAcceptsRealGttDepotsAndRejectsResidentialOrRail() {
        val sampleOverpassJson = """
            {
              "elements": [
                {
                  "type": "way",
                  "id": 1001,
                  "tags": {
                    "landuse": "depot",
                    "depot": "bus;tram",
                    "operator": "GTT",
                    "name": "Comprensorio GTT San Paolo"
                  },
                  "geometry": [
                    {"lat": 45.0636, "lon": 7.6319},
                    {"lat": 45.0636, "lon": 7.6337},
                    {"lat": 45.0648, "lon": 7.6337},
                    {"lat": 45.0648, "lon": 7.6319},
                    {"lat": 45.0636, "lon": 7.6319}
                  ]
                },
                {
                  "type": "way",
                  "id": 1002,
                  "tags": {
                    "building": "depot",
                    "operator": "GTT",
                    "name": "Rimessa San Paolo"
                  },
                  "geometry": [
                    {"lat": 45.0639, "lon": 7.6323},
                    {"lat": 45.0639, "lon": 7.6332},
                    {"lat": 45.0645, "lon": 7.6332},
                    {"lat": 45.0645, "lon": 7.6323},
                    {"lat": 45.0639, "lon": 7.6323}
                  ]
                },
                {
                  "type": "relation",
                  "id": 2001,
                  "tags": {
                    "type": "multipolygon",
                    "landuse": "depot",
                    "operator": "Gruppo Torinese Trasporti",
                    "name": "Deposito Gerbido"
                  },
                  "members": [
                    {
                      "type": "way",
                      "role": "outer",
                      "geometry": [
                        {"lat": 45.0335, "lon": 7.5998},
                        {"lat": 45.0335, "lon": 7.6026},
                        {"lat": 45.0355, "lon": 7.6026}
                      ]
                    },
                    {
                      "type": "way",
                      "role": "outer",
                      "geometry": [
                        {"lat": 45.0355, "lon": 7.6026},
                        {"lat": 45.0355, "lon": 7.5998},
                        {"lat": 45.0335, "lon": 7.5998}
                      ]
                    }
                  ]
                },
                {
                  "type": "way",
                  "id": 3001,
                  "tags": {
                    "building": "apartments",
                    "name": "Condominio San Paolo"
                  },
                  "geometry": [
                    {"lat": 45.0593, "lon": 7.6458},
                    {"lat": 45.0593, "lon": 7.6468},
                    {"lat": 45.0603, "lon": 7.6468},
                    {"lat": 45.0603, "lon": 7.6458},
                    {"lat": 45.0593, "lon": 7.6458}
                  ]
                },
                {
                  "type": "way",
                  "id": 3002,
                  "tags": {
                    "landuse": "depot",
                    "depot": "train",
                    "operator": "Trenitalia",
                    "name": "Deposito Locomotive Torino Smistamento"
                  },
                  "geometry": [
                    {"lat": 45.0350, "lon": 7.6600},
                    {"lat": 45.0350, "lon": 7.6630},
                    {"lat": 45.0370, "lon": 7.6630},
                    {"lat": 45.0370, "lon": 7.6600},
                    {"lat": 45.0350, "lon": 7.6600}
                  ]
                },
                {
                  "type": "way",
                  "id": 3003,
                  "tags": {
                    "building": "depot",
                    "operator": "GTT",
                    "name": "Cabina GTT San Paolo"
                  },
                  "geometry": [
                    {"lat": 45.0640, "lon": 7.6300},
                    {"lat": 45.0640, "lon": 7.6301},
                    {"lat": 45.0641, "lon": 7.6301},
                    {"lat": 45.0641, "lon": 7.6300},
                    {"lat": 45.0640, "lon": 7.6300}
                  ]
                },
                {
                  "type": "way",
                  "id": 3004,
                  "tags": {
                    "landuse": "industrial",
                    "operator": "GTT",
                    "name": "Tortona"
                  },
                  "geometry": [
                    {"lat": 45.0740, "lon": 7.7040},
                    {"lat": 45.0740, "lon": 7.7054},
                    {"lat": 45.0752, "lon": 7.7054},
                    {"lat": 45.0752, "lon": 7.7040},
                    {"lat": 45.0740, "lon": 7.7040}
                  ]
                },
                {
                  "type": "way",
                  "id": 3005,
                  "tags": {
                    "landuse": "depot"
                  },
                  "geometry": [
                    {"lat": 45.0802, "lon": 7.6984},
                    {"lat": 45.0802, "lon": 7.6996},
                    {"lat": 45.0812, "lon": 7.6996},
                    {"lat": 45.0812, "lon": 7.6984},
                    {"lat": 45.0802, "lon": 7.6984}
                  ]
                },
                {
                  "type": "way",
                  "id": 3006,
                  "tags": {
                    "landuse": "industrial",
                    "name": "Stabilimento Meccanico Nizza"
                  },
                  "geometry": [
                    {"lat": 45.0440, "lon": 7.6645},
                    {"lat": 45.0440, "lon": 7.6657},
                    {"lat": 45.0450, "lon": 7.6657},
                    {"lat": 45.0450, "lon": 7.6645},
                    {"lat": 45.0440, "lon": 7.6645}
                  ]
                }
              ]
            }
        """.trimIndent()

        val parsed = GttDepotGeometryService.parseOverpassDepotsJson(sampleOverpassJson)
        assertEquals(
            "Should keep San Paolo, stitched Gerbido, GTT industrial Tortona, and unnamed landuse=depot Novara, while rejecting inner shed, apartments, Trenitalia, tiny cabin, and unrelated factory",
            setOf("Deposito San Paolo", "Deposito Gerbido", "Deposito Tortona", "Deposito Novara"),
            parsed.map { it.name }.toSet(),
        )
        val sanPaolo = parsed.first { it.name == "Deposito San Paolo" }
        assertTrue(sanPaolo.hasVerifiedPolygon)
        // Outer yard (id 1001) spans ~0.0012 deg lat (~133m), larger than inner shed (id 1002)
        assertTrue(sanPaolo.radiusMeters > 75.0)
        val gerbido = parsed.first { it.name == "Deposito Gerbido" }
        assertTrue("Stitched relation outer ring must be valid", gerbido.hasVerifiedPolygon)
    }

    @Test
    fun scenario16_pointInPolygonDepotGeofencePrecision() {
        try {
            val sanPaoloPoly = listOf(
                GeoPoint(45.0637, 7.6321),
                GeoPoint(45.0637, 7.6335),
                GeoPoint(45.0647, 7.6335),
                GeoPoint(45.0647, 7.6321),
                GeoPoint(45.0637, 7.6321),
            )
            GttFleetAnalyzer.updateResolvedDepots(
                listOf(
                    GttDepot(
                        name = "Deposito San Paolo",
                        lat = 45.0642,
                        lon = 7.6328,
                        radiusMeters = 82.0,
                        polygon = sanPaoloPoly,
                    ),
                ),
            )

            // 1. Inside depot compound -> matched
            assertEquals("Deposito San Paolo", GttFleetAnalyzer.findDepot(45.0642, 7.6328)?.name)
            // 2. 10m outside northern fence (GPS jitter) -> matched via 16m fence margin
            assertEquals("Deposito San Paolo", GttFleetAnalyzer.findDepot(45.06479, 7.6328)?.name)
            // 3. 45m outside on residential street -> MUST NOT match depot
            assertEquals(null, GttFleetAnalyzer.findDepot(45.06515, 7.6328))
            // 4. Borgo San Paolo / Via Tolmino residential neighborhood -> MUST NOT match
            assertEquals(null, GttFleetAnalyzer.findDepot(45.0598, 7.6463))
        } finally {
            GttFleetAnalyzer.resetDepotsForTest()
        }
    }

    @Test
    fun scenario15_widgetRetryJobIdIsDistinctFromPeriodicAndAlertsJobs() {
        assertNotEquals(WidgetRefreshJobScheduler.JOB_ID, WidgetRefreshJobScheduler.RETRY_JOB_ID)
        assertNotEquals(FavoriteAlertsScheduler.JOB_ID, WidgetRefreshJobScheduler.RETRY_JOB_ID)
        assertNotEquals(FavoriteAlertsScheduler.JOB_ID, WidgetRefreshJobScheduler.JOB_ID)
    }
}
