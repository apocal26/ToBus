package com.example.data.network

import com.example.data.model.AlertTimeRange
import com.example.data.model.BusVehicle
import com.example.data.model.LineSummary
import com.example.data.model.LiveFeedSnapshot
import com.example.data.model.ServiceAlertInfo
import com.example.data.model.StopTimePrediction
import com.example.data.model.TripDelayInfo
import com.example.data.model.VehicleStopStatus
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin Protocol Buffers wire-format parser for standard GTFS-Realtime feeds:
 * - VehiclePositions
 * - TripUpdates (real-time schedule delay & next stop)
 * - ServiceAlerts (detours & notices per line)
 */
object GtfsRtProtobufParser {

    private const val WIRE_VARINT = 0
    private const val WIRE_64BIT = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_32BIT = 5

    fun parseFeedMessage(
        bytes: ByteArray,
        sourceName: String = "GTT GTFS-RT",
        fetchedAtMillis: Long = System.currentTimeMillis(),
    ): LiveFeedSnapshot {
        val reader = ProtoReader(bytes, 0, bytes.size)
        var feedTimestamp = 0L
        val vehicles = mutableListOf<BusVehicle>()

        while (reader.hasRemaining()) {
            val tag = reader.readVarint32()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when {
                fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED -> {
                    feedTimestamp = parseFeedHeaderTimestamp(reader.readSubReader())
                }
                fieldNumber == 2 && wireType == WIRE_LENGTH_DELIMITED -> {
                    parseFeedEntityVehicle(reader.readSubReader(), sourceName, fetchedAtMillis)
                        ?.let { vehicles.add(it) }
                }
                else -> reader.skipField(wireType)
            }
        }

        val summaries = buildLineSummaries(vehicles)
        return LiveFeedSnapshot(
            feedTimestampSeconds = feedTimestamp,
            fetchedAtMillis = fetchedAtMillis,
            allVehicles = vehicles,
            availableLines = summaries,
        )
    }

    fun parseTripUpdates(bytes: ByteArray): List<TripDelayInfo> {
        val reader = ProtoReader(bytes, 0, bytes.size)
        val out = mutableListOf<TripDelayInfo>()
        while (reader.hasRemaining()) {
            val tag = reader.readVarint32()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            if (fieldNumber == 2 && wireType == WIRE_LENGTH_DELIMITED) {
                parseFeedEntityTripUpdate(reader.readSubReader())?.let { out.add(it) }
            } else {
                reader.skipField(wireType)
            }
        }
        return out
    }

    fun parseServiceAlerts(bytes: ByteArray): List<ServiceAlertInfo> {
        val reader = ProtoReader(bytes, 0, bytes.size)
        val out = mutableListOf<ServiceAlertInfo>()
        while (reader.hasRemaining()) {
            val tag = reader.readVarint32()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            if (fieldNumber == 2 && wireType == WIRE_LENGTH_DELIMITED) {
                parseFeedEntityAlert(reader.readSubReader())?.let { out.add(it) }
            } else {
                reader.skipField(wireType)
            }
        }
        return out
    }

    fun buildLineSummaries(vehicles: List<BusVehicle>): List<LineSummary> {
        return vehicles
            .groupBy { it.lineCode }
            .map { (lineCode, list) ->
                LineSummary(
                    lineCode = lineCode,
                    rawRouteIds = list.map { it.rawRouteId }.toSet(),
                    activeVehicleCount = list.size,
                )
            }
            .sortedWith(lineCodeComparator)
    }

    const val LINE_CODE_METRO = "METRO"

    fun isNonLiveTrackedLine(lineCode: String): Boolean {
        return normalizeGttLineCode(lineCode) == LINE_CODE_METRO
    }

    private val MULTI_SPACE_REGEX = Regex("\\s+")
    private val COMPACT_BARRATO_REGEX = Regex("^([0-9]+[A-Z]?)\\s*BARR(?:AT[OA])?$")
    private val LEADING_ZERO_ALPHA_NUM_REGEX = Regex("^([A-Z])0+([1-9][0-9]*[A-Z/+]*)$")
    private val SINGLE_ALPHA_SINGLE_DIGIT_REGEX = Regex("^([A-Z])([1-9])$")

