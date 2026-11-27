package com.example

import com.example.data.model.BusVehicle
import com.example.data.model.GeoPoint
import com.example.data.model.LineSummary
import com.example.data.model.RoutePattern
import com.example.data.model.TransitStop
import com.example.data.model.TripDelayInfo
import com.example.data.model.VehicleStopStatus
import com.example.data.network.AppUpdateChecker
import com.example.data.network.GttRouteGeometryService
import com.example.data.network.GttStopArrivalsParser
import com.example.data.network.GttStopArrivalsReconciler
import com.example.data.network.GttVehicleMergeEngine
import com.example.data.network.MinimalJsonParser
import com.example.data.network.MinimalJsonParser.optArray
import com.example.data.network.MinimalJsonParser.optBoolean
import com.example.data.network.MinimalJsonParser.optLong
import com.example.data.network.MinimalJsonParser.optString
import com.example.data.network.ReleaseAssetCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Safety-net unit tests covering the modularized repository/updater engines directly
 * to guarantee zero regressions across refactoring and future open-source contributions.
 */
class RefactoringRegressionSafetyTest {

    private fun testBus(
        vehicleId: String,
        lineCode: String,
        rawRouteId: String = "${lineCode}U",
        lat: Double = 45.0650,
        lon: Double = 7.6750,
        tripId: String? = null,
        timestampSeconds: Long,
        sourceName: String = "GTT Ufficiale",
    ) = BusVehicle(
        entityId = "ent_$vehicleId",
        vehicleId = vehicleId,
        label = vehicleId,
        rawRouteId = rawRouteId,
        lineCode = lineCode,
        tripId = tripId,
        directionId = 0,
        latitude = lat,
        longitude = lon,
        bearing = 90f,
        speedKmh = 25f,
        timestampSeconds = timestampSeconds,
        stopId = null,
        status = VehicleStopStatus.IN_TRANSIT_TO,
        sourceName = sourceName,
        contributingSources = setOf(sourceName),
    )

    // -------------------------------------------------------------------------
    // MinimalJsonParser
    // -------------------------------------------------------------------------

    @Test
    fun minimalJsonParser_parsesNestedObjectsArraysEscapesAndPrimitives() {
        val raw = """
            {
              "tag_name": "v2.5.0",
              "draft": false,
              "count": 42,
              "ratio": 3.5,
              "note": "Linea \"17/\"\nTorino \u2192 Nord",
              "empty": null,
              "items": [1, "due", true, {"nested": "ok"}]
            }
        """.trimIndent()

        val obj = MinimalJsonParser.parseObject(raw)
        assertNotNull(obj)
        assertEquals("v2.5.0", obj!!.optString("tag_name"))
        assertFalse(obj.optBoolean("draft", fallback = true))
        assertEquals(42L, obj.optLong("count"))
        assertEquals(3.5, obj["ratio"] as Double, 0.0001)
        assertEquals("Linea \"17/\"\nTorino → Nord", obj.optString("note"))
        assertNull(obj["empty"])

        val items = obj.optArray("items")
        assertNotNull(items)
        assertEquals(4, items!!.size)
        assertEquals(1L, items[0])
        assertEquals("due", items[1])
        assertEquals(true, items[2])
        assertEquals("ok", (items[3] as Map<*, *>).optString("nested"))
    }

    @Test
    fun minimalJsonParser_handlesBlankAndArrayInputsSafely() {
        assertNull(MinimalJsonParser.parse("   "))
        assertNull(MinimalJsonParser.parseObject("[1, 2, 3]"))
        val arr = MinimalJsonParser.parseArray("""[{"a": 1}, {"a": 2}]""")
        assertNotNull(arr)
        assertEquals(2, arr!!.size)
    }

    // -------------------------------------------------------------------------
    // GttVehicleMergeEngine
    // -------------------------------------------------------------------------

    @Test
    fun vehicleMergeEngine_prefersFresherGpsAndMergesUniqueSources() {
        val nowMs = 1_750_000_000_000L
        val nowSec = nowMs / 1000L

        val gttOld = testBus(
            vehicleId = "3045",
            lineCode = "4",
            lat = 45.0600,
            lon = 7.6700,
            timestampSeconds = nowSec - 90L,
            sourceName = "GTT Ufficiale",
        )
        val matoFresh = testBus(
            vehicleId = "3045",
            lineCode = "4",
            lat = 45.0650,
            lon = 7.6750,
            timestampSeconds = nowSec - 10L,
            sourceName = "MaTO Live",
        )

        val merged = GttVehicleMergeEngine.mergeMultiSourceVehicles(
            matoVehicles = listOf(matoFresh),
            gttVehicles = listOf(gttOld),
            busOneVehicles = emptyList(),
            nowMillis = nowMs,
        )

        assertEquals(1, merged.size)
        val winner = merged.first()
        assertEquals(45.0650, winner.latitude, 0.00001)
        assertTrue(winner.contributingSources.contains("MaTO Live"))
        assertTrue(winner.contributingSources.contains("GTT Ufficiale"))
    }

