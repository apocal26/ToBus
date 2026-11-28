package com.example.data.network

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.data.model.BusVehicle
import com.example.data.model.DataSourceMode
import com.example.data.model.DataSourceStatus
import com.example.data.model.GttDataOutageReport
import com.example.data.model.GttDepot
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.LineSummary
import com.example.data.model.LiveFeedSnapshot
import com.example.data.model.RoutePattern
import com.example.data.model.ServiceAlertInfo
import com.example.data.model.StopArrival
import com.example.data.model.TransitStop
import com.example.data.model.TripDelayInfo
import com.example.data.model.VehicleStopStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Multi-source Turin GTT aggregator inspired by Infomezzi:
 * 1. GTT Official GTFS-RT Vehicle Positions (percorsieorari.gtt.to.it)
 * 2. BusOne Normalized GTT GTFS-RT Proxy (proxy.busone.app)
 * 3. GTT Official / BusOne Trip Updates (real-time schedule delay & next stop)
 * 4. GTT Service Alerts (active line detours & warnings, throttled for low battery)
 * 5. MATO OpenTripPlanner GraphQL (cached route geometry)
 *
 * Merges vehicles per fleet ID keeping the freshest GPS timestamp, and tracks
 * coordinate movement across polls to detect stale/frozen onboard transponders.
 */
