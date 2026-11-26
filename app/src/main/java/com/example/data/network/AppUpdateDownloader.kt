package com.example.data.network

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

@androidx.compose.runtime.Immutable
sealed interface UpdateInstallState {
    data object Idle : UpdateInstallState

    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : UpdateInstallState {
        val progressFraction: Float?
            get() = if (totalBytes > 0L) {
                (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
    }

    data class ReadyToInstall(
        val apkFilePath: String,
        val launchSeq: Long,
    ) : UpdateInstallState

    data class NeedsInstallPermission(
        val apkFilePath: String,
    ) : UpdateInstallState

    data class Error(
        val message: String,
    ) : UpdateInstallState
}

/**
 * In-app streaming APK downloader & Android PackageInstaller launcher:
 * - Downloads verified GitHub release APKs directly into `cacheDir/apk_updates/` (zero storage permissions).
 * - Validates HTTPS GitHub/CDN host (including redirect targets), Content-Length, and ZIP/APK magic bytes (`PK\x03\x04`).
 * - Launches the standard Android system installer prompt via `FileProvider` with `FLAG_GRANT_READ_URI_PERMISSION`.
 * - Automatically deletes downloaded APK files on package replacement (`ACTION_MY_PACKAGE_REPLACED`), startup, cancel, or retry.
 */
object AppUpdateDownloader {
    private const val TAG = "AppUpdateDownloader"
    private const val UPDATES_DIR_NAME = "apk_updates"
    private const val MIN_VALID_APK_BYTES = 100L * 1024L
    private const val BUFFER_SIZE = 32 * 1024
    private const val PROGRESS_EMIT_INTERVAL_MS = 120L

    private val downloadClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun updatesDir(context: Context): File =
        File(context.applicationContext.cacheDir, UPDATES_DIR_NAME)

    /**
     * Deletes any cached `.apk` or partial `.part` update files so installed APKs never waste storage.
     */
    fun cleanupOldUpdates(context: Context): Int {
        val dir = updatesDir(context)
        if (!dir.exists() || !dir.isDirectory) return 0
        var removed = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile && runCatching { file.delete() }.getOrDefault(false)) {
                removed++
            }
        }
        return removed
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }
    }

    fun buildUnknownSourcesSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
    }

    fun buildInstallApkIntent(context: Context, apkFile: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val contentUri = FileProvider.getUriForFile(context, authority, apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    internal fun isAllowedDownloadHost(host: String?): Boolean {
        val lower = host?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (lower.isEmpty()) return false
        return lower == "github.com" ||
            lower.endsWith(".github.com") ||
            lower == "githubusercontent.com" ||
            lower.endsWith(".githubusercontent.com")
    }

    internal fun sanitizeApkFileName(tagName: String): String {
        val safeTag = tagName.trim()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "latest" }
        return "ToBus-$safeTag.apk"
    }

    internal fun hasValidApkHeader(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return runCatching {
            val header = ByteArray(4)
            file.inputStream().use { input ->
                if (input.read(header) != 4) return false
            }
            header[0] == 0x50.toByte() && // 'P'
                header[1] == 0x4B.toByte() && // 'K'
                header[2] == 0x03.toByte() &&
                header[3] == 0x04.toByte()
        }.getOrDefault(false)
    }

    suspend fun downloadApk(
        context: Context,
        update: AppUpdateInfo,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val apkUrl = update.apkDownloadUrl?.trim()
        if (apkUrl.isNullOrEmpty() || !AppUpdateChecker.isTrustedGithubUrl(apkUrl)) {
            return@withContext Result.failure(IOException("Link APK GitHub non valido"))
        }

        val dir = updatesDir(context).apply { mkdirs() }
        // Remove any previous cached APK or partial file before downloading the new release
        cleanupOldUpdates(context)

        val finalFile = File(dir, sanitizeApkFileName(update.tagName))
        val partFile = File(dir, "${finalFile.name}.part")

        val request = Request.Builder()
            .url(apkUrl)
            .header("Accept", "application/octet-stream, */*")
            .header("User-Agent", "ToBusTorino-Updater")
            .build()

        val call = downloadClient.newCall(request)
        try {
            call.execute().use { response ->
                currentCoroutineContext().ensureActive()

                val finalUrl = response.request.url
                if (!finalUrl.isHttps || !isAllowedDownloadHost(finalUrl.host)) {
                    return@withContext Result.failure(IOException("Host di download non autorizzato"))
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Download non riuscito (HTTP ${response.code})"))
                }

                val body = response.body
                    ?: return@withContext Result.failure(IOException("Risposta vuota dal server"))
                val totalBytes = body.contentLength()
                var downloaded = 0L
                var lastEmitMillis = 0L

                onProgress(0L, totalBytes)

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read > 0) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastEmitMillis >= PROGRESS_EMIT_INTERVAL_MS ||
                                    (totalBytes > 0L && downloaded >= totalBytes)
                                ) {
                                    lastEmitMillis = now
                                    onProgress(downloaded, totalBytes)
                                }
                            }
                        }
                        output.fd.sync()
                    }
                }

                if (totalBytes > 0L && downloaded != totalBytes) {
                    partFile.delete()
                    return@withContext Result.failure(IOException("Download incompleto, riprova"))
                }
                if (partFile.length() < MIN_VALID_APK_BYTES || !hasValidApkHeader(partFile)) {
                    partFile.delete()
                    return@withContext Result.failure(IOException("File APK non valido o danneggiato"))
                }

                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                onProgress(finalFile.length(), finalFile.length())
                Result.success(finalFile)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            runCatching { partFile.delete() }
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "APK download failed: ${e.message}")
            runCatching { partFile.delete() }
            Result.failure(IOException(e.message ?: "Errore durante il download"))
        }
    }

    fun formatByteSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.ITALY, "%.0f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.ITALY, "%.1f MB", mb)
    }
}
