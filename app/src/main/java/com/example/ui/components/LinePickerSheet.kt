package com.example.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.LineSummary
import com.example.data.model.StopSearchEntry
import com.example.data.model.shortTurinPlaceName
import com.example.ui.glass.GlassCircleIconButton
import com.example.ui.glass.glassInteractive
import com.example.ui.glass.glassSpring
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentEmerald
import com.example.ui.theme.AccentGold
import com.example.ui.theme.DarkInkOnAccent
import com.example.ui.theme.GlassPillBorder
import com.example.ui.theme.GlassPillFill
import com.example.ui.theme.GlassRowActiveBorder
import com.example.ui.theme.GlassRowActiveFill
import com.example.ui.theme.GlassRowBorder
import com.example.ui.theme.GlassRowFill
import com.example.ui.theme.GlassSliderBorder
import com.example.ui.theme.GlassSliderBottom
import com.example.ui.theme.GlassSliderMid
import com.example.ui.theme.GlassSliderTop
import com.example.ui.theme.SmokedGlassBorder
import com.example.ui.theme.SmokedGlassFill
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextHero
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import com.example.ui.theme.TextTertiary
import com.example.ui.viewmodel.UserLocationState
import kotlin.math.roundToInt

private enum class PickerTab(val label: String) {
    LINES("Linee"),
    STOPS("Fermate"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinePickerSheet(
    selectedLine: String,
    activeLines: List<LineSummary>,
    stopSearchResults: List<StopSearchEntry>,
    isLoadingStopIndex: Boolean,
    userLocation: UserLocationState?,
    onSelectLine: (String) -> Unit,
    onSearchStops: (String) -> Unit,
    onSelectStop: (StopSearchEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var activeTab by remember { mutableStateOf(PickerTab.LINES) }
    var lineQuery by remember { mutableStateOf("") }
    var stopQuery by remember { mutableStateOf("") }

    LaunchedEffect(activeTab, stopQuery) {
        if (activeTab == PickerTab.STOPS) {
            onSearchStops(stopQuery)
        }
    }

    val cleanedLineQuery = lineQuery.trim().uppercase()
    val filteredLines = remember(activeLines, cleanedLineQuery) {
        if (cleanedLineQuery.isEmpty()) {
            activeLines
        } else {
            activeLines.filter { it.lineCode.contains(cleanedLineQuery, ignoreCase = true) }
        }
    }
    val exactMatchExists = filteredLines.any { it.lineCode.equals(cleanedLineQuery, ignoreCase = true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SmokedGlassFill,
        scrimColor = Color(0x99000000),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 6.dp)
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0x44FFFFFF)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = SmokedGlassBorder.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                )
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (activeTab == PickerTab.LINES) "Linea GTT" else "Cerca Fermata",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextHero,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (activeTab == PickerTab.LINES) {
                            if (activeLines.isNotEmpty()) {
                                "${activeLines.size} linee attive ora su Torino"
                            } else {
                                "Cerca o inserisci il numero della linea"
                            }
                        } else {
                            if (stopQuery.isBlank() && userLocation != null) {
                                "Paline più vicine con linee e direzione"
                            } else if (stopQuery.isBlank()) {
                                "Scrivi nome, #palina o anche la linea (es. Porta Nuova 12)"
                            } else {
                                "Ogni risultato mostra #palina, direzione e linee"
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSubtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GlassCircleIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_line_picker_button"),
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Sleek 2-segment Smoked Glass switcher (zero extra buttons on main map!)
            PickerSegmentedToggle(
                selected = activeTab,
                onSelect = { activeTab = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("picker_mode_segmented_toggle"),
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (activeTab == PickerTab.LINES) {
                OutlinedTextField(
                    value = lineQuery,
                    onValueChange = { if (it.length <= 10) lineQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("line_search_input"),
                    placeholder = {
                        Text(
                            text = "Cerca linea (es. 4, 15, 55, 68, SF2…)",
                            color = TextTertiary,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = TextSubtitle,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(999.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = GlassPillFill,
                        unfocusedContainerColor = GlassRowFill,
                        focusedBorderColor = Color(0x88FFFFFF),
                        unfocusedBorderColor = GlassPillBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextBody,
                        cursorColor = AccentGold,
                    ),
                )

                if (cleanedLineQuery.isNotEmpty() && !exactMatchExists) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val customInteraction = remember { MutableInteractionSource() }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .glassInteractive(customInteraction) {
                                onSelectLine(cleanedLineQuery)
                                onDismiss()
                            }
                            .testTag("select_custom_line_button"),
                        shape = RoundedCornerShape(999.dp),
                        color = AccentGold,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DirectionsBus,
                                contentDescription = null,
                                tint = DarkInkOnAccent,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Monitora linea $cleanedLineQuery",
                                style = MaterialTheme.typography.labelLarge,
                                color = DarkInkOnAccent,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (filteredLines.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (activeLines.isEmpty()) {
                                "Caricamento linee dai feed GTT…"
                            } else {
                                "Nessuna linea attiva per \"$cleanedLineQuery\""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSubtitle,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 94.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .testTag("active_lines_grid"),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 12.dp),
                    ) {
                        items(filteredLines, key = { it.lineCode }) { item ->
                            val isSelected = item.lineCode.equals(selectedLine, ignoreCase = true)
                            GlassLineTile(
                                summary = item,
                                isSelected = isSelected,
                                onClick = {
                                    onSelectLine(item.lineCode)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = stopQuery,
                    onValueChange = { if (it.length <= 42) stopQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("stop_search_input"),
                    placeholder = {
                        Text(
                            text = "Es. Porta Nuova 12, #317, Politecnico…",
                            color = TextTertiary,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Place,
                            contentDescription = null,
                            tint = AccentCyan,
                        )
                    },
                    trailingIcon = if (stopQuery.isNotEmpty()) {
                        {
                            val clearInteraction = remember { MutableInteractionSource() }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .glassInteractive(clearInteraction) { stopQuery = "" },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Cancella ricerca",
                                    tint = TextSubtitle,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(999.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = GlassPillFill,
                        unfocusedContainerColor = GlassRowFill,
                        focusedBorderColor = AccentCyan.copy(alpha = 0.72f),
                        unfocusedBorderColor = GlassPillBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextBody,
                        cursorColor = AccentCyan,
                    ),
                )

                Spacer(modifier = Modifier.height(14.dp))

                when {
                    isLoadingStopIndex && stopSearchResults.isEmpty() -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = AccentCyan,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Indicizzazione fermate GTT…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSubtitle,
                            )
                        }
                    }

                    stopSearchResults.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 36.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (stopQuery.isBlank()) {
                                    "Digita il nome o il numero di una palina"
                                } else {
                                    "Nessuna fermata trovata per \"${stopQuery.trim()}\""
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSubtitle,
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 360.dp)
                                .testTag("stop_search_results_list"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 12.dp),
                        ) {
                            items(stopSearchResults, key = { it.stopId }) { entry ->
                                val distMeters = userLocation?.let { loc ->
                                    GttFleetAnalyzer.haversineMeters(loc.lat, loc.lon, entry.lat, entry.lon).roundToInt()
                                }
                                GlassStopSearchRow(
                                    entry = entry,
                                    selectedLine = selectedLine,
                                    distanceMeters = distMeters,
                                    onClick = {
                                        onSelectStop(entry)
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerSegmentedToggle(
    selected: PickerTab,
    onSelect: (PickerTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = PickerTab.entries
    val selectedIdx = tabs.indexOf(selected).coerceAtLeast(0)
    Surface(
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(999.dp),
        color = GlassRowFill,
        border = BorderStroke(1.dp, GlassRowBorder),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
        ) {
            val slotW = maxWidth / tabs.size
            val indicatorX by animateDpAsState(
                targetValue = slotW * selectedIdx,
                animationSpec = glassSpring(),
                label = "picker_tab_slider_x",
            )
            Box(
                modifier = Modifier
                    .offset(x = indicatorX)
                    .width(slotW)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(GlassSliderTop, GlassSliderMid, GlassSliderBottom),
                        ),
                    )
                    .border(1.2.dp, GlassSliderBorder, RoundedCornerShape(999.dp)),
            )
            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    val isSelected = tab == selected
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .glassInteractive(interaction) { onSelect(tab) }
                            .testTag("picker_tab_${tab.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = if (tab == PickerTab.LINES) {
                                Icons.Rounded.DirectionsBus
                            } else {
                                Icons.Rounded.Place
                            },
                            contentDescription = null,
                            tint = if (isSelected) {
                                if (tab == PickerTab.LINES) AccentGold else AccentCyan
                            } else {
                                TextSubtitle
                            },
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) TextPrimary else TextSubtitle,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassStopSearchRow(
    entry: StopSearchEntry,
    selectedLine: String,
    distanceMeters: Int?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hasSelectedLine = remember(entry.lines, selectedLine) {
        entry.lines.any { it.equals(selectedLine, ignoreCase = true) }
    }
    val displayName = remember(entry.name) { shortTurinPlaceName(entry.name) }
    val headsignSummary = remember(entry.headsigns) {
        entry.headsigns.take(2).joinToString(" · ").takeIf { it.isNotBlank() }
    }
    val distLabel = remember(distanceMeters) {
        distanceMeters?.let { m ->
            if (m < 1000) "$m m" else String.format(java.util.Locale.ITALY, "%.1f km", m / 1000.0)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .glassInteractive(interaction, onClick = onClick)
            .testTag("stop_search_item_${entry.stopId}"),
        shape = RoundedCornerShape(16.dp),
        color = if (hasSelectedLine) GlassRowActiveFill else GlassRowFill,
        border = BorderStroke(
            width = 1.dp,
            color = if (hasSelectedLine) AccentGold.copy(alpha = 0.42f) else GlassRowBorder,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0x22FFFFFF))
                        .border(1.dp, GlassRowBorder, RoundedCornerShape(999.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "#${entry.stopCode}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextBody,
                    )
                }
                if (distLabel != null) {
                    Text(
                        text = distLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = AccentCyan,
                    )
                }
            }

            if (headsignSummary != null) {
                Text(
                    text = "→ $headsignSummary",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (entry.lines.isNotEmpty()) {
                val visibleLines = remember(entry.lines, selectedLine) {
                    val sorted = entry.lines.sortedByDescending { it.equals(selectedLine, ignoreCase = true) }
                    sorted.take(8)
                }
                val overflowCount = (entry.lines.size - visibleLines.size).coerceAtLeast(0)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(visibleLines, key = { it }) { lineCode ->
                        val isActiveLine = lineCode.equals(selectedLine, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isActiveLine) AccentGold else Color(0x1EFFFFFF),
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isActiveLine) AccentGold else GlassRowBorder,
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = lineCode,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActiveLine) DarkInkOnAccent else TextPrimary,
                            )
                        }
                    }
                    if (overflowCount > 0) {
                        item {
                            Text(
                                text = "+$overflowCount",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSubtitle,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassLineTile(
    summary: LineSummary,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .glassInteractive(interaction, onClick = onClick)
            .testTag("line_tile_${summary.lineCode}"),
        shape = shape,
        color = if (isSelected) GlassRowActiveFill else GlassRowFill,
        border = BorderStroke(
            width = if (isSelected) 1.2.dp else 1.dp,
            color = if (isSelected) GlassRowActiveBorder else GlassRowBorder,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = summary.lineCode,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isSelected) AccentGold else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isSelected) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = AccentGold,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(5.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(AccentEmerald),
                )
                Text(
                    text = if (summary.activeVehicleCount == 1) "1 mezzo" else "${summary.activeVehicleCount} mezzi",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSubtitle,
                )
            }
        }
    }
}
