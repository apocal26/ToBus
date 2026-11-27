package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.network.DiagCategory
import com.example.data.network.DiagSeverity
import com.example.data.network.DiagnosticLogEntry
import com.example.data.network.GttRealtimeRepository
import com.example.data.network.ToBusDiagnosticsLog
import com.example.data.preferences.ToBusPreferences
import com.example.notifications.FavoriteAlertNotifier
import com.example.notifications.FavoriteAlertsScheduler
import com.example.ui.glass.GlassCircleIconButton
import com.example.ui.glass.GlassModalDialog
import com.example.ui.glass.glassInteractive
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentGold
import com.example.ui.theme.GlassBandBorder
import com.example.ui.theme.GlassPillBorder
import com.example.ui.theme.GlassPillFill
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextHero
import com.example.ui.theme.TextPrimary
import com.example.ui.viewmodel.ToBusUiState
import com.example.widget.BusMapWidgetProvider
import com.example.widget.FavoriteAlertsWidgetProvider
import com.example.widget.StopArrivalsWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class DebugMenuTab(val labelIt: String, val tag: String) {
    STATUS("Stato & Fonti", "debug_tab_status"),
    TESTS("Test", "debug_tab_tests"),
    LOGS("Log", "debug_tab_logs"),
}

/**
 * Hidden diagnostics and test console unlocked via 7 rapid taps on the app version label inside
 * Settings. Exposes live source/MQTT/route/outage telemetry, comprehensive interactive test
 * actions, and a filterable diagnostic event log with one-tap clipboard report export.
 */
