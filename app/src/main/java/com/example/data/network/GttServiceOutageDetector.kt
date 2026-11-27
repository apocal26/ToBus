package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.example.data.model.BusVehicle
import com.example.data.model.DataSourceMode
import com.example.data.model.GttDataOutageReport
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.GttOutageKind
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DeviceConnectivityInfo(
    val isConnected: Boolean,
    val isValidated: Boolean,
    val isCaptivePortal: Boolean,
    val transportLabel: String,
) {
    val isHealthyForOutageCheck: Boolean
        get() = isConnected && isValidated && !isCaptivePortal
}

data class NeutralInternetProbeResult(
    val reachable: Boolean,
    val httpCode: Int?,
    val latencyMs: Long,
    val checkedAtMillis: Long,
    val endpoint: String,
    val detail: String,
)

data class OutageDetectorSnapshot(
    val activeOutage: GttDataOutageReport?,
    val isSimulated: Boolean,
    val connectivity: DeviceConnectivityInfo,
    val lastNeutralProbe: NeutralInternetProbeResult?,
    val consecutiveTotalFailures: Int,
    val consecutiveStalePolls: Int,
    val consecutiveFrozenPolls: Int,
    val lastEvaluationNote: String,
)

/**
 * High-confidence detector for citywide upstream GTT / 5T data outages.
 *
 * Anti-false-positive guarantees:
 * 1. Mass server unreachability (`SERVERS_UNREACHABLE`) fires ONLY in `DataSourceMode.AUTO`,
 *    ONLY when Android confirms a validated non-captive network AND an independent lightweight
 *    neutral internet probe (`gstatic/generate_204`) succeeds, AND across >= 2 consecutive total
 *    failures spanning >= 18s.
 * 2. Mass stale feed (`CITYWIDE_STALE_FEED`) fires ONLY when >= 85% of >= 15 non-depot vehicles
 *    across >= 5 distinct lines have GPS timestamps older than 8 minutes, NO responding source
 *    shows active physical bus movement, across >= 2 polls spanning >= 40s.
 * 3. Mass frozen fleet (`CITYWIDE_FROZEN_FLEET`) fires ONLY when >= 20 non-depot vehicles across
 *    >= 6 lines show < 3% moving across ALL responding citywide sources for >= 180s with ZERO
 *    healthy moving fallback source, across >= 2 consecutive evaluations.
 * 4. Clears immediately as soon as healthy movement or fresh citywide updates resume.
 */