    @Test
    fun vehicleMergeEngine_mergesMetroAndM1SWithoutDuplicateBarredLines() {
        val live = listOf(
            LineSummary(lineCode = "17/", rawRouteIds = setOf("17BU"), activeVehicleCount = 5),
            LineSummary(lineCode = "4", rawRouteIds = setOf("4U"), activeVehicleCount = 12),
        )
        val staticCatalog = listOf(
            LineSummary(lineCode = "M1", rawRouteIds = setOf("M1U"), activeVehicleCount = 0),
            LineSummary(lineCode = "M1S", rawRouteIds = setOf("M1SU"), activeVehicleCount = 0),
            LineSummary(lineCode = "17B", rawRouteIds = setOf("17BU"), activeVehicleCount = 0),
            LineSummary(lineCode = "17/", rawRouteIds = setOf("17/"), activeVehicleCount = 0),
            LineSummary(lineCode = "4", rawRouteIds = setOf("4U"), activeVehicleCount = 0),
        )

        val merged = GttVehicleMergeEngine.mergeLiveSummariesWithStaticCatalog(live, staticCatalog)
        val codes = merged.map { it.lineCode }

        assertTrue("METRO must always be present", "METRO" in codes)
        assertTrue("M1S replacement shuttle must stay separate", "M1S" in codes)
        assertTrue("17B canonical barred line must stay present", "17B" in codes)
        assertFalse("17/ must be deduplicated into canonical 17B", "17/" in codes)
        assertFalse("M1 raw code must normalize into METRO", "M1" in codes)
        assertEquals(5, merged.first { it.lineCode == "17B" }.activeVehicleCount)
    }

    @Test
    fun vehicleMergeEngine_enrichesWithTripUpdatesAndFiltersExpiredOverlayCache() {
        val nowMs = 1_750_000_000_000L
        val nowSec = nowMs / 1000L
        val freshBus = testBus(
            vehicleId = "5001",
            lineCode = "55",
            tripId = "T123",
            timestampSeconds = nowSec - 15L,
            sourceName = "MaTO Live",
        )

        // Fresh snapshot within TTL is kept; expired snapshot is dropped cleanly
        val withinTtl = GttVehicleMergeEngine.filterFreshOverlayVehicles(
            rawVehicles = listOf(freshBus),
            fetchedAtMillis = nowMs - 30_000L,
            nowMillis = nowMs,
        )
        assertEquals(listOf("5001"), withinTtl.map { it.vehicleId })

        val expired = GttVehicleMergeEngine.filterFreshOverlayVehicles(
            rawVehicles = listOf(freshBus),
            fetchedAtMillis = nowMs - 300_000L,
            nowMillis = nowMs,
        )
        assertTrue(expired.isEmpty())

        val enriched = GttVehicleMergeEngine.enrichWithTripUpdates(
            vehicles = withinTtl,
            activeTripUpdates = listOf(
                TripDelayInfo(
                    tripId = "T123",
                    vehicleId = "5001",
                    routeId = "55U",
                    delaySeconds = 180,
                    nextStopId = "1234",
                    timestampSeconds = nowSec - 5L,
                ),
            ),
        )
        assertEquals(180, enriched.first().delaySeconds)
        assertEquals("1234", enriched.first().nextStopId)
    }

    // -------------------------------------------------------------------------
    // GttRouteGeometryService
    // -------------------------------------------------------------------------

    @Test
    fun routeGeometryService_decodesGooglePolylineAndStitchesReversedWays() {
        // Standard Google Encoded Polyline Algorithm reference vector:
        // (38.5, -120.2), (40.7, -120.95), (43.252, -126.453)
        val decoded = GttRouteGeometryService.decodeGooglePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, decoded.size)
        assertEquals(38.5, decoded[0].lat, 0.001)
        assertEquals(-120.2, decoded[0].lon, 0.001)
        assertEquals(43.252, decoded[2].lat, 0.001)
        assertEquals(-126.453, decoded[2].lon, 0.001)

