package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.model.ServiceAlertCategory
import com.example.data.network.DiagCategory
import com.example.data.network.DiagSeverity
import com.example.data.network.DiagnosticLogEntry
import com.example.data.network.GtfsRtProtobufParser
import com.example.data.network.GttRealtimeRepository
import com.example.data.network.ToBusDiagnosticsLog
import com.example.notifications.FavoriteAlertsScheduler
import com.example.ui.glass.glassInteractive
import com.example.ui.icons.ToBusIcons
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentEmerald
import com.example.ui.theme.AccentGold
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.AccentRed
import com.example.ui.theme.GlassPillBorder
import com.example.ui.theme.GlassPillFill
import com.example.ui.theme.GlassRowBorder
import com.example.ui.theme.GlassRowFill
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import com.example.ui.viewmodel.ToBusUiState
import java.text.SimpleDateFormat
import java.util.Locale

internal data class DebugDiagnosticSnapshot(
    val permissionGranted: Boolean = false,
    val notifyEnabled: Boolean = false,
    val jobScheduled: Boolean = false,
    val baselineSeeded: Boolean = false,
    val seenFingerprintCount: Int = 0,
    val favoriteLines: List<String> = emptyList(),
    val runtime: GttRealtimeRepository.DebugRuntimeTelemetry? = null,
    val simulatedOutageEnabled: Boolean = false,
)

internal enum class DebugLogFilter(
    val labelIt: String,
    val categories: Set<DiagCategory>?,
) {
    ALL("Tutti", null),
    SOURCES("Fonti", setOf(DiagCategory.POLL, DiagCategory.SOURCE)),
    MQTT("MQTT", setOf(DiagCategory.MQTT)),
    ROUTES_STOPS("Percorsi/Fermate", setOf(DiagCategory.ROUTE, DiagCategory.STOP, DiagCategory.CACHE)),
    ALERTS("Avvisi", setOf(DiagCategory.ALERTS)),
    OUTAGE_NET("Disservizi/Rete", setOf(DiagCategory.OUTAGE, DiagCategory.NET, DiagCategory.DEBUG)),
}

