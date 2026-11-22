package com.example

import com.example.data.model.BusVehicle
import com.example.data.model.MapStyle
import com.example.data.model.VehicleStopStatus
import com.example.ui.components.MapLibreTransitIds
import com.example.ui.components.TurinVectorStyleBuilder
import com.example.ui.components.buildBusesFeatureCollection
import com.example.ui.components.buildExternalBusFeatureCollection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.geojson.Point
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TurinVectorStyleAndOverlayTest {

    @Test
    fun allThreeThemesGenerateValidStyleV8WithCompleteUrbanGeography() {
        val requiredLayerIds = setOf(
            "bg",
            "landcover-grass",
            "landcover-wood",
            "landuse-green",
            "park-fill",
            "water-fill",
            "waterway-line",
            "buildings",
            "road-minor-casing",
            "road-minor-fill",
            "road-secondary-fill",
            "road-primary-fill",
            "road-motorway-fill",
            "label-streets-major",
            "label-streets-minor",
            "label-neighborhoods",
            "label-cities",
        )
        // Guard against Android #AARRGGBB 8-digit hex strings leaking into MapLibre CSS style JSON
        val eightDigitHexRegex = Regex("#[0-9A-Fa-f]{8}\\b")

        for (style in MapStyle.entries) {
            val rawJson = TurinVectorStyleBuilder.buildStyleJson(style)
            assertFalse(
                "Style ${style.name} must not contain 8-digit hex colors (use #RRGGBB or rgba)",
                eightDigitHexRegex.containsMatchIn(rawJson),
            )

            val root = JSONObject(rawJson)
            assertEquals(8, root.getInt("version"))
            assertEquals(TurinVectorStyleBuilder.GLYPHS_URL, root.getString("glyphs"))

            val sources = root.getJSONObject("sources")
            val omt = sources.getJSONObject(TurinVectorStyleBuilder.VECTOR_SOURCE_ID)
            assertEquals("vector", omt.getString("type"))
            assertEquals(TurinVectorStyleBuilder.TILEJSON_URL, omt.getString("url"))

            val layers = root.getJSONArray("layers")
            val ids = HashSet<String>()
            val sourceLayers = HashSet<String>()
            for (i in 0 until layers.length()) {
                val layer = layers.getJSONObject(i)
                ids.add(layer.getString("id"))
                if (layer.has("source-layer")) {
                    sourceLayers.add(layer.getString("source-layer"))
                }
            }

            for (req in requiredLayerIds) {
                assertTrue("Style ${style.name} missing required layer '$req'", ids.contains(req))
            }
            assertTrue(sourceLayers.contains("building"))
            assertTrue(sourceLayers.contains("landcover"))
            assertTrue(sourceLayers.contains("landuse"))
            assertTrue(sourceLayers.contains("transportation"))
            assertFalse(
                "Style ${style.name} should not bundle raw house numbers",
                sourceLayers.contains("housenumber"),
            )
        }
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
