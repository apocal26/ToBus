package com.example

import com.example.data.model.BusVehicle
import com.example.data.model.GeoPoint
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.RoutePattern
import com.example.data.model.StopArrival
import com.example.data.model.TransitStop
import com.example.data.model.VehicleStopStatus
import com.example.data.network.GttRealtimeRepository
import com.example.data.network.GttStaticRouteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StopArrivalsCalendarAndDedupTest {

    @Test
    fun staticGtfsScheduleStrictlyFiltersByCalendarAndExceptionsForSunday() {
        // Sunday 2026-11-22 at 09:04 Europe/Rome
        val rome = TimeZone.getTimeZone("Europe/Rome")
        val nowMillis = Calendar.getInstance(rome).apply {
            set(2026, Calendar.NOVEMBER, 22, 9, 4, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val zipFile = File.createTempFile("gtt_calendar_test_", ".zip")
        try {
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                fun putEntry(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }

                putEntry(
                    "calendar.txt",
                    """
                    service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                    WEEKDAY,1,1,1,1,1,0,0,20260101,20261231
                    SAT,0,0,0,0,0,1,0,20260101,20261231
                    SUN,0,0,0,0,0,0,1,20260101,20261231
                    SUN_CANCELLED,0,0,0,0,0,0,1,20260101,20261231
                    """.trimIndent(),
                )
                putEntry(
                    "calendar_dates.txt",
                    """
                    service_id,date,exception_type
                    SUN_CANCELLED,20261122,2
                    EXTRA_EVENT,20261122,1
                    """.trimIndent(),
                )
                putEntry(
                    "routes.txt",
                    """
                    route_id,route_short_name,route_long_name
                    12U,12,Linea 12
                    """.trimIndent(),
                )
                putEntry(
                    "trips.txt",
                    """
                    route_id,service_id,trip_id,trip_headsign,direction_id
                    12U,WEEKDAY,T_WD_1,Porta Nuova,1
                    12U,WEEKDAY,T_WD_2,Porta Nuova,1
                    12U,SAT,T_SAT_1,Porta Nuova,1
                    12U,SUN,T_SUN_1,Porta Nuova,1
                    12U,SUN,T_SUN_DROPOFF,Porta Nuova,1
                    12U,SUN_CANCELLED,T_CANC_1,Porta Nuova,1
                    12U,EXTRA_EVENT,T_EVT_1,Porta Nuova,1
                    """.trimIndent(),
                )
                putEntry(
                    "stop_times.txt",
                    """
                    trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type
                    T_WD_1,09:08:00,09:08:00,317,10,0
                    T_WD_2,09:14:00,09:14:00,317,10,0
                    T_SAT_1,09:18:00,09:18:00,317,10,0
                    T_SUN_1,09:28:00,09:28:00,317,10,0
                    T_SUN_DROPOFF,09:32:00,09:32:00,317,20,1
                    T_CANC_1,09:22:00,09:22:00,317,10,0
                    T_EVT_1,09:42:00,09:42:00,317,10,0
                    """.trimIndent(),
                )
            }

            val arrivals = GttStaticRouteStore.extractScheduledArrivalsFromZip(
                zipFile = zipFile,
                stopId = "317",
                preferredLine = "12",
                nowMillis = nowMillis,
            )

            val tripIds = arrivals.map { it.tripId }
            assertEquals(listOf("T_SUN_1", "T_EVT_1"), tripIds)
        } finally {
            zipFile.delete()
        }
    }

    @Test
    fun deduplicateArrivalsCollapsesDelayedRealtimeAndScheduledSameTrip() {
        val baseEpoch = 1_795_334_400L

        val scheduledMato = StopArrival(
            lineCode = "12",
            headsign = "Porta Nuova",
            arrivalEpochSeconds = baseEpoch + 600L, // 09:10 scheduled
            isRealtime = false,
            tripId = "gtt:TRIP_1234",
            sourceLabel = "Orario MATO",
        )
        val delayedRt = StopArrival(
            lineCode = "12",
            headsign = "Linea 12",
            arrivalEpochSeconds = baseEpoch + 840L, // 09:14 realtime (+4 min delay!)
            isRealtime = true,
            delaySeconds = 240,
            vehicleId = "9037",
            tripId = "TRIP_1234",
            sourceLabel = "GTT RT",
        )
        val nextScheduled = StopArrival(
            lineCode = "12",
            headsign = "Porta Nuova",
            arrivalEpochSeconds = baseEpoch + 1_800L, // 09:30 distinct later trip
            isRealtime = false,
            tripId = "gtt:TRIP_5678",
            sourceLabel = "Orario MATO",
        )

        val deduped = GttRealtimeRepository.deduplicateArrivals(
            listOf(scheduledMato, delayedRt, nextScheduled),
        )
        assertEquals(2, deduped.size)
        val merged = deduped.first()
        assertTrue(merged.isRealtime)
        assertEquals("9037", merged.vehicleId)
        assertEquals("Porta Nuova", merged.headsign)
        assertEquals(baseEpoch + 840L, merged.arrivalEpochSeconds)
        assertEquals("gtt:TRIP_5678", deduped[1].tripId)
    }

    @Test
    fun deduplicateArrivalsCollapsesSameVehicleWithinFifteenMinutes() {
        val baseEpoch = 1_795_334_400L

        val rtEntry = StopArrival(
            lineCode = "12",
            headsign = "Porta Nuova",
            arrivalEpochSeconds = baseEpoch + 120L,
            isRealtime = true,
            vehicleId = "9037",
            sourceLabel = "MATO Live",
        )
        val gpsEstimateSameVehicle = StopArrival(
            lineCode = "12",
            headsign = "Porta Nuova",
            arrivalEpochSeconds = baseEpoch + 360L,
            isRealtime = true,
            vehicleId = "9037",
            sourceLabel = "GPS Live",
        )

        val deduped = GttRealtimeRepository.deduplicateArrivals(listOf(rtEntry, gpsEstimateSameVehicle))
        assertEquals(1, deduped.size)
        assertEquals("MATO Live", deduped.first().sourceLabel)
    }

    private fun buildNorthSouthCorridorPatterns(): Pair<TransitStop, List<RoutePattern>> {
        // Straight north-bound corridor in central Turin (Corso Orbassano area, away from depots)
        // ~110.5m per 0.001 deg latitude
        val targetStop = TransitStop(
            stopId = "317",
            stopCode = "317",
            name = "Santa Rita",
            lat = 45.0500,
            lon = 7.6500,
            directionId = 1,
            sequence = 5,
        )
        val northboundStops = listOf(
            TransitStop("315", "315", "Mirafiori", 45.0420, 7.6500, directionId = 1, sequence = 1),
            TransitStop("316", "316", "Cosenza", 45.0460, 7.6500, directionId = 1, sequence = 3),
            targetStop,
            TransitStop("318", "318", "Porta Nuova", 45.0600, 7.6500, directionId = 1, sequence = 9),
        )
        val northboundPoly = listOf(
            GeoPoint(45.0400, 7.6500),
            GeoPoint(45.0450, 7.6500),
            GeoPoint(45.0500, 7.6500),
            GeoPoint(45.0550, 7.6500),
            GeoPoint(45.0600, 7.6500),
        )
        val southboundPoly = northboundPoly.reversed()
        val patterns = listOf(
            RoutePattern(
                id = "12_dir1",
                name = "Linea 12 -> Porta Nuova",
                directionId = 1,
                segments = listOf(northboundPoly),
                stops = northboundStops,
            ),
            RoutePattern(
                id = "12_dir0",
                name = "Linea 12 -> Allamano",
                directionId = 0,
                segments = listOf(southboundPoly),
                stops = emptyList(),
            ),
        )
        return targetStop to patterns
    }

    @Test
    fun estimateBusApproachToStopDistinguishesUpstreamPassedAndOppositeDirection() {
        val nowMillis = 1_795_334_400_000L
        val nowSec = nowMillis / 1000L
        val (stop, patterns) = buildNorthSouthCorridorPatterns()

        // ~165m south of stop (heading north 0°)
        val busUpstream = BusVehicle(
            entityId = "e1",
            vehicleId = "9037",
            label = "9037",
            lineCode = "12",
            rawRouteId = "12U",
            tripId = null,
            latitude = 45.0485,
            longitude = 7.6500,
            bearing = 0f,
            speedKmh = 20f,
            directionId = 1,
            timestampSeconds = nowSec - 8L,
            stopId = null,
            status = VehicleStopStatus.IN_TRANSIT_TO,
        )
        val upApproach = GttFleetAnalyzer.estimateBusApproachToStop(busUpstream, stop, patterns, nowMillis)
        assertNotNull(upApproach)
        assertFalse(upApproach!!.alreadyPassed)
        assertTrue(upApproach.remainingMeters in 130.0..200.0)
        assertTrue("Expected <= 60s ETA for bus 165m away, got ${upApproach.estimatedEtaSeconds}s", upApproach.estimatedEtaSeconds <= 60L)

        // ~220m north of stop (already passed!)
        val busPassed = busUpstream.copy(
            entityId = "e2",
            vehicleId = "9040",
            label = "9040",
            latitude = 45.0520,
        )
        val passedApproach = GttFleetAnalyzer.estimateBusApproachToStop(busPassed, stop, patterns, nowMillis)
        assertNotNull(passedApproach)
        assertTrue(passedApproach!!.alreadyPassed)

        // Heading south (180°) in opposite direction
        val busOpposite = busUpstream.copy(
            entityId = "e3",
            vehicleId = "9042",
            label = "9042",
            bearing = 180f,
            directionId = 0,
        )
        val oppApproach = GttFleetAnalyzer.estimateBusApproachToStop(busOpposite, stop, patterns, nowMillis)
        assertNull(oppApproach)
    }

    @Test
    fun reconcileLiveVehiclesFixesBrokenLongEtaDropsPassedGhostsInjectsMissedBusAndDowngradesPhantomLive() {
        val nowMillis = 1_795_334_400_000L
        val nowSec = nowMillis / 1000L
        val (stop, patterns) = buildNorthSouthCorridorPatterns()

        // Bus #9037 is 165m upstream (right next to stop!), yet feed wrongly reports 28 minutes
        val busRightNextToStop = BusVehicle(
            entityId = "e1",
            vehicleId = "9037",
            label = "9037",
            lineCode = "12",
            rawRouteId = "12U",
            tripId = null,
            latitude = 45.0485,
            longitude = 7.6500,
            bearing = 0f,
            speedKmh = 20f,
            directionId = 1,
            timestampSeconds = nowSec - 6L,
            stopId = null,
            status = VehicleStopStatus.IN_TRANSIT_TO,
        )
        // Bus #9040 already passed the stop by 220m, yet stale feed still says 2 minutes
        val busAlreadyPassed = busRightNextToStop.copy(
            entityId = "e2",
            vehicleId = "9040",
            label = "9040",
            latitude = 45.0520,
        )
        // Bus #9045 is 660m upstream and completely missed by the feed
        val busMissedByFeed = busRightNextToStop.copy(
            entityId = "e3",
            vehicleId = "9045",
            label = "9045",
            latitude = 45.0440,
        )
        val liveList = listOf(busRightNextToStop, busAlreadyPassed, busMissedByFeed)

        val rawFeed = listOf(
            StopArrival(
                lineCode = "12",
                headsign = "Porta Nuova",
                arrivalEpochSeconds = nowSec + 28L * 60L, // Broken 28 min prediction!
                isRealtime = true,
                vehicleId = "9037",
                sourceLabel = "MATO Live",
            ),
            StopArrival(
                lineCode = "12",
                headsign = "Porta Nuova",
                arrivalEpochSeconds = nowSec + 120L, // Stale ghost for already-passed bus
                isRealtime = true,
                vehicleId = "9040",
                sourceLabel = "GTT RT",
            ),
            StopArrival(
                lineCode = "12",
                headsign = "Porta Nuova",
                arrivalEpochSeconds = nowSec + 12L * 60L, // Phantom unlinked "Live" with no matching bus
                isRealtime = true,
                vehicleId = null,
                sourceLabel = "MATO Live",
            ),
        )

        val reconciled = GttRealtimeRepository.reconcileLiveVehiclesWithArrivals(
            stop = stop,
            preferredLine = "12",
            liveVehicles = liveList,
            allLivePool = liveList,
            routePatterns = patterns,
            rawArrivals = rawFeed,
            nowMillis = nowMillis,
        )

        // 1. #9037 must have its bogus 28 min ETA replaced by ~30-60s GPS Live!
        val fixed9037 = reconciled.firstOrNull { it.vehicleId == "9037" }
        assertNotNull(fixed9037)
        assertTrue(fixed9037!!.isRealtime)
        assertEquals("GPS Live", fixed9037.sourceLabel)
        assertTrue("Expected <= 60s ETA for #9037, got ${fixed9037.arrivalEpochSeconds - nowSec}s", (fixed9037.arrivalEpochSeconds - nowSec) <= 60L)

        // 2. #9040 (already passed) must be completely dropped!
        assertNull(reconciled.firstOrNull { it.vehicleId == "9040" })

        // 3. #9045 (missed by feed, 660m upstream) must be injected as GPS Live (~2-3 min)!
        val injected9045 = reconciled.firstOrNull { it.vehicleId == "9045" }
        assertNotNull(injected9045)
        assertTrue(injected9045!!.isRealtime)
        assertEquals("GPS Live", injected9045.sourceLabel)
        val eta9045 = injected9045.arrivalEpochSeconds - nowSec
        assertTrue("Expected 90..240s ETA for #9045, got ${eta9045}s", eta9045 in 90L..240L)

        // 4. Phantom unlinked "Live" 12 min away with NO matching bus must be DROPPED completely!
        val phantom = reconciled.firstOrNull { it.vehicleId == null }
        assertNull("Unlinked phantom Live arrival should be dropped, not kept as Stimato", phantom)
    }

    @Test
    fun parseGttPalinaJsonParsesRealtimeScheduledStarSuffixAndMidnightRollover() {
        // 23:52 Europe/Rome on 2026-11-22
        val rome = TimeZone.getTimeZone("Europe/Rome")
        val nowMillis = Calendar.getInstance(rome).apply {
            set(2026, Calendar.NOVEMBER, 22, 23, 52, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val nowSec = nowMillis / 1000L

        val sampleJson = """
            [
              {
                "Linea": "12U",
                "Direzione": "ALLAMANO",
                "PassaggiRT": ["23:56", "00:08"],
                "Passaggi": ["23:56", "00:25"]
              },
              {
                "Linea": "55",
                "DirezioneBreve": "VANCHIGLIA",
                "PassaggiRT": [],
                "Passaggi": ["23:59*", "00:20"]
              }
            ]
        """.trimIndent()

        val parsed = GttRealtimeRepository.parseGttPalinaJson(sampleJson, nowMillis)
        val line12 = parsed.filter { it.lineCode == "12" }.sortedBy { it.arrivalEpochSeconds }
        assertEquals(3, line12.size)
        assertTrue(line12[0].isRealtime)
        assertEquals("GTT Palina", line12[0].sourceLabel)
        assertEquals(4L * 60L, line12[0].arrivalEpochSeconds - nowSec)
        assertTrue(line12[1].isRealtime)
        assertEquals(16L * 60L, line12[1].arrivalEpochSeconds - nowSec)
        assertFalse(line12[2].isRealtime)
        assertEquals(33L * 60L, line12[2].arrivalEpochSeconds - nowSec)

        val line55 = parsed.filter { it.lineCode == "55" }.sortedBy { it.arrivalEpochSeconds }
        assertEquals(2, line55.size)
        assertTrue("Star suffix should be parsed as realtime", line55[0].isRealtime)
        assertEquals("GTT Palina", line55[0].sourceLabel)
        assertFalse(line55[1].isRealtime)
    }

    @Test
    fun reconcileDropsImpossibleScheduledAndOtherLinePhantomsAndAbsorbsDelayedBus() {
        val nowMillis = 1_795_334_400_000L
        val nowSec = nowMillis / 1000L

        // Mid-route stop ~3.3 km north of terminus (terminus floor ~570s = ~9.5 min)
        val stop = TransitStop(
            stopId = "317",
            stopCode = "317",
            name = "Santa Rita",
            lat = 45.0500,
            lon = 7.6500,
            directionId = 1,
            sequence = 12,
        )
        val northPoly = listOf(
            GeoPoint(45.0200, 7.6500), // Terminus ~3.3 km south
            GeoPoint(45.0350, 7.6500),
            GeoPoint(45.0500, 7.6500), // Stop
            GeoPoint(45.0700, 7.6500),
        )
        val patterns = listOf(
            RoutePattern("12_1", "Linea 12 -> Porta Nuova", 1, listOf(northPoly), listOf(stop)),
            RoutePattern("12_0", "Linea 12 -> Allamano", 0, listOf(northPoly.reversed()), emptyList()),
        )

        // One real live Line 12 bus ~2.2 km south (~8-9 min away), running 6 min late vs 3-min scheduled slot!
        val delayedBus = BusVehicle(
            entityId = "e1",
            vehicleId = "9037",
            label = "9037",
            lineCode = "12",
            rawRouteId = "12U",
            tripId = null,
            latitude = 45.0300,
            longitude = 7.6500,
            bearing = 0f,
            speedKmh = 18f,
            directionId = 1,
            timestampSeconds = nowSec - 5L,
            stopId = null,
            status = VehicleStopStatus.IN_TRANSIT_TO,
        )
        // Stationary bus with NO directionId and NO bearing (could be opposite direction!) — must NOT inject GPS Live!
        val ambiguousStationary = BusVehicle(
            entityId = "e2",
            vehicleId = "9099",
            label = "9099",
            lineCode = "12",
            rawRouteId = "12U",
            tripId = null,
            latitude = 45.0480,
            longitude = 7.6500,
            bearing = null,
            speedKmh = 0f,
            directionId = null,
            timestampSeconds = nowSec - 5L,
            stopId = null,
            status = VehicleStopStatus.STOPPED_AT,
        )
        // City-wide live pool (>= 25 buses) including a Line 55 bus 5.5 km away (unreachable in 4 min!)
        // and ZERO buses on Line 99 (not running today!)
        val distantLine55Bus = delayedBus.copy(
            entityId = "e55",
            vehicleId = "5501",
            label = "5501",
            lineCode = "55",
            rawRouteId = "55U",
            latitude = 45.1000, // ~5.5 km north!
            longitude = 7.6500,
            bearing = 180f,
        )
        val cityWidePool = buildList {
            add(delayedBus)
            add(ambiguousStationary)
            add(distantLine55Bus)
            for (i in 1..25) {
                add(
                    delayedBus.copy(
                        entityId = "city_$i",
                        vehicleId = "${3000 + i}",
                        label = "${3000 + i}",
                        lineCode = "4",
                        rawRouteId = "4U",
                        latitude = 45.0700 + i * 0.0005,
                    ),
                )
            }
        }

        val rawFeed = listOf(
            // Scheduled Line 12 in 3 min -> should be ABSORBED by delayedBus (not duplicated!)
            StopArrival("12", "Porta Nuova", nowSec + 3L * 60L, isRealtime = false, sourceLabel = "Orario MATO"),
            // Phantom impossible scheduled Line 12 in 1 min (below terminus floor, no second bus!) -> DROPPED!
            StopArrival("12", "Porta Nuova", nowSec + 60L, isRealtime = false, sourceLabel = "Orario MATO"),
            // Legitimate future scheduled Line 12 in 24 min (can still depart from terminus!) -> KEPT!
            StopArrival("12", "Porta Nuova", nowSec + 24L * 60L, isRealtime = false, sourceLabel = "Orario MATO"),
            // Phantom Line 55 in 4 min (only Line 55 bus is 5.5 km away!) -> DROPPED!
            StopArrival("55", "Vanchiglia", nowSec + 4L * 60L, isRealtime = false, sourceLabel = "Orario MATO"),
            // Phantom Line 99 in 8 min (ZERO Line 99 buses active in Turin!) -> DROPPED!
            StopArrival("99", "Fantasma", nowSec + 8L * 60L, isRealtime = false, sourceLabel = "Orario MATO"),
        )

        val reconciled = GttRealtimeRepository.reconcileLiveVehiclesWithArrivals(
            stop = stop,
            preferredLine = "12",
            liveVehicles = listOf(delayedBus, ambiguousStationary),
            allLivePool = cityWidePool,
            routePatterns = patterns,
            rawArrivals = rawFeed,
            nowMillis = nowMillis,
        )

        // Ambiguous stationary bus #9099 must NOT be injected!
        assertNull(reconciled.firstOrNull { it.vehicleId == "9099" })

        // Line 12 must have ONLY 2 entries: the real delayed bus #9037 (absorbed 3-min slot) + the future 24-min scheduled trip!
        val line12 = reconciled.filter { it.lineCode == "12" }.sortedBy { it.arrivalEpochSeconds }
        assertEquals("Expected only live #9037 + 24-min future trip, got $line12", 2, line12.size)
        assertEquals("9037", line12[0].vehicleId)
        assertTrue(line12[0].isRealtime)
        assertEquals(24L * 60L, line12[1].arrivalEpochSeconds - nowSec)
        assertFalse(line12[1].isRealtime)

        // Phantom Line 55 (bus 5.5 km away) and Line 99 (0 buses in Turin) must BOTH be dropped!
        assertTrue(reconciled.none { it.lineCode == "55" })
        assertTrue(reconciled.none { it.lineCode == "99" })
    }

    @Test
    fun resolveStopDirectionOnLinePatternsHandlesInvertedDirectionIdsAndAmbiguousStreets() {
        // Physical stop #317 is directionId = 1 on Line 12,
        // but the SAME physical pole is directionId = 0 on Line 55!
        val stopOnLine12 = TransitStop(
            stopId = "317",
            stopCode = "317",
            name = "Santa Rita",
            lat = 45.0500,
            lon = 7.6500,
            directionId = 1,
            sequence = 5,
        )
        val northPoly = listOf(
            GeoPoint(45.0250, 7.6500),
            GeoPoint(45.0500, 7.6500),
            GeoPoint(45.0750, 7.6500),
        )
        val line55Patterns = listOf(
            RoutePattern(
                id = "55_dir0",
                name = "Linea 55 -> Vanchiglia",
                directionId = 0, // Inverted numeric directionId vs Line 12!
                segments = listOf(northPoly),
                stops = listOf(stopOnLine12.copy(directionId = 0)),
            ),
            RoutePattern(
                id = "55_dir1",
                name = "Linea 55 -> Gerbido",
                directionId = 1,
                segments = listOf(northPoly.reversed()),
                stops = listOf(
                    TransitStop("318", "318", "Santa Rita Opp", 45.0505, 7.6506, directionId = 1, sequence = 5),
                ),
            ),
        )

        assertEquals(0, GttRealtimeRepository.resolveStopDirectionOnLinePatterns(stopOnLine12, line55Patterns))

        // On an ambiguous two-way street where neither pattern lists the stop and both overlap at the exact same coords -> null
        val ambiguousPatterns = listOf(
            RoutePattern("99_0", "Dir 0", 0, listOf(northPoly), emptyList()),
            RoutePattern("99_1", "Dir 1", 1, listOf(northPoly.reversed()), emptyList()),
        )
        assertNull(GttRealtimeRepository.resolveStopDirectionOnLinePatterns(stopOnLine12, ambiguousPatterns))
    }

    @Test
    fun crossLineOtherLine55FocusAndReconcileNeverPicksNearestOppositeDirectionBus() {
        val nowMillis = 1_795_334_400_000L
        val nowSec = nowMillis / 1000L

        // User is currently tracking Line 12 and taps stop #317 (directionId = 1 on Line 12)
        val stopOnLine12 = TransitStop(
            stopId = "317",
            stopCode = "317",
            name = "Santa Rita",
            lat = 45.0500,
            lon = 7.6500,
            directionId = 1,
            sequence = 5,
        )
        val line12NorthPoly = listOf(
            GeoPoint(45.0300, 7.6500),
            GeoPoint(45.0500, 7.6500),
            GeoPoint(45.0700, 7.6500),
        )
        val line12Patterns = listOf(
            RoutePattern("12_1", "Linea 12 -> Porta Nuova", 1, listOf(line12NorthPoly), listOf(stopOnLine12)),
            RoutePattern("12_0", "Linea 12 -> Allamano", 0, listOf(line12NorthPoly.reversed()), emptyList()),
        )

        // Line 55 serves the EXACT same stop #317 going NORTH, but its GTFS directionId is 0 (opposite number!)
        val line55Patterns = listOf(
            RoutePattern(
                id = "55_0",
                name = "Linea 55 -> Vanchiglia",
                directionId = 0,
                segments = listOf(line12NorthPoly),
                stops = listOf(stopOnLine12.copy(directionId = 0)),
            ),
            RoutePattern(
                id = "55_1",
                name = "Linea 55 -> Gerbido",
                directionId = 1,
                segments = listOf(line12NorthPoly.reversed()),
                stops = listOf(
                    TransitStop("318", "318", "Santa Rita Opp", 45.0506, 7.6506, directionId = 1, sequence = 5),
                ),
            ),
        )

        // Bus #5501 (Line 55): ONLY ~120m away from stop #317, BUT travelling SOUTH (OPPOSITE directionId = 1)!
        val bus55OppositeNearby = BusVehicle(
            entityId = "e5501",
            vehicleId = "5501",
            label = "5501",
            lineCode = "55",
            rawRouteId = "55U",
            tripId = null,
            latitude = 45.0511,
            longitude = 7.6500,
            bearing = 180f, // Heading South (opposite!)
            speedKmh = 20f,
            directionId = 1, // Line 55's opposite direction!
            timestampSeconds = nowSec - 4L,
            stopId = null,
            status = VehicleStopStatus.IN_TRANSIT_TO,
        )
        // Bus #5502 (Line 55): ~2.0 km upstream heading NORTH (CORRECT directionId = 0, ~7-8 min away)
        val bus55CorrectUpstream = BusVehicle(
            entityId = "e5502",
            vehicleId = "5502",
            label = "5502",
            lineCode = "55",
            rawRouteId = "55U",
            tripId = null,
            latitude = 45.0320,
            longitude = 7.6500,
            bearing = 0f, // Heading North (toward stop!)
            speedKmh = 18f,
            directionId = 0, // Line 55's true direction at stop #317!
            timestampSeconds = nowSec - 4L,
            stopId = null,
            status = VehicleStopStatus.IN_TRANSIT_TO,
        )

        val allLivePool = listOf(bus55OppositeNearby, bus55CorrectUpstream)

        // 1. Reconcile stop arrivals with otherLinePatterns["55"] -> links the 8-min arrival to #5502, NEVER #5501
        val rawFeed = listOf(
            StopArrival(
                lineCode = "55",
                headsign = "Vanchiglia",
                arrivalEpochSeconds = nowSec + 8L * 60L,
                isRealtime = true,
                vehicleId = null,
                sourceLabel = "GTT Palina",
            ),
        )
        val reconciled = GttRealtimeRepository.reconcileLiveVehiclesWithArrivals(
            stop = stopOnLine12,
            preferredLine = "12",
            liveVehicles = emptyList(),
            allLivePool = allLivePool,
            routePatterns = line12Patterns,
            rawArrivals = rawFeed,
            nowMillis = nowMillis,
            otherLinePatterns = mapOf("55" to line55Patterns),
        )
        val line55Arrivals = reconciled.filter { it.lineCode == "55" }
        assertEquals(1, line55Arrivals.size)
        assertEquals("5502", line55Arrivals.first().vehicleId)

        // 2. Tapping the 8-min arrival focuses #5502 (NEVER the closer opposite-direction #5501!)
        val focusedFor8Min = GttRealtimeRepository.findExactBusForArrival(
            arrival = rawFeed.first(),
            stop = stopOnLine12,
            allLivePool = allLivePool,
            targetLinePatterns = line55Patterns,
            nowMillis = nowMillis,
        )
        assertNotNull(focusedFor8Min)
        assertEquals("5502", focusedFor8Min!!.vehicleId)

        // 3. Tapping an unlinked 2-min arrival where ONLY opposite-direction #5501 is nearby MUST return null (NEVER #5501 or #5502!)
        val unlinked2Min = StopArrival(
            lineCode = "55",
            headsign = "Vanchiglia",
            arrivalEpochSeconds = nowSec + 2L * 60L,
            isRealtime = true,
            vehicleId = null,
            sourceLabel = "GTT Palina",
        )
        val focusedFor2Min = GttRealtimeRepository.findExactBusForArrival(
            arrival = unlinked2Min,
            stop = stopOnLine12,
            allLivePool = allLivePool,
            targetLinePatterns = line55Patterns,
            claimedVehicleIds = setOf("5502"),
            nowMillis = nowMillis,
        )
        assertNull("Must NEVER jump to nearby opposite-direction bus #5501!", focusedFor2Min)
    }

    @Test
    fun stopSearchIndexDisambiguatesHomonymousStopsByLineAndPalinaCode() {
        val zipFile = File.createTempFile("gtt_stop_search_test_", ".zip")
        try {
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                fun putEntry(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                putEntry(
                    "routes.txt",
                    """
                    route_id,route_short_name
                    12U,12
                    55U,55
                    4U,4
                    15U,15
                    """.trimIndent(),
                )
                putEntry(
                    "trips.txt",
                    """
                    route_id,service_id,trip_id,trip_headsign,direction_id,shape_id
                    12U,ALL,T12_0,ALLAMANO,0,S12_0
                    55U,ALL,T55_0,VANCHIGLIA,0,S55_0
                    4U,ALL,T4_0,FALCHERA,0,S4_0
                    15U,ALL,T15_0,SASSI,0,S15_0
                    """.trimIndent(),
                )
                putEntry(
                    "stop_times.txt",
                    """
                    trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type
                    T12_0,09:00:00,09:00:00,39,1,0
                    T55_0,09:00:00,09:00:00,39,1,0
                    T4_0,09:00:00,09:00:00,40,1,0
                    T15_0,09:00:00,09:00:00,40,1,0
                    """.trimIndent(),
                )
                putEntry(
                    "stops.txt",
                    """
                    stop_id,stop_code,stop_name,stop_lat,stop_lon
                    39,39,PORTA NUOVA,45.0628,7.6788
                    40,40,PORTA NUOVA,45.0632,7.6795
                    """.trimIndent(),
                )
            }

            val index = GttStaticRouteStore.extractStopSearchIndexFromZip(zipFile)
            assertEquals(2, index.size)

            val pole39 = index.first { it.stopCode == "39" }
            assertEquals(listOf("12", "55"), pole39.lines)
            assertTrue(pole39.headsigns.any { it.contains("Allamano", ignoreCase = true) })

            val pole40 = index.first { it.stopCode == "40" }
            assertEquals(listOf("4", "15"), pole40.lines)

            // Multi-token query "porta nuova 12" filters immediately to ONLY pole #39!
            val onlyLine12 = GttStaticRouteStore.searchStops(index, "porta nuova 12", preferredLine = "12")
            assertEquals(1, onlyLine12.size)
            assertEquals("39", onlyLine12.first().stopCode)

            // Searching "#40" puts pole #40 first!
            val byCode = GttStaticRouteStore.searchStops(index, "#40", preferredLine = "12")
            assertEquals(1, byCode.size)
            assertEquals("40", byCode.first().stopCode)
        } finally {
            zipFile.delete()
        }
    }
}