@Composable
internal fun DebugStatusAndSourcesTab(
    uiState: ToBusUiState,
    snapshot: DebugDiagnosticSnapshot,
    activeLinesCount: Int,
    inactiveLinesCount: Int,
) {
    val runtime = snapshot.runtime
    val outageSnap = runtime?.outageSnapshot
    val conn = outageSnap?.connectivity
    val probe = outageSnap?.lastNeutralProbe
    val mqtt = runtime?.mqtt

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1. Network & Outage Detector
        DebugSectionCard(title = "Rete & Rilevatore Disservizi GTT") {
            DebugStatusRow(
                label = "Rete dispositivo",
                value = if (conn != null) {
                    "${conn.transportLabel} · ${if (conn.isHealthyForOutageCheck) "validata" else if (!conn.isConnected) "offline" else if (conn.isCaptivePortal) "captive" else "non validata"}"
                } else {
                    "Lettura…"
                },
                accent = if (conn?.isHealthyForOutageCheck == true) AccentEmerald else AccentOrange,
            )
            DebugStatusRow(
                label = "Probe internet neutrale",
                value = probe?.let { "${if (it.reachable) "OK" else "FAIL"} (${it.detail})" } ?: "Non ancora eseguito",
                accent = when {
                    probe == null -> TextSubtitle
                    probe.reachable -> AccentEmerald
                    else -> AccentRed
                },
            )
            val activeOutage = outageSnap?.activeOutage
            DebugStatusRow(
                label = "Stato disservizio GTT",
                value = when {
                    activeOutage == null -> "Regolare"
                    activeOutage.isSimulated -> "SIMULATO (${activeOutage.kind.id})"
                    else -> "ATTIVO · ${activeOutage.kind.labelIt}"
                },
                accent = when {
                    activeOutage == null -> AccentEmerald
                    activeOutage.isSimulated -> AccentGold
                    else -> AccentRed
                },
            )
            if (outageSnap != null) {
                DebugStatusRow(
                    label = "Contatori conferma",
                    value = "fail=${outageSnap.consecutiveTotalFailures} · stale=${outageSnap.consecutiveStalePolls} · frozen=${outageSnap.consecutiveFrozenPolls}",
                    accent = TextBody,
                )
                DebugDetailNote(text = outageSnap.lastEvaluationNote)
            }
        }

        // 2. Live Sources & Motion Tracker
        DebugSectionCard(title = "Fonti Live & Movimento GPS") {
            DebugStatusRow(
                label = "Modalità / Polling",
                value = "${uiState.settings.dataSourceMode.labelIt} · ogni ${uiState.settings.livePollInterval.labelIt}",
                accent = AccentCyan,
            )
            DebugStatusRow(
                label = "Flotta / Linea",
                value = "${uiState.totalCityVehicles} mezzi rete · L.${uiState.settings.selectedLine.ifBlank { "-" }} (${uiState.lineVehicles.size})",
                accent = TextPrimary,
            )
            if (uiState.sourceStatuses.isEmpty()) {
                DebugDetailNote(text = "Nessuna sorgente interrogata finora.")
            } else {
                uiState.sourceStatuses.forEach { src ->
                    DebugStatusRow(
                        label = src.name,
                        value = src.detail,
                        accent = if (src.isOnline) AccentEmerald else AccentRed,
                    )
                }
            }
            if (!runtime?.sourceMotion.isNullOrEmpty()) {
                runtime?.sourceMotion?.forEach { m ->
                    DebugDetailNote(
                        text = "${m.sourceName}: ${m.statusLabel} · nonDep=${m.observedNonDepot} · moto=${m.movingCount} · fermi=${m.stuckCount}",
                    )
                }
            }
        }

        // 3. MaTO MQTT WebSocket Stream
        DebugSectionCard(title = "Stream MaTO MQTT (5T /scre)") {
            if (mqtt == null) {
                DebugDetailNote(text = "Telemetria MQTT non disponibile.")
            } else {
                DebugStatusRow(
                    label = "Stato WS / Profilo",
                    value = "${if (mqtt.isConnected) "CONNESSO" else if (mqtt.isActiveDesired) "IN CONN." else "SPENTO"} · ${mqtt.profileLabel}",
                    accent = if (mqtt.isConnected) AccentEmerald else if (mqtt.isActiveDesired) AccentGold else TextSubtitle,
                )
                DebugStatusRow(
                    label = "Messaggi / Parse / Cache",
                    value = "raw=${mqtt.rawPublishCount} · ok=${mqtt.parsedVehicleCount} · cache=${mqtt.cachedVehicles}",
                    accent = TextPrimary,
                )
                DebugStatusRow(
                    label = "SUBACK / Riconnessioni",
                    value = "ok=${mqtt.subAckAccepted} · rej=${mqtt.subAckRejected} · retry=${mqtt.reconnectAttempts}",
                    accent = if (mqtt.subAckRejected > 0 && mqtt.subAckAccepted == 0) AccentRed else TextBody,
                )
                DebugStatusRow(
                    label = "MaTO GraphQL ep/query",
                    value = "endpoint #${runtime.matoGqlPreferredEndpointIdx} · query #${runtime.matoGqlPreferredQueryIdx}",
                    accent = TextBody,
                )
                mqtt.lastSampleTopic?.let { topic ->
                    DebugDetailNote(text = "Ultimo topic: $topic")
                }
                mqtt.lastError?.let { err ->
                    DebugDetailNote(text = "Ultimo errore MQTT: $err", color = AccentOrange)
                }
            }
        }

        // 4. Pipelines, Caches & Notifications
        DebugSectionCard(title = "Percorsi, Cache & Notifiche") {
            if (runtime != null) {
                DebugDetailNote(text = "Percorso: ${runtime.lastRouteLoadSummary}")
                DebugDetailNote(text = "Fermata: ${runtime.lastStopArrivalsSummary}")
                DebugStatusRow(
                    label = "Cache percorsi / GPS",
                    value = "RAM=${runtime.routeCacheRamCount} · Disco=${runtime.routeCacheDiskCount} · Track=${runtime.movementTrackedCount}",
                    accent = TextBody,
                )
                DebugDetailNote(text = runtime.staticStoreSummary)
            }
            DebugStatusRow(
                label = "Catalogo linee",
                value = "$activeLinesCount attive · $inactiveLinesCount a 0 mezzi",
                accent = AccentCyan,
            )
            DebugStatusRow(
                label = "Notifiche preferite",
                value = "${if (snapshot.permissionGranted) "Permesso OK" else "No perm."} · ${if (snapshot.notifyEnabled) "ON" else "OFF"} · Job ${if (snapshot.jobScheduled) "#${FavoriteAlertsScheduler.JOB_ID}" else "no"}",
                accent = if (snapshot.permissionGranted && snapshot.notifyEnabled) AccentEmerald else TextSubtitle,
            )
            DebugStatusRow(
                label = "Anti-ripetizione",
                value = "${snapshot.seenFingerprintCount} impronte · baseline ${if (snapshot.baselineSeeded) "OK" else "no"}",
                accent = TextBody,
            )
            DebugStatusRow(
                label = "Preferite (★)",
                value = if (snapshot.favoriteLines.isEmpty()) "Nessuna" else snapshot.favoriteLines.joinToString(", "),
                accent = if (snapshot.favoriteLines.isEmpty()) AccentGold else TextPrimary,
            )
        }
    }
}

