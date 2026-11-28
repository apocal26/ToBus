package com.example.data.network

import android.util.Log
import com.example.data.model.BusVehicle
import com.example.data.model.VehicleStopStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream

/**
 * Pure-Kotlin MQTT 3.1 / 3.1.1 over WebSocket client for 5T / Muoversi a Torino (MaTO) real-time
 * vehicle positions (`wss://mapi.5t.torino.it/scre`).
 *
 * Key resilience features:
 * - Atomic connection generation tracking (eliminates OkHttp onOpen/assignment race conditions).
 * - Automatic WebSocket subprotocol & MQTT protocol version negotiation (`mqtt`, `mqttv3.1`,
 *   combined, and bare WS; MQTT 3.1.1 + MQTT 3.1 `MQIsdp`), remembering the working profile.
 * - ACL-safe line-scoped subscriptions first (exact `/12U`, `12U`, `/12U/#`, `/gtt/12U/#`,
 *   `/+/12U/#`, multi-depth `/gtfsrt/vp/...`), with automatic staged fallback (`/ALL`, `ALL`,
 *   `/+/#`, `+/#`, `/#`, `#`) if zero vehicles are parsed after connect.
 * - Automatic QoS 1 PUBACK acknowledgment and transparent GZIP payload decompression.
 * - Universal parser supporting 5T `MQTTPositionUpdate` JSON (including tripId-only without `veh`),
 *   nested 1-level & 2-level maps, Digitransit `{"VP":{...}}`, GeoJSON `FeatureCollection`,
 *   wrapped envelopes, compact arrays/CSV, and binary GTFS-RT protobuf.
 */
