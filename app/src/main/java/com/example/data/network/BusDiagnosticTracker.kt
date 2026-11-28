package com.example.data.network

import com.example.data.model.BusVehicle
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.RoutePattern
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class PipelineBusDecision(
    val vehicleId: String,
    val lineCode: String,
    val stage: String,
    val action: String,
    val reason: String,
    val detail: String,
)

data class FocusedBusDiagnosticTrace(
    val vehicleId: String,
    val lineCode: String,
    val primarySource: String,
    val contributingSources: Set<String>,
    val rawFeedSummary: String,
    val latitude: Double,
    val longitude: Double,
    val gpsAgeSeconds: Long?,
    val stationarySeconds: Long?,
    val lastMovedAgoSeconds: Long?,
    val speedKmh: Float?,
    val bearing: Float?,
    val distRouteMeters: Int?,
    val distTerminusMeters: Int?,
    val terminusName: String?,
    val depotName: String?,
    val depotVerifiedPolygon: Boolean,
    val isHoldover: Boolean,
    val isMqttRetained: Boolean,
    val isReliableLive: Boolean,
    val showBlockedWarningOnMap: Boolean,
    val isAtTerminus: Boolean,
    val isOffRoute: Boolean,
    val verdictCode: String,
    val dropReason: String? = null,
    val mergeNote: String? = null,
) {
    val isKeptByPipeline: Boolean
        get() = dropReason == null && !verdictCode.startsWith("DROPPED") &&
            !verdictCode.startsWith("STAGED") && !verdictCode.startsWith("QUARANTINED")

    fun isHiddenByMapFilter(hideInactiveOnMap: Boolean): Boolean =
        hideInactiveOnMap && (depotName != null || showBlockedWarningOnMap)

    fun formatForensicLine(hideInactiveOnMap: Boolean): String {
        val uiTag = when {
            !isKeptByPipeline -> "UI=EXCL"
            isHiddenByMapFilter(hideInactiveOnMap) -> {
                val why = if (depotName != null) "hideInactive:depot" else "hideInactive:blockedWarn"
                "UI=HIDDEN($why)"
            }
            else -> "UI=SHOW"
        }
        val sourcesStr = contributingSources.ifEmpty { setOf(primarySource) }
            .joinToString("+") { compactSourceTag(it) }
        val flags = buildList {
            if (isHoldover) add("HOLDOVER")
            if (isMqttRetained) add("RETAINED")
            if (showBlockedWarningOnMap) add("WARN(!)")
            if (isAtTerminus) add("TERM")
            if (isOffRoute) add("OFFROUTE")
            if (depotName != null) add("DEPOT(${depotName.removePrefix("Deposito ").trim()}${if (depotVerifiedPolygon) ":OSM" else ":seed"})")
        }.joinToString(",")
        val locParts = buildList {
            distRouteMeters?.let { add("dRoute=${it}m") }
            distTerminusMeters?.let { d ->
                val tName = terminusName?.take(16)?.replace(' ', '_')
                add(if (tName != null) "dTerm=${d}m($tName)" else "dTerm=${d}m")
            }
        }.joinToString(" ")
        val spdStr = speedKmh?.let { "${it.roundToInt()}kmh" } ?: "-"
        val brgStr = bearing?.let { "${it.roundToInt()}°" } ?: "-"
        return buildString {
            append("#$vehicleId $verdictCode $uiTag")
            append(" | src=$sourcesStr raw=[$rawFeedSummary]")
            append(" age=${ToBusDiagnosticsLog.formatDurationCompact(gpsAgeSeconds)}")
            append(" stat=${ToBusDiagnosticsLog.formatDurationCompact(stationarySeconds)}")
            append(" movAgo=${ToBusDiagnosticsLog.formatDurationCompact(lastMovedAgoSeconds)}")
            append(" spd=$spdStr brg=$brgStr")
            append(" pos=${ToBusDiagnosticsLog.formatCoordPair(latitude, longitude)}")
            if (locParts.isNotEmpty()) append(" $locParts")
            if (flags.isNotEmpty()) append(" flags=[$flags]")
            if (!dropReason.isNullOrBlank()) append(" drop=$dropReason")
            if (!mergeNote.isNullOrBlank()) append(" note=$mergeNote")
        }
    }
}

