package com.example.data.network

import android.content.Context
import android.util.Log
import com.example.data.model.GeoPoint
import com.example.data.model.RoutePattern
import com.example.data.model.StopArrival
import com.example.data.model.StopSearchEntry
import com.example.data.model.TransitStop
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * Official GTT Static GTFS route geometry store.
 *
 * Downloads the official GTT static GTFS archive (`gtt_gtfs.zip`) once and caches it locally
 * (refreshed every 14 days). Extracts exact street-by-street polylines directly from
 * `routes.txt` -> `trips.txt` -> `shapes.txt` via streaming [ZipFile] access with low RAM usage.
 */
class GttStaticRouteStore(context: Context) {

    private val appContext = context.applicationContext
    private val gtfsDir: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(appContext.filesDir, "gtt_gtfs").apply { mkdirs() }
    }
    private val gtfsZipFile: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(gtfsDir, "gtt_gtfs.zip")
    }
    private val stopIndexCacheFile: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        File(gtfsDir, "stop_search_index_v1.tsv")
    }
    private val downloadMutex = Mutex()
    private val stopIndexMutex = Mutex()
    @Volatile
    private var cachedStopSearchIndex: List<StopSearchEntry>? = null
    @Volatile
    private var cachedStopIndexZipStamp: String = ""

    private val downloadClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun hasCachedGtfsZip(): Boolean {
        return gtfsZipFile.exists() && gtfsZipFile.length() > MIN_VALID_ZIP_BYTES
    }

    suspend fun prefetchGtfsZip() = withContext(Dispatchers.IO) {
        ensureGtfsZipReady()
    }

    suspend fun getRoutePatternsForLine(
        lineCode: String,
        nowMillis: Long = System.currentTimeMillis(),
        activeTripIds: Set<String> = emptySet(),
    ): List<RoutePattern> = withContext(Dispatchers.IO) {
        val target = normalizeLineKey(lineCode)
        if (target.isEmpty()) return@withContext emptyList()

        val zipFile = ensureGtfsZipReady() ?: return@withContext emptyList()
        try {
            extractPatternsFromZip(zipFile, target, nowMillis, activeTripIds)
        } catch (e: ZipException) {
            Log.w(TAG, "GTFS zip corrotto per linea $target: ${e.message}")
            runCatching { zipFile.delete() }
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Errore lettura GTFS zip per linea $target: ${e.message}")
            emptyList()
        }
    }

    suspend fun getScheduledArrivalsForStop(
        stop: TransitStop,
        preferredLine: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<StopArrival> = withContext(Dispatchers.IO) {
        val zipFile = if (gtfsZipFile.exists() && gtfsZipFile.length() > MIN_VALID_ZIP_BYTES) {
            gtfsZipFile
        } else {
            ensureGtfsZipReady() ?: return@withContext emptyList()
        }
        try {
            extractScheduledArrivalsFromZip(zipFile, stop.stopId, preferredLine, nowMillis)
        } catch (e: Exception) {
            Log.d(TAG, "Orari statici non disponibili per fermata ${stop.stopId}: ${e.message}")
            emptyList()
        }
    }

    suspend fun resolveStopNames(stopIds: Set<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val cleaned = stopIds.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        if (cleaned.isEmpty()) return@withContext emptyMap()
        val zipFile = if (gtfsZipFile.exists() && gtfsZipFile.length() > MIN_VALID_ZIP_BYTES) {
            gtfsZipFile
        } else {
            ensureGtfsZipReady() ?: return@withContext emptyMap()
        }
        try {
            extractStopNamesFromZip(zipFile, cleaned)
        } catch (e: Exception) {
            Log.d(TAG, "Lookup nomi fermate fallito: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Builds (and caches in RAM + compact disk file) an indexed directory of all Turin GTFS stops
     * enriched with the exact lines and primary headsigns serving each individual physical pole (`palina`).
     */
    suspend fun getStopSearchIndex(
        nowMillis: Long = System.currentTimeMillis(),
    ): List<StopSearchEntry> = withContext(Dispatchers.IO) {
        val zipFile = if (gtfsZipFile.exists() && gtfsZipFile.length() > MIN_VALID_ZIP_BYTES) {
            gtfsZipFile
        } else {
            ensureGtfsZipReady() ?: return@withContext emptyList()
        }
        val stamp = "${zipFile.lastModified()}_${zipFile.length()}"
        cachedStopSearchIndex?.let { cached ->
            if (cached.isNotEmpty() && cachedStopIndexZipStamp == stamp) return@withContext cached
        }
        stopIndexMutex.withLock {
            cachedStopSearchIndex?.let { cached ->
                if (cached.isNotEmpty() && cachedStopIndexZipStamp == stamp) return@withContext cached
            }
            val fromDisk = readStopSearchIndexCache(stamp)
            if (!fromDisk.isNullOrEmpty()) {
                cachedStopSearchIndex = fromDisk
                cachedStopIndexZipStamp = stamp
                return@withLock fromDisk
            }
            val built = try {
                extractStopSearchIndexFromZip(zipFile, nowMillis)
            } catch (e: Exception) {
                Log.w(TAG, "Costruzione indice fermate fallita: ${e.message}")
                emptyList()
            }
            if (built.isNotEmpty()) {
                cachedStopSearchIndex = built
                cachedStopIndexZipStamp = stamp
                writeStopSearchIndexCache(stamp, built)
            }
            built
        }
    }

    private fun readStopSearchIndexCache(expectedStamp: String): List<StopSearchEntry>? {
        val file = stopIndexCacheFile
        if (!file.exists() || file.length() < 64L) return null
        return try {
            file.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                val header = reader.readLine() ?: return null
                if (header != "#V1|$expectedStamp") return null
                val out = ArrayList<StopSearchEntry>(4096)
                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotEmpty()) {
                        val parts = line.split('\t')
                        if (parts.size >= 7) {
                            val lat = parts[3].toDoubleOrNull()
                            val lon = parts[4].toDoubleOrNull()
                            if (lat != null && lon != null) {
                                val lines = if (parts[5].isEmpty()) emptyList() else parts[5].split('|')
                                val headsigns = if (parts[6].isEmpty()) emptyList() else parts[6].split('|')
                                out.add(
                                    StopSearchEntry(
                                        stopId = parts[0],
                                        stopCode = parts[1],
                                        name = parts[2],
                                        lat = lat,
                                        lon = lon,
                                        lines = lines,
                                        headsigns = headsigns,
                                    ),
                                )
                            }
                        }
                    }
                    line = reader.readLine()
                }
                out.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Cache disco indice fermate non leggibile: ${e.message}")
            null
        }
    }

    private fun writeStopSearchIndexCache(stamp: String, entries: List<StopSearchEntry>) {
        try {
            val tmp = File(gtfsDir, "stop_search_index_v1.tsv.tmp")
            tmp.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write("#V1|")
                writer.write(stamp)
                writer.newLine()
                for (e in entries) {
                    writer.write(sanitizeTsvField(e.stopId))
                    writer.write('\t'.code)
                    writer.write(sanitizeTsvField(e.stopCode))
                    writer.write('\t'.code)
                    writer.write(sanitizeTsvField(e.name))
                    writer.write('\t'.code)
                    writer.write(e.lat.toString())
                    writer.write('\t'.code)
                    writer.write(e.lon.toString())
                    writer.write('\t'.code)
                    writer.write(e.lines.joinToString("|") { sanitizePipeField(it) })
                    writer.write('\t'.code)
                    writer.write(e.headsigns.joinToString("|") { sanitizePipeField(it) })
                    writer.newLine()
                }
            }
            if (stopIndexCacheFile.exists()) {
                stopIndexCacheFile.delete()
            }
            tmp.renameTo(stopIndexCacheFile)
        } catch (e: Exception) {
            Log.d(TAG, "Scrittura cache disco indice fermate fallita: ${e.message}")
        }
    }

    private fun sanitizeTsvField(value: String): String =
        value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun sanitizePipeField(value: String): String =
        sanitizeTsvField(value).replace('|', '/')

    private suspend fun ensureGtfsZipReady(): File? = downloadMutex.withLock {
        if (gtfsZipFile.exists() && gtfsZipFile.length() > MIN_VALID_ZIP_BYTES) {
            val ageMs = System.currentTimeMillis() - gtfsZipFile.lastModified()
            if (ageMs < GTFS_TTL_MS) {
                return@withLock gtfsZipFile
            }
        }

        val tmpFile = File(gtfsDir, "gtt_gtfs.zip.tmp")
        for (url in GTFS_ZIP_URLS) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/zip, application/octet-stream, */*")
                if (url.contains("busone.app")) {
                    reqBuilder
                        .header("X-BusOne-Client", "ToBusTorino; android")
                        .header("X-BusOne-Use", "nonprofit")
                }

                downloadClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.d(TAG, "GTFS zip HTTP ${resp.code} da $url")
                        continue
                    }
                    val body = resp.body ?: continue
                    if (tmpFile.exists()) tmpFile.delete()
                    body.byteStream().use { input ->
                        tmpFile.outputStream().buffered(32 * 1024).use { output ->
                            input.copyTo(output, bufferSize = 32 * 1024)
                        }
                    }

                    // Validate zip integrity before promoting
                    if (tmpFile.length() > MIN_VALID_ZIP_BYTES && isValidGtfsZip(tmpFile)) {
                        if (gtfsZipFile.exists()) gtfsZipFile.delete()
                        if (tmpFile.renameTo(gtfsZipFile)) {
                            Log.i(TAG, "GTFS statico GTT scaricato (${gtfsZipFile.length() / 1024} KB) da $url")
                            return@withLock gtfsZipFile
                        }
                    } else {
                        tmpFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Download GTFS zip fallito su $url: ${e.message}")
                runCatching { tmpFile.delete() }
            }
        }

        // Fall back to stale cached zip if network failed
        if (gtfsZipFile.exists() && gtfsZipFile.length() > MIN_VALID_ZIP_BYTES) {
            return@withLock gtfsZipFile
        }
        null
    }

    private fun isValidGtfsZip(file: File): Boolean {
        return try {
            ZipFile(file).use { zip ->
                zip.getEntry("routes.txt") != null &&
                    zip.getEntry("trips.txt") != null &&
                    (zip.getEntry("shapes.txt") != null || zip.getEntry("stops.txt") != null)
            }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "GttStaticRouteStore"
        private const val USER_AGENT = "ToBusTorino/2.0 (Android Transit Widget)"
        private const val MIN_VALID_ZIP_BYTES = 10_000L
        private const val GTFS_TTL_MS = 24L * 60L * 60L * 1000L

        private val GTFS_ZIP_URLS = listOf(
            "https://proxy.busone.app/gtt/gtfs.zip",
            "https://www.gtt.to.it/open_data/gtt_gtfs.zip",
            "http://www.gtt.to.it/open_data/gtt_gtfs.zip",
        )

        internal fun extractPatternsFromZip(
            zipFile: File,
            targetLine: String,
            nowMillis: Long = System.currentTimeMillis(),
            activeTripIds: Set<String> = emptySet(),
        ): List<RoutePattern> {
        val normalizedTarget = normalizeLineKey(targetLine)
        if (normalizedTarget.isEmpty()) return emptyList()
        val cleanActiveTrips = activeTripIds
            .map { it.trim().substringAfterLast(":") }
            .filter { it.isNotEmpty() }
            .toHashSet()

        return ZipFile(zipFile).use { zip ->
            // Resolve today's active service_ids in Europe/Rome so Sunday/holiday/detour shapes win over weekday shapes!
            val romeTz = java.util.TimeZone.getTimeZone("Europe/Rome")
            val cal = Calendar.getInstance(romeTz).apply { timeInMillis = nowMillis }
            val todayYyyymmdd = cal.get(Calendar.YEAR) * 10_000 +
                (cal.get(Calendar.MONTH) + 1) * 100 +
                cal.get(Calendar.DAY_OF_MONTH)
            val todayDayCol = gtfsDayColumnName(cal.get(Calendar.DAY_OF_WEEK))
            val hasCalendarFiles = zip.getEntry("calendar.txt") != null || zip.getEntry("calendar_dates.txt") != null
            val todayServices = if (hasCalendarFiles) {
                resolveActiveServiceIds(zip, todayYyyymmdd, todayDayCol)
            } else {
                emptySet()
            }

            // 1. Parse routes.txt -> find all route_ids matching targetLine
            val routesEntry = zip.getEntry("routes.txt") ?: return emptyList()
            val matchingRouteIds = HashSet<String>()
            val routeNames = HashMap<String, String>()

            zip.getInputStream(routesEntry).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 16 * 1024).use { reader ->
                    val headerLine = reader.readLine() ?: return emptyList()
                    val cols = parseCsvHeader(headerLine)
                    val idxRouteId = cols["route_id"] ?: return emptyList()
                    val idxShortName = cols["route_short_name"] ?: -1
                    val idxLongName = cols["route_long_name"] ?: -1

                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            val fields = parseCsvLine(line)
                            val routeId = fields.getOrNull(idxRouteId)?.trim().orEmpty()
                            val shortName = if (idxShortName >= 0) fields.getOrNull(idxShortName)?.trim().orEmpty() else ""
                            val longName = if (idxLongName >= 0) fields.getOrNull(idxLongName)?.trim().orEmpty() else ""

                            if (routeId.isNotEmpty() && matchesTargetLine(routeId, shortName, normalizedTarget)) {
                                matchingRouteIds.add(routeId)
                                routeNames[routeId] = longName.ifBlank { "Linea $normalizedTarget" }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }

            if (matchingRouteIds.isEmpty()) return emptyList()

            // 2. Parse trips.txt -> rank (direction_id, shape_id) by:
            //    Tier 1: live buses currently running on that trip_id (catches live detours immediately!)
            //    Tier 2: trips scheduled for TODAY's active service_id (catches Sunday/festivo/detour routes!)
            //    Tier 3: overall trip count fallback
            val tripsEntry = zip.getEntry("trips.txt") ?: return emptyList()
            data class ShapeCandidate(
                val shapeId: String,
                val directionId: Int,
                var liveTripMatches: Int,
                var todayTripCount: Int,
                var tripCount: Int,
                var headsign: String,
                var sampleTripId: String,
                var samplePriority: Int, // 2 = live, 1 = today, 0 = other
            )

            val shapeCandidates = HashMap<String, ShapeCandidate>()
            val fallbackTripByDir = HashMap<Int, String>()
            var hasExplicitDirectionId = false

            zip.getInputStream(tripsEntry).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 32 * 1024).use { reader ->
                    val headerLine = reader.readLine() ?: return emptyList()
                    val cols = parseCsvHeader(headerLine)
                    val idxRouteId = cols["route_id"] ?: return emptyList()
                    val idxTripId = cols["trip_id"] ?: -1
                    val idxServiceId = cols["service_id"] ?: -1
                    val idxDirId = cols["direction_id"] ?: -1
                    val idxShapeId = cols["shape_id"] ?: -1
                    val idxHeadsign = cols["trip_headsign"] ?: -1

                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            val fields = parseCsvLine(line)
                            val routeId = fields.getOrNull(idxRouteId)?.trim().orEmpty()
                            if (routeId in matchingRouteIds) {
                                val parsedDirId = if (idxDirId >= 0) {
                                    fields.getOrNull(idxDirId)?.trim()?.toIntOrNull()
                                } else {
                                    null
                                }
                                if (parsedDirId != null) hasExplicitDirectionId = true
                                val dirId = parsedDirId ?: 0
                                val shapeId = if (idxShapeId >= 0) fields.getOrNull(idxShapeId)?.trim().orEmpty() else ""
                                val headsign = if (idxHeadsign >= 0) fields.getOrNull(idxHeadsign)?.trim().orEmpty() else ""
                                val tripId = if (idxTripId >= 0) fields.getOrNull(idxTripId)?.trim().orEmpty() else ""
                                val serviceId = if (idxServiceId >= 0) fields.getOrNull(idxServiceId)?.trim().orEmpty() else ""

                                val isLiveTrip = tripId.isNotEmpty() && tripId in cleanActiveTrips
                                val isTodayTrip = todayServices.isNotEmpty() && serviceId in todayServices
                                val priority = when {
                                    isLiveTrip -> 2
                                    isTodayTrip -> 1
                                    else -> 0
                                }

                                if (shapeId.isNotEmpty()) {
                                    val key = "$dirId|$shapeId"
                                    val existing = shapeCandidates[key]
                                    if (existing != null) {
                                        existing.tripCount++
                                        if (isLiveTrip) existing.liveTripMatches++
                                        if (isTodayTrip) existing.todayTripCount++
                                        if (priority > existing.samplePriority && tripId.isNotBlank()) {
                                            existing.sampleTripId = tripId
                                            existing.samplePriority = priority
                                            if (headsign.isNotBlank()) existing.headsign = headsign
                                        } else {
                                            if (existing.headsign.isBlank() && headsign.isNotBlank()) {
                                                existing.headsign = headsign
                                            }
                                            if (existing.sampleTripId.isBlank() && tripId.isNotBlank()) {
                                                existing.sampleTripId = tripId
                                            }
                                        }
                                    } else {
                                        shapeCandidates[key] = ShapeCandidate(
                                            shapeId = shapeId,
                                            directionId = dirId,
                                            liveTripMatches = if (isLiveTrip) 1 else 0,
                                            todayTripCount = if (isTodayTrip) 1 else 0,
                                            tripCount = 1,
                                            headsign = headsign.ifBlank { routeNames[routeId] ?: "Linea $normalizedTarget" },
                                            sampleTripId = tripId,
                                            samplePriority = priority,
                                        )
                                    }
                                } else if (tripId.isNotEmpty() && (!fallbackTripByDir.containsKey(dirId) || isTodayTrip || isLiveTrip)) {
                                    fallbackTripByDir[dirId] = tripId
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }

            val shapeRankComparator = compareBy<ShapeCandidate> { it.liveTripMatches }
                .thenBy { it.todayTripCount }
                .thenBy { it.tripCount }

            // Pick the winning active shape for direction 0 and direction 1 (or top 2 distinct shapes if direction_id was omitted)
            val chosenShapes = mutableListOf<ShapeCandidate>()
            val byDir = shapeCandidates.values.groupBy { it.directionId }
            byDir[0]?.maxWithOrNull(shapeRankComparator)?.let { chosenShapes.add(it) }
            byDir[1]?.maxWithOrNull(shapeRankComparator)?.let { chosenShapes.add(it) }
            if (chosenShapes.isEmpty()) {
                chosenShapes.addAll(
                    shapeCandidates.values
                        .sortedWith(shapeRankComparator.reversed())
                        .distinctBy { it.shapeId }
                        .take(2),
                )
            } else if (chosenShapes.size == 1 && !hasExplicitDirectionId) {
                val alreadyId = chosenShapes[0].shapeId
                shapeCandidates.values
                    .filter { it.shapeId != alreadyId }
                    .maxWithOrNull(shapeRankComparator)
                    ?.let { chosenShapes.add(it.copy(directionId = if (chosenShapes[0].directionId == 0) 1 else 0)) }
            }

            // Extract ordered TransitStops for each representative trip
            val sampleTripsByDir = HashMap<Int, String>()
            chosenShapes.forEach { cand ->
                if (cand.sampleTripId.isNotBlank()) {
                    sampleTripsByDir[cand.directionId.coerceIn(0, 1)] = cand.sampleTripId
                }
            }
            val stopsByTripId = if (sampleTripsByDir.isNotEmpty()) {
                extractStopsForTrips(zip, sampleTripsByDir)
            } else {
                emptyMap()
            }

            // 3. Parse shapes.txt -> extract ordered GeoPoints for chosen shape_ids
            val shapesEntry = zip.getEntry("shapes.txt")
            if (chosenShapes.isNotEmpty() && shapesEntry != null) {
                val wantedIds = chosenShapes.map { it.shapeId }.toHashSet()
                val rawShapePoints = HashMap<String, MutableList<Pair<Int, GeoPoint>>>(wantedIds.size)
                wantedIds.forEach { rawShapePoints[it] = ArrayList(256) }

                zip.getInputStream(shapesEntry).use { stream ->
                    BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 64 * 1024).use { reader ->
                        val headerLine = reader.readLine() ?: return emptyList()
                        val cols = parseCsvHeader(headerLine)
                        val idxShapeId = cols["shape_id"] ?: return emptyList()
                        val idxLat = cols["shape_pt_lat"] ?: return emptyList()
                        val idxLon = cols["shape_pt_lon"] ?: return emptyList()
                        val idxSeq = cols["shape_pt_sequence"] ?: -1

                        var line = reader.readLine()
                        var seqFallback = 0
                        while (line != null) {
                            if (line.isNotBlank()) {
                                val quickId = if (idxShapeId == 0) {
                                    val comma = line.indexOf(',')
                                    if (comma > 0) line.substring(0, comma).trim().removeSurrounding("\"") else null
                                } else {
                                    null
                                }

                                if (quickId == null || quickId in wantedIds) {
                                    val fields = parseCsvLine(line)
                                    val shapeId = fields.getOrNull(idxShapeId)?.trim().orEmpty()
                                    val list = rawShapePoints[shapeId]
                                    if (list != null) {
                                        val lat = fields.getOrNull(idxLat)?.trim()?.toDoubleOrNull()
                                        val lon = fields.getOrNull(idxLon)?.trim()?.toDoubleOrNull()
                                        val seq = if (idxSeq >= 0) {
                                            fields.getOrNull(idxSeq)?.trim()?.toIntOrNull() ?: seqFallback++
                                        } else {
                                            seqFallback++
                                        }
                                        if (lat != null && lon != null && lat in 44.0..46.0 && lon in 6.5..9.0) {
                                            list.add(seq to GeoPoint(lat, lon))
                                        }
                                    }
                                }
                            }
                            line = reader.readLine()
                        }
                    }
                }

                val patterns = chosenShapes.mapNotNull { cand ->
                    val pts = rawShapePoints[cand.shapeId]
                        ?.sortedBy { it.first }
                        ?.map { it.second }
                        .orEmpty()
                    if (pts.size < 2) {
                        null
                    } else {
                        val simplified = simplifyPoints(pts, maxPts = 320)
                        val dir = cand.directionId.coerceIn(0, 1)
                        val patternStops = stopsByTripId[cand.sampleTripId].orEmpty()
                        val lastStopName = if (patternStops.size >= 2) {
                            patternStops.lastOrNull()?.name?.trim()
                                ?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() || ch == '#' } }
                        } else {
                            null
                        }
                        val rawHeadsign = cand.headsign.trim()
                        val isGenericName = rawHeadsign.isBlank() ||
                            rawHeadsign.startsWith("Linea ", ignoreCase = true) ||
                            rawHeadsign.contains(" - ") ||
                            com.example.data.model.isGenericDirectionLabel(rawHeadsign)
                        val resolvedName = if (!isGenericName) {
                            rawHeadsign
                        } else {
                            lastStopName
                                ?: patternStops.lastOrNull()?.name?.takeIf { it.isNotBlank() }
                                ?: rawHeadsign.ifBlank { "Linea $normalizedTarget" }
                        }
                        RoutePattern(
                            id = "gtfs_${cand.shapeId}",
                            name = resolvedName,
                            directionId = dir,
                            segments = listOf(simplified),
                            stops = patternStops,
                        )
                    }
                }

                if (patterns.isNotEmpty()) {
                    return patterns
                }
            }

            // 4. Fallback: derive path and stops from ordered stop_times.txt + stops.txt
            if (fallbackTripByDir.isNotEmpty()) {
                val fallbackStopsByTrip = extractStopsForTrips(zip, fallbackTripByDir)
                return fallbackTripByDir.entries.mapNotNull { (dirId, tripId) ->
                    val stops = fallbackStopsByTrip[tripId].orEmpty()
                    if (stops.size < 2) {
                        null
                    } else {
                        RoutePattern(
                            id = "gtfs_stops_$tripId",
                            name = stops.lastOrNull()?.name?.takeIf { it.isNotBlank() }
                                ?: "Linea $normalizedTarget",
                            directionId = dirId.coerceIn(0, 1),
                            segments = listOf(stops.map { GeoPoint(it.lat, it.lon) }),
                            stops = stops,
                        )
                    }
                }
            }

            emptyList()
        }
    }

    private fun extractStopsForTrips(
        zip: ZipFile,
        tripByDir: Map<Int, String>,
    ): Map<String, List<TransitStop>> {
        val stopTimesEntry = zip.getEntry("stop_times.txt") ?: return emptyMap()
        val stopsEntry = zip.getEntry("stops.txt") ?: return emptyMap()
        val wantedTrips = tripByDir.values.toHashSet()
        val dirByTrip = tripByDir.entries.associate { (d, t) -> t to d.coerceIn(0, 1) }
        val tripStops = HashMap<String, MutableList<Pair<Int, String>>>()

        zip.getInputStream(stopTimesEntry).use { stream ->
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 64 * 1024).use { reader ->
                val header = reader.readLine() ?: return emptyMap()
                val cols = parseCsvHeader(header)
                val idxTrip = cols["trip_id"] ?: return emptyMap()
                val idxStop = cols["stop_id"] ?: return emptyMap()
                val idxSeq = cols["stop_sequence"] ?: -1

                var line = reader.readLine()
                var seqFallback = 0
                while (line != null) {
                    if (line.isNotBlank()) {
                        val quickTrip = if (idxTrip == 0) {
                            val comma = line.indexOf(',')
                            if (comma > 0) line.substring(0, comma).trim().removeSurrounding("\"") else null
                        } else {
                            null
                        }
                        if (quickTrip == null || quickTrip in wantedTrips) {
                            val fields = parseCsvLine(line)
                            val tripId = fields.getOrNull(idxTrip)?.trim().orEmpty()
                            if (tripId in wantedTrips) {
                                val stopId = fields.getOrNull(idxStop)?.trim().orEmpty()
                                val seq = if (idxSeq >= 0) {
                                    fields.getOrNull(idxSeq)?.trim()?.toIntOrNull() ?: seqFallback++
                                } else {
                                    seqFallback++
                                }
                                if (stopId.isNotEmpty()) {
                                    tripStops.getOrPut(tripId) { ArrayList() }.add(seq to stopId)
                                }
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        }

        val neededStopIds = tripStops.values.flatten().map { it.second }.toHashSet()
        if (neededStopIds.isEmpty()) return emptyMap()

        data class RawStopMeta(
            val stopId: String,
            val stopCode: String,
            val name: String,
            val lat: Double,
            val lon: Double,
        )

        val stopMeta = HashMap<String, RawStopMeta>(neededStopIds.size)
        zip.getInputStream(stopsEntry).use { stream ->
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 32 * 1024).use { reader ->
                val header = reader.readLine() ?: return emptyMap()
                val cols = parseCsvHeader(header)
                val idxStop = cols["stop_id"] ?: return emptyMap()
                val idxCode = cols["stop_code"] ?: -1
                val idxName = cols["stop_name"] ?: -1
                val idxLat = cols["stop_lat"] ?: return emptyMap()
                val idxLon = cols["stop_lon"] ?: return emptyMap()

                var line = reader.readLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        val fields = parseCsvLine(line)
                        val stopId = fields.getOrNull(idxStop)?.trim().orEmpty()
                        if (stopId in neededStopIds) {
                            val lat = fields.getOrNull(idxLat)?.trim()?.toDoubleOrNull()
                            val lon = fields.getOrNull(idxLon)?.trim()?.toDoubleOrNull()
                            if (lat != null && lon != null && lat in 44.0..46.0 && lon in 6.5..9.0) {
                                val code = if (idxCode >= 0) fields.getOrNull(idxCode)?.trim().orEmpty() else ""
                                val name = if (idxName >= 0) fields.getOrNull(idxName)?.trim().orEmpty() else ""
                                stopMeta[stopId] = RawStopMeta(
                                    stopId = stopId,
                                    stopCode = code.ifBlank { stopId },
                                    name = name.ifBlank { "Fermata $stopId" },
                                    lat = lat,
                                    lon = lon,
                                )
                            }
                        }
                    }
                    line = reader.readLine()
                }
            }
        }

        return tripStops.mapValues { (tripId, seqList) ->
            val dir = dirByTrip[tripId] ?: 0
            seqList.sortedBy { it.first }.mapNotNull { (seq, sid) ->
                val meta = stopMeta[sid] ?: return@mapNotNull null
                TransitStop(
                    stopId = meta.stopId,
                    stopCode = meta.stopCode,
                    name = meta.name,
                    lat = meta.lat,
                    lon = meta.lon,
                    directionId = dir,
                    sequence = seq,
                )
            }
        }
    }

    internal fun extractStopNamesFromZip(
        zipFile: File,
        wantedIds: Set<String>,
    ): Map<String, String> {
        if (wantedIds.isEmpty()) return emptyMap()
        return ZipFile(zipFile).use { zip ->
            val stopsEntry = zip.getEntry("stops.txt") ?: return emptyMap()
            val out = HashMap<String, String>(wantedIds.size)
            zip.getInputStream(stopsEntry).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 32 * 1024).use { reader ->
                    val header = reader.readLine() ?: return emptyMap()
                    val cols = parseCsvHeader(header)
                    val idxStop = cols["stop_id"] ?: return emptyMap()
                    val idxCode = cols["stop_code"] ?: -1
                    val idxName = cols["stop_name"] ?: return emptyMap()

                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            val fields = parseCsvLine(line)
                            val stopId = fields.getOrNull(idxStop)?.trim().orEmpty()
                            val stopCode = if (idxCode >= 0) fields.getOrNull(idxCode)?.trim().orEmpty() else ""
                            val matchesId = stopId.isNotEmpty() && stopId in wantedIds
                            val matchesCode = stopCode.isNotEmpty() && stopCode in wantedIds
                            if (matchesId || matchesCode) {
                                val name = fields.getOrNull(idxName)?.trim().orEmpty()
                                if (name.isNotEmpty()) {
                                    if (matchesId) out[stopId] = name
                                    if (matchesCode) out[stopCode] = name
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
            out
        }
    }

    internal fun extractScheduledArrivalsFromZip(
        zipFile: File,
        stopId: String,
        preferredLine: String,
        nowMillis: Long,
    ): List<StopArrival> {
        val targetStop = stopId.trim()
        if (targetStop.isEmpty()) return emptyList()

        val romeTz = java.util.TimeZone.getTimeZone("Europe/Rome")
        val cal = Calendar.getInstance(romeTz).apply { timeInMillis = nowMillis }
        val nowSecondsOfDay = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
            cal.get(Calendar.MINUTE) * 60 +
            cal.get(Calendar.SECOND)
        val todayYyyymmdd = cal.get(Calendar.YEAR) * 10_000 +
            (cal.get(Calendar.MONTH) + 1) * 100 +
            cal.get(Calendar.DAY_OF_MONTH)
        val todayDayCol = gtfsDayColumnName(cal.get(Calendar.DAY_OF_WEEK))

        val midnightMillis = Calendar.getInstance(romeTz).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        // Support post-midnight trips (24:xx:xx..27:59:59) from yesterday's service between 00:00 and 04:00
        val includeYesterdayOvernight = nowSecondsOfDay < 4 * 3600
        val yestCal = Calendar.getInstance(romeTz).apply {
            timeInMillis = midnightMillis - 12 * 3600 * 1000L
        }
        val yesterdayYyyymmdd = yestCal.get(Calendar.YEAR) * 10_000 +
            (yestCal.get(Calendar.MONTH) + 1) * 100 +
            yestCal.get(Calendar.DAY_OF_MONTH)
        val yesterdayDayCol = gtfsDayColumnName(yestCal.get(Calendar.DAY_OF_WEEK))

        val minSec = nowSecondsOfDay - 60
        val maxSec = nowSecondsOfDay + 50 * 60
        val yestMinSec = nowSecondsOfDay + 86_400 - 60
        val yestMaxSec = nowSecondsOfDay + 86_400 + 50 * 60

        return ZipFile(zipFile).use { zip ->
            val stopTimesEntry = zip.getEntry("stop_times.txt") ?: return emptyList()
            val tripsEntry = zip.getEntry("trips.txt") ?: return emptyList()
            val routesEntry = zip.getEntry("routes.txt") ?: return emptyList()

            val hasCalendarFiles = zip.getEntry("calendar.txt") != null || zip.getEntry("calendar_dates.txt") != null
            val todayServices = if (hasCalendarFiles) {
                resolveActiveServiceIds(zip, todayYyyymmdd, todayDayCol)
            } else {
                emptySet()
            }
            val yesterdayServices = if (hasCalendarFiles && includeYesterdayOvernight) {
                resolveActiveServiceIds(zip, yesterdayYyyymmdd, yesterdayDayCol)
            } else {
                emptySet()
            }

            // If the GTFS archive defines calendars but ZERO services are active today (e.g. expired archive),
            // return empty rather than leaking weekday/wrong-day phantom buses!
            if (hasCalendarFiles && todayServices.isEmpty() && yesterdayServices.isEmpty()) {
                return emptyList()
            }

            // 1. Scan stop_times.txt for rows matching targetStop within [-1m, +75m] (skip drop-off only!)
            data class RawStopTime(val secOfDay: Int, val isFromYesterday: Boolean)
            val upcomingByTrip = HashMap<String, RawStopTime>()
            zip.getInputStream(stopTimesEntry).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 64 * 1024).use { reader ->
                    val header = reader.readLine() ?: return emptyList()
                    val cols = parseCsvHeader(header)
                    val idxTrip = cols["trip_id"] ?: return emptyList()
                    val idxStop = cols["stop_id"] ?: return emptyList()
                    val idxArr = cols["arrival_time"] ?: cols["departure_time"] ?: return emptyList()
                    val idxPickup = cols["pickup_type"] ?: -1

                    var line = reader.readLine()
                    while (line != null) {
                        if (line.contains(targetStop)) {
                            val fields = parseCsvLine(line)
                            val sid = fields.getOrNull(idxStop)?.trim().orEmpty()
                            if (sid == targetStop) {
                                // pickup_type == 1 means no passenger boarding allowed (terminus drop-off only)
                                val pickupType = if (idxPickup >= 0) fields.getOrNull(idxPickup)?.trim() else null
                                if (pickupType != "1") {
                                    val arrStr = fields.getOrNull(idxArr)?.trim().orEmpty()
                                    val sec = parseGtfsTimeSeconds(arrStr)
                                    val tid = fields.getOrNull(idxTrip)?.trim().orEmpty()
                                    if (sec != null && tid.isNotEmpty()) {
                                        when {
                                            sec in minSec..maxSec -> upcomingByTrip[tid] = RawStopTime(sec, false)
                                            includeYesterdayOvernight && sec in yestMinSec..yestMaxSec ->
                                                upcomingByTrip[tid] = RawStopTime(sec, true)
                                        }
                                    }
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }

            if (upcomingByTrip.isEmpty()) return emptyList()

            // 2. Resolve route_id, service_id & headsign for those trips and strictly filter by active calendar!
            data class TripMeta(val routeId: String, val headsign: String, val serviceId: String)
            val tripMeta = HashMap<String, TripMeta>(upcomingByTrip.size)
            zip.getInputStream(tripsEntry).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 32 * 1024).use { reader ->
                    val header = reader.readLine() ?: return emptyList()
                    val cols = parseCsvHeader(header)
                    val idxTrip = cols["trip_id"] ?: return emptyList()
                    val idxRoute = cols["route_id"] ?: return emptyList()
                    val idxService = cols["service_id"] ?: -1
                    val idxHeadsign = cols["trip_headsign"] ?: -1

                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            val fields = parseCsvLine(line)
                            val tid = fields.getOrNull(idxTrip)?.trim().orEmpty()
                            val rawTime = upcomingByTrip[tid]
                            if (rawTime != null) {
                                val rid = fields.getOrNull(idxRoute)?.trim().orEmpty()
                                val serviceId = if (idxService >= 0) fields.getOrNull(idxService)?.trim().orEmpty() else ""
                                val serviceActive = when {
                                    !hasCalendarFiles -> true
                                    serviceId.isEmpty() -> false
                                    rawTime.isFromYesterday -> serviceId in yesterdayServices
                                    else -> serviceId in todayServices
                                }
                                if (rid.isNotEmpty() && serviceActive) {
                                    val hs = if (idxHeadsign >= 0) fields.getOrNull(idxHeadsign)?.trim().orEmpty() else ""
                                    tripMeta[tid] = TripMeta(rid, hs, serviceId)
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }

            if (tripMeta.isEmpty()) return emptyList()

            // 3. Resolve route_short_name for those route_ids
            val neededRoutes = tripMeta.values.map { it.routeId }.toHashSet()
            val routeShortNames = HashMap<String, String>(neededRoutes.size)
            zip.getInputStream(routesEntry).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 16 * 1024).use { reader ->
                    val header = reader.readLine() ?: return emptyList()
                    val cols = parseCsvHeader(header)
                    val idxRoute = cols["route_id"] ?: return emptyList()
                    val idxShort = cols["route_short_name"] ?: -1

                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            val fields = parseCsvLine(line)
                            val rid = fields.getOrNull(idxRoute)?.trim().orEmpty()
                            if (rid in neededRoutes) {
                                val sn = if (idxShort >= 0) fields.getOrNull(idxShort)?.trim().orEmpty() else ""
                                routeShortNames[rid] = GtfsRtProtobufParser.normalizeGttLineCode(sn.ifBlank { rid })
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }

            val normPref = normalizeLineKey(preferredLine)
            val baseMidnightSec = midnightMillis / 1000L
            upcomingByTrip.entries
                .mapNotNull { (tid, rawTime) ->
                    val meta = tripMeta[tid] ?: return@mapNotNull null
                    val lineCode = routeShortNames[meta.routeId]
                        ?: GtfsRtProtobufParser.normalizeGttLineCode(meta.routeId)
                    val epochSec = if (rawTime.isFromYesterday) {
                        (baseMidnightSec - 86_400L) + rawTime.secOfDay.toLong()
                    } else {
                        baseMidnightSec + rawTime.secOfDay.toLong()
                    }
                    StopArrival(
                        lineCode = lineCode,
                        headsign = meta.headsign.ifBlank { "Linea $lineCode" },
                        arrivalEpochSeconds = epochSec,
                        isRealtime = false,
                        tripId = tid,
                        sourceLabel = "Orario GTT",
                    )
                }
                .distinctBy { "${it.lineCode}_${it.arrivalEpochSeconds / 60L}" }
                .sortedWith(
                    compareByDescending<StopArrival> { normalizeLineKey(it.lineCode) == normPref }
                        .thenBy { it.arrivalEpochSeconds },
                )
                .take(12)
        }
    }

    /**
     * Resolves the exact set of GTFS `service_id` values active on [yyyymmdd] according to
     * `calendar.txt` (date range + day-of-week flag) and `calendar_dates.txt` (`exception_type` 1 = add, 2 = remove).
     */
    internal fun resolveActiveServiceIds(
        zip: ZipFile,
        yyyymmdd: Int,
        dayColumnName: String,
    ): Set<String> {
        val active = HashSet<String>()

        zip.getEntry("calendar.txt")?.let { calEntry ->
            zip.getInputStream(calEntry).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 16 * 1024).use { reader ->
                    val header = reader.readLine() ?: return@use
                    val cols = parseCsvHeader(header)
                    val idxService = cols["service_id"] ?: return@use
                    val idxDay = cols[dayColumnName] ?: return@use
                    val idxStart = cols["start_date"] ?: return@use
                    val idxEnd = cols["end_date"] ?: return@use

                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            val fields = parseCsvLine(line)
                            val sid = fields.getOrNull(idxService)?.trim().orEmpty()
                            val runsToday = fields.getOrNull(idxDay)?.trim() == "1"
                            val start = fields.getOrNull(idxStart)?.trim()?.toIntOrNull()
                            val end = fields.getOrNull(idxEnd)?.trim()?.toIntOrNull()
                            if (sid.isNotEmpty() && runsToday && start != null && end != null && yyyymmdd in start..end) {
                                active.add(sid)
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
        }

        zip.getEntry("calendar_dates.txt")?.let { excEntry ->
            zip.getInputStream(excEntry).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 32 * 1024).use { reader ->
                    val header = reader.readLine() ?: return@use
                    val cols = parseCsvHeader(header)
                    val idxService = cols["service_id"] ?: return@use
                    val idxDate = cols["date"] ?: return@use
                    val idxType = cols["exception_type"] ?: return@use

                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            val fields = parseCsvLine(line)
                            val dateInt = fields.getOrNull(idxDate)?.trim()?.toIntOrNull()
                            if (dateInt == yyyymmdd) {
                                val sid = fields.getOrNull(idxService)?.trim().orEmpty()
                                val excType = fields.getOrNull(idxType)?.trim()
                                if (sid.isNotEmpty()) {
                                    when (excType) {
                                        "1" -> active.add(sid)
                                        "2" -> active.remove(sid)
                                    }
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
        }

        return active
    }

    private fun gtfsDayColumnName(calendarDayOfWeek: Int): String = when (calendarDayOfWeek) {
        Calendar.MONDAY -> "monday"
        Calendar.TUESDAY -> "tuesday"
        Calendar.WEDNESDAY -> "wednesday"
        Calendar.THURSDAY -> "thursday"
        Calendar.FRIDAY -> "friday"
        Calendar.SATURDAY -> "saturday"
        else -> "sunday"
    }

    private fun parseGtfsTimeSeconds(raw: String): Int? {
        val parts = raw.split(':')
        if (parts.size < 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val s = parts.getOrNull(2)?.toIntOrNull() ?: 0
        return h * 3600 + m * 60 + s
    }

    private fun matchesTargetLine(routeId: String, shortName: String, normalizedTarget: String): Boolean {
        val normShort = normalizeLineKey(shortName)
        if (normShort == normalizedTarget) return true
        val normShortGtt = normalizeLineKey(GtfsRtProtobufParser.normalizeGttLineCode(shortName))
        if (normShortGtt == normalizedTarget) return true
        val normRouteId = normalizeLineKey(GtfsRtProtobufParser.normalizeGttLineCode(routeId))
        return normRouteId == normalizedTarget
    }

    private fun normalizeLineKey(raw: String): String {
        return raw.trim().uppercase().replace(" ", "")
    }

    private fun parseCsvHeader(headerLine: String): Map<String, Int> {
        val cleaned = headerLine.removePrefix("\uFEFF")
        val cols = parseCsvLine(cleaned)
        val map = HashMap<String, Int>(cols.size)
        cols.forEachIndexed { index, name ->
            map[name.trim().lowercase()] = index
        }
        return map
    }

    private fun parseCsvLine(line: String): List<String> {
        if (!line.contains('"')) {
            return line.split(',')
        }
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun simplifyPoints(points: List<GeoPoint>, maxPts: Int): List<GeoPoint> {
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

    /**
     * Pure-Kotlin builder extracting every Turin physical stop (`palina`) along with the exact lines
     * and headsigns serving that specific pole from [zipFile].
     * Uses 1 representative trip per `(routeId, directionId, shapeId)` so scanning `stop_times.txt`
     * covers all variants/detours in ~60-100 ms with low memory footprint.
     */
    internal fun extractStopSearchIndexFromZip(
        zipFile: File,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<StopSearchEntry> {
        ZipFile(zipFile).use { zip ->
            val routesEntry = zip.getEntry("routes.txt") ?: return emptyList()
            val tripsEntry = zip.getEntry("trips.txt") ?: return emptyList()
            val stopTimesEntry = zip.getEntry("stop_times.txt") ?: return emptyList()
            val stopsEntry = zip.getEntry("stops.txt") ?: return emptyList()

            val romeTz = java.util.TimeZone.getTimeZone("Europe/Rome")
            val cal = Calendar.getInstance(romeTz).apply { timeInMillis = nowMillis }
            val yyyymmdd = cal.get(Calendar.YEAR) * 10000 +
                (cal.get(Calendar.MONTH) + 1) * 100 +
                cal.get(Calendar.DAY_OF_MONTH)
            val activeServiceIds = resolveActiveServiceIds(zip, yyyymmdd, gtfsDayColumnName(cal.get(Calendar.DAY_OF_WEEK)))

            // 1. routeId -> clean short line code (e.g. "12U" -> "12")
            val routeCodeById = HashMap<String, String>(256)
            zip.getInputStream(routesEntry).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 16 * 1024).use { reader ->
                    val cols = parseCsvHeader(reader.readLine() ?: return@use)
                    val idxId = cols["route_id"] ?: return@use
                    val idxShort = cols["route_short_name"]
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            val f = parseCsvLine(line)
                            val rid = f.getOrNull(idxId)?.trim().orEmpty()
                            val rawShort = idxShort?.let { f.getOrNull(it)?.trim() }.orEmpty()
                            if (rid.isNotEmpty()) {
                                val normalized = GtfsRtProtobufParser.normalizeGttLineCode(
                                    rawShort.ifBlank { rid },
                                ).ifBlank { rawShort.ifBlank { rid }.uppercase() }
                                routeCodeById[rid] = normalized
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }

            // 2. Pick 1 representative trip per (routeId, directionId, shapeId/headsign)
            //    preferring trips active today, with automatic fallback if a variant runs other days.
            data class RepTrip(val lineCode: String, val headsign: String, val isToday: Boolean)
            val chosenByVariantKey = HashMap<String, Pair<String, RepTrip>>(2048)
            zip.getInputStream(tripsEntry).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 32 * 1024).use { reader ->
                    val cols = parseCsvHeader(reader.readLine() ?: return@use)
                    val idxRoute = cols["route_id"] ?: return@use
                    val idxTrip = cols["trip_id"] ?: return@use
                    val idxService = cols["service_id"]
                    val idxDir = cols["direction_id"]
                    val idxShape = cols["shape_id"]
                    val idxHeadsign = cols["trip_headsign"]
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            val f = parseCsvLine(line)
                            val rid = f.getOrNull(idxRoute)?.trim().orEmpty()
                            val tid = f.getOrNull(idxTrip)?.trim().orEmpty()
                            val lineCode = routeCodeById[rid]
                            if (tid.isNotEmpty() && lineCode != null) {
                                val sid = idxService?.let { f.getOrNull(it)?.trim() }.orEmpty()
                                val dir = idxDir?.let { f.getOrNull(it)?.trim() }.orEmpty()
                                val shape = idxShape?.let { f.getOrNull(it)?.trim() }.orEmpty()
                                val headsign = idxHeadsign?.let { f.getOrNull(it)?.trim() }.orEmpty()
                                val isToday = activeServiceIds.isEmpty() || sid in activeServiceIds
                                val variantKey = "$rid|$dir|${shape.ifBlank { headsign }}"
                                val prev = chosenByVariantKey[variantKey]
                                if (prev == null || (!prev.second.isToday && isToday)) {
                                    chosenByVariantKey[variantKey] = tid to RepTrip(lineCode, headsign, isToday)
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }

            val repByTripId = HashMap<String, RepTrip>(chosenByVariantKey.size * 2)
            for ((tid, meta) in chosenByVariantKey.values) {
                repByTripId[tid] = meta
            }

            // 3. Stream stop_times.txt once to map stopId -> lines, representative trips, and each trip's final stop
            val linesByStop = HashMap<String, LinkedHashSet<String>>(4096)
            val repTripsByStop = HashMap<String, LinkedHashSet<String>>(4096)
            val lastStopByTrip = HashMap<String, Pair<Int, String>>(repByTripId.size * 2)
            zip.getInputStream(stopTimesEntry).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 128 * 1024).use { reader ->
                    val cols = parseCsvHeader(reader.readLine() ?: return@use)
                    val idxTrip = cols["trip_id"] ?: return@use
                    val idxStop = cols["stop_id"] ?: return@use
                    val idxSeq = cols["stop_sequence"]
                    val idxPickup = cols["pickup_type"]
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotEmpty()) {
                            // Fast-path: reject >99% of stop_times rows without allocating a CSV List
                            val tid: String
                            val meta: RepTrip?
                            if (idxTrip == 0) {
                                val c0 = line.indexOf(',')
                                if (c0 <= 0) {
                                    line = reader.readLine()
                                    continue
                                }
                                tid = if (line[0] == '"' && c0 >= 2 && line[c0 - 1] == '"') {
                                    line.substring(1, c0 - 1).trim()
                                } else {
                                    line.substring(0, c0).trim()
                                }
                                meta = repByTripId[tid]
                                if (meta == null) {
                                    line = reader.readLine()
                                    continue
                                }
                            } else {
                                val quickFields = parseCsvLine(line)
                                tid = quickFields.getOrNull(idxTrip)?.trim().orEmpty()
                                meta = repByTripId[tid]
                                if (meta == null) {
                                    line = reader.readLine()
                                    continue
                                }
                            }
                            val f = parseCsvLine(line)
                            val sid = f.getOrNull(idxStop)?.trim().orEmpty()
                            if (sid.isNotEmpty()) {
                                val seq = idxSeq?.let { f.getOrNull(it)?.trim()?.toIntOrNull() } ?: 0
                                val prevLast = lastStopByTrip[tid]
                                if (prevLast == null || seq >= prevLast.first) {
                                    lastStopByTrip[tid] = seq to sid
                                }
                                val pickup = idxPickup?.let { f.getOrNull(it)?.trim() }
                                if (pickup != "1") {
                                    linesByStop.getOrPut(sid) { LinkedHashSet(4) }.add(meta.lineCode)
                                    repTripsByStop.getOrPut(sid) { LinkedHashSet(4) }.add(tid)
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }

            // 4. Stream stops.txt to collect stop names + metadata, then resolve exact terminus names
            data class RawStopRow(val id: String, val code: String, val name: String, val lat: Double, val lon: Double)
            val rawStops = ArrayList<RawStopRow>(linesByStop.size.coerceAtLeast(256))
            val stopNameById = HashMap<String, String>(4096)
            zip.getInputStream(stopsEntry).use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8), 32 * 1024).use { reader ->
                    val cols = parseCsvHeader(reader.readLine() ?: return@use)
                    val idxId = cols["stop_id"] ?: return@use
                    val idxCode = cols["stop_code"]
                    val idxName = cols["stop_name"] ?: return@use
                    val idxLat = cols["stop_lat"] ?: return@use
                    val idxLon = cols["stop_lon"] ?: return@use
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            val f = parseCsvLine(line)
                            val sid = f.getOrNull(idxId)?.trim().orEmpty()
                            val name = f.getOrNull(idxName)?.trim().orEmpty()
                            if (sid.isNotEmpty() && name.isNotEmpty()) {
                                stopNameById[sid] = name
                                val scode = idxCode?.let { f.getOrNull(it)?.trim() }.orEmpty().ifBlank { sid }
                                val lat = f.getOrNull(idxLat)?.trim()?.toDoubleOrNull()
                                val lon = f.getOrNull(idxLon)?.trim()?.toDoubleOrNull()
                                if (lat != null && lon != null && lat in 44.80..45.35 && lon in 7.30..8.00 &&
                                    linesByStop.containsKey(sid)
                                ) {
                                    rawStops.add(RawStopRow(sid, scode, name, lat, lon))
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }

            val out = ArrayList<StopSearchEntry>(rawStops.size)
            val lineSortComparator = compareBy<String>(
                { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: Int.MAX_VALUE },
                { it },
            )
            for (row in rawStops) {
                val stopLines = linesByStop[row.id]?.sortedWith(lineSortComparator).orEmpty()
                if (stopLines.isEmpty()) continue
                val heads = LinkedHashSet<String>(3)
                repTripsByStop[row.id]?.forEach { tid ->
                    val meta = repByTripId[tid] ?: return@forEach
                    val finalStopId = lastStopByTrip[tid]?.second
                    val finalStopName = if (finalStopId != null && finalStopId != row.id) {
                        stopNameById[finalStopId]
                    } else {
                        null
                    }
                    val chosen = finalStopName?.takeIf { it.isNotBlank() }
                        ?: meta.headsign.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    val clean = com.example.data.model.shortTurinPlaceName(chosen)
                    if (clean.isNotBlank()) heads.add(clean)
                }
                out.add(
                    StopSearchEntry(
                        stopId = row.id,
                        stopCode = row.code,
                        name = row.name,
                        lat = row.lat,
                        lon = row.lon,
                        lines = stopLines,
                        headsigns = heads.take(2),
                    ),
                )
            }
            return out
        }
    }

    /**
     * Multi-token stop search supporting stop names, palina numbers, and line numbers together
     * (e.g. `"317"`, `"porta nuova"`, or `"porta nuova 12"`).
     * When [query] is blank and user GPS is available, returns the closest physical poles nearby.
     */
    internal fun searchStops(
        index: List<StopSearchEntry>,
        query: String,
        preferredLine: String,
        userLat: Double? = null,
        userLon: Double? = null,
        limit: Int = 24,
    ): List<StopSearchEntry> {
        if (index.isEmpty()) return emptyList()
        val normPref = normalizeLineKey(preferredLine)
        val trimmed = query.trim().lowercase(java.util.Locale.ITALY)

        fun distMeters(e: StopSearchEntry): Double {
            if (userLat == null || userLon == null) return 100_000.0
            return com.example.data.model.GttFleetAnalyzer.haversineMeters(userLat, userLon, e.lat, e.lon)
        }

        if (trimmed.isEmpty()) {
            return if (userLat != null && userLon != null) {
                index.asSequence()
                    .map { it to distMeters(it) }
                    .filter { it.second <= 2_500.0 }
                    .sortedBy { it.second }
                    .take(14)
                    .map { it.first }
                    .toList()
            } else {
                index.asSequence()
                    .filter { e -> e.lines.any { normalizeLineKey(it) == normPref } }
                    .sortedBy { it.name }
                    .take(14)
                    .toList()
            }
        }

        val tokens = trimmed
            .replace("#", " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        val scored = ArrayList<Pair<StopSearchEntry, Double>>(64)
        for (entry in index) {
            val codeLower = entry.stopCode.lowercase(java.util.Locale.ITALY)
            val idLower = entry.stopId.lowercase(java.util.Locale.ITALY)
            val nameLower = entry.name.lowercase(java.util.Locale.ITALY)
            val headLower = entry.headsigns.joinToString(" ").lowercase(java.util.Locale.ITALY)
            val linesLower = entry.lines.map { it.lowercase(java.util.Locale.ITALY) }

            var allMatch = true
            var score = 0.0
            for (tok in tokens) {
                when {
                    codeLower == tok || idLower == tok -> score += 120.0
                    linesLower.any { it == tok } -> score += 55.0
                    nameLower.startsWith(tok) -> score += 70.0
                    codeLower.startsWith(tok) -> score += 65.0
                    nameLower.contains(tok) -> score += 45.0
                    headLower.contains(tok) -> score += 28.0
                    else -> {
                        allMatch = false
                        break
                    }
                }
            }
            if (!allMatch) continue

            if (entry.lines.any { normalizeLineKey(it) == normPref }) {
                score += 18.0
            }
            if (userLat != null && userLon != null) {
                val d = distMeters(entry)
                // Gentle proximity bonus (up to +20 pts for stops right next to the user)
                score += (20.0 * (1.0 - (d / 4_000.0).coerceIn(0.0, 1.0)))
            }
            scored.add(entry to score)
        }

        return scored
            .sortedWith(
                compareByDescending<Pair<StopSearchEntry, Double>> { it.second }
                    .thenBy { distMeters(it.first) }
                    .thenBy { it.first.stopCode },
            )
            .take(limit)
            .map { it.first }
    }
    }
}