    /**
     * Canonicalizes a Turin GTT route identifier or short name across every feed
     * (GTFS Static `routes.txt`, GTFS-RT VehiclePositions/TripUpdates/Alerts, MaTO GraphQL,
     * MaTO MQTT, GTT Palina):
     * - Strips operator prefixes (`gtt:17BU` -> `17B`, `1:17/` -> `17B`)
     * - Collapses GTT spacing variants (`17 /` -> `17B`, `13 +` -> `13+`, `STAR 1` -> `STAR1`,
     *   `16 CS` -> `16CS`, `16 CD` -> `16CD`, `VE 1` -> `VE1`, `M1 S` -> `M1S`) while preserving
     *   multi-word descriptive names (`VENARIA EXPRESS`, `DORA FLY`)
     * - Unifies Turin Metro (`M1`, `M1U`, `M1F`, `METRO`, `METROPOLITANA` -> `METRO`, preserving `M1S`)
     * - Strips GTT service suffixes (`UF` Urbano Festivo, `U` Urbano, `F` Festivo on numbered/variant
     *   lines like `12F`/`17BF`/`10NF`/`13+F`/`16CSF`/`M1SF`/`STAR1F`, `E` Extraurbano/Estivo on
     *   numeric/barrata/plus lines like `1510E`/`265E`/`12E`/`17BE`/`13+E`)
     * - Unifies barrata notation (`17/`, `17/U`, `17/F`, `17 BARRATO`, `17BU`, `17BF` -> `17B`)
     * - Drops redundant leading zeros on numeric (`04` -> `4`) and Night Buster (`W01` -> `W1`,
     *   `S04` -> `S4`, `N04` -> `N4`) codes.
     */
    fun normalizeGttLineCode(rawRouteId: String): String {
        var s = rawRouteId.trim().substringAfterLast(":").trim().uppercase()
        if (s.isEmpty()) return "?"

        // Normalize internal spacing
        if (s.contains(' ') || s.contains('\t')) {
            s = MULTI_SPACE_REGEX.replace(s, " ")
                .replace(" /", "/")
                .replace("/ ", "/")
                .replace(" +", "+")
                .replace("+ ", "+")
        }

        // Handle explicit "17 BARRATO" / "17 BARR" notation
        COMPACT_BARRATO_REGEX.matchEntire(s)?.let { match ->
            s = "${match.groupValues[1]}B"
        }

        // Collapse compact spaced alphanumeric GTT codes (e.g. "STAR 1", "16 CS", "M 1", "VE 1")
        // without collapsing multi-word names that have no digits (e.g. "VENARIA EXPRESS").
        if (s.contains(' ') && s.any { it.isDigit() }) {
            val tokens = s.split(' ')
            if (tokens.all { it.isNotEmpty() && it.length <= 4 }) {
                s = tokens.joinToString("")
            }
        }

        if (s == "M1" || s == "M1U" || s == "M1F" || s == "M1UF" ||
            s == "METRO" || s == "METROPOLITANA"
        ) {
            return LINE_CODE_METRO
        }
        if (s == "BLU") return s

        // Strip GTT service-type suffixes (UF, U, F, E)
        s = when {
            s.length >= 3 && s.endsWith("UF") && s.dropLast(2).any { it.isDigit() } ->
                s.dropLast(2)
            s.length >= 2 && s.endsWith("U") ->
                s.dropLast(1)
            s.length >= 2 && s.endsWith("F") && s.dropLast(1).any { it.isDigit() } ->
                s.dropLast(1)
            s.length >= 2 && s.endsWith("E") -> {
                val prefix = s.dropLast(1)
                val isStrippableE = prefix.all { it.isDigit() } ||
                    (prefix.length >= 2 &&
                        (prefix.endsWith("B") || prefix.endsWith("/") || prefix.endsWith("+")) &&
                        prefix.dropLast(1).all { it.isDigit() })
                if (isStrippableE) prefix else s
            }
            else -> s
        }

        // Convert trailing slash barrata notation (17/, 5/, 58/, 72/, N1/) to canonical B (17B, 5B...)
        if (s.length >= 2 && s.endsWith("/") && s.dropLast(1).any { it.isDigit() }) {
            s = s.dropLast(1) + "B"
        }

        // Normalize redundant leading zeros (04 -> 4, W01 -> W1, S04 -> S4, N04 -> N4, M01 -> METRO)
        if (s.length > 1 && s.all { it.isDigit() } && s.startsWith('0')) {
            s = s.trimStart('0').ifEmpty { "0" }
        } else {
            LEADING_ZERO_ALPHA_NUM_REGEX.matchEntire(s)?.let { match ->
                s = "${match.groupValues[1]}${match.groupValues[2]}"
            }
        }

        return if (s == "M1") LINE_CODE_METRO else s
    }