data class DroppedBusDiagnosticTrace(
    val timestampMillis: Long,
    val vehicleId: String,
    val lineCode: String,
    val stage: String,
    val reason: String,
    val detail: String,
) {
    fun formatLine(timeFmt: SimpleDateFormat): String {
        val stamp = timeFmt.format(Date(timestampMillis))
        return "[$stamp] #$vehicleId L.$lineCode [$stage] $reason | $detail"
    }
}

/**
 * In-memory, transition-driven forensic diagnostic tracker for the active focused line.
 *
 * Records EVERY per-vehicle decision (appear, merge conflict, retained staging/promotion,
 * kinematic hold/quarantine, grace holdover, depot/terminus/off-route/warning transitions,
 * phantom/dead-ghost filter drops, and feed disappearances) into [ToBusDiagnosticsLog] strictly
 * on state changes so user bug reports pinpoint the exact mechanism without flooding the buffer.
 */
internal class BusDiagnosticTracker {

    private data class BusTransitionState(
        val verdictCode: String,
        val dropReason: String?,
        val gpsSourceSignature: String,
        val showBlockedWarning: Boolean,
        val depotName: String?,
        val isAtTerminus: Boolean,
        val isOffRoute: Boolean,
        val isHoldover: Boolean,
        val lastCoords: String,
        val lastGpsAgeSec: Long?,
        val lastStatSec: Long?,
        val lastRawSummary: String,
        val lastSeenAtMillis: Long,
    )

    private val lock = Any()
    private var activeLine: String? = null
    private val prevStates = LinkedHashMap<String, BusTransitionState>()
    private var latestSnapshot: List<FocusedBusDiagnosticTrace> = emptyList()
    private val recentDrops = ArrayDeque<DroppedBusDiagnosticTrace>(MAX_RECENT_DROPS)
    private val lastDropDedup = HashMap<String, Long>()
    private val lastConflictDedup = HashMap<String, Long>()
    private var lastUiHiddenSignature: String = ""

    fun reset() {
        synchronized(lock) {
            activeLine = null
            prevStates.clear()
            latestSnapshot = emptyList()
            recentDrops.clear()
            lastDropDedup.clear()
            lastConflictDedup.clear()
            lastUiHiddenSignature = ""
        }
    }

