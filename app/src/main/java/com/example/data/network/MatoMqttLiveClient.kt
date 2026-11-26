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
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Pure-Kotlin MQTT 3.1 / 3.1.1 over WebSocket client for 5T / Muoversi a Torino (MaTO) real-time
 * vehicle positions (`wss://mapi.5t.torino.it/scre`).
 *
 * Key resilience features:
 * - Atomic connection generation tracking (eliminates OkHttp onOpen/assignment race conditions).
 * - Automatic WebSocket subprotocol & MQTT protocol version negotiation (`mqtt`, `mqttv3.1`,
 *   combined, and bare WS; MQTT 3.1.1 + MQTT 3.1 `MQIsdp`), remembering the working profile.
 * - ACL-safe line-scoped subscriptions first (never immediately sends raw `#` which can trigger
 *   broker ACL disconnects), with automatic fallback wildcard if silent.
 * - Universal parser supporting 5T flat JSON, Digitransit nested `{"VP":{...}}`, JSON arrays,
 *   GeoJSON `FeatureCollection`, keyed vehicle maps, and binary GTFS-RT protobuf.
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
    private val connectedSinceMillis = AtomicLong(0L)

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
    private var workingProfileIdx: Int = -1

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var keepAliveJob: Job? = null
    private var silentFallbackJob: Job? = null

    fun isConnected(): Boolean = isMqttConnected.get()

    fun lastError(): String? = lastErrorDetail

    fun lastReceivedMillis(): Long = lastMessageReceivedAtMillis.get()

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
            scope.launch {
                subscribeForLine(activeWebSocket, norm)
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
        val lastMsg = lastMessageReceivedAtMillis.get()
        val connSince = connectedSinceMillis.get()
        val stalled = isMqttConnected.get() &&
            connSince > 0L &&
            (nowMillis - connSince) > 55_000L &&
            (lastMsg == 0L || (nowMillis - lastMsg) > 65_000L)
        if (ws == null || stalled) {
            scope.launch {
                if (!isActiveDesired.get()) return@launch
                reconnectAttempt = 0
                openSocket()
            }
        }
    }

    /**
     * Lightweight fallback kick triggered ONLY when GTFS-RT/BusOne feeds are detected as frozen
     * while MaTO MQTT has not yet delivered moving vehicles for the active line (throttled to 30s).
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
        val lastMsg = lastMessageReceivedAtMillis.get()
        val silentTooLong = lastMsg == 0L || (nowMillis - lastMsg) > 35_000L
        scope.launch {
            if (!isActiveDesired.get()) return@launch
            if (ws != null && isMqttConnected.get() && !silentTooLong) {
                subscribeForLine(ws, targetLine)
            } else {
                reconnectAttempt = 0
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
                                    scheduleSilentTopicFallback(webSocket, myGen)
                                } else {
                                    lastErrorDetail = "CONNACK ${pkt.returnCode}"
                                    webSocket.close(1002, "CONNACK rejected")
                                }
                            }
                            is MqttPacket.SubAck -> {
                                val accepted = pkt.returnCodes.count { it in 0..2 }
                                if (accepted == 0 && pkt.returnCodes.isNotEmpty()) {
                                    lastErrorDetail = "SUBACK rifiutato"
                                }
                            }
                            is MqttPacket.Publish -> {
                                lastMessageReceivedAtMillis.set(nowMillis)
                                val parsed = parseMatoMessage(pkt.topic, pkt.payload, nowMillis)
                                for (bus in parsed) {
                                    vehiclesById[bus.vehicleId] = bus
                                    if (bus.lineCode.equals(focusedLine, ignoreCase = true) ||
                                        bus.rawRouteId.equals(focusedLine, ignoreCase = true)
                                    ) {
                                        touchedFocusedLine = true
                                    }
                                }
                            }
                            is MqttPacket.PingResp -> Unit
                        }
                    }

                    if (justConnected) {
                        onFocusedLineUpdate?.invoke()
                    } else if (touchedFocusedLine && nowMillis - lastNotifyMillis >= NOTIFY_DEBOUNCE_MS) {
                        lastNotifyMillis = nowMillis
                        onFocusedLineUpdate?.invoke()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (connectionGen.get() != myGen) return
                    val nowMillis = System.currentTimeMillis()
                    val parsed = parseMatoMessage("", text.toByteArray(StandardCharsets.UTF_8), nowMillis)
                    if (parsed.isNotEmpty()) {
                        isMqttConnected.set(true)
                        workingProfileIdx = profileIdx
                        lastMessageReceivedAtMillis.set(nowMillis)
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

    /**
     * If line-scoped topics stay silent for 6s after CONNACK (for example if 5T introduces a new
     * topic prefix), cautiously add broader non-root and root wildcards without breaking initial ACLs.
     */
    private fun scheduleSilentTopicFallback(webSocket: WebSocket, myGen: Long) {
        silentFallbackJob?.cancel()
        silentFallbackJob = scope.launch {
            delay(6_000L)
            if (!isActive || connectionGen.get() != myGen || !isMqttConnected.get()) return@launch
            if (lastMessageReceivedAtMillis.get() >= connectedSinceMillis.get()) return@launch

            val pid1 = (packetIdGen.getAndIncrement() and 0x7FFF).coerceAtLeast(1)
            runCatching {
                webSocket.send(
                    encodeSubscribePacket(
                        pid1,
                        listOf("/gtfsrt/vp/#", "/hfp/v2/journey/ongoing/vp/#", "/+/#"),
                    ).toByteString(),
                )
            }

            delay(6_000L)
            if (!isActive || connectionGen.get() != myGen || !isMqttConnected.get()) return@launch
            if (lastMessageReceivedAtMillis.get() >= connectedSinceMillis.get()) return@launch

            val pid2 = (packetIdGen.getAndIncrement() and 0x7FFF).coerceAtLeast(1)
            runCatching {
                webSocket.send(encodeSubscribePacket(pid2, listOf("#")).toByteString())
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
        data class Publish(val topic: String, val payload: ByteArray) : MqttPacket()
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
                            val payloadStart = (topicEnd + if (qos > 0) 2 else 0).coerceAtMost(packetEnd)
                            val payload = combined.copyOfRange(payloadStart, packetEnd)
                            packets.add(MqttPacket.Publish(topic, payload))
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
        private const val NOTIFY_DEBOUNCE_MS = 2_000L
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private val HANDSHAKE_PROFILES = listOf(
            HandshakeProfile("scre-mqtt", "wss://mapi.5t.torino.it/scre", "mqtt", useMqtt31 = false),
            HandshakeProfile("scre-443-mqttv31", "wss://mapi.5t.torino.it:443/scre", "mqttv3.1", useMqtt31 = false),
            HandshakeProfile("scre-both", "wss://mapi.5t.torino.it/scre", "mqtt, mqttv3.1", useMqtt31 = false),
            HandshakeProfile("scre-mqisdp", "wss://mapi.5t.torino.it:443/scre", "mqttv3.1", useMqtt31 = true),
            HandshakeProfile("scre-nosub", "wss://mapi.5t.torino.it/scre", null, useMqtt31 = false),
        )

        internal fun buildLineSubscriptionGroups(lineCode: String): List<List<String>> {
            val base = GtfsRtProtobufParser.normalizeGttLineCode(lineCode)
            if (base.isEmpty() || base == "?") return emptyList()
            val variants = linkedSetOf(base).apply {
                if (!base.endsWith("U")) add("${base}U")
                if (base.all { it.isDigit() }) add("${base}F")
            }

            val group1 = mutableListOf<String>()
            val group2 = mutableListOf<String>()
            val group3 = mutableListOf<String>()

            for (v in variants) {
                group1.add("/$v/#")
                group1.add("$v/#")
                group2.add("/gtt:$v/#")
                group2.add("gtt:$v/#")
                group2.add("/gtfsrt/vp/+/+/+/$v/#")
                group2.add("/gtfsrt/vp/+/+/+/gtt:$v/#")
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
         * - 5T native flat JSON (`longitude`/`latitude`/`heading`/`speed`/`direct`/`tripId`/`nextStop`/`veh`/`line`)
         * - Digitransit HFP nested `{"VP": {...}}` JSON
         * - JSON arrays `[ {...}, {...} ]`, GeoJSON `{"features":[...]}`, and vehicle map envelopes
         * - Topic-derived route/direction/vehicleId fallbacks (`/12U/0/9037`, `12U/9037`, `/gtt/12U/9037`, `/gtfsrt/vp/...`)
         * - Binary GTFS-RT protobuf fallback
         */
        internal fun parseMatoMessage(
            topic: String,
            payload: ByteArray,
            nowMillis: Long = System.currentTimeMillis(),
        ): List<BusVehicle> {
            if (payload.isEmpty()) return emptyList()
            val firstNonSpace = payload.firstOrNull {
                it != ' '.code.toByte() &&
                    it != '\n'.code.toByte() &&
                    it != '\r'.code.toByte() &&
                    it != '\t'.code.toByte()
            } ?: return emptyList()

            if (firstNonSpace == '{'.code.toByte() || firstNonSpace == '['.code.toByte()) {
                val text = String(payload, StandardCharsets.UTF_8)
                return parseMatoJsonRoot(topic, text, nowMillis)
            }

            // Fallback: binary GTFS-RT protobuf FeedMessage over MQTT
            return runCatching {
                GtfsRtProtobufParser.parseFeedMessage(payload, "MaTO Live", nowMillis).allVehicles
            }.getOrDefault(emptyList())
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
                is List<*> -> parsed.mapNotNull { item ->
                    (item as? Map<String, Any?>)?.let { buildVehicleFromJsonMap(it, topicMeta, null, nowMillis) }
                }
                is Map<*, *> -> {
                    val root = parsed as Map<String, Any?>
                    val features = root["features"] as? List<*>
                    if (features != null) {
                        return features.mapNotNull { f ->
                            (f as? Map<String, Any?>)?.let { buildVehicleFromJsonMap(it, topicMeta, null, nowMillis) }
                        }
                    }
                    val listEnvelope = (root["vehicles"] as? List<*>) ?: (root["entities"] as? List<*>)
                    if (listEnvelope != null) {
                        return listEnvelope.mapNotNull { item ->
                            (item as? Map<String, Any?>)?.let { buildVehicleFromJsonMap(it, topicMeta, null, nowMillis) }
                        }
                    }
                    // Single vehicle object (flat or {"VP":{...}} or {"vehicle":{...}})
                    buildVehicleFromJsonMap(root, topicMeta, null, nowMillis)?.let { return listOf(it) }

                    // Keyed map of vehicles e.g. {"9037": {"lat":..., "lon":...}, "9040": {...}}
                    val keyedOut = mutableListOf<BusVehicle>()
                    for ((k, v) in root) {
                        val subMap = v as? Map<String, Any?> ?: continue
                        buildVehicleFromJsonMap(subMap, topicMeta, k, nowMillis)?.let { keyedOut.add(it) }
                    }
                    keyedOut
                }
                else -> emptyList()
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun buildVehicleFromJsonMap(
            root: Map<String, Any?>,
            topicMeta: TopicMeta,
            keyedVehicleHint: String?,
            nowMillis: Long,
        ): BusVehicle? {
            val fields = LinkedHashMap<String, String>()
            fun collect(map: Map<String, Any?>) {
                for ((k, v) in map) {
                    if (v != null && v !is Map<*, *> && v !is List<*>) {
                        fields[k] = v.toString()
                    }
                }
            }
            collect(root)
            (root["properties"] as? Map<String, Any?>)?.let(::collect)
            (root["VP"] as? Map<String, Any?>)?.let(::collect)
            (root["vehicle"] as? Map<String, Any?>)?.let(::collect)

            // Support GeoJSON geometry.coordinates = [lon, lat]
            var geoLat: Double? = null
            var geoLon: Double? = null
            val geom = root["geometry"] as? Map<String, Any?>
            val coords = geom?.get("coordinates") as? List<*>
            if (coords != null && coords.size >= 2) {
                geoLon = coords[0]?.toString()?.toDoubleOrNull()
                geoLat = coords[1]?.toString()?.toDoubleOrNull()
            }

            val lat = readDouble(fields, "latitude", "lat", "y") ?: geoLat ?: return null
            val lon = readDouble(fields, "longitude", "lon", "long", "lng", "x") ?: geoLon ?: return null
            if (!lat.isFinite() || !lon.isFinite() || lat !in 44.0..46.5 || lon !in 6.5..9.0) return null

            val rawRoute = readString(fields, "line", "linea", "route", "routeId", "route_id", "desi", "shortName", "l")
                ?.substringAfterLast(":")
                ?: topicMeta.routeId
                ?: return null
            val normalizedLine = GtfsRtProtobufParser.normalizeGttLineCode(rawRoute)
            if (normalizedLine == "?" || normalizedLine.isBlank()) return null

            val rawVehId = readString(
                fields,
                "veh",
                "vehicleId",
                "vehicle_id",
                "num",
                "id",
                "label",
                "vehicle",
                "matr",
                "matricola",
            )?.substringAfterLast(":")
                ?: keyedVehicleHint
                ?: topicMeta.vehicleId
                ?: return null
            val cleanVehId = rawVehId.trim()
            if (cleanVehId.isEmpty()) return null

            val bearing = readDouble(fields, "heading", "bearing", "hdg", "dir_deg", "angle", "course")
                ?.toFloat()
                ?.takeIf { it.isFinite() && it >= 0f && it <= 360f }

            val rawSpeed = readDouble(fields, "speed", "spd")
            val speedKmh = rawSpeed?.toFloat()?.takeIf { it.isFinite() && it >= 0f }?.let { s ->
                if (fields.containsKey("spd") && s <= 25f) s * 3.6f else s
            }

            val dirId = readInt(fields, "direct", "directionId", "direction_id", "direction", "dir")
                ?.let { raw ->
                    when (raw) {
                        0, 1 -> raw
                        2 -> 1
                        else -> null
                    }
                }
                ?: topicMeta.directionId

            val tripId = readString(fields, "tripId", "trip_id", "trip")
                ?.substringAfterLast(":")
                ?: topicMeta.tripId

            val nextStop = readString(fields, "nextStop", "next_stop", "stop", "stopId", "stop_id", "fermata")
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
            // Case 3: 5T MaTO compact topic with optional prefix e.g. /12U/9037, /12U/0/9037, /gtt/12U/9037, /scre/12U/0/9037
            val routeIdx = parts.indexOfFirst { seg ->
                val clean = seg.substringAfterLast(":")
                clean.length in 1..6 && clean.any { it.isDigit() } && !clean.equals("5t", ignoreCase = true)
            }
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
                            TopicMeta(
                                routeId = routeId,
                                vehicleId = tail.firstOrNull { it.any(Char::isDigit) }?.substringAfterLast(":")
                                    ?: tail.last().substringAfterLast(":"),
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
            for (k in listOf("tsi", "timestamp", "ts", "lastUpdated", "time", "t")) {
                val rawStr = fields[k]?.trim() ?: continue
                val raw = rawStr.toLongOrNull() ?: rawStr.toDoubleOrNull()?.toLong() ?: continue
                val sec = if (raw > 1_000_000_000_000L) raw / 1000L else raw
                if (sec in 1_600_000_000L..(nowSec + 120L)) return sec
            }
            val iso = readString(fields, "tst", "lastUpdate", "updatedAt")
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
