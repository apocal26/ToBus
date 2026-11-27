package com.example.data.network

import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.example.data.preferences.ToBusPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.example.data.network.MinimalJsonParser.optArray
import com.example.data.network.MinimalJsonParser.optBoolean
import com.example.data.network.MinimalJsonParser.optLong
import com.example.data.network.MinimalJsonParser.optString
import java.util.Locale
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val releasePageUrl: String,
    val apkDownloadUrl: String?,
    val selectedApkAbi: String? = null,
    val selectedApkSizeBytes: Long? = null,
) {
    /** Direct APK asset link when available, otherwise the GitHub Release page. */
    val preferredOpenUrl: String
        get() = apkDownloadUrl ?: releasePageUrl
}

internal data class ReleaseAssetCandidate(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long? = null,
)

internal data class SelectedApkAsset(
    val downloadUrl: String,
    val abiLabel: String?,
    val sizeBytes: Long?,
)

sealed interface UpdateCheckResult {
    data class Available(val info: AppUpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data object Throttled : UpdateCheckResult
    data object RepoNotConfigured : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Feather-light GitHub Releases update checker designed for low-end devices:
 * - Zero background services, WorkManager, or periodic timers.
 * - Strict 24-hour throttle persisted in SharedPreferences (returns in < 0.1 ms when already checked today).
 * - Single tiny (~2 KB) GET to `api.github.com/repos/{owner}/{repo}/releases/latest` on `Dispatchers.IO`.
 * - Deterministic ABI-aware APK selection matching [Build.SUPPORTED_ABIS] with automatic fallback to
 *   a universal APK (and safe release-page fallback if no compatible APK exists for this CPU).
 * - Pairs with [AppUpdateDownloader] for verified HTTPS in-app APK download via `FileProvider`
 *   and system `PackageInstaller` confirmation.
 */
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"
    internal const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private val client: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun checkForUpdate(
        preferences: ToBusPreferences,
        force: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
        currentVersionName: String = BuildConfig.VERSION_NAME,
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        val repoSlug = normalizeRepoSlug(preferences.getGithubRepo())
            ?: return@withContext UpdateCheckResult.RepoNotConfigured

        if (!force) {
            val lastCheck = preferences.getLastUpdateCheckMillis()
            if (lastCheck > 0L && (nowMillis - lastCheck) < CHECK_INTERVAL_MS) {
                return@withContext UpdateCheckResult.Throttled
            }
        }

        val url = "https://api.github.com/repos/$repoSlug/releases/latest"
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "ToBusTorino/${currentVersionName}")
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.code == 404) {
                    // Repo has no published releases yet; record check so we don't retry until tomorrow
                    preferences.setLastUpdateCheckMillis(nowMillis)
                    return@withContext UpdateCheckResult.UpToDate
                }
                if (!resp.isSuccessful) {
                    return@withContext UpdateCheckResult.Error("GitHub HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                preferences.setLastUpdateCheckMillis(nowMillis)

                val info = parseLatestReleaseJson(body)
                    ?: return@withContext UpdateCheckResult.UpToDate

                if (!isRemoteNewer(currentVersionName, info.tagName)) {
                    return@withContext UpdateCheckResult.UpToDate
                }
                if (!force) {
                    val dismissed = preferences.getDismissedUpdateTag()
                    if (!dismissed.isNullOrBlank() && dismissed.equals(info.tagName, ignoreCase = true)) {
                        return@withContext UpdateCheckResult.UpToDate
                    }
                }
                UpdateCheckResult.Available(info)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Update check skipped: ${e.message}")
            UpdateCheckResult.Error(e.message ?: "Connessione non disponibile")
        }
    }

    /**
     * Normalizes user input such as `https://github.com/user/ToBus`, `github.com/user/ToBus.git`,
     * or `user/ToBus` into a safe `owner/repo` slug, or returns `null` if invalid.
     */
    internal fun normalizeRepoSlug(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val cleaned = trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removePrefix("github.com/")
            .removeSuffix("/")
            .removeSuffix(".git")
        val parts = cleaned.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        val owner = parts[0]
        val repo = parts[1]
        val validRegex = Regex("^[A-Za-z0-9._-]+$")
        if (!validRegex.matches(owner) || !validRegex.matches(repo)) return null
        return "$owner/$repo"
    }

    /**
     * Compares semantic/dotted numeric versions (e.g. `1.0` vs `v1.1.0`, `1.9` vs `1.10.0`).
     * Returns `true` strictly when [remoteTag] is higher than [currentVersion].
     */
    internal fun isRemoteNewer(currentVersion: String, remoteTag: String): Boolean {
        val currentParts = parseNumericVersionParts(currentVersion)
        val remoteParts = parseNumericVersionParts(remoteTag)
        if (remoteParts.isEmpty()) return false
        if (currentParts.isEmpty()) return true
        val maxLen = maxOf(currentParts.size, remoteParts.size)
        for (i in 0 until maxLen) {
            val cur = currentParts.getOrElse(i) { 0 }
            val rem = remoteParts.getOrElse(i) { 0 }
            if (rem != cur) return rem > cur
        }
        return false
    }

    private fun parseNumericVersionParts(raw: String): List<Int> {
        val stripped = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (stripped.isEmpty()) return emptyList()
        return stripped.split('.').mapNotNull { seg ->
            seg.filter { it.isDigit() }.toIntOrNull()
        }
    }

    internal fun currentDeviceAbis(): List<String> = runCatching {
        Build.SUPPORTED_ABIS?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    }.getOrDefault(emptyList())

