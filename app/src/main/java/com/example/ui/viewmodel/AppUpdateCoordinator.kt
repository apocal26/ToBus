package com.example.ui.viewmodel

import android.content.Context
import com.example.data.network.AppUpdateChecker
import com.example.data.network.AppUpdateDownloader
import com.example.data.network.UpdateCheckResult
import com.example.data.network.UpdateInstallState
import com.example.data.preferences.ToBusPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Coordinates GitHub Releases update checks and the ABI-safe in-app APK download/install state
 * machine for [ToBusViewModel].
 */
internal class AppUpdateCoordinator(
    private val appContext: Context,
    private val preferences: ToBusPreferences,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<ToBusUiState>,
) {
    private var updateDownloadJob: Job? = null
    private var installLaunchSeq: Long = 0L

    /**
     * Cleans up any previously downloaded update APKs and runs the throttled once-per-24h
     * GitHub Releases check after cold boot settles.
     */
    fun scheduleStartupCheck() {
        scope.launch(Dispatchers.IO) {
            AppUpdateDownloader.cleanupOldUpdates(appContext)
            delay(4_500L)
            val result = AppUpdateChecker.checkForUpdate(preferences = preferences, force = false)
            if (result is UpdateCheckResult.Available) {
                uiState.update { it.copy(availableUpdate = result.info) }
            }
        }
    }

    fun checkForUpdatesManually() {
        if (uiState.value.isCheckingUpdate) return
        scope.launch {
            uiState.update { it.copy(isCheckingUpdate = true) }
            val result = AppUpdateChecker.checkForUpdate(preferences = preferences, force = true)
            uiState.update { state ->
                when (result) {
                    is UpdateCheckResult.Available -> state.copy(
                        isCheckingUpdate = false,
                        availableUpdate = result.info,
                    )
                    UpdateCheckResult.UpToDate -> state.copy(
                        isCheckingUpdate = false,
                        statusToast = "ToBus è già aggiornata all'ultima versione",
                    )
                    UpdateCheckResult.RepoNotConfigured -> state.copy(
                        isCheckingUpdate = false,
                        statusToast = "Imposta prima il repository GitHub",
                    )
                    UpdateCheckResult.Throttled -> state.copy(isCheckingUpdate = false)
                    is UpdateCheckResult.Error -> state.copy(
                        isCheckingUpdate = false,
                        statusToast = "Controllo aggiornamenti non riuscito (${result.message})",
                    )
                }
            }
        }
    }

    fun startInAppUpdateDownload() {
        val update = uiState.value.availableUpdate ?: return
        if (update.apkDownloadUrl.isNullOrBlank()) return
        if (uiState.value.updateInstallState is UpdateInstallState.Downloading) return

        updateDownloadJob?.cancel()
        uiState.update {
            it.copy(updateInstallState = UpdateInstallState.Downloading(bytesDownloaded = 0L, totalBytes = -1L))
        }
        updateDownloadJob = scope.launch {
            val result = AppUpdateDownloader.downloadApk(
                context = appContext,
                update = update,
                onProgress = { downloaded, total ->
                    uiState.update { state ->
                        if (state.updateInstallState is UpdateInstallState.Downloading) {
                            state.copy(
                                updateInstallState = UpdateInstallState.Downloading(
                                    bytesDownloaded = downloaded,
                                    totalBytes = total,
                                ),
                            )
                        } else {
                            state
                        }
                    }
                },
            )
            result.fold(
                onSuccess = { apkFile ->
                    val nextState = if (AppUpdateDownloader.canInstallPackages(appContext)) {
                        installLaunchSeq++
                        UpdateInstallState.ReadyToInstall(
                            apkFilePath = apkFile.absolutePath,
                            launchSeq = installLaunchSeq,
                        )
                    } else {
                        UpdateInstallState.NeedsInstallPermission(apkFilePath = apkFile.absolutePath)
                    }
                    uiState.update { it.copy(updateInstallState = nextState) }
                },
                onFailure = { err ->
                    uiState.update {
                        it.copy(
                            updateInstallState = UpdateInstallState.Error(
                                err.message ?: "Download non riuscito",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun cancelUpdateDownload() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        scope.launch(Dispatchers.IO) {
            AppUpdateDownloader.cleanupOldUpdates(appContext)
        }
        uiState.update { it.copy(updateInstallState = UpdateInstallState.Idle) }
    }

    fun onInstallPermissionReturned() {
        val current = uiState.value.updateInstallState
        val path = when (current) {
            is UpdateInstallState.NeedsInstallPermission -> current.apkFilePath
            is UpdateInstallState.ReadyToInstall -> current.apkFilePath
            else -> return
        }
        val file = File(path)
        if (!file.exists()) {
            uiState.update {
                it.copy(updateInstallState = UpdateInstallState.Error("File APK non trovato, riprova il download"))
            }
            return
        }
        if (AppUpdateDownloader.canInstallPackages(appContext)) {
            installLaunchSeq++
            uiState.update {
                it.copy(
                    updateInstallState = UpdateInstallState.ReadyToInstall(
                        apkFilePath = path,
                        launchSeq = installLaunchSeq,
                    ),
                )
            }
        }
    }

    fun retriggerReadyApkInstall() {
        val current = uiState.value.updateInstallState
        val path = when (current) {
            is UpdateInstallState.ReadyToInstall -> current.apkFilePath
            is UpdateInstallState.NeedsInstallPermission -> current.apkFilePath
            else -> return
        }
        val file = File(path)
        if (!file.exists()) {
            startInAppUpdateDownload()
            return
        }
        if (AppUpdateDownloader.canInstallPackages(appContext)) {
            installLaunchSeq++
            uiState.update {
                it.copy(
                    updateInstallState = UpdateInstallState.ReadyToInstall(
                        apkFilePath = path,
                        launchSeq = installLaunchSeq,
                    ),
                )
            }
        } else {
            uiState.update {
                it.copy(updateInstallState = UpdateInstallState.NeedsInstallPermission(apkFilePath = path))
            }
        }
    }

    fun dismissUpdateDialog() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        val wasInstalling = uiState.value.updateInstallState is UpdateInstallState.ReadyToInstall
        if (!wasInstalling) {
            scope.launch(Dispatchers.IO) {
                AppUpdateDownloader.cleanupOldUpdates(appContext)
            }
        }
        uiState.update {
            it.copy(
                availableUpdate = null,
                updateInstallState = UpdateInstallState.Idle,
            )
        }
    }

    fun ignoreCurrentUpdateVersion() {
        updateDownloadJob?.cancel()
        updateDownloadJob = null
        scope.launch(Dispatchers.IO) {
            AppUpdateDownloader.cleanupOldUpdates(appContext)
        }
        val tag = uiState.value.availableUpdate?.tagName
        if (!tag.isNullOrBlank()) {
            preferences.setDismissedUpdateTag(tag)
        }
        uiState.update {
            it.copy(
                availableUpdate = null,
                updateInstallState = UpdateInstallState.Idle,
            )
        }
    }

    fun saveGithubRepoSlug(slug: String) {
        preferences.setGithubRepo(slug)
        preferences.setLastUpdateCheckMillis(0L)
        uiState.update {
            it.copy(
                githubRepoSlug = preferences.getGithubRepo(),
                statusToast = if (slug.isBlank()) "Repository GitHub rimosso" else "Repository salvato: $slug",
            )
        }
        if (slug.isNotBlank()) {
            checkForUpdatesManually()
        }
    }
}
