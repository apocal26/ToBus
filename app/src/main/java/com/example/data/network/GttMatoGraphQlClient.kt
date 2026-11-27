package com.example.data.network

import android.util.Log
import com.example.data.model.BusVehicle
import com.example.data.model.LineSummary
import com.example.data.model.VehicleStopStatus
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Lightweight client for MaTO / 5T OpenTripPlanner GraphQL vehicle positions and line catalog
 * fallback queries, remembering the fastest working endpoint/query pair across polls.
 */
internal class GttMatoGraphQlClient(
    private val httpClientProvider: () -> OkHttpClient,
    private val userAgent: String,
    private val catalogEndpoints: List<String>,
) {
    @Volatile
    var preferredVpEndpointIdx: Int = 0
        private set

    @Volatile
    var preferredVpQueryIdx: Int = 0
        private set

    fun resetPreferredEndpoints() {
        preferredVpEndpointIdx = 0
        preferredVpQueryIdx = 0
    }

    /**
     * Queries MaTO / 5T OpenTripPlanner GraphQL `vehiclePositions` for [lineCode]
     * as an instant REST complement to the MaTO MQTT WebSocket stream.
     */
    fun fetchVehiclePositions(
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
        val client = httpClientProvider()

        val endpointOrder = VP_ENDPOINTS.indices.sortedBy { if (it == preferredVpEndpointIdx) 0 else 1 }
        val queryOrder = queries.indices.sortedBy { if (it == preferredVpQueryIdx) 0 else 1 }

        for (epIdx in endpointOrder) {
            val endpoint = VP_ENDPOINTS[epIdx]
            var endpointHttpFailed = false
            for (qIdx in queryOrder) {
                if (endpointHttpFailed) break
                val query = queries[qIdx]
                try {
                    val payloadJson = JSONObject().put("query", query).toString()
                    val req = Request.Builder()
                        .url(endpoint)
                        .header("User-Agent", userAgent)
                        .header("Accept", "application/json")
                        .header("Origin", "https://mato.muoversiatorino.it")
                        .post(payloadJson.toRequestBody(mediaType))
                        .build()

                    client.newCall(req).execute().use { resp ->
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
                            preferredVpEndpointIdx = epIdx
                            preferredVpQueryIdx = qIdx
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
     * Fallback query fetching the Turin GTT route list from MaTO GraphQL when static GTFS
     * `routes.txt` has not been cached yet.
     */
    fun fetchLineCatalog(): List<LineSummary> {
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
        val client = httpClientProvider()

        for (endpoint in catalogEndpoints) {
            for (query in queries) {
                try {
                    val payload = JSONObject().put("query", query).toString()
                    val request = Request.Builder()
                        .url(endpoint)
                        .post(payload.toRequestBody(mediaType))
                        .header("User-Agent", userAgent)
                        .header("Accept", "application/json")
                        .header("Origin", "https://mato.muoversiatorino.it")
                        .build()

                    client.newCall(request).execute().use { response ->
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

    companion object {
        private const val TAG = "GttMatoGraphQlClient"

        private val VP_ENDPOINTS = listOf(
            "https://mato.muoversiatorino.it/otp/gtfs/v1",
            "https://plan.muoversiatorino.it/otp/gtfs/v1",
            "https://mato.muoversiatorino.it/otp/routers/mato/index/graphql",
            "https://plan.muoversiatorino.it/otp/routers/mato/index/graphql",
            "https://map.5t.torino.it/otp/routers/default/index/graphql",
            "https://mato.muoversiatorino.it/otp/routers/default/index/graphql",
        )
    }
}
