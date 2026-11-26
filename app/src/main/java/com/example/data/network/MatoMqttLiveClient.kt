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
    private val packetIdGen = AtomicInteger(1)
    private val connectionGen = AtomicLong(0L)
    private val isActiveDesired = AtomicBoolean(false)
    private val isMqttConnected = AtomicBoolean(false)
    private val lastMessageReceivedAtMillis = AtomicLong(0L)
    private val lastParsedVehicleAtMillis = AtomicLong(0L)
    private val connectedSinceMillis = AtomicLong(0L)
    private val rawPublishCount = AtomicLong(0L)
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
    private var workingProfileIdx: Int = -1

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var keepAliveJob: Job? = null
    private var silentFallbackJob: Job? = null

    fun isConnected(): Boolean = isMqttConnected.get()

    fun lastError(): String? = lastErrorDetail

    fun lastReceivedMillis(): Long = lastMessageReceivedAtMillis.get()

    fun lastParsedVehicleMillis(): Long = lastParsedVehicleAtMillis.get()

    fun connectedSinceMillis(): Long = connectedSinceMillis.get()

    fun rawPublishCount(): Long = rawPublishCount.get()

    fun parsedVehicleCount(): Long = parsedVehicleCount.get()

    fun subAckRejectedAll(): Boolean =
        subAckRejectedCount.get() > 0 && subAckAcceptedCount.get() == 0

    fun totalCachedVehicles(): Int = vehiclesById.size

    fun lastSampleTopic(): String? = lastSampleTopic

    fun start(lineCode: String, onFocusedUpdate: () -> Unit) {
        val norm = GtfsRtProtobufParser.normalizeGttLineCode(lineCode)
        if (norm.isEmpty() || norm == "?") return
        focusedLine = norm
        onFocusedLineUpdate = onFocusedUpdate
        val wasActive = isActiveDesired.getAndSet(true)
        scope.launch {
            if (!wasActive || activeWebSocket == null) {
                reconnectAttempt = 0
                openSocket()
            } else if (isMqttConnected.get()) {
                subscribeForLine(activeWebSocket, focusedLine)
            }
        }
    }

    fun setFocusedLine(lineCode: String) {
        val norm = GtfsRtProtobufParser.normalizeGttLineCode(lineCode)
        if (norm.isEmpty() || norm == "?" || norm == focusedLine) return
        focusedLine = norm
        if (isMqttConnected.get()) {
            val ws = activeWebSocket
            val gen = connectionGen.get()
            scope.launch {
                subscribeForLine(ws, norm)
                val hasLineCached = vehiclesById.values.any {
                    it.lineCode.equals(norm, ignoreCase = true) ||
                        it.rawRouteId.equals(norm, ignoreCase = true)
                }
                if (!hasLineCached && ws != null && isMqttConnected.get() && connectionGen.get() == gen) {
                    scheduleSilentTopicFallback(ws, gen, onlyIfNoParsedSince = System.currentTimeMillis())
                }
            }
        }
    }

    @Volatile
    private var lastNudgeMillis: Long = 0L

    /**
     * Self-healing liveness check called during periodic foreground polls:
     * if the MQTT WebSocket silently stalled after a Wi-Fi/5G switch or screen sleep,
     * force a clean reconnect so live MaTO telemetry resumes automatically.
     */
    fun ensureHealthyConnection(nowMillis: Long = System.currentTimeMillis()) {
        if (!isActiveDesired.get()) return
        val ws = activeWebSocket
        val lastParsed = lastParsedVehicleAtMillis.get()
        val lastRaw = lastMessageReceivedAtMillis.get()
        val lastActivity = maxOf(lastParsed, lastRaw)
        val connSince = connectedSinceMillis.get()
        val stalled = isMqttConnected.get() &&
            connSince > 0L &&
            (nowMillis - connSince) > 65_000L &&
            (lastActivity == 0L || (nowMillis - lastActivity) > 70_000L)
        if (ws == null || stalled) {
            scope.launch {
                if (!isActiveDesired.get()) return@launch
                reconnectAttempt = 0
                openSocket()
            }
        }
    }

    /**
     * Non-destructive subscription nudge: NEVER kills a freshly connected WebSocket before
     * `scheduleSilentTopicFallback` finishes! Only re-subscribes while connected, or reconnects
     * if disconnected / truly silent for >= 25s after connect.
     */
    fun nudgeFocusedLineSubscription(
        lineCode: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        if (!isActiveDesired.get()) return
        if (nowMillis - lastNudgeMillis < 25_000L) return
        lastNudgeMillis = nowMillis

        val targetLine = lineCode?.let { GtfsRtProtobufParser.normalizeGttLineCode(it) }
            ?.takeIf { it.isNotEmpty() && it != "?" }
            ?: focusedLine
        focusedLine = targetLine

        val ws = activeWebSocket
        val connSince = connectedSinceMillis.get()
        val connectedAgeMs = if (connSince > 0L) nowMillis - connSince else 0L
        val lastParsed = lastParsedVehicleAtMillis.get()
        val staleAfterWarmup = connectedAgeMs >= 25_000L &&
            (lastParsed == 0L || (nowMillis - lastParsed) > 40_000L)

        scope.launch {
            if (!isActiveDesired.get()) return@launch
            if (ws != null && isMqttConnected.get() && !staleAfterWarmup) {
                subscribeForLine(ws, targetLine)
                if (connectedAgeMs >= 6_000L && lastParsed < connSince) {
                    sendBroadFallbackSubscriptions(ws)
                }
            } else if (ws == null || !isMqttConnected.get() || staleAfterWarmup) {
                if (staleAfterWarmup) {
                    // Advance handshake profile on silent-stall reconnect instead of retrying the same one forever
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
     * Returns all fresh MaTO vehicles received within the last [LIVE_TTL_MILLIS] (95 seconds),
     * pruning expired entries automatically so parked/finished buses vanish cleanly.
     */
    fun snapshotFreshVehicles(nowMillis: Long = System.currentTimeMillis()): List<BusVehicle> {
        val cutoff = nowMillis - LIVE_TTL_MILLIS
        val out = ArrayList<BusVehicle>(vehiclesById.size)
        val iterator = vehiclesById.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val v = entry.value
            if (v.fetchedAtMillis < cutoff) {
                iterator.remove()
            } else {
                out.add(v)
            }
        }
        return out
    }

    private fun openSocket() {
        if (!isActiveDesired.get()) return
        reconnectJob?.cancel()
        keepAliveJob?.cancel()
        silentFallbackJob?.cancel()
        val myGen = connectionGen.incrementAndGet()
        activeWebSocket?.cancel()
        activeWebSocket = null
        rawPublishCount.set(0L)
        parsedVehicleCount.set(0L)
        subAckAcceptedCount.set(0)
        subAckRejectedCount.set(0)
        lastSampleTopic = null

        val profileIdx = if (workingProfileIdx in HANDSHAKE_PROFILES.indices && reconnectAttempt == 0) {
            workingProfileIdx
        } else {
            reconnectAttempt % HANDSHAKE_PROFILES.size
        }
        val profile = HANDSHAKE_PROFILES[profileIdx]

        val reqBuilder = Request.Builder()
            .url(profile.url)
            .header("Origin", "https://mato.muoversiatorino.it")
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
                                    subscribeForLine(webSocket, focusedLine)
                                    startKeepAliveLoop(webSocket, myGen)
                                    scheduleSilentTopicFallback(webSocket, myGen, onlyIfNoParsedSince = nowMillis)
                                } else {
                                    lastErrorDetail = "CONNACK ${pkt.returnCode}"
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
                                } else if (accepted > 0 && lastErrorDetail == "Topic rifiutati (SUBACK)") {
                                    lastErrorDetail = null
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
                                if (pkt.topic.isNotBlank()) {
                                    lastSampleTopic = pkt.topic.take(64)
                                }
                                val parsed = parseMatoMessage(pkt.topic, pkt.payload, nowMillis)
                                if (parsed.isNotEmpty()) {
                                    lastParsedVehicleAtMillis.set(nowMillis)
                                    parsedVehicleCount.addAndGet(parsed.size.toLong())
                                    parsedAnyNow = true
                                    for (bus in parsed) {
                                        vehiclesById[bus.vehicleId] = bus
                                        if (bus.lineCode.equals(focusedLine, ignoreCase = true) ||
                                            bus.rawRouteId.equals(focusedLine, ignoreCase = true)
                                        ) {
                                            touchedFocusedLine = true
                                        }
                                    }
                                } else if (count <= 3L) {
                                    val preview = runCatching {
                                        String(pkt.payload.copyOfRange(0, pkt.payload.size.coerceAtMost(120)), StandardCharsets.UTF_8)
                                    }.getOrDefault("<bin:${pkt.payload.size}B>")
                                    Log.d(TAG, "MaTO unparsed frame #$count topic='${pkt.topic}' payload='$preview'")
                                }
                            }
                            is MqttPacket.PingResp -> Unit
                        }
                    }

                    if (justConnected) {
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
                        parsedVehicleCount.addAndGet(parsed.size.toLong())
                        var touched = false
                        for (bus in parsed) {
                            vehiclesById[bus.vehicleId] = bus
                            if (bus.lineCode.equals(focusedLine, ignoreCase = true)) touched = true
                        }
                        if (touched && nowMillis - lastNotifyMillis >= NOTIFY_DEBOUNCE_MS) {
                            lastNotifyMillis = nowMillis
                            onFocusedLineUpdate?.invoke()
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (connectionGen.get() == myGen) {
                        isMqttConnected.set(false)
                        activeWebSocket = null
                        keepAliveJob?.cancel()
                        silentFallbackJob?.cancel()
                        if (lastErrorDetail == null) {
                            lastErrorDetail = "Chiuso ($code)"
                        }
                        scheduleReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (connectionGen.get() == myGen) {
                        isMqttConnected.set(false)
                        activeWebSocket = null
                        keepAliveJob?.cancel()
                        silentFallbackJob?.cancel()
                        val codePart = response?.code?.let { "HTTP $it · " }.orEmpty()
                        lastErrorDetail = (codePart + (t.message ?: "Errore WS")).take(44)
                        Log.d(TAG, "MaTO MQTT fail [${profile.label}]: $lastErrorDetail")
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

    private fun subscribeForLine(
        webSocket: WebSocket?,
        lineCode: String,
    ) {
        val ws = webSocket ?: return
        if (lineCode.isBlank() || lineCode == "?") return
        val groups = buildLineSubscriptionGroups(lineCode)
        for (topics in groups) {
            if (topics.isEmpty()) continue
            val packetId = (packetIdGen.getAndIncrement() and 0x7FFF).coerceAtLeast(1)
            runCatching {
                ws.send(encodeSubscribePacket(packetId, topics).toByteString())
            }
        }
    }

    private fun sendBroadFallbackSubscriptions(webSocket: WebSocket) {
        val broadGroups = listOf(
            listOf("/ALL", "ALL", "/ALL/#", "ALL/#"),
            listOf("/gtfsrt/vp/#", "/hfp/v2/journey/ongoing/vp/#", "/+/#", "+/#"),
        )
        for (topics in broadGroups) {
            val pid = (packetIdGen.getAndIncrement() and 0x7FFF).coerceAtLeast(1)
            runCatching {
                webSocket.send(encodeSubscribePacket(pid, topics).toByteString())
            }
        }
    }

    /**
     * If line-scoped topics yield zero parsed vehicles for 3.5s after CONNACK (or line switch),
     * progressively add `/ALL`, `ALL`, non-root wildcards (`/+/#`, `+/#`), and finally `/#` & `#`
     * in separate packets so an ACL rejection on one topic never blocks the others.
     */
    private fun scheduleSilentTopicFallback(
        webSocket: WebSocket,
        myGen: Long,
        onlyIfNoParsedSince: Long,
    ) {
        silentFallbackJob?.cancel()
        silentFallbackJob = scope.launch {
            delay(3_500L)
            if (!isActive || connectionGen.get() != myGen || !isMqttConnected.get()) return@launch
            if (lastParsedVehicleAtMillis.get() >= onlyIfNoParsedSince) return@launch

            sendBroadFallbackSubscriptions(webSocket)

            delay(4_500L)
            if (!isActive || connectionGen.get() != myGen || !isMqttConnected.get()) return@launch
            if (lastParsedVehicleAtMillis.get() >= onlyIfNoParsedSince) return@launch

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
                    3 -> { // PUBLISH
                        val qos = (header and 0x06) ushr 1
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
                            packets.add(MqttPacket.Publish(topic, payload, qos, packetId))
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
        private const val LIVE_TTL_MILLIS = 95_000L
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
            val variants = linkedSetOf<String>().apply {
                if (!base.endsWith("U")) add("${base}U")
                add(base)
                if (base.all { it.isDigit() }) {
                    add("${base}F")
                    add("${base}E")
                }
                if (base.endsWith("/")) {
                    val bForm = base.dropLast(1) + "B"
                    add("${bForm}U")
                    add(bForm)
                } else if (base.endsWith("B") && base.dropLast(1).all { it.isDigit() }) {
                    val slashForm = base.dropLast(1) + "/"
                    add(slashForm)
                }
            }

            val group1 = mutableListOf<String>()
            val group2 = mutableListOf<String>()
            val group3 = mutableListOf<String>()

            for (v in variants) {
                // Direct 5T `/scre` exact & wildcard topics
                group1.add("/$v")
                group1.add(v)
                group1.add("/$v/+")
                group1.add("/$v/#")
                group1.add("$v/#")

                // Single-prefix GTT / 5T topics (both slash and colon conventions)
                group2.add("/gtt/$v")
                group2.add("/gtt/$v/#")
                group2.add("gtt/$v/#")
                group2.add("/+/$v/#")
                group2.add("+/$v/#")
                group2.add("/gtt:$v/#")
                group2.add("gtt:$v/#")

                // Digitransit GTFS-RT & HFP topics across 1, 2, and 3 intermediate hierarchy levels
                group3.add("/gtfsrt/vp/+/$v/#")
                group3.add("/gtfsrt/vp/+/+/$v/#")
                group3.add("/gtfsrt/vp/+/+/+/$v/#")
                group3.add("/gtfsrt/vp/+/+/+/gtt:$v/#")
                group3.add("/hfp/v2/journey/ongoing/vp/+/+/+/$v/#")
            }

            return listOf(group1.distinct(), group2.distinct(), group3.distinct())
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

            // Fallback 2: binary GTFS-RT protobuf FeedMessage over MQTT
            return runCatching {
                GtfsRtProtobufParser.parseFeedMessage(effectivePayload, "MaTO Live", nowMillis).allVehicles
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
                val tokens = line.split(delim).map { it.trim().trim('"') }
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
                val vehId = topicMeta.vehicleId
                    ?: nonCoord.firstOrNull { !it.equals(route, ignoreCase = true) && it.any(Char::isDigit) }
                    ?: continue
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
                        tripId = topicMeta.tripId,
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
                        // Tuple format e.g. ["9037", "12U", 45.07, 7.68, 180]
                        val joined = item.joinToString(",") { it?.toString().orEmpty() }
                        out.addAll(parseDelimitedTextPayload("", joined, nowMillis))
                    }
                }
            }
            return out
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

            // 3. 1-level keyed map of vehicles e.g. {"9037": {"lat":..., "lon":...}}
            //    OR 2-level map by route -> vehicle e.g. {"12U": {"9037": {"latitude":..., "longitude":...}}}
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
                                val innerMap = innerVal as? Map<String, Any?> ?: continue
                                buildVehicleFromJsonMap(
                                    root = innerMap,
                                    topicMeta = topicMeta,
                                    keyedVehicleHint = innerKey,
                                    routeHint = k,
                                    nowMillis = nowMillis,
                                )?.let { keyedOut.add(it) }
                            }
                        }
                    }
                    is List<*> -> {
                        if (isPlausibleRouteToken(k)) {
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

            val tripId = readString(fields, "tripid", "trip_id", "trip", "corsa", "journeyid")
                ?.substringAfterLast(":")
                ?: nestedTripId?.substringAfterLast(":")?.takeIf { it.isNotBlank() }
                ?: topicMeta.tripId

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
            val cleanVehId = rawVehId.trim()
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

            val tsSeconds = parseTimestampSeconds(fields, nowMillis)

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
                    0 -> TopicMeta(routeId = routeId)
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

        private fun parseTimestampSeconds(fields: Map<String, String>, nowMillis: Long): Long {
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
            return nowSec
        }
    }
}