internal class GttServiceOutageDetector(
    private val appContext: Context? = null,
    baseHttpClientProvider: (() -> OkHttpClient)? = null,
) {
    private val probeClient: OkHttpClient? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        baseHttpClientProvider?.invoke()?.newBuilder()
            ?.connectTimeout(2500, TimeUnit.MILLISECONDS)
            ?.readTimeout(2500, TimeUnit.MILLISECONDS)
            ?.followRedirects(false)
            ?.build()
    }

    private val lock = Any()

    @Volatile
    private var simulatedOutage: Boolean = false

    private var consecutiveTotalFailures: Int = 0
    private var firstTotalFailureMillis: Long = 0L

    private var consecutiveStalePolls: Int = 0
    private var firstStalePollMillis: Long = 0L

    private var consecutiveFrozenPolls: Int = 0
    private var firstFrozenPollMillis: Long = 0L

    private var lastNeutralProbe: NeutralInternetProbeResult? = null
    private var lastConnectivity: DeviceConnectivityInfo = DeviceConnectivityInfo(
        isConnected = true,
        isValidated = true,
        isCaptivePortal = false,
        transportLabel = "Sconosciuta",
    )
    private var activeOutage: GttDataOutageReport? = null
    private var lastEvaluationNote: String = "In attesa del primo ciclo di polling"

    fun setSimulatedOutage(enabled: Boolean, nowMillis: Long = System.currentTimeMillis()): GttDataOutageReport? {
        synchronized(lock) {
            simulatedOutage = enabled
            val report = if (enabled) buildSimulatedReport(nowMillis) else activeOutage
            ToBusDiagnosticsLog.record(
                category = DiagCategory.OUTAGE,
                message = if (enabled) "Simulazione disservizio GTT ATTIVATA" else "Simulazione disservizio GTT disattivata",
                severity = if (enabled) DiagSeverity.WARN else DiagSeverity.OK,
            )
            return report
        }
    }

    fun isSimulatedOutageEnabled(): Boolean = simulatedOutage

    fun currentActiveOutage(nowMillis: Long = System.currentTimeMillis()): GttDataOutageReport? = synchronized(lock) {
        if (simulatedOutage) buildSimulatedReport(nowMillis) else activeOutage
    }

    fun reset() {
        synchronized(lock) {
            consecutiveTotalFailures = 0
            firstTotalFailureMillis = 0L
            consecutiveStalePolls = 0
            firstStalePollMillis = 0L
            consecutiveFrozenPolls = 0
            firstFrozenPollMillis = 0L
            activeOutage = null
            lastEvaluationNote = "Stato azzerato"
        }
        ToBusDiagnosticsLog.record(
            category = DiagCategory.OUTAGE,
            message = "Contatori rilevatore disservizi GTT azzerati",
            severity = DiagSeverity.INFO,
        )
    }

    fun getDiagnosticSnapshot(nowMillis: Long = System.currentTimeMillis()): OutageDetectorSnapshot {
        val currentConn = appContext?.let { checkDeviceConnectivity(it) } ?: lastConnectivity
        return synchronized(lock) {
            lastConnectivity = currentConn
            OutageDetectorSnapshot(
                activeOutage = if (simulatedOutage) buildSimulatedReport(nowMillis) else activeOutage,
                isSimulated = simulatedOutage,
                connectivity = currentConn,
                lastNeutralProbe = lastNeutralProbe,
                consecutiveTotalFailures = consecutiveTotalFailures,
                consecutiveStalePolls = consecutiveStalePolls,
                consecutiveFrozenPolls = consecutiveFrozenPolls,
                lastEvaluationNote = lastEvaluationNote,
            )
        }
    }

    fun checkCurrentConnectivity(): DeviceConnectivityInfo {
        val info = appContext?.let { checkDeviceConnectivity(it) } ?: DeviceConnectivityInfo(
            isConnected = true,
            isValidated = true,
            isCaptivePortal = false,
            transportLabel = "Sconosciuta",
        )
        synchronized(lock) {
            lastConnectivity = info
        }
        return info
    }

    fun probeNeutralInternet(
        force: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): NeutralInternetProbeResult {
        synchronized(lock) {
            val cached = lastNeutralProbe
            if (!force && cached != null && (nowMillis - cached.checkedAtMillis) < NEUTRAL_PROBE_CACHE_MS) {
                return cached
            }
        }
        val client = probeClient
        if (client == null) {
            return NeutralInternetProbeResult(
                reachable = false,
                httpCode = null,
                latencyMs = 0L,
                checkedAtMillis = nowMillis,
                endpoint = NEUTRAL_PROBE_URLS.first(),
                detail = "Client HTTP non configurato",
            )
        }
        var lastError = "Timeout"
        var lastEndpoint = NEUTRAL_PROBE_URLS.first()
        var lastCode: Int? = null
        val totalStart = SystemClock.elapsedRealtime()
        for (url in NEUTRAL_PROBE_URLS) {
            lastEndpoint = url
            val reqStart = SystemClock.elapsedRealtime()
            try {
                val req = Request.Builder()
                    .url(url)
                    .head()
                    .header("Cache-Control", "no-cache")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val latency = (SystemClock.elapsedRealtime() - reqStart).coerceAtLeast(1L)
                    lastCode = resp.code
                    if (resp.code == 204 || resp.code == 200) {
                        val result = NeutralInternetProbeResult(
                            reachable = true,
                            httpCode = resp.code,
                            latencyMs = latency,
                            checkedAtMillis = nowMillis,
                            endpoint = url,
                            detail = "HTTP ${resp.code} · ${latency}ms",
                        )
                        synchronized(lock) { lastNeutralProbe = result }
                        ToBusDiagnosticsLog.record(
                            category = DiagCategory.NET,
                            message = "Probe internet neutrale OK (${resp.code}, ${latency}ms)",
                            detail = url,
                            severity = DiagSeverity.OK,
                        )
                        return result
                    } else {
                        lastError = "HTTP ${resp.code} (possibile captive portal)"
                    }
                }
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName + (e.message?.let { ": ${it.take(40)}" }.orEmpty())
            }
        }
        val failed = NeutralInternetProbeResult(
            reachable = false,
            httpCode = lastCode,
            latencyMs = (SystemClock.elapsedRealtime() - totalStart).coerceAtLeast(1L),
            checkedAtMillis = nowMillis,
            endpoint = lastEndpoint,
            detail = lastError,
        )
        synchronized(lock) { lastNeutralProbe = failed }
        ToBusDiagnosticsLog.record(
            category = DiagCategory.NET,
            message = "Probe internet neutrale fallito ($lastError)",
            detail = lastEndpoint,
            severity = DiagSeverity.WARN,
        )
        return failed
    }

    /**
     * Evaluates a live poll where ALL enabled vehicle feeds failed to respond.
     * Performs device connectivity + independent neutral internet probe checks before calling it a
     * GTT upstream server outage.
     */
    fun onTotalFeedFailure(
        dataSourceMode: DataSourceMode,
        failureDetail: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): GttDataOutageReport? {
        val connectivity = checkCurrentConnectivity()
        val probe = if (dataSourceMode == DataSourceMode.AUTO && connectivity.isHealthyForOutageCheck) {
            probeNeutralInternet(force = false, nowMillis = nowMillis)
        } else {
            null
        }
        return evaluateTotalFailure(
            dataSourceMode = dataSourceMode,
            connectivity = connectivity,
            neutralProbe = probe,
            nowMillis = nowMillis,
            failureDetail = failureDetail,
        )
    }

    /**
     * Pure deterministic evaluator for total feed failure (unit-testable without Android Context).
     */
    internal fun evaluateTotalFailure(
        dataSourceMode: DataSourceMode,
        connectivity: DeviceConnectivityInfo,
        neutralProbe: NeutralInternetProbeResult?,
        nowMillis: Long,
        failureDetail: String? = null,
    ): GttDataOutageReport? {
        synchronized(lock) {
            lastConnectivity = connectivity
            if (neutralProbe != null) lastNeutralProbe = neutralProbe
            if (simulatedOutage) return buildSimulatedReport(nowMillis)

            // Guard 1: Single fixed source failures must NOT be escalated as citywide GTT outage
            if (dataSourceMode != DataSourceMode.AUTO) {
                consecutiveTotalFailures = 0
                firstTotalFailureMillis = 0L
                lastEvaluationNote = "Fonte fissa (${dataSourceMode.shortLabelIt}) fallita: non è disservizio GTT globale"
                return null
            }

            // Guard 2: Device itself is offline, unvalidated, or behind captive portal
            if (!connectivity.isHealthyForOutageCheck) {
                consecutiveTotalFailures = 0
                firstTotalFailureMillis = 0L
                val reason = when {
                    !connectivity.isConnected -> "Dispositivo offline"
                    connectivity.isCaptivePortal -> "Rete captive portal rilevata"
                    else -> "Connessione dispositivo non validata (${connectivity.transportLabel})"
                }
                lastEvaluationNote = "$reason — nessun avviso disservizio GTT"
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.OUTAGE,
                    message = "Escluso disservizio GTT: $reason",
                    detail = failureDetail,
                    severity = DiagSeverity.INFO,
                )
                return null
            }

            // Guard 3: Independent neutral internet check MUST confirm general internet works
            if (neutralProbe == null || !neutralProbe.reachable) {
                consecutiveTotalFailures = 0
                firstTotalFailureMillis = 0L
                lastEvaluationNote = "Probe internet neutrale negativo (${neutralProbe?.detail ?: "n/d"}): probabile rete locale"
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.OUTAGE,
                    message = "Escluso disservizio GTT: internet generale non raggiungibile",
                    detail = neutralProbe?.detail,
                    severity = DiagSeverity.INFO,
                )
                return null
            }

            // Guard 4: Require >= 2 consecutive total failures spanning >= 18s
            if (consecutiveTotalFailures == 0 || firstTotalFailureMillis <= 0L) {
                consecutiveTotalFailures = 1
                firstTotalFailureMillis = nowMillis
                lastEvaluationNote = "1° fallimento totale GTT con internet OK (${neutralProbe.latencyMs}ms): attendo conferma"
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.OUTAGE,
                    message = "1° fallimento totale fonti GTT (internet OK ${neutralProbe.latencyMs}ms) — in attesa di conferma",
                    detail = failureDetail,
                    severity = DiagSeverity.WARN,
                )
                return activeOutage?.takeIf { it.kind == GttOutageKind.SERVERS_UNREACHABLE }
            }

            consecutiveTotalFailures++
            val elapsedSec = ((nowMillis - firstTotalFailureMillis) / 1000L).coerceAtLeast(0L)
            if (consecutiveTotalFailures >= MIN_TOTAL_FAILURE_STREAK && elapsedSec >= MIN_TOTAL_FAILURE_DURATION_SEC) {
                val report = GttDataOutageReport(
                    kind = GttOutageKind.SERVERS_UNREACHABLE,
                    title = "Probabile disservizio server GTT",
                    detail = "I server GTT/5T non rispondono da ${formatElapsedIt(elapsedSec)} mentre la tua rete (${connectivity.transportLabel}) è regolare.",
                    detectedAtMillis = firstTotalFailureMillis,
                    isSimulated = false,
                )
                val wasActive = activeOutage?.kind == GttOutageKind.SERVERS_UNREACHABLE
                activeOutage = report
                lastEvaluationNote = "CONFERMATO: server GTT irraggiungibili ($consecutiveTotalFailures tentativi, ${elapsedSec}s, internet OK)"
                if (!wasActive) {
                    ToBusDiagnosticsLog.record(
                        category = DiagCategory.OUTAGE,
                        message = "Disservizio confermato: server GTT irraggiungibili",
                        detail = "streak=$consecutiveTotalFailures elapsed=${elapsedSec}s net=${connectivity.transportLabel} probe=${neutralProbe.detail}",
                        severity = DiagSeverity.ERROR,
                    )
                }
                return report
            }

            lastEvaluationNote = "Fallimento #$consecutiveTotalFailures (${elapsedSec}s/${MIN_TOTAL_FAILURE_DURATION_SEC}s) — attesa conferma"
            return activeOutage?.takeIf { it.kind == GttOutageKind.SERVERS_UNREACHABLE }
        }
    }

    /**
     * Evaluates a successful vehicle poll for mass citywide stale timestamps or mass citywide
     * frozen GPS coordinates across ALL responding sources.
     */
    fun onLivePollSuccess(
        vehicles: List<BusVehicle>,
        feedTimestampSeconds: Long,
        sourceReports: Map<String, SourceHealthReport>,
        dataSourceMode: DataSourceMode,
        nowMillis: Long = System.currentTimeMillis(),
    ): GttDataOutageReport? {
        val connectivity = checkCurrentConnectivity()
        return evaluateSuccess(
            vehicles = vehicles,
            feedTimestampSeconds = feedTimestampSeconds,
            sourceReports = sourceReports,
            dataSourceMode = dataSourceMode,
            connectivity = connectivity,
            nowMillis = nowMillis,
        )
    }

    /**
     * Pure deterministic evaluator for successful polls (unit-testable without Android Context).
     */
    internal fun evaluateSuccess(
        vehicles: List<BusVehicle>,
        feedTimestampSeconds: Long,
        sourceReports: Map<String, SourceHealthReport>,
        dataSourceMode: DataSourceMode,
        connectivity: DeviceConnectivityInfo,
        nowMillis: Long,
    ): GttDataOutageReport? {
        synchronized(lock) {
            lastConnectivity = connectivity
            val previousOutage = activeOutage
            // Any successful fetch clears server-unreachable failure streak immediately
            consecutiveTotalFailures = 0
            firstTotalFailureMillis = 0L

            if (simulatedOutage) return buildSimulatedReport(nowMillis)

            if (!connectivity.isHealthyForOutageCheck) {
                lastEvaluationNote = "Rete locale non validata (${connectivity.transportLabel}): valutazione sospesa"
                return null
            }

            // Filter out parked depot vehicles so nighttime/depot buses never skew citywide ratios
            val nonDepot = vehicles.filter { bus ->
                GttFleetAnalyzer.findDepot(bus.latitude, bus.longitude) == null
            }
            val nonDepotCount = nonDepot.size
            val distinctLines = nonDepot.mapTo(HashSet()) { it.lineCode.trim().uppercase(Locale.ROOT) }.size

            // Guard: Nighttime / sparse fleet or single-line MaTO-only stream
            if (nonDepotCount < MIN_STALE_VEHICLES || distinctLines < MIN_STALE_LINES) {
                consecutiveStalePolls = 0
                firstStalePollMillis = 0L
                consecutiveFrozenPolls = 0
                firstFrozenPollMillis = 0L
                if (previousOutage != null && previousOutage.kind == GttOutageKind.SERVERS_UNREACHABLE) {
                    activeOutage = null
                    logRecovery(previousOutage, "risposta feed ripristinata ($nonDepotCount mezzi)")
                }
                lastEvaluationNote = "Flotta ridotta ($nonDepotCount mezzi su $distinctLines linee): soglia minima non raggiunta"
                return activeOutage
            }

            val nowSec = nowMillis / 1000L
            val anySourceActivelyMoving = sourceReports.values.any { report ->
                report.status == SourceMotionStatus.MOVING && report.movingCount >= MIN_HEALTHY_MOVING_BUSES
            }

            // 1. Check mass citywide stale timestamps (>= 85% of >= 15 non-depot buses older than 8 min)
            val agesSec = nonDepot.mapNotNull { bus ->
                bus.timestampSeconds.takeIf { it > 0L }?.let { (nowSec - it).coerceAtLeast(0L) }
            }
            val withTsCount = agesSec.size
            val staleCount = agesSec.count { it >= STALE_VEHICLE_AGE_SEC }
            val staleRatio = if (withTsCount > 0) staleCount.toDouble() / withTsCount.toDouble() else 0.0
            val feedAgeSec = if (feedTimestampSeconds > 0L) (nowSec - feedTimestampSeconds).coerceAtLeast(0L) else 0L

            val isStaleNow = withTsCount >= MIN_STALE_VEHICLES &&
                distinctLines >= MIN_STALE_LINES &&
                !anySourceActivelyMoving &&
                (staleRatio >= MIN_STALE_RATIO || (feedAgeSec >= STALE_VEHICLE_AGE_SEC && staleRatio >= 0.80))

            var detectedStaleReport: GttDataOutageReport? = null
            if (isStaleNow) {
                if (consecutiveStalePolls == 0 || firstStalePollMillis <= 0L) {
                    consecutiveStalePolls = 1
                    firstStalePollMillis = nowMillis
                } else {
                    consecutiveStalePolls++
                }
                val staleSpanSec = ((nowMillis - firstStalePollMillis) / 1000L).coerceAtLeast(0L)
                if (consecutiveStalePolls >= MIN_STALE_CONSECUTIVE_POLLS && staleSpanSec >= MIN_STALE_CONFIRM_SPAN_SEC) {
                    val sortedAges = agesSec.sorted()
                    val medianAgeMin = (sortedAges[sortedAges.size / 2] / 60L).coerceAtLeast(8L)
                    val pct = (staleRatio * 100.0).toInt().coerceIn(80, 100)
                    detectedStaleReport = GttDataOutageReport(
                        kind = GttOutageKind.CITYWIDE_STALE_FEED,
                        title = "Probabile rallentamento dati GTT",
                        detail = "Il $pct% dei mezzi ($staleCount/$withTsCount su $distinctLines linee) non riceve aggiornamenti GTT da circa $medianAgeMin min.",
                        detectedAtMillis = firstStalePollMillis,
                        isSimulated = false,
                    )
                }
            } else {
                consecutiveStalePolls = 0
                firstStalePollMillis = 0L
            }

            // 2. Check mass citywide frozen GPS coordinates across ALL responding citywide sources
            val citywideReports = sourceReports.values.filter { it.observedNonDepotCount >= MIN_SOURCE_CITYWIDE_VEHICLES }
            val totalObservedInReports = sourceReports.values.sumOf { it.observedNonDepotCount }
            val totalMovingInReports = sourceReports.values.sumOf { it.movingCount }
            val combinedMovingRatio = if (totalObservedInReports > 0) {
                totalMovingInReports.toDouble() / totalObservedInReports.toDouble()
            } else {
                1.0
            }
            val allCitywideFrozen = citywideReports.isNotEmpty() &&
                citywideReports.all { it.fleetFrozen && it.frozenDurationSeconds >= MIN_FROZEN_DURATION_SEC }

            val isFrozenNow = nonDepotCount >= MIN_FROZEN_VEHICLES &&
                distinctLines >= MIN_FROZEN_LINES &&
                !anySourceActivelyMoving &&
                allCitywideFrozen &&
                combinedMovingRatio < MAX_FROZEN_MOVING_RATIO

            var detectedFrozenReport: GttDataOutageReport? = null
            if (isFrozenNow) {
                if (consecutiveFrozenPolls == 0 || firstFrozenPollMillis <= 0L) {
                    consecutiveFrozenPolls = 1
                    firstFrozenPollMillis = nowMillis
                } else {
                    consecutiveFrozenPolls++
                }
                if (consecutiveFrozenPolls >= MIN_FROZEN_CONSECUTIVE_POLLS) {
                    val maxFrozenMin = (citywideReports.maxOf { it.frozenDurationSeconds } / 60L).coerceAtLeast(3L)
                    val srcCount = citywideReports.size
                    val srcPhrase = if (srcCount > 1) "su tutte le $srcCount fonti GTT" else "sul feed GTT"
                    detectedFrozenReport = GttDataOutageReport(
                        kind = GttOutageKind.CITYWIDE_FROZEN_FLEET,
                        title = "Probabile blocco GPS sui server GTT",
                        detail = "$nonDepotCount mezzi su $distinctLines linee risultano completamente fermi da $maxFrozenMin min $srcPhrase.",
                        detectedAtMillis = firstFrozenPollMillis,
                        isSimulated = false,
                    )
                }
            } else {
                consecutiveFrozenPolls = 0
                firstFrozenPollMillis = 0L
            }

            val nextOutage = detectedStaleReport ?: detectedFrozenReport
            activeOutage = nextOutage

            if (nextOutage != null) {
                lastEvaluationNote = "CONFERMATO (${nextOutage.kind.id}): nonDepot=$nonDepotCount linee=$distinctLines stale=${(staleRatio * 100).toInt()}% moving=$totalMovingInReports/$totalObservedInReports"
                if (previousOutage?.kind != nextOutage.kind) {
                    ToBusDiagnosticsLog.record(
                        category = DiagCategory.OUTAGE,
                        message = "Disservizio confermato: ${nextOutage.title}",
                        detail = nextOutage.detail,
                        severity = DiagSeverity.ERROR,
                    )
                }
            } else {
                if (previousOutage != null) {
                    logRecovery(
                        previousOutage,
                        "flotta regolare ($nonDepotCount mezzi, $distinctLines linee, stale=${(staleRatio * 100).toInt()}%, moving=$totalMovingInReports)",
                    )
                }
                val stalePct = (staleRatio * 100.0).toInt()
                lastEvaluationNote = when {
                    isStaleNow -> "Dati datati ($stalePct% >8m, poll #$consecutiveStalePolls): attendo conferma"
                    isFrozenNow -> "GPS fermi ($nonDepotCount mezzi, poll #$consecutiveFrozenPolls): attendo conferma"
                    else -> "OK · $nonDepotCount mezzi su $distinctLines linee · stale $stalePct% · in moto $totalMovingInReports · modo ${dataSourceMode.shortLabelIt}"
                }
            }
            return nextOutage
        }
    }

    private fun logRecovery(previous: GttDataOutageReport, detail: String) {
        ToBusDiagnosticsLog.record(
            category = DiagCategory.OUTAGE,
            message = "Disservizio GTT rientrato (${previous.kind.labelIt})",
            detail = detail,
            severity = DiagSeverity.OK,
        )
    }

    private fun buildSimulatedReport(nowMillis: Long): GttDataOutageReport = GttDataOutageReport(
        kind = GttOutageKind.CITYWIDE_FROZEN_FLEET,
        title = "Probabile blocco GPS sui server GTT",
        detail = "Test debug: 94% dei mezzi su 38 linee non riceve aggiornamenti reali da 6 min (simulazione manuale).",
        detectedAtMillis = nowMillis,
        isSimulated = true,
    )

    companion object {
        private const val NEUTRAL_PROBE_CACHE_MS = 45_000L
        private const val MIN_TOTAL_FAILURE_STREAK = 2
        private const val MIN_TOTAL_FAILURE_DURATION_SEC = 18L

        private const val MIN_STALE_VEHICLES = 15
        private const val MIN_STALE_LINES = 5
        private const val STALE_VEHICLE_AGE_SEC = 480L // 8 minutes
        private const val MIN_STALE_RATIO = 0.85
        private const val MIN_STALE_CONSECUTIVE_POLLS = 2
        private const val MIN_STALE_CONFIRM_SPAN_SEC = 40L

        private const val MIN_FROZEN_VEHICLES = 20
        private const val MIN_FROZEN_LINES = 6
        private const val MIN_SOURCE_CITYWIDE_VEHICLES = 15
        private const val MIN_FROZEN_DURATION_SEC = 180L // 3 full minutes
        private const val MAX_FROZEN_MOVING_RATIO = 0.03
        private const val MIN_FROZEN_CONSECUTIVE_POLLS = 2
        private const val MIN_HEALTHY_MOVING_BUSES = 3

        private val NEUTRAL_PROBE_URLS = listOf(
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://clients3.google.com/generate_204",
        )

        fun checkDeviceConnectivity(context: Context): DeviceConnectivityInfo {
            val cm = runCatching {
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            }.getOrNull() ?: return DeviceConnectivityInfo(
                isConnected = false,
                isValidated = false,
                isCaptivePortal = false,
                transportLabel = "N/D",
            )
            val active = runCatching { cm.activeNetwork }.getOrNull()
                ?: return DeviceConnectivityInfo(
                    isConnected = false,
                    isValidated = false,
                    isCaptivePortal = false,
                    transportLabel = "Offline",
                )
            val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull()
                ?: return DeviceConnectivityInfo(
                    isConnected = false,
                    isValidated = false,
                    isCaptivePortal = false,
                    transportLabel = "Offline",
                )
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val isCaptive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Rete mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Altro"
            }
            return DeviceConnectivityInfo(
                isConnected = hasInternet,
                isValidated = isValidated,
                isCaptivePortal = isCaptive,
                transportLabel = transport,
            )
        }

        private fun formatElapsedIt(seconds: Long): String {
            return if (seconds < 90L) {
                "${seconds}s"
            } else {
                val mins = (seconds / 60L).coerceAtLeast(1L)
                "$mins min"
            }
        }
    }
}
