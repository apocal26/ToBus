package com.example.data.network

import android.util.Log
import com.example.data.model.StopArrival
import com.example.data.model.TransitStop
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Fetches, parses, and deduplicates stop arrival boards from GTT Official AVM Palina
 * (`palina.getTransitiOld`) and MaTO / 5T OpenTripPlanner GraphQL (`stoptimesForPatterns`).
 *
 * Parsing and deduplication helpers are pure Kotlin so JVM unit tests can exercise midnight
 * rollovers, GTT `*` realtime conventions, and delayed-bus slot collapsing without Android stubs.
 */
internal object GttStopArrivalsParser {

    private const val TAG = "GttStopArrivalsParser"
    const val STOP_ARRIVAL_HORIZON_SEC = 50L * 60L

    private val preferredMatoEndpointIdx = AtomicInteger(0)
    private val preferredMatoPrefixIdx = AtomicInteger(0)
    private val preferredMatoQueryIdx = AtomicInteger(0)

    /**
     * Queries GTT Official AVM Palina API (`palina.getTransitiOld`) trying stop code and stop ID.
     */
    fun queryGttPalinaStopArrivals(
        httpClient: OkHttpClient,
        userAgent: String,
        stop: TransitStop,
        nowMillis: Long,
    ): List<StopArrival> {
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
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Referer", "https://www.gtt.to.it/cms/percorari/arrivi")
                    .build()
                httpClient.newCall(request).execute().use { resp ->
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

    /**
     * Queries MaTO / 5T OpenTripPlanner GraphQL stop boards with automatic OTP2 -> OTP1 -> flat fallback.
     */
    fun queryMatoStopArrivals(
        httpClient: OkHttpClient,
        userAgent: String,
        endpoints: List<String>,
        stop: TransitStop,
        nowMillis: Long,
    ): List<StopArrival> {
        // Put stopId FIRST (MATO gtfsId is `gtt:<stop_id>`), then stopCode
        val rawIds = listOf(stop.stopId, stop.stopCode)
            .map { it.trim().substringAfterLast(":") }
            .filter { it.isNotEmpty() }
            .distinct()
        val prefixTemplates = listOf(
            { code: String -> "gtt:$code" },
            { code: String -> "GTT:$code" },
            { code: String -> code },
        )
        val prefPrefix = preferredMatoPrefixIdx.get().coerceIn(0, prefixTemplates.lastIndex)
        val orderedPrefixes = listOf(prefPrefix) + prefixTemplates.indices.filter { it != prefPrefix }

        val candidatePairs = buildList {
            for (pIdx in orderedPrefixes) {
                val buildId = prefixTemplates[pIdx]
                for (code in rawIds) {
                    add(pIdx to buildId(code))
                }
            }
        }.distinctBy { it.second }.take(4)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val nowSec = nowMillis / 1000L
        val romeTz = TimeZone.getTimeZone("Europe/Rome")
        val todayStartSec = Calendar.getInstance(romeTz).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000L

        if (endpoints.isEmpty()) return emptyList()
        val prefEp = preferredMatoEndpointIdx.get().coerceIn(0, endpoints.lastIndex)
        val orderedEndpointIndices = listOf(prefEp) + endpoints.indices.filter { it != prefEp }

        for (epIdx in orderedEndpointIndices) {
            val endpoint = endpoints[epIdx]
            for ((prefixIdx, gtfsStopId) in candidatePairs) {
                val escapedId = gtfsStopId.replace("\"", "")
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

                val prefQ = preferredMatoQueryIdx.get().coerceIn(0, queries.lastIndex)
                val orderedQueryIndices = listOf(prefQ) + queries.indices.filter { it != prefQ }
                for (qIdx in orderedQueryIndices) {
                    val query = queries[qIdx]
                    try {
                        val payload = JSONObject().put("query", query).toString()
                        val request = Request.Builder()
                            .url(endpoint)
                            .post(payload.toRequestBody(mediaType))
                            .header("User-Agent", userAgent)
                            .header("Accept", "application/json")
                            .header("Origin", "https://mato.muoversiatorino.it")
                            .build()

                        httpClient.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) continue
                            val body = resp.body?.string() ?: continue
                            val root = JSONObject(body)
                            val dataObj = root.optJSONObject("data")
                            if (dataObj != null && dataObj.isNull("stop")) {
                                // Stop ID prefix does not exist on this endpoint — skip remaining query shapes
                                break
                            }
                            val stopObj = dataObj?.optJSONObject("stop") ?: continue
                            val parsed = parseMatoStopTimes(stopObj, nowSec, todayStartSec)
                            if (parsed.isNotEmpty()) {
                                preferredMatoEndpointIdx.set(epIdx)
                                preferredMatoPrefixIdx.set(prefixIdx)
                                preferredMatoQueryIdx.set(qIdx)
                                return parsed
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "MATO stop arrivals skip ($endpoint, $gtfsStopId): ${e.message}")
                    }
                }
            }
        }
        return emptyList()
    }

    /**
     * Parses a MaTO GraphQL `stop` JSON object into upcoming [StopArrival] entries,
     * filtering out canceled, skipped, and drop-off-only (`pickupType = NONE`) trips.
     */
    fun parseMatoStopTimes(
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

    /**
     * Pure-Kotlin parser for GTT Official AVM Palina JSON (`palina.getTransitiOld`),
     * with zero `org.json` runtime dependency so JVM unit tests can validate it directly.
     *
     * Handles both explicit `PassaggiRT` / `Passaggi` / `PassaggiPR` arrays and GTT's `*`
     * suffix convention (e.g. `"10:18*"` inside `Passaggi`), plus midnight rollover in Europe/Rome.
     */
    fun parseGttPalinaJson(
        jsonText: String,
        nowMillis: Long,
    ): List<StopArrival> {
        val trimmed = jsonText.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val nowSec = nowMillis / 1000L
        val romeTz = TimeZone.getTimeZone("Europe/Rome")
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

        val rootList = MinimalJsonParser.parseArray(trimmed) ?: return emptyList()
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
     * Collapses duplicate stop arrivals across GTT Palina, MaTO Live, TripUpdates, and GPS estimates
     * while preserving the highest-priority realtime source and merging missing `vehicleId`/`tripId`/headsign.
     */
    fun deduplicateArrivals(list: List<StopArrival>): MutableList<StopArrival> {
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
