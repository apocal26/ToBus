package com.example.data.network

import android.util.Log
import com.example.BuildConfig
import com.example.data.preferences.ToBusPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val tagName: String,
    val title: String,
    val releaseNotes: String,
    val releasePageUrl: String,
    val apkDownloadUrl: String?,
) {
    /** Direct APK asset link when available, otherwise the GitHub Release page. */
    val preferredOpenUrl: String
        get() = apkDownloadUrl ?: releasePageUrl
}

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
 * - Pairs with [AppUpdateDownloader] for verified HTTPS in-app APK download via `FileProvider`
 *   and system `PackageInstaller` confirmation (or GitHub Release fallback when no APK asset is attached).
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

    internal fun parseLatestReleaseJson(jsonText: String): AppUpdateInfo? {
        if (jsonText.isBlank()) return null
        return runCatching {
            val obj = JSONObject(jsonText)
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

            var apkUrl: String? = null
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "").trim()
                    val downloadUrl = asset.optString("browser_download_url", "").trim()
                    if (name.endsWith(".apk", ignoreCase = true) && isTrustedGithubUrl(downloadUrl)) {
                        apkUrl = downloadUrl
                        break
                    }
                }
            }

            AppUpdateInfo(
                tagName = tagName,
                title = title,
                releaseNotes = notes,
                releasePageUrl = htmlUrl,
                apkDownloadUrl = apkUrl,
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