    /**
     * Extracts a canonical Android ABI identifier from a release APK filename,
     * `"universal"` when explicitly labeled universal, or `null` when untagged
     * (legacy single-APK releases).
     */
    internal fun detectApkAbiTag(fileName: String): String? {
        val lower = fileName.lowercase(Locale.ROOT)
        return when {
            lower.contains("arm64-v8a") || lower.contains("arm64_v8a") ||
                Regex("(^|[-_.])arm64([-_.]|$)").containsMatchIn(lower) -> "arm64-v8a"
            lower.contains("armeabi-v7a") || lower.contains("armeabi_v7a") ||
                Regex("(^|[-_.])armv7a?([-_.]|$)").containsMatchIn(lower) -> "armeabi-v7a"
            lower.contains("x86_64") || lower.contains("x86-64") -> "x86_64"
            Regex("(^|[-_.])x86([-_.]|$)").containsMatchIn(lower) -> "x86"
            lower.contains("universal") -> "universal"
            else -> null
        }
    }

    /**
     * Picks the best installable APK asset for [supportedAbis]:
     * 1. Exact ABI match following the device's ordered [Build.SUPPORTED_ABIS] preference.
     * 2. Explicit `universal` APK fallback.
     * 3. Legacy untagged `.apk` fallback (e.g. `ToBus-v1.1.0.apk`).
     * 4. `null` if only incompatible ABI-specific APKs exist (never downloads an un-installable APK).
     */
    internal fun selectBestApkAsset(
        assets: List<ReleaseAssetCandidate>,
        supportedAbis: List<String>,
    ): SelectedApkAsset? {
        val validApks = assets.filter { candidate ->
            candidate.name.endsWith(".apk", ignoreCase = true) &&
                isTrustedGithubUrl(candidate.downloadUrl)
        }
        if (validApks.isEmpty()) return null

        val tagged = validApks.map { candidate ->
            candidate to detectApkAbiTag(candidate.name)
        }

        // 1. Strict device-preferred ABI match (e.g. arm64-v8a before armeabi-v7a)
        for (deviceAbi in supportedAbis) {
            val normalizedAbi = deviceAbi.trim().lowercase(Locale.ROOT)
            val match = tagged.firstOrNull { (_, tag) -> tag == normalizedAbi }
            if (match != null) {
                return SelectedApkAsset(
                    downloadUrl = match.first.downloadUrl,
                    abiLabel = match.second,
                    sizeBytes = match.first.sizeBytes?.takeIf { it > 0L },
                )
            }
        }

        // 2. Explicit universal APK fallback
        val explicitUniversal = tagged.firstOrNull { (_, tag) -> tag == "universal" }
        if (explicitUniversal != null) {
            return SelectedApkAsset(
                downloadUrl = explicitUniversal.first.downloadUrl,
                abiLabel = "universal",
                sizeBytes = explicitUniversal.first.sizeBytes?.takeIf { it > 0L },
            )
        }

        // 3. Legacy untagged APK fallback (single-APK releases without ABI suffix)
        val legacyUntagged = tagged.firstOrNull { (_, tag) -> tag == null }
        if (legacyUntagged != null) {
            return SelectedApkAsset(
                downloadUrl = legacyUntagged.first.downloadUrl,
                abiLabel = null,
                sizeBytes = legacyUntagged.first.sizeBytes?.takeIf { it > 0L },
            )
        }

        // 4. Only incompatible ABI-specific APKs exist -> return null so UI falls back to GitHub page
        return null
    }

    internal fun parseLatestReleaseJson(
        jsonText: String,
        supportedAbis: List<String> = currentDeviceAbis(),
    ): AppUpdateInfo? {
        if (jsonText.isBlank()) return null
        return runCatching {
            val obj = MinimalJsonParser.parseObject(jsonText) ?: return null
            if (obj.optBoolean("draft", false) || obj.optBoolean("prerelease", false)) {
                return null
            }
            val tagName = obj.optString("tag_name", "").trim()
            val htmlUrl = obj.optString("html_url", "").trim()
            if (tagName.isEmpty() || !isTrustedGithubUrl(htmlUrl)) return null

            val title = obj.optString("name", "").trim().ifBlank { "ToBus $tagName" }
            val rawNotes = obj.optString("body", "").trim()
                .replace(Regex("\r\n"), "\n")
                .replace(Regex("\n{3,}"), "\n\n")
            val notes = if (rawNotes.length > 4000) rawNotes.take(3997).trimEnd() + "…" else rawNotes

            val candidates = mutableListOf<ReleaseAssetCandidate>()
            val assets = obj.optArray("assets")
            if (assets != null) {
                for (rawAsset in assets) {
                    val asset = rawAsset as? Map<*, *> ?: continue
                    val name = asset.optString("name", "").trim()
                    val downloadUrl = asset.optString("browser_download_url", "").trim()
                    val size = asset.optLong("size", -1L).takeIf { it > 0L }
                    if (name.isNotEmpty() && downloadUrl.isNotEmpty()) {
                        candidates.add(ReleaseAssetCandidate(name = name, downloadUrl = downloadUrl, sizeBytes = size))
                    }
                }
            }

            val bestApk = selectBestApkAsset(candidates, supportedAbis)

            AppUpdateInfo(
                tagName = tagName,
                title = title,
                releaseNotes = notes,
                releasePageUrl = htmlUrl,
                apkDownloadUrl = bestApk?.downloadUrl,
                selectedApkAbi = bestApk?.abiLabel,
                selectedApkSizeBytes = bestApk?.sizeBytes,
            )
        }.getOrNull()
    }

    internal fun isTrustedGithubUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("https://github.com/") ||
            trimmed.startsWith("https://objects.githubusercontent.com/") ||
            trimmed.startsWith("https://release-assets.githubusercontent.com/")
    }
}
