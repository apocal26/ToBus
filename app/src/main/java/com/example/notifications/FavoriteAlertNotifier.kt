package com.example.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.ServiceAlertInfo
import com.example.data.preferences.ToBusPreferences
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

data class MatchedFavoriteAlert(
    val alert: ServiceAlertInfo,
    val matchedLines: List<String>,
    val contentFingerprint: String,
    val allFingerprints: Set<String>,
)

/**
 * Deduplicated, battery-friendly notification engine for new GTT service alerts
 * affecting the user's favorite lines.
 *
 * Anti-repetition guarantees:
 * - Tracks BOTH a normalized content SHA-256 fingerprint (`c:<hash>`) AND the feed entity ID (`id:<id>`).
 * - If GTT regenerates alert IDs every morning, the content fingerprint still matches -> NEVER re-notifies.
 * - If GTT edits minor punctuation/typos while keeping the same ID, the ID fingerprint still matches -> NEVER re-notifies.
 * - Keeps seen fingerprints for 90 days in persistent SharedPreferences.
 * - Seeds already-active alerts on first opt-in so enabling notifications never floods the user with old notices.
 */
object FavoriteAlertNotifier {

    private const val TAG = "ToBusAlertNotifier"
    const val CHANNEL_ID = "tobus_favorite_alerts"
    const val EXTRA_OPEN_ALERTS_LINE = "extra_open_alerts_line"
    const val EXTRA_OPEN_ALERTS_DIALOG = "extra_open_alerts_dialog"
    private const val SUMMARY_NOTIFICATION_ID = 42_000
    private const val MAX_INDIVIDUAL_NOTIFICATIONS = 3

    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
    private val processLock = Any()

    internal fun normalizeTextForFingerprint(raw: String): String {
        if (raw.isBlank()) return ""
        val decomposed = Normalizer.normalize(raw.trim().lowercase(Locale.ITALY), Normalizer.Form.NFD)
        val noAccents = DIACRITICS_REGEX.replace(decomposed, "")
        return NON_ALNUM_REGEX.replace(noAccents, " ").trim()
    }

