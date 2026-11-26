package com.example

import com.example.data.model.BusVehicle
import com.example.data.model.LineSummary
import com.example.data.model.StopSearchEntry
import com.example.data.model.VehicleStopStatus
import com.example.data.network.GttRealtimeRepository
import com.example.data.network.GttStaticRouteStore
import com.example.ui.icons.ToBusIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LowEndPerformanceOptimizationTest {

    private fun sampleVehicle(id: String, line: String, rawRoute: String = "${line}U"): BusVehicle =
        BusVehicle(
            entityId = "e_$id",
            vehicleId = id,
            label = id,
            rawRouteId = rawRoute,
            lineCode = line,
            tripId = "t_$id",
            directionId = 0,
            latitude = 45.0703,
            longitude = 7.6869,
            bearing = 90f,
            speedKmh = 24f,
            timestampSeconds = 1_760_000_000L,
            stopId = "100",
            status = VehicleStopStatus.IN_TRANSIT_TO,
            sourceName = "MaTO Live",
            contributingSources = setOf("MaTO Live"),
        )

    @Test
    fun stopSearchEntry_precomputesDisplayAndSearchFieldsCorrectly() {
        val entry = StopSearchEntry(
            stopId = "317",
            stopCode = "317",
            name = "PORTA NUOVA FS CAPOLINEA",
            lat = 45.0620,
            lon = 7.6780,
            lines = listOf("4", "12", "33"),
            headsigns = listOf("Falchera", "Allamano", "Extra"),
        )
        assertEquals("Porta Nuova FS Capolinea", entry.displayName)
        assertEquals("Falchera · Allamano", entry.headsignSummary)
        assertTrue(entry.searchLinesUpper.contains("12"))
        assertTrue(entry.searchNameLower.contains("porta nuova"))

        val emptyHeadsigns = entry.copy(headsigns = emptyList())
        assertNull(emptyHeadsigns.headsignSummary)
    }

    @Test
    fun searchStops_handlesMultiTokenAndNearbyFastBoundingBox() {
        val index = listOf(
            StopSearchEntry(
                stopId = "40",
                stopCode = "40",
                name = "PORTA NUOVA",
                lat = 45.0622,
                lon = 7.6782,
                lines = listOf("4", "12", "52"),
                headsigns = listOf("Falchera"),
            ),
            StopSearchEntry(
                stopId = "41",
                stopCode = "41",
                name = "PORTA NUOVA",
                lat = 45.0625,
                lon = 7.6785,
                lines = listOf("9", "11"),
                headsigns = listOf("Venaria"),
            ),
            StopSearchEntry(
                stopId = "999",
                stopCode = "999",
                name = "STURA CAPOLINEA",
                lat = 45.1200,
                lon = 7.7200,
                lines = listOf("4"),
                headsigns = listOf("Strada del Drosso"),
            ),
        )

        val tokenMatches = GttStaticRouteStore.searchStops(
            index = index,
            query = "Porta Nuova 12",
            preferredLine = "4",
        )
        assertEquals(1, tokenMatches.size)
        assertEquals("40", tokenMatches.first().stopId)

        val exactPalina = GttStaticRouteStore.searchStops(
            index = index,
            query = "#41",
            preferredLine = "4",
        )
        assertEquals("41", exactPalina.first().stopId)

        val nearby = GttStaticRouteStore.searchStops(
            index = index,
            query = "",
            preferredLine = "4",
            userLat = 45.0621,
            userLon = 7.6781,
        )
        assertEquals(2, nearby.size)
        assertEquals("40", nearby.first().stopId)
    }

    @Test
    fun updateLineSummariesForFocusLine_updatesIncrementallyAndPreservesOrder() {
        val base = listOf(
            LineSummary("4", setOf("4U"), 8),
            LineSummary("15", setOf("15U"), 6),
            LineSummary("55", setOf("55U"), 4),
        )

        // Update existing focused line count
        val updatedVehicles = listOf(
            sampleVehicle("101", "15"),
            sampleVehicle("102", "15"),
            sampleVehicle("201", "4"),
        )
        val afterUpdate = GttRealtimeRepository.updateLineSummariesForFocusLine(base, updatedVehicles, "15")
        assertEquals(listOf("4", "15", "55"), afterUpdate.map { it.lineCode })
        assertEquals(8, afterUpdate.first { it.lineCode == "4" }.activeVehicleCount)
        assertEquals(2, afterUpdate.first { it.lineCode == "15" }.activeVehicleCount)
        assertEquals(4, afterUpdate.first { it.lineCode == "55" }.activeVehicleCount)

        // Insert a brand-new active focused line in natural numeric order
        val withNewLine = GttRealtimeRepository.updateLineSummariesForFocusLine(
            base,
            listOf(sampleVehicle("301", "10", "10N")),
            "10",
        )
        assertEquals(listOf("4", "10", "15", "55"), withNewLine.map { it.lineCode })
        assertEquals(1, withNewLine.first { it.lineCode == "10" }.activeVehicleCount)

        // Remove focused line when count drops to 0
        val afterDrop = GttRealtimeRepository.updateLineSummariesForFocusLine(base, emptyList(), "15")
        assertEquals(listOf("4", "55"), afterDrop.map { it.lineCode })
    }

    @Test
    fun toBusIcons_allCustomVectorsAreValidAndNonEmpty() {
        val icons = listOf(
            ToBusIcons.DirectionsBus,
            ToBusIcons.MyLocation,
            ToBusIcons.MapRounded,
            ToBusIcons.MapOutlined,
            ToBusIcons.NotificationsActive,
            ToBusIcons.NotificationsNone,
            ToBusIcons.StarOutline,
            ToBusIcons.WarningAmber,
            ToBusIcons.AddToHomeScreen,
            ToBusIcons.AutoAwesome,
            ToBusIcons.BatterySaver,
            ToBusIcons.CropFree,
            ToBusIcons.GridView,
            ToBusIcons.SystemUpdate,
            ToBusIcons.Widgets,
        )
        assertEquals(15, icons.size)
        icons.forEach { vector ->
            assertNotNull(vector)
            assertFalse("Icon ${vector.name} should not have empty root", vector.root.size == 0)
        }
    }
}