@Composable
internal fun DebugTestsTab(
    targetTestLine: String,
    isRunningAction: Boolean,
    isSimulatedOutageEnabled: Boolean,
    onRunFullSourcesProbe: () -> Unit,
    onReconnectMqtt: () -> Unit,
    onToggleSimulatedOutage: () -> Unit,
    onResetTrackers: () -> Unit,
    onDiagnoseRouteFetch: () -> Unit,
    onRunLineNormalizationAudit: () -> Unit,
    onRefreshAllWidgets: () -> Unit,
    onSendTestNotification: () -> Unit,
    onRunAntiRepeatSelfTest: () -> Unit,
    onForceAlertsCheck: () -> Unit,
    onClearSeenAlerts: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DebugGroupHeader("FONTI DATI & DISSERVIZI")
        DebugActionButton(
            title = "Test completo fonti + probe internet",
            subtitle = "Interroga in parallelo rete neutrale, GTT, BusOne, TripUpdates, Avvisi e MaTO",
            icon = ToBusIcons.Sensors,
            accent = AccentCyan,
            enabled = !isRunningAction,
            testTag = "debug_full_sources_probe",
            onClick = onRunFullSourcesProbe,
        )
        DebugActionButton(
            title = "Riconnetti stream MaTO MQTT",
            subtitle = "Azzera il backoff e riapre il WebSocket 5T /scre sulla linea corrente",
            icon = Icons.Rounded.Refresh,
            accent = AccentEmerald,
            enabled = !isRunningAction,
            testTag = "debug_reconnect_mqtt",
            onClick = onReconnectMqtt,
        )
        DebugActionButton(
            title = if (isSimulatedOutageEnabled) "Disattiva simulazione disservizio GTT" else "Simula avviso disservizio GTT",
            subtitle = if (isSimulatedOutageEnabled) "Rimuove subito il banner di test dalla mappa" else "Mostra il banner di disservizio GTT sulla mappa per testarne la grafica",
            icon = ToBusIcons.WarningAmber,
            accent = if (isSimulatedOutageEnabled) AccentOrange else AccentGold,
            enabled = !isRunningAction,
            testTag = "debug_toggle_simulated_outage",
            onClick = onToggleSimulatedOutage,
        )
        DebugActionButton(
            title = "Reset stato GPS fermi e disservizi",
            subtitle = "Azzera SourceHealthTracker, cronologia movimento e contatori outage",
            icon = Icons.Rounded.Delete,
            accent = AccentOrange,
            enabled = !isRunningAction,
            testTag = "debug_reset_trackers",
            onClick = onResetTrackers,
        )

        DebugGroupHeader("PERCORSI, CATALOGO & WIDGET")
        DebugActionButton(
            title = "Forza ricarica percorso (Linea $targetTestLine)",
            subtitle = "Salta RAM/disco e registra provider, fallback, direzioni e fermate",
            icon = ToBusIcons.MapRounded,
            accent = AccentCyan,
            enabled = !isRunningAction,
            testTag = "debug_diagnose_route",
            onClick = onDiagnoseRouteFetch,
        )
        DebugActionButton(
            title = "Verifica normalizzazione & duplicati linee",
            subtitle = "Controlla 17/ vs 17B, +, M1/METRO e scansiona il catalogo attivo",
            icon = Icons.Rounded.CheckCircle,
            accent = AccentEmerald,
            enabled = !isRunningAction,
            testTag = "debug_audit_line_duplicates",
            onClick = onRunLineNormalizationAudit,
        )
        DebugActionButton(
            title = "Aggiorna tutti i widget Home ora",
            subtitle = "Forza il refresh di Mappa, Fermate e Avvisi preferite",
            icon = ToBusIcons.Widgets,
            accent = AccentGold,
            enabled = !isRunningAction,
            testTag = "debug_refresh_widgets",
            onClick = onRefreshAllWidgets,
        )

        DebugGroupHeader("NOTIFICHE & AVVISI GTT")
        DebugActionButton(
            title = "Invia notifica di test (Linea $targetTestLine)",
            subtitle = "Usa il canale reale e apre gli avvisi al tocco",
            icon = Icons.Rounded.Notifications,
            accent = AccentGold,
            enabled = !isRunningAction,
            testTag = "debug_send_test_notification",
            onClick = onSendTestNotification,
        )
        DebugActionButton(
            title = "Test anti-ripetizione (ID rigenerato)",
            subtitle = "Simula 3 giorni: nuovo → stesso testo con nuovo ID → ritocco",
            icon = Icons.Rounded.CheckCircle,
            accent = AccentEmerald,
            enabled = !isRunningAction,
            testTag = "debug_test_antirepeat",
            onClick = onRunAntiRepeatSelfTest,
        )
        DebugActionButton(
            title = "Forza controllo avvisi GTT ora",
            subtitle = "Scarica feed GTFS-RT, classifica le 5 categorie e avvia notifiche",
            icon = Icons.Rounded.Refresh,
            accent = AccentCyan,
            enabled = !isRunningAction,
            testTag = "debug_force_alerts_check",
            onClick = onForceAlertsCheck,
        )
        DebugActionButton(
            title = "Azzera impronte avvisi già visti",
            subtitle = "Permette di ricevere di nuovo gli avvisi reali al prossimo controllo",
            icon = Icons.Rounded.Delete,
            accent = AccentRed,
            enabled = !isRunningAction,
            testTag = "debug_reset_seen_alerts",
            onClick = onClearSeenAlerts,
        )
    }
}

