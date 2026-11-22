package com.example

import com.example.data.model.BusVehicle
import com.example.data.model.MapStyle
import com.example.data.model.VehicleStopStatus
import com.example.ui.components.MapLibreTransitIds
import com.example.ui.components.TurinVectorStyleBuilder
import com.example.ui.components.buildBusesFeatureCollection
import com.example.ui.components.buildExternalBusFeatureCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point

class TurinVectorStyleAndOverlayTest {

    @Test
    fun allThemesAndSatelliteConfigurationAreValid() {
        assertEquals("Scuro", MapStyle.DARK.labelIt)
        assertEquals("Chiaro", MapStyle.POSITRON.labelIt)
        assertEquals("Classico", MapStyle.VOYAGER.labelIt)
        assertEquals("Satellite", MapStyle.SATELLITE.labelIt)
        assertTrue(MapStyle.DARK.isDark)
        assertTrue(MapStyle.SATELLITE.isDark)
        assertFalse(MapStyle.POSITRON.isDark)
        assertFalse(MapStyle.VOYAGER.isDark)
        assertEquals(MapStyle.SATELLITE, MapStyle.fromId("satellite"))
        assertEquals(MapStyle.DARK, MapStyle.fromId("dark"))
        assertEquals(4, TurinVectorStyleBuilder.SATELLITE_TILE_URLS.size)
        assertTrue(TurinVectorStyleBuilder.SATELLITE_TILE_URLS.all { it.contains("google.com/vt/lyrs=s") })
    }

    @Test
    fun externalFocusedBusGeneratesValidIsolatedGeoJsonWithArrowOrBusGlyph() {
        val busWithBearing = BusVehicle(
            entityId = "e-859",
            vehicleId = "859",
            label = "859",
            lineCode = "46",
            rawRouteId = "46U",
            latitude = 45.1082,
            longitude = 7.7044,
            bearing = 142f,
            speedKmh = null,
            timestampSeconds = 1_730_000_000L,
            tripId = "T46",
            directionId = 0,
            stopId = null,
            status = VehicleStopStatus.IN_TRANSIT_TO,
        )
        val fcArrow = buildExternalBusFeatureCollection(busWithBearing)
        val featuresArrow = fcArrow.features().orEmpty()
        assertEquals(1, featuresArrow.size)
        val f1 = featuresArrow.first()
        val pt1 = f1.geometry() as? Point
        assertNotNull(pt1)
        assertEquals(7.7044, pt1!!.longitude(), 1e-6)
        assertEquals(45.1082, pt1.latitude(), 1e-6)
        assertEquals("859", f1.getStringProperty("id"))
        assertEquals(MapLibreTransitIds.ICON_EXT_ARROW, f1.getStringProperty("icon"))
        assertEquals(142.0, f1.getNumberProperty("bearing").toDouble(), 1e-3)
        assertEquals("Linea 46 · #859", f1.getStringProperty("label"))

        val busNoBearing = busWithBearing.copy(bearing = null)
        val fcNoDir = buildExternalBusFeatureCollection(busNoBearing)
        val f2 = fcNoDir.features()!!.first()
        assertEquals(MapLibreTransitIds.ICON_EXT_NODIR, f2.getStringProperty("icon"))
        assertEquals(0.0, f2.getNumberProperty("bearing").toDouble(), 1e-3)

        val empty = buildExternalBusFeatureCollection(null)
        assertTrue(empty.features().orEmpty().isEmpty())
    }

    @Test
    fun trackedLineBusesFeatureCollectionUsesDirectionColorsAndSelectionState() {
        val b1 = BusVehicle(
            entityId = "e-2411",
            vehicleId = "2411",
            label = "2411",
            lineCode = "12",
            rawRouteId = "12U",
            latitude = 45.0441,
            longitude = 7.6412,
            bearing = 35f,
            speedKmh = null,
            timestampSeconds = 1_730_000_000L,
            tripId = "T12A",
            directionId = 0,
            stopId = null,
            status = VehicleStopStatus.IN_TRANSIT_TO,
        )
        val b2 = b1.copy(entityId = "e-2412", vehicleId = "2412", label = "2412", directionId = 1, bearing = null)

        val fc = buildBusesFeatureCollection(
            vehicles = listOf(b1, b2),
            poseForBus = { Triple(it.latitude, it.longitude, it.bearing) },
            selectedVehicleId = "2411",
            busHealthById = emptyMap(),
            resolvedDirectionById = mapOf("2411" to 0, "2412" to 1),
            colorByDirection = true,
            showVehicleLabels = true,
        )
        val features = fc.features().orEmpty()
        assertEquals(2, features.size)

        assertEquals(MapLibreTransitIds.ICON_GOLD_SEL, features[0].getStringProperty("icon"))
        assertEquals("#2411", features[0].getStringProperty("label"))
        assertTrue(features[0].getBooleanProperty("isSelected"))

        assertEquals(MapLibreTransitIds.ICON_CYAN_NODIR, features[1].getStringProperty("icon"))
        assertEquals("#2412", features[1].getStringProperty("label"))
        assertFalse(features[1].getBooleanProperty("isSelected"))
    }
}