class MatoMqttLiveClient(
    private val baseClientProvider: () -> OkHttpClient,
) {
    private val wsClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        baseClientProvider().newBuilder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Long-lived streaming WebSocket
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vehiclesById = ConcurrentHashMap<String, BusVehicle>()
    private val retainedCandidatesById = ConcurrentHashMap<String, BusVehicle>()
    private val pendingTeleportById = ConcurrentHashMap<String, BusVehicle>()
    private val subscribedTopicsForConn = ConcurrentHashMap.newKeySet<String>()
    private val subscribedTopicsByLine = ConcurrentHashMap<String, MutableSet<String>>()
    private val packetIdGen = AtomicInteger(1)
    private val connectionGen = AtomicLong(0L)
    private val isActiveDesired = AtomicBoolean(false)
    private val isMqttConnected = AtomicBoolean(false)
    private val lastMessageReceivedAtMillis = AtomicLong(0L)
    private val lastParsedVehicleAtMillis = AtomicLong(0L)
    private val lastLiveParsedVehicleAtMillis = AtomicLong(0L)
    private val lastPingRespAtMillis = AtomicLong(0L)
    private val connectedSinceMillis = AtomicLong(0L)
    private val rawPublishCount = AtomicLong(0L)
    private val livePublishCount = AtomicLong(0L)
    private val retainedPublishCount = AtomicLong(0L)
    private val parsedVehicleCount = AtomicLong(0L)
    private val subAckAcceptedCount = AtomicInteger(0)
    private val subAckRejectedCount = AtomicInteger(0)

    @Volatile
    private var activeWebSocket: WebSocket? = null

    @Volatile
    private var focusedLine: String = ""

    @Volatile
    private var onFocusedLineUpdate: (() -> Unit)? = null

    @Volatile
    private var lastNotifyMillis: Long = 0L

    @Volatile
    private var lastErrorDetail: String? = null

    @Volatile
    private var lastSampleTopic: String? = null

    @Volatile
    private var lastPayloadPreview: String? = null

    @Volatile
    private var workingProfileIdx: Int = -1

    @Volatile
    private var rootWildcardDisallowed: Boolean = false

    @Volatile
    private var rootWildcardSentAtMillis: Long = 0L

    @Volatile
    private var hasPushedFocusedVehicle: Boolean = false

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var keepAliveJob: Job? = null
    private var silentFallbackJob: Job? = null

    fun isConnected(): Boolean = isMqttConnected.get()

    fun isActiveOrConnecting(): Boolean = isActiveDesired.get()

    fun lastError(): String? = lastErrorDetail

    fun lastReceivedMillis(): Long = lastMessageReceivedAtMillis.get()

    fun lastParsedVehicleMillis(): Long = lastParsedVehicleAtMillis.get()

    fun connectedSinceMillis(): Long = connectedSinceMillis.get()

    fun rawPublishCount(): Long = rawPublishCount.get()

    fun livePublishCount(): Long = livePublishCount.get()

    fun retainedPublishCount(): Long = retainedPublishCount.get()

    fun stagedRetainedCount(): Int = retainedCandidatesById.size

    fun parsedVehicleCount(): Long = parsedVehicleCount.get()

    fun subAckAcceptedCount(): Int = subAckAcceptedCount.get()

    fun subAckRejectedCount(): Int = subAckRejectedCount.get()

    fun subAckRejectedAll(): Boolean =
        subAckRejectedCount.get() > 0 && subAckAcceptedCount.get() == 0

    fun totalCachedVehicles(): Int = vehiclesById.size

    fun lastSampleTopic(): String? = lastSampleTopic

    fun lastPayloadPreview(): String? = lastPayloadPreview

    fun focusedLineCode(): String = focusedLine

    fun reconnectAttemptCount(): Int = reconnectAttempt

    fun activeProfileLabel(): String {
        val idx = if (workingProfileIdx in HANDSHAKE_PROFILES.indices) {
            workingProfileIdx
        } else {
            reconnectAttempt % HANDSHAKE_PROFILES.size
        }
        return HANDSHAKE_PROFILES[idx].label
    }

    fun forceReconnect(lineCode: String? = null) {
        val target = lineCode?.let { GtfsRtProtobufParser.normalizeGttLineCode(it) }
            ?.takeIf { it.isNotEmpty() && it != "?" }
        if (target != null) {
            focusedLine = target
        }
        isActiveDesired.set(true)
        reconnectAttempt = 0
        rootWildcardDisallowed = false
        ToBusDiagnosticsLog.record(
            category = DiagCategory.MQTT,
            message = "Riconnessione forzata stream MaTO MQTT",
            detail = "linea=${focusedLine.ifBlank { "-" }} profilo=${activeProfileLabel()}",
            severity = DiagSeverity.INFO,
        )
        scope.launch {
            openSocket()
        }
    }

    fun start(lineCode: String, onFocusedUpdate: () -> Unit) {
        val norm = GtfsRtProtobufParser.normalizeGttLineCode(lineCode)
        if (norm.isEmpty() || norm == "?") return
        val prevLine = focusedLine
        val lineChanged = norm != prevLine
        focusedLine = norm
        onFocusedLineUpdate = onFocusedUpdate
        if (lineChanged) hasPushedFocusedVehicle = false
        val wasActive = isActiveDesired.getAndSet(true)
        scope.launch {
            if (!wasActive || activeWebSocket == null) {
                reconnectAttempt = 0
                openSocket()
            } else if (isMqttConnected.get()) {
                val ws = activeWebSocket
                val gen = connectionGen.get()
                if (lineChanged && prevLine.isNotEmpty()) {
                    unsubscribeForLine(ws, prevLine)
                }
                subscribeForLine(ws, focusedLine, includeSecondaryGroups = false)
                if (ws != null && connectionGen.get() == gen) {
                    val hasLineCached = vehiclesById.values.any {
                        it.lineCode.equals(norm, ignoreCase = true) ||
                            it.rawRouteId.equals(norm, ignoreCase = true)
                    }
                    if (!hasLineCached) {
                        scheduleSilentTopicFallback(ws, gen, norm, onlyIfNoParsedSince = System.currentTimeMillis())
                    }
                }
            }
        }
    }

    fun setFocusedLine(lineCode: String) {
        val norm = GtfsRtProtobufParser.normalizeGttLineCode(lineCode)
        if (norm.isEmpty() || norm == "?" || norm == focusedLine) return
        val prevLine = focusedLine
        focusedLine = norm
        hasPushedFocusedVehicle = false
        if (isMqttConnected.get()) {
            val ws = activeWebSocket
            val gen = connectionGen.get()
            scope.launch {
                if (prevLine.isNotEmpty()) {
                    unsubscribeForLine(ws, prevLine)
                }
                subscribeForLine(ws, norm, includeSecondaryGroups = false)
                val hasLineCached = vehiclesById.values.any {
                    it.lineCode.equals(norm, ignoreCase = true) ||
                        it.rawRouteId.equals(norm, ignoreCase = true)
                }
                if (!hasLineCached && ws != null && isMqttConnected.get() && connectionGen.get() == gen) {
                    scheduleSilentTopicFallback(ws, gen, norm, onlyIfNoParsedSince = System.currentTimeMillis())
                }
            }
        }
    }

    @Volatile
    private var lastNudgeMillis: Long = 0L

    /**
     * Self-healing liveness check called during periodic foreground polls:
     * reconnects ONLY when the WebSocket actually dropped or stopped answering MQTT keep-alive
     * (`PINGRESP`) and traffic for > 95s — never killing a healthy quiet connection on a line
     * with few/zero moving buses!
     */
    fun ensureHealthyConnection(nowMillis: Long = System.currentTimeMillis()) {
        if (!isActiveDesired.get()) return
        val ws = activeWebSocket
        val lastActivity = maxOf(
            lastParsedVehicleAtMillis.get(),
            lastMessageReceivedAtMillis.get(),
            lastPingRespAtMillis.get(),
        )
        val connSince = connectedSinceMillis.get()
        val stalled = isMqttConnected.get() &&
            connSince > 0L &&
            (nowMillis - connSince) > 95_000L &&
            (lastActivity == 0L || (nowMillis - lastActivity) > 95_000L)
        if (ws == null || stalled) {
            if (stalled) {
                val idleSec = if (lastActivity > 0L) (nowMillis - lastActivity) / 1000L else (nowMillis - connSince) / 1000L
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.MQTT,
                    message = "Socket MaTO MQTT bloccato (idle=${idleSec}s) → riconnessione",
                    detail = "linea=$focusedLine raw=${rawPublishCount.get()} parsed=${parsedVehicleCount.get()}",
                    severity = DiagSeverity.WARN,
                    timestampMillis = nowMillis,
                )
            }
            scope.launch {
                if (!isActiveDesired.get()) return@launch
                reconnectAttempt = 0
                openSocket()
            }
        }
    }

    /**
     * Non-destructive subscription nudge: NEVER kills a healthy connected WebSocket that is
     * receiving PINGRESP/SUBACK! Only subscribes missing secondary groups if not yet sent,
     * or reconnects when truly disconnected / dead-socket stalled (> 95s without any frame).
     */
    fun nudgeFocusedLineSubscription(
        lineCode: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (!isActiveDesired.get()) return
        if (nowMillis - lastNudgeMillis < 30_000L) return
        lastNudgeMillis = nowMillis

        val targetLine = lineCode?.let { GtfsRtProtobufParser.normalizeGttLineCode(it) }
            ?.takeIf { it.isNotEmpty() && it != "?" }
            ?: focusedLine
        focusedLine = targetLine

        val ws = activeWebSocket
        val connSince = connectedSinceMillis.get()
        val connectedAgeMs = if (connSince > 0L) nowMillis - connSince else 0L
        val lastActivity = maxOf(
            lastParsedVehicleAtMillis.get(),
            lastMessageReceivedAtMillis.get(),
            lastPingRespAtMillis.get(),
        )
        val socketTrulyDead = connectedAgeMs >= 95_000L &&
            (lastActivity == 0L || (nowMillis - lastActivity) > 95_000L)

        scope.launch {
            if (!isActiveDesired.get()) return@launch
            if (ws != null && isMqttConnected.get() && !socketTrulyDead) {
                subscribeForLine(ws, targetLine, includeSecondaryGroups = true)
                if (connectedAgeMs >= 8_000L && lastParsedVehicleAtMillis.get() < connSince) {
                    sendBroadFallbackSubscriptions(ws)
                }
            } else if (ws == null || !isMqttConnected.get() || socketTrulyDead) {
                if (socketTrulyDead) {
                    reconnectAttempt = (reconnectAttempt + 1) % HANDSHAKE_PROFILES.size
                } else {
                    reconnectAttempt = 0
                }
                openSocket()
            }
        }
    }

    fun stop() {
        isActiveDesired.set(false)
        isMqttConnected.set(false)
        connectionGen.incrementAndGet()
        reconnectJob?.cancel()
        reconnectJob = null
        keepAliveJob?.cancel()
        keepAliveJob = null
        silentFallbackJob?.cancel()
        silentFallbackJob = null
        val ws = activeWebSocket
        activeWebSocket = null
        if (ws != null) {
            scope.launch {
                runCatching { ws.send(encodeDisconnectPacket().toByteString()) }
                runCatching { ws.close(1000, "App in background") }
            }
        }
    }

    /**
     * Returns ONLY confirmed live (non-retained) MaTO vehicles received within [LIVE_TTL_MILLIS]
     * (120 seconds), pruning expired entries automatically. Old broker RETAINED packets are NEVER
     * returned here — they remain quarantined in [retainedCandidatesById] until confirmed by a
     * fresh live PUBLISH or corroborated by GTT/BusOne.
     */
    fun snapshotFreshVehicles(nowMillis: Long = System.currentTimeMillis()): List<BusVehicle> {
        val cutoff = nowMillis - LIVE_TTL_MILLIS
        val out = ArrayList<BusVehicle>(vehiclesById.size)
        val iterator = vehiclesById.entries.iterator()
        val focus = focusedLine
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val v = entry.value
            if (v.fetchedAtMillis < cutoff) {
                if (focus.isNotEmpty() &&
                    (v.lineCode.equals(focus, ignoreCase = true) || v.rawRouteId.equals(focus, ignoreCase = true))
                ) {
                    val silentSec = ((nowMillis - v.fetchedAtMillis) / 1000L).coerceAtLeast(0L)
                    ToBusDiagnosticsLog.record(
                        category = DiagCategory.VEHICLE,
                        message = "#${v.vehicleId} L.${v.lineCode} MQTT_LIVE_EXPIRED",
                        detail = "silent=${silentSec}s>${LIVE_TTL_MILLIS / 1000L}s age=${ToBusDiagnosticsLog.formatDurationCompact(v.gpsAgeSeconds(nowMillis))} pos=${ToBusDiagnosticsLog.formatCoordPair(v.latitude, v.longitude)}",
                        severity = DiagSeverity.WARN,
                        timestampMillis = nowMillis,
                    )
                }
                iterator.remove()
            } else {
                out.add(v)
            }
        }
        return out
    }

    /**
     * Returns staged MQTT `RETAIN=1` candidates that have not yet received a live non-retained
     * update, so the merge engine can promote ONLY those corroborated by healthy GTT/BusOne feeds.
     */
    fun snapshotRetainedCandidates(nowMillis: Long = System.currentTimeMillis()): List<BusVehicle> {
        val cutoff = nowMillis - RETAINED_STAGING_TTL_MILLIS
        val out = ArrayList<BusVehicle>(retainedCandidatesById.size)
        val iterator = retainedCandidatesById.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val v = entry.value
            if (v.fetchedAtMillis < cutoff || vehiclesById.containsKey(entry.key)) {
                iterator.remove()
            } else {
                out.add(v)
            }
        }
        return out
    }

    /**
     * Inserts or updates an incoming MaTO MQTT vehicle using its canonical fleet ID:
     * - Retained (`isRetained = true`): NEVER enters [vehiclesById] directly! Placed in
     *   [retainedCandidatesById] without refreshing timestamps on duplicate re-subscribe deliveries.
     * - Live (`isRetained = false`): promotes/clears any retained staging entry, collapses
     *   provisional `tripId`-keyed entries when the real physical matricola arrives, and guards
     *   against regressive timestamps and single-frame teleports.
     */
    internal fun upsertMqttVehicle(
        bus: BusVehicle,
        isRetained: Boolean = bus.isMqttRetained,
    ) {
        val normTrip = GtfsRtProtobufParser.normalizeTripId(bus.tripId)
        val canonId = GtfsRtProtobufParser.canonicalFleetId(bus.vehicleId).ifBlank { bus.vehicleId.trim() }
        if (canonId.isEmpty()) return
        val canonLabel = GtfsRtProtobufParser.canonicalFleetId(bus.label).ifBlank { canonId }
        val normalizedBus = bus.copy(
            vehicleId = canonId,
            label = canonLabel,
            tripId = normTrip,
            isMqttRetained = isRetained,
        )

        val focus = focusedLine
        val isFocused = focus.isNotEmpty() &&
            (normalizedBus.lineCode.equals(focus, ignoreCase = true) || normalizedBus.rawRouteId.equals(focus, ignoreCase = true))

        if (isRetained) {
            // If this vehicle already sent a genuine live non-retained packet, NEVER overwrite or
            // refresh it with an old retained copy from a re-subscribe!
            if (vehiclesById.containsKey(canonId)) return
            val existingRetained = retainedCandidatesById[canonId]
            if (existingRetained != null &&
                existingRetained.lineCode.equals(normalizedBus.lineCode, ignoreCase = true)
            ) {
                val distM = GttVehicleMergeEngine.haversineMeters(
                    existingRetained.latitude,
                    existingRetained.longitude,
                    normalizedBus.latitude,
                    normalizedBus.longitude,
                )
                // Re-subscribing delivers the exact same retained coordinates again -> keep original
                // arrival time so retained ghosts can NEVER artificially stay "fresh" forever!
                if (distM <= 5.0) return
            }
            retainedCandidatesById[canonId] = normalizedBus
            if (isFocused && existingRetained == null) {
                ToBusDiagnosticsLog.record(
                    category = DiagCategory.VEHICLE,
                    message = "#$canonId L.${normalizedBus.lineCode} MQTT_STAGED_RETAINED",
                    detail = "age=${ToBusDiagnosticsLog.formatDurationCompact(normalizedBus.gpsAgeSeconds(normalizedBus.fetchedAtMillis))} pos=${ToBusDiagnosticsLog.formatCoordPair(normalizedBus.latitude, normalizedBus.longitude)} waiting=GTT/BusOne_or_live",
                    severity = DiagSeverity.INFO,
                    timestampMillis = normalizedBus.fetchedAtMillis,
                )
            }
            return
        }

        // Genuine LIVE (non-retained) update -> promote out of retained staging pool
        val promotedFromRetained = retainedCandidatesById.remove(canonId)
        if (isFocused && promotedFromRetained != null) {
            val deltaM = GttVehicleMergeEngine.haversineMeters(
                promotedFromRetained.latitude,
                promotedFromRetained.longitude,
                normalizedBus.latitude,
                normalizedBus.longitude,
            ).toInt()
            ToBusDiagnosticsLog.record(
                category = DiagCategory.VEHICLE,
                message = "#$canonId L.${normalizedBus.lineCode} MQTT_PROMOTED_LIVE",
                detail = "from=retained delta=${deltaM}m age=${ToBusDiagnosticsLog.formatDurationCompact(normalizedBus.gpsAgeSeconds(normalizedBus.fetchedAtMillis))} pos=${ToBusDiagnosticsLog.formatCoordPair(normalizedBus.latitude, normalizedBus.longitude)}",
                severity = DiagSeverity.OK,
                timestampMillis = normalizedBus.fetchedAtMillis,
            )
        }

        if (normTrip != null) {
            val isIncomingPhysical = GtfsRtProtobufParser.isPhysicalFleetId(canonId)
            retainedCandidatesById.entries.removeIf { (key, staged) ->
                key != canonId &&
                    !GtfsRtProtobufParser.isPhysicalFleetId(key) &&
                    staged.lineCode.equals(normalizedBus.lineCode, ignoreCase = true) &&
                    GtfsRtProtobufParser.normalizeTripId(staged.tripId)?.equals(normTrip, ignoreCase = true) == true
            }
            val matching = vehiclesById.entries.filter { (key, existing) ->
                key != canonId &&
                    existing.lineCode.equals(normalizedBus.lineCode, ignoreCase = true) &&
                    GtfsRtProtobufParser.normalizeTripId(existing.tripId)?.equals(normTrip, ignoreCase = true) == true
            }
            for ((oldKey, existing) in matching) {
                val isExistingPhysical = GtfsRtProtobufParser.isPhysicalFleetId(oldKey)
                if (isIncomingPhysical && !isExistingPhysical) {
                    vehiclesById.remove(oldKey)
                    pendingTeleportById.remove(oldKey)
                } else if (!isIncomingPhysical && isExistingPhysical) {
                    if (isRegressiveTimestamp(existing.timestampSeconds, normalizedBus.timestampSeconds)) {
                        if (isFocused) {
                            ToBusDiagnosticsLog.record(
                                category = DiagCategory.MQTT,
                                message = "#$oldKey L.${normalizedBus.lineCode} MQTT_TS_REGRESS (scartato)",
                                detail = "existingTs=${existing.timestampSeconds} incomingTs=${normalizedBus.timestampSeconds} delta=${existing.timestampSeconds - normalizedBus.timestampSeconds}s",
                                severity = DiagSeverity.INFO,
                            )
                        }
                        return
                    }
                    if (!acceptsKinematicUpdate(oldKey, existing, normalizedBus)) {
                        return
                    }
                    vehiclesById[oldKey] = existing.copy(
                        latitude = normalizedBus.latitude,
                        longitude = normalizedBus.longitude,
                        bearing = normalizedBus.bearing ?: existing.bearing,
                        speedKmh = normalizedBus.speedKmh ?: existing.speedKmh,
                        timestampSeconds = maxOf(existing.timestampSeconds, normalizedBus.timestampSeconds),
                        stopId = normalizedBus.stopId ?: existing.stopId,
                        nextStopId = normalizedBus.nextStopId ?: existing.nextStopId,
                        directionId = normalizedBus.directionId ?: existing.directionId,
                        fetchedAtMillis = maxOf(existing.fetchedAtMillis, normalizedBus.fetchedAtMillis),
                        isMqttRetained = false,
                        hasExplicitTimestamp = existing.hasExplicitTimestamp || normalizedBus.hasExplicitTimestamp,
                    )
                    return
                }
            }
        }
        val existing = vehiclesById[canonId]
        if (existing != null) {
            if (isRegressiveTimestamp(existing.timestampSeconds, normalizedBus.timestampSeconds)) {
                if (isFocused) {
                    ToBusDiagnosticsLog.record(
                        category = DiagCategory.MQTT,
                        message = "#$canonId L.${normalizedBus.lineCode} MQTT_TS_REGRESS (scartato)",
                        detail = "existingTs=${existing.timestampSeconds} incomingTs=${normalizedBus.timestampSeconds} delta=${existing.timestampSeconds - normalizedBus.timestampSeconds}s",
                        severity = DiagSeverity.INFO,
                    )
                }
                return
            }
            if (!acceptsKinematicUpdate(canonId, existing, normalizedBus)) {
                return
            }
        } else {
            pendingTeleportById.remove(canonId)
        }
        vehiclesById[canonId] = normalizedBus
    }

    private fun acceptsKinematicUpdate(
        key: String,
        existing: BusVehicle,
        incoming: BusVehicle,
    ): Boolean {
        val pending = pendingTeleportById[key]
        if (acceptsKinematicUpdate(existing, incoming, pending)) {
            pendingTeleportById.remove(key)
            return true
        }
        pendingTeleportById[key] = incoming
        val distM = GttVehicleMergeEngine.haversineMeters(
            existing.latitude,
            existing.longitude,
            incoming.latitude,
            incoming.longitude,
        )
        val dtSec = if (existing.timestampSeconds > 0L && incoming.timestampSeconds > existing.timestampSeconds) {
            incoming.timestampSeconds - existing.timestampSeconds
        } else {
            ((incoming.fetchedAtMillis - existing.fetchedAtMillis) / 1000L).coerceAtLeast(1L)
        }
        if (distM > 500.0) {
            ToBusDiagnosticsLog.record(
                category = DiagCategory.MQTT,
                message = "Scarto salto GPS #$key (${distM.toInt()}m in ${dtSec}s)",
                detail = "linea=${incoming.lineCode} in attesa di conferma",
                severity = DiagSeverity.WARN,
            )
        }
        return false
    }

    private fun openSocket() {
        if (!isActiveDesired.get()) return
        reconnectJob?.cancel()
        keepAliveJob?.cancel()
        silentFallbackJob?.cancel()
        val myGen = connectionGen.incrementAndGet()
        activeWebSocket?.cancel()
        activeWebSocket = null
        subscribedTopicsForConn.clear()
        subscribedTopicsByLine.clear()
        rawPublishCount.set(0L)
        livePublishCount.set(0L)
        retainedPublishCount.set(0L)
        parsedVehicleCount.set(0L)
        lastPingRespAtMillis.set(0L)
        subAckAcceptedCount.set(0)
        subAckRejectedCount.set(0)
        lastSampleTopic = null
        lastPayloadPreview = null
        rootWildcardSentAtMillis = 0L
        hasPushedFocusedVehicle = false

        val profileIdx = if (workingProfileIdx in HANDSHAKE_PROFILES.indices && reconnectAttempt == 0) {
            workingProfileIdx
        } else {
            reconnectAttempt % HANDSHAKE_PROFILES.size
        }
        val profile = HANDSHAKE_PROFILES[profileIdx]

        val reqBuilder = Request.Builder()
            .url(profile.url)
            .header("Origin", "https://mato.muoversiatorino.it")
            .header("Host", "mapi.5t.torino.it")
            .header("User-Agent", BROWSER_USER_AGENT)
        if (profile.subProtocol != null) {
            reqBuilder.header("Sec-WebSocket-Protocol", profile.subProtocol)
        }
        val request = reqBuilder.build()

        val accumulator = MqttFrameAccumulator()
        val socket = wsClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (connectionGen.get() != myGen) {
                        webSocket.close(1000, "Superseded")
                        return
                    }
                    activeWebSocket = webSocket
                    lastErrorDetail = "Handshake MQTT…"
                    val clientId = "mato_${UUID.randomUUID().toString().replace("-", "").take(12)}"
                    val connectBytes = encodeConnectPacket(
                        clientId = clientId,
                        keepAliveSec = 45,
                        useMqtt31 = profile.useMqtt31,
                    )
                    webSocket.send(connectBytes.toByteString())
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (connectionGen.get() != myGen) return
                    val packets = accumulator.appendAndDecode(bytes.toByteArray())
                    val nowMillis = System.currentTimeMillis()
                    var touchedFocusedLine = false
                    var parsedAnyNow = false
                    var justConnected = false

                    for (pkt in packets) {
                        when (pkt) {
                            is MqttPacket.ConnAck -> {
                                if (pkt.returnCode == 0) {
                                    isMqttConnected.set(true)
                                    connectedSinceMillis.set(nowMillis)
                                    workingProfileIdx = profileIdx
                                    reconnectAttempt = 0
                                    lastErrorDetail = null
                                    justConnected = true
                                    ToBusDiagnosticsLog.record(
                                        category = DiagCategory.MQTT,
                                        message = "CONNACK OK [${profile.label}]",
                                        detail = "linea=$focusedLine subProto=${profile.subProtocol ?: "none"}",
                                        severity = DiagSeverity.OK,
                                    )
                                    subscribeForLine(webSocket, focusedLine, includeSecondaryGroups = false)
                                    startKeepAliveLoop(webSocket, myGen)
                                    scheduleSilentTopicFallback(
                                        webSocket = webSocket,
                                        myGen = myGen,
                                        lineCode = focusedLine,
                                        onlyIfNoParsedSince = nowMillis,
                                    )
                                } else {
                                    lastErrorDetail = "CONNACK ${pkt.returnCode}"
                                    ToBusDiagnosticsLog.record(
                                        category = DiagCategory.MQTT,
                                        message = "CONNACK rifiutato (${pkt.returnCode}) [${profile.label}]",
                                        severity = DiagSeverity.WARN,
                                    )
                                    webSocket.close(1002, "CONNACK rejected")
                                }
                            }
                            is MqttPacket.SubAck -> {
                                val accepted = pkt.returnCodes.count { it in 0..2 }
                                val rejected = pkt.returnCodes.count { it == 0x80 }
                                if (accepted > 0) subAckAcceptedCount.addAndGet(accepted)
                                if (rejected > 0) subAckRejectedCount.addAndGet(rejected)
                                if (accepted == 0 && rejected > 0 && subAckAcceptedCount.get() == 0) {
                                    lastErrorDetail = "Topic rifiutati (SUBACK)"
                                    ToBusDiagnosticsLog.record(
                                        category = DiagCategory.MQTT,
                                        message = "SUBACK: tutti i topic rifiutati ($rejected)",
                                        detail = "linea=$focusedLine",
                                        severity = DiagSeverity.WARN,
                                    )
                                    onFocusedLineUpdate?.invoke()
                                } else if (accepted > 0 && lastErrorDetail == "Topic rifiutati (SUBACK)") {
                                    lastErrorDetail = null
                                    onFocusedLineUpdate?.invoke()
                                }
                            }
                            is MqttPacket.Publish -> {
                                if (pkt.qos == 1 && pkt.packetId != null) {
                                    runCatching {
                                        webSocket.send(encodePubAckPacket(pkt.packetId).toByteString())
                                    }
                                }
                                lastMessageReceivedAtMillis.set(nowMillis)
                                val count = rawPublishCount.incrementAndGet()
                                if (pkt.isRetained) {
                                    retainedPublishCount.incrementAndGet()
                                } else {
                                    livePublishCount.incrementAndGet()
                                }
                                if (pkt.topic.isNotBlank()) {
                                    lastSampleTopic = pkt.topic.take(64)
                                }
                                val parsed = parseMatoMessage(
                                    topic = pkt.topic,
                                    payload = pkt.payload,
                                    nowMillis = nowMillis,
                                    isRetained = pkt.isRetained,
                                )
                                if (parsed.isNotEmpty()) {
                                    lastParsedVehicleAtMillis.set(nowMillis)
                                    if (!pkt.isRetained) {
                                        lastLiveParsedVehicleAtMillis.set(nowMillis)
                                        parsedVehicleCount.addAndGet(parsed.size.toLong())
                                        parsedAnyNow = true
                                    }
                                    for (bus in parsed) {
                                        upsertMqttVehicle(bus, isRetained = pkt.isRetained)
                                        if (!pkt.isRetained && (
                                                bus.lineCode.equals(focusedLine, ignoreCase = true) ||
                                                    bus.rawRouteId.equals(focusedLine, ignoreCase = true)
                                                )
                                        ) {
                                            touchedFocusedLine = true
                                        }
                                    }
                                } else if (count <= 3L) {
                                    val preview = runCatching {
                                        String(pkt.payload.copyOfRange(0, pkt.payload.size.coerceAtMost(96)), StandardCharsets.UTF_8)
                                            .replace(Regex("[\\r\\n\\t]+"), " ")
                                            .trim()
                                    }.getOrDefault("<bin:${pkt.payload.size}B>")
                                    lastPayloadPreview = preview.take(48)
                                    Log.d(TAG, "MaTO unparsed frame #$count topic='${pkt.topic}' payload='$preview'")
                                    if (count == 1L) {
                                        onFocusedLineUpdate?.invoke()
                                    }
                                }
                            }
                            is MqttPacket.PingResp -> {
                                lastPingRespAtMillis.set(nowMillis)
                                lastMessageReceivedAtMillis.set(nowMillis)
                            }
                        }
                    }

                    val firstFocusedHit = touchedFocusedLine && !hasPushedFocusedVehicle
                    if (firstFocusedHit) {
                        hasPushedFocusedVehicle = true
                        lastNotifyMillis = nowMillis
                        onFocusedLineUpdate?.invoke()
                    } else if (justConnected) {
                        onFocusedLineUpdate?.invoke()
                    } else if ((touchedFocusedLine || (parsedAnyNow && parsedVehicleCount.get() <= 4L)) &&
                        nowMillis - lastNotifyMillis >= NOTIFY_DEBOUNCE_MS
                    ) {
                        lastNotifyMillis = nowMillis
                        onFocusedLineUpdate?.invoke()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (connectionGen.get() != myGen) return
                    val nowMillis = System.currentTimeMillis()
                    lastMessageReceivedAtMillis.set(nowMillis)
                    rawPublishCount.incrementAndGet()
                    val parsed = parseMatoMessage("", text.toByteArray(StandardCharsets.UTF_8), nowMillis)
                    if (parsed.isNotEmpty()) {
                        isMqttConnected.set(true)
                        workingProfileIdx = profileIdx
                        lastParsedVehicleAtMillis.set(nowMillis)
                        lastLiveParsedVehicleAtMillis.set(nowMillis)
                        livePublishCount.incrementAndGet()
                        parsedVehicleCount.addAndGet(parsed.size.toLong())
                        var touched = false
                        for (bus in parsed) {
                            upsertMqttVehicle(bus)
                            if (bus.lineCode.equals(focusedLine, ignoreCase = true)) touched = true
                        }
                        if (touched && nowMillis - lastNotifyMillis >= NOTIFY_DEBOUNCE_MS) {
                            lastNotifyMillis = nowMillis
                            onFocusedLineUpdate?.invoke()
                        }
                    } else if (lastPayloadPreview == null) {
                        lastPayloadPreview = text.replace(Regex("[\\r\\n\\t]+"), " ").trim().take(48)
                        onFocusedLineUpdate?.invoke()
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (connectionGen.get() == myGen) {
                        val now = System.currentTimeMillis()
                        if (rootWildcardSentAtMillis > 0L && (now - rootWildcardSentAtMillis) < 3_000L) {
                            rootWildcardDisallowed = true
                        }
                        isMqttConnected.set(false)
                        activeWebSocket = null
                        keepAliveJob?.cancel()
                        silentFallbackJob?.cancel()
                        if (lastErrorDetail == null) {
                            lastErrorDetail = "Chiuso ($code)"
                        }
                        ToBusDiagnosticsLog.record(
                            category = DiagCategory.MQTT,
                            message = "WS chiuso ($code) [${profile.label}]",
                            detail = "raw=${rawPublishCount.get()} parsed=${parsedVehicleCount.get()} subOk=${subAckAcceptedCount.get()}/rej=${subAckRejectedCount.get()}",
                            severity = DiagSeverity.WARN,
                        )
                        onFocusedLineUpdate?.invoke()
                        scheduleReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (connectionGen.get() == myGen) {
                        val now = System.currentTimeMillis()
                        if (rootWildcardSentAtMillis > 0L && (now - rootWildcardSentAtMillis) < 3_000L) {
                            rootWildcardDisallowed = true
                        }
                        isMqttConnected.set(false)
                        activeWebSocket = null
                        keepAliveJob?.cancel()
                        silentFallbackJob?.cancel()
                        val codePart = response?.code?.let { "HTTP $it · " }.orEmpty()
                        lastErrorDetail = (codePart + (t.message ?: "Errore WS")).take(44)
                        Log.d(TAG, "MaTO MQTT fail [${profile.label}]: $lastErrorDetail")
                        ToBusDiagnosticsLog.record(
                            category = DiagCategory.MQTT,
                            message = "Errore WS [${profile.label}]: $lastErrorDetail",
                            detail = "attempt=$reconnectAttempt",
                            severity = DiagSeverity.ERROR,
                        )
                        onFocusedLineUpdate?.invoke()
                        scheduleReconnect()
                    }
                }
            },
        )
        if (connectionGen.get() == myGen) {
            activeWebSocket = socket
        } else {
            socket.cancel()
        }
    }

    private fun sendUniqueSubscriptions(
        webSocket: WebSocket,
        topics: List<String>,
        onePerPacket: Boolean,
        ownerLine: String? = null,
    ) {
        val fresh = topics.filter { topic -> subscribedTopicsForConn.add(topic) }
        if (fresh.isEmpty()) return
        if (!ownerLine.isNullOrBlank()) {
            subscribedTopicsByLine
                .computeIfAbsent(ownerLine.uppercase()) { ConcurrentHashMap.newKeySet() }
                .addAll(fresh)
        }
        if (onePerPacket) {
            for (singleTopic in fresh) {
                val packetId = (packetIdGen.getAndIncrement() and 0x7FFF).coerceAtLeast(1)
                runCatching {
                    webSocket.send(encodeSubscribePacket(packetId, listOf(singleTopic)).toByteString())
                }
            }
        } else {
            for (batch in fresh.chunked(4)) {
                val packetId = (packetIdGen.getAndIncrement() and 0x7FFF).coerceAtLeast(1)
                runCatching {
                    webSocket.send(encodeSubscribePacket(packetId, batch).toByteString())
                }
            }
        }
    }

    private fun unsubscribeForLine(
        webSocket: WebSocket?,
        lineCode: String,
    ) {
        val ws = webSocket ?: return
        val key = lineCode.trim().uppercase()
        if (key.isEmpty()) return
        val topics = subscribedTopicsByLine.remove(key)?.toList().orEmpty()
        if (topics.isEmpty()) return
        subscribedTopicsForConn.removeAll(topics.toSet())
        for (batch in topics.chunked(6)) {
            val packetId = (packetIdGen.getAndIncrement() and 0x7FFF).coerceAtLeast(1)
            runCatching {
                ws.send(encodeUnsubscribePacket(packetId, batch).toByteString())
            }
        }
    }

    private fun subscribeForLine(
        webSocket: WebSocket?,
        lineCode: String,
        includeSecondaryGroups: Boolean = false,
    ) {
        val ws = webSocket ?: return
        if (lineCode.isBlank() || lineCode == "?") return
        val groups = buildLineSubscriptionGroups(lineCode)
        // Stage 0: Minimal exact & single-level 5T/MaTO topics sent 1-per-SUBSCRIBE packet
        // (deduplicated per connection so re-subscribes NEVER trigger retained ghost bursts!)
        groups.getOrNull(0)?.let { sendUniqueSubscriptions(ws, it, onePerPacket = true, ownerLine = lineCode) }
        if (includeSecondaryGroups) {
            groups.getOrNull(1)?.let { sendUniqueSubscriptions(ws, it, onePerPacket = false, ownerLine = lineCode) }
            sendSecondaryLineGroups(ws, groups, ownerLine = lineCode)
        }
    }

    private fun sendSecondaryLineGroups(
        webSocket: WebSocket,
        groups: List<List<String>>,
        ownerLine: String? = null,
    ) {
        for (topics in groups.drop(1)) {
            if (topics.isEmpty()) continue
            sendUniqueSubscriptions(webSocket, topics, onePerPacket = false, ownerLine = ownerLine)
        }
    }

    private fun sendBroadFallbackSubscriptions(webSocket: WebSocket) {
        val broadTopics = listOf(
            "/ALL",
            "ALL",
            "/map/v1/eta/#",
            "/gtfsrt/vp/#",
            "/hfp/v2/journey/ongoing/vp/#",
            "/+/#",
            "+/#",
        )
        sendUniqueSubscriptions(webSocket, broadTopics, onePerPacket = true)
    }

    /**
     * Staged silent-topic escalation:
     * - +2.0s: send prefixed / GTFS-RT / HFP line topics only if Stage 0+1 received no vehicles yet.
     * - +4.0s: send broad citywide filters (`/ALL`, `ALL`, `/+/#`, `+/#`, `/map/v1/eta/#`).
     * - +7.0s: send root wildcards (`/#`, `#`) unless the broker previously kicked on root wildcards.
     */
    private fun scheduleSilentTopicFallback(
        webSocket: WebSocket,
        myGen: Long,
        lineCode: String,
        onlyIfNoParsedSince: Long,
    ) {
        silentFallbackJob?.cancel()
        silentFallbackJob = scope.launch {
            delay(2_000L)
            if (!isActive || connectionGen.get() != myGen || !isMqttConnected.get()) return@launch
            if (lastParsedVehicleAtMillis.get() >= onlyIfNoParsedSince) return@launch

            sendSecondaryLineGroups(webSocket, buildLineSubscriptionGroups(lineCode), ownerLine = lineCode)
            ToBusDiagnosticsLog.record(
                category = DiagCategory.MQTT,
                message = "Escalation topic L.$lineCode: gruppi secondari (+2s silenzio)",
                detail = "raw=${rawPublishCount.get()} parsed=${parsedVehicleCount.get()}",
                severity = DiagSeverity.INFO,
            )

            delay(2_000L)
            if (!isActive || connectionGen.get() != myGen || !isMqttConnected.get()) return@launch
            if (lastParsedVehicleAtMillis.get() >= onlyIfNoParsedSince) return@launch

            sendBroadFallbackSubscriptions(webSocket)
            ToBusDiagnosticsLog.record(
                category = DiagCategory.MQTT,
                message = "Escalation topic L.$lineCode: fallback ampio /ALL (+4s silenzio)",
                detail = "raw=${rawPublishCount.get()} parsed=${parsedVehicleCount.get()}",
                severity = DiagSeverity.INFO,
            )

            delay(3_000L)
            if (!isActive || connectionGen.get() != myGen || !isMqttConnected.get()) return@launch
            if (lastParsedVehicleAtMillis.get() >= onlyIfNoParsedSince) return@launch
            if (rootWildcardDisallowed) return@launch

            rootWildcardSentAtMillis = System.currentTimeMillis()
            ToBusDiagnosticsLog.record(
                category = DiagCategory.MQTT,
                message = "Escalation topic L.$lineCode: wildcard /# (+7s silenzio)",
                detail = "raw=${rawPublishCount.get()} parsed=${parsedVehicleCount.get()}",
                severity = DiagSeverity.WARN,
            )
            for (rootTopic in listOf("/#", "#")) {
                val pid = (packetIdGen.getAndIncrement() and 0x7FFF).coerceAtLeast(1)
                runCatching {
                    webSocket.send(encodeSubscribePacket(pid, listOf(rootTopic)).toByteString())
                }
            }
        }
    }

    private fun startKeepAliveLoop(webSocket: WebSocket, myGen: Long) {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && isActiveDesired.get() && connectionGen.get() == myGen) {
                delay(30_000L)
                if (connectionGen.get() != myGen) break
                val ok = runCatching { webSocket.send(encodePingReqPacket().toByteString()) }.getOrDefault(false)
                if (!ok) break
            }
        }
    }

    private fun scheduleReconnect() {
        if (!isActiveDesired.get()) return
        reconnectJob?.cancel()
        // Quickly cycle through all handshake profiles on startup, then back off gently
        val backoffMs = when {
            reconnectAttempt < HANDSHAKE_PROFILES.size -> 1_500L
            reconnectAttempt < HANDSHAKE_PROFILES.size + 2 -> 6_000L
            else -> 18_000L
        }
        reconnectAttempt++
        reconnectJob = scope.launch {
            delay(backoffMs)
            if (isActiveDesired.get()) {
                openSocket()
            }
        }
    }

    private data class HandshakeProfile(
        val label: String,
        val url: String,
        val subProtocol: String?,
        val useMqtt31: Boolean = false,
    )

    sealed class MqttPacket {
        data class ConnAck(val returnCode: Int) : MqttPacket()
        data class SubAck(val packetId: Int, val returnCodes: List<Int>) : MqttPacket()
        data class Publish(
            val topic: String,
            val payload: ByteArray,
            val qos: Int = 0,
            val packetId: Int? = null,
            val isRetained: Boolean = false,
        ) : MqttPacket()
        data object PingResp : MqttPacket()
    }

    /**
     * Streaming MQTT 3.1 / 3.1.1 frame decoder handling coalesced or fragmented binary WebSocket frames.
     */
    internal class MqttFrameAccumulator {
        private var buffer = ByteArray(0)

        fun appendAndDecode(chunk: ByteArray): List<MqttPacket> {
            if (chunk.isEmpty()) return emptyList()
            val combined = ByteArray(buffer.size + chunk.size)
            System.arraycopy(buffer, 0, combined, 0, buffer.size)
            System.arraycopy(chunk, 0, combined, buffer.size, chunk.size)

            val packets = mutableListOf<MqttPacket>()
            var offset = 0
            while (offset < combined.size) {
                if (offset + 2 > combined.size) break
                val header = combined[offset].toInt() and 0xFF
                val packetType = header ushr 4

                var multiplier = 1
                var remainingLength = 0
                var lenBytes = 0
                var completeLen = false
                var idx = offset + 1
                while (idx < combined.size && lenBytes < 4) {
                    val b = combined[idx++].toInt() and 0xFF
                    remainingLength += (b and 0x7F) * multiplier
                    lenBytes++
                    if ((b and 0x80) == 0) {
                        completeLen = true
                        break
                    }
                    multiplier *= 128
                }
                if (!completeLen) break
                val bodyStart = offset + 1 + lenBytes
                val packetEnd = bodyStart + remainingLength
                if (packetEnd > combined.size) break

                when (packetType) {
                    2 -> { // CONNACK
                        if (remainingLength >= 2) {
                            val rc = combined[bodyStart + 1].toInt() and 0xFF
                            packets.add(MqttPacket.ConnAck(rc))
                        }
                    }
                    3 -> { // PUBLISH (bit 0 = RETAIN, bits 1-2 = QoS, bit 3 = DUP)
                        val qos = (header and 0x06) ushr 1
                        val isRetained = (header and 0x01) != 0
                        if (remainingLength >= 2) {
                            val topicLen = ((combined[bodyStart].toInt() and 0xFF) shl 8) or
                                (combined[bodyStart + 1].toInt() and 0xFF)
                            val topicStart = bodyStart + 2
                            val topicEnd = (topicStart + topicLen).coerceAtMost(packetEnd)
                            val topic = String(combined, topicStart, topicEnd - topicStart, StandardCharsets.UTF_8)
                            val packetId = if (qos > 0 && topicEnd + 2 <= packetEnd) {
                                ((combined[topicEnd].toInt() and 0xFF) shl 8) or
                                    (combined[topicEnd + 1].toInt() and 0xFF)
                            } else {
                                null
                            }
                            val payloadStart = (topicEnd + if (qos > 0) 2 else 0).coerceAtMost(packetEnd)
                            val payload = combined.copyOfRange(payloadStart, packetEnd)
                            packets.add(MqttPacket.Publish(topic, payload, qos, packetId, isRetained))
                        }
                    }
                    9 -> { // SUBACK
                        if (remainingLength >= 2) {
                            val pid = ((combined[bodyStart].toInt() and 0xFF) shl 8) or
                                (combined[bodyStart + 1].toInt() and 0xFF)
                            val codes = (bodyStart + 2 until packetEnd).map { combined[it].toInt() and 0xFF }
                            packets.add(MqttPacket.SubAck(pid, codes))
                        }
                    }
                    13 -> packets.add(MqttPacket.PingResp)
                }

                offset = packetEnd
            }

            buffer = if (offset < combined.size) {
                combined.copyOfRange(offset, combined.size)
            } else {
                ByteArray(0)
            }
            return packets
        }
    }

    companion object {
        private const val TAG = "MatoMqttLive"
        private const val LIVE_TTL_MILLIS = 120_000L
        private const val RETAINED_STAGING_TTL_MILLIS = 10 * 60_000L
        private const val NOTIFY_DEBOUNCE_MS = 1_500L
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val TOPIC_NON_ROUTE_SEGMENTS = setOf(
            "5t", "gtt", "mato", "scre", "map", "eta", "vp", "bus", "tram", "metro",
            "rail", "train", "all", "vehicles", "vehicle", "positions", "position", "pos",
            "live", "realtime", "rt", "gtfs", "gtfsrt", "hfp", "journey", "ongoing",
            "torino", "turin", "piemonte", "to", "api", "ws", "mqtt", "feed", "data",
            "v1", "v2", "v3", "v4", "v5", "s1", "s2",
        )

        private val KNOWN_ALPHA_LINES = setOf(
            "BLU", "DORA", "STAR", "METRO", "VENARIA", "RIVOLI", "SUPERGA",
        )

        private val HANDSHAKE_PROFILES = listOf(
            HandshakeProfile("scre-443-mqtt", "wss://mapi.5t.torino.it:443/scre", "mqtt", useMqtt31 = false),
            HandshakeProfile("scre-443-mqttv31", "wss://mapi.5t.torino.it:443/scre", "mqttv3.1", useMqtt31 = false),
            HandshakeProfile("scre-both", "wss://mapi.5t.torino.it/scre", "mqtt, mqttv3.1", useMqtt31 = false),
            HandshakeProfile("scre-mqisdp", "wss://mapi.5t.torino.it:443/scre", "mqttv3.1", useMqtt31 = true),
            HandshakeProfile("scre-nosub", "wss://mapi.5t.torino.it/scre", null, useMqtt31 = false),
        )

        internal fun buildLineSubscriptionGroups(lineCode: String): List<List<String>> {
            val base = GtfsRtProtobufParser.normalizeGttLineCode(lineCode)
            if (base.isEmpty() || base == "?") return emptyList()
            val primaryVariants = linkedSetOf<String>().apply {
                if (!base.endsWith("U")) add("${base}U")
                add(base)
                for (alt in GtfsRtProtobufParser.alternateFeedQueryCodes(base)) {
                    if (!alt.contains(' ')) {
                        if (!alt.endsWith("U") && !alt.endsWith("/")) add("${alt}U")
                        add(alt)
                    }
                }
            }
            val allVariants = linkedSetOf<String>().apply {
                addAll(primaryVariants)
                if (base.all { it.isDigit() } ||
                    (base.length >= 2 && base.endsWith("B") && base.dropLast(1).all { it.isDigit() })
                ) {
                    add("${base}F")
                    add("${base}E")
                }
            }

            val exactPrimary = mutableListOf<String>()
            val wildcardGroup = mutableListOf<String>()
            val prefixedGroup = mutableListOf<String>()
            val gtfsRtGroup = mutableListOf<String>()

            // Group 0: Minimal, non-overlapping primary 5T topics (avoids 40-topic duplicate delivery bursts!)
            for (v in primaryVariants) {
                exactPrimary.add("/$v/+")
                exactPrimary.add("/$v")
                exactPrimary.add("$v/+")
                exactPrimary.add(v)
            }

            // Group 1: Secondary prefixed/wildcard topics (sent ONLY if Group 0 stays silent)
            for (v in primaryVariants) {
                wildcardGroup.add("/gtt:$v")
                wildcardGroup.add("gtt:$v")
                wildcardGroup.add("/map/v1/eta/$v")
                wildcardGroup.add("/map/v1/eta/$v/+")
                wildcardGroup.add("/map/v1/eta/gtt:$v")
                wildcardGroup.add("/gtt/$v")
            }
            for (v in allVariants) {
                wildcardGroup.add("/$v/#")
                wildcardGroup.add("$v/#")
                wildcardGroup.add("/map/v1/eta/$v/#")
                wildcardGroup.add("/map/v1/eta/gtt:$v/#")
                if (v !in primaryVariants) {
                    wildcardGroup.add("/$v")
                    wildcardGroup.add(v)
                }
            }

            // Group 2: Prefixed GTT / 5T topics
            for (v in allVariants) {
                prefixedGroup.add("/gtt/$v/#")
                prefixedGroup.add("gtt/$v/#")
                prefixedGroup.add("/+/$v/#")
                prefixedGroup.add("+/$v/#")
                prefixedGroup.add("/gtt:$v/#")
                prefixedGroup.add("gtt:$v/#")
            }

            // Group 3: Digitransit GTFS-RT & HFP topics across 1, 2, and 3 intermediate hierarchy levels
            for (v in primaryVariants) {
                gtfsRtGroup.add("/gtfsrt/vp/+/$v/#")
                gtfsRtGroup.add("/gtfsrt/vp/+/+/$v/#")
                gtfsRtGroup.add("/gtfsrt/vp/+/+/+/$v/#")
                gtfsRtGroup.add("/gtfsrt/vp/+/+/+/gtt:$v/#")
                gtfsRtGroup.add("/hfp/v2/journey/ongoing/vp/+/+/+/$v/#")
            }

            return listOf(
                exactPrimary.distinct(),
                wildcardGroup.distinct(),
                prefixedGroup.distinct(),
                gtfsRtGroup.distinct(),
            )
        }

        internal fun encodeConnectPacket(
            clientId: String,
            keepAliveSec: Int = 45,
            useMqtt31: Boolean = false,
        ): ByteArray {
            val body = ByteArrayOutputStream()
            if (useMqtt31) {
                writeMqttString(body, "MQIsdp")
                body.write(0x03) // MQTT 3.1
            } else {
                writeMqttString(body, "MQTT")
                body.write(0x04) // MQTT 3.1.1
            }
            body.write(0x02) // Clean Session
            body.write((keepAliveSec ushr 8) and 0xFF)
            body.write(keepAliveSec and 0xFF)
            writeMqttString(body, clientId)
            return wrapFixedHeader(0x10, body.toByteArray())
        }

        internal fun encodeSubscribePacket(
            packetId: Int,
            topics: List<String>,
        ): ByteArray {
            val body = ByteArrayOutputStream()
            body.write((packetId ushr 8) and 0xFF)
            body.write(packetId and 0xFF)
            for (topic in topics) {
                writeMqttString(body, topic)
                body.write(0x00) // QoS 0
            }
            return wrapFixedHeader(0x82, body.toByteArray())
        }

        internal fun encodeUnsubscribePacket(
            packetId: Int,
            topics: List<String>,
        ): ByteArray {
            val body = ByteArrayOutputStream()
            body.write((packetId ushr 8) and 0xFF)
            body.write(packetId and 0xFF)
            for (topic in topics) {
                writeMqttString(body, topic)
            }
            return wrapFixedHeader(0xA2, body.toByteArray())
        }

        internal fun encodePubAckPacket(packetId: Int): ByteArray = byteArrayOf(
            0x40,
            0x02,
            ((packetId ushr 8) and 0xFF).toByte(),
            (packetId and 0xFF).toByte(),
        )

        internal fun encodePingReqPacket(): ByteArray = byteArrayOf(0xC0.toByte(), 0x00)

        internal fun encodeDisconnectPacket(): ByteArray = byteArrayOf(0xE0.toByte(), 0x00)

        private fun writeMqttString(out: ByteArrayOutputStream, value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            out.write((bytes.size ushr 8) and 0xFF)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
        }

        private fun wrapFixedHeader(firstByte: Int, payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(payload.size + 5)
            out.write(firstByte)
            var x = payload.size
            do {
                var encoded = x and 0x7F
                x = x ushr 7
                if (x > 0) encoded = encoded or 0x80
                out.write(encoded)
            } while (x > 0)
            out.write(payload)
            return out.toByteArray()
        }

        /**
         * Universal parser for 5T MaTO `/scre` MQTT vehicle position messages:
         * - Automatic GZIP decompression (`1F 8B`)
         * - 5T native `MQTTPositionUpdate` JSON (`latitude`/`longitude`/`heading`/`speed`/`direct`/`tripId`/`nextStop`/`veh`/`line`)
         * - Digitransit HFP nested `{"VP": {...}}` / `{"vp": {...}}` JSON
         * - JSON arrays `[ {...}, {...} ]`, GeoJSON `{"features":[...]}`, wrapped envelopes, 1-level & 2-level vehicle maps
         * - Compact CSV / delimited text payloads
         * - Topic-derived route/direction/vehicleId fallbacks (`/12U/0/9037`, `/map/v1/eta/12U/9037`, `/gtt/12U/9037`, `/gtfsrt/vp/...`)
         * - Binary GTFS-RT protobuf fallback
         */
        internal fun parseMatoMessage(
            topic: String,
            payload: ByteArray,
            nowMillis: Long = System.currentTimeMillis(),
            isRetained: Boolean = false,
        ): List<BusVehicle> {
            val parsed = parseMatoMessageRaw(topic, payload, nowMillis)
            return if (!isRetained || parsed.isEmpty()) {
                parsed
            } else {
                parsed.map { it.copy(isMqttRetained = true) }
            }
        }

        private fun parseMatoMessageRaw(
            topic: String,
            payload: ByteArray,
            nowMillis: Long,
        ): List<BusVehicle> {
            if (payload.isEmpty()) return emptyList()
            val effectivePayload = if (
                payload.size >= 2 &&
                (payload[0].toInt() and 0xFF) == 0x1F &&
                (payload[1].toInt() and 0xFF) == 0x8B
            ) {
                runCatching {
                    GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
                }.getOrDefault(payload)
            } else {
                payload
            }
            if (effectivePayload.isEmpty()) return emptyList()

            val firstNonSpace = effectivePayload.firstOrNull {
                it != ' '.code.toByte() &&
                    it != '\n'.code.toByte() &&
                    it != '\r'.code.toByte() &&
                    it != '\t'.code.toByte()
            } ?: return emptyList()

            if (firstNonSpace == '{'.code.toByte() || firstNonSpace == '['.code.toByte()) {
                val text = String(effectivePayload, StandardCharsets.UTF_8)
                val jsonVehicles = parseMatoJsonRoot(topic, text, nowMillis)
                if (jsonVehicles.isNotEmpty()) return jsonVehicles
            }

            // Fallback 1: plain-text CSV/delimited position line (e.g. "9037,12U,45.0703,7.6869,180")
            if (isLikelyAsciiText(effectivePayload)) {
                val text = String(effectivePayload, StandardCharsets.UTF_8).trim()
                val csvVehicles = parseDelimitedTextPayload(topic, text, nowMillis)
                if (csvVehicles.isNotEmpty()) return csvVehicles
            }

            // Fallback 2: binary GTFS-RT protobuf (FeedMessage, bare FeedEntity, or bare VehiclePosition)
            val topicMeta = extractTopicMetadata(topic.trim('/').split('/').filter { it.isNotBlank() })
            return runCatching {
                GtfsRtProtobufParser.parseSingleVehicleMessage(
                    bytes = effectivePayload,
                    fallbackRouteId = topicMeta.routeId,
                    fallbackVehicleId = topicMeta.vehicleId,
                    sourceName = "MaTO Live",
                    fetchedAtMillis = nowMillis,
                )
            }.getOrDefault(emptyList())
        }

        private fun isLikelyAsciiText(bytes: ByteArray): Boolean {
            val limit = bytes.size.coerceAtMost(96)
            for (i in 0 until limit) {
                val b = bytes[i].toInt() and 0xFF
                if (b != 9 && b != 10 && b != 13 && (b < 32 || b > 126)) return false
            }
            return true
        }

        private fun parseDelimitedTextPayload(
            topic: String,
            text: String,
            nowMillis: Long,
        ): List<BusVehicle> {
            if (text.length < 8) return emptyList()
            val topicMeta = extractTopicMetadata(topic.trim('/').split('/').filter { it.isNotBlank() })
            val out = mutableListOf<BusVehicle>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty()) continue
                val delim = when {
                    line.contains(';') -> ';'
                    line.contains('|') -> '|'
                    line.contains(',') -> ','
                    else -> continue
                }
                val tokens = line.split(delim).map { it.trim().trim('"', '[', ']') }
                if (tokens.size < 2) continue
                var lat: Double? = null
                var lon: Double? = null
                var latIdx = -1
                var lonIdx = -1
                tokens.forEachIndexed { idx, tok ->
                    val d = tok.toDoubleOrNull() ?: return@forEachIndexed
                    if (lat == null && d in 44.0..46.5) {
                        lat = d
                        latIdx = idx
                    } else if (lon == null && d in 6.5..9.0) {
                        lon = d
                        lonIdx = idx
                    }
                }
                val fLat = lat ?: continue
                val fLon = lon ?: continue
                val nonCoord = tokens.filterIndexed { idx, s ->
                    idx != latIdx && idx != lonIdx && s.isNotBlank()
                }
                val route = topicMeta.routeId
                    ?: nonCoord.firstOrNull { isPlausibleRouteToken(it) }
                    ?: continue
                val normRoute = GtfsRtProtobufParser.normalizeGttLineCode(route)
                if (normRoute == "?" || normRoute.isBlank()) continue
                val rawVehId = topicMeta.vehicleId
                    ?: nonCoord.firstOrNull { !it.equals(route, ignoreCase = true) && it.any(Char::isDigit) }
                    ?: continue
                val vehId = GtfsRtProtobufParser.canonicalFleetId(rawVehId)
                if (vehId.isEmpty()) continue
                val normTripId = GtfsRtProtobufParser.normalizeTripId(topicMeta.tripId)
                val heading = nonCoord
                    .mapNotNull { it.toFloatOrNull() }
                    .firstOrNull { it in 0f..360f && it.toString() != vehId }
                out.add(
                    BusVehicle(
                        entityId = "mato_${vehId}_$normRoute",
                        vehicleId = vehId,
                        label = vehId,
                        rawRouteId = route.trim().uppercase(),
                        lineCode = normRoute,
                        tripId = normTripId,
                        directionId = topicMeta.directionId,
                        latitude = fLat,
                        longitude = fLon,
                        bearing = heading,
                        speedKmh = null,
                        timestampSeconds = nowMillis / 1000L,
                        stopId = topicMeta.nextStopId,
                        status = VehicleStopStatus.IN_TRANSIT_TO,
                        sourceName = "MaTO Live",
                        contributingSources = setOf("MaTO Live"),
                        nextStopId = topicMeta.nextStopId,
                        fetchedAtMillis = nowMillis,
                        hasExplicitTimestamp = false,
                    ),
                )
            }
            return out
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseMatoJsonRoot(
            topic: String,
            jsonText: String,
            nowMillis: Long,
        ): List<BusVehicle> {
            val parsed = SimpleJsonParser(jsonText).parseValue() ?: return emptyList()
            val topicMeta = extractTopicMetadata(topic.trim('/').split('/').filter { it.isNotBlank() })

            return when (parsed) {
                is List<*> -> parseJsonList(parsed, topicMeta, null, nowMillis)
                is Map<*, *> -> parseJsonMap(parsed as Map<String, Any?>, topicMeta, null, nowMillis)
                else -> emptyList()
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseJsonList(
            list: List<*>,
            topicMeta: TopicMeta,
            routeHint: String?,
            nowMillis: Long,
        ): List<BusVehicle> {
            // Fast path: 5T MaTO `/scre` native flat positional JSONArray payload:
            // [longitude, latitude, heading, speed, tripId, direct, nextStop, full]
            // (also supports [latitude, longitude, ...] and optional leading vehicle/route tokens)
            parse5tPositionalArray(
                list = list,
                topicMeta = topicMeta,
                keyedVehicleHint = null,
                routeHint = routeHint,
                nowMillis = nowMillis,
            )?.let { return listOf(it) }

            val out = mutableListOf<BusVehicle>()
            for (item in list) {
                when (item) {
                    is Map<*, *> -> {
                        buildVehicleFromJsonMap(
                            root = item as Map<String, Any?>,
                            topicMeta = topicMeta,
                            keyedVehicleHint = null,
                            routeHint = routeHint,
                            nowMillis = nowMillis,
                        )?.let { out.add(it) }
                    }
                    is List<*> -> {
                        val posVehicle = parse5tPositionalArray(
                            list = item,
                            topicMeta = topicMeta,
                            keyedVehicleHint = null,
                            routeHint = routeHint,
                            nowMillis = nowMillis,
                        )
                        if (posVehicle != null) {
                            out.add(posVehicle)
                        } else {
                            // Generic tuple fallback e.g. ["9037", "12U", 45.07, 7.68, 180]
                            val joined = item.joinToString(",") { it?.toString().orEmpty() }
                            out.addAll(parseDelimitedTextPayload("", joined, nowMillis))
                        }
                    }
                }
            }
            return out
        }

        /**
         * Parses 5T / Muoversi a Torino (MaTO) `/scre` flat positional JSONArray payloads:
         * `[lon, lat, heading, speed, tripId, direct, nextStop, full]` or `[lat, lon, ...]`.
         * Turin latitude (`44.0..46.5`) and longitude (`6.5..9.0`) are strictly disjoint,
         * so coordinate order is auto-detected unambiguously.
         */
        private fun parse5tPositionalArray(
            list: List<*>,
            topicMeta: TopicMeta,
            keyedVehicleHint: String?,
            routeHint: String?,
            nowMillis: Long,
        ): BusVehicle? {
            if (list.size < 2) return null
            if (list[0] is Map<*, *> || list[0] is List<*>) return null

            // Locate the first adjacent (lat, lon) or (lon, lat) Turin coordinate pair
            var coordStart = -1
            var lat = Double.NaN
            var lon = Double.NaN
            val maxStart = (list.size - 2).coerceAtMost(2)
            for (i in 0..maxStart) {
                val a = list[i]?.takeUnless { it is Boolean }?.toString()?.trim()?.toDoubleOrNull()
                val b = list[i + 1]?.takeUnless { it is Boolean }?.toString()?.trim()?.toDoubleOrNull()
                if (a == null || b == null || !a.isFinite() || !b.isFinite()) continue
                when {
                    a in 44.0..46.5 && b in 6.5..9.0 -> {
                        lat = a
                        lon = b
                        coordStart = i
                        break
                    }
                    b in 44.0..46.5 && a in 6.5..9.0 -> {
                        lon = a
                        lat = b
                        coordStart = i
                        break
                    }
                }
            }
            if (coordStart < 0) return null

            val leadingTokens = (0 until coordStart)
                .mapNotNull { list[it]?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() && !s.equals("null", ignoreCase = true) } }
            val leadingRoute = leadingTokens.firstOrNull { isPlausibleRouteToken(it) }
            val leadingVeh = leadingTokens.firstOrNull { !it.equals(leadingRoute, ignoreCase = true) && it.any(Char::isDigit) }

            fun doubleAt(offset: Int): Double? {
                val raw = list.getOrNull(coordStart + offset) ?: return null
                if (raw is Boolean || raw is Map<*, *> || raw is List<*>) return null
                return raw.toString().trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            }

            fun cleanStrAt(offset: Int): String? {
                val raw = list.getOrNull(coordStart + offset) ?: return null
                if (raw is Boolean || raw is Map<*, *> || raw is List<*>) return null
                val s = raw.toString().trim()
                if (s.isEmpty() || s.equals("null", ignoreCase = true) || s == "-1") return null
                return s
            }

            val heading = doubleAt(2)?.toFloat()?.takeIf { it in 0f..360f }
            val speedKmh = doubleAt(3)?.toFloat()?.takeIf { it in 0f..150f }
            val tripId = GtfsRtProtobufParser.normalizeTripId(cleanStrAt(4))
                ?: GtfsRtProtobufParser.normalizeTripId(topicMeta.tripId)

            val directRaw = list.getOrNull(coordStart + 5)
            val dirId = when (directRaw) {
                is Boolean -> if (directRaw) 1 else 0
                else -> doubleAt(5)?.toInt()?.let { raw ->
                    when (raw) {
                        0, 1 -> raw
                        2 -> 1
                        else -> null
                    }
                }
            } ?: topicMeta.directionId

            val nextStop = cleanStrAt(6)
                ?.substringAfterLast(":")
                ?.takeIf { it.isNotBlank() && it != "0" }
                ?: topicMeta.nextStopId

            val rawRoute = routeHint?.substringAfterLast(":")?.takeIf { it.isNotBlank() }
                ?: topicMeta.routeId
                ?: leadingRoute?.substringAfterLast(":")
                ?: extractRouteFromTripId(tripId)
                ?: return null
            val normalizedLine = GtfsRtProtobufParser.normalizeGttLineCode(rawRoute)
            if (normalizedLine == "?" || normalizedLine.isBlank()) return null

            val rawVehId = keyedVehicleHint?.substringAfterLast(":")?.takeIf { it.isNotBlank() }
                ?: topicMeta.vehicleId
                ?: leadingVeh?.substringAfterLast(":")
                ?: tripId?.takeIf { it.isNotBlank() }
                ?: return null
            val cleanVehId = GtfsRtProtobufParser.canonicalFleetId(rawVehId)
            if (cleanVehId.isEmpty()) return null

            val nowSec = nowMillis / 1000L
            val explicitTs = doubleAt(7)?.toLong()?.let { raw ->
                val sec = if (raw > 1_000_000_000_000L) raw / 1000L else raw
                sec.takeIf { it in 1_600_000_000L..(nowSec + 120L) }
            }

            return BusVehicle(
                entityId = "mato_${cleanVehId}_$normalizedLine",
                vehicleId = cleanVehId,
                label = cleanVehId,
                rawRouteId = rawRoute.trim().uppercase(),
                lineCode = normalizedLine,
                tripId = tripId,
                directionId = dirId,
                latitude = lat,
                longitude = lon,
                bearing = heading,
                speedKmh = speedKmh,
                timestampSeconds = explicitTs ?: nowSec,
                stopId = nextStop,
                status = VehicleStopStatus.IN_TRANSIT_TO,
                sourceName = "MaTO Live",
                contributingSources = setOf("MaTO Live"),
                nextStopId = nextStop,
                fetchedAtMillis = nowMillis,
                hasExplicitTimestamp = explicitTs != null,
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseJsonMap(
            root: Map<String, Any?>,
            topicMeta: TopicMeta,
            routeHint: String?,
            nowMillis: Long,
        ): List<BusVehicle> {
            // 1. GeoJSON FeatureCollection or list/map envelopes
            val listEnvelopeKeys = listOf("features", "vehicles", "positions", "buses", "entities", "data", "items", "list", "payload")
            for (envKey in listEnvelopeKeys) {
                val value = root[envKey] ?: continue
                when (value) {
                    is List<*> -> {
                        val listRes = parseJsonList(value, topicMeta, routeHint, nowMillis)
                        if (listRes.isNotEmpty()) return listRes
                    }
                    is Map<*, *> -> {
                        // Could be a single vehicle wrapper (e.g. {"data": {"latitude": ...}}) OR a keyed map ({"vehicles": {"9037": {...}}})
                        val mapRes = parseJsonMap(value as Map<String, Any?>, topicMeta, routeHint, nowMillis)
                        if (mapRes.isNotEmpty()) return mapRes
                    }
                }
            }

            // 2. Single vehicle object (flat or {"VP":{...}} or {"vehicle":{...}} or {"position":{...}})
            buildVehicleFromJsonMap(root, topicMeta, null, routeHint, nowMillis)?.let { return listOf(it) }

            // 3. 1-level keyed map of vehicles e.g. {"9037": {"lat":..., "lon":...}} or {"9037": [lon, lat, ...]}
            //    OR 2-level map by route -> vehicle e.g. {"12U": {"9037": {...} or [lon, lat, ...]}}
            val keyedOut = mutableListOf<BusVehicle>()
            for ((k, v) in root) {
                when (v) {
                    is Map<*, *> -> {
                        val subMap = v as Map<String, Any?>
                        val direct = buildVehicleFromJsonMap(subMap, topicMeta, k, routeHint, nowMillis)
                        if (direct != null) {
                            keyedOut.add(direct)
                        } else if (isPlausibleRouteToken(k)) {
                            // 2-level map: outer key is line code (e.g. "12U"), inner keys are vehicle IDs
                            for ((innerKey, innerVal) in subMap) {
                                when (innerVal) {
                                    is Map<*, *> -> buildVehicleFromJsonMap(
                                        root = innerVal as Map<String, Any?>,
                                        topicMeta = topicMeta,
                                        keyedVehicleHint = innerKey,
                                        routeHint = k,
                                        nowMillis = nowMillis,
                                    )?.let { keyedOut.add(it) }
                                    is List<*> -> parse5tPositionalArray(
                                        list = innerVal,
                                        topicMeta = topicMeta,
                                        keyedVehicleHint = innerKey,
                                        routeHint = k,
                                        nowMillis = nowMillis,
                                    )?.let { keyedOut.add(it) }
                                }
                            }
                        }
                    }
                    is List<*> -> {
                        val directPos = parse5tPositionalArray(
                            list = v,
                            topicMeta = topicMeta,
                            keyedVehicleHint = k,
                            routeHint = routeHint,
                            nowMillis = nowMillis,
                        )
                        if (directPos != null) {
                            keyedOut.add(directPos)
                        } else if (isPlausibleRouteToken(k)) {
                            keyedOut.addAll(parseJsonList(v, topicMeta, k, nowMillis))
                        }
                    }
                }
            }
            return keyedOut
        }

        @Suppress("UNCHECKED_CAST")
        private fun buildVehicleFromJsonMap(
            root: Map<String, Any?>,
            topicMeta: TopicMeta,
            keyedVehicleHint: String?,
            routeHint: String?,
            nowMillis: Long,
        ): BusVehicle? {
            val fields = LinkedHashMap<String, String>()
            fun collect(map: Map<String, Any?>, allowOverwrite: Boolean = true) {
                for ((k, v) in map) {
                    if (v != null && v !is Map<*, *> && v !is List<*>) {
                        val lower = k.lowercase(Locale.ROOT)
                        if (allowOverwrite || !fields.containsKey(lower)) {
                            fields[lower] = v.toString()
                        }
                    }
                }
            }
            collect(root)
            for (nestedKey in listOf("properties", "VP", "vp", "vehicle", "position", "pos", "location", "loc", "coord", "coords", "data", "payload")) {
                (root[nestedKey] as? Map<String, Any?>)?.let { collect(it) }
            }
            // Extract nested trip/stop/route metadata without clobbering vehicle's own `id`
            val nestedTrip = root["trip"] as? Map<String, Any?>
            val nestedTripId = nestedTrip?.let {
                (it["gtfsId"] ?: it["tripId"] ?: it["trip_id"] ?: it["id"])?.toString()
            }
            val nestedRoute = root["route"] as? Map<String, Any?>
            val nestedRouteId = nestedRoute?.let {
                (it["shortName"] ?: it["short_name"] ?: it["gtfsId"] ?: it["id"] ?: it["name"])?.toString()
            }

            // Support GeoJSON geometry.coordinates or top-level coordinate arrays ([lon, lat] or [lat, lon])
            var geoLat: Double? = null
            var geoLon: Double? = null
            val coordArray = ((root["geometry"] as? Map<String, Any?>)?.get("coordinates") as? List<*>)
                ?: (root["coordinates"] as? List<*>)
                ?: (root["coords"] as? List<*>)
                ?: (root["position"] as? List<*>)
                ?: (root["pos"] as? List<*>)
                ?: (root["location"] as? List<*>)
            if (coordArray != null && coordArray.size >= 2) {
                val c0 = coordArray[0]?.toString()?.toDoubleOrNull()
                val c1 = coordArray[1]?.toString()?.toDoubleOrNull()
                if (c0 != null && c1 != null) {
                    when {
                        c0 in 44.0..46.5 && c1 in 6.5..9.0 -> {
                            geoLat = c0
                            geoLon = c1
                        }
                        c1 in 44.0..46.5 && c0 in 6.5..9.0 -> {
                            geoLon = c0
                            geoLat = c1
                        }
                    }
                }
            }

            val lat = readDouble(fields, "latitude", "lat", "latitudine", "y") ?: geoLat ?: return null
            val lon = readDouble(fields, "longitude", "lon", "long", "lng", "longitudine", "x") ?: geoLon ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in 44.0..46.5 || lon !in 6.5..9.0) return null

            val tripId = GtfsRtProtobufParser.normalizeTripId(
                readString(fields, "tripid", "trip_id", "trip", "corsa", "journeyid")
                    ?: nestedTripId
                    ?: topicMeta.tripId,
            )

            val rawRoute = readString(
                fields,
                "line",
                "linea",
                "linecode",
                "line_code",
                "lineid",
                "line_id",
                "codlinea",
                "codicelinea",
                "numlinea",
                "route",
                "routeid",
                "route_id",
                "routeshortname",
                "route_short_name",
                "routename",
                "desi",
                "shortname",
                "l",
            )?.substringAfterLast(":")
                ?: nestedRouteId?.substringAfterLast(":")?.takeIf { it.isNotBlank() }
                ?: routeHint?.substringAfterLast(":")?.takeIf { it.isNotBlank() }
                ?: topicMeta.routeId
                ?: extractRouteFromTripId(tripId)
                ?: return null
            val normalizedLine = GtfsRtProtobufParser.normalizeGttLineCode(rawRoute)
            if (normalizedLine == "?" || normalizedLine.isBlank()) return null

            val rawVehId = readString(
                fields,
                "veh",
                "vehicleid",
                "vehicle_id",
                "num",
                "id",
                "label",
                "vehicle",
                "matr",
                "matricola",
                "vid",
                "bus",
                "busid",
                "bus_id",
                "fleet",
                "fleetid",
                "code",
                "plate",
                "v",
            )?.substringAfterLast(":")
                ?: keyedVehicleHint?.substringAfterLast(":")
                ?: topicMeta.vehicleId
                ?: tripId?.takeIf { it.isNotBlank() }
                ?: return null
            val cleanVehId = GtfsRtProtobufParser.canonicalFleetId(rawVehId)
            if (cleanVehId.isEmpty()) return null

            val bearing = readDouble(fields, "heading", "bearing", "hdg", "dir_deg", "angle", "course", "orientamento")
                ?.toFloat()
                ?.takeIf { it.isFinite() && it >= 0f && it <= 360f }

            val rawSpeed = readDouble(fields, "speed", "spd", "velocita")
            val speedKmh = rawSpeed?.toFloat()?.takeIf { it.isFinite() && it >= 0f }?.let { s ->
                if (fields.containsKey("spd") && s <= 25f) s * 3.6f else s
            }

            val dirId = readInt(fields, "direct", "directionid", "direction_id", "direction", "dir", "verso")
                ?.let { raw ->
                    when (raw) {
                        0, 1 -> raw
                        2 -> 1
                        else -> null
                    }
                }
                ?: topicMeta.directionId

            val nextStop = readString(fields, "nextstop", "next_stop", "stop", "stopid", "stop_id", "fermata", "prossimafermata")
                ?.substringAfterLast(":")
                ?: topicMeta.nextStopId

            val explicitTs = parseExplicitTimestampSeconds(fields, nowMillis)
            val tsSeconds = explicitTs ?: (nowMillis / 1000L)

            return BusVehicle(
                entityId = "mato_${cleanVehId}_$normalizedLine",
                vehicleId = cleanVehId,
                label = cleanVehId,
                rawRouteId = rawRoute.trim().uppercase(),
                lineCode = normalizedLine,
                tripId = tripId,
                directionId = dirId,
                latitude = lat,
                longitude = lon,
                bearing = bearing,
                speedKmh = speedKmh,
                timestampSeconds = tsSeconds,
                stopId = nextStop,
                status = VehicleStopStatus.IN_TRANSIT_TO,
                sourceName = "MaTO Live",
                contributingSources = setOf("MaTO Live"),
                nextStopId = nextStop,
                fetchedAtMillis = nowMillis,
                hasExplicitTimestamp = explicitTs != null,
            )
        }

        private fun isPlausibleRouteToken(raw: String): Boolean {
            val clean = raw.substringAfterLast(":").trim()
            if (clean.isEmpty() || clean.length > 8) return false
            val lower = clean.lowercase(Locale.ROOT)
            if (lower in TOPIC_NON_ROUTE_SEGMENTS) return false
            if (clean.uppercase(Locale.ROOT) in KNOWN_ALPHA_LINES) return true
            // Skip version tags like v1, v2, v10 and long pure-numeric vehicle/trip IDs (>= 4 digits)
            if (lower.startsWith("v") && lower.drop(1).all { it.isDigit() }) return false
            if (clean.length >= 4 && clean.all { it.isDigit() }) return false
            return clean.length in 1..6 && clean.any { it.isDigit() }
        }

        private fun extractRouteFromTripId(tripId: String?): String? {
            if (tripId.isNullOrBlank()) return null
            val clean = tripId.substringAfterLast(":").trim()
            val prefix = clean.substringBefore("_").substringBefore("-").trim()
            return prefix.takeIf { prefix != clean && isPlausibleRouteToken(it) }
        }

        private data class TopicMeta(
            val routeId: String? = null,
            val directionId: Int? = null,
            val vehicleId: String? = null,
            val tripId: String? = null,
            val nextStopId: String? = null,
        )

        private fun extractTopicMetadata(parts: List<String>): TopicMeta {
            if (parts.isEmpty()) return TopicMeta()
            // Case 1: Digitransit GTFS-RT MQTT: gtfsrt/vp/{feed}/{agency}/{mode}/{route}/{dir}/{headsign}/{trip}/{nextStop}/{start}/{veh}/...
            if (parts.size >= 6 && parts[0].equals("gtfsrt", ignoreCase = true) && parts[1].equals("vp", ignoreCase = true)) {
                return TopicMeta(
                    routeId = parts.getOrNull(5)?.substringAfterLast(":")?.takeIf { it.isNotBlank() && it != "+" },
                    directionId = parts.getOrNull(6)?.toIntOrNull()?.takeIf { it == 0 || it == 1 },
                    tripId = parts.getOrNull(8)?.substringAfterLast(":")?.takeIf { it.isNotBlank() },
                    nextStopId = parts.getOrNull(9)?.substringAfterLast(":")?.takeIf { it.isNotBlank() },
                    vehicleId = parts.getOrNull(11)?.substringAfterLast(":")?.takeIf { it.isNotBlank() },
                )
            }
            // Case 2: Digitransit HFP v2: hfp/v2/journey/ongoing/vp/{mode}/{op}/{veh}/{route}/{dir}/{headsign}/{start}/{nextStop}/...
            if (parts.size >= 10 && parts[0].equals("hfp", ignoreCase = true)) {
                val rawDir = parts.getOrNull(9)?.toIntOrNull()
                return TopicMeta(
                    vehicleId = parts.getOrNull(7)?.substringAfterLast(":")?.takeIf { it.isNotBlank() },
                    routeId = parts.getOrNull(8)?.substringAfterLast(":")?.takeIf { it.isNotBlank() },
                    directionId = when (rawDir) {
                        0, 1 -> rawDir
                        2 -> 1
                        else -> null
                    },
                    nextStopId = parts.getOrNull(12)?.substringAfterLast(":")?.takeIf { it.isNotBlank() },
                )
            }
            // Case 3: 5T MaTO compact/prefixed topic e.g. /12U, /12U/9037, /12U/0/9037, /gtt/12U/9037, /map/v1/eta/12U/9037
            val routeIdx = parts.indexOfFirst { seg -> isPlausibleRouteToken(seg) }
            if (routeIdx >= 0) {
                val routeId = parts[routeIdx].substringAfterLast(":")
                val tail = parts.subList(routeIdx + 1, parts.size)
                return when (tail.size) {
                    0 -> {
                        val prevVeh = if (routeIdx > 0) {
                            val prev = parts[routeIdx - 1].substringAfterLast(":")
                            prev.takeIf {
                                it.lowercase(Locale.ROOT) !in TOPIC_NON_ROUTE_SEGMENTS && it.any(Char::isDigit)
                            }
                        } else {
                            null
                        }
                        TopicMeta(routeId = routeId, vehicleId = prevVeh)
                    }
                    1 -> TopicMeta(routeId = routeId, vehicleId = tail[0].substringAfterLast(":"))
                    else -> {
                        val firstTailInt = tail[0].toIntOrNull()
                        if (firstTailInt != null && firstTailInt in 0..2) {
                            TopicMeta(
                                routeId = routeId,
                                directionId = if (firstTailInt == 2) 1 else firstTailInt,
                                vehicleId = tail[1].substringAfterLast(":"),
                            )
                        } else {
                            // Prefer the last short numeric/alphanumeric fleet ID segment (avoid mistaking a long tripId for vehId)
                            val vehSeg = tail.lastOrNull { seg ->
                                val c = seg.substringAfterLast(":")
                                c.length in 2..6 && c.any(Char::isDigit)
                            } ?: tail.last()
                            TopicMeta(
                                routeId = routeId,
                                vehicleId = vehSeg.substringAfterLast(":"),
                            )
                        }
                    }
                }
            }
            // Case 4: 4-digit suburban/extraurban GTT line topics like /1085/9037 or /gtt/1510/3301
            val significant = parts.filter { seg ->
                seg.substringAfterLast(":").lowercase(Locale.ROOT) !in TOPIC_NON_ROUTE_SEGMENTS
            }
            if (significant.size >= 2) {
                return TopicMeta(
                    routeId = significant[0].substringAfterLast(":"),
                    vehicleId = significant[1].substringAfterLast(":"),
                )
            }
            return TopicMeta()
        }

        private class SimpleJsonParser(private val s: String) {
            private var pos = 0

            fun parseValue(): Any? {
                skipWs()
                if (pos >= s.length) return null
                return when (s[pos]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't', 'f' -> parseBoolean()
                    'n' -> parseNull()
                    else -> parseNumber()
                }
            }

            private fun parseObject(): Map<String, Any?>? {
                if (pos >= s.length || s[pos] != '{') return null
                pos++
                val map = LinkedHashMap<String, Any?>()
                skipWs()
                if (pos < s.length && s[pos] == '}') {
                    pos++
                    return map
                }
                while (pos < s.length) {
                    skipWs()
                    val key = parseString() ?: return null
                    skipWs()
                    if (pos >= s.length || s[pos] != ':') return null
                    pos++
                    val value = parseValue()
                    map[key] = value
                    skipWs()
                    if (pos >= s.length) break
                    when (s[pos]) {
                        ',' -> pos++
                        '}' -> {
                            pos++
                            return map
                        }
                        else -> return null
                    }
                }
                return map
            }

            private fun parseArray(): List<Any?>? {
                if (pos >= s.length || s[pos] != '[') return null
                pos++
                val list = ArrayList<Any?>()
                skipWs()
                if (pos < s.length && s[pos] == ']') {
                    pos++
                    return list
                }
                while (pos < s.length) {
                    list.add(parseValue())
                    skipWs()
                    if (pos >= s.length) break
                    when (s[pos]) {
                        ',' -> pos++
                        ']' -> {
                            pos++
                            return list
                        }
                        else -> return null
                    }
                }
                return list
            }

            private fun parseString(): String? {
                if (pos >= s.length || s[pos] != '"') return null
                pos++
                val sb = StringBuilder()
                while (pos < s.length) {
                    val ch = s[pos++]
                    if (ch == '"') return sb.toString()
                    if (ch == '\\' && pos < s.length) {
                        when (val esc = s[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> if (pos + 4 <= s.length) {
                                val hex = s.substring(pos, pos + 4)
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                pos += 4
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(ch)
                    }
                }
                return null
            }

            private fun parseBoolean(): Boolean? {
                return when {
                    s.startsWith("true", pos) -> {
                        pos += 4
                        true
                    }
                    s.startsWith("false", pos) -> {
                        pos += 5
                        false
                    }
                    else -> null
                }
            }

            private fun parseNull(): Any? {
                if (s.startsWith("null", pos)) pos += 4
                return null
            }

            private fun parseNumber(): String? {
                val start = pos
                while (pos < s.length && s[pos] in "0123456789+-.eE") {
                    pos++
                }
                return if (pos > start) s.substring(start, pos) else null
            }

            private fun skipWs() {
                while (pos < s.length && s[pos] <= ' ') pos++
            }
        }

        private fun readDouble(fields: Map<String, String>, vararg keys: String): Double? {
            for (k in keys) {
                fields[k]?.trim()?.toDoubleOrNull()?.let { return it }
            }
            return null
        }

        private fun readInt(fields: Map<String, String>, vararg keys: String): Int? {
            for (k in keys) {
                val raw = fields[k]?.trim() ?: continue
                raw.toIntOrNull()?.let { return it }
                raw.toDoubleOrNull()?.toInt()?.let { return it }
            }
            return null
        }

        private fun readString(fields: Map<String, String>, vararg keys: String): String? {
            for (k in keys) {
                val s = fields[k]?.trim()
                if (!s.isNullOrEmpty() && !s.equals("null", ignoreCase = true)) {
                    return s
                }
            }
            return null
        }

        private fun parseExplicitTimestampSeconds(fields: Map<String, String>, nowMillis: Long): Long? {
            val nowSec = nowMillis / 1000L
            for (k in listOf("tsi", "timestamp", "ts", "lastupdated", "last_updated", "time", "t")) {
                val rawStr = fields[k]?.trim() ?: continue
                val raw = rawStr.toLongOrNull() ?: rawStr.toDoubleOrNull()?.toLong() ?: continue
                val sec = if (raw > 1_000_000_000_000L) raw / 1000L else raw
                if (sec in 1_600_000_000L..(nowSec + 120L)) return sec
            }
            val iso = readString(fields, "tst", "lastupdate", "last_update", "updatedat", "updated_at")
            if (iso != null) {
                runCatching { OffsetDateTime.parse(iso).toEpochSecond() }
                    .getOrNull()
                    ?.takeIf { it in 1_600_000_000L..(nowSec + 120L) }
                    ?.let { return it }
            }
            return null
        }

        internal fun isRegressiveTimestamp(existingTsSec: Long, incomingTsSec: Long): Boolean {
            return existingTsSec > 0L && incomingTsSec > 0L && incomingTsSec < existingTsSec - 5L
        }

        internal fun isRegressiveTimestamp(prev: BusVehicle, candidate: BusVehicle): Boolean {
            return isRegressiveTimestamp(prev.timestampSeconds, candidate.timestampSeconds)
        }

        internal fun isImplausibleKinematicJump(prev: BusVehicle, candidate: BusVehicle): Boolean {
            if (!prev.lineCode.equals(candidate.lineCode, ignoreCase = true)) {
                return false
            }
            val distM = GttVehicleMergeEngine.haversineMeters(
                prev.latitude,
                prev.longitude,
                candidate.latitude,
                candidate.longitude,
            )
            val dtSec = if (prev.timestampSeconds > 0L && candidate.timestampSeconds > prev.timestampSeconds) {
                candidate.timestampSeconds - prev.timestampSeconds
            } else {
                ((candidate.fetchedAtMillis - prev.fetchedAtMillis) / 1000L).coerceAtLeast(1L)
            }
            return dtSec in 1L..45L &&
                distM > 380.0 &&
                ((distM / dtSec.toDouble()) * 3.6) > 145.0
        }

        internal fun acceptsKinematicUpdate(
            prev: BusVehicle,
            candidate: BusVehicle,
            pendingOutlier: BusVehicle?,
        ): Boolean {
            if (!isImplausibleKinematicJump(prev, candidate)) {
                return true
            }
            if (pendingOutlier != null) {
                val pendingDistM = GttVehicleMergeEngine.haversineMeters(
                    pendingOutlier.latitude,
                    pendingOutlier.longitude,
                    candidate.latitude,
                    candidate.longitude,
                )
                val ageSincePendingMs = candidate.fetchedAtMillis - pendingOutlier.fetchedAtMillis
                if (pendingDistM <= 220.0 && ageSincePendingMs in 0L..90_000L) {
                    return true
                }
            }
            return false
        }
    }
}
