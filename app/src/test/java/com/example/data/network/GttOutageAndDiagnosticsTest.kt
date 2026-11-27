package com.example.data.network

import com.example.data.model.BusVehicle
import com.example.data.model.DataSourceMode
import com.example.data.model.GttOutageKind
import com.example.data.model.VehicleStopStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GttOutageAndDiagnosticsTest {

    private val healthyWifi = DeviceConnectivityInfo(
        isConnected = true,
        isValidated = true,
        isCaptivePortal = false,
        transportLabel = "Wi-Fi",
    )

    private val offlineDevice = DeviceConnectivityInfo(
        isConnected = false,
        isValidated = false,
        isCaptivePortal = false,
        transportLabel = "Offline",
    )

    private val unvalidatedWifi = DeviceConnectivityInfo(
        isConnected = true,
        isValidated = false,
        isCaptivePortal = false,
        transportLabel = "Wi-Fi",
    )

    private val captivePortalWifi = DeviceConnectivityInfo(
        isConnected = true,
        isValidated = true,
        isCaptivePortal = true,
        transportLabel = "Wi-Fi",
    )

    private fun okProbe(nowMillis: Long) = NeutralInternetProbeResult(
        reachable = true,
        httpCode = 204,
        latencyMs = 34L,
        checkedAtMillis = nowMillis,
        endpoint = "https://connectivitycheck.gstatic.com/generate_204",
        detail = "HTTP 204 · 34ms",
    )

    private fun failedProbe(nowMillis: Long) = NeutralInternetProbeResult(
        reachable = false,
        httpCode = null,
        latencyMs = 2500L,
        checkedAtMillis = nowMillis,
        endpoint = "https://connectivitycheck.gstatic.com/generate_204",
        detail = "SocketTimeoutException",
    )

    /** Builds realistic non-depot Turin vehicles spread across multiple lines in central Turin. */
    private fun makeCityVehicles(
        count: Int,
        distinctLines: Int,
        timestampSeconds: Long,
        nowMillis: Long,
    ): List<BusVehicle> {
        val lines = (1..distinctLines).map { it.toString() }
        return (0 until count).map { i ->
            val line = lines[i % lines.size]
            BusVehicle(
                entityId = "e_$i",
                vehicleId = "${3000 + i}",
                label = "${3000 + i}",
                rawRouteId = "${line}U",
                lineCode = line,
                tripId = "trip_$i",
                directionId = i % 2,
                // Central Turin (Piazza Castello / Porta Nuova area, far from all GTT depots)
                latitude = 45.0680 + (i % 7) * 0.0015,
                longitude = 7.6800 + (i / 7) * 0.0015,
                bearing = 90f,
                speedKmh = 18f,
                timestampSeconds = timestampSeconds,
                stopId = "100",
                status = VehicleStopStatus.IN_TRANSIT_TO,
                fetchedAtMillis = nowMillis,
            )
        }
    }

    private fun makeSourceReport(
        name: String,
        status: SourceMotionStatus,
        observedNonDepot: Int,
        movingCount: Int,
        fleetFrozen: Boolean,
        frozenDurationSec: Long,
    ) = SourceHealthReport(
        sourceName = name,
        status = status,
        fleetFrozen = fleetFrozen,
        focusLineFrozen = fleetFrozen,
        observedNonDepotCount = observedNonDepot,
        movingCount = movingCount,
        frozenDurationSeconds = frozenDurationSec,
        confirmedMovingVehicleIds = emptySet(),
        stuckVehicleIds = emptySet(),
        latestPositions = emptyMap(),
    )

    @Before
    fun setup() {
        ToBusDiagnosticsLog.clear()
    }

    @Test
    fun totalFailure_neverFires_whenDeviceOfflineOrCaptiveOrUnvalidated() {
        val detector = GttServiceOutageDetector()
        val t0 = 1_750_000_000_000L

        assertNull(detector.evaluateTotalFailure(DataSourceMode.AUTO, offlineDevice, okProbe(t0), t0))
        assertNull(detector.evaluateTotalFailure(DataSourceMode.AUTO, offlineDevice, okProbe(t0 + 30_000L), t0 + 30_000L))

        assertNull(detector.evaluateTotalFailure(DataSourceMode.AUTO, unvalidatedWifi, okProbe(t0), t0))
        assertNull(detector.evaluateTotalFailure(DataSourceMode.AUTO, unvalidatedWifi, okProbe(t0 + 30_000L), t0 + 30_000L))

        assertNull(detector.evaluateTotalFailure(DataSourceMode.AUTO, captivePortalWifi, okProbe(t0), t0))
        assertNull(detector.evaluateTotalFailure(DataSourceMode.AUTO, captivePortalWifi, okProbe(t0 + 30_000L), t0 + 30_000L))
    }

    @Test
    fun totalFailure_neverFires_whenNeutralInternetProbeFails() {
        val detector = GttServiceOutageDetector()
        val t0 = 1_750_000_000_000L

        assertNull(detector.evaluateTotalFailure(DataSourceMode.AUTO, healthyWifi, failedProbe(t0), t0))
        assertNull(detector.evaluateTotalFailure(DataSourceMode.AUTO, healthyWifi, failedProbe(t0 + 25_000L), t0 + 25_000L))
        assertNull(detector.evaluateTotalFailure(DataSourceMode.AUTO, healthyWifi, failedProbe(t0 + 55_000L), t0 + 55_000L))
    }

    @Test
    fun totalFailure_neverFires_whenUserPinnedSingleSourceMode() {
        val detector = GttServiceOutageDetector()
        val t0 = 1_750_000_000_000L

        assertNull(detector.evaluateTotalFailure(DataSourceMode.GTT_OFFICIAL, healthyWifi, okProbe(t0), t0))
        assertNull(detector.evaluateTotalFailure(DataSourceMode.GTT_OFFICIAL, healthyWifi, okProbe(t0 + 30_000L), t0 + 30_000L))
        assertNull(detector.evaluateTotalFailure(DataSourceMode.BUSONE_PROXY, healthyWifi, okProbe(t0 + 60_000L), t0 + 60_000L))
    }

    @Test
    fun totalFailure_confirmsOutageOnlyAfterPersistenceWithValidatedInternetAndNeutralProbe() {
        val detector = GttServiceOutageDetector()
        val t0 = 1_750_000_000_000L

        // 1st failure: must NOT trigger yet
        assertNull(detector.evaluateTotalFailure(DataSourceMode.AUTO, healthyWifi, okProbe(t0), t0))
        // 2nd failure too quickly (only 5s): must NOT trigger yet
        assertNull(detector.evaluateTotalFailure(DataSourceMode.AUTO, healthyWifi, okProbe(t0 + 5_000L), t0 + 5_000L))
        // Sustained failure after >= 18s with validated internet + neutral probe OK -> CONFIRMED
        val report = detector.evaluateTotalFailure(DataSourceMode.AUTO, healthyWifi, okProbe(t0 + 22_000L), t0 + 22_000L)
        assertNotNull(report)
        assertEquals(GttOutageKind.SERVERS_UNREACHABLE, report?.kind)

        // Any healthy live poll clears it immediately
        val tRecover = t0 + 50_000L
        val healthyVehicles = makeCityVehicles(30, 10, (tRecover / 1000L) - 10L, tRecover)
        val reports = mapOf(
            "GTT Ufficiale" to makeSourceReport("GTT Ufficiale", SourceMotionStatus.MOVING, 30, 18, false, 0L),
        )
        assertNull(
            detector.evaluateSuccess(
                vehicles = healthyVehicles,
                feedTimestampSeconds = tRecover / 1000L,
                sourceReports = reports,
                dataSourceMode = DataSourceMode.AUTO,
                connectivity = healthyWifi,
                nowMillis = tRecover,
            ),
        )
    }

    @Test
    fun staleFeed_neverFiresAtNightWithSmallFleet_orWhenSourceIsActivelyMoving() {
        val detector = GttServiceOutageDetector()
        val t0 = 1_750_000_000_000L
        val staleTs = (t0 / 1000L) - 650L // ~11 min old

        // Nighttime small fleet (6 buses on 3 lines) -> MUST NOT fire
        val nightBuses = makeCityVehicles(6, 3, staleTs, t0)
        assertNull(detector.evaluateSuccess(nightBuses, staleTs, emptyMap(), DataSourceMode.AUTO, healthyWifi, t0))
        assertNull(detector.evaluateSuccess(nightBuses, staleTs, emptyMap(), DataSourceMode.AUTO, healthyWifi, t0 + 50_000L))

        // Large fleet with old timestamps BUT MaTO source shows 12 buses physically moving -> clock skew, NOT outage
        val skewBuses = makeCityVehicles(35, 12, staleTs, t0)
        val movingReports = mapOf(
            "MaTO Live" to makeSourceReport("MaTO Live", SourceMotionStatus.MOVING, 35, 12, false, 0L),
        )
        assertNull(detector.evaluateSuccess(skewBuses, staleTs, movingReports, DataSourceMode.AUTO, healthyWifi, t0))
        assertNull(detector.evaluateSuccess(skewBuses, staleTs, movingReports, DataSourceMode.AUTO, healthyWifi, t0 + 50_000L))
    }

    @Test
    fun staleFeed_confirmsCitywideOutageAfterTwoPollsAndRecoversImmediately() {
        val detector = GttServiceOutageDetector()
        val t0 = 1_750_000_000_000L
        val staleTs = (t0 / 1000L) - 600L // 10 min old
        val staleVehicles = makeCityVehicles(30, 10, staleTs, t0)
        val warmupReports = mapOf(
            "GTT Ufficiale" to makeSourceReport("GTT Ufficiale", SourceMotionStatus.WARMING_UP, 30, 0, false, 0L),
        )

        // First stale poll -> pending confirmation
        assertNull(detector.evaluateSuccess(staleVehicles, staleTs, warmupReports, DataSourceMode.AUTO, healthyWifi, t0))

        // Second stale poll >= 40s later -> CONFIRMED
        val t1 = t0 + 45_000L
        val staleVehicles2 = makeCityVehicles(30, 10, staleTs, t1)
        val report = detector.evaluateSuccess(staleVehicles2, staleTs, warmupReports, DataSourceMode.AUTO, healthyWifi, t1)
        assertNotNull(report)
        assertEquals(GttOutageKind.CITYWIDE_STALE_FEED, report?.kind)

        // Recovery poll with fresh timestamps -> clears immediately
        val t2 = t1 + 25_000L
        val freshTs = (t2 / 1000L) - 12L
        val freshVehicles = makeCityVehicles(30, 10, freshTs, t2)
        val movingReports = mapOf(
            "GTT Ufficiale" to makeSourceReport("GTT Ufficiale", SourceMotionStatus.MOVING, 30, 15, false, 0L),
        )
        assertNull(detector.evaluateSuccess(freshVehicles, freshTs, movingReports, DataSourceMode.AUTO, healthyWifi, t2))
    }

    @Test
    fun frozenFleet_neverFiresWhenFallbackSourceIsMoving_andFiresOnlyWhenAllSourcesFrozen() {
        val detector = GttServiceOutageDetector()
        val t0 = 1_750_000_000_000L
        val freshTs = (t0 / 1000L) - 10L
        val vehicles = makeCityVehicles(40, 14, freshTs, t0)

        // GTT Official is frozen for 4 min, BUT MaTO Live has 9 buses moving -> NO outage!
        val mixedReports = mapOf(
            "GTT Ufficiale" to makeSourceReport("GTT Ufficiale", SourceMotionStatus.FROZEN, 40, 0, true, 240L),
            "MaTO Live" to makeSourceReport("MaTO Live", SourceMotionStatus.MOVING, 20, 9, false, 0L),
        )
        assertNull(detector.evaluateSuccess(vehicles, freshTs, mixedReports, DataSourceMode.AUTO, healthyWifi, t0))
        assertNull(detector.evaluateSuccess(vehicles, freshTs, mixedReports, DataSourceMode.AUTO, healthyWifi, t0 + 30_000L))

        // Now ALL responding sources are frozen for >= 180s with 0 moving buses
        val allFrozen = mapOf(
            "GTT Ufficiale" to makeSourceReport("GTT Ufficiale", SourceMotionStatus.FROZEN, 40, 0, true, 220L),
            "BusOne Proxy" to makeSourceReport("BusOne Proxy", SourceMotionStatus.FROZEN, 38, 0, true, 220L),
        )
        // 1st evaluation -> waits for 2nd confirmation
        assertNull(detector.evaluateSuccess(vehicles, freshTs, allFrozen, DataSourceMode.AUTO, healthyWifi, t0 + 60_000L))
        // 2nd consecutive evaluation -> CONFIRMED
        val report = detector.evaluateSuccess(vehicles, freshTs, allFrozen, DataSourceMode.AUTO, healthyWifi, t0 + 85_000L)
        assertNotNull(report)
        assertEquals(GttOutageKind.CITYWIDE_FROZEN_FLEET, report?.kind)
    }

    @Test
    fun diagnosticsLog_capsRingBufferFiltersCategoriesAndFormatsReport() {
        ToBusDiagnosticsLog.clear()
        repeat(260) { idx ->
            val cat = if (idx % 2 == 0) DiagCategory.POLL else DiagCategory.MQTT
            ToBusDiagnosticsLog.record(cat, "Event #$idx", detail = "d=$idx", severity = DiagSeverity.INFO)
        }
        // Ring buffer stays bounded (<= 220)
        assertEquals(220, ToBusDiagnosticsLog.count())

        val mqttOnly = ToBusDiagnosticsLog.snapshot(categories = setOf(DiagCategory.MQTT))
        assertTrue(mqttOnly.isNotEmpty())
        assertTrue(mqttOnly.all { it.category == DiagCategory.MQTT })
        // Newest-first ordering
        assertTrue(mqttOnly.first().seq > mqttOnly.last().seq)

        val formatted = ToBusDiagnosticsLog.formatReport(
            headerLines = listOf("App: ToBus Test"),
            categories = setOf(DiagCategory.POLL),
            maxEntries = 10,
        )
        assertTrue(formatted.contains("=== ToBus Diagnostic Report"))
        assertTrue(formatted.contains("App: ToBus Test"))
        assertTrue(formatted.contains("POLL:"))
    }
}
