package com.example.notifications

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.example.data.network.GttRealtimeRepository
import com.example.data.preferences.ToBusPreferences
import com.example.widget.FavoriteAlertsWidgetProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lightweight periodic background job that checks GTT GTFS-RT service alerts for the user's
 * favorite lines and posts a notification strictly for brand-new unseen alerts.
 */
class FavoriteAlertsJobService : JobService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val prefs = ToBusPreferences.getInstance(applicationContext)
        val settings = prefs.currentSettings()
        val favorites = prefs.currentFavoriteLines()
        if (!settings.notifyFavoriteLineAlerts || favorites.isEmpty()) {
            FavoriteAlertsScheduler.syncSchedule(
                context = applicationContext,
                enabled = settings.notifyFavoriteLineAlerts,
                hasFavoriteLines = favorites.isNotEmpty(),
            )
            return false
        }

        activeJob?.cancel()
        activeJob = serviceScope.launch {
            try {
                val repo = GttRealtimeRepository.getInstance(applicationContext)
                val alerts = withTimeoutOrNull(14_000L) {
                    repo.fetchServiceAlertsNow()
                }
                if (alerts != null) {
                    FavoriteAlertNotifier.processFetchedAlerts(
                        context = applicationContext,
                        alerts = alerts,
                        postNotifications = true,
                    )
                    FavoriteAlertsWidgetProvider.syncWithAlerts(applicationContext, alerts)
                }
            } catch (e: Exception) {
                Log.d(TAG, "Controllo avvisi background saltato: ${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        activeJob?.cancel()
        activeJob = null
        return false
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FavoriteAlertsJobSvc"
    }
}