@Composable
fun HiddenDebugMenuDialog(
    uiState: ToBusUiState,
    onDebugStateChanged: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(DebugMenuTab.STATUS) }
    var selectedLogFilter by remember { mutableStateOf(DebugLogFilter.ALL) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf(DebugDiagnosticSnapshot()) }
    var logEntries by remember { mutableStateOf<List<DiagnosticLogEntry>>(emptyList()) }
    var isRunningAction by remember { mutableStateOf(false) }
    var lastActionOutput by remember {
        mutableStateOf("Pronto. Apri Stato & Fonti, esegui Test o copia il Report dalla scheda Log.")
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.ITALY) }
    fun recordConsoleOutput(message: String) {
        val stamp = timeFormat.format(Date())
        lastActionOutput = "[$stamp] $message"
    }

    LaunchedEffect(
        refreshTick,
        selectedTab,
        selectedLogFilter,
        uiState.favoriteLines,
        uiState.settings.notifyFavoriteLineAlerts,
        uiState.lastUpdatedMillis,
        uiState.gttDataOutage,
    ) {
        snapshot = withContext(Dispatchers.IO) {
            val prefs = ToBusPreferences.getInstance(context)
            val repo = GttRealtimeRepository.getInstance(context)
            DebugDiagnosticSnapshot(
                permissionGranted = FavoriteAlertNotifier.hasNotificationPermission(context),
                notifyEnabled = prefs.currentSettings().notifyFavoriteLineAlerts,
                jobScheduled = FavoriteAlertsScheduler.isJobScheduled(context),
                baselineSeeded = prefs.isAlertBaselineSeeded(),
                seenFingerprintCount = prefs.getSeenAlertFingerprints().size,
                favoriteLines = prefs.currentFavoriteLines(),
                runtime = repo.getDebugRuntimeTelemetry(),
                simulatedOutageEnabled = repo.isSimulatedOutageEnabled(),
            )
        }
        logEntries = ToBusDiagnosticsLog.snapshot(categories = selectedLogFilter.categories)
    }

    val targetTestLine = remember(uiState.settings.selectedLine, uiState.favoriteLines) {
        uiState.settings.selectedLine.takeIf { it.isNotBlank() }
            ?: uiState.favoriteLines.firstOrNull()
            ?: "4"
    }
    val activeLinesCount = remember(uiState.allActiveLines) {
        uiState.allActiveLines.count { it.activeVehicleCount > 0 }
    }
    val inactiveLinesCount = remember(uiState.allActiveLines) {
        uiState.allActiveLines.count { it.activeVehicleCount == 0 }
    }

    GlassModalDialog(
        onDismissRequest = onDismiss,
        accentTint = AccentGold,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("hidden_debug_dialog"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Debug ToBus",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextHero,
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} · ${ToBusDiagnosticsLog.count()} eventi in memoria",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentGold,
                    )
                }
                GlassCircleIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = "Chiudi menu debug",
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_debug_dialog"),
                )
            }

            // Segmented tab selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DebugMenuTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    val interaction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .glassInteractive(interaction, enableHoverScale = false) {
                                selectedTab = tab
                                refreshTick++
                            }
                            .background(
                                if (selected) AccentGold.copy(alpha = 0.22f) else GlassPillFill,
                                RoundedCornerShape(12.dp),
                            )
                            .border(
                                1.dp,
                                if (selected) AccentGold.copy(alpha = 0.65f) else GlassPillBorder,
                                RoundedCornerShape(12.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 8.dp)
                            .testTag(tab.tag),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (tab == DebugMenuTab.LOGS) "${tab.labelIt} (${logEntries.size})" else tab.labelIt,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) AccentGold else TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            HorizontalDivider(color = GlassBandBorder)

            // Scrollable tab body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 390.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (selectedTab) {
                    DebugMenuTab.STATUS -> DebugStatusAndSourcesTab(
                        uiState = uiState,
                        snapshot = snapshot,
                        activeLinesCount = activeLinesCount,
                        inactiveLinesCount = inactiveLinesCount,
                    )

                    DebugMenuTab.TESTS -> DebugTestsTab(
                        targetTestLine = targetTestLine,
                        isRunningAction = isRunningAction,
                        isSimulatedOutageEnabled = snapshot.simulatedOutageEnabled,
                        onRunFullSourcesProbe = {
                            if (isRunningAction) return@DebugTestsTab
                            isRunningAction = true
                            recordConsoleOutput("Eseguo probe internet neutrale e test parallelo di tutte le fonti…")
                            scope.launch {
                                val report = GttRealtimeRepository.getInstance(context)
                                    .runFullSourcesProbeNow(targetTestLine)
                                recordConsoleOutput(report)
                                isRunningAction = false
                                refreshTick++
                            }
                        },
                        onReconnectMqtt = {
                            val msg = GttRealtimeRepository.getInstance(context)
                                .forceReconnectMatoMqtt(targetTestLine)
                            recordConsoleOutput(msg)
                            refreshTick++
                        },
                        onToggleSimulatedOutage = {
                            val report = GttRealtimeRepository.getInstance(context).toggleSimulatedOutage()
                            onDebugStateChanged(true)
                            recordConsoleOutput(
                                if (report != null && report.isSimulated) {
                                    "Simulazione disservizio GTT ATTIVA: chiudi il menu e torna in Mappa per vedere il banner."
                                } else {
                                    "Simulazione disservizio GTT disattivata."
                                },
                            )
                            refreshTick++
                        },
                        onResetTrackers = {
                            GttRealtimeRepository.getInstance(context).resetHealthAndOutageTrackers()
                            onDebugStateChanged(true)
                            recordConsoleOutput("Tracker GPS bloccati, movimento e rilevatore disservizi azzerati.")
                            refreshTick++
                        },
                        onDiagnoseRouteFetch = {
                            if (isRunningAction) return@DebugTestsTab
                            isRunningAction = true
                            recordConsoleOutput("Ricarico percorso L.$targetTestLine saltando cache RAM/disco…")
                            scope.launch {
                                val result = GttRealtimeRepository.getInstance(context)
                                    .diagnoseRouteFetch(targetTestLine)
                                recordConsoleOutput(result)
                                isRunningAction = false
                                refreshTick++
                            }
                        },
                        onRunLineNormalizationAudit = {
                            val audit = runLineNormalizationAndDuplicateAudit(uiState)
                            recordConsoleOutput(audit)
                            refreshTick++
                        },
                        onRefreshAllWidgets = {
                            scope.launch(Dispatchers.IO) {
                                BusMapWidgetProvider.requestUpdateAllWidgets(context)
                                StopArrivalsWidgetProvider.requestUpdateAll(context)
                                FavoriteAlertsWidgetProvider.requestUpdateAll(context)
                                ToBusDiagnosticsLog.record(
                                    category = DiagCategory.DEBUG,
                                    message = "Richiesto aggiornamento immediato di tutti i widget Home",
                                    severity = DiagSeverity.OK,
                                )
                            }
                            recordConsoleOutput("Aggiornamento inviato ai widget Mappa, Fermate e Avvisi.")
                            refreshTick++
                        },
                        onSendTestNotification = {
                            val (_, msg) = FavoriteAlertNotifier.sendDebugTestNotification(context, targetTestLine)
                            ToBusDiagnosticsLog.record(
                                category = DiagCategory.DEBUG,
                                message = "Notifica test L.$targetTestLine",
                                detail = msg,
                                severity = DiagSeverity.INFO,
                            )
                            recordConsoleOutput(msg)
                            refreshTick++
                        },
                        onRunAntiRepeatSelfTest = {
                            val report = FavoriteAlertNotifier.runDebugAntiRepeatSelfTest(targetTestLine)
                            ToBusDiagnosticsLog.record(
                                category = DiagCategory.DEBUG,
                                message = "Self-test anti-ripetizione",
                                detail = report,
                                severity = DiagSeverity.OK,
                            )
                            recordConsoleOutput(report)
                            refreshTick++
                        },
                        onForceAlertsCheck = {
                            if (isRunningAction) return@DebugTestsTab
                            isRunningAction = true
                            recordConsoleOutput("Scarico avvisi GTT live…")
                            scope.launch {
                                val summary = withContext(Dispatchers.IO) {
                                    val prefs = ToBusPreferences.getInstance(context)
                                    val favs = prefs.currentFavoriteLines()
                                    val settings = prefs.currentSettings()
                                    FavoriteAlertsScheduler.syncSchedule(
                                        context = context,
                                        enabled = settings.notifyFavoriteLineAlerts,
                                        hasFavoriteLines = favs.isNotEmpty(),
                                    )
                                    val alerts = runCatching {
                                        GttRealtimeRepository.getInstance(context).fetchServiceAlertsNow()
                                    }.getOrNull()
                                    if (alerts != null) {
                                        FavoriteAlertsWidgetProvider.syncWithAlerts(context, alerts)
                                    }
                                    when {
                                        alerts == null -> "Feed avvisi GTT non raggiungibile in questo momento."
                                        else -> {
                                            val breakdown = summarizeAlertCategories(alerts)
                                            when {
                                                !settings.notifyFavoriteLineAlerts ->
                                                    "Scaricati ${alerts.size} avvisi ($breakdown), ma il toggle notifiche è spento."
                                                favs.isEmpty() ->
                                                    "Scaricati ${alerts.size} avvisi ($breakdown), ma non hai linee preferite (★)."
                                                else -> {
                                                    val favSet = favs.map { it.uppercase(Locale.ROOT) }.toSet()
                                                    val matchingNow = alerts.count { a ->
                                                        a.isCurrentOrUpcoming() && a.affectedLines.any { it.uppercase(Locale.ROOT) in favSet }
                                                    }
                                                    val posted = FavoriteAlertNotifier.processFetchedAlerts(
                                                        context = context,
                                                        alerts = alerts,
                                                        postNotifications = true,
                                                    )
                                                    "Scaricati ${alerts.size} avvisi ($breakdown) · $matchingNow sulle preferite · $posted nuove notifiche."
                                                }
                                            }
                                        }
                                    }
                                }
                                recordConsoleOutput(summary)
                                isRunningAction = false
                                refreshTick++
                            }
                        },
                        onClearSeenAlerts = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    ToBusPreferences.getInstance(context)
                                        .clearSeenAlertFingerprints(keepBaselineSeeded = true)
                                    ToBusDiagnosticsLog.record(
                                        category = DiagCategory.DEBUG,
                                        message = "Azzerate impronte avvisi già visti",
                                        severity = DiagSeverity.INFO,
                                    )
                                }
                                recordConsoleOutput("Cronologia impronte azzerata (baseline mantenuta).")
                                refreshTick++
                            }
                        },
                    )

                    DebugMenuTab.LOGS -> DebugLogViewerTab(
                        entries = logEntries,
                        selectedFilter = selectedLogFilter,
                        onSelectFilter = { selectedLogFilter = it },
                        onCopyReport = {
                            val chars = copyFullDiagnosticReportToClipboard(
                                context = context,
                                uiState = uiState,
                                snapshot = snapshot,
                                filter = selectedLogFilter,
                            )
                            recordConsoleOutput("Report diagnostico completo copiato negli appunti ($chars caratteri).")
                        },
                        onRefreshLogs = {
                            refreshTick++
                            recordConsoleOutput("Log aggiornati (${ToBusDiagnosticsLog.count()} eventi).")
                        },
                        onClearLogs = {
                            ToBusDiagnosticsLog.clear()
                            refreshTick++
                            recordConsoleOutput("Buffer log svuotato.")
                        },
                    )
                }
            }

            // Compact live console output footer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC0A0E17), RoundedCornerShape(14.dp))
                    .border(1.dp, AccentCyan.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
                    .padding(10.dp)
                    .testTag("debug_console_output"),
            ) {
                Text(
                    text = lastActionOutput,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                    ),
                    color = TextBody,
                )
            }
        }
    }
}
