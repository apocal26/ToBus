package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.model.DataSourceMode
import com.example.data.model.FavoriteStop
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.LivePollInterval
import com.example.data.model.MapStyle
import com.example.data.model.RefreshInterval
import com.example.data.model.WidgetCameraMode
import com.example.data.model.shortTurinPlaceName
import com.example.ui.glass.AccentIconBadge
import com.example.ui.glass.DirectionColorBadge
import com.example.ui.glass.GlassBand
import com.example.ui.glass.GlassCard
import com.example.ui.glass.GlassCircleIconButton
import com.example.ui.glass.LiquidGlassSwitch
import com.example.ui.glass.SlidingGlassSegmentedControl
import com.example.ui.glass.glassInteractive
import com.example.ui.icons.ToBusIcons
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentEmerald
import com.example.ui.theme.AccentGold
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.AccentRed
import com.example.ui.theme.GlassBandBorder
import com.example.ui.theme.GlassPillBorder
import com.example.ui.theme.GlassPillFill
import com.example.ui.theme.GlassRowActiveBorder
import com.example.ui.theme.GlassRowActiveFill
import com.example.ui.theme.GlassRowBorder
import com.example.ui.theme.GlassRowFill
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextHero
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import com.example.ui.viewmodel.ToBusUiState

@Composable
internal fun WidgetControlsColumn(
    uiState: ToBusUiState,
    onOpenLinePicker: () -> Unit,
    onSelectMapStyle: (MapStyle) -> Unit,
    onSelectCameraMode: (WidgetCameraMode) -> Unit,
    onCaptureCurrentMapArea: () -> Unit,
    onToggleVehicleLabels: (Boolean) -> Unit,
    onToggleRouteLine: (Boolean) -> Unit,
    onToggleColorByDirection: (Boolean) -> Unit,
    onToggleHideInactiveInWidget: (Boolean) -> Unit,
    onToggleHideInactiveOnMap: (Boolean) -> Unit,
    onToggleShowServiceAlerts: (Boolean) -> Unit,
    onToggleNotifyFavoriteLineAlerts: (Boolean) -> Unit,
    onToggleKeepScreenOn: (Boolean) -> Unit,
    onSelectDataSourceMode: (DataSourceMode) -> Unit,
    onSelectLivePollInterval: (LivePollInterval) -> Unit,
    onSelectRefreshInterval: (RefreshInterval) -> Unit,
    onOpenFavoriteStop: (FavoriteStop) -> Unit,
    onRemoveFavoriteStop: (String) -> Unit,
    onCheckUpdates: () -> Unit,
    onEditGithubRepo: () -> Unit,
    onClearAppCaches: () -> Unit,
    onOpenHiddenDebugMenu: () -> Unit = {},
) {
    val settings = uiState.settings

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // ═══════════════════════════════════════════════════════════════════════
        // 1. LINEA E FERMATE PREFERITE
        // ═══════════════════════════════════════════════════════════════════════
        SettingsSection(
            title = "LINEA E PREFERITI",
            icon = ToBusIcons.DirectionsBus,
            accent = AccentGold,
            trailingBadge = if (uiState.favoriteStops.isNotEmpty()) "${uiState.favoriteStops.size}/6 fermate" else null,
        ) {
            // Active line picker card
            val lineInt = remember { MutableInteractionSource() }
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassInteractive(lineInt, onClick = onOpenLinePicker)
                    .testTag("studio_change_line_row"),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AccentIconBadge(
                        icon = ToBusIcons.DirectionsBus,
                        accent = AccentGold,
                        size = 44.dp,
                        iconSize = 22.dp,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (settings.hasSelectedLine) {
                                "Linea ${settings.selectedLine}"
                            } else {
                                "Nessuna linea selezionata"
                            },
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                        )
                        val spatialById = remember(uiState.lineVehicles, uiState.routePatterns) {
                            uiState.lineVehicles.associate { bus ->
                                bus.vehicleId to GttFleetAnalyzer.assessSpatial(bus, uiState.routePatterns)
                            }
                        }
                        val activeReliableCount = remember(uiState.lineVehicles, spatialById, uiState.nowMillis) {
                            uiState.lineVehicles.count { bus ->
                                val spatial = spatialById[bus.vehicleId]
                                    ?: GttFleetAnalyzer.assessSpatial(bus, uiState.routePatterns)
                                GttFleetAnalyzer.assessWithSpatial(bus, spatial, uiState.nowMillis).isReliableLive
                            }
                        }
                        Text(
                            text = when {
                                !settings.hasSelectedLine -> "Scegli una linea per iniziare"
                                com.example.data.network.GtfsRtProtobufParser.isNonLiveTrackedLine(settings.selectedLine) &&
                                    uiState.lineVehicles.isEmpty() ->
                                    "La linea ${settings.selectedLine} non viene tracciata in tempo reale"
                                uiState.lineVehicles.isEmpty() -> "Nessun mezzo attivo ora"
                                activeReliableCount == 0 -> "${uiState.lineVehicles.size} in deposito o fermi · ${uiState.onlineSourcesCount} fonti"
                                activeReliableCount == 1 -> "1 pullman attivo · ${uiState.onlineSourcesCount} fonti"
                                else -> "$activeReliableCount pullman attivi · ${uiState.onlineSourcesCount} fonti"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSubtitle,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(GlassPillFill, RoundedCornerShape(999.dp))
                            .border(1.dp, GlassPillBorder, RoundedCornerShape(999.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = if (settings.hasSelectedLine) "Cambia" else "Scegli",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = GlassBandBorder)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Fermate preferite",
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                )
                if (uiState.favoriteStops.isNotEmpty()) {
                    Text(
                        text = "${uiState.favoriteStops.size}/6",
                        style = MaterialTheme.typography.labelMedium,
                        color = AccentGold,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (uiState.favoriteStops.isEmpty()) {
                Text(
                    text = "Tocca una fermata sulla mappa e premi ★ per salvarla e usarla nel widget Fermate Live.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtitle,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.favoriteStops.forEach { fav ->
                        val openInt = remember(fav.stopId) { MutableInteractionSource() }
                        val shape = RoundedCornerShape(14.dp)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glassInteractive(openInt, enableHoverScale = false) { onOpenFavoriteStop(fav) }
                                .background(GlassRowFill, shape)
                                .border(1.dp, GlassRowBorder, shape)
                                .padding(start = 10.dp, top = 8.dp, end = 6.dp, bottom = 8.dp)
                                .testTag("studio_fav_stop_${fav.stopId}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AccentIconBadge(
                                icon = Icons.Rounded.Place,
                                accent = AccentGold,
                                size = 34.dp,
                                iconSize = 17.dp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = shortTurinPlaceName(fav.name),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val favAccent = if (settings.colorByDirection && fav.directionId == 1) AccentCyan else AccentGold
                                val favTerminus = fav.terminusName?.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = "Linea ${fav.lineCode}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSubtitle,
                                    )
                                    if (favTerminus != null) {
                                        DirectionColorBadge(
                                            label = favTerminus,
                                            accent = favAccent,
                                            prefix = "→",
                                        )
                                    }
                                }
                            }
                            GlassCircleIconButton(
                                icon = Icons.Rounded.Close,
                                contentDescription = "Rimuovi fermata preferita",
                                onClick = { onRemoveFavoriteStop(fav.stopId) },
                                modifier = Modifier.testTag("remove_fav_stop_${fav.stopId}"),
                            )
                        }
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // 2. MAPPA (solo opzioni dedicate alla mappa interattiva)
        // ═══════════════════════════════════════════════════════════════════════
        SettingsSection(
            title = "MAPPA",
            icon = ToBusIcons.MapRounded,
            accent = AccentCyan,
        ) {
            GlassToggleRow(
                title = "Percorso linea",
                subtitle = "Mostra il tracciato GTT sotto ai mezzi",
                icon = ToBusIcons.MapRounded,
                iconAccent = AccentCyan,
                checked = settings.showRouteLine,
                onCheckedChange = onToggleRouteLine,
                testTag = "toggle_route_line",
            )
            SettingsRowDivider()
            GlassToggleRow(
                title = "Numero vettura sui pullman",
                subtitle = "Mostra la matricola GTT sulla mappa interattiva",
                icon = ToBusIcons.DirectionsBus,
                iconAccent = AccentCyan,
                checked = settings.showVehicleLabels,
                onCheckedChange = onToggleVehicleLabels,
                testTag = "toggle_vehicle_labels",
            )
            SettingsRowDivider()
            GlassToggleRow(
                title = "Nascondi mezzi fermi",
                subtitle = "Esclude dalla mappa bus in deposito o con GPS vecchio",
                icon = ToBusIcons.MyLocation,
                iconAccent = AccentCyan,
                checked = settings.hideInactiveOnMap,
                onCheckedChange = onToggleHideInactiveOnMap,
                testTag = "toggle_hide_inactive_map",
            )
            SettingsRowDivider()
            GlassToggleRow(
                title = "Schermo sempre acceso",
                subtitle = "Evita lo spegnimento mentre aspetti il pullman",
                icon = ToBusIcons.AutoAwesome,
                iconAccent = AccentCyan,
                checked = settings.keepScreenOn,
                onCheckedChange = onToggleKeepScreenOn,
                testTag = "toggle_keep_screen_on",
            )
        }

        // ═══════════════════════════════════════════════════════════════════════
        // 3. ASPETTO E TEMA (stile basemap e colori direzione)
        // ═══════════════════════════════════════════════════════════════════════
        SettingsSection(
            title = "ASPETTO E TEMA",
            icon = ToBusIcons.AutoAwesome,
            accent = AccentGold,
        ) {
            Text(
                text = "Stile mappa",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "Tema grafico della mappa nell'app e nei widget.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtitle,
            )
            Spacer(modifier = Modifier.height(10.dp))
            val styles = MapStyle.entries
            SlidingGlassSegmentedControl(
                items = styles,
                selectedIndex = styles.indexOf(settings.mapStyle),
                labelProvider = { it.labelIt },
                onSelect = { _, item -> onSelectMapStyle(item) },
                tagPrefix = "map_style",
            )

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = GlassBandBorder)
            Spacer(modifier = Modifier.height(4.dp))

            val dir0Short = uiState.terminusForDirection(0)?.let(::shortTurinPlaceName)
            val dir1Short = uiState.terminusForDirection(1)?.let(::shortTurinPlaceName)
            DirectionColorsToggleRow(
                dir0Name = dir0Short,
                dir1Name = dir1Short,
                checked = settings.colorByDirection,
                onCheckedChange = onToggleColorByDirection,
            )
        }

        // ═══════════════════════════════════════════════════════════════════════
        // 4. NOTIFICHE E AVVISI
        // ═══════════════════════════════════════════════════════════════════════
        SettingsSection(
            title = "NOTIFICHE E AVVISI",
            icon = ToBusIcons.NotificationsActive,
            accent = AccentOrange,
            trailingBadge = if (settings.notifyFavoriteLineAlerts) "Attive" else null,
        ) {
            val favLinesPreview = remember(uiState.favoriteLines) {
                if (uiState.favoriteLines.isEmpty()) {
                    "Aggiungi almeno una linea ai preferiti (★) per ricevere notifiche"
                } else {
                    val head = uiState.favoriteLines.take(5).joinToString(", ")
                    val suffix = if (uiState.favoriteLines.size > 5) "…" else ""
                    "Solo nuovi avvisi per $head$suffix (senza ripetizioni)"
                }
            }
            GlassToggleRow(
                title = "Notifiche linee preferite",
                subtitle = favLinesPreview,
                icon = ToBusIcons.NotificationsActive,
                iconAccent = AccentGold,
                checked = settings.notifyFavoriteLineAlerts,
                onCheckedChange = onToggleNotifyFavoriteLineAlerts,
                testTag = "toggle_notify_favorite_alerts",
            )
            SettingsRowDivider()
            GlassToggleRow(
                title = "Avvisi e deviazioni GTT",
                subtitle = "Mostra il pulsante avvisi in mappa e controlla le deviazioni",
                icon = ToBusIcons.WarningAmber,
                iconAccent = AccentOrange,
                checked = settings.showServiceAlerts,
                onCheckedChange = onToggleShowServiceAlerts,
                testTag = "toggle_service_alerts",
            )
        }

        // ═══════════════════════════════════════════════════════════════════════
        // 5. WIDGET HOME (inquadratura, filtri widget e frequenza aggiornamento)
        // ═══════════════════════════════════════════════════════════════════════
        SettingsSection(
            title = "WIDGET HOME",
            icon = ToBusIcons.Widgets,
            accent = AccentCyan,
        ) {
            Text(
                text = "Inquadratura mappa",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetCameraMode.entries.forEach { mode ->
                    val isSelected = settings.cameraMode == mode
                    val interaction = remember { MutableInteractionSource() }
                    val shape = RoundedCornerShape(14.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glassInteractive(interaction) { onSelectCameraMode(mode) }
                            .background(if (isSelected) GlassRowActiveFill else GlassRowFill, shape)
                            .border(1.dp, if (isSelected) GlassRowActiveBorder else GlassRowBorder, shape)
                            .padding(12.dp)
                            .testTag("camera_mode_${mode.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AccentIconBadge(
                            icon = if (mode == WidgetCameraMode.AUTO_FIT_BUSES) {
                                ToBusIcons.AutoAwesome
                            } else {
                                ToBusIcons.MyLocation
                            },
                            accent = if (isSelected) AccentCyan else TextSubtitle,
                            size = 36.dp,
                            iconSize = 18.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mode.labelIt,
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimary,
                            )
                            Text(
                                text = mode.descriptionIt,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSubtitle,
                            )
                        }
                    }
                }

                if (settings.cameraMode == WidgetCameraMode.FIXED_AREA) {
                    val capInt = remember { MutableInteractionSource() }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .glassInteractive(capInt, onClick = onCaptureCurrentMapArea)
                            .testTag("capture_map_area_button"),
                        shape = RoundedCornerShape(999.dp),
                        color = GlassPillFill,
                        border = BorderStroke(1.dp, GlassPillBorder),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = ToBusIcons.CropFree,
                                contentDescription = null,
                                tint = AccentGold,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Usa inquadratura attuale della Mappa",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextPrimary,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            HorizontalDivider(color = GlassBandBorder)
            Spacer(modifier = Modifier.height(4.dp))

            GlassToggleRow(
                title = "Nascondi mezzi fermi nel widget",
                subtitle = "Esclude dal widget bus in deposito o con GPS vecchio",
                icon = ToBusIcons.Widgets,
                iconAccent = AccentCyan,
                checked = settings.hideInactiveInWidget,
                onCheckedChange = onToggleHideInactiveInWidget,
                testTag = "toggle_hide_inactive_widget",
            )

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = GlassBandBorder)
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Aggiornamento automatico",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "Intervallo di aggiornamento in background dei widget sulla Home.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtitle,
            )
            Spacer(modifier = Modifier.height(8.dp))
            val intervals = RefreshInterval.entries
            SlidingGlassSegmentedControl(
                items = intervals,
                selectedIndex = intervals.indexOf(settings.refreshInterval),
                labelProvider = { it.labelIt },
                onSelect = { _, item -> onSelectRefreshInterval(item) },
                tagPrefix = "refresh_interval",
            )
        }

        // ═══════════════════════════════════════════════════════════════════════
        // 6. DATI LIVE E PRESTAZIONI
        // ═══════════════════════════════════════════════════════════════════════
        SettingsSection(
            title = "DATI E PRESTAZIONI",
            icon = ToBusIcons.Sensors,
            accent = AccentEmerald,
            trailingBadge = if (settings.dataSourceMode.isFixed) "Fissa" else "Auto",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Fonte dati live",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Text(
                        text = if (settings.dataSourceMode.isFixed) {
                            "Fonte fissa · nessun fallback automatico"
                        } else {
                            "Multi-fonte intelligente con fallback"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtitle,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val modes = DataSourceMode.entries
            SlidingGlassSegmentedControl(
                items = modes,
                selectedIndex = modes.indexOf(settings.dataSourceMode).coerceAtLeast(0),
                labelProvider = { it.shortLabelIt },
                onSelect = { _, item -> onSelectDataSourceMode(item) },
                tagPrefix = "data_source_mode",
            )
            Spacer(modifier = Modifier.height(10.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassRowFill, RoundedCornerShape(14.dp))
                    .border(1.dp, GlassRowBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = settings.dataSourceMode.descriptionIt,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextBody,
                )
                val vehicleStatuses = remember(uiState.sourceStatuses) {
                    uiState.sourceStatuses.filter {
                        it.id == "mato_live" || it.id == "gtt_official" || it.id == "busone_proxy"
                    }
                }
                if (vehicleStatuses.isNotEmpty()) {
                    HorizontalDivider(color = GlassBandBorder)
                    vehicleStatuses.forEach { st ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(
                                        color = if (st.isOnline) AccentEmerald else AccentRed,
                                        shape = CircleShape,
                                    ),
                            )
                            Text(
                                text = st.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = st.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSubtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = GlassBandBorder)
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AccentIconBadge(
                    icon = ToBusIcons.BatterySaver,
                    accent = AccentEmerald,
                    size = 34.dp,
                    iconSize = 17.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Frequenza mappa live",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Text(
                        text = "Bilancia reattività in app e autonomia della batteria.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtitle,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            val liveIntervals = LivePollInterval.entries
            SlidingGlassSegmentedControl(
                items = liveIntervals,
                selectedIndex = liveIntervals.indexOf(settings.livePollInterval).coerceAtLeast(0),
                labelProvider = { it.labelIt },
                onSelect = { _, item -> onSelectLivePollInterval(item) },
                tagPrefix = "live_poll_interval",
            )
        }

        // ═══════════════════════════════════════════════════════════════════════
        // 7. APP E MANUTENZIONE (aggiornamenti, repo GitHub, cache & debug nascosto)
        // ═══════════════════════════════════════════════════════════════════════
        SettingsSection(
            title = "APP E MANUTENZIONE",
            icon = ToBusIcons.SystemUpdate,
            accent = TextSubtitle,
            trailingBadge = "v${BuildConfig.VERSION_NAME}",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccentIconBadge(
                    icon = ToBusIcons.SystemUpdate,
                    accent = AccentCyan,
                    size = 38.dp,
                    iconSize = 19.dp,
                )
                var versionTapCount by remember { mutableIntStateOf(0) }
                var lastVersionTapMillis by remember { mutableLongStateOf(0L) }
                val silentVersionInteraction = remember { MutableInteractionSource() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = silentVersionInteraction,
                            indication = null,
                        ) {
                            val now = System.currentTimeMillis()
                            val nextCount = if (now - lastVersionTapMillis <= 1_800L) versionTapCount + 1 else 1
                            lastVersionTapMillis = now
                            if (nextCount >= 7) {
                                versionTapCount = 0
                                onOpenHiddenDebugMenu()
                            } else {
                                versionTapCount = nextCount
                            }
                        },
                ) {
                    Text(
                        text = "Aggiornamenti app",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Text(
                        text = if (uiState.githubRepoSlug.isNotBlank()) {
                            "v${BuildConfig.VERSION_NAME} · ${uiState.githubRepoSlug}"
                        } else {
                            "v${BuildConfig.VERSION_NAME} · Controllo quotidiano su GitHub"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val checkInt = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .widthIn(min = 48.dp)
                        .heightIn(min = 48.dp)
                        .glassInteractive(checkInt, enableHoverScale = false, onClick = onCheckUpdates)
                        .background(GlassPillFill, RoundedCornerShape(999.dp))
                        .border(1.dp, GlassPillBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("check_updates_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            uiState.isCheckingUpdate -> "Controllo…"
                            uiState.githubRepoSlug.isBlank() -> "Configura"
                            else -> "Controlla"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                    )
                }
            }
            if (uiState.githubRepoSlug.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                val editInt = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .glassInteractive(editInt, enableHoverScale = false, onClick = onEditGithubRepo)
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .testTag("edit_github_repo_button"),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "Cambia repository GitHub",
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentCyan,
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = GlassBandBorder)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccentIconBadge(
                    icon = Icons.Rounded.Delete,
                    accent = AccentGold,
                    size = 38.dp,
                    iconSize = 19.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cache percorsi e linee",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                    )
                    Text(
                        text = "Ricarica tracciati e fermate GTT aggiornati",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtitle,
                    )
                }
                val clearInt = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .widthIn(min = 48.dp)
                        .heightIn(min = 48.dp)
                        .glassInteractive(clearInt, enableHoverScale = false, onClick = onClearAppCaches)
                        .background(GlassPillFill, RoundedCornerShape(999.dp))
                        .border(1.dp, GlassPillBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("clear_cache_button"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Svuota",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextPrimary,
                    )
                }
            }
        }
    }
}

/**
 * Distinct visual chapter container with an exterior eyebrow category header,
 * accent micro-badge, fading hairline rule, and an enclosed Liquid Glass band.
 */
@Composable
private fun SettingsSection(
    title: String,
    icon: ImageVector,
    accent: Color,
    trailingBadge: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(accent.copy(alpha = 0.16f), CircleShape)
                    .border(1.dp, accent.copy(alpha = 0.34f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.0.sp,
                ),
                color = if (accent == TextSubtitle) TextPrimary else accent,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                accent.copy(alpha = 0.28f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            if (trailingBadge != null) {
                Box(
                    modifier = Modifier
                        .background(accent.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                        .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = trailingBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (accent == TextSubtitle) TextBody else accent,
                        maxLines = 1,
                    )
                }
            }
        }

        GlassBand(content = content)
    }
}

@Composable
private fun SettingsRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 46.dp),
        color = GlassBandBorder,
    )
}

@Composable
private fun GlassToggleRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconAccent: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    val interaction = remember { MutableInteractionSource() }
    val activeAccent = if (checked) iconAccent else TextSubtitle
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassInteractive(interaction, enableHoverScale = false) { onCheckedChange(!checked) }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    color = if (checked) iconAccent.copy(alpha = 0.15f) else Color(0x12FFFFFF),
                    shape = RoundedCornerShape(11.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (checked) iconAccent.copy(alpha = 0.34f) else GlassRowBorder,
                    shape = RoundedCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = activeAccent,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
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
        Spacer(modifier = Modifier.width(12.dp))
        LiquidGlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectionColorsToggleRow(
    dir0Name: String?,
    dir1Name: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassInteractive(interaction, enableHoverScale = false) { onCheckedChange(!checked) }
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    color = if (checked) AccentGold.copy(alpha = 0.15f) else Color(0x12FFFFFF),
                    shape = RoundedCornerShape(11.dp),
                )
                .border(
                    width = 1.dp,
                    color = if (checked) AccentGold.copy(alpha = 0.34f) else GlassRowBorder,
                    shape = RoundedCornerShape(11.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                tint = if (checked) AccentGold else TextSubtitle,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Colori per direzione",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            if (dir0Name != null && dir1Name != null) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DirectionColorBadge(
                        label = dir0Name,
                        accent = AccentGold,
                    )
                    DirectionColorBadge(
                        label = dir1Name,
                        accent = AccentCyan,
                    )
                }
            } else {
                Text(
                    text = "Colori distinti per i due capolinea",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtitle,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        LiquidGlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag("toggle_color_direction"),
        )
    }
}
