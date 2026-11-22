package com.example

import com.example.data.network.GttStaticRouteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GttStaticRouteStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun extractsDominantAndataAndRitornoShapesWithGttCodeNormalization() {
        val zipFile = createGtfsZip(
            routesCsv = "\uFEFFroute_id,agency_id,route_short_name,route_long_name,route_type\n" +
                "12U,GTT,\"12\",\"Allamano - Corso Toscana, Torino\",3\n" +
                "4U,GTT,4,Falchera - Strada del Drosso,0\n",
            tripsCsv = "route_id,service_id,trip_id,trip_headsign,direction_id,shape_id\n" +
                "12U,FER,t1,Allamano,0,shape_12_andata\n" +
                "12U,FER,t2,Allamano,0,shape_12_andata\n" +
                "12U,FER,t3,Deposito,0,shape_12_depot\n" +
                "12U,FER,t4,Corso Toscana,1,shape_12_ritorno\n" +
                "12U,FER,t5,Corso Toscana,1,shape_12_ritorno\n" +
                "4U,FER,t6,Falchera,0,shape_4_andata\n",
            shapesCsv = "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n" +
                "shape_12_andata,45.0500,7.6400,20\n" +
                "shape_12_andata,45.0400,7.6300,10\n" +
                "shape_12_andata,45.0600,7.6500,30\n" +
                "shape_12_depot,45.0100,7.6100,1\n" +
                "shape_12_depot,45.0200,7.6200,2\n" +
                "shape_12_ritorno,45.0610,7.6510,1\n" +
                "shape_12_ritorno,45.0410,7.6310,2\n" +
                "shape_4_andata,45.0700,7.6800,1\n" +
                "shape_4_andata,45.0800,7.6900,2\n",
            stopTimesCsv = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "t1,10:05:00,10:05:00,243,2\n" +
                "t1,10:00:00,10:00:00,101,1\n" +
                "t4,10:15:00,10:15:00,244,1\n",
            stopsCsv = "stop_id,stop_code,stop_name,stop_lat,stop_lon\n" +
                "101,101,ALLAMANO CAP,45.0400,7.6300\n" +
                "243,243,SANTA RITA,45.0500,7.6400\n" +
                "244,244,CORSO TOSCANA,45.0610,7.6510\n",
        )

        val patterns = GttStaticRouteStore.extractPatternsFromZip(zipFile, "12")
        assertEquals(2, patterns.size)

        val andata = patterns.first { it.directionId == 0 }
        assertEquals("gtfs_shape_12_andata", andata.id)
        assertEquals("Allamano", andata.name)
        assertEquals(3, andata.points.size)
        // Verify sorted by shape_pt_sequence (10, 20, 30)
        assertEquals(45.0400, andata.points[0].lat, 0.0001)
        assertEquals(45.0500, andata.points[1].lat, 0.0001)
        assertEquals(45.0600, andata.points[2].lat, 0.0001)
        // Verify ordered stops extracted on the route
        assertEquals(2, andata.stops.size)
        assertEquals("101", andata.stops[0].stopCode)
        assertEquals("ALLAMANO CAP", andata.stops[0].name)
        assertEquals("243", andata.stops[1].stopCode)

        val ritorno = patterns.first { it.directionId == 1 }
        assertEquals("gtfs_shape_12_ritorno", ritorno.id)
        assertEquals(2, ritorno.points.size)
        assertEquals(1, ritorno.stops.size)
        assertEquals("244", ritorno.stops[0].stopCode)

        // Verify scheduled stop arrivals lookup around 10:00 Europe/Rome
        val tenAmMillis = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Rome")).apply {
            set(java.util.Calendar.HOUR_OF_DAY, 10)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val arrivals = GttStaticRouteStore.extractScheduledArrivalsFromZip(zipFile, "243", "12", tenAmMillis)
        assertEquals(1, arrivals.size)
        assertEquals("12", arrivals[0].lineCode)
        assertEquals("Allamano", arrivals[0].headsign)
        assertEquals(5, arrivals[0].minutesUntil(tenAmMillis))
    }

    @Test
    fun fallsBackToStopSequenceWhenShapeIdIsMissing() {
        val zipFile = createGtfsZip(
            routesCsv = "route_id,route_short_name,route_long_name\n" +
                "STAR1U,STAR 1,Circolare Centro\n",
            tripsCsv = "route_id,trip_id,direction_id,shape_id\n" +
                "STAR1U,trip_star_1,0,\n",
            shapesCsv = null,
            stopTimesCsv = "trip_id,stop_id,stop_sequence\n" +
                "trip_star_1,S2,2\n" +
                "trip_star_1,S1,1\n" +
                "trip_star_1,S3,3\n",
            stopsCsv = "stop_id,stop_name,stop_lat,stop_lon\n" +
                "S1,Porta Nuova,45.0620,7.6780\n" +
                "S2,Piazza Castello,45.0709,7.6859\n" +
                "S3,Porta Susa,45.0720,7.6660\n",
        )

        val patterns = GttStaticRouteStore.extractPatternsFromZip(zipFile, "STAR 1")
        assertEquals(1, patterns.size)
        assertEquals(3, patterns[0].points.size)
        assertTrue(patterns[0].points[0].lat in 45.061..45.063)
        assertTrue(patterns[0].points[2].lon in 7.665..7.667)
    }

    private fun createGtfsZip(
        routesCsv: String,
        tripsCsv: String,
        shapesCsv: String?,
        stopTimesCsv: String? = null,
        stopsCsv: String? = null,
    ): File {
        val file = tempFolder.newFile("test_gtt_gtfs.zip")
        ZipOutputStream(file.outputStream()).use { zos ->
            fun addEntry(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            addEntry("routes.txt", routesCsv)
            addEntry("trips.txt", tripsCsv)
            if (shapesCsv != null) addEntry("shapes.txt", shapesCsv)
            if (stopTimesCsv != null) addEntry("stop_times.txt", stopTimesCsv)
            if (stopsCsv != null) addEntry("stops.txt", stopsCsv)
        }
        return file
    }
}
