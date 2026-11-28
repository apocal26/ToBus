package com.example

import com.example.data.model.BusVehicle
import com.example.data.model.GeoPoint
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.RoutePattern
import com.example.data.model.VehicleStopStatus
import com.example.data.network.GttVehicleMergeEngine
import com.example.data.network.MatoMqttLiveClient
import com.example.map.WebMercator
import com.example.notifications.FavoriteAlertsScheduler
import com.example.widget.WidgetRefreshJobScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditHardeningTest {

    private val nowMillis = 1_760_000_000_000L
    private val nowSec = nowMillis / 1000L

    private fun bus(
        id: String,
        lineCode: String = "4",
        lat: Double,
        lon: Double,
        tsSec: Long = nowSec - 5L,
        source: String = "GTT Ufficiale",
        sources: Set<String> = setOf(source),
        stationarySinceMillis: Long? = null,
        speedKmh: Float? = 25f,
    ) = BusVehicle(
        entityId = "${source}_$id",
        vehicleId = id,
        label = id,
        rawRouteId = "${lineCode}U",
        lineCode = lineCode,
        tripId = "T_$id",
        directionId = 0,
        latitude = lat,
        longitude = lon,
        bearing = 90f,
        speedKmh = speedKmh,
        timestampSeconds = tsSec,
        stopId = null,
        status = VehicleStopStatus.IN_TRANSIT_TO,
        sourceName = source,
        contributingSources = sources,
        stationarySinceMillis = stationarySinceMillis,
    )

    @Test
    fun webMercator_routeCenteredAutoFitFramesFullRouteWithoutBusPull() {
        val routeSouth = GeoPoint(45.0150, 7.6200)
        val routeMid = GeoPoint(45.0600, 7.6700)
        val routeNorth = GeoPoint(45.1050, 7.7200)
        val pattern = RoutePattern(
            id = "4_0",
            name = "FALCHERA",
            directionId = 0,
            segments = listOf(listOf(routeSouth, routeMid, routeNorth)),
        )
        // Off-route / depot bus far west should NOT pull the camera away when includeNearbyVehicles = false
        val farOffRouteBus = bus(id = "6001", lat = 45.0399, lon = 7.5600)
        val expectedMidLat = (routeSouth.lat + routeNorth.lat) / 2.0
        val expectedMidLon = (routeSouth.lon + routeNorth.lon) / 2.0

        val cam = WebMercator.computeAutoFitCamera(
            vehicles = listOf(farOffRouteBus),
            patterns = listOf(pattern),
            viewportWidthPx = 1080,
            viewportHeightPx = 1920,
            paddingFraction = 0.10,
            maxZoom = 15.1,
            topPaddingFraction = 0.16,
            bottomPaddingFraction = 0.16,
            includeNearbyVehicles = false,
        )

        assertEquals(expectedMidLat, cam.centerLat, 0.002)
        assertEquals(expectedMidLon, cam.centerLon, 0.002)

        for (pt in listOf(routeSouth, routeMid, routeNorth)) {
            val (px, py) = WebMercator.latLonToScreenOffset(pt.lat, pt.lon, cam, 1080f, 1920f)
            assertTrue("Route point clipped horizontally: px=$px", px in 40f..1040f)
            assertTrue("Route point clipped vertically: py=$py", py in 120f..1800f)
        }
    }

    @Test
    fun webMercator_logicalDpPhoneViewportProducesCorrectFullRouteZoomAndIgnoresZeroCoords() {
        // Long Turin N-S trunk line (~11.5 km) on a standard 360x800 dp MapLibre viewport
        val south = GeoPoint(45.0120, 7.6240)
        val mid = GeoPoint(45.0650, 7.6725)
        val north = GeoPoint(45.1180, 7.7210)
        val invalidZero = GeoPoint(0.0, 0.0)
        val pattern = RoutePattern(
            id = "4_0",
            name = "FALCHERA",
            directionId = 0,
            segments = listOf(listOf(south, invalidZero, mid, north)),
        )

        val cam = WebMercator.computeAutoFitCamera(
            vehicles = emptyList(),
            patterns = listOf(pattern),
            viewportWidthPx = 360,
            viewportHeightPx = 800,
            paddingFraction = 0.09,
            maxZoom = 15.0,
            topPaddingFraction = 0.19,
            bottomPaddingFraction = 0.15,
            includeNearbyVehicles = false,
        )

        // Previously with physical pixels (1080x2400), zoom erroneously spiked to ~13.6 (~3x too close).
        // In logical dp (360x800), full 11.5 km Turin route frames cleanly around WebMercator z=11.8..12.4 (mlZoom ~10.8..11.4).
        assertTrue("Expected full-route zoom in 11.5..12.6, got ${cam.zoom}", cam.zoom in 11.5..12.6)
        assertTrue("Center lat corrupted by (0,0): ${cam.centerLat}", cam.centerLat in 45.04..45.09)
        assertTrue("Center lon corrupted by (0,0): ${cam.centerLon}", cam.centerLon in 7.65..7.70)

        for (pt in listOf(south, mid, north)) {
            val (px, py) = WebMercator.latLonToScreenOffset(pt.lat, pt.lon, cam, 360f, 800f)
            assertTrue("Route point clipped X ($px dp)", px in 25f..335f)
            assertTrue("Route point clipped Y ($py dp)", py in 130f..700f)
        }
    }

    @Test
    fun mqttGuards_rejectRegressiveTimestampsAndSingleFrameTeleports() {
        val prev = bus(id = "6012", lat = 45.0650, lon = 7.6750, tsSec = nowSec - 10L, source = "MaTO Live")
        // Out-of-order older timestamp must be rejected even with different coordinates
        val older = prev.copy(latitude = 45.0660, longitude = 7.6760, timestampSeconds = nowSec - 25L)
        assertTrue(MatoMqttLiveClient.isRegressiveTimestamp(prev, older))

        // Extreme solo teleport (>300m jump within 4 seconds at >180 km/h) must NOT commit on frame 1
        val glitchJump = prev.copy(latitude = 45.0950, longitude = 7.7150, timestampSeconds = nowSec - 6L)
        assertFalse(MatoMqttLiveClient.isRegressiveTimestamp(prev, glitchJump))
        assertFalse(
            MatoMqttLiveClient.acceptsKinematicUpdate(
                prev = prev,
                candidate = glitchJump,
                pendingOutlier = null,
            ),
        )

        // Second coherent frame near the held outlier confirms legitimate catch-up after GPS gap
        val followUp = glitchJump.copy(latitude = 45.0953, longitude = 7.7152, timestampSeconds = nowSec)
        assertTrue(
            MatoMqttLiveClient.acceptsKinematicUpdate(
                prev = prev,
                candidate = followUp,
                pendingOutlier = glitchJump,
            ),
        )
    }

    @Test
    fun mergeEngine_prefersPlausibleSourceOverTeleportingFeedWithinPrecedenceWindow() {
        val vid = "6045"
        val prevPoses = mapOf(
            vid to GttVehicleMergeEngine.VehicleKinematicPose(
                lineCode = "4",
                lat = 45.0650,
                lon = 7.6750,
                timestampSeconds = nowSec - 10L,
                observedAtMillis = nowMillis - 10_000L,
            ),
        )
        // GTT Official moves realistically ~40m in 6s (~24 km/h)
        val gttPlausible = bus(
            id = vid,
            lat = 45.06535,
            lon = 7.67510,
            tsSec = nowSec - 4L,
            source = "GTT Ufficiale",
        )
        // MaTO glitches 3.5 km north 2 seconds newer (within 20s MaTO precedence window!)
        val matoTeleport = bus(
            id = vid,
            lat = 45.0970,
            lon = 7.6850,
            tsSec = nowSec - 2L,
            source = "MaTO Live",
        )

        val merged = GttVehicleMergeEngine.mergeMultiSourceVehicles(
            matoVehicles = listOf(matoTeleport),
            gttVehicles = listOf(gttPlausible),
            busOneVehicles = emptyList(),
            nowMillis = nowMillis,
            previousPoses = prevPoses,
        )

        assertEquals(1, merged.size)
        val winner = merged.first()
        assertEquals("GTT Ufficiale", winner.sourceName)
        assertEquals(45.06535, winner.latitude, 0.0001)
    }

    @Test
    fun mergeEngine_quarantinesRepeatedOrExtremeSoloWarpsAndRecoversWhenCorroborated() {
        val poses = HashMap<String, GttVehicleMergeEngine.VehicleKinematicPose>()
        val quarantined = mutableListOf<String>()
        val initial = bus(id = "9011", lat = 45.0500, lon = 7.6600, tsSec = nowSec - 20L, source = "GTT Ufficiale")

        val step1 = GttVehicleMergeEngine.evaluateKinematicAnomalies(
            vehicles = listOf(initial),
            kinematicPoses = poses,
            nowMillis = nowMillis - 15_000L,
        ) { b, _ -> quarantined.add(b.vehicleId) }
        assertEquals(1, step1.size)
        assertTrue(quarantined.isEmpty())

        // Mild jump (>140 km/h over 4s, <=650m): held at previous pose on strike 1 (not hidden yet)
        val mildSpike = bus(id = "9011", lat = 45.0538, lon = 7.6600, tsSec = nowSec - 16L, source = "GTT Ufficiale")
        val step2 = GttVehicleMergeEngine.evaluateKinematicAnomalies(
            vehicles = listOf(mildSpike),
            kinematicPoses = poses,
            nowMillis = nowMillis - 10_000L,
        ) { b, _ -> quarantined.add(b.vehicleId) }
        assertEquals(1, step2.size)
        assertEquals(45.0500, step2.first().latitude, 0.00001)
        assertTrue(quarantined.isEmpty())

        // Second impossible jump in opposite direction within 90s -> quarantined & hidden
        val secondSpike = bus(id = "9011", lat = 45.0420, lon = 7.6600, tsSec = nowSec - 12L, source = "GTT Ufficiale")
        val step3 = GttVehicleMergeEngine.evaluateKinematicAnomalies(
            vehicles = listOf(secondSpike),
            kinematicPoses = poses,
            nowMillis = nowMillis - 5_000L,
        ) { b, _ -> quarantined.add(b.vehicleId) }
        assertTrue(step3.isEmpty())
        assertEquals(listOf("9011"), quarantined)

        // Independent multi-source corroboration (MaTO + GTT) lifts quarantine immediately
        val recovered = bus(
            id = "9011",
            lat = 45.0508,
            lon = 7.6605,
            tsSec = nowSec - 1L,
            source = "MaTO Live",
            sources = setOf("MaTO Live", "GTT Ufficiale"),
        )
        val step4 = GttVehicleMergeEngine.evaluateKinematicAnomalies(
            vehicles = listOf(recovered),
            kinematicPoses = poses,
            nowMillis = nowMillis,
        ) { b, _ -> quarantined.add(b.vehicleId) }
        assertEquals(1, step4.size)
    }

    @Test
    fun fleetAnalyzer_filtersMidRouteDeadGhostsWhilePreservingTerminusAndMultiSourceBuses() {
        val pattern = RoutePattern(
            id = "4_0",
            name = "CAP. D'ARCO",
            directionId = 0,
            segments = listOf(
                listOf(
                    GeoPoint(45.0200, 7.6200), // South terminus
                    GeoPoint(45.0500, 7.6500), // Mid route
                    GeoPoint(45.0900, 7.6900), // North terminus
                ),
            ),
        )
        // Frozen mid-route for 9 minutes (not at terminus) -> confirmed dead ghost
        val frozenMidRoute = bus(
            id = "3010",
            lat = 45.0500,
            lon = 7.6500,
            tsSec = nowSec - 30L,
            source = "GTT Ufficiale",
            stationarySinceMillis = nowMillis - 540_000L,
            speedKmh = 0f,
        )
        // Stationary >7 min with stale GPS (>3 min old) mid-route -> confirmed dead ghost
        val staleMidRoute = bus(
            id = "3011",
            lat = 45.0510,
            lon = 7.6510,
            tsSec = nowSec - 240L,
            source = "GTT Ufficiale",
            stationarySinceMillis = nowMillis - 430_000L,
            speedKmh = 0f,
        )
        // Stationary AT terminus for 9 minutes with fresh GPS -> legitimate layover, MUST be preserved
        val terminusLayover = bus(
            id = "3012",
            lat = 45.0900,
            lon = 7.6900,
            tsSec = nowSec - 15L,
            source = "GTT Ufficiale",
            stationarySinceMillis = nowMillis - 540_000L,
            speedKmh = 0f,
        )
        // Active multi-source bus mid-route -> MUST be preserved
        val activeMultiSource = bus(
            id = "3013",
            lat = 45.0520,
            lon = 7.6520,
            tsSec = nowSec - 5L,
            source = "MaTO Live",
            sources = setOf("MaTO Live", "GTT Ufficiale"),
        )
        // Deeply dead GPS (>10 min old) mid-route even without stationary tracker -> confirmed dead ghost
        val deadHeartbeatMidRoute = bus(
            id = "3014",
            lat = 45.0530,
            lon = 7.6530,
            tsSec = nowSec - 660L,
            source = "GTT Ufficiale",
            stationarySinceMillis = null,
        )

        val surviving = GttFleetAnalyzer.filterOutConfirmedPhantomsAndDeadGhosts(
            vehicles = listOf(
                frozenMidRoute,
                staleMidRoute,
                terminusLayover,
                activeMultiSource,
                deadHeartbeatMidRoute,
            ),
            routePatterns = listOf(pattern),
            nowMillis = nowMillis,
        )

        val ids = surviving.map { it.vehicleId }.toSet()
        assertFalse(ids.contains("3010"))
        assertFalse(ids.contains("3011"))
        assertFalse(ids.contains("3014"))
        assertTrue(ids.contains("3012"))
        assertTrue(ids.contains("3013"))
    }

    @Test
    fun backgroundSchedulers_useDistinctJobIds() {
        assertNotEquals(FavoriteAlertsScheduler.JOB_ID, WidgetRefreshJobScheduler.JOB_ID)
    }
}