    fun onLineSwitched(newLine: String?) {
        val norm = newLine?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "?" }
        synchronized(lock) {
            if (norm == activeLine) return
            val prev = activeLine
            activeLine = norm
            prevStates.clear()
            latestSnapshot = emptyList()
            lastConflictDedup.clear()
            lastUiHiddenSignature = ""
            if (norm != null) {
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.VEHICLE,
                    message = "Cambio linea monitorata: ${prev ?: "-"} → L.$norm",
                    detail = "reset stato transizioni mezzi (cronologia ultimi scarti preservata)",
                    severity = DiagSeverity.INFO,
                )
            }
        }
    }

    fun getLatestFocusedBusTraces(): List<FocusedBusDiagnosticTrace> = synchronized(lock) {
        latestSnapshot
    }

    fun getRecentDroppedBuses(limit: Int = MAX_RECENT_DROPS): List<DroppedBusDiagnosticTrace> = synchronized(lock) {
        recentDrops.asReversed().take(limit)
    }

    /**
     * Records when the UI-layer `hideInactiveOnMap` toggle hides/unhides buses on the active line.
     */
    fun recordMapUiHideState(
        lineCode: String,
        hideInactiveOnMap: Boolean,
        hiddenEntries: List<Pair<String, String>>,
    ) {
        val norm = lineCode.trim().uppercase()
        if (norm.isEmpty()) return
        val sig = if (!hideInactiveOnMap || hiddenEntries.isEmpty()) {
            "$norm|none"
        } else {
            "$norm|" + hiddenEntries.sortedBy { it.first }.joinToString(",") { "${it.first}:${it.second}" }
        }
        synchronized(lock) {
            if (sig == lastUiHiddenSignature) return
            lastUiHiddenSignature = sig
            if (hideInactiveOnMap && hiddenEntries.isNotEmpty()) {
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.VEHICLE,
                    message = "UI mappa nasconde ${hiddenEntries.size} mezzi su L.$norm (hideInactiveOnMap=true)",
                    detail = hiddenEntries.joinToString(", ") { "#${it.first}(${it.second})" },
                    severity = DiagSeverity.INFO,
                )
            } else if (!hideInactiveOnMap) {
                // Only log if previously hiding something
                if (hiddenEntries.isNotEmpty()) {
                    ToBusDiagnosticsLog.record(
                        category = DiagCategory.VEHICLE,
                        message = "UI mappa mostra tutti i mezzi su L.$norm (hideInactiveOnMap=false)",
                        severity = DiagSeverity.INFO,
                    )
                }
            }
        }
    }

    fun evaluateCycle(
        focusLine: String?,
        trigger: String,
        rawGtt: List<BusVehicle>,
        rawBusOne: List<BusVehicle>,
        rawMatoLive: List<BusVehicle>,
        rawMatoGql: List<BusVehicle>,
        rawMatoRetained: List<BusVehicle>,
        mergedAndGraceVehicles: List<BusVehicle>,
        routePatterns: List<RoutePattern>,
        healthyOfficialGtfsOnline: Boolean,
        pipelineDecisions: List<PipelineBusDecision>,
        nowMillis: Long,
    ) {
        val normLine = focusLine?.trim()?.uppercase()?.takeIf { it.isNotEmpty() && it != "?" } ?: return

        fun matchesLine(v: BusVehicle): Boolean =
            v.lineCode.equals(normLine, ignoreCase = true) || v.rawRouteId.equals(normLine, ignoreCase = true)

        fun canonId(v: BusVehicle): String =
            GtfsRtProtobufParser.canonicalFleetId(v.vehicleId).ifBlank { v.vehicleId.trim() }

        val gttMap = LinkedHashMap<String, BusVehicle>()
        for (v in rawGtt) if (matchesLine(v)) gttMap[canonId(v)] = v
        val busOneMap = LinkedHashMap<String, BusVehicle>()
        for (v in rawBusOne) if (matchesLine(v)) busOneMap[canonId(v)] = v
        val matoLiveMap = LinkedHashMap<String, BusVehicle>()
        for (v in rawMatoLive) if (matchesLine(v)) matoLiveMap[canonId(v)] = v
        val matoGqlMap = LinkedHashMap<String, BusVehicle>()
        for (v in rawMatoGql) if (matchesLine(v)) matoGqlMap[canonId(v)] = v
        val matoRetMap = LinkedHashMap<String, BusVehicle>()
        for (v in rawMatoRetained) if (matchesLine(v)) matoRetMap[canonId(v)] = v

        val mergedLineMap = LinkedHashMap<String, BusVehicle>()
        for (v in mergedAndGraceVehicles) {
            if (matchesLine(v)) mergedLineMap[canonId(v)] = v
        }

        val decisionsByVid = LinkedHashMap<String, MutableList<PipelineBusDecision>>()
        for (d in pipelineDecisions) {
            if (d.lineCode.equals(normLine, ignoreCase = true) ||
                d.vehicleId in gttMap || d.vehicleId in busOneMap ||
                d.vehicleId in matoLiveMap || d.vehicleId in matoGqlMap ||
                d.vehicleId in matoRetMap || d.vehicleId in mergedLineMap
            ) {
                decisionsByVid.getOrPut(d.vehicleId) { mutableListOf() }.add(d)
            }
        }

        val allIds = LinkedHashSet<String>()
        allIds.addAll(mergedLineMap.keys)
        allIds.addAll(gttMap.keys)
        allIds.addAll(busOneMap.keys)
        allIds.addAll(matoLiveMap.keys)
        allIds.addAll(matoGqlMap.keys)
        allIds.addAll(matoRetMap.keys)
        allIds.addAll(decisionsByVid.keys)

        val hasPatterns = routePatterns.isNotEmpty()
        val traces = ArrayList<FocusedBusDiagnosticTrace>(allIds.size)
        val nextStates = LinkedHashMap<String, BusTransitionState>(allIds.size)

        synchronized(lock) {
            if (activeLine != normLine) {
                activeLine = normLine
                prevStates.clear()
                lastConflictDedup.clear()
            }

            for (vid in allIds) {
                val gttV = gttMap[vid]
                val busOneV = busOneMap[vid]
                val matoLiveV = matoLiveMap[vid]
                val matoGqlV = matoGqlMap[vid]
                val matoRetV = matoRetMap[vid]
                val mergedV = mergedLineMap[vid]
                val vidDecisions = decisionsByVid[vid].orEmpty()

                val rawSummary = buildRawFeedSummary(gttV, busOneV, matoLiveV, matoGqlV, matoRetV, nowMillis)
                val noteSummary = vidDecisions
                    .filter { it.action in setOf("MERGE_CONFLICT", "HOLD", "PROMOTED", "GRACE_BRIDGE") }
                    .joinToString("; ") { "${it.action}:${it.reason}" }
                    .takeIf { it.isNotBlank() }

                // Log notable merge/kinematic/grace decisions with per-bus cooldown deduplication
                for (dec in vidDecisions) {
                    when (dec.action) {
                        "MERGE_CONFLICT" -> {
                            val key = "$normLine|$vid|MERGE|${dec.reason}"
                            val lastAt = lastConflictDedup[key] ?: 0L
                            if (nowMillis - lastAt >= CONFLICT_LOG_COOLDOWN_MS) {
                                lastConflictDedup[key] = nowMillis
                                ToBusDiagnosticsLog.record(
                                    category = DiagCategory.VEHICLE,
                                    message = "#$vid L.$normLine MERGE ${dec.reason}",
                                    detail = "${dec.detail} [$trigger]",
                                    severity = DiagSeverity.INFO,
                                    timestampMillis = nowMillis,
                                )
                            }
                        }
                        "HOLD", "PROMOTED", "GRACE_BRIDGE", "QUARANTINE_LIFT" -> {
                            val key = "$normLine|$vid|${dec.action}|${dec.reason}"
                            val lastAt = lastConflictDedup[key] ?: 0L
                            if (nowMillis - lastAt >= CONFLICT_LOG_COOLDOWN_MS) {
                                lastConflictDedup[key] = nowMillis
                                ToBusDiagnosticsLog.record(
                                    category = DiagCategory.VEHICLE,
                                    message = "#$vid L.$normLine ${dec.action}: ${dec.reason}",
                                    detail = "${dec.detail} [$trigger]",
                                    severity = if (dec.action == "HOLD") DiagSeverity.WARN else DiagSeverity.INFO,
                                    timestampMillis = nowMillis,
                                )
                            }
                        }
                        "GRACE_EXPIRED" -> {
                            ToBusDiagnosticsLog.record(
                                category = DiagCategory.VEHICLE,
                                message = "#$vid L.$normLine ${dec.action}: ${dec.reason}",
                                detail = "${dec.detail} [$trigger]",
                                severity = DiagSeverity.WARN,
                                timestampMillis = nowMillis,
                            )
                            recordDropLocked(vid, normLine, dec.stage, dec.reason, dec.detail, nowMillis)
                        }
                    }
                }
                if (lastConflictDedup.size > 240) {
                    val cutoff = nowMillis - CONFLICT_LOG_COOLDOWN_MS * 2L
                    lastConflictDedup.entries.removeIf { it.value < cutoff }
                }

                if (mergedV != null) {
                    val spatial = GttFleetAnalyzer.assessSpatial(mergedV, routePatterns)
                    val health = GttFleetAnalyzer.assessWithSpatial(mergedV, spatial, nowMillis)
                    val ghostDropReason = GttFleetAnalyzer.explainFilterVerdictWithSpatial(
                        bus = mergedV,
                        spatial = spatial,
                        hasKnownRoutePatterns = hasPatterns,
                        nowMillis = nowMillis,
                        healthyOfficialGtfsOnline = healthyOfficialGtfsOnline,
                    )
                    val verdictCode = when {
                        ghostDropReason != null -> "DROPPED_GHOST"
                        mergedV.isHoldover -> "KEPT_HOLDOVER"
                        health.isInDepot -> "KEPT_DEPOT"
                        health.showBlockedWarningOnMap -> "KEPT_WARN"
                        health.isOffRoute -> "KEPT_OFFROUTE"
                        health.isAtTerminus -> "KEPT_TERMINUS"
                        else -> "KEPT_LIVE"
                    }
                    val lastMovedAgoSec = mergedV.lastMovedAtMillis?.let { ((nowMillis - it) / 1000L).coerceAtLeast(0L) }
                    val trace = FocusedBusDiagnosticTrace(
                        vehicleId = vid,
                        lineCode = normLine,
                        primarySource = mergedV.sourceName,
                        contributingSources = mergedV.contributingSources,
                        rawFeedSummary = rawSummary,
                        latitude = mergedV.latitude,
                        longitude = mergedV.longitude,
                        gpsAgeSeconds = health.gpsAgeSeconds,
                        stationarySeconds = health.stationarySeconds,
                        lastMovedAgoSeconds = lastMovedAgoSec,
                        speedKmh = mergedV.speedKmh,
                        bearing = mergedV.bearing,
                        distRouteMeters = spatial.distToRouteMeters?.roundToInt(),
                        distTerminusMeters = spatial.distanceToTerminusMeters.takeIf { it.isFinite() }?.roundToInt(),
                        terminusName = spatial.terminusStopName,
                        depotName = health.depotName,
                        depotVerifiedPolygon = spatial.depotHasVerifiedPolygon,
                        isHoldover = mergedV.isHoldover,
                        isMqttRetained = mergedV.isMqttRetained,
                        isReliableLive = health.isReliableLive,
                        showBlockedWarningOnMap = health.showBlockedWarningOnMap,
                        isAtTerminus = health.isAtTerminus,
                        isOffRoute = health.isOffRoute,
                        verdictCode = verdictCode,
                        dropReason = ghostDropReason,
                        mergeNote = noteSummary,
                    )
                    traces.add(trace)

                    val gpsSrcSig = mergedV.gpsSourceNames().sorted().joinToString("+") { compactSourceTag(it) }
                    val nextState = BusTransitionState(
                        verdictCode = verdictCode,
                        dropReason = ghostDropReason,
                        gpsSourceSignature = gpsSrcSig,
                        showBlockedWarning = health.showBlockedWarningOnMap,
                        depotName = health.depotName,
                        isAtTerminus = health.isAtTerminus,
                        isOffRoute = health.isOffRoute,
                        isHoldover = mergedV.isHoldover,
                        lastCoords = ToBusDiagnosticsLog.formatCoordPair(mergedV.latitude, mergedV.longitude),
                        lastGpsAgeSec = health.gpsAgeSeconds,
                        lastStatSec = health.stationarySeconds,
                        lastRawSummary = rawSummary,
                        lastSeenAtMillis = nowMillis,
                    )
                    nextStates[vid] = nextState
                    emitTransitionsLocked(
                        vid = vid,
                        lineCode = normLine,
                        trigger = trigger,
                        prev = prevStates[vid],
                        next = nextState,
                        trace = trace,
                        healthyOfficialGtfsOnline = healthyOfficialGtfsOnline,
                        hasPatterns = hasPatterns,
                        nowMillis = nowMillis,
                    )
                } else {
                    // Vehicle existed in raw feeds or pipeline decisions, but did NOT survive merge/kinematic/filter!
                    val refBus = gttV ?: busOneV ?: matoLiveV ?: matoGqlV ?: matoRetV
                    val blockingDecision = vidDecisions.lastOrNull {
                        it.action in setOf("DROPPED", "STAGED", "QUARANTINE")
                    }
                    val stage = blockingDecision?.stage ?: if (matoRetV != null) "RETAINED_STAGE" else "PIPELINE"
                    val reason = blockingDecision?.reason ?: if (matoRetV != null) "retained_no_official_match" else "excluded_in_merge"
                    val verdictCode = when {
                        blockingDecision?.action == "QUARANTINE" -> "QUARANTINED"
                        stage == "RETAINED_STAGE" -> "STAGED_RETAINED"
                        else -> "DROPPED_$stage"
                    }
                    val gpsAge = refBus?.gpsAgeSeconds(nowMillis)
                    val statSec = refBus?.stationarySinceMillis?.let { ((nowMillis - it) / 1000L).coerceAtLeast(0L) }
                    val trace = FocusedBusDiagnosticTrace(
                        vehicleId = vid,
                        lineCode = normLine,
                        primarySource = refBus?.sourceName ?: "?",
                        contributingSources = refBus?.contributingSources ?: emptySet(),
                        rawFeedSummary = rawSummary,
                        latitude = refBus?.latitude ?: 0.0,
                        longitude = refBus?.longitude ?: 0.0,
                        gpsAgeSeconds = gpsAge,
                        stationarySeconds = statSec,
                        lastMovedAgoSeconds = refBus?.lastMovedAtMillis?.let { ((nowMillis - it) / 1000L).coerceAtLeast(0L) },
                        speedKmh = refBus?.speedKmh,
                        bearing = refBus?.bearing,
                        distRouteMeters = null,
                        distTerminusMeters = null,
                        terminusName = null,
                        depotName = refBus?.let { GttFleetAnalyzer.findDepot(it.latitude, it.longitude)?.name },
                        depotVerifiedPolygon = refBus?.let { GttFleetAnalyzer.findDepot(it.latitude, it.longitude)?.hasVerifiedPolygon == true } ?: false,
                        isHoldover = false,
                        isMqttRetained = refBus?.isMqttRetained ?: false,
                        isReliableLive = false,
                        showBlockedWarningOnMap = false,
                        isAtTerminus = false,
                        isOffRoute = false,
                        verdictCode = verdictCode,
                        dropReason = reason,
                        mergeNote = blockingDecision?.detail,
                    )
                    traces.add(trace)

                    val nextState = BusTransitionState(
                        verdictCode = verdictCode,
                        dropReason = reason,
                        gpsSourceSignature = compactSourceTag(refBus?.sourceName ?: "?"),
                        showBlockedWarning = false,
                        depotName = trace.depotName,
                        isAtTerminus = false,
                        isOffRoute = false,
                        isHoldover = false,
                        lastCoords = refBus?.let { ToBusDiagnosticsLog.formatCoordPair(it.latitude, it.longitude) } ?: "-",
                        lastGpsAgeSec = gpsAge,
                        lastStatSec = statSec,
                        lastRawSummary = rawSummary,
                        lastSeenAtMillis = nowMillis,
                    )
                    nextStates[vid] = nextState

                    val prev = prevStates[vid]
                    if (prev == null ||
                        prev.verdictCode != verdictCode ||
                        dropRuleKey(prev.dropReason) != dropRuleKey(reason)
                    ) {
                        val detail = buildString {
                            append("rule=$reason raw=[$rawSummary]")
                            append(" age=${ToBusDiagnosticsLog.formatDurationCompact(gpsAge)}")
                            append(" stat=${ToBusDiagnosticsLog.formatDurationCompact(statSec)}")
                            append(" officialOk=$healthyOfficialGtfsOnline")
                            if (blockingDecision != null && blockingDecision.detail.isNotBlank()) {
                                append(" · ${blockingDecision.detail}")
                            }
                            append(" [$trigger]")
                        }
                        val sev = if (verdictCode == "STAGED_RETAINED") DiagSeverity.INFO else DiagSeverity.WARN
                        ToBusDiagnosticsLog.record(
                            category = DiagCategory.VEHICLE,
                            message = "#$vid L.$normLine $verdictCode ($stage)",
                            detail = detail,
                            severity = sev,
                            timestampMillis = nowMillis,
                        )
                        recordDropLocked(vid, normLine, stage, reason, detail, nowMillis)
                    }
                }
            }

            // Detect previously kept vehicles that vanished completely from ALL raw feeds in this cycle
            for ((vid, prev) in prevStates) {
                if (vid in nextStates) continue
                if (!prev.verdictCode.startsWith("KEPT")) continue
                val detail = "lastVerdict=${prev.verdictCode} lastSrc=${prev.gpsSourceSignature} lastRaw=[${prev.lastRawSummary}] lastAge=${ToBusDiagnosticsLog.formatDurationCompact(prev.lastGpsAgeSec)} lastStat=${ToBusDiagnosticsLog.formatDurationCompact(prev.lastStatSec)} lastPos=${prev.lastCoords} officialOk=$healthyOfficialGtfsOnline [$trigger]"
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.VEHICLE,
                    message = "#$vid L.$normLine DISAPPEARED (assente da tutti i feed)",
                    detail = detail,
                    severity = DiagSeverity.WARN,
                    timestampMillis = nowMillis,
                )
                recordDropLocked(vid, normLine, "FEED_GONE", "absent_from_all_feeds", detail, nowMillis)
            }

            prevStates.clear()
            prevStates.putAll(nextStates)
            latestSnapshot = traces.sortedWith(
                compareByDescending<FocusedBusDiagnosticTrace> { it.isKeptByPipeline }
                    .thenBy { it.vehicleId },
            )
        }
    }

    private fun emitTransitionsLocked(
        vid: String,
        lineCode: String,
        trigger: String,
        prev: BusTransitionState?,
        next: BusTransitionState,
        trace: FocusedBusDiagnosticTrace,
        healthyOfficialGtfsOnline: Boolean,
        hasPatterns: Boolean,
        nowMillis: Long,
    ) {
        val metrics = buildString {
            append("src=${next.gpsSourceSignature} raw=[${trace.rawFeedSummary}]")
            append(" age=${ToBusDiagnosticsLog.formatDurationCompact(trace.gpsAgeSeconds)}")
            append(" stat=${ToBusDiagnosticsLog.formatDurationCompact(trace.stationarySeconds)}")
            append(" movAgo=${ToBusDiagnosticsLog.formatDurationCompact(trace.lastMovedAgoSeconds)}")
            trace.distRouteMeters?.let { append(" dRoute=${it}m") }
            trace.distTerminusMeters?.let { append(" dTerm=${it}m") }
            trace.depotName?.let { append(" depot=${it.removePrefix("Deposito ").trim()}(${if (trace.depotVerifiedPolygon) "OSM" else "seed"})") }
            append(" pos=${next.lastCoords} officialOk=$healthyOfficialGtfsOnline routeLoaded=$hasPatterns [$trigger]")
        }

        if (prev == null) {
            val action = if (trace.dropReason != null) "DROPPED_GHOST" else "APPEARED(${next.verdictCode})"
            val msg = if (trace.dropReason != null) {
                "#$vid L.$lineCode DROPPED_GHOST: ${trace.dropReason}"
            } else {
                "#$vid L.$lineCode $action"
            }
            ToBusDiagnosticsLog.record(
                category = DiagCategory.VEHICLE,
                message = msg,
                detail = metrics,
                severity = if (trace.dropReason != null || next.showBlockedWarning) DiagSeverity.WARN else DiagSeverity.OK,
                timestampMillis = nowMillis,
            )
            if (trace.dropReason != null) {
                recordDropLocked(vid, lineCode, "GHOST_FILTER", trace.dropReason, metrics, nowMillis)
            }
            return
        }

        // 1. Kept <-> Dropped by phantom/dead-ghost analyzer (compare rule prefix so dynamic
        //    counters like `stat=886s` -> `stat=889s` don't re-log the same dropped ghost every 2s)
        if (dropRuleKey(prev.dropReason) != dropRuleKey(next.dropReason)) {
            if (next.dropReason != null) {
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.VEHICLE,
                    message = "#$vid L.$lineCode DROPPED_GHOST: ${next.dropReason}",
                    detail = "prev=${prev.verdictCode} · $metrics",
                    severity = DiagSeverity.WARN,
                    timestampMillis = nowMillis,
                )
                recordDropLocked(vid, lineCode, "GHOST_FILTER", next.dropReason, metrics, nowMillis)
            } else {
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.VEHICLE,
                    message = "#$vid L.$lineCode RESTORED(${next.verdictCode})",
                    detail = "wasDropped=${prev.dropReason} · $metrics",
                    severity = DiagSeverity.OK,
                    timestampMillis = nowMillis,
                )
            }
            return
        }

        // 2. Blocked warning (!) badge ON / OFF
        if (prev.showBlockedWarning != next.showBlockedWarning) {
            ToBusDiagnosticsLog.record(
                category = DiagCategory.VEHICLE,
                message = "#$vid L.$lineCode ${if (next.showBlockedWarning) "WARN_ON (!)" else "WARN_OFF"}",
                detail = metrics,
                severity = if (next.showBlockedWarning) DiagSeverity.WARN else DiagSeverity.OK,
                timestampMillis = nowMillis,
            )
        }

        // 3. Depot enter / exit
        if (prev.depotName != next.depotName) {
            val tag = if (next.depotName != null) "DEPOT_ENTER(${next.depotName})" else "DEPOT_EXIT(${prev.depotName})"
            ToBusDiagnosticsLog.record(
                category = DiagCategory.VEHICLE,
                message = "#$vid L.$lineCode $tag",
                detail = metrics,
                severity = DiagSeverity.INFO,
                timestampMillis = nowMillis,
            )
        }

        // 4. Terminus enter / exit
        if (prev.isAtTerminus != next.isAtTerminus) {
            val tag = if (next.isAtTerminus) {
                "TERMINUS_ENTER(${trace.terminusName ?: "capolinea"})"
            } else {
                "TERMINUS_EXIT"
            }
            ToBusDiagnosticsLog.record(
                category = DiagCategory.VEHICLE,
                message = "#$vid L.$lineCode $tag",
                detail = metrics,
                severity = DiagSeverity.INFO,
                timestampMillis = nowMillis,
            )
        }

        // 5. Off-route enter / exit
        if (prev.isOffRoute != next.isOffRoute) {
            ToBusDiagnosticsLog.record(
                category = DiagCategory.VEHICLE,
                message = "#$vid L.$lineCode ${if (next.isOffRoute) "OFFROUTE_ENTER" else "OFFROUTE_EXIT"}",
                detail = metrics,
                severity = if (next.isOffRoute) DiagSeverity.WARN else DiagSeverity.INFO,
                timestampMillis = nowMillis,
            )
        }

        // 6. Holdover recovery
        if (prev.isHoldover && !next.isHoldover) {
            ToBusDiagnosticsLog.record(
                category = DiagCategory.VEHICLE,
                message = "#$vid L.$lineCode HOLDOVER_RECOVERED",
                detail = metrics,
                severity = DiagSeverity.OK,
                timestampMillis = nowMillis,
            )
        }

        // 7. GPS source set shifted (e.g. GTT+BusOne -> MaTO-only or vice versa)
        if (prev.gpsSourceSignature != next.gpsSourceSignature) {
            ToBusDiagnosticsLog.record(
                category = DiagCategory.VEHICLE,
                message = "#$vid L.$lineCode SRC_SHIFT ${prev.gpsSourceSignature} → ${next.gpsSourceSignature}",
                detail = metrics,
                severity = DiagSeverity.INFO,
                timestampMillis = nowMillis,
            )
        }
    }

    private fun recordDropLocked(
        vehicleId: String,
        lineCode: String,
        stage: String,
        reason: String,
        detail: String,
        nowMillis: Long,
    ) {
        val dedupKey = "$lineCode|$vehicleId|$stage|${dropRuleKey(reason)}"
        val prevAt = lastDropDedup[dedupKey] ?: 0L
        if (nowMillis - prevAt < DROP_DEDUP_WINDOW_MS) return
        if (lastDropDedup.size > 200) {
            val cutoff = nowMillis - DROP_DEDUP_WINDOW_MS
            lastDropDedup.entries.removeIf { it.value < cutoff }
        }
        lastDropDedup[dedupKey] = nowMillis
        if (recentDrops.size >= MAX_RECENT_DROPS) {
            recentDrops.removeFirst()
        }
        recentDrops.addLast(
            DroppedBusDiagnosticTrace(
                timestampMillis = nowMillis,
                vehicleId = vehicleId,
                lineCode = lineCode,
                stage = stage,
                reason = reason,
                detail = detail.take(360),
            ),
        )
    }

    private fun dropRuleKey(reason: String?): String? =
        reason?.substringBefore('(')?.trim()

    private fun buildRawFeedSummary(
        gtt: BusVehicle?,
        busOne: BusVehicle?,
        matoLive: BusVehicle?,
        matoGql: BusVehicle?,
        matoRet: BusVehicle?,
        nowMillis: Long,
    ): String {
        val parts = ArrayList<String>(5)
        gtt?.let { parts.add("GTT(${ToBusDiagnosticsLog.formatDurationCompact(it.gpsAgeSeconds(nowMillis))})") }
        busOne?.let { parts.add("BusOne(${ToBusDiagnosticsLog.formatDurationCompact(it.gpsAgeSeconds(nowMillis))})") }
        matoLive?.let {
            val silentSec = ((nowMillis - it.fetchedAtMillis) / 1000L).coerceAtLeast(0L)
            parts.add("MaTOmq(age=${ToBusDiagnosticsLog.formatDurationCompact(it.gpsAgeSeconds(nowMillis))},sil=${silentSec}s)")
        }
        matoGql?.let { parts.add("MaTOgql(${ToBusDiagnosticsLog.formatDurationCompact(it.gpsAgeSeconds(nowMillis))})") }
        matoRet?.let {
            val stagedSec = ((nowMillis - it.fetchedAtMillis) / 1000L).coerceAtLeast(0L)
            parts.add("MaTOret(staged=${stagedSec}s)")
        }
        return if (parts.isEmpty()) "none" else parts.joinToString(",")
    }

    companion object {
        private const val MAX_RECENT_DROPS = 30
        private const val DROP_DEDUP_WINDOW_MS = 45_000L
        private const val CONFLICT_LOG_COOLDOWN_MS = 60_000L
    }
}

internal fun compactSourceTag(name: String): String = when {
    name.equals("GTT Ufficiale", ignoreCase = true) -> "GTT"
    name.equals("BusOne Proxy", ignoreCase = true) -> "BusOne"
    name.equals("MaTO Live", ignoreCase = true) -> "MaTO"
    name.equals("TripUpdates", ignoreCase = true) -> "TU"
    else -> name
}