    internal fun computeContentFingerprint(alert: ServiceAlertInfo): String {
        val normHeader = normalizeTextForFingerprint(alert.headerText)
        val normDesc = normalizeTextForFingerprint(alert.descriptionText)
        val payload = if (normDesc.isNotEmpty()) {
            "$normHeader|$normDesc"
        } else {
            val sortedLines = alert.affectedLines
                .map { it.trim().uppercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString(",")
            "$normHeader|lines:$sortedLines"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(24)
        for (i in 0 until 12) {
            val v = digest[i].toInt() and 0xFF
            hex.append(Character.forDigit(v ushr 4, 16))
            hex.append(Character.forDigit(v and 0x0F, 16))
        }
        return "c:$hex"
    }

    internal fun computeAlertFingerprints(alert: ServiceAlertInfo): Set<String> {
        val contentFp = computeContentFingerprint(alert)
        val cleanId = alert.id.trim().lowercase(Locale.ROOT)
        return if (cleanId.isNotEmpty()) {
            setOf(contentFp, "id:$cleanId")
        } else {
            setOf(contentFp)
        }
    }

    /**
     * Pure selection function: returns ONLY current/upcoming alerts that affect at least one
     * favorite line and have NEVER been seen before (by content hash OR entity ID), deduplicated
     * within the batch so multi-line notices produce a single concise alert.
     */
    fun findNewFavoriteLineAlerts(
        alerts: List<ServiceAlertInfo>,
        favoriteLines: List<String>,
        seenFingerprints: Set<String>,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): List<MatchedFavoriteAlert> {
        val favOrdered = favoriteLines
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
        if (favOrdered.isEmpty() || alerts.isEmpty()) return emptyList()
        val favSet = favOrdered.toSet()

        val batchSeenContent = HashSet<String>()
        val matched = mutableListOf<MatchedFavoriteAlert>()

        for (alert in alerts) {
            if (!alert.isCurrentOrUpcoming(nowEpochSeconds = nowEpochSeconds, maxHorizonDays = 7)) {
                continue
            }
            val affectedUpper = alert.affectedLines
                .map { it.trim().uppercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }
                .toSet()
            val matchingFavs = favOrdered.filter { it in affectedUpper && it in favSet }
            if (matchingFavs.isEmpty()) continue

            val contentFp = computeContentFingerprint(alert)
            val allFps = computeAlertFingerprints(alert)
            if (allFps.any { it in seenFingerprints }) continue
            if (!batchSeenContent.add(contentFp)) continue

            matched.add(
                MatchedFavoriteAlert(
                    alert = alert,
                    matchedLines = matchingFavs,
                    contentFingerprint = contentFp,
                    allFingerprints = allFps,
                ),
            )
        }
        return matched
    }

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_alerts_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_alerts_desc)
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Marks all currently active alerts for the user's favorite lines as already seen without
     * posting any notifications (used on initial opt-in, when starring a line, or when opening
     * the in-app Alerts sheet).
     */
    fun markAlertsSeen(
        context: Context,
        alerts: List<ServiceAlertInfo>,
        favoriteLines: List<String>? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val prefs = ToBusPreferences.getInstance(context)
        val favSet = favoriteLines
            ?.map { it.trim().uppercase(Locale.ROOT) }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
        val fingerprints = HashSet<String>()
        for (alert in alerts) {
            if (favSet != null) {
                val hitsFav = alert.affectedLines.any { it.trim().uppercase(Locale.ROOT) in favSet }
                if (!hitsFav) continue
            }
            fingerprints.addAll(computeAlertFingerprints(alert))
        }
        if (fingerprints.isNotEmpty()) {
            prefs.markAlertFingerprintsSeen(fingerprints, nowMillis)
        }
        if (alerts.isNotEmpty() && !prefs.isAlertBaselineSeeded()) {
            prefs.setAlertBaselineSeeded(true)
        }
    }

    /**
     * Evaluates freshly fetched GTT alerts against persistent seen fingerprints and posts
     * notifications strictly for brand-new alerts on favorite lines.
     */
    fun processFetchedAlerts(
        context: Context,
        alerts: List<ServiceAlertInfo>,
        postNotifications: Boolean = true,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int {
        val newMatches = synchronized(processLock) {
            val prefs = ToBusPreferences.getInstance(context)
            val settings = prefs.currentSettings()
            if (!settings.notifyFavoriteLineAlerts) return 0
            val favorites = prefs.currentFavoriteLines()
            if (favorites.isEmpty()) return 0

            // First-ever fetch after enabling notifications: establish baseline silently so old active
            // alerts never flood the notification shade.
            if (!prefs.isAlertBaselineSeeded()) {
                if (alerts.isNotEmpty()) {
                    markAlertsSeen(context, alerts, favorites, nowMillis)
                }
                prefs.setAlertBaselineSeeded(true)
                return 0
            }

            val seenMap = prefs.getSeenAlertFingerprints(nowMillis)
            val matches = findNewFavoriteLineAlerts(
                alerts = alerts,
                favoriteLines = favorites,
                seenFingerprints = seenMap.keys,
                nowEpochSeconds = nowMillis / 1000L,
            )
            if (matches.isEmpty()) return 0

            // Persist seen fingerprints BEFORE posting so even an abrupt process kill or concurrent
            // poll can never trigger a duplicate notification.
            val toPersist = HashSet<String>(matches.size * 2)
            matches.forEach { toPersist.addAll(it.allFingerprints) }
            prefs.markAlertFingerprintsSeen(toPersist, nowMillis)
            matches
        }

        if (!postNotifications) return 0
        if (!hasNotificationPermission(context)) return 0

        ensureChannel(context)
        return postMatchedNotifications(context, newMatches)
    }

    /**
     * Hidden debug utility: posts a realistic simulated GTT alert notification through the real
     * channel & PendingIntent without modifying production alert fingerprints.
     */
    fun sendDebugTestNotification(
        context: Context,
        lineCode: String,
    ): Pair<Boolean, String> {
        if (!hasNotificationPermission(context)) {
            return false to "Permesso notifiche Android non concesso (attiva prima il toggle o i permessi di sistema)."
        }
        val cleanLine = lineCode.trim().uppercase(Locale.ROOT).ifEmpty { "4" }
        ensureChannel(context)
        val synthetic = ServiceAlertInfo(
            id = "debug_test_${System.currentTimeMillis()}",
            headerText = "Deviazione temporanea di prova",
            descriptionText = "Test notifica ToBus per la linea $cleanLine. Tocca questa notifica per aprire direttamente il pannello avvisi.",
            affectedLines = setOf(cleanLine),
            activePeriods = listOf(
                com.example.data.model.AlertTimeRange(
                    startEpochSeconds = (System.currentTimeMillis() / 1000L) - 60L,
                    endEpochSeconds = (System.currentTimeMillis() / 1000L) + 3_600L,
                ),
            ),
        )
        val match = MatchedFavoriteAlert(
            alert = synthetic,
            matchedLines = listOf(cleanLine),
            contentFingerprint = "debug_test_${System.currentTimeMillis()}",
            allFingerprints = emptySet(),
        )
        val posted = postMatchedNotifications(context, listOf(match))
        return if (posted > 0) {
            true to "Notifica di prova inviata per linea $cleanLine. Controlla la tendina e toccala per testare l'apertura."
        } else {
            false to "Invio notifica fallito (verifica notifiche abilitate nelle impostazioni Android)."
        }
    }

    /**
     * Hidden debug utility: runs an isolated 3-step anti-repetition check (Day 1 fresh alert ->
     * Day 2 regenerated GTT ID with punctuation tweak -> Day 3 same ID with edited text) without
     * mutating real user preferences.
     */
    fun runDebugAntiRepeatSelfTest(lineCode: String): String {
        val cleanLine = lineCode.trim().uppercase(Locale.ROOT).ifEmpty { "4" }
        val nowSec = System.currentTimeMillis() / 1000L
        val day1 = ServiceAlertInfo(
            id = "gtt_daily_2026_01",
            headerText = "Deviazione in Corso Regina Margherita",
            descriptionText = "Cantiere stradale: deviazione temporanea fino a fine lavori.",
            affectedLines = setOf(cleanLine),
            activePeriods = listOf(
                com.example.data.model.AlertTimeRange(
                    startEpochSeconds = nowSec - 600L,
                    endEpochSeconds = nowSec + 86_400L * 3L,
                ),
            ),
        )
        val seen = HashSet<String>()
        val step1 = findNewFavoriteLineAlerts(listOf(day1), listOf(cleanLine), seen, nowSec)
        step1.forEach { seen.addAll(it.allFingerprints) }

        val day2RegeneratedId = day1.copy(
            id = "gtt_daily_2026_02_new_id",
            headerText = "  Deviazione in corso regina margherita! ",
            descriptionText = "Cantiere stradale — deviazione temporanea fino a fine lavori",
        )
        val step2 = findNewFavoriteLineAlerts(listOf(day2RegeneratedId), listOf(cleanLine), seen, nowSec + 86_400L)

        val day3TextEditSameId = day1.copy(
            descriptionText = "Cantiere stradale prorogato di 24 ore.",
        )
        val step3 = findNewFavoriteLineAlerts(listOf(day3TextEditSameId), listOf(cleanLine), seen, nowSec + 172_800L)

        val ok = step1.size == 1 && step2.isEmpty() && step3.isEmpty()
        val verdict = if (ok) "SUPERATO" else "ANOMALIA"
        return buildString {
            append("Anti-ripetizione $verdict (linea $cleanLine)\n")
            append("• Giorno 1 (nuovo): ${step1.size} notificato\n")
            append("• Giorno 2 (ID rigenerato + punteggiatura): ${step2.size} bloccato\n")
            append("• Giorno 3 (stesso ID + testo ritoccato): ${step3.size} bloccato")
        }
    }

    private fun postMatchedNotifications(
        context: Context,
        matches: List<MatchedFavoriteAlert>,
    ): Int {
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return 0
        }

        return try {
            if (matches.size > MAX_INDIVIDUAL_NOTIFICATIONS) {
                val allLines = matches.flatMap { it.matchedLines }.distinct()
                val primaryLine = allLines.firstOrNull().orEmpty()
                val linesLabel = allLines.take(5).joinToString(", ") + if (allLines.size > 5) "…" else ""
                val title = "${matches.size} nuovi avvisi GTT · Linee $linesLabel"
                val previewLines = matches.take(4).joinToString("\n") { m ->
                    val prefix = m.matchedLines.take(2).joinToString("/")
                    val header = m.alert.headerText.ifBlank { m.alert.descriptionText }.trim()
                    "• Linea $prefix: $header"
                }
                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_tobus_alert)
                    .setContentTitle(title)
                    .setContentText(previewLines.lineSequence().firstOrNull().orEmpty())
                    .setStyle(NotificationCompat.BigTextStyle().bigText(previewLines))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setContentIntent(buildOpenAlertsPendingIntent(context, primaryLine, SUMMARY_NOTIFICATION_ID))
                    .build()
                manager.notify(SUMMARY_NOTIFICATION_ID, notification)
                1
            } else {
                var posted = 0
                for (match in matches) {
                    val linesPrefix = if (match.matchedLines.size == 1) {
                        "Avviso Linea ${match.matchedLines.first()}"
                    } else {
                        "Avviso Linee ${match.matchedLines.take(4).joinToString(", ")}"
                    }
                    val header = match.alert.headerText.trim()
                    val body = match.alert.descriptionText.trim().ifBlank { header }
                    val contentTitle = if (header.isNotEmpty() && !header.equals(body, ignoreCase = true)) {
                        "$linesPrefix · $header"
                    } else {
                        linesPrefix
                    }
                    val notifId = 42_100 + (match.contentFingerprint.hashCode() and 0x7FFF)
                    val primaryLine = match.matchedLines.first()
                    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_stat_tobus_alert)
                        .setContentTitle(contentTitle)
                        .setContentText(body)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setAutoCancel(true)
                        .setContentIntent(buildOpenAlertsPendingIntent(context, primaryLine, notifId))
                        .build()
                    manager.notify(notifId, notification)
                    posted++
                }
                posted
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Permesso notifiche mancante: ${e.message}")
            0
        } catch (e: Exception) {
            Log.w(TAG, "Invio notifica avvisi fallito: ${e.message}")
            0
        }
    }