class GttRealtimeRepository private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val httpClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val routeHttpClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .build()
    }

    private val matoFastHttpClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        httpClient.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val matoMqttClient: MatoMqttLiveClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MatoMqttLiveClient { httpClient }
    }

    private val staticRouteStore: GttStaticRouteStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GttStaticRouteStore(appContext)
    }
    private val stopNameMemoryCache = ConcurrentHashMap<String, String>()
    private val routeCacheDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(appContext.cacheDir, "gtt_routes").apply { mkdirs() }
    }
    private val routeGeometryService: GttRouteGeometryService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GttRouteGeometryService(
            cacheDirProvider = { routeCacheDir },
            matoFastHttpClientProvider = { matoFastHttpClient },
            routeHttpClientProvider = { routeHttpClient },
            staticRouteStoreProvider = { staticRouteStore },
            stopNameCache = stopNameMemoryCache,
            userAgent = USER_AGENT,
            matoEndpoints = MATO_GRAPHQL_ENDPOINTS,
            overpassEndpoints = OVERPASS_ENDPOINTS,
        )
    }
    private val depotGeometryService: GttDepotGeometryService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GttDepotGeometryService(
            cacheFileProvider = { File(appContext.cacheDir, GttDepotGeometryService.CACHE_FILE_NAME) },
            httpClientProvider = { routeHttpClient },
            userAgent = USER_AGENT,
            overpassEndpoints = OVERPASS_ENDPOINTS,
        )
    }
    private val matoGraphQlClient: GttMatoGraphQlClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GttMatoGraphQlClient(
            httpClientProvider = { matoFastHttpClient },
            userAgent = USER_AGENT,
            catalogEndpoints = MATO_GRAPHQL_ENDPOINTS,
        )
    }
    private val widgetVehiclesCacheFile: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(appContext.cacheDir, "widget_line_vehicles_v1.json")
    }
    private val movementStateCacheFile: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(appContext.cacheDir, "vehicle_movement_state_v1.json")
    }
    private val activeLinesCacheFile: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(appContext.cacheDir, "active_lines_v1.tsv")
    }

    private data class MovementRecord(
        val lineCode: String,
        val anchorLat: Double,
        val anchorLon: Double,
        val lastLat: Double,
        val lastLon: Double,
        val lastMovedAtMillis: Long?,
        val firstSeenAtPositionMillis: Long,
        val lastObservedAtMillis: Long,
        val inferredBearing: Float? = null,
        val lastAnchorDistMeters: Double = 0.0,
        val lastStepMovedForward: Boolean = false,
        val tripId: String? = null,
    )

    private data class CachedSingleLineVehicles(
        val fetchedAtMillis: Long,
        val vehicles: List<BusVehicle>,
    )

    private data class CachedStopArrivals(
        val fetchedAtMillis: Long,
        val arrivals: List<StopArrival>,
    )

    private val pipelineLock = Any()
    private val movementTracker = ConcurrentHashMap<String, MovementRecord>()
    private val kinematicTracker = ConcurrentHashMap<String, GttVehicleMergeEngine.VehicleKinematicPose>()
    private val verifiedDropoutGraceCache = ConcurrentHashMap<String, GttVehicleMergeEngine.VerifiedBusGraceEntry>()
    private val sourceHealthTracker = SourceHealthTracker()

    @Volatile
    private var movementStateHydratedFromDisk: Boolean = false

    @Volatile
    private var lastMovementStateSavedAtMillis: Long = 0L

    private val outageDetector: GttServiceOutageDetector by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GttServiceOutageDetector(appContext) { matoFastHttpClient }
    }
    private val busDiagnosticTracker = BusDiagnosticTracker()
    private val singleLineLiveCache = ConcurrentHashMap<String, CachedSingleLineVehicles>()
    private val stopArrivalsCache = ConcurrentHashMap<String, CachedStopArrivals>()

    @Volatile
    private var lastSnapshot: LiveFeedSnapshot? = null

    @Volatile
    private var lastSourceReports: Map<String, SourceHealthReport> = emptyMap()

    @Volatile
    private var lastStopArrivalsSummary: String = "Nessuna fermata interrogata"

    @Volatile
    private var lastGttRawVehicles: List<BusVehicle> = emptyList()

    @Volatile
    private var lastGttFetchedAtMillis: Long = 0L

    @Volatile
    private var lastBusOneRawVehicles: List<BusVehicle> = emptyList()

    @Volatile
    private var lastBusOneFetchedAtMillis: Long = 0L

    @Volatile
    private var lastMatoGqlRawVehicles: List<BusVehicle> = emptyList()

    @Volatile
    private var lastMatoGqlFetchedAtMillis: Long = 0L

    @Volatile
    private var lastFocusLine: String? = null

    @Volatile
    private var lastDataSourceMode: DataSourceMode? = null

    @Volatile
    private var lastTripUpdates: List<TripDelayInfo> = emptyList()

    @Volatile
    private var lastTripUpdatesFetchMillis: Long = 0L

    @Volatile
    private var cachedAlerts: List<ServiceAlertInfo> = emptyList()

    @Volatile
    private var lastAlertsFetchMillis: Long = 0L

    data class DebugSourceMotionSummary(
        val sourceName: String,
        val statusLabel: String,
        val observedNonDepot: Int,
        val movingCount: Int,
        val stuckCount: Int,
        val frozenDurationSec: Long,
        val fleetFrozen: Boolean,
        val focusLineFrozen: Boolean,
    )

    data class DebugMqttSummary(
        val isActiveDesired: Boolean,
        val isConnected: Boolean,
        val profileLabel: String,
        val focusedLine: String,
        val rawPublishCount: Long,
        val parsedVehicleCount: Long,
        val cachedVehicles: Int,
        val subAckAccepted: Int,
        val subAckRejected: Int,
        val reconnectAttempts: Int,
        val lastSampleTopic: String?,
        val lastError: String?,
        val livePublishCount: Long = 0L,
        val retainedPublishCount: Long = 0L,
        val stagedRetainedCount: Int = 0,
    )

    data class DebugRuntimeTelemetry(
        val outageSnapshot: OutageDetectorSnapshot,
        val mqtt: DebugMqttSummary,
        val sourceMotion: List<DebugSourceMotionSummary>,
        val routeCacheRamCount: Int,
        val routeCacheDiskCount: Int,
        val movementTrackedCount: Int,
        val tripUpdatesCount: Int,
        val staticStoreSummary: String,
        val lastRouteLoadSummary: String,
        val lastStopArrivalsSummary: String,
        val matoGqlPreferredEndpointIdx: Int,
        val matoGqlPreferredQueryIdx: Int,
        val focusedLineBusTraces: List<FocusedBusDiagnosticTrace> = emptyList(),
        val recentDroppedBuses: List<DroppedBusDiagnosticTrace> = emptyList(),
        val depotPolygonsLoaded: Int = 0,
        val depotTotalCount: Int = 0,
        val verifiedGraceCount: Int = 0,
        val kinematicTrackedCount: Int = 0,
    )

    fun getCurrentOutageReport(): GttDataOutageReport? = outageDetector.currentActiveOutage()

    fun isSimulatedOutageEnabled(): Boolean = outageDetector.isSimulatedOutageEnabled()

    fun toggleSimulatedOutage(): GttDataOutageReport? {
        val next = !outageDetector.isSimulatedOutageEnabled()
        return outageDetector.setSimulatedOutage(next)
    }

    fun forceReconnectMatoMqtt(lineCode: String? = null): String {
        val target = lineCode?.takeIf { it.isNotBlank() } ?: lastFocusLine
        matoMqttClient.forceReconnect(target)
        return "Riconnessione MaTO MQTT avviata (linea ${target ?: "-"}, profilo ${matoMqttClient.activeProfileLabel()})."
    }

    fun recordMapUiHideState(
        lineCode: String,
        hideInactiveOnMap: Boolean,
        hiddenEntries: List<Pair<String, String>>,
    ) {
        busDiagnosticTracker.recordMapUiHideState(lineCode, hideInactiveOnMap, hiddenEntries)
    }

    fun notifyFocusLineSwitched(lineCode: String?) {
        busDiagnosticTracker.onLineSwitched(lineCode)
    }

    fun resetHealthAndOutageTrackers() {
        sourceHealthTracker.reset()
        movementTracker.clear()
        kinematicTracker.clear()
        verifiedDropoutGraceCache.clear()
        busDiagnosticTracker.reset()
        stopArrivalsCache.clear()
        outageDetector.reset()
        lastSourceReports = emptyMap()
        ToBusDiagnosticsLog.record(
            category = DiagCategory.DEBUG,
            message = "Azzerati tracker GPS bloccati, movimento, grace e rilevatore disservizi",
            severity = DiagSeverity.OK,
        )
    }

    fun getDebugRuntimeTelemetry(): DebugRuntimeTelemetry {
        val motion = lastSourceReports.values
            .sortedBy { it.sourceName }
            .map { r ->
                DebugSourceMotionSummary(
                    sourceName = r.sourceName,
                    statusLabel = when {
                        r.isFrozenOverall -> "FERMO (${r.frozenDurationSeconds}s)"
                        r.status == SourceMotionStatus.MOVING -> "IN MOTO"
                        else -> "WARMUP"
                    },
                    observedNonDepot = r.observedNonDepotCount,
                    movingCount = r.movingCount,
                    stuckCount = r.stuckVehicleIds.size,
                    frozenDurationSec = r.frozenDurationSeconds,
                    fleetFrozen = r.fleetFrozen,
                    focusLineFrozen = r.focusLineFrozen,
                )
            }
        val depots = GttFleetAnalyzer.TURIN_DEPOTS
        return DebugRuntimeTelemetry(
            outageSnapshot = outageDetector.getDiagnosticSnapshot(),
            mqtt = DebugMqttSummary(
                isActiveDesired = matoMqttClient.isActiveOrConnecting(),
                isConnected = matoMqttClient.isConnected(),
                profileLabel = matoMqttClient.activeProfileLabel(),
                focusedLine = matoMqttClient.focusedLineCode(),
                rawPublishCount = matoMqttClient.rawPublishCount(),
                parsedVehicleCount = matoMqttClient.parsedVehicleCount(),
                cachedVehicles = matoMqttClient.totalCachedVehicles(),
                subAckAccepted = matoMqttClient.subAckAcceptedCount(),
                subAckRejected = matoMqttClient.subAckRejectedCount(),
                reconnectAttempts = matoMqttClient.reconnectAttemptCount(),
                lastSampleTopic = matoMqttClient.lastSampleTopic(),
                lastError = matoMqttClient.lastError(),
                livePublishCount = matoMqttClient.livePublishCount(),
                retainedPublishCount = matoMqttClient.retainedPublishCount(),
                stagedRetainedCount = matoMqttClient.stagedRetainedCount(),
            ),
            sourceMotion = motion,
            routeCacheRamCount = routeGeometryService.cacheRamCount(),
            routeCacheDiskCount = routeGeometryService.cacheDiskCount(),
            movementTrackedCount = movementTracker.size,
            tripUpdatesCount = lastTripUpdates.size,
            staticStoreSummary = staticRouteStore.getCacheDiagnosticSummary(),
            lastRouteLoadSummary = routeGeometryService.lastRouteLoadSummary,
            lastStopArrivalsSummary = lastStopArrivalsSummary,
            matoGqlPreferredEndpointIdx = matoGraphQlClient.preferredVpEndpointIdx,
            matoGqlPreferredQueryIdx = matoGraphQlClient.preferredVpQueryIdx,
            focusedLineBusTraces = busDiagnosticTracker.getLatestFocusedBusTraces(),
            recentDroppedBuses = busDiagnosticTracker.getRecentDroppedBuses(),
            depotPolygonsLoaded = depots.count { it.hasVerifiedPolygon },
            depotTotalCount = depots.size,
            verifiedGraceCount = verifiedDropoutGraceCache.size,
            kinematicTrackedCount = kinematicTracker.size,
        )
    }

    /**
     * Runs an on-demand parallel probe of neutral internet connectivity + every GTT / BusOne / MaTO
     * data feed, recording detailed latencies and item counts into [ToBusDiagnosticsLog].
     */
    suspend fun runFullSourcesProbeNow(focusLine: String?): String = withContext(Dispatchers.IO) {
        val nowMillis = System.currentTimeMillis()
        val normLine = focusLine?.let { GtfsRtProtobufParser.normalizeGttLineCode(it) }
            ?.takeIf { it.isNotEmpty() && it != "?" }
        val conn = outageDetector.checkCurrentConnectivity()

        val summary = coroutineScope {
            val netDef = async { outageDetector.probeNeutralInternet(force = true, nowMillis = nowMillis) }
            val gttDef = async { fetchVehicleSource("gtt_official", "GTT Ufficiale", GTT_VP_URLS, nowMillis) }
            val busOneDef = async { fetchVehicleSource("busone_proxy", "BusOne Proxy", BUSONE_VP_URLS, nowMillis) }
            val tripDef = async { fetchTripUpdatesSource() }
            val alertsDef = async { fetchAlertsSource() }
            val matoGqlDef = async {
                if (normLine != null) {
                    val t0 = SystemClock.elapsedRealtime()
                    val v = runCatching { matoGraphQlClient.fetchVehiclePositions(normLine, nowMillis) }.getOrDefault(emptyList())
                    v.size to (SystemClock.elapsedRealtime() - t0)
                } else {
                    0 to 0L
                }
            }

            val net = netDef.await()
            val gtt = gttDef.await()
            val busOne = busOneDef.await()
            val trip = tripDef.await()
            val alerts = alertsDef.await()
            val (matoCount, matoMs) = matoGqlDef.await()

            val lines = buildList {
                add("Rete: ${conn.transportLabel} (validata=${conn.isValidated}, captive=${conn.isCaptivePortal}) · Probe neutrale: ${if (net.reachable) "OK" else "FAIL"} (${net.detail})")
                add("GTT Ufficiale: ${if (gtt.status.isOnline) "OK" else "FAIL"} · ${gtt.status.detail}")
                add("BusOne Proxy: ${if (busOne.status.isOnline) "OK" else "FAIL"} · ${busOne.status.detail}")
                add("TripUpdates: ${if (trip.status.isOnline) "OK" else "FAIL"} · ${trip.status.detail}")
                add("Avvisi GTT: ${if (alerts.status.isOnline) "OK" else "FAIL"} · ${alerts.status.itemCount} avvisi (${alerts.status.latencyMs}ms)")
                if (normLine != null) {
                    add("MaTO GraphQL (L.$normLine): $matoCount mezzi · ${matoMs}ms (ep#${matoGraphQlClient.preferredVpEndpointIdx}/q#${matoGraphQlClient.preferredVpQueryIdx})")
                }
                add("MaTO MQTT: conn=${matoMqttClient.isConnected()} · profilo=${matoMqttClient.activeProfileLabel()} · raw=${matoMqttClient.rawPublishCount()} · parsed=${matoMqttClient.parsedVehicleCount()} · subOk=${matoMqttClient.subAckAcceptedCount()}/rej=${matoMqttClient.subAckRejectedCount()}")
            }
            lines.joinToString("\n")
        }

        ToBusDiagnosticsLog.record(
            category = DiagCategory.DEBUG,
            message = "Test completo fonti completato",
            detail = summary.replace("\n", " || "),
            severity = DiagSeverity.OK,
        )
        summary
    }

    /**
     * Bypasses RAM/disk caches to trace route geometry loading for [lineCode] end-to-end.
     */
    suspend fun diagnoseRouteFetch(lineCode: String): String = withContext(Dispatchers.IO) {
        val norm = GtfsRtProtobufParser.normalizeGttLineCode(lineCode)
        val start = SystemClock.elapsedRealtime()
        val patterns = fetchRoutePatterns(norm, forceRefresh = true)
        val elapsed = SystemClock.elapsedRealtime() - start
        val totalStops = patterns.sumOf { it.stops.size }
        val totalPoints = patterns.sumOf { it.points.size }
        val dirs = patterns.joinToString(", ") { p ->
            "dir${p.directionId}=${p.terminusName() ?: p.name.take(18)}(${p.stops.size}f)"
        }.ifBlank { "nessun tracciato" }
        val msg = "Percorso L.$norm ricaricato in ${elapsed}ms: ${patterns.size} direzioni, $totalStops fermate, $totalPoints punti [$dirs] (${routeGeometryService.lastRouteLoadSummary})"
        ToBusDiagnosticsLog.record(
            category = DiagCategory.DEBUG,
            message = "Diagnostica percorso L.$norm",
            detail = msg,
            severity = if (patterns.isNotEmpty()) DiagSeverity.OK else DiagSeverity.WARN,
        )
        msg
    }

    fun startMatoLiveStream(lineCode: String, onFocusedUpdate: () -> Unit) {
        val cleaned = lineCode.trim().uppercase()
        if (cleaned.isEmpty()) return
        lastFocusLine = cleaned
        matoMqttClient.start(cleaned, onFocusedUpdate)
    }

    fun updateMatoLiveLine(lineCode: String) {
        val cleaned = lineCode.trim().uppercase()
        if (cleaned.isEmpty()) return
        lastFocusLine = cleaned
        matoMqttClient.setFocusedLine(cleaned)
    }

    fun stopMatoLiveStream() {
        matoMqttClient.stop()
    }

    private fun buildMatoConnectedIdleDetail(isFixedMato: Boolean, nowMillis: Long): String {
        val prefix = if (isFixedMato) "Fonte fissa · " else ""
        val rawMsgs = matoMqttClient.rawPublishCount()
        val parsed = matoMqttClient.parsedVehicleCount()
        val cached = matoMqttClient.totalCachedVehicles()
        val subOk = matoMqttClient.subAckAcceptedCount()
        val subRej = matoMqttClient.subAckRejectedCount()
        val sampleTopic = matoMqttClient.lastSampleTopic()?.take(24)
        val connSince = matoMqttClient.connectedSinceMillis()
        val connectedAgeMs = if (connSince > 0L) (nowMillis - connSince).coerceAtLeast(0L) else 0L
        return when {
            matoMqttClient.subAckRejectedAll() -> "${prefix}Topic rifiutati dal broker 5T ($subRej)"
            rawMsgs > 0L && parsed == 0L -> {
                val topicPart = sampleTopic?.let { " ($it)" }.orEmpty()
                "${prefix}$rawMsgs msg non riconosciuti$topicPart"
            }
            cached > 0 -> "${prefix}0 mezzi su linea ($cached in rete)"
            connectedAgeMs < 10_000L -> "${prefix}Connesso · in attesa dati…"
            subOk > 0 -> "${prefix}Connesso ($subOk topic OK · 0 msg da 5T)"
            else -> "${prefix}Connesso · 0 msg dal broker 5T"
        }
    }

    suspend fun clearLocalCaches(): Int = withContext(Dispatchers.IO) {
        stopNameMemoryCache.clear()
        movementTracker.clear()
        verifiedDropoutGraceCache.clear()
        var deleted = routeGeometryService.clearCaches()
        deleted += depotGeometryService.clearCache()
        runCatching { if (activeLinesCacheFile.exists() && activeLinesCacheFile.delete()) deleted++ }
        runCatching { if (widgetVehiclesCacheFile.exists() && widgetVehiclesCacheFile.delete()) deleted++ }
        runCatching { if (movementStateCacheFile.exists() && movementStateCacheFile.delete()) deleted++ }
        deleted
    }

    /**
     * Fast in-memory merge of fresh MaTO MQTT live positions over the latest snapshot,
     * allowing active buses to glide smoothly on the map in real time between HTTP polls.
     */
    fun getLiveMatoOverlaySnapshot(
        dataSourceMode: DataSourceMode = DataSourceMode.AUTO,
    ): LiveFeedSnapshot? {
        if (!shouldRunMatoStream(dataSourceMode)) return null
        val isFixedMato = dataSourceMode == DataSourceMode.MATO_LIVE
        val connected = matoMqttClient.isConnected()
        val lastErr = matoMqttClient.lastError()

        return synchronized(pipelineLock) {
            val nowMillis = System.currentTimeMillis()
            val matoLive = matoMqttClient.snapshotFreshVehicles(nowMillis)
            val matoRetained = matoMqttClient.snapshotRetainedCandidates(nowMillis)
            if (matoLive.isEmpty() && !connected && !(isFixedMato && lastErr != null)) {
                return@synchronized null
            }

            val base = lastSnapshot
            if (matoLive.isEmpty() && base != null) {
                val matoStatus = DataSourceStatus(
                    id = "mato_live",
                    name = "MaTO Live",
                    isOnline = connected,
                    latencyMs = 0L,
                    itemCount = 0,
                    detail = if (connected) {
                        buildMatoConnectedIdleDetail(isFixedMato, nowMillis)
                    } else {
                        "Fonte fissa · ${lastErr ?: "Disconnesso"}"
                    },
                )
                val filteredBaseStatuses = if (isFixedMato) {
                    base.sourceStatuses.filterNot { it.id == "mato_live" || it.id == "gtt_official" || it.id == "busone_proxy" }
                } else {
                    base.sourceStatuses.filterNot { it.id == "mato_live" }
                }
                val updatedStatuses = listOf(matoStatus) + filteredBaseStatuses
                val updated = if (isFixedMato) {
                    base.copy(
                        allVehicles = base.allVehicles.filter { it.sourceName == "MaTO Live" && !it.isMqttRetained },
                        sourceStatuses = updatedStatuses,
                    )
                } else {
                    base.copy(sourceStatuses = updatedStatuses)
                }
                lastSnapshot = updated
                return@synchronized updated
            }

            val freshMatoGql = if (nowMillis - lastMatoGqlFetchedAtMillis <= 95_000L) {
                lastMatoGqlRawVehicles
            } else {
                emptyList()
            }
            val matoCombined = mergeMultiSourceVehicles(
                matoVehicles = matoLive,
                gttVehicles = freshMatoGql,
                busOneVehicles = emptyList(),
                nowMillis = nowMillis,
            )

            val gttInputs = if (isFixedMato) {
                emptyList()
            } else {
                filterFreshOverlayVehicles(lastGttRawVehicles, lastGttFetchedAtMillis, nowMillis)
            }
            val busOneInputs = if (isFixedMato) {
                emptyList()
            } else {
                filterFreshOverlayVehicles(lastBusOneRawVehicles, lastBusOneFetchedAtMillis, nowMillis)
            }

            val activeFocus = lastFocusLine
            val rawReports = HashMap<String, SourceHealthReport>(3)
            if (matoCombined.isNotEmpty()) {
                rawReports["MaTO Live"] = sourceHealthTracker.recordAndEvaluate("MaTO Live", matoCombined, activeFocus, nowMillis)
            }
            if (gttInputs.isNotEmpty()) {
                rawReports["GTT Ufficiale"] = sourceHealthTracker.peekReport("GTT Ufficiale", gttInputs, activeFocus, nowMillis)
            }
            if (busOneInputs.isNotEmpty()) {
                rawReports["BusOne Proxy"] = sourceHealthTracker.peekReport("BusOne Proxy", busOneInputs, activeFocus, nowMillis)
            }
            val sourceReports = SourceHealthTracker.refineWithCrossSourceComparison(rawReports)

            if (lastDataSourceMode != null && lastDataSourceMode != dataSourceMode) {
                verifiedDropoutGraceCache.clear()
            }
            lastDataSourceMode = dataSourceMode

            val gttFrozenNow = !isFixedMato &&
                sourceReports["GTT Ufficiale"]?.isFrozenForLine(activeFocus ?: "", activeFocus) == true
            val busOneFrozenNow = !isFixedMato &&
                sourceReports["BusOne Proxy"]?.isFrozenForLine(activeFocus ?: "", activeFocus) == true
            val anyGtfsFrozen = gttFrozenNow || busOneFrozenNow
            val healthyOfficialGtfsOnline = !isFixedMato && (
                (gttInputs.isNotEmpty() && !gttFrozenNow) ||
                    (busOneInputs.isNotEmpty() && !busOneFrozenNow)
                )
            val pipelineDecisions = ArrayList<PipelineBusDecision>()
            val decisionSink: (PipelineBusDecision) -> Unit = { d ->
                if (activeFocus != null && d.lineCode.equals(activeFocus, ignoreCase = true)) {
                    pipelineDecisions.add(d)
                }
            }

            // Scope the 1.5s MQTT overlay ONLY to the focused line + any vehicle touched by MaTO MQTT
            // when a base citywide snapshot already exists. This avoids re-evaluating ~300 unrelated
            // citywide HTTP buses every 1.5s (15x less CPU and zero spurious quarantines on other lines).
            val useScopedOverlay = base != null && !isFixedMato && !activeFocus.isNullOrBlank()
            val matoTouchedIds = if (useScopedOverlay) {
                HashSet<String>(matoCombined.size + matoRetained.size).apply {
                    for (v in matoCombined) {
                        val cid = GtfsRtProtobufParser.canonicalFleetId(v.vehicleId)
                        if (cid.isNotEmpty()) add(cid)
                    }
                    for (v in matoRetained) {
                        val cid = GtfsRtProtobufParser.canonicalFleetId(v.vehicleId)
                        if (cid.isNotEmpty()) add(cid)
                    }
                }
            } else {
                emptySet()
            }
            fun isInOverlayScope(v: BusVehicle): Boolean {
                if (!useScopedOverlay || activeFocus.isNullOrBlank()) return true
                if (v.lineCode.equals(activeFocus, ignoreCase = true) ||
                    v.rawRouteId.equals(activeFocus, ignoreCase = true)
                ) {
                    return true
                }
                val cid = GtfsRtProtobufParser.canonicalFleetId(v.vehicleId)
                return cid.isNotEmpty() && cid in matoTouchedIds
            }

            val scopedGttInputs = if (useScopedOverlay) gttInputs.filter(::isInOverlayScope) else gttInputs
            val scopedBusOneInputs = if (useScopedOverlay) busOneInputs.filter(::isInOverlayScope) else busOneInputs

            val effectiveMatoForMerge = GttVehicleMergeEngine.filterStaleUncorroboratedMato(
                matoVehicles = matoCombined,
                gttVehicles = scopedGttInputs,
                busOneVehicles = scopedBusOneInputs,
                healthyOfficialGtfsOnline = healthyOfficialGtfsOnline,
                nowMillis = nowMillis,
                isWidgetFastMode = false,
                onDecision = decisionSink,
            )

            val mergedScope = mergeMultiSourceVehicles(
                matoVehicles = effectiveMatoForMerge,
                gttVehicles = scopedGttInputs,
                busOneVehicles = scopedBusOneInputs,
                nowMillis = nowMillis,
                focusLine = activeFocus,
                sourceReports = sourceReports,
                previousPoses = kinematicTracker,
                matoRetainedCandidates = if (isFixedMato) emptyList() else matoRetained,
                onDecision = decisionSink,
            )
            val quarantinedIds = HashSet<String>(4)
            val kinematicallySafeScope = GttVehicleMergeEngine.evaluateKinematicAnomalies(
                vehicles = mergedScope,
                kinematicPoses = kinematicTracker,
                nowMillis = nowMillis,
                onDecision = decisionSink,
            ) { bus, reason ->
                quarantinedIds.add(GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId.trim() })
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.VEHICLE,
                    message = "#${bus.label} L.${bus.lineCode} QUARANTENA CINEMATICA: $reason",
                    detail = "src=${compactSourceTag(bus.sourceName)} [MQTT_OVERLAY]",
                    severity = DiagSeverity.WARN,
                    timestampMillis = nowMillis,
                )
            }
            val enrichedScopeRaw = enrichWithTripUpdatesAndMovement(kinematicallySafeScope, lastTripUpdates, nowMillis)
            val enrichedScope = GttVehicleMergeEngine.applyVerifiedDropoutGrace(
                freshVehicles = enrichedScopeRaw,
                graceCache = verifiedDropoutGraceCache,
                focusLine = activeFocus,
                nowMillis = nowMillis,
                quarantinedIds = quarantinedIds,
                onDecision = decisionSink,
            )

            val enrichedAll = if (useScopedOverlay && !activeFocus.isNullOrBlank()) {
                val replacedIds = HashSet<String>(enrichedScope.size + matoTouchedIds.size + quarantinedIds.size).apply {
                    addAll(matoTouchedIds)
                    addAll(quarantinedIds)
                    for (v in enrichedScope) {
                        val cid = GtfsRtProtobufParser.canonicalFleetId(v.vehicleId)
                        if (cid.isNotEmpty()) add(cid)
                    }
                }
                val combined = ArrayList<BusVehicle>(base.allVehicles.size + enrichedScope.size)
                for (existing in base.allVehicles) {
                    val isFocus = existing.lineCode.equals(activeFocus, ignoreCase = true) ||
                        existing.rawRouteId.equals(activeFocus, ignoreCase = true)
                    if (isFocus) continue
                    val cid = GtfsRtProtobufParser.canonicalFleetId(existing.vehicleId)
                    if (cid.isNotEmpty() && cid in replacedIds) continue
                    combined.add(existing)
                }
                combined.addAll(enrichedScope)
                combined
            } else {
                enrichedScope
            }

            if (!activeFocus.isNullOrBlank()) {
                busDiagnosticTracker.evaluateCycle(
                    focusLine = activeFocus,
                    trigger = "MQTT_OVERLAY",
                    rawGtt = scopedGttInputs,
                    rawBusOne = scopedBusOneInputs,
                    rawMatoLive = matoLive,
                    rawMatoGql = freshMatoGql,
                    rawMatoRetained = if (isFixedMato) emptyList() else matoRetained,
                    mergedAndGraceVehicles = enrichedScope,
                    routePatterns = routeGeometryService.getMemoryCachedPatterns(activeFocus),
                    healthyOfficialGtfsOnline = healthyOfficialGtfsOnline,
                    pipelineDecisions = pipelineDecisions,
                    nowMillis = nowMillis,
                )
            }
            val matoStatus = DataSourceStatus(
                id = "mato_live",
                name = "MaTO Live",
                isOnline = true,
                latencyMs = 0L,
                itemCount = matoCombined.size,
                detail = when {
                    isFixedMato -> "${matoCombined.size} mezzi · Fonte fissa"
                    anyGtfsFrozen -> "${matoCombined.size} mezzi · Priorità attiva"
                    else -> "${matoCombined.size} mezzi · MQTT Live"
                },
            )
            val existingStatuses = base?.sourceStatuses
                ?.filterNot { status ->
                    status.id == "mato_live" ||
                        (isFixedMato && (status.id == "gtt_official" || status.id == "busone_proxy"))
                }
                ?.map { status ->
                    val report = sourceReports[status.name]
                    if (!isFixedMato && report != null && report.isFrozenForLine(activeFocus ?: "", activeFocus)) {
                        status.copy(detail = "GPS fermi · fallback attivo")
                    } else {
                        status
                    }
                }
                .orEmpty()
            val updatedStatuses = listOf(matoStatus) + existingStatuses

            val matoMaxTs = matoCombined.maxOfOrNull { it.timestampSeconds } ?: 0L
            val feedTs = maxOf(base?.feedTimestampSeconds ?: 0L, matoMaxTs)

            val nextSummaries = if (base != null && base.availableLines.isNotEmpty() && !activeFocus.isNullOrBlank()) {
                updateLineSummariesForFocusLine(base.availableLines, enrichedAll, activeFocus)
            } else {
                GtfsRtProtobufParser.buildLineSummaries(enrichedAll)
            }

            val updated = LiveFeedSnapshot(
                feedTimestampSeconds = feedTs,
                fetchedAtMillis = nowMillis,
                allVehicles = enrichedAll,
                availableLines = nextSummaries,
                sourceStatuses = updatedStatuses,
                alerts = base?.alerts ?: cachedAlerts,
                outageReport = base?.outageReport,
                healthyOfficialGtfsOnline = healthyOfficialGtfsOnline,
            )
            lastSnapshot = updated
            updated
        }
    }

    suspend fun fetchLiveVehicles(
        includeSecondaryFeeds: Boolean = true,
        widgetFastMode: Boolean = false,
        focusLine: String? = null,
        dataSourceMode: DataSourceMode = DataSourceMode.AUTO,
        includeAlerts: Boolean = true,
    ): Result<LiveFeedSnapshot> =
        withContext(Dispatchers.IO) {
            val nowMillis = System.currentTimeMillis()
            val normFocus = focusLine?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
            if (normFocus != null) {
                lastFocusLine = normFocus
            }

            // When no line is selected yet (initial launch), always query at least one citywide feed
            // so the Line Picker can display all active Torino lines immediately.
            val effectiveMode = if (normFocus == null && dataSourceMode == DataSourceMode.MATO_LIVE) {
                DataSourceMode.AUTO
            } else {
                dataSourceMode
            }
            val useMato = shouldRunMatoStream(effectiveMode)
            val useGtt = effectiveMode == DataSourceMode.AUTO || effectiveMode == DataSourceMode.GTT_OFFICIAL
            val useBusOne = (effectiveMode == DataSourceMode.AUTO && (includeSecondaryFeeds || widgetFastMode)) ||
                effectiveMode == DataSourceMode.BUSONE_PROXY
            val allowCrossSourceFallback = effectiveMode == DataSourceMode.AUTO

            if (!widgetFastMode && useMato && normFocus != null) {
                matoMqttClient.ensureHealthyConnection(nowMillis)
            }

            if (lastDataSourceMode != null && lastDataSourceMode != effectiveMode) {
                verifiedDropoutGraceCache.clear()
            }
            lastDataSourceMode = effectiveMode

            val gttTimeoutMs = if (widgetFastMode) 5_800L else 8_000L
            val busOneTimeoutMs = if (widgetFastMode) 5_800L else 7_000L
            val matoGqlTimeoutMs = if (widgetFastMode) 4_500L else 6_000L

            coroutineScope {
                val gttJob = if (useGtt) {
                    async {
                        withTimeoutOrNull(gttTimeoutMs) {
                            fetchVehicleSource("gtt_official", "GTT Ufficiale", GTT_VP_URLS, nowMillis)
                        } ?: VehicleSourceResult(
                            snapshot = null,
                            status = DataSourceStatus(
                                id = "gtt_official",
                                name = "GTT Ufficiale",
                                isOnline = false,
                                latencyMs = gttTimeoutMs,
                                itemCount = 0,
                                detail = "Timeout",
                            ),
                            error = IOException("Timeout GTT Ufficiale"),
                        )
                    }
                } else {
                    null
                }
                val busOneJob = if (useBusOne) {
                    async {
                        withTimeoutOrNull(busOneTimeoutMs) {
                            fetchVehicleSource("busone_proxy", "BusOne Proxy", BUSONE_VP_URLS, nowMillis)
                        } ?: VehicleSourceResult(
                            snapshot = null,
                            status = DataSourceStatus(
                                id = "busone_proxy",
                                name = "BusOne Proxy",
                                isOnline = false,
                                latencyMs = busOneTimeoutMs,
                                itemCount = 0,
                                detail = "Timeout",
                            ),
                            error = IOException("Timeout BusOne Proxy"),
                        )
                    }
                } else {
                    null
                }
                // Skip redundant MaTO GraphQL VP poll in foreground when MQTT is already connected and
                // actively streaming live vehicles for the focused line (saves 6x2 HTTP calls every 25s).
                val mqttStreamingFocus = !widgetFastMode &&
                    matoMqttClient.isConnected() &&
                    !normFocus.isNullOrBlank() &&
                    matoMqttClient.snapshotFreshVehicles(nowMillis).any {
                        it.lineCode.equals(normFocus, ignoreCase = true) ||
                            it.rawRouteId.equals(normFocus, ignoreCase = true)
                    }
                val matoGqlJob = if (useMato && !normFocus.isNullOrBlank() && !mqttStreamingFocus) {
                    async {
                        withTimeoutOrNull(matoGqlTimeoutMs) {
                            matoGraphQlClient.fetchVehiclePositions(normFocus, nowMillis)
                        }.orEmpty()
                    }
                } else {
                    null
                }
                val tripUpdatesJob = if (includeSecondaryFeeds && !widgetFastMode) {
                    async {
                        withTimeoutOrNull(6_000L) { fetchTripUpdatesSource() }
                    }
                } else {
                    null
                }
                val alertsTtl = if (cachedAlerts.isEmpty()) 45_000L else ALERTS_TTL_MS
                val alertsJob = if (includeAlerts && includeSecondaryFeeds && !widgetFastMode && nowMillis - lastAlertsFetchMillis > alertsTtl) {
                    async {
                        withTimeoutOrNull(7_500L) { fetchAlertsSource() }
                    }
                } else {
                    null
                }

                val gttRes = gttJob?.await()
                var busOneRes = busOneJob?.await()
                var matoGqlVehicles = matoGqlJob?.await().orEmpty()
                val tripRes = tripUpdatesJob?.await()
                val alertsRes = alertsJob?.await()

                var matoMqttVehicles = if (!widgetFastMode && useMato) {
                    matoMqttClient.snapshotFreshVehicles(nowMillis)
                } else {
                    emptyList()
                }
                // In fixed MATO_LIVE mode, give a newly opened MQTT WebSocket up to 2.4s to finish
                // TLS + CONNACK + SUBSCRIBE + first vehicle burst before returning an empty poll.
                if (effectiveMode == DataSourceMode.MATO_LIVE &&
                    !widgetFastMode &&
                    matoMqttVehicles.isEmpty() &&
                    matoGqlVehicles.isEmpty() &&
                    matoMqttClient.isActiveOrConnecting()
                ) {
                    val waitDeadline = System.currentTimeMillis() + 2_400L
                    while (System.currentTimeMillis() < waitDeadline) {
                        delay(200L)
                        val fresh = matoMqttClient.snapshotFreshVehicles(System.currentTimeMillis())
                        if (fresh.isNotEmpty()) {
                            matoMqttVehicles = fresh
                            break
                        }
                    }
                }
                var matoCombined = if (useMato) {
                    mergeMultiSourceVehicles(
                        matoVehicles = matoMqttVehicles,
                        gttVehicles = matoGqlVehicles,
                        busOneVehicles = emptyList(),
                        nowMillis = nowMillis,
                    )
                } else {
                    emptyList()
                }

                val gttVehicles = if (useGtt) gttRes?.snapshot?.allVehicles.orEmpty() else emptyList()
                var busOneVehicles = if (useBusOne) busOneRes?.snapshot?.allVehicles.orEmpty() else emptyList()

                // Track real physical GPS movement per individual source to detect frozen feeds
                // (e.g. VSGTT/GTFS-RT advancing timestamps while all coordinates remain frozen).
                val rawReports = HashMap<String, SourceHealthReport>(3)
                if (gttVehicles.isNotEmpty()) {
                    rawReports["GTT Ufficiale"] = sourceHealthTracker.recordAndEvaluate("GTT Ufficiale", gttVehicles, normFocus, nowMillis)
                }
                if (busOneVehicles.isNotEmpty()) {
                    rawReports["BusOne Proxy"] = sourceHealthTracker.recordAndEvaluate("BusOne Proxy", busOneVehicles, normFocus, nowMillis)
                }
                if (matoCombined.isNotEmpty()) {
                    rawReports["MaTO Live"] = sourceHealthTracker.recordAndEvaluate("MaTO Live", matoCombined, normFocus, nowMillis)
                }
                var sourceReports = SourceHealthTracker.refineWithCrossSourceComparison(rawReports)

                val gttFrozen = sourceReports["GTT Ufficiale"]?.isFrozenForLine(normFocus ?: "", normFocus) == true
                val busOneFrozen = sourceReports["BusOne Proxy"]?.isFrozenForLine(normFocus ?: "", normFocus) == true
                val anyGtfsFrozen = gttFrozen || busOneFrozen

                // Active lightweight fallback ONLY in AUTO mode when a GTFS-RT source is detected as frozen,
                // OR single-endpoint MaTO retry when fixed on MATO_LIVE and first query returned empty.
                if (allowCrossSourceFallback && anyGtfsFrozen) {
                    if (!widgetFastMode) {
                        val hasFocusedMqtt = normFocus != null && matoMqttVehicles.any {
                            it.lineCode.equals(normFocus, ignoreCase = true) ||
                                it.rawRouteId.equals(normFocus, ignoreCase = true)
                        }
                        if (!hasFocusedMqtt) {
                            matoMqttClient.nudgeFocusedLineSubscription(normFocus, nowMillis)
                        }
                    }

                    val retryMatoDeferred = if (!normFocus.isNullOrBlank() && matoGqlVehicles.isEmpty()) {
                        matoGraphQlClient.resetPreferredEndpoints()
                        async {
                            withTimeoutOrNull(3_500L) {
                                matoGraphQlClient.fetchVehiclePositions(normFocus, nowMillis)
                            }.orEmpty()
                        }
                    } else {
                        null
                    }
                    val fallbackBusOneDeferred = if (busOneRes == null && gttFrozen) {
                        async {
                            withTimeoutOrNull(4_500L) {
                                fetchVehicleSource("busone_proxy", "BusOne Proxy", BUSONE_VP_URLS, nowMillis)
                            }
                        }
                    } else {
                        null
                    }

                    val retriedMato = retryMatoDeferred?.await().orEmpty()
                    if (retriedMato.isNotEmpty()) {
                        matoGqlVehicles = retriedMato
                        matoCombined = mergeMultiSourceVehicles(
                            matoVehicles = matoMqttVehicles,
                            gttVehicles = matoGqlVehicles,
                            busOneVehicles = emptyList(),
                            nowMillis = System.currentTimeMillis(),
                        )
                        rawReports["MaTO Live"] = sourceHealthTracker.recordAndEvaluate("MaTO Live", matoCombined, normFocus, System.currentTimeMillis())
                    }
                    val fallbackBusOne = fallbackBusOneDeferred?.await()
                    if (fallbackBusOne != null) {
                        busOneRes = fallbackBusOne
                        busOneVehicles = fallbackBusOne.snapshot?.allVehicles.orEmpty()
                        if (busOneVehicles.isNotEmpty()) {
                            rawReports["BusOne Proxy"] = sourceHealthTracker.recordAndEvaluate("BusOne Proxy", busOneVehicles, normFocus, System.currentTimeMillis())
                        }
                    }
                    sourceReports = SourceHealthTracker.refineWithCrossSourceComparison(rawReports)
                } else if (effectiveMode == DataSourceMode.MATO_LIVE && !normFocus.isNullOrBlank() && matoCombined.isEmpty()) {
                    if (!widgetFastMode) {
                        matoMqttClient.nudgeFocusedLineSubscription(normFocus, nowMillis)
                    }
                }

                val finalGttFrozen = allowCrossSourceFallback &&
                    sourceReports["GTT Ufficiale"]?.isFrozenForLine(normFocus ?: "", normFocus) == true
                val finalBusOneFrozen = allowCrossSourceFallback &&
                    sourceReports["BusOne Proxy"]?.isFrozenForLine(normFocus ?: "", normFocus) == true
                val finalMatoFrozen = allowCrossSourceFallback &&
                    sourceReports["MaTO Live"]?.isFrozenForLine(normFocus ?: "", normFocus) == true

                val statuses = mutableListOf<DataSourceStatus>()
                val matoConnecting = useMato && !widgetFastMode && matoMqttClient.isActiveOrConnecting()
                val matoOnline = useMato && ((!widgetFastMode && matoMqttClient.isConnected()) || matoCombined.isNotEmpty())
                if (useMato && (!widgetFastMode || matoGqlVehicles.isNotEmpty() || effectiveMode == DataSourceMode.MATO_LIVE)) {
                    val matoMode = when {
                        effectiveMode == DataSourceMode.MATO_LIVE && matoCombined.isNotEmpty() -> "Fonte fissa"
                        effectiveMode == DataSourceMode.MATO_LIVE && !widgetFastMode && matoMqttClient.isConnected() ->
                            buildMatoConnectedIdleDetail(isFixedMato = true, nowMillis = nowMillis)
                        effectiveMode == DataSourceMode.MATO_LIVE && matoConnecting ->
                            "Fonte fissa · ${matoMqttClient.lastError() ?: "Connessione in corso…"}"
                        finalMatoFrozen && matoCombined.isNotEmpty() -> "GPS fermi · fallback attivo"
                        (finalGttFrozen || finalBusOneFrozen) && matoCombined.isNotEmpty() -> "Priorità attiva"
                        matoMqttVehicles.isNotEmpty() && matoGqlVehicles.isNotEmpty() -> "MQTT + GraphQL"
                        matoMqttVehicles.isNotEmpty() -> "MQTT Live"
                        matoGqlVehicles.isNotEmpty() -> "GraphQL Live"
                        !widgetFastMode && matoMqttClient.isConnected() ->
                            buildMatoConnectedIdleDetail(isFixedMato = false, nowMillis = nowMillis)
                        else -> matoMqttClient.lastError() ?: "In connessione…"
                    }
                    statuses.add(
                        DataSourceStatus(
                            id = "mato_live",
                            name = "MaTO Live",
                            isOnline = matoOnline,
                            latencyMs = 0L,
                            itemCount = matoCombined.size,
                            detail = if (matoCombined.isNotEmpty() && !finalMatoFrozen) {
                                "${matoCombined.size} mezzi · $matoMode"
                            } else {
                                matoMode
                            },
                        ),
                    )
                }
                gttRes?.let { res ->
                    statuses.add(
                        when {
                            effectiveMode == DataSourceMode.GTT_OFFICIAL && res.status.isOnline ->
                                res.status.copy(detail = "${gttVehicles.size} mezzi · Fonte fissa")
                            finalGttFrozen && res.status.isOnline ->
                                res.status.copy(detail = "GPS fermi · fallback attivo")
                            else -> res.status
                        },
                    )
                }
                busOneRes?.let { res ->
                    statuses.add(
                        when {
                            effectiveMode == DataSourceMode.BUSONE_PROXY && res.status.isOnline ->
                                res.status.copy(detail = "${busOneVehicles.size} mezzi · Fonte fissa")
                            finalBusOneFrozen && res.status.isOnline ->
                                res.status.copy(detail = "GPS fermi · fallback attivo")
                            finalGttFrozen && !finalBusOneFrozen && res.status.isOnline ->
                                res.status.copy(detail = "${busOneVehicles.size} mezzi · Fallback attivo")
                            else -> res.status
                        },
                    )
                }
                tripRes?.let {
                    statuses.add(it.status)
                    if (it.updates.isNotEmpty()) {
                        lastTripUpdates = it.updates
                        lastTripUpdatesFetchMillis = nowMillis
                    }
                }
                if (includeAlerts) {
                    if (alertsRes != null) {
                        if (alertsRes.alerts != null) {
                            cachedAlerts = alertsRes.alerts
                            lastAlertsFetchMillis = nowMillis
                        }
                        statuses.add(alertsRes.status)
                    } else if (includeSecondaryFeeds && !widgetFastMode) {
                        statuses.add(
                            DataSourceStatus(
                                id = "gtt_alerts",
                                name = "Avvisi GTT",
                                isOnline = true,
                                latencyMs = 0L,
                                itemCount = cachedAlerts.size,
                                detail = "Cache locale",
                            ),
                        )
                    }
                }

                val primarySnap = gttRes?.snapshot
                val secondarySnap = busOneRes?.snapshot

                lastSourceReports = sourceReports

                when (effectiveMode) {
                    DataSourceMode.GTT_OFFICIAL -> {
                        if (primarySnap == null) {
                            val err = gttRes?.error ?: IOException("Feed GTT Ufficiale non raggiungibile")
                            if (!widgetFastMode) {
                                outageDetector.onTotalFeedFailure(effectiveMode, err.message, nowMillis)
                                ToBusDiagnosticsLog.record(
                                    category = DiagCategory.POLL,
                                    message = "Poll fallito (GTT Ufficiale fisso)",
                                    detail = err.message,
                                    severity = DiagSeverity.ERROR,
                                )
                            }
                            return@coroutineScope Result.failure(err)
                        }
                    }
                    DataSourceMode.BUSONE_PROXY -> {
                        if (secondarySnap == null) {
                            val err = busOneRes?.error ?: IOException("Feed BusOne Proxy non raggiungibile")
                            if (!widgetFastMode) {
                                outageDetector.onTotalFeedFailure(effectiveMode, err.message, nowMillis)
                                ToBusDiagnosticsLog.record(
                                    category = DiagCategory.POLL,
                                    message = "Poll fallito (BusOne Proxy fisso)",
                                    detail = err.message,
                                    severity = DiagSeverity.ERROR,
                                )
                            }
                            return@coroutineScope Result.failure(err)
                        }
                    }
                    DataSourceMode.MATO_LIVE -> {
                        if (matoCombined.isEmpty() && !matoOnline && !matoConnecting) {
                            val err = IOException("Feed MaTO Live non raggiungibile")
                            if (!widgetFastMode) {
                                outageDetector.onTotalFeedFailure(effectiveMode, err.message, nowMillis)
                                ToBusDiagnosticsLog.record(
                                    category = DiagCategory.POLL,
                                    message = "Poll fallito (MaTO Live fisso)",
                                    detail = matoMqttClient.lastError(),
                                    severity = DiagSeverity.ERROR,
                                )
                            }
                            return@coroutineScope Result.failure(err)
                        }
                    }
                    DataSourceMode.AUTO -> {
                        if (primarySnap == null && secondarySnap == null && matoCombined.isEmpty()) {
                            val err = gttRes?.error ?: busOneRes?.error
                                ?: IOException("Tutte le fonti GTT/MaTO sono irraggiungibili")
                            if (!widgetFastMode) {
                                outageDetector.onTotalFeedFailure(effectiveMode, err.message, nowMillis)
                                ToBusDiagnosticsLog.record(
                                    category = DiagCategory.POLL,
                                    message = "Poll AUTO fallito: tutte le fonti irraggiungibili",
                                    detail = "gtt=${gttRes?.error?.message ?: "n/d"} busOne=${busOneRes?.error?.message ?: "n/d"} mqtt=${matoMqttClient.lastError() ?: "off"}",
                                    severity = DiagSeverity.ERROR,
                                )
                            }
                            return@coroutineScope Result.failure(err)
                        }
                    }
                }

                val activeTripUpdates = tripRes?.updates ?: lastTripUpdates
                val healthyOfficialGtfsOnline = (gttVehicles.isNotEmpty() && !finalGttFrozen) ||
                    (busOneVehicles.isNotEmpty() && !finalBusOneFrozen)

                // Serialize raw-feed cache updates + merge + kinematic + movement + grace + diagnostics +
                // lastSnapshot publication inside pipelineLock using a FRESH post-HTTP commit timestamp so
                // concurrent [POLL] and [MQTT_OVERLAY] ticks never race or compute negative/1s wall deltas!
                data class CommittedPollResult(
                    val commitNowMillis: Long,
                    val enriched: List<BusVehicle>,
                    val feedTs: Long,
                    val outageReport: GttDataOutageReport?,
                    val finalSnapshot: LiveFeedSnapshot,
                    val rawSummaries: List<LineSummary>,
                )

                val committed = synchronized(pipelineLock) {
                    val commitNowMillis = System.currentTimeMillis()
                    if (useGtt) {
                        if (gttRes?.snapshot != null) {
                            lastGttRawVehicles = gttVehicles
                            lastGttFetchedAtMillis = commitNowMillis
                        } else {
                            lastGttRawVehicles = emptyList()
                            lastGttFetchedAtMillis = 0L
                        }
                    } else {
                        lastGttRawVehicles = emptyList()
                        lastGttFetchedAtMillis = 0L
                    }
                    if (useBusOne || busOneRes != null) {
                        if (busOneRes?.snapshot != null) {
                            lastBusOneRawVehicles = busOneVehicles
                            lastBusOneFetchedAtMillis = commitNowMillis
                        } else {
                            lastBusOneRawVehicles = emptyList()
                            lastBusOneFetchedAtMillis = 0L
                        }
                    } else if (effectiveMode != DataSourceMode.AUTO) {
                        lastBusOneRawVehicles = emptyList()
                        lastBusOneFetchedAtMillis = 0L
                    }
                    if (useMato && matoGqlVehicles.isNotEmpty()) {
                        lastMatoGqlRawVehicles = matoGqlVehicles
                        lastMatoGqlFetchedAtMillis = commitNowMillis
                    } else if (!useMato) {
                        lastMatoGqlRawVehicles = emptyList()
                        lastMatoGqlFetchedAtMillis = 0L
                    }

                    // Re-snapshot MQTT at exact commit time so we never regress behind an overlay tick
                    if (!widgetFastMode && useMato) {
                        val latestMqtt = matoMqttClient.snapshotFreshVehicles(commitNowMillis)
                        if (latestMqtt.isNotEmpty() || matoMqttVehicles.isEmpty()) {
                            matoMqttVehicles = latestMqtt
                            matoCombined = mergeMultiSourceVehicles(
                                matoVehicles = matoMqttVehicles,
                                gttVehicles = matoGqlVehicles,
                                busOneVehicles = emptyList(),
                                nowMillis = commitNowMillis,
                            )
                        }
                    }

                    val matoRetainedCandidates = if (!widgetFastMode && useMato && effectiveMode != DataSourceMode.MATO_LIVE) {
                        matoMqttClient.snapshotRetainedCandidates(commitNowMillis)
                    } else {
                        emptyList()
                    }
                    val pipelineDecisions = if (!widgetFastMode && !normFocus.isNullOrBlank()) {
                        ArrayList<PipelineBusDecision>()
                    } else {
                        null
                    }
                    val decisionSink: ((PipelineBusDecision) -> Unit)? = if (pipelineDecisions != null && !normFocus.isNullOrBlank()) {
                        { d ->
                            if (d.lineCode.equals(normFocus, ignoreCase = true)) {
                                pipelineDecisions.add(d)
                            }
                        }
                    } else {
                        null
                    }
                    val effectiveMatoForMerge = GttVehicleMergeEngine.filterStaleUncorroboratedMato(
                        matoVehicles = matoCombined,
                        gttVehicles = gttVehicles,
                        busOneVehicles = busOneVehicles,
                        healthyOfficialGtfsOnline = healthyOfficialGtfsOnline && effectiveMode != DataSourceMode.MATO_LIVE,
                        nowMillis = commitNowMillis,
                        isWidgetFastMode = widgetFastMode,
                        onDecision = decisionSink,
                    )

                    val merged = mergeMultiSourceVehicles(
                        matoVehicles = effectiveMatoForMerge,
                        gttVehicles = gttVehicles,
                        busOneVehicles = busOneVehicles,
                        nowMillis = commitNowMillis,
                        focusLine = normFocus,
                        sourceReports = if (allowCrossSourceFallback) sourceReports else emptyMap(),
                        previousPoses = if (widgetFastMode) emptyMap() else kinematicTracker,
                        matoRetainedCandidates = matoRetainedCandidates,
                        onDecision = decisionSink,
                    )
                    val quarantinedIds = HashSet<String>(4)
                    val kinematicallySafe = if (widgetFastMode) {
                        merged
                    } else {
                        GttVehicleMergeEngine.evaluateKinematicAnomalies(
                            vehicles = merged,
                            kinematicPoses = kinematicTracker,
                            nowMillis = commitNowMillis,
                            onDecision = decisionSink,
                        ) { bus, reason ->
                            quarantinedIds.add(GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId.trim() })
                            ToBusDiagnosticsLog.record(
                                category = DiagCategory.VEHICLE,
                                message = "#${bus.label} L.${bus.lineCode} QUARANTENA CINEMATICA: $reason",
                                detail = "src=${compactSourceTag(bus.sourceName)} [POLL]",
                                severity = DiagSeverity.WARN,
                                timestampMillis = commitNowMillis,
                            )
                        }
                    }

                    val enrichedRaw = enrichWithTripUpdatesAndMovement(kinematicallySafe, activeTripUpdates, commitNowMillis)
                    val enriched = if (widgetFastMode) {
                        enrichedRaw
                    } else {
                        GttVehicleMergeEngine.applyVerifiedDropoutGrace(
                            freshVehicles = enrichedRaw,
                            graceCache = verifiedDropoutGraceCache,
                            focusLine = normFocus,
                            nowMillis = commitNowMillis,
                            quarantinedIds = quarantinedIds,
                            onDecision = decisionSink,
                        )
                    }

                    if (!widgetFastMode && !normFocus.isNullOrBlank() && pipelineDecisions != null) {
                        busDiagnosticTracker.evaluateCycle(
                            focusLine = normFocus,
                            trigger = "POLL",
                            rawGtt = gttVehicles,
                            rawBusOne = busOneVehicles,
                            rawMatoLive = matoMqttVehicles,
                            rawMatoGql = matoGqlVehicles,
                            rawMatoRetained = matoRetainedCandidates,
                            mergedAndGraceVehicles = enriched,
                            routePatterns = routeGeometryService.getMemoryCachedPatterns(normFocus),
                            healthyOfficialGtfsOnline = healthyOfficialGtfsOnline,
                            pipelineDecisions = pipelineDecisions,
                            nowMillis = commitNowMillis,
                        )
                    }

                    val matoMaxTs = matoCombined.maxOfOrNull { it.timestampSeconds } ?: 0L
                    val feedTs = maxOf(
                        primarySnap?.feedTimestampSeconds ?: 0L,
                        secondarySnap?.feedTimestampSeconds ?: 0L,
                        matoMaxTs,
                    )

                    val outageReport = if (!widgetFastMode) {
                        outageDetector.onLivePollSuccess(
                            vehicles = enriched,
                            feedTimestampSeconds = feedTs,
                            sourceReports = sourceReports,
                            dataSourceMode = effectiveMode,
                            nowMillis = commitNowMillis,
                        )
                    } else {
                        outageDetector.currentActiveOutage(commitNowMillis)
                    }

                    if (!widgetFastMode) {
                        val gttTag = gttRes?.let { "GTT=${it.status.itemCount}(${it.status.latencyMs}ms${if (finalGttFrozen) ",FROZEN" else ""})" } ?: "GTT=skip"
                        val busOneTag = busOneRes?.let { "BusOne=${it.status.itemCount}(${it.status.latencyMs}ms${if (finalBusOneFrozen) ",FROZEN" else ""})" } ?: "BusOne=skip"
                        val matoTag = "MaTO=${matoMqttVehicles.size}live+${matoRetainedCandidates.size}ret+${matoGqlVehicles.size}gql"
                        val holdoverCount = enriched.count { it.isHoldover }
                        val holdoverTag = if (holdoverCount > 0) " · hold=$holdoverCount" else ""
                        ToBusDiagnosticsLog.record(
                            category = DiagCategory.POLL,
                            message = "Poll ${effectiveMode.shortLabelIt} OK · ${enriched.size} mezzi (L.${normFocus ?: "-"})",
                            detail = "$gttTag · $busOneTag · $matoTag · TU=${activeTripUpdates.size}$holdoverTag",
                            severity = if (outageReport != null && !outageReport.isSimulated) DiagSeverity.WARN else DiagSeverity.OK,
                            timestampMillis = commitNowMillis,
                        )
                    }

                    val rawSummaries = GtfsRtProtobufParser.buildLineSummaries(enriched)
                    val staticCatalog = staticRouteStore.getCachedLineCatalogFast()
                    val summaries = when {
                        rawSummaries.size > 5 && staticCatalog.isNotEmpty() ->
                            mergeLiveSummariesWithStaticCatalog(rawSummaries, staticCatalog)
                        rawSummaries.size <= 5 -> {
                            val catalogFallback = staticCatalog
                                .ifEmpty { getCachedActiveLinesFast(maxAgeMillis = Long.MAX_VALUE) }
                            mergeLineSummariesWithCatalog(rawSummaries, catalogFallback)
                        }
                        else -> mergeLiveSummariesWithStaticCatalog(rawSummaries, listOf(METRO_LINE_SUMMARY))
                    }
                    val finalSnapshot = LiveFeedSnapshot(
                        feedTimestampSeconds = feedTs,
                        fetchedAtMillis = commitNowMillis,
                        allVehicles = enriched,
                        availableLines = summaries,
                        sourceStatuses = statuses,
                        alerts = if (includeAlerts) cachedAlerts else emptyList(),
                        outageReport = outageReport,
                        healthyOfficialGtfsOnline = healthyOfficialGtfsOnline,
                    )
                    lastSnapshot = finalSnapshot
                    CommittedPollResult(
                        commitNowMillis = commitNowMillis,
                        enriched = enriched,
                        feedTs = feedTs,
                        outageReport = outageReport,
                        finalSnapshot = finalSnapshot,
                        rawSummaries = rawSummaries,
                    )
                }

                // Perform disk cache writes OUTSIDE pipelineLock so file I/O never stalls MQTT overlays
                if (committed.rawSummaries.size > 1 ||
                    (effectiveMode != DataSourceMode.MATO_LIVE && committed.rawSummaries.isNotEmpty())
                ) {
                    saveActiveLinesCache(committed.rawSummaries)
                }
                if (!normFocus.isNullOrBlank()) {
                    val rawLineBuses = committed.enriched.filter {
                        it.lineCode.equals(normFocus, ignoreCase = true) ||
                            it.rawRouteId.equals(normFocus, ignoreCase = true)
                    }
                    val cachedPatterns = routeGeometryService.getMemoryCachedPatterns(normFocus)
                    val validatedLineBuses = GttFleetAnalyzer.filterOutConfirmedPhantomsAndDeadGhosts(
                        vehicles = rawLineBuses,
                        routePatterns = cachedPatterns,
                        nowMillis = committed.commitNowMillis,
                        healthyOfficialGtfsOnline = healthyOfficialGtfsOnline,
                    )
                    val officialResponded = primarySnap != null || secondarySnap != null
                    if (validatedLineBuses.isNotEmpty()) {
                        saveWidgetLineVehiclesCache(normFocus, committed.commitNowMillis, validatedLineBuses, confirmedEmpty = false)
                    } else if (officialResponded) {
                        // Explicitly clear stale widget cache when healthy official feeds confirm 0 real buses!
                        saveWidgetLineVehiclesCache(normFocus, committed.commitNowMillis, emptyList(), confirmedEmpty = true)
                    }
                }
                Result.success(committed.finalSnapshot)
            }
        }

    private fun ensureMovementStateHydrated(nowMillis: Long) {
        if (movementStateHydratedFromDisk) return
        synchronized(this) {
            if (movementStateHydratedFromDisk) return
            movementStateHydratedFromDisk = true
            // Ensure background widget/worker processes also hydrate real OSM depot polygons from disk cache.
            depotGeometryService.loadCachedDepotsFast(nowMillis)
            runCatching {
                val file = movementStateCacheFile
                if (!file.exists()) return@runCatching
                val root = JSONObject(file.readText())
                val arr = root.optJSONArray("records") ?: return@runCatching
                val cutoff = nowMillis - MOVEMENT_STATE_DISK_TTL_MS
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val vid = obj.optString("vid").trim()
                    if (vid.isEmpty()) continue
                    val obsAt = obj.optLong("obs", 0L)
                    if (obsAt < cutoff) continue
                    val lat = obj.optDouble("lat", Double.NaN)
                    val lon = obj.optDouble("lon", Double.NaN)
                    if (!lat.isFinite() || !lon.isFinite()) continue
                    val firstAt = obj.optLong("first", obsAt)
                    val movedAt = if (obj.has("moved")) obj.optLong("moved") else null
                    val savedTrip = obj.optString("trip").takeIf { it.isNotBlank() }
                    movementTracker.putIfAbsent(
                        vid,
                        MovementRecord(
                            lineCode = obj.optString("line"),
                            anchorLat = lat,
                            anchorLon = lon,
                            lastLat = lat,
                            lastLon = lon,
                            lastMovedAtMillis = movedAt,
                            firstSeenAtPositionMillis = firstAt,
                            lastObservedAtMillis = obsAt,
                            tripId = savedTrip,
                        ),
                    )
                }
            }
        }
    }

    private fun maybePersistMovementState(nowMillis: Long) {
        if (nowMillis - lastMovementStateSavedAtMillis < MOVEMENT_STATE_SAVE_INTERVAL_MS) return
        lastMovementStateSavedAtMillis = nowMillis
        runCatching {
            val cutoff = nowMillis - MOVEMENT_STATE_DISK_TTL_MS
            val arr = JSONArray()
            var count = 0
            for ((vid, rec) in movementTracker) {
                if (rec.lastObservedAtMillis < cutoff) continue
                arr.put(
                    JSONObject().apply {
                        put("vid", vid)
                        put("line", rec.lineCode)
                        put("lat", rec.anchorLat)
                        put("lon", rec.anchorLon)
                        put("first", rec.firstSeenAtPositionMillis)
                        put("obs", rec.lastObservedAtMillis)
                        if (rec.lastMovedAtMillis != null) put("moved", rec.lastMovedAtMillis)
                        if (!rec.tripId.isNullOrBlank()) put("trip", rec.tripId)
                    },
                )
                if (++count >= 450) break
            }
            val root = JSONObject().apply {
                put("savedAt", nowMillis)
                put("records", arr)
            }
            movementStateCacheFile.writeText(root.toString())
        }
    }

    private fun enrichWithTripUpdatesAndMovement(
        vehicles: List<BusVehicle>,
        activeTripUpdates: List<TripDelayInfo>,
        nowMillis: Long,
    ): List<BusVehicle> {
        ensureMovementStateHydrated(nowMillis)
        val withDelays = enrichWithTripUpdates(vehicles, activeTripUpdates)
        val enriched = withDelays.map { bus ->
            val canonVid = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId }
            val rawPrev = movementTracker[canonVid]
            val prev = rawPrev?.takeIf {
                it.lineCode.isBlank() || it.lineCode.equals(bus.lineCode, ignoreCase = true)
            }
            val anchorDistMeters = if (prev != null) {
                GttVehicleMergeEngine.haversineMeters(
                    prev.anchorLat,
                    prev.anchorLon,
                    bus.latitude,
                    bus.longitude,
                )
            } else {
                Double.POSITIVE_INFINITY
            }
            val stepDistMeters = if (prev != null) {
                GttVehicleMergeEngine.haversineMeters(
                    prev.lastLat,
                    prev.lastLon,
                    bus.latitude,
                    bus.longitude,
                )
            } else {
                Double.POSITIVE_INFINITY
            }
            // Confirmed physical displacement:
            // - >= 22m from anchor clears stationary immediately (immune to 10-16m GPS jitter), OR
            // - >= 14m from anchor with two consecutive progressive steps away from anchor (fast
            //   recovery as soon as a bus begins rolling out of a stop or traffic light).
            val stepMovedForward = prev != null &&
                stepDistMeters >= 5.0 &&
                anchorDistMeters >= prev.lastAnchorDistMeters + 3.0
            val confirmedPhysicalMove = prev != null && (
                anchorDistMeters >= 22.0 ||
                    (anchorDistMeters >= 14.0 && prev.lastStepMovedForward && stepDistMeters >= 5.0)
                )

            val computedBearing = when {
                bus.bearing != null -> bus.bearing
                prev != null && stepDistMeters in 14.0..650.0 ->
                    GttVehicleMergeEngine.initialBearingDeg(
                        prev.lastLat,
                        prev.lastLon,
                        bus.latitude,
                        bus.longitude,
                    ).toFloat()
                prev != null -> prev.inferredBearing
                else -> null
            }

            val observationGapMs = if (prev != null) (nowMillis - prev.lastObservedAtMillis).coerceAtLeast(0L) else 0L
            // After a long standby/closed-app gap (> 8 min), a real bus can complete a full round trip
            // and return to the same terminus under a NEW tripId. Reset the stationary anchor so it is
            // never mistakenly treated as continuously parked for 35+ minutes!
            val returnedOnNewTripAfterGap = prev != null &&
                observationGapMs > 8 * 60_000L &&
                !bus.tripId.isNullOrBlank() &&
                !prev.tripId.isNullOrBlank() &&
                !bus.tripId.equals(prev.tripId, ignoreCase = true)

            val record = when {
                prev == null || returnedOnNewTripAfterGap -> {
                    // Very first observation (or new trip after long standby): do NOT fabricate
                    // `lastMovedAtMillis = nowMillis` before observing actual displacement.
                    MovementRecord(
                        lineCode = bus.lineCode,
                        anchorLat = bus.latitude,
                        anchorLon = bus.longitude,
                        lastLat = bus.latitude,
                        lastLon = bus.longitude,
                        lastMovedAtMillis = if (returnedOnNewTripAfterGap) nowMillis else bus.lastMovedAtMillis,
                        firstSeenAtPositionMillis = if (returnedOnNewTripAfterGap) nowMillis else (bus.stationarySinceMillis ?: nowMillis),
                        lastObservedAtMillis = nowMillis,
                        inferredBearing = computedBearing,
                        lastAnchorDistMeters = 0.0,
                        lastStepMovedForward = false,
                        tripId = bus.tripId,
                    )
                }
                confirmedPhysicalMove -> {
                    MovementRecord(
                        lineCode = bus.lineCode,
                        anchorLat = bus.latitude,
                        anchorLon = bus.longitude,
                        lastLat = bus.latitude,
                        lastLon = bus.longitude,
                        lastMovedAtMillis = nowMillis,
                        firstSeenAtPositionMillis = nowMillis,
                        lastObservedAtMillis = nowMillis,
                        inferredBearing = computedBearing,
                        lastAnchorDistMeters = 0.0,
                        lastStepMovedForward = false,
                        tripId = bus.tripId ?: prev.tripId,
                    )
                }
                else -> {
                    prev.copy(
                        lineCode = bus.lineCode,
                        lastLat = bus.latitude,
                        lastLon = bus.longitude,
                        lastObservedAtMillis = nowMillis,
                        inferredBearing = computedBearing ?: prev.inferredBearing,
                        lastAnchorDistMeters = anchorDistMeters,
                        lastStepMovedForward = stepMovedForward,
                        tripId = bus.tripId ?: prev.tripId,
                    )
                }
            }
            movementTracker[canonVid] = record

            val stationarySince = if (prev != null && !confirmedPhysicalMove && !returnedOnNewTripAfterGap) {
                record.firstSeenAtPositionMillis
            } else {
                null
            }

            bus.copy(
                bearing = bus.bearing ?: record.inferredBearing,
                lastMovedAtMillis = record.lastMovedAtMillis,
                stationarySinceMillis = stationarySince,
            )
        }

        // Prune stale vehicle records older than 45 min based on last observation
        if (movementTracker.size > 800) {
            val cutoff = nowMillis - MOVEMENT_STATE_DISK_TTL_MS
            movementTracker.entries.removeIf { it.value.lastObservedAtMillis < cutoff }
        }
        maybePersistMovementState(nowMillis)
        return enriched
    }

    /**
     * On-demand fast MaTO GraphQL lookup for a single line (used when the user taps a "Live" arrival
     * of another line inside a stop card and its vehicle was not in the general GTFS-RT pool).
     */
    fun getCachedSingleLineVehicles(
        lineCode: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<BusVehicle> {
        val clean = GtfsRtProtobufParser.normalizeGttLineCode(lineCode).ifBlank { lineCode.trim().uppercase() }
        if (clean.isEmpty() || clean == "?") return emptyList()
        val cached = singleLineLiveCache[clean] ?: return emptyList()
        val ttl = if (cached.vehicles.isNotEmpty()) SINGLE_LINE_LIVE_CACHE_TTL_MS else SINGLE_LINE_EMPTY_CACHE_TTL_MS
        return if ((nowMillis - cached.fetchedAtMillis) <= ttl) cached.vehicles else emptyList()
    }

    suspend fun fetchLiveVehiclesForSingleLine(
        lineCode: String,
        forceRefresh: Boolean = false,
    ): List<BusVehicle> = withContext(Dispatchers.IO) {
        val clean = GtfsRtProtobufParser.normalizeGttLineCode(lineCode).ifBlank { lineCode.trim().uppercase() }
        if (clean.isEmpty() || clean == "?") return@withContext emptyList()
        val nowMillis = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = singleLineLiveCache[clean]
            if (cached != null) {
                val ttl = if (cached.vehicles.isNotEmpty()) SINGLE_LINE_LIVE_CACHE_TTL_MS else SINGLE_LINE_EMPTY_CACHE_TTL_MS
                if ((nowMillis - cached.fetchedAtMillis) <= ttl) {
                    return@withContext cached.vehicles
                }
            }
        }
        val fetched = withTimeoutOrNull(3_400L) {
            matoGraphQlClient.fetchVehiclePositions(clean, nowMillis)
        }.orEmpty()
        val enriched = if (fetched.isNotEmpty()) {
            enrichWithTripUpdatesAndMovement(fetched, lastTripUpdates, nowMillis)
        } else {
            emptyList()
        }
        singleLineLiveCache[clean] = CachedSingleLineVehicles(nowMillis, enriched)
        if (singleLineLiveCache.size > 48) {
            val cutoff = nowMillis - 2 * SINGLE_LINE_LIVE_CACHE_TTL_MS
            singleLineLiveCache.entries.removeIf { it.value.fetchedAtMillis < cutoff }
        }
        enriched
    }

    /**
     * Persists the latest ghost-filtered vehicle list for [lineCode] so Home Screen Widgets survive
     * process death + transient Doze network timeouts WITHOUT ever resurrecting retained ghosts or
     * stale buses after a line genuinely drops to 0 active vehicles.
     */
    fun saveWidgetLineVehiclesCache(
        lineCode: String,
        fetchedAtMillis: Long,
        vehicles: List<BusVehicle>,
        confirmedEmpty: Boolean = false,
    ) {
        runCatching {
            val arr = JSONArray()
            vehicles.filterNot { it.isMqttRetained }.forEach { v ->
                arr.put(
                    JSONObject().apply {
                        put("entityId", v.entityId)
                        put("vehicleId", v.vehicleId)
                        put("label", v.label)
                        put("rawRouteId", v.rawRouteId)
                        put("lineCode", v.lineCode)
                        if (v.tripId != null) put("tripId", v.tripId)
                        if (v.directionId != null) put("directionId", v.directionId)
                        put("lat", v.latitude)
                        put("lon", v.longitude)
                        if (v.bearing != null) put("bearing", v.bearing.toDouble())
                        if (v.speedKmh != null) put("speedKmh", v.speedKmh.toDouble())
                        put("ts", v.timestampSeconds)
                        if (v.stopId != null) put("stopId", v.stopId)
                        put("sourceName", v.sourceName)
                        val srcs = JSONArray()
                        v.contributingSources.forEach { srcs.put(it) }
                        put("sources", srcs)
                        if (v.stationarySinceMillis != null) put("statSince", v.stationarySinceMillis)
                        if (v.lastMovedAtMillis != null) put("movedAt", v.lastMovedAtMillis)
                    },
                )
            }
            val root = JSONObject().apply {
                put("lineCode", lineCode.trim().uppercase())
                put("fetchedAtMillis", fetchedAtMillis)
                put("confirmedEmpty", confirmedEmpty && arr.length() == 0)
                put("vehicles", arr)
            }
            widgetVehiclesCacheFile.writeText(root.toString())
        }
    }

    /**
     * Loads the last persisted validated widget vehicles for [lineCode] if still fresh (within [maxAgeMillis]).
     * Explicitly preserves `confirmedEmpty` (0 vehicles) so stale old ghosts are never resurrected.
     */
    fun loadFreshWidgetLineVehicles(
        lineCode: String,
        maxAgeMillis: Long = 5 * 60_000L,
    ): Pair<Long, List<BusVehicle>>? {
        return runCatching {
            val file = widgetVehiclesCacheFile
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            val cachedLine = root.optString("lineCode").trim().uppercase()
            if (!cachedLine.equals(lineCode.trim().uppercase(), ignoreCase = true)) return null
            val fetchedAt = root.optLong("fetchedAtMillis", 0L)
            if (fetchedAt <= 0L || System.currentTimeMillis() - fetchedAt > maxAgeMillis) return null
            val confirmedEmpty = root.optBoolean("confirmedEmpty", false)
            val arr = root.optJSONArray("vehicles") ?: return null
            val list = ArrayList<BusVehicle>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue
                val srcName = obj.optString("sourceName", "Cache")
                val sourcesArr = obj.optJSONArray("sources")
                val contributing = LinkedHashSet<String>()
                if (sourcesArr != null) {
                    for (sIdx in 0 until sourcesArr.length()) {
                        val s = sourcesArr.optString(sIdx).trim()
                        if (s.isNotEmpty()) contributing.add(s)
                    }
                }
                if (contributing.isEmpty()) contributing.add(srcName)
                list.add(
                    BusVehicle(
                        entityId = obj.optString("entityId"),
                        vehicleId = obj.optString("vehicleId"),
                        label = obj.optString("label"),
                        rawRouteId = obj.optString("rawRouteId"),
                        lineCode = obj.optString("lineCode"),
                        tripId = obj.optString("tripId").takeIf { it.isNotBlank() },
                        directionId = if (obj.has("directionId")) obj.optInt("directionId") else null,
                        latitude = lat,
                        longitude = lon,
                        bearing = if (obj.has("bearing")) obj.optDouble("bearing").toFloat() else null,
                        speedKmh = if (obj.has("speedKmh")) obj.optDouble("speedKmh").toFloat() else null,
                        timestampSeconds = obj.optLong("ts", fetchedAt / 1000L),
                        stopId = obj.optString("stopId").takeIf { it.isNotBlank() },
                        status = VehicleStopStatus.IN_TRANSIT_TO,
                        sourceName = srcName,
                        contributingSources = contributing,
                        lastMovedAtMillis = if (obj.has("movedAt")) obj.optLong("movedAt") else null,
                        stationarySinceMillis = if (obj.has("statSince")) obj.optLong("statSince") else null,
                        fetchedAtMillis = fetchedAt,
                    ),
                )
            }
            if (list.isEmpty() && !confirmedEmpty) null else fetchedAt to list
        }.getOrNull()
    }

    fun getLastCachedSnapshot(): LiveFeedSnapshot? = lastSnapshot

    /**
     * Ultra-fast startup hydration of active GTT lines from a tiny TSV cache (~1.5 KB) or
     * static GTFS `routes.txt` catalog so opening the Line Picker is instant even when live feeds
     * are unreachable or the previous cache expired.
     */
    fun getCachedActiveLinesFast(maxAgeMillis: Long = ACTIVE_LINES_CACHE_TTL_MS): List<LineSummary> {
        val file = activeLinesCacheFile
        val isFresh = file.exists() && (System.currentTimeMillis() - file.lastModified() <= maxAgeMillis)
        if (isFresh) {
            val freshLines = readActiveLinesTsv(file, zeroCountsOnly = false)
            if (freshLines.isNotEmpty()) return freshLines
        }
        val staticFast = staticRouteStore.getCachedLineCatalogFast()
        if (staticFast.isNotEmpty()) return staticFast
        if (file.exists()) {
            return readActiveLinesTsv(file, zeroCountsOnly = true)
        }
        return emptyList()
    }

    private fun readActiveLinesTsv(file: File, zeroCountsOnly: Boolean): List<LineSummary> {
        return runCatching {
            val out = ArrayList<LineSummary>(120)
            file.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val code = line.substring(0, tab).trim()
                    val count = line.substring(tab + 1).trim().toIntOrNull() ?: continue
                    if (code.isNotEmpty() && count >= 0) {
                        out.add(
                            LineSummary(
                                lineCode = code,
                                rawRouteIds = setOf(code),
                                activeVehicleCount = if (zeroCountsOnly) 0 else count,
                            ),
                        )
                    }
                }
            }
            out
        }.getOrDefault(emptyList())
    }

    private fun saveActiveLinesCache(lines: List<LineSummary>) {
        runCatching {
            val sb = StringBuilder(lines.size * 10)
            for (item in lines) {
                sb.append(item.lineCode).append('\t').append(item.activeVehicleCount).append('\n')
            }
            activeLinesCacheFile.writeText(sb.toString())
        }
    }

    /**
     * Fetches the full GTT line catalog with strict bounded timeouts across multiple independent sources:
     * 1. Citywide GTFS-RT feeds (GTT Official + BusOne Proxy) for live vehicle counts
     * 2. Official static GTFS `routes.txt` (cached locally or downloaded up to 7.5s)
     * 3. MaTO GraphQL `{ routes { gtfsId shortName } }` endpoint fallback (~3.8s)
     * Never blocks endlessly; falls back to cached line codes before returning failure.
     */
    suspend fun fetchCitywideLineCatalog(): Result<List<LineSummary>> = withContext(Dispatchers.IO) {
        val nowMillis = System.currentTimeMillis()
        coroutineScope {
            val liveJob = async {
                withTimeoutOrNull(5_500L) {
                    val gttDeferred = async {
                        withTimeoutOrNull(5_000L) {
                            fetchVehicleSource("gtt_official", "GTT Ufficiale", GTT_VP_URLS, nowMillis)
                        }
                    }
                    val busOneDeferred = async {
                        withTimeoutOrNull(5_000L) {
                            fetchVehicleSource("busone_proxy", "BusOne Proxy", BUSONE_VP_URLS, nowMillis)
                        }
                    }
                    val gttVehicles = gttDeferred.await()?.snapshot?.allVehicles.orEmpty()
                    val busOneVehicles = busOneDeferred.await()?.snapshot?.allVehicles.orEmpty()
                    val merged = mergeMultiSourceVehicles(
                        matoVehicles = emptyList(),
                        gttVehicles = gttVehicles,
                        busOneVehicles = busOneVehicles,
                        nowMillis = nowMillis,
                    )
                    GtfsRtProtobufParser.buildLineSummaries(merged)
                }.orEmpty()
            }

            val fastStatic = staticRouteStore.getAllKnownLineSummaries(downloadIfNeeded = false)
            val matoCatalogJob = if (fastStatic.isEmpty()) {
                async {
                    withTimeoutOrNull(3_800L) {
                        matoGraphQlClient.fetchLineCatalog()
                    }.orEmpty()
                }
            } else {
                null
            }

            val liveSummaries = liveJob.await()
            if (liveSummaries.size > 5) {
                saveActiveLinesCache(liveSummaries)
                val fullCatalog = fastStatic.ifEmpty {
                    matoCatalogJob?.await().orEmpty()
                }
                val combined = mergeLiveSummariesWithStaticCatalog(
                    liveSummaries = liveSummaries,
                    staticCatalog = fullCatalog.ifEmpty { listOf(METRO_LINE_SUMMARY) },
                )
                return@coroutineScope Result.success(combined)
            }

            var catalog = fastStatic
            if (catalog.isEmpty()) {
                val matoCatalog = matoCatalogJob?.await().orEmpty()
                if (matoCatalog.isNotEmpty()) {
                    catalog = matoCatalog
                }
            }
            if (catalog.isEmpty()) {
                val downloadedStatic = withTimeoutOrNull(7_500L) {
                    staticRouteStore.getAllKnownLineSummaries(downloadIfNeeded = true)
                }.orEmpty()
                if (downloadedStatic.isNotEmpty()) {
                    catalog = downloadedStatic
                }
            }

            if (liveSummaries.size > 1) {
                saveActiveLinesCache(liveSummaries)
            }

            val combined = mergeLiveSummariesWithStaticCatalog(liveSummaries, catalog)
            if (combined.isNotEmpty()) {
                return@coroutineScope Result.success(combined)
            }

            val cachedFallback = getCachedActiveLinesFast(maxAgeMillis = Long.MAX_VALUE)
            if (cachedFallback.isNotEmpty()) {
                return@coroutineScope Result.success(mergeLineSummariesWithCatalog(liveSummaries, cachedFallback))
            }

            Result.failure(IOException("Feed linee GTT non raggiungibili al momento"))
        }
    }

    /**
     * Returns all known Turin GTT lines from the cached static GTFS routes catalog (with 0-count
     * baseline) so the Line Picker can surface inactive lines in a separate section at the bottom.
     */
    suspend fun getKnownStaticLineCatalog(): List<LineSummary> = withContext(Dispatchers.IO) {
        staticRouteStore.getAllKnownLineSummaries(downloadIfNeeded = false)
    }

    /**
     * Lightweight on-demand fetch of current GTT GTFS-RT service alerts used by background
     * favorite-line alert notifications without touching vehicle or route feeds.
     */
    suspend fun fetchServiceAlertsNow(): List<ServiceAlertInfo>? = withContext(Dispatchers.IO) {
        val result = fetchAlertsSource()
        val alerts = result.alerts
        if (alerts != null) {
            cachedAlerts = alerts
            lastAlertsFetchMillis = System.currentTimeMillis()
        }
        alerts
    }

    suspend fun resolveStopNames(stopIds: Set<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val cleaned = stopIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (cleaned.isEmpty()) return@withContext emptyMap()
        val missing = cleaned.filter { !stopNameMemoryCache.containsKey(it) }.toSet()
        if (missing.isNotEmpty()) {
            val resolved = staticRouteStore.resolveStopNames(missing)
            resolved.forEach { (id, name) -> stopNameMemoryCache[id] = name }
        }
        cleaned.mapNotNull { id -> stopNameMemoryCache[id]?.let { id to it } }.toMap()
    }

    suspend fun getStopSearchIndex(): List<com.example.data.model.StopSearchEntry> =
        staticRouteStore.getStopSearchIndex()

    private data class VehicleSourceResult(
        val snapshot: LiveFeedSnapshot?,
        val status: DataSourceStatus,
        val error: Exception?,
    )

    private fun fetchVehicleSource(
        id: String,
        name: String,
        urls: List<String>,
        nowMillis: Long,
    ): VehicleSourceResult {
        val start = SystemClock.elapsedRealtime()
        var lastErr: Exception? = null
        for (url in urls) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/x-protobuf, application/octet-stream, */*")
                    .header("Cache-Control", "no-cache")
                if (url.contains("busone.app")) {
                    reqBuilder
                        .header("X-BusOne-Client", "ToBusTorino; android")
                        .header("X-BusOne-Use", "nonprofit")
                }
                val req = reqBuilder.build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val bytes = resp.body?.bytes() ?: throw IOException("Corpo vuoto")
                    if (bytes.isEmpty()) throw IOException("Feed vuoto")
                    val snap = GtfsRtProtobufParser.parseFeedMessage(bytes, name, nowMillis)
                    val latency = SystemClock.elapsedRealtime() - start
                    return VehicleSourceResult(
                        snapshot = snap,
                        status = DataSourceStatus(
                            id = id,
                            name = name,
                            isOnline = true,
                            latencyMs = latency,
                            itemCount = snap.allVehicles.size,
                            detail = "${snap.allVehicles.size} mezzi · ${latency}ms",
                        ),
                        error = null,
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Fonte $name fallita ($url): ${e.message}")
                lastErr = e
            }
        }
        val latency = SystemClock.elapsedRealtime() - start
        return VehicleSourceResult(
            snapshot = null,
            status = DataSourceStatus(
                id = id,
                name = name,
                isOnline = false,
                latencyMs = latency,
                itemCount = 0,
                detail = lastErr?.message?.take(36) ?: "Non raggiungibile",
            ),
            error = lastErr,
        )
    }

    private data class TripUpdatesSourceResult(
        val updates: List<TripDelayInfo>,
        val status: DataSourceStatus,
    )

    private fun fetchTripUpdatesSource(): TripUpdatesSourceResult {
        val start = SystemClock.elapsedRealtime()
        for (url in TRIP_UPDATES_URLS) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/x-protobuf, application/octet-stream, */*")
                if (url.contains("busone.app")) {
                    reqBuilder
                        .header("X-BusOne-Client", "ToBusTorino; android")
                        .header("X-BusOne-Use", "nonprofit")
                }
                val req = reqBuilder.build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) continue
                    val bytes = resp.body?.bytes() ?: continue
                    if (bytes.isEmpty()) continue
                    val list = GtfsRtProtobufParser.parseTripUpdates(bytes)
                    val latency = SystemClock.elapsedRealtime() - start
                    return TripUpdatesSourceResult(
                        updates = list,
                        status = DataSourceStatus(
                            id = "gtt_trip_updates",
                            name = "Ritardi & Corse",
                            isOnline = true,
                            latencyMs = latency,
                            itemCount = list.size,
                            detail = "${list.size} corse · ${latency}ms",
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "TripUpdates skip $url: ${e.message}")
            }
        }
        val latency = SystemClock.elapsedRealtime() - start
        return TripUpdatesSourceResult(
            updates = emptyList(),
            status = DataSourceStatus(
                id = "gtt_trip_updates",
                name = "Ritardi & Corse",
                isOnline = false,
                latencyMs = latency,
                itemCount = 0,
                detail = "Non disponibile",
            ),
        )
    }

    private data class AlertsSourceResult(
        val alerts: List<ServiceAlertInfo>?,
        val status: DataSourceStatus,
    )

    private fun fetchAlertsSource(): AlertsSourceResult {
        val start = SystemClock.elapsedRealtime()
        for (url in ALERTS_URLS) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/x-protobuf, application/octet-stream, */*")
                if (url.contains("busone.app")) {
                    reqBuilder
                        .header("X-BusOne-Client", "ToBusTorino; android")
                        .header("X-BusOne-Use", "nonprofit")
                }
                val req = reqBuilder.build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) continue
                    val bytes = resp.body?.bytes() ?: continue
                    val rawList = GtfsRtProtobufParser.parseServiceAlerts(bytes)
                    val nowSec = System.currentTimeMillis() / 1000L
                    val list = com.example.data.model.ServiceAlertOrganizer.cleanAndDeduplicate(
                        rawAlerts = rawList,
                        nowEpochSeconds = nowSec,
                        maxHorizonDays = 7,
                    )
                    val latency = SystemClock.elapsedRealtime() - start
                    ToBusDiagnosticsLog.record(
                        category = DiagCategory.ALERTS,
                        message = "Avvisi GTT scaricati: ${list.size} unici (su ${rawList.size} nel feed)",
                        detail = "${latency}ms · ${url.substringAfter("://").substringBefore("/")}",
                        severity = DiagSeverity.OK,
                    )
                    return AlertsSourceResult(
                        alerts = list,
                        status = DataSourceStatus(
                            id = "gtt_alerts",
                            name = "Avvisi GTT",
                            isOnline = true,
                            latencyMs = latency,
                            itemCount = list.size,
                            detail = "${list.size} avvisi attuali",
                        ),
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Alerts skip $url: ${e.message}")
            }
        }
        ToBusDiagnosticsLog.record(
            category = DiagCategory.ALERTS,
            message = "Feed avvisi GTT non raggiungibile",
            detail = "cache=${cachedAlerts.size} avvisi",
            severity = DiagSeverity.WARN,
        )
        return AlertsSourceResult(
            alerts = null,
            status = DataSourceStatus(
                id = "gtt_alerts",
                name = "Avvisi GTT",
                isOnline = false,
                latencyMs = SystemClock.elapsedRealtime() - start,
                itemCount = cachedAlerts.size,
                detail = "Offline",
            ),
        )
    }

    suspend fun prefetchStaticGtfsInBackground() {
        runCatching { staticRouteStore.prefetchGtfsZip() }
    }

    /**
     * Ultra-fast official street-by-street route & stops loader for Turin GTT lines.
     * Delegates to [GttRouteGeometryService].
     */
    suspend fun fetchRoutePatterns(
        lineCode: String,
        activeTripIds: Set<String> = emptySet(),
        forceRefresh: Boolean = false,
    ): List<RoutePattern> = routeGeometryService.fetchRoutePatterns(
        lineCode = lineCode,
        activeTripIds = activeTripIds,
        forceRefresh = forceRefresh,
    )

    /**
     * Low-latency route loader for stop arrival verification & cross-line bus focusing.
     * Delegates to [GttRouteGeometryService].
     */
    suspend fun getFastRoutePatternsForLine(
        lineCode: String,
        activeTripIds: Set<String> = emptySet(),
    ): List<RoutePattern> = routeGeometryService.getFastRoutePatternsForLine(
        lineCode = lineCode,
        activeTripIds = activeTripIds,
    )

    /**
     * Fetches upcoming arrivals for a tapped stop on the route, combining:
     * 1. GTT Official AVM Palina API (`palina.getTransitiOld`) — authoritative operational board (`PassaggiRT` + active schedule)
     * 2. MATO / 5T GraphQL real-time stop board (OTP2 + OTP1 fallback, strictly omitting CANCELED / non-pickup trips)
     * 3. GTT Official GTFS-RT `TripUpdates` live per-stop predictions (only when linked to an active non-depot vehicle)
     * 4. Physics-consistent reconciliation against live GPS positions across ALL Turin lines to drop impossible/phantom arrivals
     * 5. Calendar-filtered Official GTT Static GTFS schedule ONLY when both GTT Palina and MATO are unavailable
     */
    suspend fun fetchStopArrivals(
        stop: TransitStop,
        preferredLine: String,
        liveVehicles: List<BusVehicle>,
        routePatterns: List<RoutePattern> = emptyList(),
        forceRefresh: Boolean = false,
    ): List<StopArrival> = withContext(Dispatchers.IO) {
        val nowMillis = System.currentTimeMillis()
        val nowSec = nowMillis / 1000L
        val normPref = preferredLine.trim().uppercase()
        val cacheKey = "${stop.stopId.trim().uppercase()}|$normPref"
        if (!forceRefresh) {
            val cached = stopArrivalsCache[cacheKey]
            if (cached != null && (nowMillis - cached.fetchedAtMillis) <= STOP_ARRIVALS_CACHE_TTL_MS) {
                return@withContext cached.arrivals
            }
        }

        val activePatterns = routePatterns.ifEmpty { routeGeometryService.getMemoryCachedPatterns(normPref) }
        val combined = mutableListOf<StopArrival>()

        // 1. Query GTT Official Palina + MATO GraphQL in parallel with strict bounded timeouts
        val (palinaArrivals, matoArrivals) = coroutineScope {
            val palinaDeferred = async {
                withTimeoutOrNull(2_600L) {
                    GttStopArrivalsParser.queryGttPalinaStopArrivals(
                        httpClient = matoFastHttpClient,
                        userAgent = USER_AGENT,
                        stop = stop,
                        nowMillis = nowMillis,
                    )
                }.orEmpty()
            }
            val matoDeferred = async {
                withTimeoutOrNull(2_600L) {
                    GttStopArrivalsParser.queryMatoStopArrivals(
                        httpClient = matoFastHttpClient,
                        userAgent = USER_AGENT,
                        endpoints = MATO_GRAPHQL_ENDPOINTS,
                        stop = stop,
                        nowMillis = nowMillis,
                    )
                }.orEmpty()
            }
            palinaDeferred.await() to matoDeferred.await()
        }

        // 2. Live GTT GTFS-RT TripUpdates stop predictions (refresh if empty or older than 25s, capped cleanly)
        val tripUpdates = if (lastTripUpdates.isEmpty() || nowMillis - lastTripUpdatesFetchMillis > 25_000L) {
            val fresh = withTimeoutOrNull(2_200L) { fetchTripUpdatesSource().updates }.orEmpty()
            if (fresh.isNotEmpty()) {
                lastTripUpdates = fresh
                lastTripUpdatesFetchMillis = nowMillis
                fresh
            } else {
                lastTripUpdates
            }
        } else {
            lastTripUpdates
        }

        // Match against ALL live Turin vehicles so even other lines at this stop (e.g. 55, 5) link to their exact bus!
        val allLivePool: List<BusVehicle> = (getLastCachedSnapshot()?.allVehicles ?: emptyList()).ifEmpty { liveVehicles }
        val vehicleByTrip = allLivePool.mapNotNull { v ->
            GtfsRtProtobufParser.normalizeTripId(v.tripId)?.let { it to v }
        }.toMap()
        val vehicleByCanonId = allLivePool.associateBy {
            GtfsRtProtobufParser.canonicalFleetId(it.vehicleId).ifBlank { it.vehicleId }
        }
        val stopKeys = setOf(stop.stopId.trim(), stop.stopCode.trim()).filter { it.isNotEmpty() }.toSet()

        // Enrich MATO arrivals with exact vehicleId when tripId matches a live bus
        val enrichedMato = matoArrivals.map { arr ->
            val cleanTrip = GtfsRtProtobufParser.normalizeTripId(arr.tripId)
            val matchedVeh = cleanTrip?.let { vehicleByTrip[it] }
            if (matchedVeh != null && arr.vehicleId.isNullOrBlank()) {
                arr.copy(vehicleId = matchedVeh.vehicleId)
            } else {
                arr
            }
        }

        if (palinaArrivals.isNotEmpty()) {
            // GTT Palina is GTT's live AVM dispatch board: trust it for scheduled passages so suppressed
            // or deviated static MATO timetable runs never leak in as phantom arrivals.
            combined.addAll(palinaArrivals)
            val palinaLines = palinaArrivals.map { it.lineCode.trim().uppercase() }.toHashSet()
            for (m in enrichedMato) {
                val mLine = m.lineCode.trim().uppercase()
                if (m.isRealtime && (mLine in palinaLines || !m.vehicleId.isNullOrBlank())) {
                    combined.add(m)
                }
            }
        } else {
            combined.addAll(enrichedMato)
        }

        // Only accept GTT RT TripUpdates as `isRealtime = true` when linked to an ACTUAL live non-depot vehicle!
        // GTT's trip_update.aspx frequently emits future scheduled trips (30-60 min out) without any active bus.
        for (tu in tripUpdates) {
            val pred = tu.stopPredictions.firstOrNull { it.stopId.trim() in stopKeys } ?: continue
            val arrSec = pred.arrivalEpochSeconds ?: pred.departureEpochSeconds ?: continue
            if (arrSec < nowSec - 60L || arrSec > nowSec + STOP_ARRIVAL_HORIZON_SEC) continue
            val cleanTrip = GtfsRtProtobufParser.normalizeTripId(tu.tripId)
            val cleanTuVeh = GtfsRtProtobufParser.canonicalFleetId(tu.vehicleId).ifBlank { null }
            val matchedVeh = cleanTrip?.let { vehicleByTrip[it] }
                ?: cleanTuVeh?.let { vid ->
                    vehicleByCanonId[vid] ?: allLivePool.firstOrNull {
                        GtfsRtProtobufParser.canonicalFleetId(it.vehicleId) == vid ||
                            GtfsRtProtobufParser.canonicalFleetId(it.label) == vid
                    }
                }

            if (matchedVeh == null) continue
            val vehHealth = com.example.data.model.GttFleetAnalyzer.assess(matchedVeh, emptyList(), nowMillis)
            if (vehHealth.isInDepot || vehHealth.isStaleGps) continue

            val line = when {
                !tu.routeId.isNullOrBlank() -> GtfsRtProtobufParser.normalizeGttLineCode(tu.routeId)
                else -> matchedVeh.lineCode
            }
            combined.add(
                StopArrival(
                    lineCode = line,
                    headsign = "Linea $line",
                    arrivalEpochSeconds = arrSec,
                    isRealtime = true,
                    delaySeconds = pred.delaySeconds ?: tu.delaySeconds,
                    vehicleId = matchedVeh.vehicleId,
                    tripId = cleanTrip ?: tu.tripId,
                    sourceLabel = "GTT RT",
                ),
            )
        }

        // 3. Calendar-filtered Static GTFS schedule fallback ONLY when both GTT Palina and MATO failed
        val preReconciled = deduplicateArrivals(combined)
        if (palinaArrivals.isEmpty() && matoArrivals.isEmpty()) {
            val scheduled = staticRouteStore.getScheduledArrivalsForStop(stop, normPref, nowMillis)
            preReconciled.addAll(scheduled)
        }

        // 4. Load real route geometries AND on-demand MaTO live positions in parallel for OTHER lines
        //    passing this stop so GPS crosshairs appear immediately on first open (without visiting each line first!)
        val dedupedPre = deduplicateArrivals(preReconciled)
        val otherLineCodes = dedupedPre
            .groupBy { it.lineCode.trim().uppercase() }
            .entries
            .filter { (code, _) -> code.isNotEmpty() && !code.equals(normPref, ignoreCase = true) }
            .sortedWith(
                compareByDescending<Map.Entry<String, List<StopArrival>>> { (_, list) ->
                    list.any { it.isRealtime }
                }.thenBy { (_, list) ->
                    list.minOfOrNull { it.arrivalEpochSeconds } ?: Long.MAX_VALUE
                },
            )
            .map { it.key }
            .take(10)

        val liveCountByLine = allLivePool.groupingBy { it.lineCode.trim().uppercase() }.eachCount()
        val linesNeedingMatoLive = buildList {
            for (code in otherLineCodes) {
                val lineArrs = dedupedPre.filter { it.lineCode.equals(code, ignoreCase = true) }
                val rtArrs = lineArrs.filter { it.isRealtime }
                if (rtArrs.isEmpty()) continue
                val hasUnlinkedRt = rtArrs.any { arr ->
                    val vid = GtfsRtProtobufParser.canonicalFleetId(arr.vehicleId).ifBlank { null }
                    vid == null || vid !in vehicleByCanonId
                }
                val poolCount = liveCountByLine[code] ?: 0
                if (hasUnlinkedRt || poolCount < rtArrs.size) {
                    add(code)
                }
            }
            if (normPref.isNotEmpty()) {
                val prefRt = dedupedPre.filter {
                    it.lineCode.equals(normPref, ignoreCase = true) && it.isRealtime
                }
                val prefUnlinked = prefRt.any { arr ->
                    val vid = GtfsRtProtobufParser.canonicalFleetId(arr.vehicleId).ifBlank { null }
                    vid == null || vid !in vehicleByCanonId
                }
                val prefPoolCount = liveCountByLine[normPref] ?: 0
                if (prefRt.isNotEmpty() && (prefUnlinked || prefPoolCount < prefRt.size)) {
                    add(normPref)
                }
            }
        }.distinct().take(6)

        val partialPatterns = ConcurrentHashMap<String, List<RoutePattern>>()
        val partialExtraVehicles = java.util.Collections.synchronizedList(mutableListOf<BusVehicle>())
        withTimeoutOrNull(2_400L) {
            coroutineScope {
                otherLineCodes.forEach { code ->
                    async {
                        val lineTrips = allLivePool
                            .asSequence()
                            .filter { it.lineCode.equals(code, ignoreCase = true) }
                            .mapNotNull { it.tripId?.trim()?.takeIf { t -> t.isNotEmpty() } }
                            .toSet()
                        val patterns = getFastRoutePatternsForLine(code, lineTrips)
                        if (patterns.isNotEmpty()) {
                            partialPatterns[code] = patterns
                        }
                    }
                }
                linesNeedingMatoLive.forEach { code ->
                    async {
                        val extra = fetchLiveVehiclesForSingleLine(code)
                        if (extra.isNotEmpty()) {
                            partialExtraVehicles.addAll(extra)
                        }
                    }
                }
            }
        }
        val otherLinePatterns: Map<String, List<RoutePattern>> = HashMap(partialPatterns)
        val extraSingleLineVehicles: List<BusVehicle> = synchronized(partialExtraVehicles) {
            partialExtraVehicles.toList()
        }

        val enrichedAllLivePool = if (extraSingleLineVehicles.isNotEmpty()) {
            mergeMultiSourceVehicles(
                matoVehicles = extraSingleLineVehicles,
                gttVehicles = allLivePool,
                busOneVehicles = emptyList(),
                nowMillis = nowMillis,
            )
        } else {
            allLivePool
        }

        val enrichedPrefVehicles = if (extraSingleLineVehicles.isNotEmpty() && normPref.isNotEmpty()) {
            val extraPref = extraSingleLineVehicles.filter {
                it.lineCode.equals(normPref, ignoreCase = true) ||
                    it.rawRouteId.equals(normPref, ignoreCase = true)
            }
            if (extraPref.isNotEmpty()) {
                mergeMultiSourceVehicles(
                    matoVehicles = extraPref,
                    gttVehicles = liveVehicles,
                    busOneVehicles = emptyList(),
                    nowMillis = nowMillis,
                )
            } else {
                liveVehicles
            }
        } else {
            liveVehicles
        }

        // Re-link any unlinked arrivals whose tripId now matches one of the freshly fetched MaTO vehicles
        val enrichedByTrip = if (extraSingleLineVehicles.isNotEmpty()) {
            enrichedAllLivePool.mapNotNull { v ->
                GtfsRtProtobufParser.normalizeTripId(v.tripId)?.let { it to v }
            }.toMap()
        } else {
            emptyMap()
        }
        val finalPreArrivals = if (enrichedByTrip.isNotEmpty()) {
            dedupedPre.map { arr ->
                if (!arr.vehicleId.isNullOrBlank()) {
                    arr
                } else {
                    val cleanTrip = GtfsRtProtobufParser.normalizeTripId(arr.tripId)
                    val matchedVeh = cleanTrip?.let { enrichedByTrip[it] }
                    if (matchedVeh != null && matchedVeh.lineCode.equals(arr.lineCode, ignoreCase = true)) {
                        arr.copy(vehicleId = matchedVeh.vehicleId)
                    } else {
                        arr
                    }
                }
            }
        } else {
            dedupedPre
        }

        // 5. Physics-consistent reconciliation with live GPS positions & exact route polylines (preferred + other lines):
        //    - Overrides wildly wrong feed ETAs when a tracked bus is physically right upstream of the stop
        //    - Drops stale ghost predictions for buses that already passed or are in opposite direction
        //    - Links exact verified vehicleId onto both preferredLine and other-line arrivals
        //    - Drops physically impossible unlinked/scheduled phantom arrivals when no bus can reach the stop
        //    - Always injects approaching live buses on preferredLine that feeds missed
        val reconciled = reconcileLiveVehiclesWithArrivals(
            stop = stop,
            preferredLine = normPref,
            liveVehicles = enrichedPrefVehicles,
            allLivePool = enrichedAllLivePool,
            routePatterns = activePatterns,
            rawArrivals = finalPreArrivals,
            nowMillis = nowMillis,
            otherLinePatterns = otherLinePatterns,
        )

        val finalArrivals = deduplicateArrivals(reconciled)
            .filter { it.arrivalEpochSeconds in (nowSec - 60L)..(nowSec + STOP_ARRIVAL_HORIZON_SEC) }
            .sortedWith(
                compareByDescending<StopArrival> { it.lineCode.equals(normPref, ignoreCase = true) }
                    .thenBy { it.arrivalEpochSeconds },
            )
            .take(18)

        val linkedGps = finalArrivals.count { !it.vehicleId.isNullOrBlank() }
        val rtCount = finalArrivals.count { it.isRealtime }
        lastStopArrivalsSummary = "#${stop.displayCode}: ${finalArrivals.size} passaggi (RT=$rtCount, GPS=$linkedGps · Palina=${palinaArrivals.size}, MaTO=${matoArrivals.size})"
        ToBusDiagnosticsLog.record(
            category = DiagCategory.STOP,
            message = "Fermata #${stop.displayCode} (${stop.name.take(24)})",
            detail = "out=${finalArrivals.size} (RT=$rtCount GPS=$linkedGps) · Palina=${palinaArrivals.size} MaTO=${matoArrivals.size} extraBus=${extraSingleLineVehicles.size}",
            severity = if (finalArrivals.isNotEmpty()) DiagSeverity.OK else DiagSeverity.INFO,
        )
        if (finalArrivals.isNotEmpty()) {
            stopArrivalsCache[cacheKey] = CachedStopArrivals(nowMillis, finalArrivals)
            if (stopArrivalsCache.size > 40) {
                val cutoff = nowMillis - STOP_ARRIVALS_CACHE_TTL_MS * 3L
                stopArrivalsCache.entries.removeIf { it.value.fetchedAtMillis < cutoff }
            }
        }
        finalArrivals
    }

    internal fun deduplicateArrivals(
        list: List<StopArrival>,
    ): MutableList<StopArrival> = GttStopArrivalsParser.deduplicateArrivals(list)

    /**
     * Synchronously hydrates verified OSM GTT depot polygons from local 30-day disk cache
     * (and updates [GttFleetAnalyzer.TURIN_DEPOTS] immediately). Returns empty list if not cached yet.
     */
    fun getCachedVerifiedDepotsFast(): List<GttDepot> =
        depotGeometryService.loadCachedDepotsFast()

    /**
     * Ensures real OpenStreetMap compound polygons for Turin's GTT depots are loaded from disk
     * cache or fetched from Overpass API, updating [GttFleetAnalyzer] Point-in-Polygon geofences.
     */
    suspend fun ensureDepotPolygonsLoaded(forceRefresh: Boolean = false): List<GttDepot> =
        depotGeometryService.fetchOrLoadDepots(forceRefresh = forceRefresh)

    companion object {
        private const val TAG = "GttRealtimeRepo"
        private const val USER_AGENT = "ToBusTorino/2.0 (Android Transit Widget)"

        fun shouldRunMatoStream(mode: DataSourceMode): Boolean =
            mode == DataSourceMode.AUTO || mode == DataSourceMode.MATO_LIVE

        private val GTT_VP_URLS = listOf(
            "https://percorsieorari.gtt.to.it/das_gtfsrt/vehicle_position.aspx",
        )
        private val BUSONE_VP_URLS = listOf(
            "https://proxy.busone.app/gtt/gtfs-rt/vehicle-positions.pb",
        )
        private val TRIP_UPDATES_URLS = listOf(
            "https://percorsieorari.gtt.to.it/das_gtfsrt/trip_update.aspx",
            "https://percorsieorari.gtt.to.it/das_gtfsrt/trip_updates.aspx",
            "https://proxy.busone.app/gtt/gtfs-rt/trip-updates.pb",
        )
        private val ALERTS_URLS = listOf(
            "https://percorsieorari.gtt.to.it/das_gtfsrt/alerts.aspx",
            "https://proxy.busone.app/gtt/gtfs-rt/service-alerts.pb",
        )
        private val MATO_GRAPHQL_ENDPOINTS = listOf(
            "https://mato.muoversiatorino.it/otp/gtfs/v1",
            "https://mato.muoversiatorino.it/otp/routers/mato/index/graphql",
            "https://map.5t.torino.it/otp/routers/default/index/graphql",
            "https://mato.muoversiatorino.it/otp/routers/default/index/graphql",
        )
        private val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )
        private const val ACTIVE_LINES_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val MOVEMENT_STATE_DISK_TTL_MS = 45L * 60L * 1000L
        private const val MOVEMENT_STATE_SAVE_INTERVAL_MS = 30_000L
        private const val ALERTS_TTL_MS = 3L * 60L * 1000L
        private const val STOP_ARRIVAL_HORIZON_SEC = GttStopArrivalsParser.STOP_ARRIVAL_HORIZON_SEC
        internal const val OVERLAY_HTTP_CACHE_TTL_MS = GttVehicleMergeEngine.OVERLAY_HTTP_CACHE_TTL_MS
        private const val SINGLE_LINE_LIVE_CACHE_TTL_MS = 25_000L
        private const val SINGLE_LINE_EMPTY_CACHE_TTL_MS = 10_000L
        private const val STOP_ARRIVALS_CACHE_TTL_MS = 12_000L
        private val METRO_LINE_SUMMARY = GttVehicleMergeEngine.METRO_LINE_SUMMARY

        @Volatile
        private var instance: GttRealtimeRepository? = null

        fun getInstance(context: Context): GttRealtimeRepository {
            return instance ?: synchronized(this) {
                instance ?: GttRealtimeRepository(context).also { instance = it }
            }
        }

        // Pure domain facades preserved for backwards compatibility with existing callers & unit tests.

        internal fun filterFreshOverlayVehicles(
            rawVehicles: List<BusVehicle>,
            fetchedAtMillis: Long,
            nowMillis: Long,
            ttlMillis: Long = OVERLAY_HTTP_CACHE_TTL_MS,
        ): List<BusVehicle> = GttVehicleMergeEngine.filterFreshOverlayVehicles(
            rawVehicles = rawVehicles,
            fetchedAtMillis = fetchedAtMillis,
            nowMillis = nowMillis,
            ttlMillis = ttlMillis,
        )

        internal fun enrichWithTripUpdates(
            vehicles: List<BusVehicle>,
            activeTripUpdates: List<TripDelayInfo>,
        ): List<BusVehicle> = GttVehicleMergeEngine.enrichWithTripUpdates(
            vehicles = vehicles,
            activeTripUpdates = activeTripUpdates,
        )

        internal fun updateLineSummariesForFocusLine(
            baseSummaries: List<LineSummary>,
            vehicles: List<BusVehicle>,
            focusLine: String?,
        ): List<LineSummary> = GttVehicleMergeEngine.updateLineSummariesForFocusLine(
            baseSummaries = baseSummaries,
            vehicles = vehicles,
            focusLine = focusLine,
        )

        internal fun mergeLineSummariesWithCatalog(
            liveSummaries: List<LineSummary>,
            catalogSummaries: List<LineSummary>,
        ): List<LineSummary> = GttVehicleMergeEngine.mergeLineSummariesWithCatalog(
            liveSummaries = liveSummaries,
            catalogSummaries = catalogSummaries,
        )

        internal fun mergeLiveSummariesWithStaticCatalog(
            liveSummaries: List<LineSummary>,
            staticCatalog: List<LineSummary>,
        ): List<LineSummary> = GttVehicleMergeEngine.mergeLiveSummariesWithStaticCatalog(
            liveSummaries = liveSummaries,
            staticCatalog = staticCatalog,
        )

        internal fun mergeMultiSourceVehicles(
            matoVehicles: List<BusVehicle>,
            gttVehicles: List<BusVehicle>,
            busOneVehicles: List<BusVehicle>,
            nowMillis: Long,
            focusLine: String? = null,
            sourceReports: Map<String, SourceHealthReport> = emptyMap(),
            previousPoses: Map<String, GttVehicleMergeEngine.VehicleKinematicPose> = emptyMap(),
            matoRetainedCandidates: List<BusVehicle> = emptyList(),
            onDecision: ((PipelineBusDecision) -> Unit)? = null,
        ): List<BusVehicle> = GttVehicleMergeEngine.mergeMultiSourceVehicles(
            matoVehicles = matoVehicles,
            gttVehicles = gttVehicles,
            busOneVehicles = busOneVehicles,
            nowMillis = nowMillis,
            focusLine = focusLine,
            sourceReports = sourceReports,
            previousPoses = previousPoses,
            matoRetainedCandidates = matoRetainedCandidates,
            onDecision = onDecision,
        )

        internal fun parseGttPalinaJson(
            jsonText: String,
            nowMillis: Long,
        ): List<StopArrival> = GttStopArrivalsParser.parseGttPalinaJson(jsonText, nowMillis)

        internal fun resolveStopDirectionOnLinePatterns(
            stop: TransitStop,
            linePatterns: List<RoutePattern>,
        ): Int? = GttStopArrivalsReconciler.resolveStopDirectionOnLinePatterns(stop, linePatterns)

        internal fun findExactBusForArrival(
            arrival: StopArrival,
            stop: TransitStop,
            allLivePool: List<BusVehicle>,
            targetLinePatterns: List<RoutePattern>,
            claimedVehicleIds: Set<String> = emptySet(),
            nowMillis: Long,
        ): BusVehicle? = GttStopArrivalsReconciler.findExactBusForArrival(
            arrival = arrival,
            stop = stop,
            allLivePool = allLivePool,
            targetLinePatterns = targetLinePatterns,
            claimedVehicleIds = claimedVehicleIds,
            nowMillis = nowMillis,
        )

        internal fun reconcileLiveVehiclesWithArrivals(
            stop: TransitStop,
            preferredLine: String,
            liveVehicles: List<BusVehicle>,
            allLivePool: List<BusVehicle>,
            routePatterns: List<RoutePattern>,
            rawArrivals: List<StopArrival>,
            nowMillis: Long,
            otherLinePatterns: Map<String, List<RoutePattern>> = emptyMap(),
        ): List<StopArrival> = GttStopArrivalsReconciler.reconcileLiveVehiclesWithArrivals(
            stop = stop,
            preferredLine = preferredLine,
            liveVehicles = liveVehicles,
            allLivePool = allLivePool,
            routePatterns = routePatterns,
            rawArrivals = rawArrivals,
            nowMillis = nowMillis,
            otherLinePatterns = otherLinePatterns,
        )

        internal fun deduplicateArrivals(
            list: List<StopArrival>,
        ): MutableList<StopArrival> = GttStopArrivalsParser.deduplicateArrivals(list)
    }
}