    /**
     * Alternate feed query names to try when querying MaTO GraphQL, OpenStreetMap Overpass,
     * or MaTO MQTT if the canonical code (e.g. `17B`, `METRO`, `STAR1`, `16CS`, `W1`) is
     * indexed under its display/padded shortName (`17/`, `M1`, `STAR 1`, `16 CS`, `W01`).
     */
    fun alternateFeedQueryCodes(canonicalLineCode: String): List<String> {
        val code = normalizeGttLineCode(canonicalLineCode)
        if (code.isEmpty() || code == "?") return emptyList()
        val alts = LinkedHashSet<String>(3)
        if (code == LINE_CODE_METRO) {
            alts.add("M1")
        }
        if (code.length >= 2 && code.endsWith("B") && code.dropLast(1).any { it.isDigit() }) {
            alts.add(code.dropLast(1) + "/")
        }
        if (code.startsWith("STAR") && code.length > 4 && code.drop(4).all { it.isDigit() }) {
            alts.add("STAR ${code.drop(4)}")
        }
        if ((code.endsWith("CS") || code.endsWith("CD")) &&
            code.length >= 3 &&
            code.dropLast(2).all { it.isDigit() }
        ) {
            alts.add("${code.dropLast(2)} ${code.takeLast(2)}")
        }
        SINGLE_ALPHA_SINGLE_DIGIT_REGEX.matchEntire(code)?.let { match ->
            alts.add("${match.groupValues[1]}0${match.groupValues[2]}")
        }
        alts.remove(code)
        return alts.toList()
    }

    /**
     * Canonicalizes a Turin/GTT vehicle identifier across all feeds (MaTO MQTT, MaTO GraphQL,
     * GTT Official GTFS-RT, BusOne Proxy, and TripUpdates):
     * - Strips operator prefixes (`gtt:9320U` -> `9320U`)
     * - Strips GTT urban/festivo/night service suffixes attached to numeric fleet matricole
     *   (`9320U` -> `9320`, `9320UF` -> `9320`, `9320F` -> `9320`, `9320E` -> `9320`)
     * - Trims redundant leading zeros on purely numeric fleet numbers (`0805` -> `805`)
     * - Preserves fallback trip IDs or special alphanumeric IDs (`T88421`, `TR1234`) intact.
     */
    fun canonicalFleetId(rawId: String?): String {
        var s = rawId?.trim()?.substringAfterLast(":")?.trim().orEmpty()
        if (s.isEmpty() || s.equals("null", ignoreCase = true)) return ""
        val upper = s.uppercase()
        s = when {
            upper.length >= 4 && upper.endsWith("UF") && upper.dropLast(2).all { it.isDigit() } ->
                upper.dropLast(2)
            upper.length >= 3 &&
                (upper.endsWith("U") || upper.endsWith("F") || upper.endsWith("E")) &&
                upper.dropLast(1).all { it.isDigit() } ->
                upper.dropLast(1)
            else -> s
        }
        if (s.length > 1 && s.all { it.isDigit() } && s.startsWith('0')) {
            s = s.trimStart('0').ifEmpty { "0" }
        }
        return s
    }