    private fun buildOpenAlertsPendingIntent(
        context: Context,
        lineCode: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ALERTS_LINE, lineCode)
            putExtra(EXTRA_OPEN_ALERTS_DIALOG, true)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Zero-dependency, Doze-friendly periodic background scheduler using native Android [JobScheduler].
 * Runs roughly every 30 minutes only when network is available, notifications are enabled, AND
 * the user has at least one favorite line. Automatically persists across reboots.
 */
object FavoriteAlertsScheduler {

    private const val TAG = "ToBusAlertScheduler"
    const val JOB_ID = 42_017
    private const val PERIODIC_INTERVAL_MS = 30L * 60L * 1_000L
    private const val FLEX_INTERVAL_MS = 10L * 60L * 1_000L

    fun isJobScheduled(context: Context): Boolean {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return false
        return scheduler.getPendingJob(JOB_ID) != null
    }

    fun syncSchedule(
        context: Context,
        enabled: Boolean,
        hasFavoriteLines: Boolean,
    ) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val shouldSchedule = enabled && hasFavoriteLines
        if (!shouldSchedule) {
            scheduler.cancel(JOB_ID)
            return
        }
        val alreadyScheduled = scheduler.getPendingJob(JOB_ID) != null
        if (alreadyScheduled) return

        val component = ComponentName(context, FavoriteAlertsJobService::class.java)
        val jobInfo = JobInfo.Builder(JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(PERIODIC_INTERVAL_MS, FLEX_INTERVAL_MS)
            .setPersisted(true)
            .build()
        val result = runCatching { scheduler.schedule(jobInfo) }.getOrDefault(JobScheduler.RESULT_FAILURE)
        if (result != JobScheduler.RESULT_SUCCESS) {
            Log.w(TAG, "Scheduling FavoriteAlertsJobService non riuscito (code=$result)")
        }
    }
}
