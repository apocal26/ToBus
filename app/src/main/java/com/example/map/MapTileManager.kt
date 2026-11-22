package com.example.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.util.Log
import android.util.LruCache
import com.example.data.model.MapStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 100% keyless, high-speed map tile manager:
 * - Interactive Map: Pure lossless OpenStreetMap PNG tiles (+ OSM-FR fallback) with GPU ColorMatrix styling
 * - Home Widgets: Ultra-clean keyless Esri Dark/Light Gray Canvas (ZERO giant district labels!)
 *   with automatic fallback to muted OSM tiles
 * - Strict RAM-only main-thread lookup (`getMemoryCachedTile`) so UI scrolling never blocks on disk I/O
 * - In-flight request deduplication so rapid pan/zoom never fires duplicate HTTP calls
 */
class MapTileManager private constructor(context: Context) {

    private val tileCacheDir = File(context.applicationContext.cacheDir, "map_tiles_v6").apply { mkdirs() }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Generous 40 MB RAM cache keeps multiple zoom levels warm -> zero flashes when zooming
    private val memoryCache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(40 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val inFlightRequests = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()

    init {
        runCatching {
            listOf("map_tiles", "map_tiles_v3", "map_tiles_v4", "map_tiles_v5").forEach { legacy ->
                val legacyDir = File(context.applicationContext.cacheDir, legacy)
                if (legacyDir.exists()) legacyDir.deleteRecursively()
            }
        }
    }

    /**
     * Strictly RAM-only lookup safe to call on the Main Thread during Compose layout/draw.
     */
    fun getMemoryCachedTile(z: Int, x: Int, y: Int): Bitmap? {
        val maxTile = 1 shl z
        if (z < 0 || z > 19 || y < 0 || y >= maxTile) return null
        val wrappedX = ((x % maxTile) + maxTile) % maxTile
        return memoryCache.get(tileKey("osm", z, wrappedX, y))?.takeIf { !it.isRecycled }
    }

    suspend fun loadTile(z: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        val maxTile = 1 shl z
        if (z < 0 || z > 19 || y < 0 || y >= maxTile) return@withContext null
        val wrappedX = ((x % maxTile) + maxTile) % maxTile
        val key = tileKey("osm", z, wrappedX, y)
        loadWithDeduplication(key) { buildCrispOsmUrls(z, wrappedX, y) }
    }

    /**
     * Loads minimalist, label-free architectural map tiles for Home Screen Widgets so
     * routes and buses stand out cleanly without giant neighborhood names cluttering the card.
     * Returns Pair(bitmap, needsStyleColorFilter).
     */
    suspend fun loadCleanWidgetTile(z: Int, x: Int, y: Int, style: MapStyle): Pair<Bitmap?, Boolean> =
        withContext(Dispatchers.IO) {
            val maxTile = 1 shl z
            if (z < 0 || z > 19 || y < 0 || y >= maxTile) return@withContext (null to false)
            val wrappedX = ((x % maxTile) + maxTile) % maxTile

            if (style == MapStyle.VOYAGER) {
                val bmp = loadTile(z, wrappedX, y)
                return@withContext (bmp to false)
            }

            val prefix = if (style.isDark) "wdark" else "wlight"
            val key = tileKey(prefix, z, wrappedX, y)
            val cleanBmp = loadWithDeduplication(key) {
                buildCleanWidgetUrls(z, wrappedX, y, isDark = style.isDark)
            }
            if (cleanBmp != null) {
                // Esri Dark/Light Gray Base is already natively styled and label-free!
                return@withContext (cleanBmp to false)
            }

            // Fallback to OSM tiles with ColorMatrix styling if Dark Gray is temporarily unreachable
            val fallbackOsm = loadTile(z, wrappedX, y)
            fallbackOsm to true
        }

    private suspend fun loadWithDeduplication(
        key: String,
        urlProvider: () -> List<String>,
    ): Bitmap? {
        memoryCache.get(key)?.takeIf { !it.isRecycled }?.let { return it }

        // Check disk cache off main thread
        val diskFile = tileFile(key)
        if (diskFile.exists()) {
            val decoded = runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
            if (decoded != null) {
                memoryCache.put(key, decoded)
                return decoded
            } else {
                runCatching { diskFile.delete() }
            }
        }

        // Deduplicate concurrent fetches for the same tile
        val existingDeferred = inFlightRequests[key]
        if (existingDeferred != null) {
            return existingDeferred.await()
        }

        val deferred = CompletableDeferred<Bitmap?>()
        val prev = inFlightRequests.putIfAbsent(key, deferred)
        if (prev != null) {
            return prev.await()
        }

        return try {
            var result: Bitmap? = null
            for (url in urlProvider()) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "image/png,image/*;q=0.9,*/*;q=0.8")
                        .header("Referer", "https://tobus.torino.app/")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) continue
                        val bytes = response.body?.bytes() ?: continue
                        if (bytes.size < 100) continue
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                        memoryCache.put(key, bitmap)
                        saveToDisk(key, bytes)
                        result = bitmap
                        break
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Tile skip $url: ${e.message}")
                }
            }
            deferred.complete(result)
            result
        } catch (t: Throwable) {
            deferred.complete(null)
            null
        } finally {
            inFlightRequests.remove(key, deferred)
        }
    }

    private fun buildCrispOsmUrls(z: Int, x: Int, y: Int): List<String> {
        val sub = OSM_FR_SUBDOMAINS[(x + y) % OSM_FR_SUBDOMAINS.size]
        return listOf(
            "https://tile.openstreetmap.org/$z/$x/$y.png",
            "https://$sub.tile.openstreetmap.fr/osmfr/$z/$x/$y.png",
        )
    }

    private fun buildCleanWidgetUrls(z: Int, x: Int, y: Int, isDark: Boolean): List<String> {
        return if (isDark) {
            listOf(
                // Minimalist dark architectural canvas with ZERO text/district labels
                "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/$z/$y/$x",
            )
        } else {
            listOf(
                // Minimalist light architectural canvas with ZERO text/district labels
                "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Light_Gray_Base/MapServer/tile/$z/$y/$x",
            )
        }
    }

    private fun tileKey(prefix: String, z: Int, x: Int, y: Int): String = "${prefix}_${z}_${x}_${y}"

    private fun tileFile(key: String): File = File(tileCacheDir, "$key.tile")

    private fun saveToDisk(key: String, bytes: ByteArray) {
        runCatching {
            val file = tileFile(key)
            FileOutputStream(file).use { it.write(bytes) }
            trimDiskCacheIfNeeded()
        }
    }

    private fun trimDiskCacheIfNeeded() {
        val files = tileCacheDir.listFiles() ?: return
        if (files.size <= MAX_DISK_TILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - (MAX_DISK_TILES * 3 / 4))
            .forEach { it.delete() }
    }

    companion object {
        private const val TAG = "MapTileManager"
        private const val USER_AGENT = "ToBusTorinoAndroid/2.2 (https://tobus.torino.app; transit widget)"
        private val OSM_FR_SUBDOMAINS = arrayOf("a", "b", "c")
        private const val MAX_DISK_TILES = 600

        @Volatile
        private var instance: MapTileManager? = null

        fun getInstance(context: Context): MapTileManager {
            return instance ?: synchronized(this) {
                instance ?: MapTileManager(context).also { instance = it }
            }
        }

        fun colorMatrixForStyle(style: MapStyle): FloatArray? {
            return when (style) {
                MapStyle.DARK -> {
                    val invert = ColorMatrix(
                        floatArrayOf(
                            -1f, 0f, 0f, 0f, 255f,
                            0f, -1f, 0f, 0f, 255f,
                            0f, 0f, -1f, 0f, 255f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    )
                    val hue180 = ColorMatrix(
                        floatArrayOf(
                            -0.574f, 1.430f, 0.144f, 0f, 0f,
                            0.426f, 0.430f, 0.144f, 0f, 0f,
                            0.426f, 1.430f, -0.856f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    )
                    // Deep slate-obsidian background with crisp, legible street names and muted emerald parks
                    val grade = ColorMatrix(
                        floatArrayOf(
                            0.86f, 0.03f, 0.04f, 0f, 4f,
                            0.03f, 0.88f, 0.06f, 0f, 8f,
                            0.03f, 0.05f, 0.94f, 0f, 15f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    )
                    val combined = ColorMatrix()
                    combined.postConcat(invert)
                    combined.postConcat(hue180)
                    combined.postConcat(grade)
                    combined.array
                }
                MapStyle.POSITRON -> {
                    val desat = ColorMatrix().apply { setSaturation(0.32f) }
                    val brighten = ColorMatrix(
                        floatArrayOf(
                            1.04f, 0f, 0f, 0f, 6f,
                            0f, 1.04f, 0f, 0f, 6f,
                            0f, 0f, 1.05f, 0f, 8f,
                            0f, 0f, 0f, 1f, 0f,
                        ),
                    )
                    desat.postConcat(brighten)
                    desat.array
                }
                MapStyle.VOYAGER -> null
            }
        }
    }
}
