package com.example.data.network

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.data.model.BusVehicle
import com.example.data.model.DataSourceMode
import com.example.data.model.DataSourceStatus
import com.example.data.model.GeoPoint
import com.example.data.model.GttDataOutageReport
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
    private val routeMemoryCache = ConcurrentHashMap<String, List<RoutePattern>>()
    private val stopNameMemoryCache = ConcurrentHashMap<String, String>()
    private val routeCacheDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(appContext.cacheDir, "gtt_routes").apply { mkdirs() }
    }
    private val widgetVehiclesCacheFile: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(appContext.cacheDir, "widget_line_vehicles_v1.json")
    }
    private val activeLinesCacheFile: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(appContext.cacheDir, "active_lines_v1.tsv")
    }

    private data class MovementRecord(
        val lat: Double,
        val lon: Double,
        val lastMovedAtMillis: Long,
        val firstSeenAtPositionMillis: Long,
        val inferredBearing: Float? = null,
    )

    private data class CachedSingleLineVehicles(
        val fetchedAtMillis: Long,
        val vehicles: List<BusVehicle>,
    )

    private val movementTracker = ConcurrentHashMap<String, MovementRecord>()
    private val sourceHealthTracker = SourceHealthTracker()
    private val outageDetector: GttServiceOutageDetector by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GttServiceOutageDetector(appContext) { matoFastHttpClient }
    }
    private val singleLineLiveCache = ConcurrentHashMap<String, CachedSingleLineVehicles>()

    @Volatile
    private var lastSnapshot: LiveFeedSnapshot? = null

    @Volatile
    private var lastSourceReports: Map<String, SourceHealthReport> = emptyMap()

    @Volatile
    private var lastRouteLoadSummary: String = "Nessun percorso caricato"

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
    private var lastTripUpdates: List<TripDelayInfo> = emptyList()

    @Volatile
    private var lastTripUpdatesFetchMillis: Long = 0L

    @Volatile
    private var cachedAlerts: List<ServiceAlertInfo> = emptyList()

    @Volatile
    private var lastAlertsFetchMillis: Long = 0L

    @Volatile
    private var preferredMatoVpEndpointIdx: Int = 0

    @Volatile
    private var preferredMatoVpQueryIdx: Int = 0

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

    fun resetHealthAndOutageTrackers() {
        sourceHealthTracker.reset()
        movementTracker.clear()
        outageDetector.reset()
        lastSourceReports = emptyMap()
        ToBusDiagnosticsLog.record(
            category = DiagCategory.DEBUG,
            message = "Azzerati tracker GPS bloccati, movimento e rilevatore disservizi",
            severity = DiagSeverity.OK,
        )
    }

    fun getDebugRuntimeTelemetry(): DebugRuntimeTelemetry {
        val diskFiles = runCatching { routeCacheDir.listFiles()?.count { it.isFile } ?: 0 }.getOrDefault(0)
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
            ),
            sourceMotion = motion,
            routeCacheRamCount = routeMemoryCache.size,
            routeCacheDiskCount = diskFiles,
            movementTrackedCount = movementTracker.size,
            tripUpdatesCount = lastTripUpdates.size,
            staticStoreSummary = staticRouteStore.getCacheDiagnosticSummary(),
            lastRouteLoadSummary = lastRouteLoadSummary,
            lastStopArrivalsSummary = lastStopArrivalsSummary,
            matoGqlPreferredEndpointIdx = preferredMatoVpEndpointIdx,
            matoGqlPreferredQueryIdx = preferredMatoVpQueryIdx,
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
                    val v = runCatching { fetchMatoGraphQlVehiclePositions(normLine, nowMillis) }.getOrDefault(emptyList())
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
                    add("MaTO GraphQL (L.$normLine): $matoCount mezzi · ${matoMs}ms (ep#$preferredMatoVpEndpointIdx/q#$preferredMatoVpQueryIdx)")
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
        val msg = "Percorso L.$norm ricaricato in ${elapsed}ms: ${patterns.size} direzioni, $totalStops fermate, $totalPoints punti [$dirs] ($lastRouteLoadSummary)"
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
        routeMemoryCache.clear()
        stopNameMemoryCache.clear()
        var deleted = 0
        runCatching {
            routeCacheDir.listFiles()?.forEach { f ->
                if (f.isFile && f.delete()) deleted++
            }
        }
        runCatching { if (activeLinesCacheFile.exists() && activeLinesCacheFile.delete()) deleted++ }
        runCatching { if (widgetVehiclesCacheFile.exists() && widgetVehiclesCacheFile.delete()) deleted++ }
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
        val nowMillis = System.currentTimeMillis()
        val matoLive = matoMqttClient.snapshotFreshVehicles(nowMillis)
        val connected = matoMqttClient.isConnected()
        val isFixedMato = dataSourceMode == DataSourceMode.MATO_LIVE
        val lastErr = matoMqttClient.lastError()
        if (matoLive.isEmpty() && !connected && !(isFixedMato && lastErr != null)) return null

        val base = lastSnapshot
        if (matoLive.isEmpty() && base != null) {
            // MQTT connected (waiting/diagnostics) or fixed MaTO errored -> reflect real live diagnostic state
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
                    allVehicles = base.allVehicles.filter { it.sourceName == "MaTO Live" },
                    sourceStatuses = updatedStatuses,
                )
            } else {
                base.copy(sourceStatuses = updatedStatuses)
            }
            lastSnapshot = updated
            return updated
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

        val merged = mergeMultiSourceVehicles(
            matoVehicles = matoCombined,
            gttVehicles = gttInputs,
            busOneVehicles = busOneInputs,
            nowMillis = nowMillis,
            focusLine = activeFocus,
            sourceReports = sourceReports,
        )
        val enriched = enrichWithTripUpdatesAndMovement(merged, lastTripUpdates, nowMillis)

        val anyGtfsFrozen = !isFixedMato && (
            sourceReports["GTT Ufficiale"]?.isFrozenForLine(activeFocus ?: "", activeFocus) == true ||
                sourceReports["BusOne Proxy"]?.isFrozenForLine(activeFocus ?: "", activeFocus) == true
            )
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
            updateLineSummariesForFocusLine(base.availableLines, enriched, activeFocus)
        } else {
            GtfsRtProtobufParser.buildLineSummaries(enriched)
        }

        val updated = LiveFeedSnapshot(
            feedTimestampSeconds = feedTs,
            fetchedAtMillis = nowMillis,
            allVehicles = enriched,
            availableLines = nextSummaries,
            sourceStatuses = updatedStatuses,
            alerts = base?.alerts ?: cachedAlerts,
        )
        lastSnapshot = updated
        return updated
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

            val gttTimeoutMs = if (widgetFastMode) 5_000L else 8_000L
            val busOneTimeoutMs = if (widgetFastMode) 5_000L else 7_000L
            val matoGqlTimeoutMs = if (widgetFastMode) 4_800L else 6_000L

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
                // Always query MaTO GraphQL when MaTO is enabled and focusLine is set — BOTH in foreground AND widgetFastMode.
                val matoGqlJob = if (useMato && !normFocus.isNullOrBlank()) {
                    async {
                        withTimeoutOrNull(matoGqlTimeoutMs) {
                            fetchMatoGraphQlVehiclePositions(normFocus, nowMillis)
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
                val alertsJob = if (includeAlerts && includeSecondaryFeeds && !widgetFastMode && nowMillis - lastAlertsFetchMillis > ALERTS_TTL_MS) {
                    async {
                        withTimeoutOrNull(5_000L) { fetchAlertsSource() }
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

                if (useGtt) {
                    if (gttRes?.snapshot != null) {
                        lastGttRawVehicles = gttVehicles
                        lastGttFetchedAtMillis = nowMillis
                    } else {
                        lastGttRawVehicles = emptyList()
                        lastGttFetchedAtMillis = 0L
                    }
                } else {
                    lastGttRawVehicles = emptyList()
                    lastGttFetchedAtMillis = 0L
                }
                if (useBusOne) {
                    if (busOneRes?.snapshot != null) {
                        lastBusOneRawVehicles = busOneVehicles
                        lastBusOneFetchedAtMillis = nowMillis
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
                    lastMatoGqlFetchedAtMillis = nowMillis
                } else if (!useMato) {
                    lastMatoGqlRawVehicles = emptyList()
                    lastMatoGqlFetchedAtMillis = 0L
                }

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
                        preferredMatoVpEndpointIdx = 0
                        preferredMatoVpQueryIdx = 0
                        async {
                            withTimeoutOrNull(3_500L) {
                                fetchMatoGraphQlVehiclePositions(normFocus, nowMillis)
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
                        lastMatoGqlRawVehicles = retriedMato
                        lastMatoGqlFetchedAtMillis = nowMillis
                        matoCombined = mergeMultiSourceVehicles(
                            matoVehicles = matoMqttVehicles,
                            gttVehicles = matoGqlVehicles,
                            busOneVehicles = emptyList(),
                            nowMillis = nowMillis,
                        )
                        rawReports["MaTO Live"] = sourceHealthTracker.recordAndEvaluate("MaTO Live", matoCombined, normFocus, nowMillis)
                    }
                    val fallbackBusOne = fallbackBusOneDeferred?.await()
                    if (fallbackBusOne != null) {
                        busOneRes = fallbackBusOne
                        busOneVehicles = fallbackBusOne.snapshot?.allVehicles.orEmpty()
                        if (fallbackBusOne.snapshot != null) {
                            lastBusOneRawVehicles = busOneVehicles
                            lastBusOneFetchedAtMillis = nowMillis
                        }
                        if (busOneVehicles.isNotEmpty()) {
                            rawReports["BusOne Proxy"] = sourceHealthTracker.recordAndEvaluate("BusOne Proxy", busOneVehicles, normFocus, nowMillis)
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

                // Merge by physical vehicleId prioritizing non-frozen & actively moving sources
                // while preserving every unique bus so vehicles never disappear during fallbacks.
                val merged = mergeMultiSourceVehicles(
                    matoVehicles = matoCombined,
                    gttVehicles = gttVehicles,
                    busOneVehicles = busOneVehicles,
                    nowMillis = nowMillis,
                    focusLine = normFocus,
                    sourceReports = if (allowCrossSourceFallback) sourceReports else emptyMap(),
                )

                val activeTripUpdates = tripRes?.updates ?: lastTripUpdates
                val enriched = enrichWithTripUpdatesAndMovement(merged, activeTripUpdates, nowMillis)

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
                        nowMillis = nowMillis,
                    )
                } else {
                    outageDetector.currentActiveOutage(nowMillis)
                }

                if (!widgetFastMode) {
                    val gttTag = gttRes?.let { "GTT=${it.status.itemCount}(${it.status.latencyMs}ms${if (finalGttFrozen) ",FROZEN" else ""})" } ?: "GTT=skip"
                    val busOneTag = busOneRes?.let { "BusOne=${it.status.itemCount}(${it.status.latencyMs}ms${if (finalBusOneFrozen) ",FROZEN" else ""})" } ?: "BusOne=skip"
                    val matoTag = "MaTO=${matoMqttVehicles.size}mq+${matoGqlVehicles.size}gql"
                    ToBusDiagnosticsLog.record(
                        category = DiagCategory.POLL,
                        message = "Poll ${effectiveMode.shortLabelIt} OK · ${enriched.size} mezzi (L.${normFocus ?: "-"})",
                        detail = "$gttTag · $busOneTag · $matoTag · TU=${activeTripUpdates.size}",
                        severity = if (outageReport != null && !outageReport.isSimulated) DiagSeverity.WARN else DiagSeverity.OK,
                    )
                }

                val rawSummaries = GtfsRtProtobufParser.buildLineSummaries(enriched)
                if (rawSummaries.size > 1 || (effectiveMode != DataSourceMode.MATO_LIVE && rawSummaries.isNotEmpty())) {
                    saveActiveLinesCache(rawSummaries)
                }
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
                    fetchedAtMillis = nowMillis,
                    allVehicles = enriched,
                    availableLines = summaries,
                    sourceStatuses = statuses,
                    alerts = if (includeAlerts) cachedAlerts else emptyList(),
                    outageReport = outageReport,
                )
                lastSnapshot = finalSnapshot
                if (!normFocus.isNullOrBlank()) {
                    val lineBuses = enriched.filter {
                        it.lineCode.equals(normFocus, ignoreCase = true) ||
                            it.rawRouteId.equals(normFocus, ignoreCase = true)
                    }
                    if (lineBuses.isNotEmpty()) {
                        saveWidgetLineVehiclesCache(normFocus, nowMillis, lineBuses)
                    }
                }
                Result.success(finalSnapshot)
            }
        }

    private fun enrichWithTripUpdatesAndMovement(
        vehicles: List<BusVehicle>,
        activeTripUpdates: List<TripDelayInfo>,
        nowMillis: Long,
    ): List<BusVehicle> {
        val withDelays = enrichWithTripUpdates(vehicles, activeTripUpdates)
        val enriched = withDelays.map { bus ->
            val canonVid = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId }
            val prev = movementTracker[canonVid]
            val movedDistMeters = if (prev != null) {
                haversineMeters(prev.lat, prev.lon, bus.latitude, bus.longitude)
            } else {
                Double.POSITIVE_INFINITY
            }
            val moved = prev == null || movedDistMeters >= 15.0

            val computedBearing = when {
                bus.bearing != null -> bus.bearing
                prev != null && movedDistMeters in 18.0..1800.0 ->
                    initialBearingDeg(prev.lat, prev.lon, bus.latitude, bus.longitude).toFloat()
                prev != null -> prev.inferredBearing
                else -> null
            }

            val record = if (prev == null || movedDistMeters >= 15.0) {
                MovementRecord(
                    lat = bus.latitude,
                    lon = bus.longitude,
                    lastMovedAtMillis = nowMillis,
                    firstSeenAtPositionMillis = nowMillis,
                    inferredBearing = computedBearing,
                )
            } else {
                prev.copy(inferredBearing = computedBearing ?: prev.inferredBearing)
            }
            movementTracker[canonVid] = record

            bus.copy(
                bearing = bus.bearing ?: record.inferredBearing,
                lastMovedAtMillis = record.lastMovedAtMillis,
                stationarySinceMillis = if (!moved) record.firstSeenAtPositionMillis else null,
            )
        }

        // Prune stale vehicle records older than 30 min so long-running sessions stay lean
        if (movementTracker.size > 800) {
            val cutoff = nowMillis - 30 * 60_000L
            movementTracker.entries.removeIf { it.value.lastMovedAtMillis < cutoff }
        }
        return enriched
    }

    /**
     * Queries MaTO / 5T OpenTripPlanner GraphQL `vehiclePositions` for the currently focused line
     * as an instant REST complement to the MaTO MQTT WebSocket stream.
     */
    private fun fetchMatoGraphQlVehiclePositions(
        lineCode: String,
        nowMillis: Long,
    ): List<BusVehicle> {
        val cleanLine = lineCode.trim().uppercase()
        if (cleanLine.isEmpty()) return emptyList()
        val safeLine = cleanLine.replace("\"", "").replace("\\", "")
        val queries = listOf(
            """
            {
              routes(name: "$safeLine") {
                gtfsId
                shortName
                patterns {
                  directionId
                  vehiclePositions {
                    vehicleId
                    label
                    lat
                    lon
                    heading
                    speed
                    lastUpdated
                    trip { gtfsId }
                    stopRelationship {
                      status
                      stop { gtfsId code }
                    }
                  }
                }
              }
            }
            """.trimIndent(),
            """
            {
              routes(name: "$safeLine") {
                gtfsId
                shortName
                patterns {
                  directionId
                  vehiclePositions {
                    vehicleId
                    label
                    lat
                    lon
                    heading
                    speed
                    lastUpdated
                    trip { gtfsId }
                  }
                }
              }
            }
            """.trimIndent(),
        )

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val nowSec = nowMillis / 1000L

        // OTP2 `/otp/gtfs/v1` first for `vehiclePositions` support, then OTP1/plan endpoints
        val vpEndpoints = listOf(
            "https://mato.muoversiatorino.it/otp/gtfs/v1",
            "https://plan.muoversiatorino.it/otp/gtfs/v1",
            "https://mato.muoversiatorino.it/otp/routers/mato/index/graphql",
            "https://plan.muoversiatorino.it/otp/routers/mato/index/graphql",
            "https://map.5t.torino.it/otp/routers/default/index/graphql",
            "https://mato.muoversiatorino.it/otp/routers/default/index/graphql",
        )

        // Order endpoints & query variants starting from the last known working pair
        val endpointOrder = vpEndpoints.indices.sortedBy { if (it == preferredMatoVpEndpointIdx) 0 else 1 }
        val queryOrder = queries.indices.sortedBy { if (it == preferredMatoVpQueryIdx) 0 else 1 }

        for (epIdx in endpointOrder) {
            val endpoint = vpEndpoints[epIdx]
            var endpointHttpFailed = false
            for (qIdx in queryOrder) {
                if (endpointHttpFailed) break
                val query = queries[qIdx]
                try {
                    val payloadJson = JSONObject().put("query", query).toString()
                    val req = Request.Builder()
                        .url(endpoint)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .header("Origin", "https://mato.muoversiatorino.it")
                        .post(payloadJson.toRequestBody(mediaType))
                        .build()

                    matoFastHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            if (resp.code == 404 || resp.code == 405 || resp.code >= 500) {
                                endpointHttpFailed = true
                            }
                            continue
                        }
                        val body = resp.body?.string() ?: continue
                        val root = JSONObject(body)
                        val errors = root.optJSONArray("errors")
                        val routes = root.optJSONObject("data")?.optJSONArray("routes")
                        if (routes == null) {
                            if (errors != null && errors.toString().contains("vehiclePositions", ignoreCase = true)) {
                                // This OTP instance schema doesn't expose Pattern.vehiclePositions at all
                                endpointHttpFailed = true
                            }
                            continue
                        }
                        val out = mutableListOf<BusVehicle>()

                        for (r in 0 until routes.length()) {
                            val routeObj = routes.optJSONObject(r) ?: continue
                            val shortName = routeObj.optString("shortName").trim()
                            val rawRouteId = routeObj.optString("gtfsId").substringAfterLast(":").ifBlank { shortName }
                            val normRoute = GtfsRtProtobufParser.normalizeGttLineCode(shortName.ifBlank { rawRouteId })
                            if (!normRoute.equals(cleanLine, ignoreCase = true) &&
                                !shortName.equals(cleanLine, ignoreCase = true)
                            ) {
                                continue
                            }

                            val patterns = routeObj.optJSONArray("patterns") ?: continue
                            for (p in 0 until patterns.length()) {
                                val patObj = patterns.optJSONObject(p) ?: continue
                                val patDir = if (patObj.isNull("directionId")) null else patObj.optInt("directionId")
                                val vpArr = patObj.optJSONArray("vehiclePositions") ?: continue
                                for (vIdx in 0 until vpArr.length()) {
                                    val vp = vpArr.optJSONObject(vIdx) ?: continue
                                    val lat = vp.optDouble("lat", Double.NaN)
                                    val lon = vp.optDouble("lon", Double.NaN)
                                    if (!lat.isFinite() || !lon.isFinite() || lat !in 44.0..46.5 || lon !in 6.5..9.0) continue

                                    val rawVeh = vp.optString("vehicleId").substringAfterLast(":").trim()
                                        .ifBlank { vp.optString("label").trim() }
                                    val cleanVeh = GtfsRtProtobufParser.canonicalFleetId(rawVeh)
                                    if (cleanVeh.isEmpty()) continue
                                    val cleanLabel = GtfsRtProtobufParser.canonicalFleetId(vp.optString("label"))
                                        .ifBlank { cleanVeh }

                                    val heading = if (vp.isNull("heading")) null else {
                                        vp.optDouble("heading", Double.NaN).toFloat()
                                            .takeIf { it.isFinite() && it in 0f..360f }
                                    }
                                    val speedKmh = if (vp.isNull("speed")) null else {
                                        vp.optDouble("speed", Double.NaN).toFloat()
                                            .takeIf { it.isFinite() && it >= 0f }
                                            ?.let { s -> if (s <= 25f) s * 3.6f else s }
                                    }
                                    val lastUpdRaw = vp.optLong("lastUpdated", 0L)
                                    val normalizedUpd = if (lastUpdRaw > 1_000_000_000_000L) lastUpdRaw / 1000L else lastUpdRaw
                                    val tsSec = when {
                                        normalizedUpd <= 0L -> 0L
                                        normalizedUpd > nowSec + 120L -> nowSec
                                        normalizedUpd >= 1_600_000_000L -> normalizedUpd
                                        else -> 0L
                                    }
                                    val tripId = GtfsRtProtobufParser.normalizeTripId(
                                        vp.optJSONObject("trip")?.optString("gtfsId"),
                                    )
                                    val stopRel = vp.optJSONObject("stopRelationship")
                                    val stopObj = stopRel?.optJSONObject("stop")
                                    val stopId = stopObj?.optString("code")?.takeIf { it.isNotBlank() }
                                        ?: stopObj?.optString("gtfsId")?.substringAfterLast(":")?.takeIf { it.isNotBlank() }
                                    val status = when (stopRel?.optString("status")?.uppercase()) {
                                        "INCOMING_AT" -> VehicleStopStatus.INCOMING_AT
                                        "STOPPED_AT" -> VehicleStopStatus.STOPPED_AT
                                        else -> VehicleStopStatus.IN_TRANSIT_TO
                                    }

                                    out.add(
                                        BusVehicle(
                                            entityId = "mato_gql_${cleanVeh}_$normRoute",
                                            vehicleId = cleanVeh,
                                            label = cleanLabel,
                                            rawRouteId = rawRouteId.uppercase(),
                                            lineCode = normRoute,
                                            tripId = tripId,
                                            directionId = patDir,
                                            latitude = lat,
                                            longitude = lon,
                                            bearing = heading,
                                            speedKmh = speedKmh,
                                            timestampSeconds = tsSec,
                                            stopId = stopId,
                                            status = status,
                                            sourceName = "MaTO Live",
                                            contributingSources = setOf("MaTO Live"),
                                            nextStopId = stopId,
                                            fetchedAtMillis = nowMillis,
                                        ),
                                    )
                                }
                            }
                        }
                        if (out.isNotEmpty()) {
                            preferredMatoVpEndpointIdx = epIdx
                            preferredMatoVpQueryIdx = qIdx
                            return out
                        }
                    }
                } catch (e: Exception) {
                    endpointHttpFailed = true
                    Log.d(TAG, "MaTO GraphQL VP skip $endpoint: ${e.message}")
                }
            }
        }
        return emptyList()
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
            fetchMatoGraphQlVehiclePositions(clean, nowMillis)
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
     * Persists the latest non-empty vehicle list for [lineCode] so Home Screen Widgets survive
     * process death + transient Doze network timeouts without flashing "0 mezzi".
     */
    fun saveWidgetLineVehiclesCache(lineCode: String, fetchedAtMillis: Long, vehicles: List<BusVehicle>) {
        runCatching {
            val arr = JSONArray()
            vehicles.forEach { v ->
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
                    },
                )
            }
            val root = JSONObject().apply {
                put("lineCode", lineCode.trim().uppercase())
                put("fetchedAtMillis", fetchedAtMillis)
                put("vehicles", arr)
            }
            widgetVehiclesCacheFile.writeText(root.toString())
        }
    }

    /**
     * Loads the last persisted widget vehicles for [lineCode] if still fresh (within [maxAgeMillis]).
     */
    fun loadFreshWidgetLineVehicles(
        lineCode: String,
        maxAgeMillis: Long = 12 * 60_000L,
    ): Pair<Long, List<BusVehicle>>? {
        return runCatching {
            val file = widgetVehiclesCacheFile
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            val cachedLine = root.optString("lineCode").trim().uppercase()
            if (!cachedLine.equals(lineCode.trim().uppercase(), ignoreCase = true)) return null
            val fetchedAt = root.optLong("fetchedAtMillis", 0L)
            if (fetchedAt <= 0L || System.currentTimeMillis() - fetchedAt > maxAgeMillis) return null
            val arr = root.optJSONArray("vehicles") ?: return null
            val list = ArrayList<BusVehicle>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue
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
                        sourceName = obj.optString("sourceName", "Cache"),
                        contributingSources = setOf(obj.optString("sourceName", "Cache")),
                        fetchedAtMillis = fetchedAt,
                    ),
                )
            }
            if (list.isEmpty()) null else fetchedAt to list
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
                        fetchMatoGraphQlLineCatalog()
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

    private fun fetchMatoGraphQlLineCatalog(): List<LineSummary> {
        val queries = listOf(
            """
            {
              routes(feeds: ["gtt"]) {
                gtfsId
                shortName
              }
            }
            """.trimIndent(),
            """
            {
              routes {
                gtfsId
                shortName
              }
            }
            """.trimIndent(),
        )
        val mediaType = "application/json; charset=utf-8".toMediaType()
        for (endpoint in MATO_GRAPHQL_ENDPOINTS) {
            for (query in queries) {
                try {
                    val payload = JSONObject().put("query", query).toString()
                    val request = Request.Builder()
                        .url(endpoint)
                        .post(payload.toRequestBody(mediaType))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .header("Origin", "https://mato.muoversiatorino.it")
                        .build()

                    matoFastHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) continue
                        val body = response.body?.string() ?: continue
                        val root = JSONObject(body)
                        val routes = root.optJSONObject("data")?.optJSONArray("routes") ?: continue
                        if (routes.length() == 0) continue

                        val rawIdsByLine = HashMap<String, LinkedHashSet<String>>(160)
                        for (i in 0 until routes.length()) {
                            val obj = routes.optJSONObject(i) ?: continue
                            val gtfsId = obj.optString("gtfsId", "").trim()
                            if (gtfsId.contains(':') &&
                                !gtfsId.startsWith("gtt:", ignoreCase = true) &&
                                !gtfsId.startsWith("1:", ignoreCase = true)
                            ) {
                                continue
                            }
                            val rawId = gtfsId.substringAfter(":").trim()
                            val shortName = obj.optString("shortName", "").trim()
                            val candidate = shortName.ifBlank { rawId }
                            if (candidate.isEmpty()) continue
                            val normalized = GtfsRtProtobufParser.normalizeGttLineCode(candidate)
                            if (normalized.isNotBlank() && normalized != "?") {
                                val set = rawIdsByLine.getOrPut(normalized) { LinkedHashSet(2) }
                                if (rawId.isNotEmpty()) set.add(rawId)
                                if (shortName.isNotEmpty()) set.add(shortName)
                            }
                        }
                        if (rawIdsByLine.isNotEmpty()) {
                            return rawIdsByLine.entries
                                .map { (lineCode, rawIds) ->
                                    LineSummary(
                                        lineCode = lineCode,
                                        rawRouteIds = rawIds.ifEmpty { setOf(lineCode) },
                                        activeVehicleCount = 0,
                                    )
                                }
                                .sortedWith(GtfsRtProtobufParser.lineCodeComparator)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "MATO GraphQL catalogo linee skip su $endpoint: ${e.message}")
                }
            }
        }
        return emptyList()
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
                    val list = rawList
                        .filter { it.isCurrentOrUpcoming(nowEpochSeconds = nowSec, maxHorizonDays = 7) }
                        .sortedWith(
                            compareByDescending<ServiceAlertInfo> { it.isActiveNow(nowSec) }
                                .thenBy { it.earliestRelevantStartSeconds(nowSec) },
                        )
                    val latency = SystemClock.elapsedRealtime() - start
                    ToBusDiagnosticsLog.record(
                        category = DiagCategory.ALERTS,
                        message = "Avvisi GTT scaricati: ${list.size} attuali (su ${rawList.size} totali)",
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
     * Ultra-fast official street-by-street route & stops loader for Turin GTT lines:
     * 1. Same-day RAM / Disk cache (`route_v5_<LINE>_<YYYYMMDD>.json`) -> < 15ms
     * 2. Daily-updated MATO / 5T GraphQL with `tripsForDate(serviceDate)` + live `activeTripIds`
     *    (automatically matches Sunday/festivo and real-time detours like Infomezzi!)
     * 3. Official GTT Static GTFS ZIP filtered by `activeTripIds` + today's `calendar.txt`/`calendar_dates.txt`
     * 4. OpenStreetMap Overpass API fallback
     */
    suspend fun fetchRoutePatterns(
        lineCode: String,
        activeTripIds: Set<String> = emptySet(),
        forceRefresh: Boolean = false,
    ): List<RoutePattern> = withContext(Dispatchers.IO) {
        val normalized = GtfsRtProtobufParser.normalizeGttLineCode(lineCode)
        if (normalized.isEmpty() || normalized == "?") return@withContext emptyList()

        val nowMillis = System.currentTimeMillis()
        val todayYyyyMmDd = formatRomeYyyyMmDd(nowMillis)
        val memKey = "${normalized}_$todayYyyyMmDd"

        if (!forceRefresh) {
            routeMemoryCache[memKey]?.let { return@withContext it }
        }

        val safeKey = normalized.replace(SAFE_ROUTE_KEY_REGEX, "_")
        val diskFile = File(routeCacheDir, "route_v5_${safeKey}_$todayYyyyMmDd.json")
        if (!forceRefresh && diskFile.exists() && nowMillis - diskFile.lastModified() < ROUTE_CACHE_TTL_MS) {
            runCatching { parseCachedPatterns(diskFile.readText()) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    cacheRouteStops(it)
                    routeMemoryCache[memKey] = it
                    routeMemoryCache[normalized] = it
                    return@withContext it
                }
        }

        val routeFetchStart = SystemClock.elapsedRealtime()
        var routeSourceTag = "none"

        // Prefer daily-updated MATO GraphQL filtered by today's active serviceDate & live tripIds
        // so Sunday deviations and live detours match MaTO / Infomezzi 1-to-1.
        var fetched: List<RoutePattern> = queryMatoGraphQl(normalized, todayYyyyMmDd, activeTripIds)
        if (fetched.isNotEmpty()) {
            routeSourceTag = "MaTO GraphQL ($normalized)"
        }
        if (fetched.isEmpty()) {
            for (altQuery in GtfsRtProtobufParser.alternateFeedQueryCodes(normalized)) {
                fetched = queryMatoGraphQl(altQuery, todayYyyyMmDd, activeTripIds, matchTargetLine = normalized)
                if (fetched.isNotEmpty()) {
                    routeSourceTag = "MaTO GraphQL (alt:$altQuery)"
                    break
                }
            }
        }

        if (fetched.isEmpty()) {
            fetched = staticRouteStore.getRoutePatternsForLine(normalized, nowMillis, activeTripIds)
            if (fetched.isNotEmpty()) routeSourceTag = "GTFS Statico"
        }

        // Last-resort OpenStreetMap Overpass API fallback
        if (fetched.isEmpty()) {
            fetched = queryOverpassTurinRoute(normalized)
            if (fetched.isNotEmpty()) {
                routeSourceTag = "OSM Overpass ($normalized)"
            } else {
                for (altQuery in GtfsRtProtobufParser.alternateFeedQueryCodes(normalized)) {
                    fetched = queryOverpassTurinRoute(altQuery)
                    if (fetched.isNotEmpty()) {
                        routeSourceTag = "OSM Overpass (alt:$altQuery)"
                        break
                    }
                }
            }
        }

        val routeElapsedMs = SystemClock.elapsedRealtime() - routeFetchStart
        val totalStops = fetched.sumOf { it.stops.size }
        lastRouteLoadSummary = "L.$normalized via $routeSourceTag (${fetched.size} dir, $totalStops ferm, ${routeElapsedMs}ms)"
        ToBusDiagnosticsLog.record(
            category = DiagCategory.ROUTE,
            message = "Percorso L.$normalized: $routeSourceTag",
            detail = "dir=${fetched.size} fermate=$totalStops ${routeElapsedMs}ms liveTrips=${activeTripIds.size}",
            severity = if (fetched.isNotEmpty()) DiagSeverity.OK else DiagSeverity.WARN,
        )

        if (fetched.isNotEmpty()) {
            cacheRouteStops(fetched)
            routeMemoryCache[memKey] = fetched
            routeMemoryCache[normalized] = fetched
            runCatching { diskFile.writeText(serializePatterns(fetched)) }
        }
        fetched
    }

    /**
     * Low-latency route loader for stop arrival verification & cross-line bus focusing:
     * checks same-day RAM/disk cache first, then local GTFS zip (if cached on disk),
     * and finally bounded network fetch (max 2.2s) so stop interactions stay instantaneous.
     */
    suspend fun getFastRoutePatternsForLine(
        lineCode: String,
        activeTripIds: Set<String> = emptySet(),
    ): List<RoutePattern> = withContext(Dispatchers.IO) {
        val normalized = GtfsRtProtobufParser.normalizeGttLineCode(lineCode)
        if (normalized.isEmpty() || normalized == "?") return@withContext emptyList()

        val nowMillis = System.currentTimeMillis()
        val todayYyyyMmDd = formatRomeYyyyMmDd(nowMillis)
        val memKey = "${normalized}_$todayYyyyMmDd"

        routeMemoryCache[memKey]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

        val safeKey = normalized.replace(SAFE_ROUTE_KEY_REGEX, "_")
        val diskFile = File(routeCacheDir, "route_v5_${safeKey}_$todayYyyyMmDd.json")
        if (diskFile.exists() && nowMillis - diskFile.lastModified() < ROUTE_CACHE_TTL_MS) {
            runCatching { parseCachedPatterns(diskFile.readText()) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    cacheRouteStops(it)
                    routeMemoryCache[memKey] = it
                    routeMemoryCache[normalized] = it
                    return@withContext it
                }
        }

        if (staticRouteStore.hasCachedGtfsZip()) {
            val fromZip = runCatching {
                staticRouteStore.getRoutePatternsForLine(normalized, nowMillis, activeTripIds)
            }.getOrNull().orEmpty()
            if (fromZip.isNotEmpty()) {
                cacheRouteStops(fromZip)
                routeMemoryCache[memKey] = fromZip
                routeMemoryCache[normalized] = fromZip
                return@withContext fromZip
            }
        }

        withTimeoutOrNull(3_600L) {
            fetchRoutePatterns(normalized, activeTripIds)
        }.orEmpty()
    }

    private fun formatRomeYyyyMmDd(nowMillis: Long): String {
        val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone("Europe/Rome")).apply {
            timeInMillis = nowMillis
        }
        val y = cal.get(Calendar.YEAR)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        return "%04d%02d%02d".format(y, m, d)
    }

    private fun cacheRouteStops(patterns: List<RoutePattern>) {
        for (p in patterns) {
            for (st in p.stops) {
                if (st.name.isNotBlank()) {
                    if (st.stopId.isNotBlank()) stopNameMemoryCache[st.stopId] = st.name
                    if (st.stopCode.isNotBlank()) stopNameMemoryCache[st.stopCode] = st.name
                }
            }
        }
    }

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
    ): List<StopArrival> = withContext(Dispatchers.IO) {
        val nowMillis = System.currentTimeMillis()
        val nowSec = nowMillis / 1000L
        val normPref = preferredLine.trim().uppercase()
        val activePatterns = routePatterns.ifEmpty { routeMemoryCache[normPref].orEmpty() }
        val combined = mutableListOf<StopArrival>()

        // 1. Query GTT Official Palina + MATO GraphQL in parallel for lowest latency and highest accuracy
        val (palinaArrivals, matoArrivals) = coroutineScope {
            val palinaDeferred = async { queryGttPalinaStopArrivals(stop, nowMillis) }
            val matoDeferred = async { queryMatoStopArrivals(stop, nowMillis) }
            palinaDeferred.await() to matoDeferred.await()
        }

        // 2. Live GTT GTFS-RT TripUpdates stop predictions (refresh if empty or older than 25s)
        val tripUpdates = if (lastTripUpdates.isEmpty() || nowMillis - lastTripUpdatesFetchMillis > 25_000L) {
            val fresh = fetchTripUpdatesSource().updates
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
        }.distinct().take(8)

        val (otherLinePatterns, extraSingleLineVehicles) = coroutineScope {
            val patternsDeferred = otherLineCodes.map { code ->
                async {
                    val lineTrips = allLivePool
                        .asSequence()
                        .filter { it.lineCode.equals(code, ignoreCase = true) }
                        .mapNotNull { it.tripId?.trim()?.takeIf { t -> t.isNotEmpty() } }
                        .toSet()
                    code to getFastRoutePatternsForLine(code, lineTrips)
                }
            }
            val matoLinesDeferred = linesNeedingMatoLive.map { code ->
                async {
                    fetchLiveVehiclesForSingleLine(code)
                }
            }
            val patternsMap = patternsDeferred
                .map { it.await() }
                .filter { it.second.isNotEmpty() }
                .toMap()
            val extraVehicles = matoLinesDeferred
                .flatMap { it.await() }
            patternsMap to extraVehicles
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
        finalArrivals
    }

    internal fun deduplicateArrivals(
        list: List<StopArrival>,
    ): MutableList<StopArrival> = Companion.deduplicateArrivals(list)

    private fun queryGttPalinaStopArrivals(stop: TransitStop, nowMillis: Long): List<StopArrival> {
        val candidateCodes = listOf(stop.stopCode, stop.stopId)
            .map { it.trim().substringAfterLast(":") }
            .filter { it.isNotEmpty() && it.all { ch -> ch.isLetterOrDigit() } }
            .distinct()

        for (code in candidateCodes) {
            val url =
                "https://www.gtt.to.it/cms/index.php?option=com_gtt&task=palina.getTransitiOld&bacino=U&realtime=true&get_param=value&palina=$code"
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Referer", "https://www.gtt.to.it/cms/percorari/arrivi")
                    .build()
                matoFastHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) continue
                    val body = resp.body?.string()?.trim().orEmpty()
                    if (!body.startsWith("[")) continue
                    val parsed = parseGttPalinaJson(body, nowMillis)
                    if (parsed.isNotEmpty()) return parsed
                }
            } catch (e: Exception) {
                Log.d(TAG, "GTT Palina skip ($code): ${e.message}")
            }
        }
        return emptyList()
    }

    private fun queryMatoStopArrivals(stop: TransitStop, nowMillis: Long): List<StopArrival> {
        // Put stopId FIRST (MATO gtfsId is `gtt:<stop_id>`), then stopCode
        val rawIds = listOf(stop.stopId, stop.stopCode)
            .map { it.trim().substringAfterLast(":") }
            .filter { it.isNotEmpty() }
            .distinct()
        val candidateIds = buildList {
            for (code in rawIds) add("gtt:$code")
            for (code in rawIds) add("GTT:$code")
            for (code in rawIds) add(code)
        }.distinct()

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val nowSec = nowMillis / 1000L
        val romeTz = java.util.TimeZone.getTimeZone("Europe/Rome")
        val todayStartSec = Calendar.getInstance(romeTz).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000L

        for (endpoint in MATO_GRAPHQL_ENDPOINTS) {
            for (gtfsStopId in candidateIds.take(4)) {
                val escapedId = gtfsStopId.replace("\"", "")
                // Query 1: OTP2 stoptimesForPatterns with omitCanceled: true
                // Query 2: OTP1-compatible stoptimesForPatterns (filters CANCELED in Kotlin)
                // Query 3: fallback stoptimesWithoutPatterns
                val queries = listOf(
                    """
                    {
                      stop(id: "$escapedId") {
                        stoptimesForPatterns(startTime: $nowSec, timeRange: 3000, numberOfDepartures: 4, omitNonPickups: true, omitCanceled: true) {
                          pattern {
                            headsign
                            route {
                              shortName
                            }
                          }
                          stoptimes {
                            scheduledArrival
                            realtimeArrival
                            scheduledDeparture
                            realtimeDeparture
                            arrivalDelay
                            realtime
                            realtimeState
                            pickupType
                            serviceDay
                            headsign
                            trip {
                              gtfsId
                              route {
                                shortName
                              }
                            }
                          }
                        }
                      }
                    }
                    """.trimIndent(),
                    """
                    {
                      stop(id: "$escapedId") {
                        stoptimesForPatterns(startTime: $nowSec, timeRange: 3000, numberOfDepartures: 4, omitNonPickups: true) {
                          pattern {
                            headsign
                            route {
                              shortName
                            }
                          }
                          stoptimes {
                            scheduledArrival
                            realtimeArrival
                            scheduledDeparture
                            realtimeDeparture
                            arrivalDelay
                            realtime
                            realtimeState
                            pickupType
                            serviceDay
                            headsign
                            trip {
                              gtfsId
                              route {
                                shortName
                              }
                            }
                          }
                        }
                      }
                    }
                    """.trimIndent(),
                    """
                    {
                      stop(id: "$escapedId") {
                        stoptimesWithoutPatterns(startTime: $nowSec, timeRange: 3000, numberOfDepartures: 28, omitNonPickups: true) {
                          scheduledArrival
                          realtimeArrival
                          scheduledDeparture
                          realtimeDeparture
                          arrivalDelay
                          realtime
                          realtimeState
                          pickupType
                          serviceDay
                          headsign
                          trip {
                            gtfsId
                            route {
                              shortName
                            }
                          }
                        }
                      }
                    }
                    """.trimIndent(),
                )

                for (query in queries) {
                    try {
                        val payload = JSONObject().put("query", query).toString()
                        val request = Request.Builder()
                            .url(endpoint)
                            .post(payload.toRequestBody(mediaType))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "application/json")
                            .header("Origin", "https://mato.muoversiatorino.it")
                            .build()

                        matoFastHttpClient.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) continue
                            val body = resp.body?.string() ?: continue
                            val root = JSONObject(body)
                            val stopObj = root.optJSONObject("data")?.optJSONObject("stop") ?: continue
                            val parsed = parseMatoStopTimes(stopObj, nowSec, todayStartSec)
                            if (parsed.isNotEmpty()) return parsed
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "MATO stop arrivals skip ($endpoint, $gtfsStopId): ${e.message}")
                    }
                }
            }
        }
        return emptyList()
    }

    private fun parseMatoStopTimes(
        stopObj: JSONObject,
        nowSec: Long,
        todayStartSec: Long,
    ): List<StopArrival> {
        val out = mutableListOf<StopArrival>()

        fun consumeStopTimeObj(st: JSONObject, fallbackLine: String, fallbackHeadsign: String) {
            val realtimeState = st.optString("realtimeState", "").trim().uppercase()
            if (realtimeState == "CANCELED" || realtimeState == "CANCELLED" || realtimeState == "SKIPPED") return
            val pickupType = st.optString("pickupType", "").trim().uppercase()
            if (pickupType == "NONE" || pickupType == "1") return

            val rawRealtimeFlag = st.optBoolean("realtime", false)
            val isRt = rawRealtimeFlag && realtimeState != "SCHEDULED"

            val serviceDay = st.optLong("serviceDay", todayStartSec)
            val rtArr = st.optLong("realtimeArrival", -1L)
            val rtDep = st.optLong("realtimeDeparture", -1L)
            val schArr = st.optLong("scheduledArrival", -1L)
            val schDep = st.optLong("scheduledDeparture", -1L)

            val chosenSec = if (isRt) {
                when {
                    rtArr >= 0L -> rtArr
                    rtDep >= 0L -> rtDep
                    schArr >= 0L -> schArr
                    else -> schDep
                }
            } else {
                when {
                    schArr >= 0L -> schArr
                    schDep >= 0L -> schDep
                    rtArr >= 0L -> rtArr
                    else -> rtDep
                }
            }
            if (chosenSec < 0L) return

            val epoch = serviceDay + chosenSec
            if (epoch < nowSec - 60L || epoch > nowSec + STOP_ARRIVAL_HORIZON_SEC) return

            val tripObj = st.optJSONObject("trip")
            val rawLine = tripObj
                ?.optJSONObject("route")
                ?.optString("shortName", "")
                .orEmpty()
                .ifBlank { fallbackLine }
            if (rawLine.isBlank()) return

            val rawTripId = tripObj?.optString("gtfsId", "")
                ?.takeIf { it.isNotBlank() }
                ?.substringAfterLast(":")
            val lineCode = GtfsRtProtobufParser.normalizeGttLineCode(rawLine)
            val headsign = st.optString("headsign", "")
                .ifBlank { fallbackHeadsign }
                .ifBlank { "Linea $lineCode" }
            val delay = if (st.has("arrivalDelay") && !st.isNull("arrivalDelay")) st.optInt("arrivalDelay") else null

            out.add(
                StopArrival(
                    lineCode = lineCode,
                    headsign = headsign,
                    arrivalEpochSeconds = epoch,
                    isRealtime = isRt,
                    delaySeconds = delay,
                    tripId = rawTripId,
                    sourceLabel = if (isRt) "MATO Live" else "Orario MATO",
                ),
            )
        }

        val byPattern = stopObj.optJSONArray("stoptimesForPatterns")
        if (byPattern != null && byPattern.length() > 0) {
            for (i in 0 until byPattern.length()) {
                val pEntry = byPattern.optJSONObject(i) ?: continue
                val pObj = pEntry.optJSONObject("pattern")
                val pLine = pObj?.optJSONObject("route")?.optString("shortName", "").orEmpty()
                val pHeadsign = pObj?.optString("headsign", "").orEmpty()
                val stArr = pEntry.optJSONArray("stoptimes") ?: continue
                for (j in 0 until stArr.length()) {
                    val st = stArr.optJSONObject(j) ?: continue
                    consumeStopTimeObj(st, pLine, pHeadsign)
                }
            }
        }

        val flat = stopObj.optJSONArray("stoptimesWithoutPatterns")
        if (out.isEmpty() && flat != null && flat.length() > 0) {
            for (i in 0 until flat.length()) {
                val st = flat.optJSONObject(i) ?: continue
                consumeStopTimeObj(st, "", "")
            }
        }

        return out
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2.0) * sin(dLon / 2.0)
        return r * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    }

    private fun initialBearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private fun queryMatoGraphQl(
        lineCode: String,
        todayYyyyMmDd: String,
        activeTripIds: Set<String> = emptySet(),
        matchTargetLine: String = lineCode,
    ): List<RoutePattern> {
        val escapedName = lineCode.replace("\"", "")
        // Prioritize queries that include `tripsForDate(serviceDate)` so Sunday/detour patterns win over weekday shapes!
        val queries = listOf(
            """
            {
              routes(name: "$escapedName") {
                shortName
                patterns {
                  code
                  name
                  directionId
                  tripsForDate(serviceDate: "$todayYyyyMmDd") {
                    gtfsId
                  }
                  geometry {
                    lat
                    lon
                  }
                  stops {
                    gtfsId
                    code
                    name
                    lat
                    lon
                  }
                }
              }
            }
            """.trimIndent(),
            """
            {
              routes(name: "$escapedName") {
                shortName
                patterns {
                  id
                  name
                  directionId
                  tripsForDate(serviceDate: "$todayYyyyMmDd") {
                    gtfsId
                  }
                  patternGeometry {
                    points
                  }
                  stops {
                    gtfsId
                    code
                    name
                    lat
                    lon
                  }
                }
              }
            }
            """.trimIndent(),
            """
            {
              routes(name: "$escapedName") {
                shortName
                patterns {
                  code
                  name
                  directionId
                  trips {
                    gtfsId
                  }
                  geometry {
                    lat
                    lon
                  }
                  stops {
                    gtfsId
                    code
                    name
                    lat
                    lon
                  }
                }
              }
            }
            """.trimIndent(),
            """
            {
              routes(name: "$escapedName") {
                shortName
                patterns {
                  id
                  name
                  directionId
                  patternGeometry {
                    points
                  }
                  stops {
                    gtfsId
                    code
                    name
                    lat
                    lon
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val mediaType = "application/json; charset=utf-8".toMediaType()

        for (endpoint in MATO_GRAPHQL_ENDPOINTS) {
            for (query in queries) {
                try {
                    val payload = JSONObject().put("query", query).toString()
                    val request = Request.Builder()
                        .url(endpoint)
                        .post(payload.toRequestBody(mediaType))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .header("Origin", "https://mato.muoversiatorino.it")
                        .build()

                    matoFastHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) continue
                        val body = response.body?.string() ?: continue
                        val parsed = parseMatoGraphQlResponse(body, matchTargetLine, activeTripIds)
                        if (parsed.isNotEmpty()) return parsed
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "MATO GraphQL skip su $endpoint: ${e.message}")
                }
            }
        }
        return emptyList()
    }

    private fun parseMatoGraphQlResponse(
        jsonStr: String,
        targetLine: String,
        activeTripIds: Set<String> = emptySet(),
    ): List<RoutePattern> {
        val root = JSONObject(jsonStr)
        val routes = root.optJSONObject("data")?.optJSONArray("routes") ?: return emptyList()
        val result = mutableListOf<RoutePattern>()
        val cleanActiveTrips = activeTripIds
            .map { it.trim().substringAfterLast(":") }
            .filter { it.isNotEmpty() }
            .toHashSet()

        data class ScoredPattern(
            val pattern: RoutePattern,
            val liveMatches: Int,
            val todayTrips: Int,
            val totalTrips: Int,
        )

        val patternScoreComparator = compareBy<ScoredPattern> { it.liveMatches }
            .thenBy { it.todayTrips }
            .thenBy { it.totalTrips }
            .thenBy { it.pattern.stops.size }
            .thenBy { it.pattern.points.size }

        for (i in 0 until routes.length()) {
            val routeObj = routes.optJSONObject(i) ?: continue
            val shortName = routeObj.optString("shortName", "").trim().uppercase()
            if (shortName != targetLine && GtfsRtProtobufParser.normalizeGttLineCode(shortName) != targetLine) {
                continue
            }
            val patterns = routeObj.optJSONArray("patterns") ?: continue
            val bestByDir = mutableMapOf<Int, ScoredPattern>()
            for (p in 0 until patterns.length()) {
                val pObj = patterns.optJSONObject(p) ?: continue
                val dirId = pObj.optInt("directionId", p % 2).coerceIn(0, 1)

                val pts = mutableListOf<GeoPoint>()
                val encoded = pObj.optJSONObject("patternGeometry")?.optString("points", "").orEmpty()
                if (encoded.isNotBlank()) {
                    pts.addAll(decodeGooglePolyline(encoded))
                }
                if (pts.size < 2) {
                    val geom = pObj.optJSONArray("geometry")
                    if (geom != null) {
                        for (g in 0 until geom.length()) {
                            val gObj = geom.optJSONObject(g) ?: continue
                            val lat = gObj.optDouble("lat", Double.NaN)
                            val lon = gObj.optDouble("lon", Double.NaN)
                            if (!lat.isNaN() && !lon.isNaN()) pts.add(GeoPoint(lat, lon))
                        }
                    }
                }
                if (pts.size >= 2) {
                    val simplified = simplifySegment(pts, maxPts = 280)
                    val stopsArr = pObj.optJSONArray("stops")
                    val stopsList = mutableListOf<TransitStop>()
                    if (stopsArr != null) {
                        for (s in 0 until stopsArr.length()) {
                            val sObj = stopsArr.optJSONObject(s) ?: continue
                            val sLat = sObj.optDouble("lat", Double.NaN)
                            val sLon = sObj.optDouble("lon", Double.NaN)
                            if (sLat.isNaN() || sLon.isNaN()) continue
                            val rawId = sObj.optString("gtfsId", "").substringAfter(":")
                            val code = sObj.optString("code", "").ifBlank { rawId }
                            val sName = sObj.optString("name", "").ifBlank { "Fermata $code" }
                            stopsList.add(
                                TransitStop(
                                    stopId = rawId.ifBlank { code },
                                    stopCode = code,
                                    name = sName,
                                    lat = sLat,
                                    lon = sLon,
                                    directionId = dirId,
                                    sequence = s,
                                ),
                            )
                        }
                    }

                    val todayTripsArr = pObj.optJSONArray("tripsForDate")
                    val allTripsArr = pObj.optJSONArray("trips")
                    val todayCount = todayTripsArr?.length() ?: 0
                    val totalCount = allTripsArr?.length() ?: todayCount
                    var liveHits = 0
                    if (cleanActiveTrips.isNotEmpty()) {
                        val scanArr = todayTripsArr ?: allTripsArr
                        if (scanArr != null) {
                            for (t in 0 until scanArr.length()) {
                                val tid = scanArr.optJSONObject(t)?.optString("gtfsId", "")
                                    ?.substringAfterLast(":")?.trim()
                                if (!tid.isNullOrEmpty() && tid in cleanActiveTrips) {
                                    liveHits++
                                }
                            }
                        }
                    }

                    val candidate = ScoredPattern(
                        pattern = RoutePattern(
                            id = pObj.optString("code", pObj.optString("id", "mato_$p")),
                            name = pObj.optString("name", shortName),
                            directionId = dirId,
                            segments = listOf(simplified),
                            stops = stopsList,
                        ),
                        liveMatches = liveHits,
                        todayTrips = todayCount,
                        totalTrips = totalCount,
                    )
                    val existing = bestByDir[dirId]
                    if (existing == null || patternScoreComparator.compare(candidate, existing) > 0) {
                        bestByDir[dirId] = candidate
                    }
                }
            }
            result.addAll(bestByDir.values.map { it.pattern })
            if (result.isNotEmpty()) break
        }
        return result
    }

    private fun queryOverpassTurinRoute(lineCode: String): List<RoutePattern> {
        val safeRef = lineCode.replace(OVERPASS_SAFE_REF_REGEX, "")
        if (safeRef.isBlank()) return emptyList()

        // Exact ref match uses Overpass tag index (10x faster than regex)
        // Turin metropolitan bounding box: south=44.92, west=7.48, north=45.22, east=7.86
        val ql = """
            [out:json][timeout:20];
            (
              relation["type"="route"]["route"="bus"]["ref"="$safeRef"](44.92,7.48,45.22,7.86);
              relation["type"="route"]["route"="tram"]["ref"="$safeRef"](44.92,7.48,45.22,7.86);
              relation["type"="route"]["route"="trolleybus"]["ref"="$safeRef"](44.92,7.48,45.22,7.86);
            );
            out geom;
        """.trimIndent()

        val mediaType = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        val encodedBody = "data=" + java.net.URLEncoder.encode(ql, "UTF-8")

        for (endpoint in OVERPASS_ENDPOINTS) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(encodedBody.toRequestBody(mediaType))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .build()

                routeHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) continue
                    val body = resp.body?.string() ?: continue
                    val parsed = parseOverpassRelations(body, safeRef)
                    if (parsed.isNotEmpty()) return parsed
                }
            } catch (e: Exception) {
                Log.d(TAG, "Overpass skip $endpoint: ${e.message}")
            }
        }
        return emptyList()
    }

    private fun parseOverpassRelations(jsonStr: String, lineCode: String): List<RoutePattern> {
        val root = JSONObject(jsonStr)
        val elements = root.optJSONArray("elements") ?: return emptyList()

        data class ScoredRelation(
            val isGtt: Boolean,
            val totalPoints: Int,
            val pattern: RoutePattern,
        )

        val candidates = mutableListOf<ScoredRelation>()

        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            if (el.optString("type") != "relation") continue
            val members = el.optJSONArray("members") ?: continue

            val rawWays = mutableListOf<List<GeoPoint>>()
            for (m in 0 until members.length()) {
                val mem = members.optJSONObject(m) ?: continue
                if (mem.optString("type") != "way") continue
                val role = mem.optString("role", "")
                if (role.contains("platform", ignoreCase = true) || role.contains("stop", ignoreCase = true)) {
                    continue
                }
                val geom = mem.optJSONArray("geometry") ?: continue
                val pts = ArrayList<GeoPoint>(geom.length())
                for (g in 0 until geom.length()) {
                    val gObj = geom.optJSONObject(g) ?: continue
                    val lat = gObj.optDouble("lat", Double.NaN)
                    val lon = gObj.optDouble("lon", Double.NaN)
                    if (!lat.isNaN() && !lon.isNaN()) pts.add(GeoPoint(lat, lon))
                }
                if (pts.size >= 2) rawWays.add(pts)
            }

            if (rawWays.isEmpty()) continue
            val stitched = stitchConnectedWays(rawWays).map { simplifySegment(it, maxPts = 140) }
            val tags = el.optJSONObject("tags")
            val operator = tags?.optString("operator", "").orEmpty()
            val network = tags?.optString("network", "").orEmpty()
            val isGtt = operator.contains("GTT", ignoreCase = true) ||
                operator.contains("Gruppo Torinese", ignoreCase = true) ||
                network.contains("GTT", ignoreCase = true)
            val name = tags?.optString("name", "Linea $lineCode").orEmpty().ifBlank { "Linea $lineCode" }
            val totalPts = stitched.sumOf { it.size }
            candidates.add(
                ScoredRelation(
                    isGtt = isGtt,
                    totalPoints = totalPts,
                    pattern = RoutePattern(
                        id = "osm_${el.optLong("id", i.toLong())}",
                        name = name,
                        directionId = 0,
                        segments = stitched,
                    ),
                ),
            )
        }

        // Prioritize GTT relations and longest main routes (Andata + Ritorno)
        return candidates
            .sortedWith(compareByDescending<ScoredRelation> { it.isGtt }.thenByDescending { it.totalPoints })
            .take(2)
            .mapIndexed { idx, item -> item.pattern.copy(directionId = idx % 2) }
    }

    /**
     * Stitches consecutive OSM ways into clean continuous chains without ever drawing
     * artificial straight lines across gaps or reversed segments.
     */
    private fun stitchConnectedWays(ways: List<List<GeoPoint>>): List<List<GeoPoint>> {
        val chains = mutableListOf<MutableList<GeoPoint>>()
        val thresholdDeg = 0.0012 // ~110m tolerance

        fun closeEnough(a: GeoPoint, b: GeoPoint): Boolean =
            abs(a.lat - b.lat) < thresholdDeg && abs(a.lon - b.lon) < thresholdDeg

        for (way in ways) {
            if (way.size < 2) continue
            val current = chains.lastOrNull()
            if (current == null) {
                chains.add(way.toMutableList())
                continue
            }
            when {
                closeEnough(current.last(), way.first()) -> {
                    current.addAll(way.drop(1))
                }
                closeEnough(current.last(), way.last()) -> {
                    current.addAll(way.asReversed().drop(1))
                }
                closeEnough(current.first(), way.last()) -> {
                    current.addAll(0, way.dropLast(1))
                }
                closeEnough(current.first(), way.first()) -> {
                    current.addAll(0, way.asReversed().dropLast(1))
                }
                else -> {
                    chains.add(way.toMutableList())
                }
            }
        }
        return chains
    }

    private fun decodeGooglePolyline(encoded: String): List<GeoPoint> {
        val poly = mutableListOf<GeoPoint>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                if (index >= len) break
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lat += dlat

            shift = 0
            result = 0
            do {
                if (index >= len) break
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            lng += dlng

            poly.add(GeoPoint(lat / 1E5, lng / 1E5))
        }
        return poly
    }

    private fun simplifySegment(points: List<GeoPoint>, maxPts: Int = 140): List<GeoPoint> {
        if (points.size <= maxPts) return points
        val step = (points.size / maxPts).coerceAtLeast(1)
        val out = ArrayList<GeoPoint>(points.size / step + 2)
        var i = 0
        while (i < points.size) {
            out.add(points[i])
            i += step
        }
        if (out.last() != points.last()) out.add(points.last())
        return out
    }

    private fun serializePatterns(patterns: List<RoutePattern>): String {
        val arr = JSONArray()
        for (p in patterns) {
            val segsArr = JSONArray()
            for (seg in p.segments) {
                val ptsArr = JSONArray()
                for (pt in seg) ptsArr.put(JSONArray().put(pt.lat).put(pt.lon))
                segsArr.put(ptsArr)
            }
            val stopsArr = JSONArray()
            for (st in p.stops) {
                stopsArr.put(
                    JSONObject()
                        .put("id", st.stopId)
                        .put("code", st.stopCode)
                        .put("name", st.name)
                        .put("lat", st.lat)
                        .put("lon", st.lon)
                        .put("dir", st.directionId)
                        .put("seq", st.sequence),
                )
            }
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("dir", p.directionId)
                    .put("segs", segsArr)
                    .put("stops", stopsArr),
            )
        }
        return arr.toString()
    }

    private fun parseCachedPatterns(raw: String): List<RoutePattern> {
        val arr = JSONArray(raw)
        val out = mutableListOf<RoutePattern>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val segsArr = obj.optJSONArray("segs") ?: continue
            val segments = mutableListOf<List<GeoPoint>>()
            for (s in 0 until segsArr.length()) {
                val ptsArr = segsArr.optJSONArray(s) ?: continue
                val pts = ArrayList<GeoPoint>(ptsArr.length())
                for (j in 0 until ptsArr.length()) {
                    val pair = ptsArr.optJSONArray(j) ?: continue
                    pts.add(GeoPoint(pair.getDouble(0), pair.getDouble(1)))
                }
                if (pts.size >= 2) segments.add(pts)
            }
            val stopsList = mutableListOf<TransitStop>()
            val stopsArr = obj.optJSONArray("stops")
            if (stopsArr != null) {
                for (k in 0 until stopsArr.length()) {
                    val sObj = stopsArr.optJSONObject(k) ?: continue
                    stopsList.add(
                        TransitStop(
                            stopId = sObj.optString("id"),
                            stopCode = sObj.optString("code"),
                            name = sObj.optString("name"),
                            lat = sObj.optDouble("lat"),
                            lon = sObj.optDouble("lon"),
                            directionId = sObj.optInt("dir", 0),
                            sequence = sObj.optInt("seq", k),
                        ),
                    )
                }
            }
            if (segments.isNotEmpty()) {
                out.add(
                    RoutePattern(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        directionId = obj.optInt("dir", 0),
                        segments = segments,
                        stops = stopsList,
                    ),
                )
            }
        }
        return out
    }

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
        private const val ROUTE_CACHE_TTL_MS = 6L * 60L * 60L * 1000L
        private const val ACTIVE_LINES_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val ALERTS_TTL_MS = 3L * 60L * 1000L
        private const val STOP_ARRIVAL_HORIZON_SEC = 50L * 60L
        private const val MAX_VEHICLE_ZOMBIE_AGE_SECONDS = 360L
        private const val MATO_PRECEDENCE_WINDOW_SECONDS = 25L
        internal const val OVERLAY_HTTP_CACHE_TTL_MS = 120_000L
        private const val SINGLE_LINE_LIVE_CACHE_TTL_MS = 25_000L
        private const val SINGLE_LINE_EMPTY_CACHE_TTL_MS = 10_000L
        private val SAFE_ROUTE_KEY_REGEX = Regex("[^A-Z0-9_]")
        private val OVERPASS_SAFE_REF_REGEX = Regex("[^A-Za-z0-9/+\\- ]")

        internal fun filterFreshOverlayVehicles(
            rawVehicles: List<BusVehicle>,
            fetchedAtMillis: Long,
            nowMillis: Long,
            ttlMillis: Long = OVERLAY_HTTP_CACHE_TTL_MS,
        ): List<BusVehicle> {
            if (rawVehicles.isEmpty() || fetchedAtMillis <= 0L) return emptyList()
            return if ((nowMillis - fetchedAtMillis) <= ttlMillis) rawVehicles else emptyList()
        }

        internal fun enrichWithTripUpdates(
            vehicles: List<BusVehicle>,
            activeTripUpdates: List<TripDelayInfo>,
        ): List<BusVehicle> {
            if (vehicles.isEmpty() || activeTripUpdates.isEmpty()) return vehicles
            val tripByTripId = HashMap<String, TripDelayInfo>(activeTripUpdates.size)
            val tripByVehicleId = HashMap<String, TripDelayInfo>(activeTripUpdates.size)
            for (tu in activeTripUpdates) {
                GtfsRtProtobufParser.normalizeTripId(tu.tripId)?.let { tripByTripId[it] = tu }
                val rawVid = tu.vehicleId?.trim()?.takeIf { it.isNotEmpty() }
                if (rawVid != null) {
                    tripByVehicleId[rawVid] = tu
                    val canonVid = GtfsRtProtobufParser.canonicalFleetId(rawVid)
                    if (canonVid.isNotEmpty()) {
                        tripByVehicleId[canonVid] = tu
                    }
                }
            }
            return vehicles.map { bus ->
                val normTrip = GtfsRtProtobufParser.normalizeTripId(bus.tripId)
                val canonVid = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId.trim() }
                val tripMatch = normTrip?.let { tripByTripId[it] }?.takeIf { tu ->
                    tu.routeId.isNullOrBlank() ||
                        GtfsRtProtobufParser.normalizeGttLineCode(tu.routeId).equals(bus.lineCode, ignoreCase = true)
                }
                val vehMatch = (tripByVehicleId[canonVid] ?: tripByVehicleId[bus.vehicleId.trim()])?.takeIf { tu ->
                    tu.routeId.isNullOrBlank() ||
                        GtfsRtProtobufParser.normalizeGttLineCode(tu.routeId).equals(bus.lineCode, ignoreCase = true)
                }
                val tu = tripMatch ?: vehMatch
                if (tu == null) {
                    bus
                } else {
                    bus.copy(
                        contributingSources = bus.contributingSources + "TripUpdates",
                        delaySeconds = tu.delaySeconds ?: bus.delaySeconds,
                        nextStopId = tu.nextStopId ?: bus.nextStopId ?: bus.stopId,
                    )
                }
            }
        }

        @Volatile
        private var instance: GttRealtimeRepository? = null

        fun getInstance(context: Context): GttRealtimeRepository {
            return instance ?: synchronized(this) {
                instance ?: GttRealtimeRepository(context).also { instance = it }
            }
        }

        /**
         * Incrementally updates ONLY the focused line's summary entry during high-frequency MaTO MQTT frames,
         * avoiding a full O(N log N) regrouping + sort over all ~400 citywide vehicles every ~2 seconds.
         */
        internal fun updateLineSummariesForFocusLine(
            baseSummaries: List<LineSummary>,
            vehicles: List<BusVehicle>,
            focusLine: String?,
        ): List<LineSummary> {
            val target = focusLine?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                ?: return GtfsRtProtobufParser.buildLineSummaries(vehicles)
            var count = 0
            val rawIds = HashSet<String>(4)
            for (v in vehicles) {
                if (v.lineCode.equals(target, ignoreCase = true) || v.rawRouteId.equals(target, ignoreCase = true)) {
                    count++
                    if (v.rawRouteId.isNotEmpty()) rawIds.add(v.rawRouteId)
                }
            }
            val existingIdx = baseSummaries.indexOfFirst { it.lineCode.equals(target, ignoreCase = true) }
            if (count == 0) {
                return if (existingIdx < 0) baseSummaries else baseSummaries.filterIndexed { i, _ -> i != existingIdx }
            }
            if (existingIdx >= 0) {
                val existing = baseSummaries[existingIdx]
                if (existing.activeVehicleCount == count && (rawIds.isEmpty() || existing.rawRouteIds.containsAll(rawIds))) {
                    return baseSummaries
                }
                val copy = ArrayList<LineSummary>(baseSummaries.size)
                copy.addAll(baseSummaries)
                copy[existingIdx] = existing.copy(
                    rawRouteIds = if (rawIds.isNotEmpty()) existing.rawRouteIds + rawIds else existing.rawRouteIds,
                    activeVehicleCount = count,
                )
                return copy
            }
            val added = LineSummary(
                lineCode = target,
                rawRouteIds = rawIds.ifEmpty { setOf(target) },
                activeVehicleCount = count,
            )
            val merged = ArrayList<LineSummary>(baseSummaries.size + 1)
            merged.addAll(baseSummaries)
            merged.add(added)
            merged.sortWith(GtfsRtProtobufParser.lineCodeComparator)
            return merged
        }

        private val METRO_LINE_SUMMARY = LineSummary(
            lineCode = GtfsRtProtobufParser.LINE_CODE_METRO,
            rawRouteIds = setOf("METRO", "M1", "M1U"),
            activeVehicleCount = 0,
        )

        internal fun mergeLineSummariesWithCatalog(
            liveSummaries: List<LineSummary>,
            catalogSummaries: List<LineSummary>,
        ): List<LineSummary> {
            return mergeCatalogAndLive(
                liveSummaries = liveSummaries,
                catalogSummaries = catalogSummaries,
                resetCatalogCountsToZero = false,
            )
        }

        /**
         * Combines authoritative citywide [liveSummaries] with the full [staticCatalog] so every
         * known Turin GTT line appears (including Turin's METRO), while lines with no live buses
         * right now accurately report `activeVehicleCount = 0` (never preserving stale counts).
         */
        internal fun mergeLiveSummariesWithStaticCatalog(
            liveSummaries: List<LineSummary>,
            staticCatalog: List<LineSummary>,
        ): List<LineSummary> {
            return mergeCatalogAndLive(
                liveSummaries = liveSummaries,
                catalogSummaries = staticCatalog,
                resetCatalogCountsToZero = true,
            )
        }

        private fun mergeCatalogAndLive(
            liveSummaries: List<LineSummary>,
            catalogSummaries: List<LineSummary>,
            resetCatalogCountsToZero: Boolean,
        ): List<LineSummary> {
            val byCode = LinkedHashMap<String, LineSummary>(catalogSummaries.size + liveSummaries.size + 1)
            byCode[GtfsRtProtobufParser.LINE_CODE_METRO] = METRO_LINE_SUMMARY

            for (entry in catalogSummaries) {
                val key = GtfsRtProtobufParser.normalizeGttLineCode(entry.lineCode)
                if (key.isEmpty() || key == "?") continue
                val existing = byCode[key]
                val count = if (resetCatalogCountsToZero) 0 else entry.activeVehicleCount
                byCode[key] = if (existing == null) {
                    entry.copy(lineCode = key, activeVehicleCount = count)
                } else {
                    existing.copy(
                        rawRouteIds = existing.rawRouteIds + entry.rawRouteIds,
                        activeVehicleCount = if (resetCatalogCountsToZero) 0 else maxOf(existing.activeVehicleCount, count),
                    )
                }
            }

            // Cross-link any static catalog entry whose rawRouteIds (e.g. GTFS route_id = "13PU"
            // paired with route_short_name = "13+") normalize to a live lineCode so mismatched
            // route_id/shortName pairs in GTT feeds never spawn 0-vehicle duplicate entries.
            val aliasToCatalogKey = HashMap<String, String>(byCode.size * 2)
            for ((catalogKey, summary) in byCode) {
                for (rawId in summary.rawRouteIds) {
                    val normAlias = GtfsRtProtobufParser.normalizeGttLineCode(rawId)
                    if (normAlias.isNotEmpty() && normAlias != "?" && normAlias != catalogKey && !byCode.containsKey(normAlias)) {
                        aliasToCatalogKey.putIfAbsent(normAlias, catalogKey)
                    }
                }
            }

            for (entry in liveSummaries) {
                val key = GtfsRtProtobufParser.normalizeGttLineCode(entry.lineCode)
                if (key.isEmpty() || key == "?") continue
                var existing = byCode[key]
                if (existing == null) {
                    val aliasedKey = aliasToCatalogKey[key]
                        ?: entry.rawRouteIds.firstNotNullOfOrNull { raw ->
                            val normRaw = GtfsRtProtobufParser.normalizeGttLineCode(raw)
                            aliasToCatalogKey[normRaw]
                        }
                    if (aliasedKey != null && aliasedKey != GtfsRtProtobufParser.LINE_CODE_METRO) {
                        val removed = byCode.remove(aliasedKey)
                        if (removed != null) {
                            existing = removed.copy(lineCode = key)
                        }
                    }
                }
                byCode[key] = if (existing == null) {
                    entry.copy(lineCode = key)
                } else {
                    existing.copy(
                        rawRouteIds = existing.rawRouteIds + entry.rawRouteIds,
                        activeVehicleCount = if (resetCatalogCountsToZero) {
                            entry.activeVehicleCount
                        } else {
                            maxOf(existing.activeVehicleCount, entry.activeVehicleCount)
                        },
                    )
                }
            }
            return byCode.values.sortedWith(GtfsRtProtobufParser.lineCodeComparator)
        }

        /**
         * Merges live vehicles from MaTO Live, GTT Official GTFS-RT, and BusOne Proxy strictly
         * by physical `vehicleId`:
         * 1. Non-frozen sources ALWAYS defeat frozen sources (immune to fake-fresh timestamps from
         *    stalled VSGTT/GTFS-RT feeds!).
         * 2. Per-vehicle confirmed movement ALWAYS defeats a stuck competitor when coordinates diverge.
         * 3. Never drops unique vehicles from a frozen source when no healthier source reports them
         *    (zero bus loss during partial fallbacks).
         * 4. Prevents cross-line ghost vehicles when a physical bus switches line (e.g., 12 -> 14)
         *    and avoids copying stale heading/stop metadata from a frozen/diverged losing feed.
         */
        internal fun mergeMultiSourceVehicles(
            matoVehicles: List<BusVehicle>,
            gttVehicles: List<BusVehicle>,
            busOneVehicles: List<BusVehicle>,
            nowMillis: Long,
            focusLine: String? = null,
            sourceReports: Map<String, SourceHealthReport> = emptyMap(),
        ): List<BusVehicle> {
            val nowSec = nowMillis / 1000L
            val allRaw = buildList(matoVehicles.size + gttVehicles.size + busOneVehicles.size) {
                addAll(matoVehicles)
                addAll(gttVehicles)
                addAll(busOneVehicles)
            }
            if (allRaw.isEmpty()) return emptyList()

            val hasAnyFreshTelemetry = allRaw.any { v ->
                val ts = if (v.timestampSeconds > 1_000_000_000_000L) v.timestampSeconds / 1000L else v.timestampSeconds
                ts > 0L && (nowSec - ts) in -120L..MAX_VEHICLE_ZOMBIE_AGE_SECONDS
            }

            val mergedByVehicleId = LinkedHashMap<String, BusVehicle>()
            val keyByLineAndTrip = HashMap<String, String>()

            fun normalizeIncoming(v: BusVehicle): BusVehicle {
                val canonVid = GtfsRtProtobufParser.canonicalFleetId(v.vehicleId)
                val canonLabel = GtfsRtProtobufParser.canonicalFleetId(v.label).ifBlank { canonVid }
                val normTrip = GtfsRtProtobufParser.normalizeTripId(v.tripId)
                val tsRaw = if (v.timestampSeconds > 1_000_000_000_000L) v.timestampSeconds / 1000L else v.timestampSeconds
                val clampedTs = if (tsRaw > nowSec + 120L) nowSec else tsRaw
                return if (canonVid == v.vehicleId &&
                    canonLabel == v.label &&
                    normTrip == v.tripId &&
                    clampedTs == v.timestampSeconds
                ) {
                    v
                } else {
                    v.copy(
                        vehicleId = canonVid,
                        label = canonLabel,
                        tripId = normTrip,
                        timestampSeconds = clampedTs,
                    )
                }
            }

            fun lineTripKey(lineCode: String, tripId: String?): String? {
                val cleanTrip = GtfsRtProtobufParser.normalizeTripId(tripId) ?: return null
                val cleanLine = lineCode.trim().uppercase()
                if (cleanLine.isEmpty()) return null
                return "$cleanLine|${cleanTrip.uppercase()}"
            }

            for (rawV in allRaw) {
                val rawVidTrimmed = rawV.vehicleId.trim()
                val v = normalizeIncoming(rawV)
                val vid = v.vehicleId
                if (vid.isEmpty()) continue

                // Drop zombie vehicles older than 6 minutes across ALL sources (including MaTO GraphQL)
                // whenever fresher telemetry is present
                if (hasAnyFreshTelemetry && v.timestampSeconds > 0L &&
                    (nowSec - v.timestampSeconds) > MAX_VEHICLE_ZOMBIE_AGE_SECONDS
                ) {
                    continue
                }

                val isMato = v.sourceName == "MaTO Live"
                var existingKey: String? = if (mergedByVehicleId.containsKey(vid)) vid else null
                if (existingKey == null) {
                    val ltKey = lineTripKey(v.lineCode, v.tripId)
                    val candidateKey = ltKey?.let { keyByLineAndTrip[it] }
                    if (candidateKey != null && mergedByVehicleId.containsKey(candidateKey)) {
                        // Only merge by (lineCode, tripId) when at least one record used a non-physical fallback ID
                        // (never collapse two distinct physical fleet buses like #9320 and #9321!)
                        val bothPhysical = GtfsRtProtobufParser.isPhysicalFleetId(candidateKey) &&
                            GtfsRtProtobufParser.isPhysicalFleetId(vid)
                        if (!bothPhysical) {
                            existingKey = candidateKey
                        }
                    }
                }

                val existing = existingKey?.let { mergedByVehicleId[it] }
                if (existing == null || existingKey == null) {
                    mergedByVehicleId[vid] = v
                    lineTripKey(v.lineCode, v.tripId)?.let { keyByLineAndTrip[it] = vid }
                    continue
                }

                val vReport = sourceReports[v.sourceName]
                val existingReport = sourceReports[existing.sourceName]
                val vFrozen = vReport?.isFrozenForLine(v.lineCode, focusLine) == true
                val existingFrozen = existingReport?.isFrozenForLine(existing.lineCode, focusLine) == true

                val vMoving = vReport?.confirmedMovingVehicleIds?.let {
                    vid in it || rawVidTrimmed in it
                } == true
                val existingMoving = existingReport?.confirmedMovingVehicleIds?.let {
                    existingKey in it || existing.vehicleId in it
                } == true
                val vStuck = vReport?.stuckVehicleIds?.let {
                    vid in it || rawVidTrimmed in it
                } == true
                val existingStuck = existingReport?.stuckVehicleIds?.let {
                    existingKey in it || existing.vehicleId in it
                } == true

                val distMeters = SourceHealthTracker.haversineMeters(
                    v.latitude,
                    v.longitude,
                    existing.latitude,
                    existing.longitude,
                )
                val coordsDiverge = distMeters >= 40.0

                val existingIsMato = existing.sourceName == "MaTO Live"
                val incomingWins = when {
                    // Priority 1: Any non-frozen source beats a frozen source regardless of fake timestamps
                    existingFrozen && !vFrozen -> true
                    vFrozen && !existingFrozen -> false
                    // Priority 2: Confirmed real movement on this vehicle beats a stuck competitor
                    vMoving && existingStuck && coordsDiverge -> true
                    existingMoving && vStuck && coordsDiverge -> false
                    // Priority 3: Healthy MaTO Live precedence window over healthy GTFS-RT/BusOne
                    isMato && !existingIsMato ->
                        existing.timestampSeconds <= 0L ||
                            v.timestampSeconds >= existing.timestampSeconds - MATO_PRECEDENCE_WINDOW_SECONDS
                    existingIsMato && !isMato ->
                        v.timestampSeconds > existing.timestampSeconds + MATO_PRECEDENCE_WINDOW_SECONDS
                    // Priority 4: Fresher timestamp, then source quality tie-breaker
                    v.timestampSeconds != existing.timestampSeconds ->
                        v.timestampSeconds > existing.timestampSeconds
                    existing.sourceName == "MaTO Live" -> false
                    v.sourceName == "MaTO Live" -> true
                    existing.sourceName == "GTT Ufficiale" -> false
                    v.sourceName == "GTT Ufficiale" -> true
                    else -> false
                }

                val winner = if (incomingWins) v else existing
                val loser = if (incomingWins) existing else v
                val loserFrozen = if (incomingWins) existingFrozen else vFrozen
                val winnerMoving = if (incomingWins) vMoving else existingMoving
                val loserStuck = if (incomingWins) existingStuck else vStuck
                val loserStaleDiverged = (loserFrozen || (winnerMoving && loserStuck)) && coordsDiverge

                // Prefer the real physical fleet matricola over a tripId fallback whenever both describe one bus
                val preferredPhysicalId = when {
                    GtfsRtProtobufParser.isPhysicalFleetId(winner.vehicleId) -> winner.vehicleId
                    winner.lineCode.equals(loser.lineCode, ignoreCase = true) &&
                        GtfsRtProtobufParser.isPhysicalFleetId(loser.vehicleId) -> loser.vehicleId
                    else -> winner.vehicleId
                }
                val preferredLabel = if (GtfsRtProtobufParser.isPhysicalFleetId(preferredPhysicalId)) {
                    preferredPhysicalId
                } else {
                    winner.label.ifBlank { preferredPhysicalId }
                }

                val mergedBus = if (winner.lineCode.equals(loser.lineCode, ignoreCase = true)) {
                    if (loserStaleDiverged) {
                        val allowTripBorrow = distMeters < 800.0
                        winner.copy(
                            vehicleId = preferredPhysicalId,
                            label = preferredLabel,
                            contributingSources = winner.contributingSources,
                            directionId = winner.directionId ?: if (allowTripBorrow) loser.directionId else null,
                            tripId = winner.tripId ?: if (allowTripBorrow) loser.tripId else null,
                        )
                    } else {
                        winner.copy(
                            vehicleId = preferredPhysicalId,
                            label = preferredLabel,
                            contributingSources = winner.contributingSources + loser.contributingSources,
                            directionId = winner.directionId ?: loser.directionId,
                            bearing = winner.bearing ?: loser.bearing,
                            speedKmh = winner.speedKmh ?: loser.speedKmh,
                            stopId = winner.stopId ?: loser.stopId,
                            nextStopId = winner.nextStopId ?: loser.nextStopId,
                            tripId = winner.tripId ?: loser.tripId,
                            delaySeconds = winner.delaySeconds ?: loser.delaySeconds,
                        )
                    }
                } else {
                    // Physical vehicle switched lines: keep ONLY the fresher/moving line record and never
                    // bleed the old line's tripId, stopId, or directionId onto the new line!
                    winner.copy(vehicleId = preferredPhysicalId, label = preferredLabel)
                }

                if (existingKey != preferredPhysicalId) {
                    mergedByVehicleId.remove(existingKey)
                }
                mergedByVehicleId[preferredPhysicalId] = mergedBus
                lineTripKey(mergedBus.lineCode, mergedBus.tripId)?.let {
                    keyByLineAndTrip[it] = preferredPhysicalId
                }
            }

            return mergedByVehicleId.values.toList()
        }

        /**
         * Pure-Kotlin parser for GTT Official AVM Palina JSON (`palina.getTransitiOld`),
         * with zero `org.json` runtime dependency so JVM unit tests can validate it directly.
         *
         * Handles both explicit `PassaggiRT` / `Passaggi` / `PassaggiPR` arrays and GTT's `*`
         * suffix convention (e.g. `"10:18*"` inside `Passaggi`), plus midnight rollover in Europe/Rome.
         */
        internal fun parseGttPalinaJson(
            jsonText: String,
            nowMillis: Long,
        ): List<StopArrival> {
            val trimmed = jsonText.trim()
            if (!trimmed.startsWith("[")) return emptyList()
            val nowSec = nowMillis / 1000L
            val romeTz = java.util.TimeZone.getTimeZone("Europe/Rome")
            val midnightSec = Calendar.getInstance(romeTz).apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis / 1000L

            fun resolveClockToEpoch(rawTime: String): Pair<Long, Boolean>? {
                val cleaned = rawTime.trim()
                if (cleaned.isEmpty()) return null
                val hasStar = cleaned.endsWith("*")
                val clock = cleaned.removeSuffix("*").trim()
                val colon = clock.indexOf(':')
                if (colon <= 0 || colon >= clock.lastIndex) return null
                val hh = clock.substring(0, colon).toIntOrNull() ?: return null
                val mm = clock.substring(colon + 1).take(2).toIntOrNull() ?: return null
                if (hh !in 0..27 || mm !in 0..59) return null
                var epoch = midnightSec + hh * 3600L + mm * 60L
                val diff = epoch - nowSec
                if (diff < -12L * 3600L) epoch += 86_400L
                else if (diff > 12L * 3600L) epoch -= 86_400L
                if (epoch < nowSec - 60L || epoch > nowSec + STOP_ARRIVAL_HORIZON_SEC) return null
                return epoch to hasStar
            }

            // Minimal recursive-descent JSON reader for objects/arrays/strings inside the Palina array
            var pos = 0
            val n = trimmed.length
            fun skipWs() {
                while (pos < n && trimmed[pos] <= ' ') pos++
            }
            fun readString(): String {
                if (pos >= n || trimmed[pos] != '"') return ""
                pos++
                val sb = StringBuilder()
                while (pos < n) {
                    val ch = trimmed[pos++]
                    if (ch == '"') break
                    if (ch == '\\' && pos < n) {
                        when (val esc = trimmed[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> if (pos + 4 <= n) {
                                val code = trimmed.substring(pos, pos + 4).toIntOrNull(16)
                                if (code != null) sb.append(code.toChar())
                                pos += 4
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(ch)
                    }
                }
                return sb.toString()
            }
            fun readValue(): Any? {
                skipWs()
                if (pos >= n) return null
                return when (trimmed[pos]) {
                    '"' -> readString()
                    '[' -> {
                        pos++
                        val list = ArrayList<Any?>()
                        skipWs()
                        if (pos < n && trimmed[pos] == ']') {
                            pos++
                            return list
                        }
                        while (pos < n) {
                            list.add(readValue())
                            skipWs()
                            if (pos < n && trimmed[pos] == ',') {
                                pos++
                            } else {
                                if (pos < n && trimmed[pos] == ']') pos++
                                break
                            }
                        }
                        list
                    }
                    '{' -> {
                        pos++
                        val map = LinkedHashMap<String, Any?>()
                        skipWs()
                        if (pos < n && trimmed[pos] == '}') {
                            pos++
                            return map
                        }
                        while (pos < n) {
                            skipWs()
                            if (pos >= n || trimmed[pos] != '"') break
                            val key = readString()
                            skipWs()
                            if (pos < n && trimmed[pos] == ':') pos++
                            map[key] = readValue()
                            skipWs()
                            if (pos < n && trimmed[pos] == ',') {
                                pos++
                            } else {
                                if (pos < n && trimmed[pos] == '}') pos++
                                break
                            }
                        }
                        map
                    }
                    else -> {
                        val start = pos
                        while (pos < n && trimmed[pos] != ',' && trimmed[pos] != '}' && trimmed[pos] != ']' && trimmed[pos] > ' ') {
                            pos++
                        }
                        trimmed.substring(start, pos)
                    }
                }
            }

            val rootList = (readValue() as? List<*>) ?: return emptyList()
            val out = mutableListOf<StopArrival>()

            for (rawEntry in rootList) {
                val obj = (rawEntry as? Map<*, *>) ?: continue
                val rawLine = (obj["Linea"] ?: obj["LineaAlias"] ?: obj["line"])?.toString()?.trim().orEmpty()
                if (rawLine.isEmpty()) continue
                val lineCode = GtfsRtProtobufParser.normalizeGttLineCode(rawLine)
                if (lineCode.isEmpty() || lineCode == "?") continue

                val headsign = (obj["Direzione"] ?: obj["DirezioneBreve"] ?: obj["headsign"])
                    ?.toString()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                    ?: "Linea $lineCode"

                val rtEpochs = ArrayList<Long>()
                val rtRawList = (obj["PassaggiRT"] as? List<*>) ?: emptyList<Any?>()
                for (t in rtRawList) {
                    val resolved = resolveClockToEpoch(t?.toString().orEmpty()) ?: continue
                    rtEpochs.add(resolved.first)
                    out.add(
                        StopArrival(
                            lineCode = lineCode,
                            headsign = headsign,
                            arrivalEpochSeconds = resolved.first,
                            isRealtime = true,
                            sourceLabel = "GTT Palina",
                        ),
                    )
                }

                val schedRawList = buildList {
                    (obj["Passaggi"] as? List<*>)?.let { addAll(it) }
                    (obj["PassaggiPR"] as? List<*>)?.let { addAll(it) }
                }
                for (t in schedRawList) {
                    val (epoch, isStarRt) = resolveClockToEpoch(t?.toString().orEmpty()) ?: continue
                    if (rtEpochs.any { abs(it - epoch) <= 120L }) continue
                    if (isStarRt) {
                        rtEpochs.add(epoch)
                        out.add(
                            StopArrival(
                                lineCode = lineCode,
                                headsign = headsign,
                                arrivalEpochSeconds = epoch,
                                isRealtime = true,
                                sourceLabel = "GTT Palina",
                            ),
                        )
                    } else {
                        out.add(
                            StopArrival(
                                lineCode = lineCode,
                                headsign = headsign,
                                arrivalEpochSeconds = epoch,
                                isRealtime = false,
                                sourceLabel = "Orario GTT",
                            ),
                        )
                    }
                }
            }

            return out
        }

        /**
         * Determines the exact `directionId` (0 or 1) that [linePatterns] uses when serving [stop].
         *
         * Crucial for Turin transit: physical stop #317 may be `directionId = 1` on Line 12,
         * while being `directionId = 0` on Line 55! Returns `null` when ambiguous on a two-way street
         * (never guesses direction).
         */
        internal fun resolveStopDirectionOnLinePatterns(
            stop: TransitStop,
            linePatterns: List<RoutePattern>,
        ): Int? {
            if (linePatterns.isEmpty()) return null
            val stopKeys = setOf(
                stop.stopId.trim().substringAfterLast(":"),
                stop.stopCode.trim().substringAfterLast(":"),
            ).filter { it.isNotEmpty() }.toSet()

            // 1. Authoritative exact stopId / stopCode match inside pattern.stops
            if (stopKeys.isNotEmpty()) {
                val exactDirs = linePatterns
                    .filter { pat ->
                        pat.stops.any { st ->
                            st.stopId.trim().substringAfterLast(":") in stopKeys ||
                                st.stopCode.trim().substringAfterLast(":") in stopKeys
                        }
                    }
                    .map { it.directionId }
                    .distinct()
                if (exactDirs.size == 1) return exactDirs.first()
            }

            // 2. Same physical pole match by coordinates (<= 35m)
            val bestStopDistByDir = HashMap<Int, Double>()
            for (pat in linePatterns) {
                for (st in pat.stops) {
                    val d = com.example.data.model.GttFleetAnalyzer.haversineMeters(
                        stop.lat,
                        stop.lon,
                        st.lat,
                        st.lon,
                    )
                    if (d <= 35.0) {
                        val prev = bestStopDistByDir[pat.directionId]
                        if (prev == null || d < prev) bestStopDistByDir[pat.directionId] = d
                    }
                }
            }
            if (bestStopDistByDir.size == 1) return bestStopDistByDir.keys.first()
            if (bestStopDistByDir.size >= 2) {
                val sorted = bestStopDistByDir.entries.sortedBy { it.value }
                if (sorted[0].value <= 15.0 && sorted[1].value - sorted[0].value >= 12.0) {
                    return sorted[0].key
                }
            }

            // 3. Single-direction (circular) line or physically separated one-way branches
            val distinctDirs = linePatterns.map { it.directionId }.distinct()
            if (distinctDirs.size == 1) return distinctDirs.first()

            val bestPolyDistByDir = HashMap<Int, Double>()
            for (pat in linePatterns) {
                if (pat.points.size < 2) continue
                val proj = com.example.data.model.GttFleetAnalyzer.projectAlongPolyline(stop.lat, stop.lon, pat.points)
                    ?: continue
                val prev = bestPolyDistByDir[pat.directionId]
                if (prev == null || proj.perpMeters < prev) bestPolyDistByDir[pat.directionId] = proj.perpMeters
            }
            if (bestPolyDistByDir.size == 1) return bestPolyDistByDir.keys.first()
            if (bestPolyDistByDir.size >= 2) {
                val sorted = bestPolyDistByDir.entries.sortedBy { it.value }
                if (sorted[0].value <= 40.0 && sorted[1].value - sorted[0].value >= 30.0) {
                    return sorted[0].key
                }
            }
            return null
        }

        /**
         * Strictly resolves the EXACT live [BusVehicle] for a tapped [StopArrival] on ANY line
         * (tracked line or another line like 55/5).
         *
         * NEVER falls back to blind "nearest straight-line bus" guessing! A bus is returned ONLY when:
         * 1. Exact `vehicleId` matches (and is not in depot / opposite direction / already passed), OR
         * 2. Exact `tripId` matches (same safety checks), OR
         * 3. `arrival.isRealtime` AND [targetLinePatterns] resolves the line's exact direction at [stop]
         *    AND an unclaimed upstream bus along that exact polyline has an ETA matching `arrival`'s ETA.
         */
        internal fun findExactBusForArrival(
            arrival: StopArrival,
            stop: TransitStop,
            allLivePool: List<BusVehicle>,
            targetLinePatterns: List<RoutePattern>,
            claimedVehicleIds: Set<String> = emptySet(),
            nowMillis: Long,
        ): BusVehicle? {
            val nowSec = nowMillis / 1000L
            val targetLine = arrival.lineCode.trim().uppercase()
            val resolvedStopDir = resolveStopDirectionOnLinePatterns(stop, targetLinePatterns)
            val stopOnTargetLine = resolvedStopDir?.let { stop.copy(directionId = it) }

            fun isValidMatchedBus(bus: BusVehicle): Boolean {
                val health = com.example.data.model.GttFleetAnalyzer.assess(bus, targetLinePatterns, nowMillis)
                if (health.isInDepot ||
                    health.isStaleGps ||
                    com.example.data.model.GttFleetAnalyzer.isConfirmedMatoPhantom(bus, targetLinePatterns, nowMillis)
                ) {
                    return false
                }
                if (stopOnTargetLine != null && targetLinePatterns.isNotEmpty()) {
                    val busDir = com.example.data.model.GttFleetAnalyzer.resolveDirectionId(bus, targetLinePatterns)
                    if (busDir != null && busDir != stopOnTargetLine.directionId) return false
                    val approach = com.example.data.model.GttFleetAnalyzer.estimateBusApproachToStop(
                        bus = bus,
                        stop = stopOnTargetLine,
                        routePatterns = targetLinePatterns,
                        nowMillis = nowMillis,
                        allowTrafficPaused = true,
                    )
                    if (approach != null && approach.alreadyPassed) return false
                }
                return true
            }

            // 1. Canonical vehicleId match
            val vid = GtfsRtProtobufParser.canonicalFleetId(arrival.vehicleId).ifBlank { null }
            if (vid != null) {
                val exactVeh = allLivePool.firstOrNull {
                    (GtfsRtProtobufParser.canonicalFleetId(it.vehicleId).equals(vid, ignoreCase = true) ||
                        GtfsRtProtobufParser.canonicalFleetId(it.label).equals(vid, ignoreCase = true)) &&
                        (it.lineCode.equals(targetLine, ignoreCase = true) || it.rawRouteId.equals(targetLine, ignoreCase = true))
                }
                if (exactVeh != null && isValidMatchedBus(exactVeh)) return exactVeh
            }

            // 2. Normalized tripId match
            val cleanTrip = GtfsRtProtobufParser.normalizeTripId(arrival.tripId)
            if (cleanTrip != null) {
                val exactTripBus = allLivePool.firstOrNull { bus ->
                    val busTrip = GtfsRtProtobufParser.normalizeTripId(bus.tripId)
                    busTrip != null && busTrip.equals(cleanTrip, ignoreCase = true) &&
                        (bus.lineCode.equals(targetLine, ignoreCase = true) || bus.rawRouteId.equals(targetLine, ignoreCase = true))
                }
                if (exactTripBus != null && isValidMatchedBus(exactTripBus)) return exactTripBus
            }

            // 3. Strict polyline-verified same-direction ETA match ONLY for realtime arrivals when we know
            //    this exact line's direction at this stop (NEVER pick a random nearest or opposite-direction bus!)
            if (!arrival.isRealtime || stopOnTargetLine == null || targetLinePatterns.isEmpty()) {
                return null
            }

            val targetEtaSec = (arrival.arrivalEpochSeconds - nowSec).coerceAtLeast(0L)
            val maxEtaErrorSec = maxOf(210L, (targetEtaSec * 0.45).toLong())

            val candidates = allLivePool.mapNotNull { bus ->
                if (!bus.lineCode.equals(targetLine, ignoreCase = true) &&
                    !bus.rawRouteId.equals(targetLine, ignoreCase = true)
                ) {
                    return@mapNotNull null
                }
                if (bus.vehicleId in claimedVehicleIds) return@mapNotNull null
                val candidateHealth = com.example.data.model.GttFleetAnalyzer.assess(bus, targetLinePatterns, nowMillis)
                if (candidateHealth.isInDepot ||
                    candidateHealth.isStaleGps ||
                    com.example.data.model.GttFleetAnalyzer.isConfirmedMatoPhantom(bus, targetLinePatterns, nowMillis)
                ) {
                    return@mapNotNull null
                }
                val approach = com.example.data.model.GttFleetAnalyzer.estimateBusApproachToStop(
                    bus = bus,
                    stop = stopOnTargetLine,
                    routePatterns = targetLinePatterns,
                    nowMillis = nowMillis,
                    allowTrafficPaused = true,
                ) ?: return@mapNotNull null
                if (approach.alreadyPassed) return@mapNotNull null
                val etaDiff = abs(approach.estimatedEtaSeconds - targetEtaSec)
                if (etaDiff > maxEtaErrorSec) return@mapNotNull null
                bus to etaDiff
            }

            return candidates.minByOrNull { it.second }?.first
        }

        /**
         * Reconciles raw stop arrivals from GTT Palina / MATO / TripUpdates with live GPS vehicle
         * positions across ALL Turin lines:
         * 1. Corrects wildly erroneous feed ETAs when a tracked live bus is physically right upstream.
         * 2. Drops stale ghost predictions for buses that already passed the stop or are heading away.
         * 3. Absorbs delayed-bus timetable slots (-3 min..+8 min) so a delayed bus never shows twice.
         * 4. Links exact verified `vehicleId` onto BOTH [preferredLine] and other-line arrivals using
         *    each line's TRUE direction and polyline at this stop.
         * 5. Drops physically impossible unlinked phantom arrivals on BOTH [preferredLine] and other lines.
         */
        internal fun reconcileLiveVehiclesWithArrivals(
            stop: TransitStop,
            preferredLine: String,
            liveVehicles: List<BusVehicle>,
            allLivePool: List<BusVehicle>,
            routePatterns: List<RoutePattern>,
            rawArrivals: List<StopArrival>,
            nowMillis: Long,
            otherLinePatterns: Map<String, List<RoutePattern>> = emptyMap(),
        ): List<StopArrival> {
            val nowSec = nowMillis / 1000L
            val normPref = preferredLine.trim().uppercase()
            val dirPattern = routePatterns
                .filter { it.directionId == stop.directionId && it.points.size >= 2 }
                .firstOrNull { pat ->
                    pat.stops.any {
                        it.stopId.equals(stop.stopId, ignoreCase = true) ||
                            it.stopCode.equals(stop.stopCode, ignoreCase = true)
                    }
                }
                ?: routePatterns.firstOrNull { it.directionId == stop.directionId && it.points.size >= 2 }
            val dirTerminus = dirPattern?.terminusName()
            val stopAlongMeters = dirPattern?.let { pat ->
                com.example.data.model.GttFleetAnalyzer.projectAlongPolyline(stop.lat, stop.lon, pat.points)
                    ?.takeIf { it.perpMeters <= 220.0 }
                    ?.alongMeters
            }
            // Earliest physically possible time a bus that hasn't even left the terminus yet could reach this stop
            val terminusFloorSec: Long? = stopAlongMeters?.let { along ->
                if (along < 250.0) 90L else (along / 5.8).toLong().coerceIn(180L, 1_200L)
            }
            val prefOriginPoint = dirPattern?.stops?.firstOrNull()?.let { GeoPoint(it.lat, it.lon) }
                ?: dirPattern?.points?.firstOrNull()

            val prefLineVehicles = com.example.data.model.GttFleetAnalyzer.filterOutConfirmedMatoPhantoms(
                vehicles = liveVehicles.filter {
                    it.lineCode.equals(normPref, ignoreCase = true) ||
                        it.rawRouteId.equals(normPref, ignoreCase = true)
                },
                routePatterns = routePatterns,
                nowMillis = nowMillis,
            )
            val approachByVehicleId = LinkedHashMap<String, com.example.data.model.GttFleetAnalyzer.BusStopApproach>()
            for (bus in prefLineVehicles) {
                val approach = com.example.data.model.GttFleetAnalyzer.estimateBusApproachToStop(
                    bus = bus,
                    stop = stop,
                    routePatterns = routePatterns,
                    nowMillis = nowMillis,
                    allowTrafficPaused = true,
                ) ?: continue
                val canonVid = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId }
                approachByVehicleId[canonVid] = approach
            }
            val approachingUpstream = approachByVehicleId.values
                .filter { !it.alreadyPassed && it.remainingMeters <= 10_000.0 }
                .sortedBy { it.remainingMeters }
            val prefRecentlyPassed = approachByVehicleId.values
                .filter { it.alreadyPassed && it.remainingMeters in -950.0..-75.0 }

            val allLiveById = allLivePool.associateBy {
                GtfsRtProtobufParser.canonicalFleetId(it.vehicleId).ifBlank { it.vehicleId }
            }
            val reliableLiveByLine = HashMap<String, MutableList<BusVehicle>>()
            for (bus in allLivePool) {
                val h = com.example.data.model.GttFleetAnalyzer.assess(bus, emptyList(), nowMillis)
                if (h.isInDepot || h.isStaleGps) continue
                val key = bus.lineCode.trim().uppercase()
                if (key.isNotEmpty()) {
                    reliableLiveByLine.getOrPut(key) { ArrayList() }.add(bus)
                }
            }
            val hasCityWideLiveFeed = allLivePool.size >= 25

            // Precompute exact per-line polyline approaches for OTHER lines passing this stop (e.g. 55, 5, 58)
            data class OtherLineContext(
                val patterns: List<RoutePattern>,
                val stopDirectionId: Int,
                val terminusName: String?,
                val terminusFloorSec: Long?,
                val originPoint: GeoPoint?,
                val activeBuses: List<BusVehicle>,
                val approachByVehId: Map<String, com.example.data.model.GttFleetAnalyzer.BusStopApproach>,
                val approachingUpstream: List<com.example.data.model.GttFleetAnalyzer.BusStopApproach>,
                val recentlyPassedDownstream: List<com.example.data.model.GttFleetAnalyzer.BusStopApproach>,
            )
            val otherLineContexts = HashMap<String, OtherLineContext>()
            for ((rawKey, patterns) in otherLinePatterns) {
                val lineKey = rawKey.trim().uppercase()
                if (lineKey.isEmpty() || lineKey == normPref || patterns.isEmpty()) continue
                val otherDir = resolveStopDirectionOnLinePatterns(stop, patterns) ?: continue
                val stopForOther = stop.copy(directionId = otherDir)
                val otherDirPat = patterns
                    .filter { it.directionId == otherDir && it.points.size >= 2 }
                    .firstOrNull { pat ->
                        pat.stops.any {
                            it.stopId.equals(stop.stopId, ignoreCase = true) ||
                                it.stopCode.equals(stop.stopCode, ignoreCase = true)
                        }
                    }
                    ?: patterns.firstOrNull { it.directionId == otherDir && it.points.size >= 2 }
                val otherAlong = otherDirPat?.let { pat ->
                    com.example.data.model.GttFleetAnalyzer.projectAlongPolyline(stop.lat, stop.lon, pat.points)
                        ?.takeIf { it.perpMeters <= 220.0 }
                        ?.alongMeters
                }
                val otherFloor = otherAlong?.let { along ->
                    if (along < 250.0) 90L else (along / 5.8).toLong().coerceIn(180L, 1_200L)
                }
                val otherOriginPoint = otherDirPat?.stops?.firstOrNull()?.let { GeoPoint(it.lat, it.lon) }
                    ?: otherDirPat?.points?.firstOrNull()
                val otherBuses = com.example.data.model.GttFleetAnalyzer.filterOutConfirmedMatoPhantoms(
                    vehicles = reliableLiveByLine[lineKey].orEmpty(),
                    routePatterns = patterns,
                    nowMillis = nowMillis,
                )
                val appMap = LinkedHashMap<String, com.example.data.model.GttFleetAnalyzer.BusStopApproach>()
                for (bus in otherBuses) {
                    val app = com.example.data.model.GttFleetAnalyzer.estimateBusApproachToStop(
                        bus = bus,
                        stop = stopForOther,
                        routePatterns = patterns,
                        nowMillis = nowMillis,
                        allowTrafficPaused = true,
                    ) ?: continue
                    val canonBusId = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId }
                    appMap[canonBusId] = app
                }
                val upstream = appMap.values
                    .filter { !it.alreadyPassed && it.remainingMeters <= 10_000.0 }
                    .sortedBy { it.remainingMeters }
                val recentlyPassed = appMap.values
                    .filter { it.alreadyPassed && it.remainingMeters in -950.0..-75.0 }
                otherLineContexts[lineKey] = OtherLineContext(
                    patterns = patterns,
                    stopDirectionId = otherDir,
                    terminusName = otherDirPat?.terminusName(),
                    terminusFloorSec = otherFloor,
                    originPoint = otherOriginPoint,
                    activeBuses = otherBuses,
                    approachByVehId = appMap,
                    approachingUpstream = upstream,
                    recentlyPassedDownstream = recentlyPassed,
                )
            }

            // Process already-linked arrivals first, then unlinked realtime arrivals, then scheduled slots
            // so a verified vehicleId is never stolen by an earlier unlinked prediction.
            val orderedRawArrivals = rawArrivals.sortedWith(
                compareByDescending<StopArrival> { !it.vehicleId.isNullOrBlank() }
                    .thenByDescending { it.isRealtime }
                    .thenBy { it.arrivalEpochSeconds },
            )

            val coveredVehicleIds = HashSet<String>()
            val out = mutableListOf<StopArrival>()

            for (arr in orderedRawArrivals) {
                val lineKey = arr.lineCode.trim().uppercase()
                val isPref = lineKey.equals(normPref, ignoreCase = true)
                val vid = GtfsRtProtobufParser.canonicalFleetId(arr.vehicleId).ifBlank { null }
                val feedEtaSec = arr.arrivalEpochSeconds - nowSec

                if (isPref && vid != null) {
                    val approach = approachByVehicleId[vid]
                    if (approach != null) {
                        if (approach.alreadyPassed) {
                            if (feedEtaSec <= 15L * 60L) {
                                continue
                            } else {
                                out.add(
                                    arr.copy(
                                        isRealtime = false,
                                        vehicleId = null,
                                        sourceLabel = "Orario",
                                    ),
                                )
                                continue
                            }
                        } else {
                            val gpsEtaSec = approach.estimatedEtaSeconds
                            val maxToleranceSec = maxOf(300L, (gpsEtaSec * 0.65).toLong())
                            val wildlyWrongFeed = abs(feedEtaSec - gpsEtaSec) > maxToleranceSec
                            val nearStopButFeedHuge = approach.remainingMeters <= 1_400.0 && feedEtaSec > gpsEtaSec + 240L
                            coveredVehicleIds.add(vid)
                            if (wildlyWrongFeed || nearStopButFeedHuge) {
                                out.add(
                                    arr.copy(
                                        arrivalEpochSeconds = nowSec + gpsEtaSec,
                                        isRealtime = true,
                                        vehicleId = vid,
                                        headsign = arr.headsign.takeUnless { it.startsWith("Linea ", true) }
                                            ?: dirTerminus
                                            ?: arr.headsign,
                                        sourceLabel = "GPS Live",
                                    ),
                                )
                            } else {
                                out.add(arr)
                            }
                            continue
                        }
                    } else {
                        val knownBus = prefLineVehicles.firstOrNull {
                            GtfsRtProtobufParser.canonicalFleetId(it.vehicleId) == vid
                        }
                        if (knownBus != null) {
                            val resolvedDir = com.example.data.model.GttFleetAnalyzer.resolveDirectionId(knownBus, routePatterns)
                            val health = com.example.data.model.GttFleetAnalyzer.assess(knownBus, routePatterns, nowMillis)
                            if (health.isInDepot || (resolvedDir != null && resolvedDir != stop.directionId)) {
                                if (feedEtaSec <= 12L * 60L) {
                                    continue
                                } else {
                                    out.add(arr.copy(isRealtime = false, vehicleId = null, sourceLabel = "Orario"))
                                    continue
                                }
                            }
                        }
                    }
                } else if (!isPref) {
                    val otherCtx = otherLineContexts[lineKey]
                    if (vid != null) {
                        val otherApproach = otherCtx?.approachByVehId?.get(vid)
                        if (otherCtx != null && otherApproach != null) {
                            if (otherApproach.alreadyPassed) {
                                if (feedEtaSec <= 15L * 60L) {
                                    continue
                                } else {
                                    out.add(arr.copy(isRealtime = false, vehicleId = null, sourceLabel = "Orario"))
                                    continue
                                }
                            } else {
                                val gpsEtaSec = otherApproach.estimatedEtaSeconds
                                val maxToleranceSec = maxOf(300L, (gpsEtaSec * 0.65).toLong())
                                val wildlyWrongFeed = abs(feedEtaSec - gpsEtaSec) > maxToleranceSec
                                val nearStopButFeedHuge = otherApproach.remainingMeters <= 1_400.0 && feedEtaSec > gpsEtaSec + 240L
                                coveredVehicleIds.add(vid)
                                if (wildlyWrongFeed || nearStopButFeedHuge) {
                                    out.add(
                                        arr.copy(
                                            arrivalEpochSeconds = nowSec + gpsEtaSec,
                                            isRealtime = true,
                                            vehicleId = vid,
                                            headsign = arr.headsign.takeUnless { it.startsWith("Linea ", true) }
                                                ?: otherCtx.terminusName
                                                ?: arr.headsign,
                                            sourceLabel = "GPS Live",
                                        ),
                                    )
                                } else {
                                    out.add(arr)
                                }
                                continue
                            }
                        } else if (otherCtx != null) {
                            val knownOther = allLiveById[vid]
                            if (knownOther != null) {
                                val busDir = com.example.data.model.GttFleetAnalyzer.resolveDirectionId(knownOther, otherCtx.patterns)
                                val h = com.example.data.model.GttFleetAnalyzer.assess(knownOther, otherCtx.patterns, nowMillis)
                                if (h.isInDepot || (busDir != null && busDir != otherCtx.stopDirectionId)) {
                                    if (feedEtaSec <= 12L * 60L) {
                                        continue
                                    } else {
                                        out.add(arr.copy(isRealtime = false, vehicleId = null, sourceLabel = "Orario"))
                                        continue
                                    }
                                }
                            }
                        } else if (arr.isRealtime) {
                            // Fallback sanity check when other line's route pattern is unavailable
                            val otherBus = allLiveById[vid]
                            if (otherBus != null) {
                                val distM = com.example.data.model.GttFleetAnalyzer.haversineMeters(
                                    otherBus.latitude,
                                    otherBus.longitude,
                                    stop.lat,
                                    stop.lon,
                                )
                                val bearing = otherBus.bearing
                                val toStop = com.example.data.model.GttFleetAnalyzer.initialBearingDeg(
                                    otherBus.latitude,
                                    otherBus.longitude,
                                    stop.lat,
                                    stop.lon,
                                )
                                val angleDiff = bearing?.let {
                                    com.example.data.model.GttFleetAnalyzer.angleDiffDeg(it.toDouble(), toStop)
                                }
                                if (distM <= 260.0 && (angleDiff == null || angleDiff <= 80.0) && feedEtaSec > 8L * 60L) {
                                    val fixedSec = ((distM / 4.8).toLong()).coerceIn(20L, 90L)
                                    out.add(arr.copy(arrivalEpochSeconds = nowSec + fixedSec, sourceLabel = "GPS Live"))
                                    continue
                                }
                                if (distM >= 450.0 && angleDiff != null && angleDiff >= 135.0 && feedEtaSec <= 120L) {
                                    continue
                                }
                            }
                        }
                    } else if (arr.isRealtime && otherCtx != null && otherCtx.approachingUpstream.isNotEmpty()) {
                        // Link unlinked realtime arrival on another line (e.g. GTT Palina Line 55) ONLY to an
                        // upstream bus on Line 55's EXACT direction whose polyline ETA matches!
                        val maxGap = maxOf(240L, (feedEtaSec.coerceAtLeast(0L) * 0.50).toLong())
                        val bestMatch = otherCtx.approachingUpstream
                            .filter {
                                val cid = GtfsRtProtobufParser.canonicalFleetId(it.bus.vehicleId).ifBlank { it.bus.vehicleId }
                                cid !in coveredVehicleIds
                            }
                            .minByOrNull { abs(it.estimatedEtaSeconds - feedEtaSec) }
                        if (bestMatch != null && abs(bestMatch.estimatedEtaSeconds - feedEtaSec) <= maxGap) {
                            val cid = GtfsRtProtobufParser.canonicalFleetId(bestMatch.bus.vehicleId).ifBlank { bestMatch.bus.vehicleId }
                            coveredVehicleIds.add(cid)
                            out.add(
                                arr.copy(
                                    vehicleId = bestMatch.bus.vehicleId,
                                    tripId = arr.tripId ?: bestMatch.bus.tripId,
                                ),
                            )
                            continue
                        }
                    }
                }

                // For unlinked preferredLine "Live" arrivals, link to the closest ETA match among uncovered upstream buses
                if (isPref && vid == null && arr.isRealtime && approachingUpstream.isNotEmpty()) {
                    val bestMatch = approachingUpstream
                        .filter {
                            val cid = GtfsRtProtobufParser.canonicalFleetId(it.bus.vehicleId).ifBlank { it.bus.vehicleId }
                            cid !in coveredVehicleIds
                        }
                        .minByOrNull { abs(it.estimatedEtaSeconds - feedEtaSec) }
                    if (bestMatch != null) {
                        val maxGap = maxOf(300L, (bestMatch.estimatedEtaSeconds * 0.65).toLong())
                        if (abs(bestMatch.estimatedEtaSeconds - feedEtaSec) <= maxGap) {
                            val cid = GtfsRtProtobufParser.canonicalFleetId(bestMatch.bus.vehicleId).ifBlank { bestMatch.bus.vehicleId }
                            coveredVehicleIds.add(cid)
                            out.add(arr.copy(vehicleId = bestMatch.bus.vehicleId))
                            continue
                        }
                    }
                }

                out.add(arr)
            }

            // Always ensure EVERY approaching reliable live bus on preferredLine is represented,
            // absorbing any nearby scheduled slot (up to 9 min late or 4 min early) instead of duplicating it!
            for (approach in approachingUpstream) {
                val cid = GtfsRtProtobufParser.canonicalFleetId(approach.bus.vehicleId).ifBlank { approach.bus.vehicleId }
                if (cid in coveredVehicleIds) continue
                val existingCloseIdx = out.withIndex()
                    .filter { (_, existing) ->
                        if (!existing.lineCode.equals(normPref, ignoreCase = true) || existing.vehicleId != null) {
                            return@filter false
                        }
                        val existingEta = existing.arrivalEpochSeconds - nowSec
                        val delta = approach.estimatedEtaSeconds - existingEta
                        if (existing.isRealtime) {
                            abs(delta) <= 270L
                        } else {
                            // A real bus running up to 9 min late (or 4 min early) replaces that scheduled slot
                            delta in -240L..540L
                        }
                    }
                    .minByOrNull { (_, existing) ->
                        abs((existing.arrivalEpochSeconds - nowSec) - approach.estimatedEtaSeconds)
                    }
                    ?.index

                if (existingCloseIdx != null) {
                    val existing = out[existingCloseIdx]
                    val existingEta = existing.arrivalEpochSeconds - nowSec
                    val shouldUpdateEta = !existing.isRealtime || abs(existingEta - approach.estimatedEtaSeconds) > 180L
                    out[existingCloseIdx] = existing.copy(
                        arrivalEpochSeconds = if (shouldUpdateEta) nowSec + approach.estimatedEtaSeconds else existing.arrivalEpochSeconds,
                        isRealtime = true,
                        vehicleId = approach.bus.vehicleId,
                        tripId = existing.tripId ?: approach.bus.tripId,
                        headsign = existing.headsign.takeUnless { it.startsWith("Linea ", true) }
                            ?: dirTerminus
                            ?: existing.headsign,
                        sourceLabel = if (shouldUpdateEta) "GPS Live" else existing.sourceLabel,
                    )
                    coveredVehicleIds.add(cid)
                } else if (!approach.isTrafficPaused && approach.remainingMeters <= 6_500.0) {
                    out.add(
                        StopArrival(
                            lineCode = normPref,
                            headsign = dirTerminus ?: "Linea $normPref",
                            arrivalEpochSeconds = nowSec + approach.estimatedEtaSeconds,
                            isRealtime = true,
                            delaySeconds = approach.bus.delaySeconds,
                            vehicleId = approach.bus.vehicleId,
                            tripId = approach.bus.tripId,
                            sourceLabel = "GPS Live",
                        ),
                    )
                    coveredVehicleIds.add(cid)
                }
            }

            // For OTHER lines with resolved route geometry, absorb any nearby unlinked scheduled slot
            // corresponding to an uncovered upstream bus (prevents duplicate Stimato + Live and enables crosshair!)
            for ((lineKey, otherCtx) in otherLineContexts) {
                for (approach in otherCtx.approachingUpstream) {
                    val cid = GtfsRtProtobufParser.canonicalFleetId(approach.bus.vehicleId).ifBlank { approach.bus.vehicleId }
                    if (cid in coveredVehicleIds) continue
                    val existingCloseIdx = out.withIndex()
                        .filter { (_, existing) ->
                            if (!existing.lineCode.equals(lineKey, ignoreCase = true) || existing.vehicleId != null) {
                                return@filter false
                            }
                            val existingEta = existing.arrivalEpochSeconds - nowSec
                            val delta = approach.estimatedEtaSeconds - existingEta
                            if (existing.isRealtime) {
                                abs(delta) <= 270L
                            } else {
                                delta in -240L..540L
                            }
                        }
                        .minByOrNull { (_, existing) ->
                            abs((existing.arrivalEpochSeconds - nowSec) - approach.estimatedEtaSeconds)
                        }
                        ?.index
                    if (existingCloseIdx != null) {
                        val existing = out[existingCloseIdx]
                        val existingEta = existing.arrivalEpochSeconds - nowSec
                        val shouldUpdateEta = !existing.isRealtime || abs(existingEta - approach.estimatedEtaSeconds) > 180L
                        out[existingCloseIdx] = existing.copy(
                            arrivalEpochSeconds = if (shouldUpdateEta) nowSec + approach.estimatedEtaSeconds else existing.arrivalEpochSeconds,
                            isRealtime = true,
                            vehicleId = approach.bus.vehicleId,
                            tripId = existing.tripId ?: approach.bus.tripId,
                            headsign = existing.headsign.takeUnless { it.startsWith("Linea ", true) }
                                ?: otherCtx.terminusName
                                ?: existing.headsign,
                            sourceLabel = if (shouldUpdateEta) "GPS Live" else existing.sourceLabel,
                        )
                        coveredVehicleIds.add(cid)
                    }
                }
            }

            // Build verified-live arrival lookup & unclaimed fleet capacity per line for physical consistency filtering
            val verifiedLiveByLine = out
                .filter { it.isRealtime && !it.vehicleId.isNullOrBlank() }
                .groupBy { it.lineCode.trim().uppercase() }

            fun countOriginTerminusBuses(
                lineBuses: List<BusVehicle>,
                originPoint: GeoPoint?,
            ): Int {
                if (originPoint == null || lineBuses.isEmpty()) return 0
                return lineBuses.count { bus ->
                    val cid = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId }
                    if (cid in coveredVehicleIds) return@count false
                    com.example.data.model.GttFleetAnalyzer.haversineMeters(
                        bus.latitude,
                        bus.longitude,
                        originPoint.lat,
                        originPoint.lon,
                    ) <= 320.0
                }
            }

            fun hasSecondPhysicalBusForSlot(
                unclaimedUpstream: List<com.example.data.model.GttFleetAnalyzer.BusStopApproach>,
                originTerminusCount: Int,
                floorSec: Long?,
                slotEtaSec: Long,
            ): Boolean {
                val toleranceSec = maxOf(360L, (slotEtaSec.coerceAtLeast(0L) * 0.55).toLong())
                if (unclaimedUpstream.any { abs(it.estimatedEtaSeconds - slotEtaSec) <= toleranceSec }) {
                    return true
                }
                if (originTerminusCount > 0 && floorSec != null && slotEtaSec >= floorSec) {
                    return true
                }
                return false
            }

            val prefUnclaimedUpstream = approachingUpstream.filter {
                val cid = GtfsRtProtobufParser.canonicalFleetId(it.bus.vehicleId).ifBlank { it.bus.vehicleId }
                cid !in coveredVehicleIds
            }
            val prefOriginTerminusCount = countOriginTerminusBuses(prefLineVehicles, prefOriginPoint)

            // Final physical reality filter: drop impossible phantom arrivals & incompatible nearby duplicates
            // when live GPS telemetry is active, while preserving real buses (including GPS-less GTT Palina Live runs).
            val hasPrefLiveTelemetry = prefLineVehicles.isNotEmpty() && terminusFloorSec != null
            return out.filter { arr ->
                val vid = arr.vehicleId?.trim()?.takeIf { it.isNotEmpty() }
                if (vid != null) return@filter true // Verified live vehicle!

                val lineKey = arr.lineCode.trim().uppercase()
                val isPref = lineKey.equals(normPref, ignoreCase = true)
                val feedEtaSec = arr.arrivalEpochSeconds - nowSec
                val verifiedSameLine = verifiedLiveByLine[lineKey].orEmpty()

                val lineHasLiveAndGeo: Boolean
                val lineFloorSec: Long?
                val lineUnclaimedUpstream: List<com.example.data.model.GttFleetAnalyzer.BusStopApproach>
                val lineRecentlyPassed: List<com.example.data.model.GttFleetAnalyzer.BusStopApproach>
                val lineOriginCount: Int

                if (isPref) {
                    lineHasLiveAndGeo = hasPrefLiveTelemetry
                    lineFloorSec = terminusFloorSec
                    lineUnclaimedUpstream = prefUnclaimedUpstream
                    lineRecentlyPassed = prefRecentlyPassed
                    lineOriginCount = prefOriginTerminusCount
                } else {
                    val otherCtx = otherLineContexts[lineKey]
                    lineFloorSec = otherCtx?.terminusFloorSec
                    lineHasLiveAndGeo = otherCtx != null && otherCtx.activeBuses.isNotEmpty() && lineFloorSec != null
                    lineUnclaimedUpstream = otherCtx?.approachingUpstream.orEmpty().filter {
                        val cid = GtfsRtProtobufParser.canonicalFleetId(it.bus.vehicleId).ifBlank { it.bus.vehicleId }
                        cid !in coveredVehicleIds
                    }
                    lineRecentlyPassed = otherCtx?.recentlyPassedDownstream.orEmpty()
                    lineOriginCount = if (otherCtx != null) {
                        countOriginTerminusBuses(otherCtx.activeBuses, otherCtx.originPoint)
                    } else {
                        0
                    }
                }

                // GTT Palina AVM Live (unlinked): keep standalone real runs (e.g. silent GPS transponder),
                // but reject clear duplicates near an already-tracked bus without a 2nd physical vehicle
                // and imminent post-passage ghosts when a bus just passed the stop!
                if (arr.sourceLabel == "GTT Palina" && arr.isRealtime) {
                    if (lineHasLiveAndGeo) {
                        val hasSecondBus = hasSecondPhysicalBusForSlot(
                            unclaimedUpstream = lineUnclaimedUpstream,
                            originTerminusCount = lineOriginCount,
                            floorSec = lineFloorSec,
                            slotEtaSec = feedEtaSec,
                        )
                        val isDuplicateNearTracked = verifiedSameLine.any { tracked ->
                            abs(tracked.arrivalEpochSeconds - arr.arrivalEpochSeconds) <= 300L
                        }
                        if (isDuplicateNearTracked && !hasSecondBus) {
                            return@filter false
                        }
                        val isPostPassageGhost = feedEtaSec <= 180L &&
                            lineRecentlyPassed.isNotEmpty() &&
                            lineUnclaimedUpstream.isEmpty() &&
                            verifiedSameLine.none { abs(it.arrivalEpochSeconds - arr.arrivalEpochSeconds) <= 180L }
                        if (isPostPassageGhost) {
                            return@filter false
                        }
                    }
                    return@filter true
                }

                if (isPref && hasPrefLiveTelemetry) {
                    if (arr.isRealtime && feedEtaSec <= 25L * 60L) return@filter false
                    if (!arr.isRealtime) {
                        if (feedEtaSec < terminusFloorSec) return@filter false
                        val nearVerifiedLive = verifiedSameLine.any { tracked ->
                            val diff = tracked.arrivalEpochSeconds - arr.arrivalEpochSeconds
                            abs(diff) <= 360L || diff in -240L..540L
                        }
                        if (nearVerifiedLive && !hasSecondPhysicalBusForSlot(
                                unclaimedUpstream = lineUnclaimedUpstream,
                                originTerminusCount = lineOriginCount,
                                floorSec = terminusFloorSec,
                                slotEtaSec = feedEtaSec,
                            )
                        ) {
                            return@filter false
                        }
                    }
                    return@filter true
                }

                if (!isPref) {
                    val otherBuses = reliableLiveByLine[lineKey].orEmpty()
                    if (lineHasLiveAndGeo && lineFloorSec != null) {
                        // Exact polyline-based verification on Line 55/5/etc.
                        if (arr.isRealtime && feedEtaSec <= 25L * 60L) return@filter false
                        if (!arr.isRealtime) {
                            if (feedEtaSec < lineFloorSec) return@filter false
                            val nearVerifiedLive = verifiedSameLine.any { tracked ->
                                val diff = tracked.arrivalEpochSeconds - arr.arrivalEpochSeconds
                                abs(diff) <= 360L || diff in -240L..540L
                            }
                            if (nearVerifiedLive && !hasSecondPhysicalBusForSlot(
                                    unclaimedUpstream = lineUnclaimedUpstream,
                                    originTerminusCount = lineOriginCount,
                                    floorSec = lineFloorSec,
                                    slotEtaSec = feedEtaSec,
                                )
                            ) {
                                return@filter false
                            }
                        }
                    } else if (otherBuses.isNotEmpty() && feedEtaSec <= 12L * 60L) {
                        val maxReachableMeters = maxOf(700.0, feedEtaSec * 7.2 + 500.0)
                        val anyReachable = otherBuses.any { bus ->
                            val dist = com.example.data.model.GttFleetAnalyzer.haversineMeters(
                                bus.latitude,
                                bus.longitude,
                                stop.lat,
                                stop.lon,
                            )
                            if (dist > maxReachableMeters) return@any false
                            if (dist <= 300.0) return@any true
                            val b = bus.bearing ?: return@any true
                            val toStop = com.example.data.model.GttFleetAnalyzer.initialBearingDeg(
                                bus.latitude,
                                bus.longitude,
                                stop.lat,
                                stop.lon,
                            )
                            com.example.data.model.GttFleetAnalyzer.angleDiffDeg(b.toDouble(), toStop) <= 130.0
                        }
                        if (!anyReachable) return@filter false
                    } else if (otherBuses.isEmpty() && hasCityWideLiveFeed && feedEtaSec <= 15L * 60L) {
                        return@filter false
                    }
                }

                true
            }.map { arr ->
                if (!com.example.data.model.isGenericDirectionLabel(arr.headsign)) {
                    arr
                } else {
                    val lineKey = arr.lineCode.trim().uppercase()
                    val exactTerminus = if (lineKey.equals(normPref, ignoreCase = true)) {
                        dirTerminus
                    } else {
                        otherLineContexts[lineKey]?.terminusName
                    }
                    if (!exactTerminus.isNullOrBlank() && !com.example.data.model.isGenericDirectionLabel(exactTerminus)) {
                        arr.copy(headsign = exactTerminus)
                    } else {
                        arr
                    }
                }
            }
        }

        internal fun deduplicateArrivals(
            list: List<StopArrival>,
        ): MutableList<StopArrival> {
            val out = mutableListOf<StopArrival>()
            val sorted = list.sortedWith(
                compareByDescending<StopArrival> { it.isRealtime }
                    .thenByDescending {
                        it.sourceLabel == "GPS Live" ||
                            it.sourceLabel == "GTT Palina" ||
                            it.sourceLabel == "MATO Live" ||
                            it.sourceLabel == "GTT RT"
                    }
                    .thenBy { it.arrivalEpochSeconds },
            )
            for (item in sorted) {
                val itemTrip = GtfsRtProtobufParser.normalizeTripId(item.tripId)
                val itemVeh = GtfsRtProtobufParser.canonicalFleetId(item.vehicleId).ifBlank { null }

                val existingIdx = out.indexOfFirst { existing ->
                    val sameLine = existing.lineCode.equals(item.lineCode, ignoreCase = true)
                    val existingTrip = GtfsRtProtobufParser.normalizeTripId(existing.tripId)
                    val existingVeh = GtfsRtProtobufParser.canonicalFleetId(existing.vehicleId).ifBlank { null }

                    val sameTrip = itemTrip != null && existingTrip != null &&
                        itemTrip.equals(existingTrip, ignoreCase = true)
                    val bothHaveDistinctTrips = itemTrip != null && existingTrip != null &&
                        !itemTrip.equals(existingTrip, ignoreCase = true)
                    val sameVehicleNearby = sameLine && itemVeh != null && existingVeh != null &&
                        itemVeh.equals(existingVeh, ignoreCase = true) &&
                        abs(existing.arrivalEpochSeconds - item.arrivalEpochSeconds) <= 15L * 60L
                    val sameTimeSlot = sameLine &&
                        abs(existing.arrivalEpochSeconds - item.arrivalEpochSeconds) <= 120L
                    // Collapse an unlinked scheduled entry into a delayed real-time bus (up to 7 min late or 3 min early)
                    val delayedScheduledOfSameLiveBus = sameLine &&
                        existing.isRealtime &&
                        !item.isRealtime &&
                        itemVeh == null &&
                        !bothHaveDistinctTrips &&
                        (existing.arrivalEpochSeconds - item.arrivalEpochSeconds) in -180L..420L

                    sameTrip || sameVehicleNearby || sameTimeSlot || delayedScheduledOfSameLiveBus
                }
                if (existingIdx < 0) {
                    out.add(item)
                } else {
                    // Merge useful vehicleId / tripId / headsign from secondary source into the winning entry
                    val existing = out[existingIdx]
                    val mergedVehicleId = existing.vehicleId ?: item.vehicleId
                    val mergedTripId = existing.tripId ?: item.tripId
                    val mergedHeadsign = if (
                        com.example.data.model.isGenericDirectionLabel(existing.headsign) &&
                        !com.example.data.model.isGenericDirectionLabel(item.headsign)
                    ) {
                        item.headsign
                    } else {
                        existing.headsign
                    }
                    if (mergedVehicleId != existing.vehicleId ||
                        mergedTripId != existing.tripId ||
                        mergedHeadsign != existing.headsign
                    ) {
                        out[existingIdx] = existing.copy(
                            vehicleId = mergedVehicleId,
                            tripId = mergedTripId,
                            headsign = mergedHeadsign,
                        )
                    }
                }
            }
            return out
        }
    }
}