@Composable
internal fun DebugLogViewerTab(
    entries: List<DiagnosticLogEntry>,
    selectedFilter: DebugLogFilter,
    onSelectFilter: (DebugLogFilter) -> Unit,
    onCopyReport: () -> Unit,
    onRefreshLogs: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.ITALY) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Filter chips row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DebugLogFilter.entries.forEach { filter ->
                val selected = filter == selectedFilter
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .glassInteractive(interaction, enableHoverScale = false) {
                            onSelectFilter(filter)
                        }
                        .background(
                            if (selected) AccentCyan.copy(alpha = 0.22f) else GlassPillFill,
                            RoundedCornerShape(999.dp),
                        )
                        .border(
                            1.dp,
                            if (selected) AccentCyan.copy(alpha = 0.65f) else GlassPillBorder,
                            RoundedCornerShape(999.dp),
                        )
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = filter.labelIt,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) AccentCyan else TextBody,
                    )
                }
            }
        }

        // Toolbar: Copy full report + Refresh + Clear
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DebugSmallActionPill(
                label = "Copia report",
                icon = ToBusIcons.ContentCopy,
                accent = AccentGold,
                modifier = Modifier
                    .weight(1f)
                    .testTag("debug_copy_report_button"),
                onClick = onCopyReport,
            )
            DebugSmallActionPill(
                label = "Aggiorna",
                icon = Icons.Rounded.Refresh,
                accent = AccentCyan,
                modifier = Modifier.testTag("debug_refresh_logs_button"),
                onClick = onRefreshLogs,
            )
            DebugSmallActionPill(
                label = "Pulisci",
                icon = Icons.Rounded.Delete,
                accent = AccentRed,
                modifier = Modifier.testTag("debug_clear_logs_button"),
                onClick = onClearLogs,
            )
        }

        // Event log feed
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xD6080C15), RoundedCornerShape(14.dp))
                .border(1.dp, GlassPillBorder, RoundedCornerShape(14.dp))
                .padding(10.dp)
                .testTag("debug_event_log_list"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (entries.isEmpty()) {
                Text(
                    text = "Nessun evento registrato per questo filtro. Avvia un test o attendi il prossimo poll.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    color = TextSubtitle,
                )
            } else {
                entries.take(80).forEach { entry ->
                    val severityColor = when (entry.severity) {
                        DiagSeverity.OK -> AccentEmerald
                        DiagSeverity.WARN -> AccentGold
                        DiagSeverity.ERROR -> AccentRed
                        DiagSeverity.INFO -> AccentCyan
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = "[${timeFmt.format(entry.timestampMillis)}] ${entry.severity.symbol} ${entry.category.tag} · ${entry.message}",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                            ),
                            color = severityColor,
                        )
                        if (!entry.detail.isNullOrBlank()) {
                            Text(
                                text = "   ${entry.detail}",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 13.5.sp,
                                ),
                                color = TextSubtitle,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun copyFullDiagnosticReportToClipboard(
    context: Context,
    uiState: ToBusUiState,
    snapshot: DebugDiagnosticSnapshot,
    filter: DebugLogFilter,
): Int {
    val runtime = snapshot.runtime
    val outage = runtime?.outageSnapshot
    val mqtt = runtime?.mqtt
    val header = buildList {
        add("App: ToBus v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        add("Linea attiva: ${uiState.settings.selectedLine.ifBlank { "-" }} (${uiState.lineVehicles.size} mezzi) · Flotta rete: ${uiState.totalCityVehicles} · Modalità: ${uiState.settings.dataSourceMode.id} · Poll: ${uiState.settings.livePollInterval.seconds}s")
        add("Catalogo: ${uiState.allActiveLines.count { it.activeVehicleCount > 0 }} attive, ${uiState.allActiveLines.count { it.activeVehicleCount == 0 }} inattive · Preferite: ${snapshot.favoriteLines.joinToString(",").ifBlank { "nessuna" }}")
        if (outage != null) {
            add("Rete: ${outage.connectivity.transportLabel} (conn=${outage.connectivity.isConnected}, valid=${outage.connectivity.isValidated}, captive=${outage.connectivity.isCaptivePortal})")
            add("Probe neutrale: ${outage.lastNeutralProbe?.let { "${if (it.reachable) "OK" else "FAIL"} ${it.detail}" } ?: "n/d"}")
            add("Outage: ${outage.activeOutage?.let { "${it.kind.id} (sim=${it.isSimulated})" } ?: "nessuno"} · fail=${outage.consecutiveTotalFailures} stale=${outage.consecutiveStalePolls} frozen=${outage.consecutiveFrozenPolls} · ${outage.lastEvaluationNote}")
        }
        if (mqtt != null) {
            add("MaTO MQTT: conn=${mqtt.isConnected} active=${mqtt.isActiveDesired} profile=${mqtt.profileLabel} line=${mqtt.focusedLine} raw=${mqtt.rawPublishCount} parsed=${mqtt.parsedVehicleCount} cache=${mqtt.cachedVehicles} subOk=${mqtt.subAckAccepted} subRej=${mqtt.subAckRejected} retry=${mqtt.reconnectAttempts} topic=${mqtt.lastSampleTopic ?: "-"} err=${mqtt.lastError ?: "-"}")
        }
        if (uiState.sourceStatuses.isNotEmpty()) {
            add("Fonti: " + uiState.sourceStatuses.joinToString(" | ") { "${it.name}=${if (it.isOnline) "ON" else "OFF"}(${it.detail})" })
        }
        if (runtime != null) {
            if (runtime.sourceMotion.isNotEmpty()) {
                add("Motion: " + runtime.sourceMotion.joinToString(" | ") { "${it.sourceName}:${it.statusLabel} nonDep=${it.observedNonDepot} mov=${it.movingCount} stuck=${it.stuckCount}" })
            }
            add("Percorso: ${runtime.lastRouteLoadSummary} · Fermata: ${runtime.lastStopArrivalsSummary}")
            add("Cache: RAM=${runtime.routeCacheRamCount} Disco=${runtime.routeCacheDiskCount} Track=${runtime.movementTrackedCount} TU=${runtime.tripUpdatesCount} · ${runtime.staticStoreSummary}")
        }
    }
    val reportText = ToBusDiagnosticsLog.formatReport(
        headerLines = header,
        categories = filter.categories,
        maxEntries = 90,
    )
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("ToBus Diagnostic Report", reportText))
    return reportText.length
}

internal fun runLineNormalizationAndDuplicateAudit(uiState: ToBusUiState): String {
    val checks = listOf(
        "17/" to "17B",
        "17B" to "17B",
        "17 B" to "17B",
        "17B U" to "17B",
        "17+" to "17+",
        "04" to "4",
        "M1" to "METRO",
        "METRO" to "METRO",
        "M1S" to "M1S",
    )
    val failedRules = checks.mapNotNull { (input, expected) ->
        val actual = GtfsRtProtobufParser.normalizeGttLineCode(input)
        if (actual == expected) null else "$input→$actual(atteso $expected)"
    }
    val seen = HashMap<String, MutableList<String>>()
    uiState.allActiveLines.forEach { summary ->
        val key = GtfsRtProtobufParser.normalizeGttLineCode(summary.lineCode)
        seen.getOrPut(key) { mutableListOf() }.add(summary.lineCode)
    }
    val duplicates = seen.filterValues { it.size > 1 }
    val msg = buildString {
        append("Regole normalizzazione: ${checks.size - failedRules.size}/${checks.size} OK")
        if (failedRules.isNotEmpty()) append(" [FAIL: ${failedRules.joinToString()}]")
        append(" · Catalogo (${uiState.allActiveLines.size} linee): ")
        if (duplicates.isEmpty()) {
            append("0 duplicati rilevati")
        } else {
            append("${duplicates.size} duplicati: ${duplicates.entries.take(5).joinToString { "${it.key}=${it.value}" }}")
        }
    }
    ToBusDiagnosticsLog.record(
        category = DiagCategory.DEBUG,
        message = "Audit normalizzazione & duplicati",
        detail = msg,
        severity = if (failedRules.isEmpty() && duplicates.isEmpty()) DiagSeverity.OK else DiagSeverity.WARN,
    )
    return msg
}

internal fun summarizeAlertCategories(alerts: List<com.example.data.model.ServiceAlertInfo>): String {
    if (alerts.isEmpty()) return "0 categorie"
    val counts = alerts.groupingBy { it.classifyCategory() }.eachCount()
    return ServiceAlertCategory.entries
        .mapNotNull { cat -> counts[cat]?.let { "${cat.label}:$it" } }
        .joinToString(" · ")
}

@Composable
private fun DebugSectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassRowFill, RoundedCornerShape(16.dp))
            .border(1.dp, GlassRowBorder, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title.uppercase(Locale.ITALY),
            style = MaterialTheme.typography.labelSmall,
            color = AccentGold,
        )
        content()
    }
}

@Composable
private fun DebugGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = TextSubtitle,
        modifier = Modifier.padding(top = 4.dp, start = 2.dp),
    )
}

@Composable
private fun DebugDetailNote(
    text: String,
    color: Color = TextSubtitle,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
        ),
        color = color,
    )
}

@Composable
private fun DebugStatusRow(
    label: String,
    value: String,
    accent: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSubtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DebugSmallActionPill(
    label: String,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .glassInteractive(interaction, enableHoverScale = false, onClick = onClick)
            .background(GlassPillFill, RoundedCornerShape(14.dp))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = accent,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
internal fun DebugActionButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .alpha(if (enabled) 1f else 0.55f)
            .glassInteractive(interaction, enableHoverScale = false) {
                if (enabled) onClick()
            }
            .background(GlassPillFill, RoundedCornerShape(16.dp))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtitle,
            )
        }
    }
}
