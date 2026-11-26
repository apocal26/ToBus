package com.example

import com.example.data.model.BusVehicle
import com.example.data.model.GeoPoint
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.RoutePattern
import com.example.data.model.VehicleStopStatus
import com.example.data.network.AppUpdateChecker
import com.example.data.network.GtfsRtProtobufParser
import com.example.data.network.GttRealtimeRepository
import com.example.data.network.GttStaticRouteStore
import com.example.data.network.MatoMqttLiveClient
import com.example.data.network.SourceHealthTracker
import com.example.data.network.SourceMotionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MatoLiveAndMergeTest {

    private val nowMillis = 1_760_000_000_000L
    private val nowSec = nowMillis / 1000L

    @Test
    fun mqttEncoderAndFrameAccumulatorHandleCoalescedAndFragmentedPackets() {
        val connect = MatoMqttLiveClient.encodeConnectPacket("tobus_test123", keepAliveSec = 45)
        assertEquals(0x10, connect[0].toInt() and 0xFF)
        assertTrue(String(connect, StandardCharsets.UTF_8).contains("MQTT"))
        assertTrue(String(connect, StandardCharsets.UTF_8).contains("tobus_test123"))

        val subscribe = MatoMqttLiveClient.encodeSubscribePacket(7, listOf("#", "/12U/#"))
        assertEquals(0x82, subscribe[0].toInt() and 0xFF)

        // Build coalesced CONNACK + PUBLISH(QoS 0) and split across two chunks mid-payload
        val connAck = byteArrayOf(0x20, 0x02, 0x00, 0x00)
        val publish = buildPublishPacket(
            topic = "/12U/1/9037",
            payload = """{"latitude":45.0361,"longitude":7.6285,"heading":78,"speed":24}""",
        )
        val combined = connAck + publish
        val chunk1 = combined.copyOfRange(0, 12)
        val chunk2 = combined.copyOfRange(12, combined.size)

        val acc = MatoMqttLiveClient.MqttFrameAccumulator()
        val firstBatch = acc.appendAndDecode(chunk1)
        assertEquals(1, firstBatch.size)
        assertEquals(MatoMqttLiveClient.MqttPacket.ConnAck(0), firstBatch[0])

        val secondBatch = acc.appendAndDecode(chunk2)
        assertEquals(1, secondBatch.size)
        val pub = secondBatch[0] as MatoMqttLiveClient.MqttPacket.Publish
        assertEquals("/12U/1/9037", pub.topic)

        val vehicles = MatoMqttLiveClient.parseMatoMessage(pub.topic, pub.payload, nowMillis)
        assertEquals(1, vehicles.size)
        val bus = vehicles[0]
        assertEquals("9037", bus.vehicleId)
        assertEquals("12", bus.lineCode)
        assertEquals(1, bus.directionId)
        assertEquals("MaTO Live", bus.sourceName)
        assertEquals(45.0361, bus.latitude, 0.0001)
        assertEquals(7.6285, bus.longitude, 0.0001)
        assertEquals(78f, bus.bearing ?: -1f, 0.01f)
    }

    @Test
    fun parseMatoMessageSupports5tFlatJsonAndDigitransitNestedVp() {
        val flatJson = """
            {
              "line": "12U",
              "veh": "9042",
              "latitude": 45.0332,
              "longitude": 7.6194,
              "heading": 112.5,
              "speed": 31.0,
              "direct": 0,
              "tripId": "gtt:123456",
              "nextStop": "gtt:2514",
              "timestamp": $nowSec
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val flatParsed = MatoMqttLiveClient.parseMatoMessage("/12U/0/9042", flatJson, nowMillis)
        assertEquals(1, flatParsed.size)
        assertEquals("9042", flatParsed[0].vehicleId)
        assertEquals("12", flatParsed[0].lineCode)
        assertEquals(0, flatParsed[0].directionId)
        assertEquals("123456", flatParsed[0].tripId)
        assertEquals("2514", flatParsed[0].stopId)

        val hfpJson = """
            {
              "VP": {
                "lat": 45.0610,
                "long": 7.6690,
                "hdg": 215,
                "spd": 10.0,
                "tsi": $nowSec
              }
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val hfpTopic = "/gtfsrt/vp/torino/gtt/bus/12U/1/Allamano/T99/317/09:00/9088"
        val hfpParsed = MatoMqttLiveClient.parseMatoMessage(hfpTopic, hfpJson, nowMillis)
        assertEquals(1, hfpParsed.size)
        assertEquals("9088", hfpParsed[0].vehicleId)
        assertEquals("12", hfpParsed[0].lineCode)
        assertEquals(1, hfpParsed[0].directionId)
        assertEquals("T99", hfpParsed[0].tripId)
        assertEquals("317", hfpParsed[0].stopId)
        assertEquals(36.0f, hfpParsed[0].speedKmh ?: -1f, 0.05f)
    }

    @Test
    fun mergeMultiSourceVehiclesEliminatesCrossLineGhostAndPreservesMatoBus() {
        // #9523 switched from Line 12 to Line 14: GTT still reports stale Line 12, MaTO reports fresh Line 14
        val gttGhost12 = sampleBus(
            vehicleId = "9523",
            lineCode = "12",
            lat = 45.0338,
            lon = 7.6512,
            tsSec = nowSec - 45,
            source = "GTT Ufficiale",
            tripId = "OLD_12_TRIP",
            stopId = "OLD_12_STOP",
            directionId = 0,
        )
        val matoReal14 = sampleBus(
            vehicleId = "9523",
            lineCode = "14",
            lat = 45.0355,
            lon = 7.6520,
            tsSec = nowSec - 5,
            source = "MaTO Live",
            tripId = null,
            stopId = null,
            directionId = null,
        )
        // Active Mirafiori Nord Line 12 bus present ONLY in MaTO Live (missed by GTT GTFS-RT)
        val matoMirafiori12 = sampleBus(
            vehicleId = "9088",
            lineCode = "12",
            lat = 45.0345,
            lon = 7.6240,
            tsSec = nowSec - 3,
            source = "MaTO Live",
            directionId = 0,
        )
        // Zombie GTT bus with 8-minute-old GPS should be dropped
        val zombieBus = sampleBus(
            vehicleId = "9999",
            lineCode = "12",
            lat = 45.0400,
            lon = 7.6400,
            tsSec = nowSec - 480,
            source = "GTT Ufficiale",
        )

        val merged = GttRealtimeRepository.mergeMultiSourceVehicles(
            matoVehicles = listOf(matoReal14, matoMirafiori12),
            gttVehicles = listOf(gttGhost12, zombieBus),
            busOneVehicles = emptyList(),
            nowMillis = nowMillis,
        )

        // #9523 must appear ONLY ONCE, on Line 14, with NO leaked tripId/stopId/directionId from Line 12
        val bus9523Matches = merged.filter { it.vehicleId == "9523" }
        assertEquals(1, bus9523Matches.size)
        val bus9523 = bus9523Matches[0]
        assertEquals("14", bus9523.lineCode)
        assertNull(bus9523.tripId)
        assertNull(bus9523.stopId)
        assertNull(bus9523.directionId)

        // Active Mirafiori Nord Line 12 bus must be present
        val mirafiori = merged.firstOrNull { it.vehicleId == "9088" }
        assertNotNull(mirafiori)
        assertEquals("12", mirafiori?.lineCode)

        // Zombie 8-min-old GTT vehicle must be dropped
        assertNull(merged.firstOrNull { it.vehicleId == "9999" })
    }

    @Test
    fun mergeMultiSourceVehiclesPrioritizesMatoWithin20sAndMergesSameLineMetadata() {
        val matoBus = sampleBus(
            vehicleId = "9037",
            lineCode = "12",
            lat = 45.0601,
            lon = 7.6682,
            tsSec = nowSec - 12,
            source = "MaTO Live",
            tripId = null,
            directionId = null,
        )
        val gttBus = sampleBus(
            vehicleId = "9037",
            lineCode = "12",
            lat = 45.0590,
            lon = 7.6675,
            tsSec = nowSec - 5, // Only 7s newer than MaTO -> within 20s MaTO precedence window
            source = "GTT Ufficiale",
            tripId = "TRIP_12_A",
            directionId = 1,
        )

        val merged = GttRealtimeRepository.mergeMultiSourceVehicles(
            matoVehicles = listOf(matoBus),
            gttVehicles = listOf(gttBus),
            busOneVehicles = emptyList(),
            nowMillis = nowMillis,
        )

        assertEquals(1, merged.size)
        val winner = merged[0]
        assertEquals("MaTO Live", winner.sourceName)
        assertEquals(45.0601, winner.latitude, 0.0001)
        // Same line -> safely enriched with GTT tripId & directionId
        assertEquals("TRIP_12_A", winner.tripId)
        assertEquals(1, winner.directionId)
        assertTrue(winner.contributingSources.contains("MaTO Live"))
        assertTrue(winner.contributingSources.contains("GTT Ufficiale"))
    }

    @Test
    fun fleetAnalyzerFlagsOffRouteBusInFiladelfiaAsNotReliableLive() {
        val line12Pattern = RoutePattern(
            id = "12_0",
            name = "12 → Porta Nuova",
            directionId = 0,
            segments = listOf(
                listOf(
                    GeoPoint(45.0330, 7.6190), // Allamano
                    GeoPoint(45.0320, 7.6380), // Mirafiori Nord / Corso Orbassano
                    GeoPoint(45.0480, 7.6510), // Santa Rita
                    GeoPoint(45.0620, 7.6760), // Porta Nuova
                ),
            ),
            stops = emptyList(),
        )

        // Bus ~1.3 km east of Corso Orbassano in Filadelfia (deadheading / wrong route)
        val offRouteFiladelfia = sampleBus(
            vehicleId = "9523",
            lineCode = "12",
            lat = 45.0315,
            lon = 7.6555,
            tsSec = nowSec - 10,
            source = "GTT Ufficiale",
        )
        val onRouteMirafiori = sampleBus(
            vehicleId = "9042",
            lineCode = "12",
            lat = 45.0322,
            lon = 7.6375,
            tsSec = nowSec - 4,
            source = "MaTO Live",
        )

        val offHealth = GttFleetAnalyzer.assess(offRouteFiladelfia, listOf(line12Pattern), nowMillis)
        assertTrue(offHealth.isOffRoute)
        assertFalse(offHealth.isReliableLive)
        assertTrue(offHealth.isInactiveOrSuspicious)

        val onHealth = GttFleetAnalyzer.assess(onRouteMirafiori, listOf(line12Pattern), nowMillis)
        assertFalse(onHealth.isOffRoute)
        assertTrue(onHealth.isReliableLive)
    }

    @Test
    fun normalizeGttLineCodeStripsFestivoAndUrbanSuffixesWithoutBreakingNamedLines() {
        assertEquals("12", GtfsRtProtobufParser.normalizeGttLineCode("gtt:12U"))
        assertEquals("12", GtfsRtProtobufParser.normalizeGttLineCode("12F"))
        assertEquals("12", GtfsRtProtobufParser.normalizeGttLineCode("12UF"))
        assertEquals("12", GtfsRtProtobufParser.normalizeGttLineCode("gtt:12F"))
        assertEquals("BLU", GtfsRtProtobufParser.normalizeGttLineCode("BLU"))
        assertEquals("5B", GtfsRtProtobufParser.normalizeGttLineCode("5B"))
        assertEquals("10N", GtfsRtProtobufParser.normalizeGttLineCode("10N"))
        assertEquals("16CS", GtfsRtProtobufParser.normalizeGttLineCode("16CS"))
    }

    @Test
    fun mqtt31ConnectAndAclSafeSubscriptionGroupsAndMultiFormatPayloads() {
        val connect31 = MatoMqttLiveClient.encodeConnectPacket("tobus_v31", keepAliveSec = 45, useMqtt31 = true)
        assertEquals(0x10, connect31[0].toInt() and 0xFF)
        val connect31Str = String(connect31, StandardCharsets.UTF_8)
        assertTrue(connect31Str.contains("MQIsdp"))
        val protoIdx = connect31Str.indexOf("MQIsdp") + "MQIsdp".length
        assertEquals(0x03, connect31[protoIdx].toInt() and 0xFF)

        val groups = MatoMqttLiveClient.buildLineSubscriptionGroups("12")
        val allTopics = groups.flatten()
        assertFalse("Must never send raw root # initially", allTopics.contains("#"))
        assertFalse("Must never send raw /# initially", allTopics.contains("/#"))
        assertTrue(allTopics.contains("/12U/#"))
        assertTrue(allTopics.contains("12U/#"))
        assertTrue(allTopics.contains("/12F/#"))
        assertTrue(allTopics.contains("12F/#"))

        // 1. JSON array payload
        val arrayPayload = """
            [
              {"veh":"9037","line":"12F","latitude":45.0510,"longitude":7.6560,"direct":0,"timestamp":$nowSec},
              {"veh":"9040","line":"12U","latitude":45.0420,"longitude":7.6440,"direct":1,"timestamp":$nowSec}
            ]
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val arrayParsed = MatoMqttLiveClient.parseMatoMessage("/12U", arrayPayload, nowMillis)
        assertEquals(2, arrayParsed.size)
        assertEquals(setOf("9037", "9040"), arrayParsed.map { it.vehicleId }.toSet())
        assertTrue(arrayParsed.all { it.lineCode == "12" })

        // 2. GeoJSON FeatureCollection
        val geoJsonPayload = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": {"type": "Point", "coordinates": [7.6520, 45.0485]},
                  "properties": {"veh": "9099", "line": "12U", "heading": 180, "direct": 1, "timestamp": $nowSec}
                }
              ]
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val geoParsed = MatoMqttLiveClient.parseMatoMessage("/12U/1/9099", geoJsonPayload, nowMillis)
        assertEquals(1, geoParsed.size)
        assertEquals("9099", geoParsed[0].vehicleId)
        assertEquals(45.0485, geoParsed[0].latitude, 0.0001)
        assertEquals(7.6520, geoParsed[0].longitude, 0.0001)

        // 3. Keyed vehicle map + prefixed 5T topic
        val keyedMapPayload = """
            {
              "9101": {"lat": 45.0580, "lon": 7.6640, "heading": 45, "timestamp": $nowSec}
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val keyedParsed = MatoMqttLiveClient.parseMatoMessage("/gtt/12U/0/9101", keyedMapPayload, nowMillis)
        assertEquals(1, keyedParsed.size)
        assertEquals("9101", keyedParsed[0].vehicleId)
        assertEquals("12", keyedParsed[0].lineCode)
        assertEquals(0, keyedParsed[0].directionId)
    }

    @Test
    fun staticRouteStoreSelectsSundayShapeAndLiveDetourTripOverWeekdayShape() {
        val tempZip = File.createTempFile("gtt_sunday_detour_", ".zip")
        try {
            val routesTxt = """
                route_id,route_short_name,route_long_name
                12U,12,Allamano - Porta Nuova
            """.trimIndent()
            val calendarTxt = """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                WEEKDAY,1,1,1,1,1,0,0,20260101,20261231
                SUNDAY,0,0,0,0,0,0,1,20260101,20261231
            """.trimIndent()
            val tripsBuilder = StringBuilder("route_id,service_id,trip_id,trip_headsign,direction_id,shape_id\n")
            // 10 weekday trips on Corso Orbassano (SHAPE_WEEKDAY)
            for (i in 1..10) {
                tripsBuilder.append("12U,WEEKDAY,WD_$i,Allamano Feriale,1,SHAPE_WEEKDAY\n")
            }
            // 2 Sunday trips on Corso Galileo Ferraris (SHAPE_SUNDAY)
            tripsBuilder.append("12U,SUNDAY,SUN_1,Allamano Festivo,1,SHAPE_SUNDAY\n")
            tripsBuilder.append("12U,SUNDAY,SUN_2,Allamano Festivo,1,SHAPE_SUNDAY\n")
            // 1 special live detour trip (SHAPE_LIVE_DETOUR)
            tripsBuilder.append("12U,SUNDAY,LIVE_DETOUR_TRIP,Allamano Deviata,1,SHAPE_LIVE_DETOUR\n")

            val shapesTxt = """
                shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence
                SHAPE_WEEKDAY,45.0600,7.6700,1
                SHAPE_WEEKDAY,45.0480,7.6480,2
                SHAPE_WEEKDAY,45.0330,7.6200,3
                SHAPE_SUNDAY,45.0600,7.6700,1
                SHAPE_SUNDAY,45.0490,7.6575,2
                SHAPE_SUNDAY,45.0330,7.6200,3
                SHAPE_LIVE_DETOUR,45.0600,7.6700,1
                SHAPE_LIVE_DETOUR,45.0515,7.6625,2
                SHAPE_LIVE_DETOUR,45.0330,7.6200,3
            """.trimIndent()

            val stopsTxt = """
                stop_id,stop_code,stop_name,stop_lat,stop_lon
                S1,1001,Porta Nuova,45.0600,7.6700
                S_WD,1002,Corso Orbassano,45.0480,7.6480
                S_SUN,1003,Corso Galileo Ferraris,45.0490,7.6575
                S_DET,1004,Deviazione Live,45.0515,7.6625
            """.trimIndent()

            val stopTimesTxt = """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence
                WD_1,08:00:00,08:00:00,S1,1
                WD_1,08:10:00,08:10:00,S_WD,2
                SUN_1,10:00:00,10:00:00,S1,1
                SUN_1,10:10:00,10:10:00,S_SUN,2
                LIVE_DETOUR_TRIP,10:30:00,10:30:00,S1,1
                LIVE_DETOUR_TRIP,10:40:00,10:40:00,S_DET,2
            """.trimIndent()

            ZipOutputStream(tempZip.outputStream()).use { zos ->
                fun addEntry(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(StandardCharsets.UTF_8))
                    zos.closeEntry()
                }
                addEntry("routes.txt", routesTxt)
                addEntry("calendar.txt", calendarTxt)
                addEntry("trips.txt", tripsBuilder.toString())
                addEntry("shapes.txt", shapesTxt)
                addEntry("stops.txt", stopsTxt)
                addEntry("stop_times.txt", stopTimesTxt)
            }

            // Sunday 22 November 2026 10:50 Europe/Rome
            val sundayMillis = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome")).apply {
                set(2026, Calendar.NOVEMBER, 22, 10, 50, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // 1. On Sunday with no live trips, SUNDAY shape (Corso Galileo Ferraris) beats 10x WEEKDAY trips
            val sundayPatterns = GttStaticRouteStore.extractPatternsFromZip(
                zipFile = tempZip,
                targetLine = "12",
                nowMillis = sundayMillis,
                activeTripIds = emptySet(),
            )
            assertEquals(1, sundayPatterns.size)
            assertEquals(7.6575, sundayPatterns[0].segments[0][1].lon, 0.0001)
            assertTrue(sundayPatterns[0].stops.any { it.name == "Corso Galileo Ferraris" })

            // 2. With a live active detour tripId, LIVE_DETOUR_TRIP overrides even the regular Sunday shape
            val detourPatterns = GttStaticRouteStore.extractPatternsFromZip(
                zipFile = tempZip,
                targetLine = "12",
                nowMillis = sundayMillis,
                activeTripIds = setOf("gtt:LIVE_DETOUR_TRIP"),
            )
            assertEquals(1, detourPatterns.size)
            assertEquals(7.6625, detourPatterns[0].segments[0][1].lon, 0.0001)
            assertTrue(detourPatterns[0].stops.any { it.name == "Deviazione Live" })
        } finally {
            tempZip.delete()
        }
    }

    @Test
    fun frozenGtfsFeedWithFakeFreshTimestampsLosesToMovingMatoWithoutDroppingUniqueBuses() {
        val tracker = SourceHealthTracker()
        val t0 = nowMillis
        val t1 = t0 + 40_000L
        val t2 = t0 + 80_000L
        val t3 = t0 + 120_000L

        // 4 non-depot buses frozen at identical coordinates in GTT Ufficiale even though timestamps keep advancing!
        fun frozenGttBatch(atMillis: Long): List<BusVehicle> {
            val sec = atMillis / 1000L
            return listOf(
                sampleBus("9001", "12", 45.0500, 7.6600, sec - 1, "GTT Ufficiale", stopId = "OLD_STOP_1"),
                sampleBus("9002", "12", 45.0550, 7.6650, sec - 1, "GTT Ufficiale", stopId = "OLD_STOP_2"),
                sampleBus("9003", "4", 45.0650, 7.6750, sec - 1, "GTT Ufficiale"),
                sampleBus("9004", "15", 45.0700, 7.6800, sec - 1, "GTT Ufficiale"),
                sampleBus("9005", "12", 45.0480, 7.6580, sec - 1, "GTT Ufficiale"), // Only in GTT (must NOT disappear!)
            )
        }

        // MaTO Live reports #9001 and #9002 genuinely moving along Line 12, even with 45s older real GPS timestamps!
        val matoT0 = listOf(
            sampleBus("9001", "12", 45.0500, 7.6600, (t0 / 1000L) - 35, "MaTO Live", stopId = null),
            sampleBus("9002", "12", 45.0550, 7.6650, (t0 / 1000L) - 35, "MaTO Live", stopId = null),
        )
        val matoT3 = listOf(
            sampleBus("9001", "12", 45.0545, 7.6645, (t3 / 1000L) - 45, "MaTO Live", stopId = null),
            sampleBus("9002", "12", 45.0595, 7.6695, (t3 / 1000L) - 45, "MaTO Live", stopId = null),
        )

        tracker.recordAndEvaluate("GTT Ufficiale", frozenGttBatch(t0), "12", t0)
        tracker.recordAndEvaluate("MaTO Live", matoT0, "12", t0)
        tracker.recordAndEvaluate("GTT Ufficiale", frozenGttBatch(t1), "12", t1)
        tracker.recordAndEvaluate("GTT Ufficiale", frozenGttBatch(t2), "12", t2)

        val gttReport = tracker.recordAndEvaluate("GTT Ufficiale", frozenGttBatch(t3), "12", t3)
        val matoReport = tracker.recordAndEvaluate("MaTO Live", matoT3, "12", t3)

        assertTrue("GTT fleet must be flagged frozen after 120s without movement", gttReport.fleetFrozen)
        assertEquals(SourceMotionStatus.FROZEN, gttReport.status)
        assertFalse("Moving MaTO must NOT be flagged frozen", matoReport.isFrozenOverall)
        assertEquals(SourceMotionStatus.MOVING, matoReport.status)

        val reports = SourceHealthTracker.refineWithCrossSourceComparison(
            mapOf("GTT Ufficiale" to gttReport, "MaTO Live" to matoReport),
        )
        val merged = GttRealtimeRepository.mergeMultiSourceVehicles(
            matoVehicles = matoT3,
            gttVehicles = frozenGttBatch(t3),
            busOneVehicles = emptyList(),
            nowMillis = t3,
            focusLine = "12",
            sourceReports = reports,
        )

        // All 5 unique vehicles must still be present (zero bus loss!)
        assertEquals(5, merged.size)

        // #9001 & #9002 must use MaTO's moving coordinates even though GTT's fake timestamp was 44s newer!
        val bus9001 = merged.first { it.vehicleId == "9001" }
        assertEquals("MaTO Live", bus9001.sourceName)
        assertEquals(45.0545, bus9001.latitude, 0.0001)
        // Must NOT copy stale stopId from frozen GTT ~600m behind
        assertNull(bus9001.stopId)

        // #9005 (only in GTT) must STILL be preserved so buses never vanish
        assertNotNull(merged.firstOrNull { it.vehicleId == "9005" })

        // Instant auto-recovery as soon as GTT vehicles move again
        val t4 = t3 + 25_000L
        val recoveredGtt = listOf(
            sampleBus("9001", "12", 45.0560, 7.6660, (t4 / 1000L) - 2, "GTT Ufficiale"),
            sampleBus("9002", "12", 45.0610, 7.6710, (t4 / 1000L) - 2, "GTT Ufficiale"),
            sampleBus("9003", "4", 45.0680, 7.6780, (t4 / 1000L) - 2, "GTT Ufficiale"),
            sampleBus("9004", "15", 45.0730, 7.6830, (t4 / 1000L) - 2, "GTT Ufficiale"),
        )
        val recoveredReport = tracker.recordAndEvaluate("GTT Ufficiale", recoveredGtt, "12", t4)
        assertFalse("GTT must immediately unfreeze once buses move again", recoveredReport.fleetFrozen)
        assertEquals(SourceMotionStatus.MOVING, recoveredReport.status)
    }

    @Test
    fun crossSourceDivergenceDetectsFrozenFeedWithin50Seconds() {
        val tracker = SourceHealthTracker()
        val t0 = nowMillis
        val t1 = t0 + 50_000L

        val gttFrozen = listOf(
            sampleBus("9101", "12", 45.0400, 7.6400, (t1 / 1000L) - 1, "GTT Ufficiale"),
            sampleBus("9102", "12", 45.0450, 7.6450, (t1 / 1000L) - 1, "GTT Ufficiale"),
        )
        val matoT0 = listOf(
            sampleBus("9101", "12", 45.0400, 7.6400, (t0 / 1000L) - 15, "MaTO Live"),
            sampleBus("9102", "12", 45.0450, 7.6450, (t0 / 1000L) - 15, "MaTO Live"),
        )
        val matoT1 = listOf(
            sampleBus("9101", "12", 45.0425, 7.6425, (t1 / 1000L) - 35, "MaTO Live"),
            sampleBus("9102", "12", 45.0475, 7.6475, (t1 / 1000L) - 35, "MaTO Live"),
        )

        tracker.recordAndEvaluate("GTT Ufficiale", gttFrozen, "12", t0)
        tracker.recordAndEvaluate("MaTO Live", matoT0, "12", t0)

        val gttRep = tracker.recordAndEvaluate("GTT Ufficiale", gttFrozen, "12", t1)
        val matoRep = tracker.recordAndEvaluate("MaTO Live", matoT1, "12", t1)
        val refined = SourceHealthTracker.refineWithCrossSourceComparison(
            mapOf("GTT Ufficiale" to gttRep, "MaTO Live" to matoRep),
        )

        assertTrue(refined["GTT Ufficiale"]!!.crossSourceFrozen)
        assertFalse(refined["MaTO Live"]!!.isFrozenOverall)
    }

    @Test
    fun appUpdateCheckerNormalizesReposComparesSemverAndValidatesGithubUrls() {
        assertEquals("mario/ToBus", AppUpdateChecker.normalizeRepoSlug("mario/ToBus"))
        assertEquals("mario/ToBus", AppUpdateChecker.normalizeRepoSlug("https://github.com/mario/ToBus/"))
        assertEquals("mario/ToBus", AppUpdateChecker.normalizeRepoSlug("github.com/mario/ToBus.git"))
        assertNull(AppUpdateChecker.normalizeRepoSlug("not-a-repo"))
        assertNull(AppUpdateChecker.normalizeRepoSlug("https://github.com/bad repo/name"))

        assertTrue(AppUpdateChecker.isRemoteNewer("1.0", "v1.0.1"))
        assertTrue(AppUpdateChecker.isRemoteNewer("1.9.0", "v1.10.0"))
        assertFalse(AppUpdateChecker.isRemoteNewer("1.2.0", "v1.2.0"))
        assertFalse(AppUpdateChecker.isRemoteNewer("1.2.0", "v1.1.9"))
        assertFalse(AppUpdateChecker.isRemoteNewer("1.0", "v1.0.0"))

        assertTrue(AppUpdateChecker.isTrustedGithubUrl("https://github.com/mario/ToBus/releases/download/v1.1.0/ToBus-v1.1.0.apk"))
        assertFalse(AppUpdateChecker.isTrustedGithubUrl("http://github.com/mario/ToBus/releases"))
        assertFalse(AppUpdateChecker.isTrustedGithubUrl("https://evil.example.com/ToBus.apk"))
    }

    private fun sampleBus(
        vehicleId: String,
        lineCode: String,
        lat: Double,
        lon: Double,
        tsSec: Long,
        source: String,
        tripId: String? = null,
        stopId: String? = null,
        directionId: Int? = null,
    ): BusVehicle = BusVehicle(
        entityId = "${source}_${vehicleId}_$lineCode",
        vehicleId = vehicleId,
        label = vehicleId,
        rawRouteId = "${lineCode}U",
        lineCode = lineCode,
        tripId = tripId,
        directionId = directionId,
        latitude = lat,
        longitude = lon,
        bearing = 90f,
        speedKmh = 22f,
        timestampSeconds = tsSec,
        stopId = stopId,
        status = VehicleStopStatus.IN_TRANSIT_TO,
        sourceName = source,
        contributingSources = setOf(source),
        fetchedAtMillis = nowMillis,
    )

    private fun buildPublishPacket(topic: String, payload: String): ByteArray {
        val topicBytes = topic.toByteArray(StandardCharsets.UTF_8)
        val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
        val body = ByteArrayOutputStream()
        body.write((topicBytes.size ushr 8) and 0xFF)
        body.write(topicBytes.size and 0xFF)
        body.write(topicBytes)
        body.write(payloadBytes)
        val bodyBytes = body.toByteArray()

        val out = ByteArrayOutputStream()
        out.write(0x30) // PUBLISH QoS 0
        var x = bodyBytes.size
        do {
            var encoded = x and 0x7F
            x = x ushr 7
            if (x > 0) encoded = encoded or 0x80
            out.write(encoded)
        } while (x > 0)
        out.write(bodyBytes)
        return out.toByteArray()
    }
}