    fun normalizeTripId(rawTripId: String?): String? {
        val clean = rawTripId?.trim()?.substringAfterLast(":")?.trim()
        return clean?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    /**
     * Returns true when [rawId] resolves to a real physical GTT fleet number (2..6 digits, e.g. 790,
     * 9320, 11200) rather than a fallback tripId (`T88421`, `TR1234`, `12U_991`).
     */
    fun isPhysicalFleetId(rawId: String?): Boolean {
        val c = canonicalFleetId(rawId)
        return c.length in 2..6 && c.all { it.isDigit() }
    }

    val lineCodeComparator: Comparator<LineSummary> = Comparator { a, b ->
        compareLineCodes(a.lineCode, b.lineCode)
    }

    fun compareLineCodes(a: String, b: String): Int {
        val isMetroA = a.equals(LINE_CODE_METRO, ignoreCase = true)
        val isMetroB = b.equals(LINE_CODE_METRO, ignoreCase = true)
        if (isMetroA && !isMetroB) return -1
        if (!isMetroA && isMetroB) return 1
        val numA = extractLeadingInt(a)
        val numB = extractLeadingInt(b)
        return when {
            numA != null && numB != null -> {
                val cmp = numA.compareTo(numB)
                if (cmp != 0) cmp else a.compareTo(b)
            }
            numA != null -> -1
            numB != null -> 1
            else -> a.compareTo(b)
        }
    }

    private fun extractLeadingInt(code: String): Int? {
        var idx = 0
        while (idx < code.length && code[idx].isDigit()) idx++
        return if (idx > 0) code.substring(0, idx).toIntOrNull() else null
    }

    private fun parseFeedHeaderTimestamp(reader: ProtoReader): Long {
        var ts = 0L
        while (reader.hasRemaining()) {
            val tag = reader.readVarint32()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            if (fieldNumber == 3 && wireType == WIRE_VARINT) {
                ts = reader.readVarint64()
            } else {
                reader.skipField(wireType)
            }
        }
        return ts
    }

    /**
     * Parses a binary GTFS-RT protobuf payload received over MQTT, supporting:
     * 1. Standard `FeedMessage` containing one or more `FeedEntity` items
     * 2. Bare `FeedEntity` containing a `VehiclePosition`
     * 3. Bare `VehiclePosition` directly as the MQTT payload
     *
     * Uses [fallbackRouteId] and [fallbackVehicleId] (e.g. derived from the MQTT topic)
     * whenever the protobuf omits `trip.route_id` or `vehicle.id`.
     */
    fun parseSingleVehicleMessage(
        bytes: ByteArray,
        fallbackRouteId: String? = null,
        fallbackVehicleId: String? = null,
        sourceName: String = "MaTO Live",
        fetchedAtMillis: Long = System.currentTimeMillis(),
    ): List<BusVehicle> {
        if (bytes.isEmpty()) return emptyList()
        // 1. Try full FeedMessage (applying topic fallbacks if route/vehicle missing)
        val feedVehicles = mutableListOf<BusVehicle>()
        runCatching {
            val reader = ProtoReader(bytes, 0, bytes.size)
            while (reader.hasRemaining()) {
                val tag = reader.readVarint32()
                val fieldNumber = tag ushr 3
                val wireType = tag and 0x07
                if (fieldNumber == 2 && wireType == WIRE_LENGTH_DELIMITED) {
                    parseFeedEntityVehicle(
                        reader = reader.readSubReader(),
                        sourceName = sourceName,
                        fetchedAtMillis = fetchedAtMillis,
                        fallbackRouteId = fallbackRouteId,
                        fallbackVehicleId = fallbackVehicleId,
                    )?.let { feedVehicles.add(it) }
                } else {
                    reader.skipField(wireType)
                }
            }
        }
        if (feedVehicles.isNotEmpty()) return feedVehicles

        // 2. Try bare FeedEntity (field 1 = id, field 4 = VehiclePosition)
        val entityVehicle = runCatching {
            parseFeedEntityVehicle(
                reader = ProtoReader(bytes, 0, bytes.size),
                sourceName = sourceName,
                fetchedAtMillis = fetchedAtMillis,
                fallbackRouteId = fallbackRouteId,
                fallbackVehicleId = fallbackVehicleId,
            )
        }.getOrNull()
        if (entityVehicle != null) return listOf(entityVehicle)

        // 3. Try bare VehiclePosition (field 1 = TripDescriptor, field 2 = Position, ...)
        val bareVehicle = runCatching {
            parseVehiclePosition(
                reader = ProtoReader(bytes, 0, bytes.size),
                entityId = "",
                sourceName = sourceName,
                fetchedAtMillis = fetchedAtMillis,
                fallbackRouteId = fallbackRouteId,
                fallbackVehicleId = fallbackVehicleId,
            )
        }.getOrNull()
        return listOfNotNull(bareVehicle)
    }

    private fun parseFeedEntityVehicle(
        reader: ProtoReader,
        sourceName: String,
        fetchedAtMillis: Long,
        fallbackRouteId: String? = null,
        fallbackVehicleId: String? = null,
    ): BusVehicle? {
        var entityId = ""
        var vehicle: BusVehicle? = null
        while (reader.hasRemaining()) {
            val tag = reader.readVarint32()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when {
                fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED -> entityId = reader.readString()
                fieldNumber == 4 && wireType == WIRE_LENGTH_DELIMITED -> {
                    vehicle = parseVehiclePosition(
                        reader = reader.readSubReader(),
                        entityId = entityId,
                        sourceName = sourceName,
                        fetchedAtMillis = fetchedAtMillis,
                        fallbackRouteId = fallbackRouteId,
                        fallbackVehicleId = fallbackVehicleId,
                    )
                }
                else -> reader.skipField(wireType)
            }
        }
        return vehicle?.let {
            if (it.entityId.isEmpty() && entityId.isNotEmpty()) it.copy(entityId = entityId) else it
        }
    }

    private fun parseVehiclePosition(
        reader: ProtoReader,
        entityId: String,
        sourceName: String,
        fetchedAtMillis: Long,
        fallbackRouteId: String? = null,
        fallbackVehicleId: String? = null,
    ): BusVehicle? {
        var tripId: String? = null
        var routeId = ""
        var directionId: Int? = null
        var lat = Double.NaN
        var lon = Double.NaN
        var bearing: Float? = null
        var speedMs: Float? = null
        var status = VehicleStopStatus.UNKNOWN
        var timestamp = 0L
        var stopId: String? = null
        var vehicleId = ""
        var vehicleLabel = ""

        while (reader.hasRemaining()) {
            val tag = reader.readVarint32()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when {
                fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED -> {
                    val sub = reader.readSubReader()
                    while (sub.hasRemaining()) {
                        val tTag = sub.readVarint32()
                        val tField = tTag ushr 3
                        val tWire = tTag and 0x07
                        when {
                            tField == 1 && tWire == WIRE_LENGTH_DELIMITED -> tripId = sub.readString()
                            tField == 5 && tWire == WIRE_LENGTH_DELIMITED -> routeId = sub.readString()
                            tField == 6 && tWire == WIRE_VARINT -> directionId = sub.readVarint32()
                            else -> sub.skipField(tWire)
                        }
                    }
                }
                fieldNumber == 2 && wireType == WIRE_LENGTH_DELIMITED -> {
                    val sub = reader.readSubReader()
                    while (sub.hasRemaining()) {
                        val pTag = sub.readVarint32()
                        val pField = pTag ushr 3
                        val pWire = pTag and 0x07
                        when {
                            pField == 1 && pWire == WIRE_32BIT -> lat = sub.readFloat().toDouble()
                            pField == 2 && pWire == WIRE_32BIT -> lon = sub.readFloat().toDouble()
                            pField == 3 && pWire == WIRE_32BIT -> bearing = sub.readFloat()
                            pField == 5 && pWire == WIRE_32BIT -> speedMs = sub.readFloat()
                            else -> sub.skipField(pWire)
                        }
                    }
                }
                fieldNumber == 4 && wireType == WIRE_VARINT -> {
                    status = when (reader.readVarint32()) {
                        0 -> VehicleStopStatus.INCOMING_AT
                        1 -> VehicleStopStatus.STOPPED_AT
                        2 -> VehicleStopStatus.IN_TRANSIT_TO
                        else -> VehicleStopStatus.UNKNOWN
                    }
                }
                fieldNumber == 5 && wireType == WIRE_VARINT -> timestamp = reader.readVarint64()
                fieldNumber == 7 && wireType == WIRE_LENGTH_DELIMITED -> stopId = reader.readString()
                fieldNumber == 8 && wireType == WIRE_LENGTH_DELIMITED -> {
                    val sub = reader.readSubReader()
                    while (sub.hasRemaining()) {
                        val vTag = sub.readVarint32()
                        val vField = vTag ushr 3
                        val vWire = vTag and 0x07
                        when {
                            vField == 1 && vWire == WIRE_LENGTH_DELIMITED -> vehicleId = sub.readString()
                            vField == 2 && vWire == WIRE_LENGTH_DELIMITED -> vehicleLabel = sub.readString()
                            else -> sub.skipField(vWire)
                        }
                    }
                }
                else -> reader.skipField(wireType)
            }
        }

        if (lat.isNaN() || lon.isNaN() || lat == 0.0 || lon == 0.0) return null
        if (lat !in 44.0..46.5 || lon !in 6.5..9.0) return null

        val effectiveRoute = routeId.ifBlank { fallbackRouteId?.trim().orEmpty() }.ifBlank { return null }
        val normalizedLine = normalizeGttLineCode(effectiveRoute)
        val normTripId = normalizeTripId(tripId)
        val rawVehicleId = vehicleId.ifBlank {
            vehicleLabel.ifBlank {
                fallbackVehicleId?.trim().orEmpty().ifBlank {
                    entityId.ifBlank { normTripId.orEmpty().ifBlank { "?" } }
                }
            }
        }
        val effectiveVehicleId = canonicalFleetId(rawVehicleId).ifBlank { "?" }
        val cleanedLabel = canonicalFleetId(vehicleLabel)
        val effectiveLabel = cleanedLabel.ifBlank { effectiveVehicleId }

        val nowSec = fetchedAtMillis / 1000L
        val normalizedTs = if (timestamp > 1_000_000_000_000L) timestamp / 1000L else timestamp
        val clampedTimestamp = when {
            normalizedTs <= 0L -> nowSec
            normalizedTs > nowSec + 120L -> nowSec
            else -> normalizedTs
        }

        return BusVehicle(
            entityId = entityId.ifBlank { "${effectiveVehicleId}_${effectiveRoute}" },
            vehicleId = effectiveVehicleId,
            label = effectiveLabel,
            rawRouteId = effectiveRoute,
            lineCode = normalizedLine,
            tripId = normTripId,
            directionId = directionId,
            latitude = lat,
            longitude = lon,
            bearing = bearing?.takeIf { !it.isNaN() && it >= 0f },
            speedKmh = speedMs?.takeIf { !it.isNaN() && it >= 0f }?.let { it * 3.6f },
            timestampSeconds = clampedTimestamp,
            stopId = stopId,
            status = status,
            sourceName = sourceName,
            contributingSources = setOf(sourceName),
            fetchedAtMillis = fetchedAtMillis,
        )
    }

    private fun parseFeedEntityTripUpdate(reader: ProtoReader): TripDelayInfo? {
        while (reader.hasRemaining()) {
            val tag = reader.readVarint32()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            if (fieldNumber == 3 && wireType == WIRE_LENGTH_DELIMITED) {
                return parseTripUpdate(reader.readSubReader())
            } else {
                reader.skipField(wireType)
            }
        }
        return null
    }

    private fun parseTripUpdate(reader: ProtoReader): TripDelayInfo? {
        var tripId: String? = null
        var routeId: String? = null
        var tripScheduleRel = 0
        var vehicleId: String? = null
        var vehicleLabel: String? = null
        var delay: Int? = null
        var nextStopId: String? = null
        var ts = 0L
        val predictions = mutableListOf<StopTimePrediction>()

        while (reader.hasRemaining()) {
            val tag = reader.readVarint32()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when {
                fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED -> {
                    val sub = reader.readSubReader()
                    while (sub.hasRemaining()) {
                        val tTag = sub.readVarint32()
                        val tField = tTag ushr 3
                        val tWire = tTag and 0x07
                        when {
                            tField == 1 && tWire == WIRE_LENGTH_DELIMITED -> tripId = sub.readString()
                            tField == 4 && tWire == WIRE_VARINT -> tripScheduleRel = sub.readVarint32()
                            tField == 5 && tWire == WIRE_LENGTH_DELIMITED -> routeId = sub.readString()
                            else -> sub.skipField(tWire)
                        }
                    }
                }
                fieldNumber == 3 && wireType == WIRE_LENGTH_DELIMITED -> {
                    val sub = reader.readSubReader()
                    while (sub.hasRemaining()) {
                        val vTag = sub.readVarint32()
                        val vField = vTag ushr 3
                        val vWire = vTag and 0x07
                        when {
                            vField == 1 && vWire == WIRE_LENGTH_DELIMITED -> vehicleId = sub.readString()
                            vField == 2 && vWire == WIRE_LENGTH_DELIMITED -> vehicleLabel = sub.readString()
                            else -> sub.skipField(vWire)
                        }
                    }
                }
                fieldNumber == 2 && wireType == WIRE_LENGTH_DELIMITED -> {
                    // StopTimeUpdate — parse arrival/departure times, delay, stop_id & schedule_relationship
                    val sub = reader.readSubReader()
                    var stuDelay: Int? = null
                    var stuArrivalTime: Long? = null
                    var stuDepartureTime: Long? = null
                    var stuStop: String? = null
                    var stuScheduleRel = 0 // 0=SCHEDULED, 1=SKIPPED, 2=NO_DATA
                    while (sub.hasRemaining()) {
                        val sTag = sub.readVarint32()
                        val sField = sTag ushr 3
                        val sWire = sTag and 0x07
                        when {
                            sField == 2 && sWire == WIRE_LENGTH_DELIMITED -> {
                                val evt = sub.readSubReader()
                                while (evt.hasRemaining()) {
                                    val eTag = evt.readVarint32()
                                    val eField = eTag ushr 3
                                    val eWire = eTag and 0x07
                                    when {
                                        eField == 1 && eWire == WIRE_VARINT -> stuDelay = evt.readVarint64().toInt()
                                        eField == 2 && eWire == WIRE_VARINT -> {
                                            stuArrivalTime = evt.readVarint64().takeIf { it > 1_500_000_000L }
                                        }
                                        else -> evt.skipField(eWire)
                                    }
                                }
                            }
                            sField == 3 && sWire == WIRE_LENGTH_DELIMITED -> {
                                val evt = sub.readSubReader()
                                while (evt.hasRemaining()) {
                                    val eTag = evt.readVarint32()
                                    val eField = eTag ushr 3
                                    val eWire = eTag and 0x07
                                    when {
                                        eField == 1 && eWire == WIRE_VARINT -> {
                                            if (stuDelay == null) stuDelay = evt.readVarint64().toInt()
                                            else evt.readVarint64()
                                        }
                                        eField == 2 && eWire == WIRE_VARINT -> {
                                            stuDepartureTime = evt.readVarint64().takeIf { it > 1_500_000_000L }
                                        }
                                        else -> evt.skipField(eWire)
                                    }
                                }
                            }
                            sField == 4 && sWire == WIRE_LENGTH_DELIMITED -> stuStop = sub.readString()
                            sField == 5 && sWire == WIRE_VARINT -> stuScheduleRel = sub.readVarint32()
                            else -> sub.skipField(sWire)
                        }
                    }
                    // Skip SKIPPED (1) or NO_DATA (2) stop updates
                    if (stuScheduleRel == 1 || stuScheduleRel == 2) continue
                    if (nextStopId == null && stuStop != null) nextStopId = stuStop
                    if (delay == null && stuDelay != null) delay = stuDelay
                    if (!stuStop.isNullOrBlank() && (stuArrivalTime != null || stuDepartureTime != null)) {
                        predictions.add(
                            StopTimePrediction(
                                stopId = stuStop,
                                arrivalEpochSeconds = stuArrivalTime,
                                departureEpochSeconds = stuDepartureTime,
                                delaySeconds = stuDelay,
                            ),
                        )
                    }
                }
                fieldNumber == 4 && wireType == WIRE_VARINT -> ts = reader.readVarint64()
                fieldNumber == 5 && wireType == WIRE_VARINT -> delay = reader.readVarint64().toInt()
                else -> reader.skipField(wireType)
            }
        }

        // Ignore CANCELED (3) trips completely
        if (tripScheduleRel == 3) return null
        val rawEffectiveVehicleId = vehicleId?.takeIf { it.isNotBlank() } ?: vehicleLabel?.takeIf { it.isNotBlank() }
        val cleanVehicleId = rawEffectiveVehicleId?.let { canonicalFleetId(it).ifBlank { null } }
        val cleanTripId = normalizeTripId(tripId)
        if (cleanTripId == null && cleanVehicleId == null) return null
        return TripDelayInfo(
            tripId = cleanTripId,
            vehicleId = cleanVehicleId,
            routeId = routeId,
            delaySeconds = delay,
            nextStopId = nextStopId,
            timestampSeconds = ts,
            stopPredictions = predictions,
        )
    }

    private fun parseFeedEntityAlert(reader: ProtoReader): ServiceAlertInfo? {
        var entityId = ""
        var alert: ServiceAlertInfo? = null
        while (reader.hasRemaining()) {
            val tag = reader.readVarint32()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when {
                fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED -> entityId = reader.readString()
                fieldNumber == 5 && wireType == WIRE_LENGTH_DELIMITED -> {
                    alert = parseAlert(reader.readSubReader(), entityId)
                }
                else -> reader.skipField(wireType)
            }
        }
        return alert
    }

    private fun parseAlert(reader: ProtoReader, id: String): ServiceAlertInfo? {
        val periods = mutableListOf<AlertTimeRange>()
        val lines = mutableSetOf<String>()
        var header = ""
        var desc = ""
        var cause: Int? = null
        var effect: Int? = null

        while (reader.hasRemaining()) {
            val tag = reader.readVarint32()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            when {
                fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED -> {
                    // TimeRange { uint64 start = 1; uint64 end = 2; }
                    val sub = reader.readSubReader()
                    var startSec: Long? = null
                    var endSec: Long? = null
                    while (sub.hasRemaining()) {
                        val trTag = sub.readVarint32()
                        val trField = trTag ushr 3
                        val trWire = trTag and 0x07
                        when {
                            trField == 1 && trWire == WIRE_VARINT -> startSec = sub.readVarint64().takeIf { it > 0L }
                            trField == 2 && trWire == WIRE_VARINT -> endSec = sub.readVarint64().takeIf { it > 0L }
                            else -> sub.skipField(trWire)
                        }
                    }
                    if (startSec != null || endSec != null) {
                        periods.add(AlertTimeRange(startEpochSeconds = startSec, endEpochSeconds = endSec))
                    }
                }
                fieldNumber == 5 && wireType == WIRE_LENGTH_DELIMITED -> {
                    val sub = reader.readSubReader()
                    while (sub.hasRemaining()) {
                        val iTag = sub.readVarint32()
                        val iField = iTag ushr 3
                        val iWire = iTag and 0x07
                        if (iField == 2 && iWire == WIRE_LENGTH_DELIMITED) {
                            lines.add(normalizeGttLineCode(sub.readString()))
                        } else {
                            sub.skipField(iWire)
                        }
                    }
                }
                fieldNumber == 6 && wireType == WIRE_VARINT -> cause = reader.readVarint32()
                fieldNumber == 7 && wireType == WIRE_VARINT -> effect = reader.readVarint32()
                fieldNumber == 10 && wireType == WIRE_LENGTH_DELIMITED -> header = parseTranslatedString(reader.readSubReader())
                fieldNumber == 11 && wireType == WIRE_LENGTH_DELIMITED -> desc = parseTranslatedString(reader.readSubReader())
                else -> reader.skipField(wireType)
            }
        }

        if (header.isBlank() && desc.isBlank()) return null
        return ServiceAlertInfo(
            id = id.ifBlank { header.take(24) },
            headerText = header.ifBlank { desc.take(80) },
            descriptionText = desc,
            affectedLines = lines,
            activePeriods = periods,
            gtfsCause = cause,
            gtfsEffect = effect,
        )
    }

    private fun parseTranslatedString(reader: ProtoReader): String {
        var best = ""
        while (reader.hasRemaining()) {
            val tag = reader.readVarint32()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x07
            if (fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED) {
                val sub = reader.readSubReader()
                var text = ""
                var lang = ""
                while (sub.hasRemaining()) {
                    val tTag = sub.readVarint32()
                    val tField = tTag ushr 3
                    val tWire = tTag and 0x07
                    when {
                        tField == 1 && tWire == WIRE_LENGTH_DELIMITED -> text = sub.readString()
                        tField == 2 && tWire == WIRE_LENGTH_DELIMITED -> lang = sub.readString()
                        else -> sub.skipField(tWire)
                    }
                }
                if (best.isEmpty() || lang.startsWith("it", ignoreCase = true)) {
                    best = text
                }
            } else {
                reader.skipField(wireType)
            }
        }
        return best.trim()
    }

    private class ProtoReader(
        private val data: ByteArray,
        private var pos: Int,
        private val limit: Int,
    ) {
        fun hasRemaining(): Boolean = pos < limit

        fun readVarint32(): Int = readVarint64().toInt()

        fun readVarint64(): Long {
            var result = 0L
            var shift = 0
            while (pos < limit && shift < 64) {
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            return result
        }

        fun readFixed32(): Int {
            if (pos + 4 > limit) {
                pos = limit
                return 0
            }
            val b0 = data[pos++].toInt() and 0xFF
            val b1 = data[pos++].toInt() and 0xFF
            val b2 = data[pos++].toInt() and 0xFF
            val b3 = data[pos++].toInt() and 0xFF
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        fun readFloat(): Float = java.lang.Float.intBitsToFloat(readFixed32())

        fun readSubReader(): ProtoReader {
            val length = readVarint32().coerceAtLeast(0)
            val end = (pos + length).coerceAtMost(limit)
            val sub = ProtoReader(data, pos, end)
            pos = end
            return sub
        }

        fun readString(): String {
            val length = readVarint32().coerceAtLeast(0)
            val end = (pos + length).coerceAtMost(limit)
            val str = String(data, pos, end - pos, StandardCharsets.UTF_8)
            pos = end
            return str
        }

        fun skipField(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> readVarint64()
                WIRE_64BIT -> pos = (pos + 8).coerceAtMost(limit)
                WIRE_LENGTH_DELIMITED -> {
                    val len = readVarint32().coerceAtLeast(0)
                    pos = (pos + len).coerceAtMost(limit)
                }
                WIRE_32BIT -> pos = (pos + 4).coerceAtMost(limit)
                else -> pos = limit
            }
        }
    }
}