        // Stitch two ways where the second way is reversed at the junction point (> 0.0012 deg apart)
        val p1 = GeoPoint(45.0700, 7.6800)
        val p2 = GeoPoint(45.0750, 7.6850)
        val p3 = GeoPoint(45.0800, 7.6900)
        val stitched = GttRouteGeometryService.stitchConnectedWays(
            listOf(
                listOf(p1, p2),
                listOf(p3, p2), // reversed way sharing p2
            ),
        )
        assertEquals(1, stitched.size)
        assertEquals(listOf(p1, p2, p3), stitched.first())
    }

    @Test
    fun routeGeometryService_simplifyPreservesEndpointsAndFormatsRomeDate() {
        val dense = (0..500).map { i -> GeoPoint(45.0 + i * 0.0001, 7.6 + i * 0.0001) }
        val simplified = GttRouteGeometryService.simplifySegment(dense, maxPts = 80)
        assertTrue(simplified.size in 70..90)
        assertEquals(dense.first(), simplified.first())
        assertEquals(dense.last(), simplified.last())

        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome")).apply {
            set(2026, Calendar.NOVEMBER, 27, 15, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("20261127", GttRouteGeometryService.formatRomeYyyyMmDd(cal.timeInMillis))
    }

    // -------------------------------------------------------------------------
    // GttStopArrivalsParser & GttStopArrivalsReconciler
    // -------------------------------------------------------------------------

    @Test
    fun stopArrivalsParser_parsesGttPalinaRealtimeStarAndMidnightRollover() {
        // 23:55 Europe/Rome on 2026-11-27
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome")).apply {
            set(2026, Calendar.NOVEMBER, 27, 23, 55, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMs = cal.timeInMillis
        val palinaJson = """
            [
              {"Linea": "4", "Direzione": "FALCHERA", "PassaggiRT": ["23:58*", "00:05*"], "PassaggiPR": ["00:15"]},
              {"Linea": "17/", "Direzione": "OSPEDALE", "PassaggiRT": [], "PassaggiPR": ["00:10"]}
            ]
        """.trimIndent()

        val arrivals = GttStopArrivalsParser.parseGttPalinaJson(palinaJson, nowMs)
        assertEquals(4, arrivals.size)
        val rt4 = arrivals.filter { it.lineCode == "4" && it.isRealtime }
        assertEquals(2, rt4.size)
        assertTrue("23:58 should be ~3 min from 23:55", rt4[0].minutesUntil(nowMs) in 2..4)
        assertTrue("00:05 after midnight should roll to +10 min", rt4[1].minutesUntil(nowMs) in 9..11)
        assertTrue("17/ should normalize to canonical 17B", arrivals.any { it.lineCode == "17B" && !it.isRealtime })
    }

    @Test
    fun stopArrivalsReconciler_resolvesExactDirectionForStopOnLinePatterns() {
        val stopDir0 = TransitStop("100", "100", "PORTA NUOVA", 45.062, 7.678, directionId = 0, sequence = 1)
        val stopDir1 = TransitStop("200", "200", "PORTA SUSA", 45.072, 7.666, directionId = 1, sequence = 1)
        val patterns = listOf(
            RoutePattern("p0", "Andata", directionId = 0, segments = emptyList(), stops = listOf(stopDir0)),
            RoutePattern("p1", "Ritorno", directionId = 1, segments = emptyList(), stops = listOf(stopDir1)),
        )

        assertEquals(0, GttStopArrivalsReconciler.resolveStopDirectionOnLinePatterns(stopDir0, patterns))
        assertEquals(1, GttStopArrivalsReconciler.resolveStopDirectionOnLinePatterns(stopDir1, patterns))
    }

    // -------------------------------------------------------------------------
    // AppUpdateChecker ABI edge cases
    // -------------------------------------------------------------------------

    @Test
    fun appUpdateChecker_detectsAbiTagsAndNeverSelectsIncompatibleArchitecture() {
        assertEquals("arm64-v8a", AppUpdateChecker.detectApkAbiTag("ToBus-v2.5.0-arm64-v8a-release.apk"))
        assertEquals("armeabi-v7a", AppUpdateChecker.detectApkAbiTag("ToBus-v2.5.0-armeabi-v7a.apk"))
        assertEquals("universal", AppUpdateChecker.detectApkAbiTag("ToBus-v2.5.0-universal.apk"))
        assertNull(AppUpdateChecker.detectApkAbiTag("ToBus-v2.5.0.apk"))

        val onlyX86 = listOf(
            ReleaseAssetCandidate(
                name = "ToBus-v2.5.0-x86_64.apk",
                downloadUrl = "https://example.com/x86_64.apk",
                sizeBytes = 10_000_000L,
            ),
        )
        assertNull(
            "Must never download an x86_64 APK on an arm64-v8a device",
            AppUpdateChecker.selectBestApkAsset(onlyX86, listOf("arm64-v8a", "armeabi-v7a")),
        )
    }
}
