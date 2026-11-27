package com.example.data.network

import android.os.SystemClock
import android.util.Log
import com.example.data.model.GeoPoint
import com.example.data.model.RoutePattern
import com.example.data.model.TransitStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Loads, simplifies, stitches, and caches Turin GTT street-by-street route geometries & stop lists:
 * 1. Same-day RAM / Disk cache (`route_v5_<LINE>_<YYYYMMDD>.json`)
 * 2. Daily-updated MaTO / 5T GraphQL with `tripsForDate(serviceDate)` + live `activeTripIds`
 * 3. Official GTT Static GTFS ZIP filtered by today's calendar & live trips
 * 4. OpenStreetMap Overpass API last-resort fallback
 */
internal class GttRouteGeometryService(
    private val cacheDirProvider: () -> File,
    private val matoFastHttpClientProvider: () -> OkHttpClient,
    private val routeHttpClientProvider: () -> OkHttpClient,
    private val staticRouteStoreProvider: () -> GttStaticRouteStore,
    private val stopNameCache: ConcurrentHashMap<String, String>,
    private val userAgent: String,
    private val matoEndpoints: List<String>,
    private val overpassEndpoints: List<String>,
) {
    private val routeMemoryCache = ConcurrentHashMap<String, List<RoutePattern>>()

    @Volatile
    var lastRouteLoadSummary: String = "Nessun percorso caricato"
        private set

    fun getMemoryCachedPatterns(normalizedLine: String): List<RoutePattern> =
        routeMemoryCache[normalizedLine].orEmpty()

    fun cacheRamCount(): Int = routeMemoryCache.size

    fun cacheDiskCount(): Int =
        runCatching { cacheDirProvider().listFiles()?.count { it.isFile } ?: 0 }.getOrDefault(0)

    fun clearCaches(): Int {
        routeMemoryCache.clear()
        var deleted = 0
        runCatching {
            cacheDirProvider().listFiles()?.forEach { f ->
                if (f.isFile && f.delete()) deleted++
            }
        }
        return deleted
    }

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
        val diskFile = File(cacheDirProvider(), "route_v5_${safeKey}_$todayYyyyMmDd.json")
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
            fetched = staticRouteStoreProvider().getRoutePatternsForLine(normalized, nowMillis, activeTripIds)
            if (fetched.isNotEmpty()) routeSourceTag = "GTFS Statico"
        }

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
     * and finally bounded network fetch so stop interactions stay instantaneous.
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
        val diskFile = File(cacheDirProvider(), "route_v5_${safeKey}_$todayYyyyMmDd.json")
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

        val store = staticRouteStoreProvider()
        if (store.hasCachedGtfsZip()) {
            val fromZip = runCatching {
                store.getRoutePatternsForLine(normalized, nowMillis, activeTripIds)
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

    private fun cacheRouteStops(patterns: List<RoutePattern>) {
        for (p in patterns) {
            for (st in p.stops) {
                if (st.name.isNotBlank()) {
                    if (st.stopId.isNotBlank()) stopNameCache[st.stopId] = st.name
                    if (st.stopCode.isNotBlank()) stopNameCache[st.stopCode] = st.name
                }
            }
        }
    }

    private fun queryMatoGraphQl(
        lineCode: String,
        todayYyyyMmDd: String,
        activeTripIds: Set<String> = emptySet(),
        matchTargetLine: String = lineCode,
    ): List<RoutePattern> {
        val escapedName = lineCode.replace("\"", "")
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
        val client = matoFastHttpClientProvider()

        for (endpoint in matoEndpoints) {
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
        val encodedBody = "data=" + URLEncoder.encode(ql, "UTF-8")
        val client = routeHttpClientProvider()

        for (endpoint in overpassEndpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(encodedBody.toRequestBody(mediaType))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .build()

                client.newCall(req).execute().use { resp ->
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

        return candidates
            .sortedWith(compareByDescending<ScoredRelation> { it.isGtt }.thenByDescending { it.totalPoints })
            .take(2)
            .mapIndexed { idx, item -> item.pattern.copy(directionId = idx % 2) }
    }

    companion object {
        private const val TAG = "GttRouteGeometry"
        private const val ROUTE_CACHE_TTL_MS = 6L * 60L * 60L * 1000L
        private val SAFE_ROUTE_KEY_REGEX = Regex("[^A-Z0-9_]")
        private val OVERPASS_SAFE_REF_REGEX = Regex("[^A-Za-z0-9/+\\- ]")

        fun formatRomeYyyyMmDd(nowMillis: Long): String {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome")).apply {
                timeInMillis = nowMillis
            }
            val y = cal.get(Calendar.YEAR)
            val m = cal.get(Calendar.MONTH) + 1
            val d = cal.get(Calendar.DAY_OF_MONTH)
            return "%04d%02d%02d".format(y, m, d)
        }

        /**
         * Stitches consecutive OSM ways into clean continuous chains without drawing artificial
         * straight lines across gaps or reversed segments.
         */
        fun stitchConnectedWays(ways: List<List<GeoPoint>>): List<List<GeoPoint>> {
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
                    closeEnough(current.last(), way.first()) -> current.addAll(way.drop(1))
                    closeEnough(current.last(), way.last()) -> current.addAll(way.asReversed().drop(1))
                    closeEnough(current.first(), way.last()) -> current.addAll(0, way.dropLast(1))
                    closeEnough(current.first(), way.first()) -> current.addAll(0, way.asReversed().dropLast(1))
                    else -> chains.add(way.toMutableList())
                }
            }
            return chains
        }

        /** Decodes a Google-encoded polyline string (1e5 precision) into [GeoPoint] coordinates. */
        fun decodeGooglePolyline(encoded: String): List<GeoPoint> {
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

        /** Downsamples dense polylines to at most [maxPts] vertices while always preserving endpoints. */
        fun simplifySegment(points: List<GeoPoint>, maxPts: Int = 140): List<GeoPoint> {
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

        fun serializePatterns(patterns: List<RoutePattern>): String {
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

        fun parseCachedPatterns(raw: String): List<RoutePattern> {
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
    }
}
