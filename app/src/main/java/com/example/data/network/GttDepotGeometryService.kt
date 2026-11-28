package com.example.data.network

import android.util.Log
import com.example.data.model.GeoPoint
import com.example.data.model.GttDepot
import com.example.data.model.GttFleetAnalyzer
import com.example.data.network.MinimalJsonParser.optArray
import com.example.data.network.MinimalJsonParser.optLong
import com.example.data.network.MinimalJsonParser.optString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos

/**
 * Fetches and caches real, wall-accurate OpenStreetMap polygon footprints for Turin's
 * active GTT bus & tram depots via Overpass API.
 *
 * Design guarantees:
 * - Zero guessed circular overlays: only verified closed OSM polygons (`polygon.size >= 4`)
 *   are emitted for map rendering and upgraded into [GttFleetAnalyzer] Point-in-Polygon geofencing.
 * - Strict negative filters reject residential buildings, non-GTT rail/logistics yards, metro stations,
 *   and tiny shelters so depot overlays NEVER spill onto residential neighborhoods.
 * - 30-day disk cache (`gtt_depots_osm_v1.json`) provides instant 0ms startup hydration after first load.
 */
internal class GttDepotGeometryService(
    private val cacheFileProvider: () -> File,
    private val httpClientProvider: () -> OkHttpClient,
    private val userAgent: String,
    private val overpassEndpoints: List<String>,
) {
    private val fetchMutex = Mutex()

    @Volatile
    private var memoryCache: List<GttDepot> = emptyList()

    @Volatile
    private var memoryFetchedAtMillis: Long = 0L

    @Volatile
    private var lastAttemptAtMillis: Long = 0L

    /**
     * Synchronous fast hydration from local disk cache (safe on IO thread or cold init).
     * Returns only depots with verified OSM polygons (`hasVerifiedPolygon == true`).
     */
    fun loadCachedDepotsFast(nowMillis: Long = System.currentTimeMillis()): List<GttDepot> {
        val inMem = memoryCache
        if (inMem.isNotEmpty()) return inMem
        val parsed = readCacheFromDisk(nowMillis, allowExpired = true)
        if (parsed != null && parsed.depots.isNotEmpty()) {
            memoryCache = parsed.depots
            memoryFetchedAtMillis = parsed.fetchedAtMillis
            GttFleetAnalyzer.updateResolvedDepots(parsed.depots)
            return parsed.depots
        }
        return emptyList()
    }

    /**
     * Ensures real OSM depot polygons are loaded from fresh disk cache or fetched from Overpass.
     * Thread-safe with mutex deduplication and retry backoff on transient Overpass errors.
     */
    suspend fun fetchOrLoadDepots(
        forceRefresh: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<GttDepot> = withContext(Dispatchers.IO) {
        if (!forceRefresh && memoryCache.isNotEmpty() && (nowMillis - memoryFetchedAtMillis) < CACHE_TTL_MS) {
            return@withContext memoryCache
        }

        fetchMutex.withLock {
            val now = System.currentTimeMillis()
            if (!forceRefresh && memoryCache.isNotEmpty() && (now - memoryFetchedAtMillis) < CACHE_TTL_MS) {
                return@withLock memoryCache
            }

            if (!forceRefresh) {
                val disk = readCacheFromDisk(now, allowExpired = false)
                if (disk != null && disk.depots.isNotEmpty()) {
                    memoryCache = disk.depots
                    memoryFetchedAtMillis = disk.fetchedAtMillis
                    GttFleetAnalyzer.updateResolvedDepots(disk.depots)
                    return@withLock disk.depots
                }
            }

            if (!forceRefresh && lastAttemptAtMillis > 0L && (now - lastAttemptAtMillis) < FAILURE_RETRY_COOLDOWN_MS) {
                return@withLock memoryCache.ifEmpty {
                    readCacheFromDisk(now, allowExpired = true)?.depots.orEmpty()
                }
            }
            lastAttemptAtMillis = now

            val fetched = queryOverpassTurinDepots()
            if (fetched.isNotEmpty()) {
                memoryCache = fetched
                memoryFetchedAtMillis = now
                GttFleetAnalyzer.updateResolvedDepots(fetched)
                writeCacheToDisk(fetched, now)
                Log.i(TAG, "Loaded ${fetched.size} verified OSM GTT depot polygons")
                return@withLock fetched
            }

            // Fallback to expired disk cache if Overpass is temporarily unreachable
            val staleDisk = readCacheFromDisk(now, allowExpired = true)
            if (staleDisk != null && staleDisk.depots.isNotEmpty()) {
                memoryCache = staleDisk.depots
                memoryFetchedAtMillis = staleDisk.fetchedAtMillis
                GttFleetAnalyzer.updateResolvedDepots(staleDisk.depots)
                return@withLock staleDisk.depots
            }
            memoryCache
        }
    }

    fun clearCache(): Int {
        memoryCache = emptyList()
        memoryFetchedAtMillis = 0L
        lastAttemptAtMillis = 0L
        return runCatching {
            val f = cacheFileProvider()
            if (f.exists() && f.delete()) 1 else 0
        }.getOrDefault(0)
    }

    private fun queryOverpassTurinDepots(): List<GttDepot> {
        val mediaType = "application/x-www-form-urlencoded; charset=utf-8".toMediaType()
        val encodedBody = "data=" + URLEncoder.encode(OVERPASS_DEPOTS_QL, "UTF-8")
        val client = httpClientProvider()

        for (endpoint in overpassEndpoints) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(encodedBody.toRequestBody(mediaType))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.d(TAG, "Overpass depot HTTP ${resp.code} on $endpoint")
                        return@use
                    }
                    val body = resp.body?.string() ?: return@use
                    val parsed = parseOverpassDepotsJson(body)
                    if (parsed.isNotEmpty()) {
                        return parsed
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Overpass depot skip $endpoint: ${e.message}")
            }
        }
        return emptyList()
    }

    private data class DiskDepotCache(
        val fetchedAtMillis: Long,
        val depots: List<GttDepot>,
    )

    private fun readCacheFromDisk(nowMillis: Long, allowExpired: Boolean): DiskDepotCache? {
        return runCatching {
            val file = cacheFileProvider()
            if (!file.exists() || file.length() <= 0L) return null
            val root = MinimalJsonParser.parseObject(file.readText(Charsets.UTF_8)) ?: return null
            if (root.optLong("version", 0L).toInt() != CACHE_SCHEMA_VERSION) return null
            val fetchedAt = root.optLong("fetchedAtMillis", 0L)
            if (fetchedAt <= 0L) return null
            if (!allowExpired && (nowMillis - fetchedAt) > CACHE_TTL_MS) return null
            val arr = root.optArray("depots") ?: return null
            val depots = ArrayList<GttDepot>(arr.size)
            for (item in arr) {
                val obj = item as? Map<*, *> ?: continue
                val name = obj.optString("name", "").trim()
                val lat = obj.optDouble("lat")
                val lon = obj.optDouble("lon")
                val radius = obj.optDouble("radiusMeters")
                val polyArr = obj.optArray("polygon") ?: continue
                if (name.isEmpty() || !lat.isFinite() || !lon.isFinite() || !radius.isFinite()) continue
                val ring = ArrayList<GeoPoint>(polyArr.size)
                for (pairAny in polyArr) {
                    val pair = pairAny as? List<*> ?: continue
                    if (pair.size < 2) continue
                    val pLat = pair[0].toFiniteDouble()
                    val pLon = pair[1].toFiniteDouble()
                    if (pLat.isFinite() && pLon.isFinite()) {
                        ring.add(GeoPoint(pLat, pLon))
                    }
                }
                if (ring.size >= 4) {
                    depots.add(
                        GttDepot(
                            name = name,
                            lat = lat,
                            lon = lon,
                            radiusMeters = radius.coerceIn(30.0, 500.0),
                            polygon = ring,
                        ),
                    )
                }
            }
            if (depots.isEmpty()) null else DiskDepotCache(fetchedAt, depots)
        }.getOrNull()
    }

    private fun writeCacheToDisk(depots: List<GttDepot>, fetchedAtMillis: Long) {
        runCatching {
            val target = cacheFileProvider()
            target.parentFile?.mkdirs()
            val verified = depots.filter { it.hasVerifiedPolygon }
            val sb = StringBuilder(verified.size * 512)
            sb.append("{\"version\":").append(CACHE_SCHEMA_VERSION)
                .append(",\"fetchedAtMillis\":").append(fetchedAtMillis)
                .append(",\"depots\":[")
            verified.forEachIndexed { idx, depot ->
                if (idx > 0) sb.append(',')
                sb.append("{\"name\":\"").append(escapeJson(depot.name)).append('"')
                    .append(",\"lat\":").append(depot.lat)
                    .append(",\"lon\":").append(depot.lon)
                    .append(",\"radiusMeters\":").append(depot.radiusMeters)
                    .append(",\"polygon\":[")
                depot.polygon.forEachIndexed { pIdx, pt ->
                    if (pIdx > 0) sb.append(',')
                    sb.append('[').append(pt.lat).append(',').append(pt.lon).append(']')
                }
                sb.append("]}")
            }
            sb.append("]}")
            val payload = sb.toString()

            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            }
        }.onFailure { err ->
            Log.w(TAG, "Unable to persist OSM depot cache: ${err.message}")
        }
    }

    companion object {
        private const val TAG = "GttDepotGeometry"
        internal const val CACHE_FILE_NAME = "gtt_depots_osm_v1.json"
        private const val CACHE_SCHEMA_VERSION = 1
        private const val CACHE_TTL_MS = 30L * 24L * 60L * 60L * 1000L
        private const val FAILURE_RETRY_COOLDOWN_MS = 10L * 60L * 1000L

        private const val MIN_LAT = 45.010
        private const val MAX_LAT = 45.155
        private const val MIN_LON = 7.560
        private const val MAX_LON = 7.765

        private const val MIN_DEPOT_AREA_SQM = 1_800.0
        private const val MAX_DEPOT_AREA_SQM = 250_000.0
        private const val CANONICAL_NAME_MAX_DIST_METERS = 2_500.0
        private const val UNNAMED_DEPOT_MAX_DIST_METERS = 1_200.0

        private val OVERPASS_DEPOTS_QL = """
            [out:json][timeout:18];
            (
              way["landuse"="depot"]($MIN_LAT,$MIN_LON,$MAX_LAT,$MAX_LON);
              relation["landuse"="depot"]($MIN_LAT,$MIN_LON,$MAX_LAT,$MAX_LON);
              way["depot"~"bus|tram|public_transport",i]($MIN_LAT,$MIN_LON,$MAX_LAT,$MAX_LON);
              relation["depot"~"bus|tram|public_transport",i]($MIN_LAT,$MIN_LON,$MAX_LAT,$MAX_LON);
              way["building"="depot"]($MIN_LAT,$MIN_LON,$MAX_LAT,$MAX_LON);
              relation["building"="depot"]($MIN_LAT,$MIN_LON,$MAX_LAT,$MAX_LON);
              way["operator"~"GTT|Gruppo Torinese Trasporti",i]["landuse"]($MIN_LAT,$MIN_LON,$MAX_LAT,$MAX_LON);
              relation["operator"~"GTT|Gruppo Torinese Trasporti",i]["landuse"]($MIN_LAT,$MIN_LON,$MAX_LAT,$MAX_LON);
              way["name"~"(Deposito|Stabilimento|Rimessa|Comprensorio).*(GTT|Gerbido|San Paolo|Venaria|Tortona|Nizza|Novara|Fiochetto|Sassi)|GTT.*(Deposito|Stabilimento|Rimessa|Comprensorio)",i]($MIN_LAT,$MIN_LON,$MAX_LAT,$MAX_LON);
              relation["name"~"(Deposito|Stabilimento|Rimessa|Comprensorio).*(GTT|Gerbido|San Paolo|Venaria|Tortona|Nizza|Novara|Fiochetto|Sassi)|GTT.*(Deposito|Stabilimento|Rimessa|Comprensorio)",i]($MIN_LAT,$MIN_LON,$MAX_LAT,$MAX_LON);
            );
            out tags geom;
        """.trimIndent()

        private data class CanonicalDepotSeed(
            val displayName: String,
            val keywords: List<String>,
            val lat: Double,
            val lon: Double,
        )

        private val CANONICAL_SEEDS = listOf(
            CanonicalDepotSeed("Deposito Gerbido", listOf("gerbido", "gorini"), 45.0345, 7.6012),
            CanonicalDepotSeed("Deposito San Paolo", listOf("san paolo", "s. paolo", "trapani"), 45.0642, 7.6328),
            CanonicalDepotSeed("Deposito Venaria", listOf("venaria", "amati"), 45.1321, 7.6254),
            CanonicalDepotSeed("Deposito Tortona", listOf("tortona", "officina centrale", "manin"), 45.0746, 7.7047),
            CanonicalDepotSeed("Deposito Nizza", listOf("nizza", "bramante"), 45.0445, 7.6651),
            CanonicalDepotSeed("Deposito Novara", listOf("novara"), 45.0807, 7.6990),
            CanonicalDepotSeed("Deposito Fiochetto", listOf("fiochetto"), 45.0761, 7.6891),
            CanonicalDepotSeed("Deposito Sassi", listOf("sassi"), 45.0789, 7.7347),
        )

        private val FORBIDDEN_BUILDINGS = setOf(
            "apartments", "residential", "house", "terrace", "detached", "semidetached_house",
            "school", "university", "college", "kindergarten", "church", "cathedral", "chapel",
            "hospital", "clinic", "retail", "commercial", "supermarket", "hotel", "hostel",
            "office", "stadium", "grandstand", "parking", "garage", "garages",
        )

        private val FORBIDDEN_LANDUSE = setOf(
            "residential", "retail", "commercial", "cemetery", "meadow", "grass", "forest",
            "farmland", "allotments", "recreation_ground", "construction", "quarry", "military",
        )

        private val FORBIDDEN_OPERATORS_OR_NAMES = listOf(
            "trenitalia", "rfi", "rete ferroviaria", "ferrovie dello stato", "italo", "sncf",
            "dhl", "amazon", "brt", "bartolini", "gls", "sda", "ups", "fedex", "poste italiane",
            "amiat", "iren", "smat", "stellantis", "fiat", "metro collegno", "metropolitana",
            "smistamento", "scalo merci",
        )

        private data class ParsedDepotCandidate(
            val canonicalName: String,
            val isOuterLanduse: Boolean,
            val hasExplicitGtt: Boolean,
            val areaSqm: Double,
            val depot: GttDepot,
        )

        /**
         * Pure, unit-testable Overpass JSON parser for Turin GTT bus & tram depot polygons.
         */
        internal fun parseOverpassDepotsJson(jsonText: String): List<GttDepot> {
            val root = runCatching { MinimalJsonParser.parseObject(jsonText) }.getOrNull() ?: return emptyList()
            val elements = root.optArray("elements") ?: return emptyList()
            val candidates = mutableListOf<ParsedDepotCandidate>()

            for (item in elements) {
                val el = item as? Map<*, *> ?: continue
                val type = el.optString("type", "")
                if (type != "way" && type != "relation") continue
                val tags = el["tags"] as? Map<*, *> ?: continue

                val ring = when (type) {
                    "way" -> extractClosedWayRing(el.optArray("geometry"))
                    "relation" -> extractClosedRelationOuterRing(el.optArray("members"))
                    else -> null
                } ?: continue

                val geom = computePolygonMetrics(ring) ?: continue
                if (geom.areaSqm < MIN_DEPOT_AREA_SQM || geom.areaSqm > MAX_DEPOT_AREA_SQM) continue
                if (geom.centroidLat !in MIN_LAT..MAX_LAT || geom.centroidLon !in MIN_LON..MAX_LON) continue

                val candidate = classifyAndValidateCandidate(tags, ring, geom) ?: continue
                candidates.add(candidate)
            }

            if (candidates.isEmpty()) return emptyList()

            // Sort: prefer outer compound landuse polygons and larger enclosures first so inner sheds
            // inside an outer yard are cleanly subsumed.
            val sorted = candidates.sortedWith(
                compareByDescending<ParsedDepotCandidate> { it.isOuterLanduse }
                    .thenByDescending { it.areaSqm },
            )

            val selectedByCanonical = LinkedHashMap<String, ParsedDepotCandidate>()
            for (cand in sorted) {
                val key = cand.canonicalName.lowercase(Locale.ROOT)
                val existing = selectedByCanonical[key]
                if (existing != null) {
                    // Already have a stronger/larger outer perimeter for this canonical depot
                    continue
                }
                // Drop smaller inner sheds whose centroid falls inside any already-selected larger compound
                val nestedInsideExisting = selectedByCanonical.values.any { larger ->
                    larger.areaSqm >= cand.areaSqm &&
                        GttFleetAnalyzer.isPointInOrNearPolygon(
                            lat = cand.depot.lat,
                            lon = cand.depot.lon,
                            polygon = larger.depot.polygon,
                            marginMeters = 25.0,
                        )
                }
                if (nestedInsideExisting) continue
                selectedByCanonical[key] = cand
            }

            return selectedByCanonical.values.map { it.depot }
        }

        private fun classifyAndValidateCandidate(
            tags: Map<*, *>,
            ring: List<GeoPoint>,
            geom: PolygonMetrics,
        ): ParsedDepotCandidate? {
            val building = tags.optString("building", "").trim().lowercase(Locale.ROOT)
            val landuse = tags.optString("landuse", "").trim().lowercase(Locale.ROOT)
            val depotTag = tags.optString("depot", "").trim().lowercase(Locale.ROOT)
            val railway = tags.optString("railway", "").trim().lowercase(Locale.ROOT)
            val station = tags.optString("station", "").trim().lowercase(Locale.ROOT)
            val subway = tags.optString("subway", "").trim().lowercase(Locale.ROOT)
            val amenity = tags.optString("amenity", "").trim().lowercase(Locale.ROOT)
            val publicTransport = tags.optString("public_transport", "").trim().lowercase(Locale.ROOT)
            val operator = tags.optString("operator", "").trim()
            val brand = tags.optString("brand", "").trim()
            val network = tags.optString("network", "").trim()
            val rawName = tags.optString("name", "").trim()

            val nameLower = rawName.lowercase(Locale.ROOT)
            val operatorBlob = "$operator $brand $network".lowercase(Locale.ROOT)
            val fullTextBlob = "$nameLower $operatorBlob"

            // 1. Hard negative filters: never allow residential, non-transit, metro, or rail freight yards
            if (building in FORBIDDEN_BUILDINGS) return null
            if (landuse in FORBIDDEN_LANDUSE) return null
            if (railway == "station" || railway == "halt" || railway == "subway_entrance") return null
            if (station == "subway" || subway == "yes") return null
            if (FORBIDDEN_OPERATORS_OR_NAMES.any { fullTextBlob.contains(it) }) return null

            val isBusOrTramDepotTag = depotTag.contains("bus") ||
                depotTag.contains("tram") ||
                depotTag.contains("public_transport") ||
                depotTag.contains("trolleybus")
            val isForbiddenDepotType = depotTag.isNotEmpty() && !isBusOrTramDepotTag && (
                depotTag.contains("train") ||
                    depotTag.contains("rail") ||
                    depotTag.contains("subway") ||
                    depotTag.contains("metro") ||
                    depotTag.contains("freight") ||
                    depotTag.contains("cargo") ||
                    depotTag.contains("fuel") ||
                    depotTag.contains("oil") ||
                    depotTag.contains("military")
                )
            if (isForbiddenDepotType) return null

            val hasDepotWordInName = nameLower.contains("deposito") ||
                nameLower.contains("stabilimento") ||
                nameLower.contains("rimessa") ||
                nameLower.contains("comprensorio") ||
                nameLower.contains("officina centrale") ||
                nameLower.contains("autostazione")

            if ((nameLower.contains("stazione") || nameLower.contains("fermata") || nameLower.contains("parcheggio")) &&
                !hasDepotWordInName
            ) {
                return null
            }

            // 2. Positive transit/depot verification
            val hasExplicitGtt = fullTextBlob.contains("gtt") ||
                fullTextBlob.contains("gruppo torinese trasporti") ||
                fullTextBlob.contains("atm torino")
            val hasDepotTag = landuse == "depot" ||
                building == "depot" ||
                isBusOrTramDepotTag ||
                amenity == "bus_station" ||
                publicTransport == "station" ||
                hasDepotWordInName
            if (!hasDepotTag) return null

            // 3. Match to canonical Turin GTT depot
            val matchedByName = CANONICAL_SEEDS.firstOrNull { seed ->
                seed.keywords.any { kw -> nameLower.contains(kw) } &&
                    GttFleetAnalyzer.haversineMeters(geom.centroidLat, geom.centroidLon, seed.lat, seed.lon) <=
                    CANONICAL_NAME_MAX_DIST_METERS
            }

            val matchedByProximity = if (matchedByName == null && (hasExplicitGtt || isBusOrTramDepotTag)) {
                CANONICAL_SEEDS
                    .map { seed ->
                        seed to GttFleetAnalyzer.haversineMeters(geom.centroidLat, geom.centroidLon, seed.lat, seed.lon)
                    }
                    .filter { (_, dist) -> dist <= UNNAMED_DEPOT_MAX_DIST_METERS }
                    .minByOrNull { it.second }
                    ?.first
            } else {
                null
            }

            val resolvedName = when {
                matchedByName != null -> matchedByName.displayName
                matchedByProximity != null -> matchedByProximity.displayName
                hasExplicitGtt && (landuse == "depot" || isBusOrTramDepotTag || hasDepotWordInName) -> {
                    normalizeCustomDepotName(rawName) ?: return null
                }
                else -> return null
            }

            val boundingRadius = ring.maxOf { pt ->
                GttFleetAnalyzer.haversineMeters(geom.centroidLat, geom.centroidLon, pt.lat, pt.lon)
            }.coerceIn(35.0, 480.0)

            return ParsedDepotCandidate(
                canonicalName = resolvedName,
                isOuterLanduse = landuse == "depot" || (building.isEmpty() && isBusOrTramDepotTag),
                hasExplicitGtt = hasExplicitGtt,
                areaSqm = geom.areaSqm,
                depot = GttDepot(
                    name = resolvedName,
                    lat = geom.centroidLat,
                    lon = geom.centroidLon,
                    radiusMeters = boundingRadius,
                    polygon = ring,
                ),
            )
        }

        private fun normalizeCustomDepotName(rawName: String): String? {
            val cleaned = rawName.trim()
            if (cleaned.isEmpty()) return null
            val lower = cleaned.lowercase(Locale.ROOT)
            if (lower.startsWith("deposito ")) return cleaned
            if (lower.startsWith("stabilimento ")) {
                return "Deposito " + cleaned.removePrefix("Stabilimento ").removePrefix("stabilimento ").trim()
            }
            return cleaned
        }

        private fun extractClosedWayRing(geometry: List<Any?>?): List<GeoPoint>? {
            if (geometry == null || geometry.size < 4) return null
            val pts = ArrayList<GeoPoint>(geometry.size + 1)
            for (item in geometry) {
                val node = item as? Map<*, *> ?: continue
                val lat = node.optDouble("lat")
                val lon = node.optDouble("lon")
                if (lat.isFinite() && lon.isFinite()) {
                    pts.add(GeoPoint(lat, lon))
                }
            }
            return closeAndCleanRing(pts)
        }

        private fun extractClosedRelationOuterRing(members: List<Any?>?): List<GeoPoint>? {
            if (members == null || members.isEmpty()) return null
            val outerSegments = mutableListOf<List<GeoPoint>>()
            for (item in members) {
                val m = item as? Map<*, *> ?: continue
                if (m.optString("type", "") != "way") continue
                val role = m.optString("role", "")
                if (role.isNotEmpty() && role != "outer") continue
                val geom = m.optArray("geometry") ?: continue
                if (geom.size < 2) continue
                val seg = ArrayList<GeoPoint>(geom.size)
                for (nodeAny in geom) {
                    val node = nodeAny as? Map<*, *> ?: continue
                    val lat = node.optDouble("lat")
                    val lon = node.optDouble("lon")
                    if (lat.isFinite() && lon.isFinite()) {
                        seg.add(GeoPoint(lat, lon))
                    }
                }
                if (seg.size >= 2) outerSegments.add(seg)
            }
            if (outerSegments.isEmpty()) return null

            if (outerSegments.size == 1) {
                return closeAndCleanRing(outerSegments.first())
            }

            // Stitch multi-way outer rings greedily and return the largest valid closed ring
            val remaining = outerSegments.toMutableList()
            val closedRings = mutableListOf<List<GeoPoint>>()
            while (remaining.isNotEmpty()) {
                val chain = remaining.removeAt(0).toMutableList()
                var stitched = true
                while (stitched && remaining.isNotEmpty()) {
                    stitched = false
                    val tail = chain.last()
                    var bestIdx = -1
                    var bestReverse = false
                    var bestDist = 35.0
                    for (idx in remaining.indices) {
                        val seg = remaining[idx]
                        val dStart = GttFleetAnalyzer.haversineMeters(tail.lat, tail.lon, seg.first().lat, seg.first().lon)
                        if (dStart < bestDist) {
                            bestDist = dStart
                            bestIdx = idx
                            bestReverse = false
                        }
                        val dEnd = GttFleetAnalyzer.haversineMeters(tail.lat, tail.lon, seg.last().lat, seg.last().lon)
                        if (dEnd < bestDist) {
                            bestDist = dEnd
                            bestIdx = idx
                            bestReverse = true
                        }
                    }
                    if (bestIdx >= 0) {
                        val nextSeg = remaining.removeAt(bestIdx)
                        val ordered = if (bestReverse) nextSeg.asReversed() else nextSeg
                        chain.addAll(ordered.drop(1))
                        stitched = true
                    }
                }
                closeAndCleanRing(chain)?.let { closedRings.add(it) }
            }

            return closedRings.maxByOrNull { ring -> computePolygonMetrics(ring)?.areaSqm ?: 0.0 }
        }

        private fun closeAndCleanRing(raw: List<GeoPoint>): List<GeoPoint>? {
            if (raw.size < 4) return null
            val cleaned = ArrayList<GeoPoint>(raw.size + 1)
            for (pt in raw) {
                val prev = cleaned.lastOrNull()
                if (prev == null ||
                    abs(prev.lat - pt.lat) > 1e-7 ||
                    abs(prev.lon - pt.lon) > 1e-7
                ) {
                    cleaned.add(pt)
                }
            }
            if (cleaned.size < 3) return null
            val first = cleaned.first()
            val last = cleaned.last()
            val closingGap = GttFleetAnalyzer.haversineMeters(first.lat, first.lon, last.lat, last.lon)
            if (closingGap > 40.0) return null
            if (closingGap > 0.2) {
                cleaned.add(first)
            } else {
                cleaned[cleaned.lastIndex] = first
            }
            return if (cleaned.size >= 4) cleaned else null
        }

        private data class PolygonMetrics(
            val areaSqm: Double,
            val centroidLat: Double,
            val centroidLon: Double,
        )

        private fun computePolygonMetrics(ring: List<GeoPoint>): PolygonMetrics? {
            if (ring.size < 4) return null
            val refLat = ring[0].lat
            val refLon = ring[0].lon
            val mPerDegLat = 110_540.0
            val mPerDegLon = 111_320.0 * cos(Math.toRadians(refLat)).coerceAtLeast(0.2)

            var twiceSignedArea = 0.0
            var cxAcc = 0.0
            var cyAcc = 0.0
            val count = ring.size - 1
            for (i in 0 until count) {
                val p1 = ring[i]
                val p2 = ring[i + 1]
                val x1 = (p1.lon - refLon) * mPerDegLon
                val y1 = (p1.lat - refLat) * mPerDegLat
                val x2 = (p2.lon - refLon) * mPerDegLon
                val y2 = (p2.lat - refLat) * mPerDegLat
                val cross = x1 * y2 - x2 * y1
                twiceSignedArea += cross
                cxAcc += (x1 + x2) * cross
                cyAcc += (y1 + y2) * cross
            }
            val areaSqm = abs(twiceSignedArea) * 0.5
            if (!areaSqm.isFinite() || areaSqm < 1.0) return null

            val (cLat, cLon) = if (abs(twiceSignedArea) > 1e-3) {
                val cx = cxAcc / (3.0 * twiceSignedArea)
                val cy = cyAcc / (3.0 * twiceSignedArea)
                val candLat = refLat + (cy / mPerDegLat)
                val candLon = refLon + (cx / mPerDegLon)
                if (candLat.isFinite() && candLon.isFinite()) {
                    candLat to candLon
                } else {
                    ring.take(count).map { it.lat }.average() to ring.take(count).map { it.lon }.average()
                }
            } else {
                ring.take(count).map { it.lat }.average() to ring.take(count).map { it.lon }.average()
            }

            return PolygonMetrics(
                areaSqm = areaSqm,
                centroidLat = cLat,
                centroidLon = cLon,
            )
        }

        private fun Map<*, *>.optDouble(key: String): Double = this[key].toFiniteDouble()

        private fun Any?.toFiniteDouble(): Double = when (this) {
            is Number -> this.toDouble().takeIf { it.isFinite() } ?: Double.NaN
            is String -> this.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: Double.NaN
            else -> Double.NaN
        }

        private fun escapeJson(value: String): String = buildString(value.length + 4) {
            for (ch in value) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
    }
}
