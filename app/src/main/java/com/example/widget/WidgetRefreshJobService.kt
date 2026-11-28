package com.example.widget

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.example.data.model.RefreshInterval
import com.example.data.preferences.ToBusPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resilient network-aware periodic backup job for Home Screen Widgets via Android [JobScheduler].
 *
 * Works alongside [BusMapWidgetProvider]'s self-rescheduling [AlarmManager] without requiring any
 * extra manifest permissions: automatically wakes widgets once connectivity returns after Doze
 * or OEM background restrictions, and re-arms the primary alarm chain if the OS cleared it.
 */
class WidgetRefreshJobService : JobService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        val appContext = applicationContext
        val prefs = ToBusPreferences.getInstance(appContext)
        val interval = prefs.currentSettings().refreshInterval
        val isNetworkRetry = params?.jobId == WidgetRefreshJobScheduler.RETRY_JOB_ID

        if (!BusMapWidgetProvider.hasAnyInstalledWidgets(appContext)) {
            WidgetRefreshJobScheduler.cancel(appContext)
            return false
        }
        if (!isNetworkRetry && interval == RefreshInterval.MANUAL) {
            WidgetRefreshJobScheduler.cancel(appContext)
            return false
        }

        val now = System.currentTimeMillis()
        val lastAttempt = prefs.getLastWidgetRefreshAttemptMillis()
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val minGapMs = if (pm?.isPowerSaveMode == true) {
            maxOf(interval.minutes * 2L, 15L) * 60_000L
        } else {
            (interval.minutes * 60_000L * 0.75).toLong().coerceAtLeast(60_000L)
        }

        // Always keep AlarmManager chain healthy even if AlarmManager fired recently
        if (interval != RefreshInterval.MANUAL) {
            BusMapWidgetProvider.ensurePeriodicAlarmScheduled(appContext, interval)
        }

        // Bypass throttling for one-shot network recovery jobs so widgets refresh right away on wake
        if (!isNetworkRetry && lastAttempt > 0L && now - lastAttempt < minGapMs) {
            return false
        }

        activeJob?.cancel()
        activeJob = serviceScope.launch {
            try {
                withTimeoutOrNull(14_000L) {
                    BusMapWidgetProvider.refreshAllWidgetsSynchronously(
                        context = appContext,
                        showLoadingFeedback = false,
                        useCachedOnly = false,
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Job refresh widget saltato: ${e.message}")
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
        private const val TAG = "WidgetRefreshJobSvc"
    }
}

object WidgetRefreshJobScheduler {

    private const val TAG = "ToBusWidgetJobSched"
    const val JOB_ID = 42_031
    const val RETRY_JOB_ID = 42_032

    @Volatile
    private var lastScheduledIntervalMinutes: Int = -1

    fun isJobScheduled(context: Context): Boolean {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return false
        return scheduler.getPendingJob(JOB_ID) != null
    }

    fun syncSchedule(
        context: Context,
        interval: RefreshInterval,
        hasInstalledWidgets: Boolean = BusMapWidgetProvider.hasAnyInstalledWidgets(context),
    ) {
        val appContext = context.applicationContext
        val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        if (!hasInstalledWidgets || interval == RefreshInterval.MANUAL) {
            scheduler.cancel(JOB_ID)
            lastScheduledIntervalMinutes = -1
            return
        }

        val periodMinutes = maxOf(15L, interval.minutes.toLong())
        val alreadyScheduled = scheduler.getPendingJob(JOB_ID) != null
        if (alreadyScheduled && lastScheduledIntervalMinutes == interval.minutes) {
            return
        }

        val component = ComponentName(appContext, WidgetRefreshJobService::class.java)
        val periodMs = periodMinutes * 60_000L
        val flexMs = (periodMs / 3L).coerceAtLeast(5L * 60_000L)
        val jobInfo = JobInfo.Builder(JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(periodMs, flexMs)
            .setPersisted(true)
            .build()

        val result = runCatching { scheduler.schedule(jobInfo) }.getOrDefault(JobScheduler.RESULT_FAILURE)
        if (result == JobScheduler.RESULT_SUCCESS) {
            lastScheduledIntervalMinutes = interval.minutes
        } else {
            Log.w(TAG, "Scheduling WidgetRefreshJobService fallito (code=$result)")
        }
    }

    /**
     * Schedules a lightweight one-shot network-constrained retry job (no extra permissions)
     * that automatically wakes and refreshes widgets the instant validated connectivity returns
     * after Doze / screen-off standby.
     */
    fun scheduleNetworkRetry(context: Context) {
        val appContext = context.applicationContext
        if (!BusMapWidgetProvider.hasAnyInstalledWidgets(appContext)) return
        val scheduler = appContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        if (scheduler.getPendingJob(RETRY_JOB_ID) != null) return
        val component = ComponentName(appContext, WidgetRefreshJobService::class.java)
        val jobInfo = JobInfo.Builder(RETRY_JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(2_000L)
            .setOverrideDeadline(15L * 60_000L)
            .build()
        runCatching { scheduler.schedule(jobInfo) }
    }

    fun cancelNetworkRetry(context: Context) {
        val scheduler = context.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        scheduler.cancel(RETRY_JOB_ID)
    }

    fun cancel(context: Context) {
        val scheduler = context.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        scheduler.cancel(JOB_ID)
        scheduler.cancel(RETRY_JOB_ID)
        lastScheduledIntervalMinutes = -1
    }
}
