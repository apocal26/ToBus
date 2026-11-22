package com.example

import com.example.data.model.BusVehicle
import com.example.data.model.VehicleFreshness
import com.example.data.model.VehicleStopStatus
import com.example.data.network.GtfsRtProtobufParser
import com.example.map.WebMercator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class GtfsRtProtobufParserTest {

    @Test
    fun parsesStandardGtfsRtVehiclePositionsMessage() {
        val vehicle1 = buildVehiclePosition(
            tripId = "trip_4_101",
            routeId = "4U",
            directionId = 0,
            lat = 45.0703f,
            lon = 7.6869f,
            bearing = 180.5f,
            speedMs = 8.0f,
            status = 2,
            timestamp = 1732180000L,
            vehicleId = "6012",
            label = "6012",
        )
        val vehicle2 = buildVehiclePosition(
            tripId = "trip_55_202",
            routeId = "55U",
            directionId = 1,
            lat = 45.0642f,
            lon = 7.6615f,
            bearing = 45.0f,
            speedMs = 5.0f,
            status = 1,
            timestamp = 1732180010L,
            vehicleId = "3318",
            label = "3318",
        )
        val entity1 = buildFeedEntity("e1", 4, vehicle1)
        val entity2 = buildFeedEntity("e2", 4, vehicle2)
        val header = buildFeedHeader(1732180020L)

        val out = ByteArrayOutputStream()
        writeLengthDelimited(out, 1, header)
        writeLengthDelimited(out, 2, entity1)
        writeLengthDelimited(out, 2, entity2)

        val snapshot = GtfsRtProtobufParser.parseFeedMessage(
            bytes = out.toByteArray(),
            sourceName = "GTT Ufficiale",
            fetchedAtMillis = 123456L,
        )

        assertEquals(1732180020L, snapshot.feedTimestampSeconds)
        assertEquals(2, snapshot.allVehicles.size)
        assertEquals(listOf("4", "55"), snapshot.availableLines.map { it.lineCode })

        val v1 = snapshot.allVehicles[0]
        assertEquals("4", v1.lineCode)
        assertEquals("GTT Ufficiale", v1.sourceName)
        assertEquals(VehicleStopStatus.IN_TRANSIT_TO, v1.status)

        val v2 = snapshot.allVehicles[1]
        assertEquals("55", v2.lineCode)
        assertEquals(VehicleStopStatus.STOPPED_AT, v2.status)
    }

    @Test
    fun parsesGtfsRtTripUpdatesDelayAndNextStop() {
        val tripOut = ByteArrayOutputStream()
        writeString(tripOut, 1, "trip_4_101")
        writeString(tripOut, 5, "4U")

        val arrivalOut = ByteArrayOutputStream()
        writeVarintField(arrivalOut, 1, 180L) // +3 min delay

        val stuOut = ByteArrayOutputStream()
        writeLengthDelimited(stuOut, 2, arrivalOut.toByteArray())
        writeString(stuOut, 4, "1234")

        val vehOut = ByteArrayOutputStream()
        writeString(vehOut, 1, "6012")

        val tuOut = ByteArrayOutputStream()
        writeLengthDelimited(tuOut, 1, tripOut.toByteArray())
        writeLengthDelimited(tuOut, 2, stuOut.toByteArray())
        writeLengthDelimited(tuOut, 3, vehOut.toByteArray())
        writeVarintField(tuOut, 4, 1732180050L)

        val entity = buildFeedEntity("tu1", 3, tuOut.toByteArray())
        val feed = ByteArrayOutputStream()
        writeLengthDelimited(feed, 2, entity)

        val updates = GtfsRtProtobufParser.parseTripUpdates(feed.toByteArray())
        assertEquals(1, updates.size)
        assertEquals("trip_4_101", updates[0].tripId)
        assertEquals("6012", updates[0].vehicleId)
        assertEquals(180, updates[0].delaySeconds)
        assertEquals("1234", updates[0].nextStopId)
    }

    @Test
    fun evaluatesVehicleFreshnessThresholdsAccurately() {
        val nowSec = 1732180300L
        val nowMs = nowSec * 1000L

        val liveBus = sampleBus(timestampSec = nowSec - 18L)
        val slowBus = sampleBus(timestampSec = nowSec - 90L)
        val staleBus = sampleBus(timestampSec = nowSec - 210L)

        assertEquals(18L, liveBus.gpsAgeSeconds(nowMs))
        assertEquals(VehicleFreshness.LIVE, liveBus.freshness(nowMs))
        assertEquals(VehicleFreshness.SLOW, slowBus.freshness(nowMs))
        assertEquals(VehicleFreshness.STALE, staleBus.freshness(nowMs))
    }

    @Test
    fun webMercatorRoundTripIsAccurate() {
        val lat = 45.0703
        val lon = 7.6869
        val zoom = 14.0
        val (wx, wy) = WebMercator.latLonToWorldPixel(lat, lon, zoom)
        val back = WebMercator.worldPixelToLatLon(wx, wy, zoom)
        assertEquals(lat, back.lat, 1e-5)
        assertEquals(lon, back.lon, 1e-5)
    }

    @Test
    fun autoFitCameraCentersBetweenVehicles() {
        val vehicles = listOf(
            sampleBus(lat = 45.05, lon = 7.66),
            sampleBus(lat = 45.09, lon = 7.70),
        )
        val cam = WebMercator.computeAutoFitCamera(vehicles, emptyList(), 600, 320)
        assertEquals(45.07, cam.centerLat, 0.001)
        assertEquals(7.68, cam.centerLon, 0.001)
        assertTrue(cam.zoom in 11.3..15.6)
    }

    private fun sampleBus(
        timestampSec: Long = 1732180000L,
        lat: Double = 45.07,
        lon: Double = 7.68,
    ) = BusVehicle(
        entityId = "e",
        vehicleId = "9010",
        label = "9010",
        rawRouteId = "4U",
        lineCode = "4",
        tripId = null,
        directionId = 0,
        latitude = lat,
        longitude = lon,
        bearing = 90f,
        speedKmh = 20f,
        timestampSeconds = timestampSec,
        stopId = null,
        status = VehicleStopStatus.IN_TRANSIT_TO,
    )

    private fun buildFeedHeader(timestamp: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, 1, "2.0")
        writeVarintField(out, 3, timestamp)
        return out.toByteArray()
    }

    private fun buildFeedEntity(id: String, payloadField: Int, payloadBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        writeString(out, 1, id)
        writeLengthDelimited(out, payloadField, payloadBytes)
        return out.toByteArray()
    }

    private fun buildVehiclePosition(
        tripId: String,
        routeId: String,
        directionId: Int,
        lat: Float,
        lon: Float,
        bearing: Float,
        speedMs: Float,
        status: Int,
        timestamp: Long,
        vehicleId: String,
        label: String,
    ): ByteArray {
        val tripOut = ByteArrayOutputStream()
        writeString(tripOut, 1, tripId)
        writeString(tripOut, 5, routeId)
        writeVarintField(tripOut, 6, directionId.toLong())

        val posOut = ByteArrayOutputStream()
        writeFloatField(posOut, 1, lat)
        writeFloatField(posOut, 2, lon)
        writeFloatField(posOut, 3, bearing)
        writeFloatField(posOut, 5, speedMs)

        val vehOut = ByteArrayOutputStream()
        writeString(vehOut, 1, vehicleId)
        writeString(vehOut, 2, label)

        val out = ByteArrayOutputStream()
        writeLengthDelimited(out, 1, tripOut.toByteArray())
        writeLengthDelimited(out, 2, posOut.toByteArray())
        writeVarintField(out, 4, status.toLong())
        writeVarintField(out, 5, timestamp)
        writeLengthDelimited(out, 8, vehOut.toByteArray())
        return out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return
            }
            out.write(((v.toInt() and 0x7F) or 0x80))
            v = v ushr 7
        }
    }

    private fun writeVarintField(out: ByteArrayOutputStream, fieldNumber: Int, value: Long) {
        writeVarint(out, ((fieldNumber shl 3) or 0).toLong())
        writeVarint(out, value)
    }

    private fun writeFloatField(out: ByteArrayOutputStream, fieldNumber: Int, value: Float) {
        writeVarint(out, ((fieldNumber shl 3) or 5).toLong())
        val bits = java.lang.Float.floatToRawIntBits(value)
        out.write(bits and 0xFF)
        out.write((bits ushr 8) and 0xFF)
        out.write((bits ushr 16) and 0xFF)
        out.write((bits ushr 24) and 0xFF)
    }

    private fun writeLengthDelimited(out: ByteArrayOutputStream, fieldNumber: Int, payload: ByteArray) {
        writeVarint(out, ((fieldNumber shl 3) or 2).toLong())
        writeVarint(out, payload.size.toLong())
        out.write(payload)
    }

    private fun writeString(out: ByteArrayOutputStream, fieldNumber: Int, text: String) {
        writeLengthDelimited(out, fieldNumber, text.toByteArray(Charsets.UTF_8))
    }
}
