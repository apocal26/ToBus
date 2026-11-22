package com.example

import com.example.data.model.AlertTimeRange
import com.example.data.model.BusVehicle
import com.example.data.model.GeoPoint
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.RoutePattern
import com.example.data.model.ServiceAlertInfo
import com.example.data.model.TransitStop
import com.example.data.model.VehicleStopStatus
import com.example.data.model.shortTurinPlaceName
import com.example.map.WebMercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GttFleetAnalyzerTest {

    private fun sampleBus(
        id: String,
        lat: Double,
        lon: Double,
        timestampSeconds: Long,
        stationarySinceMillis: Long? = null,
    ) = BusVehicle(
        entityId = "ent_$id",
        vehicleId = id,
        label = id,
        rawRouteId = "12U",
        lineCode = "12",
        tripId = "trip_$id",
        directionId = 0,
        latitude = lat,
        longitude = lon,
        bearing = 90f,
        speedKmh = 20f,
        timestampSeconds = timestampSeconds,
        stopId = null,
        status = VehicleStopStatus.IN_TRANSIT_TO,
        stationarySinceMillis = stationarySinceMillis,
    )

    @Test
    fun detectsGerbidoDepotVehicle() {
        val now = 1_700_000_000_000L
        val bus = sampleBus("2411", 45.0399, 7.6020, (now / 1000L) - 10L)
        val health = GttFleetAnalyzer.assess(bus, emptyList(), now)
        assertTrue(health.isInDepot)
        assertEquals("Deposito Gerbido", health.depotName)
        assertFalse(health.isReliableLive)
        assertNotNull(health.warningMessage())
    }

    @Test
    fun detectsStaleGpsAndStuckVehicle() {
        val now = 1_700_000_000_000L
        val staleBus = sampleBus("9010", 45.0703, 7.6869, (now / 1000L) - 240L)
        val staleHealth = GttFleetAnalyzer.assess(staleBus, emptyList(), now)
        assertTrue(staleHealth.isStaleGps)
        assertFalse(staleHealth.isInDepot)
        assertFalse(staleHealth.isReliableLive)

        val stuckBus = sampleBus(
            id = "9014",
            lat = 45.0703,
            lon = 7.6869,
            timestampSeconds = (now / 1000L) - 15L,
            stationarySinceMillis = now - 300_000L,
        )
        val stuckHealth = GttFleetAnalyzer.assess(stuckBus, emptyList(), now)
        assertTrue(stuckHealth.isStuck)
        assertFalse(stuckHealth.isReliableLive)
    }

    @Test
    fun decodesCommonTurinFleetSeries() {
        assertEquals("Bus elettrico BYD", GttFleetAnalyzer.decodeFleetModel("2411"))
        assertEquals("Tram Alstom Cityway", GttFleetAnalyzer.decodeFleetModel("6012"))
        assertEquals("Bus elettrico GTT", GttFleetAnalyzer.decodeFleetModel("9027"))
        assertNull(GttFleetAnalyzer.decodeFleetModel("ABC"))
    }

    @Test
    fun autoFitFramesFullRouteWithoutClipping() {
        val pattern = RoutePattern(
            id = "12_0",
            name = "ALLAMANO",
            directionId = 0,
            segments = listOf(
                listOf(
                    GeoPoint(45.020, 7.610),
                    GeoPoint(45.070, 7.685),
                    GeoPoint(45.105, 7.720),
                ),
            ),
        )
        val singleBus = sampleBus("9027", 45.070, 7.685, 1_700_000_000L)
        val cam = WebMercator.computeAutoFitCamera(
            vehicles = listOf(singleBus),
            patterns = listOf(pattern),
            viewportWidthPx = 640,
            viewportHeightPx = 360,
            paddingFraction = 0.16,
            maxZoom = 14.3,
        )
        // Verify all 3 route endpoints project strictly inside the 640x360 viewport
        for (pt in pattern.segments.first()) {
            val (px, py) = WebMercator.latLonToScreenOffset(pt.lat, pt.lon, cam, 640f, 360f)
            assertTrue("Point $pt clipped horizontally: px=$px", px in 0f..640f)
            assertTrue("Point $pt clipped vertically: py=$py", py in 0f..360f)
        }
    }

    @Test
    fun filtersAlertsToCurrentAndNextSevenDaysOnly() {
        // 2026-05-15 12:00:00 UTC = 1778846400
        val nowSec = 1_778_846_400L
        val day = 86_400L

        val activeAlert = ServiceAlertInfo(
            id = "active",
            headerText = "Deviazione linea 12",
            descriptionText = "Lavori in corso",
            affectedLines = setOf("12"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = nowSec - day, endEpochSeconds = nowSec + 2 * day)),
        )
        assertTrue(activeAlert.isCurrentOrUpcoming(nowSec))
        assertTrue(activeAlert.isActiveNow(nowSec))

        val upcomingWithinWeek = ServiceAlertInfo(
            id = "soon",
            headerText = "Evento domenica",
            descriptionText = "Chiusura centro",
            affectedLines = setOf("12"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = nowSec + 3 * day, endEpochSeconds = nowSec + 4 * day)),
        )
        assertTrue(upcomingWithinWeek.isCurrentOrUpcoming(nowSec))
        assertFalse(upcomingWithinWeek.isActiveNow(nowSec))

        val tooFarFuture = ServiceAlertInfo(
            id = "far",
            headerText = "Cantiere estivo",
            descriptionText = "Tra un mese",
            affectedLines = setOf("12"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = nowSec + 14 * day, endEpochSeconds = nowSec + 20 * day)),
        )
        assertFalse(tooFarFuture.isCurrentOrUpcoming(nowSec))

        val expiredAlert = ServiceAlertInfo(
            id = "expired",
            headerText = "Deviazione vecchia",
            descriptionText = "Terminata",
            affectedLines = setOf("12"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = nowSec - 10 * day, endEpochSeconds = nowSec - 1 * day)),
        )
        assertFalse(expiredAlert.isCurrentOrUpcoming(nowSec))

        val ancientOpenEnded = ServiceAlertInfo(
            id = "ancient",
            headerText = "Modifica fermata 2022",
            descriptionText = "Dal 10/03/2022 spostamento fermata",
            affectedLines = setOf("12"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = nowSec - 200 * day, endEpochSeconds = null)),
        )
        assertFalse(ancientOpenEnded.isCurrentOrUpcoming(nowSec))

        val noPeriodPastText = ServiceAlertInfo(
            id = "past_text",
            headerText = "Avviso del 12/01/2024",
            descriptionText = "Lavori conclusi il 15/01/2024",
            affectedLines = setOf("12"),
            activePeriods = emptyList(),
        )
        assertFalse(noPeriodPastText.isCurrentOrUpcoming(nowSec))

        // Real GTT case from user screenshot: in November 2026, a July notice with end=31/12 & "sino a nuove comunicazioni"
        // 2026-11-21 12:00:00 UTC = 1795262400, 2026-07-10 = 1783641600, 2026-12-31 = 1798761599
        val novNowSec = 1_795_262_400L
        val julyStartSec = 1_783_641_600L
        val dec31EndSec = 1_798_761_599L
        val gttJulyStanding = ServiceAlertInfo(
            id = "s45_july",
            headerText = "Linea S45 marrone deviata in direzione Chieri",
            descriptionText = "Da venerdi' 10 luglio e sino a nuove comunicazioni. Causa lavori stradali.",
            affectedLines = setOf("S45"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = julyStartSec, endEpochSeconds = dec31EndSec)),
        )
        assertFalse(gttJulyStanding.isCurrentOrUpcoming(novNowSec))

        val gttJulyNoPeriod = ServiceAlertInfo(
            id = "14_july_no_period",
            headerText = "Linea 14 ripristino percorso e capolinea",
            descriptionText = "Da martedi' 7 luglio e sino a nuove comunicazioni",
            affectedLines = setOf("14"),
            activePeriods = emptyList(),
        )
        assertFalse(gttJulyNoPeriod.isCurrentOrUpcoming(novNowSec))

        // Immediate temporary match/event boost happening tomorrow in November -> MUST BE KEPT
        val matchBoost = ServiceAlertInfo(
            id = "juve_match",
            headerText = "Potenziamento linea 9/ per partita allo Stadium",
            descriptionText = "Domenica 22 novembre servizio potenziato prima e dopo la partita.",
            affectedLines = setOf("9/"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = novNowSec + day, endEpochSeconds = novNowSec + 2 * day)),
        )
        assertTrue(matchBoost.isCurrentOrUpcoming(novNowSec))
    }

    @Test
    fun shortensTurinPlaceNamesCleanly() {
        assertEquals("Mirafiori Nord", shortTurinPlaceName("MIRAFIORI NORD, VIA ALLASON"))
        assertEquals("Porta Nuova", shortTurinPlaceName("PORTA NUOVA, C. VITTORIO EMANUELE II"))
        assertEquals("Allamano", shortTurinPlaceName("Cap. ALLAMANO"))
        assertEquals("P.za Castello", shortTurinPlaceName("P.ZA CASTELLO"))
        assertEquals("Politecnico", shortTurinPlaceName("Fermata 3284 - POLITECNICO"))
        assertEquals("Santa Rita", shortTurinPlaceName("Fermata 317 - SANTA RITA"))
    }

    @Test
    fun squareWidgetSafeAreaNeverClipsRouteBehindPills() {
        val pattern = RoutePattern(
            id = "12_0",
            name = "MIRAFIORI",
            directionId = 0,
            segments = listOf(
                listOf(
                    GeoPoint(45.018, 7.608),
                    GeoPoint(45.055, 7.655),
                    GeoPoint(45.098, 7.712),
                ),
            ),
        )
        val sizePx = 600
        val cam = WebMercator.computeAutoFitCamera(
            vehicles = emptyList(),
            patterns = listOf(pattern),
            viewportWidthPx = sizePx,
            viewportHeightPx = sizePx,
            paddingFraction = 0.18,
            maxZoom = 13.6,
            topPaddingFraction = 0.27,
            bottomPaddingFraction = 0.19,
        )
        for (pt in pattern.segments.first()) {
            val (px, py) = WebMercator.latLonToScreenOffset(pt.lat, pt.lon, cam, sizePx.toFloat(), sizePx.toFloat())
            assertTrue("X out of safe box: $px", px in (sizePx * 0.17f)..(sizePx * 0.83f))
            assertTrue("Y out of safe box: $py", py in (sizePx * 0.26f)..(sizePx * 0.82f))
        }
    }

    @Test
    fun resolvesMissingAndInvertedBusDirectionFromRouteGeometry() {
        // Two-way Turin corridor: Dir 0 goes North-East (bearing ~37°), Dir 1 goes South-West (bearing ~217°)
        val dir0NorthEast = RoutePattern(
            id = "12_0",
            name = "PORTA NUOVA",
            directionId = 0,
            segments = listOf(
                listOf(
                    GeoPoint(45.0450, 7.6450),
                    GeoPoint(45.0550, 7.6550),
                    GeoPoint(45.0650, 7.6650),
                ),
            ),
        )
        val dir1SouthWest = RoutePattern(
            id = "12_1",
            name = "MIRAFIORI NORD",
            directionId = 1,
            segments = listOf(
                listOf(
                    GeoPoint(45.0650, 7.6652),
                    GeoPoint(45.0550, 7.6552),
                    GeoPoint(45.0450, 7.6452),
                ),
            ),
        )
        val patterns = listOf(dir0NorthEast, dir1SouthWest)

        // 1. Bus #2411 moving SW with missing directionId=null -> must resolve to Cyan (1)
        val nullDirSwBus = sampleBus("2411", 45.0550, 7.6551, 1_700_000_000L).copy(
            directionId = null,
            bearing = 216f,
        )
        assertEquals(1, GttFleetAnalyzer.resolveDirectionId(nullDirSwBus, patterns))

        // 2. Bus moving NE with missing directionId=null -> must resolve to Gold (0)
        val nullDirNeBus = sampleBus("9014", 45.0550, 7.6551, 1_700_000_000L).copy(
            directionId = null,
            bearing = 36f,
        )
        assertEquals(0, GttFleetAnalyzer.resolveDirectionId(nullDirNeBus, patterns))

        // 3. Bus with stale/inverted directionId=0 while physically heading SW -> must correct to Cyan (1)
        val invertedBus = sampleBus("3312", 45.0550, 7.6551, 1_700_000_000L).copy(
            directionId = 0,
            bearing = 218f,
        )
        assertEquals(1, GttFleetAnalyzer.resolveDirectionId(invertedBus, patterns))
    }

    @Test
    fun findsCurrentStopOnlyWhenBusIsPhysicallyAtStop() {
        val originStop = TransitStop(
            stopId = "100",
            stopCode = "100",
            name = "VITTORIO EMANUELE II",
            lat = 45.0630,
            lon = 7.6780,
            directionId = 1,
            sequence = 0,
        )
        val largoOrbassano = TransitStop(
            stopId = "317",
            stopCode = "317",
            name = "LARGO ORBASSANO",
            lat = 45.0510,
            lon = 7.6510,
            directionId = 1,
            sequence = 5,
        )
        val pattern = RoutePattern(
            id = "12_1",
            name = "MIRAFIORI NORD",
            directionId = 1,
            segments = listOf(listOf(GeoPoint(45.0630, 7.6780), GeoPoint(45.0510, 7.6510))),
            stops = listOf(originStop, largoOrbassano),
        )

        // Bus physically right at Largo Orbassano (~30m away), even if feed keeps distant origin in nextStopId
        val busAtStop = sampleBus("2411", 45.0512, 7.6512, 1_700_000_000L).copy(
            directionId = 1,
            stopId = null,
            nextStopId = "100",
        )
        val found = GttFleetAnalyzer.findCurrentStop(busAtStop, listOf(pattern), resolvedDirectionId = 1)
        assertNotNull(found)
        assertEquals("LARGO ORBASSANO", found?.name)

        // Bus mid-route (~500m from every stop) with stale departure stopId="100" -> MUST return null!
        val busMidRoute = sampleBus("2411", 45.0565, 7.6635, 1_700_000_000L).copy(
            directionId = 1,
            stopId = "100",
            nextStopId = "100",
        )
        assertNull(GttFleetAnalyzer.findCurrentStop(busMidRoute, listOf(pattern), resolvedDirectionId = 1))
    }
}

