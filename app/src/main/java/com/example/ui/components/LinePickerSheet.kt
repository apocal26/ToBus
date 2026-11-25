package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.example.ui.glass.liquidGlassRimBrush
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
import com.example.ui.theme.GlassSliderBottom
import com.example.ui.theme.GlassSliderMid
import com.example.ui.theme.GlassSliderTop
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextHero
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import com.example.ui.theme.TextTertiary
import com.example.ui.viewmodel.UserLocationState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SheetTopShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
// Fully opaque obsidian-slate sheet surface avoids cross-window / SurfaceFlinger alpha-blending overhead
// over the OpenGL MapView while keeping the crisp Liquid Glass top rim & frosted cards.
private val SheetOpaqueContainerColor = Color(0xFF0E111A)
private val SheetHeaderTopWash = Brush.verticalGradient(
    colors = listOf(Color(0x16FFFFFF), Color.Transparent),
)
private val SheetTopRimBrush = Brush.verticalGradient(
    colors = listOf(Color(0x38FFFFFF), Color(0x10FFFFFF), Color.Transparent),
)
private val PillShape = RoundedCornerShape(999.dp)
private val LineTileShape = RoundedCornerShape(14.dp)
private val StopRowShape = RoundedCornerShape(16.dp)
private val LineBadgeShape = RoundedCornerShape(8.dp)
private val FavoriteTileFill = Color(0xFF231D14)
private val FavoriteTileSelectedFill = Color(0xFF332814)
private val RegularTileFill = Color(0xFF171C28)
private val RegularTileSelectedFill = Color(0xFF23293A)
private val FavoriteTileBorder = Color(0x88FBBF24)
private val RegularTileBorder = Color(0x22FFFFFF)
private val PickerSliderBrush = Brush.verticalGradient(
    colors = listOf(GlassSliderTop, GlassSliderMid, GlassSliderBottom),
)
private val PickerSliderRimBrush = liquidGlassRimBrush(
    topAlpha = 0.46f,
    midAlpha = 0.14f,
    bottomAlpha = 0.24f,
)

private enum class PickerTab(val label: String) {
    LINES("Linee"),
    STOPS("Fermate"),
}

/**
 * In-window Line & Stop Picker sheet designed for locked 90/120fps scrolling:
 * - Lives directly inside the main Activity window (NO translucent ModalBottomSheet Dialog window).
 * - Uses an opaque deep-obsidian surface with glass rim & cards (zero GPU overdraw / SurfaceFlinger blending).
 * - Gives [LazyVerticalGrid] and [LazyColumn] direct `weight(1f)` ownership with ZERO nested-scroll
 *   or AnchoredDraggable gesture competition.
 * - Downward swipe-to-dismiss lives exclusively on the top header handle bar.
 */
@Composable
fun LinePickerSheet(
    selectedLine: String,
    activeLines: List<LineSummary>,
    favoriteLines: List<String> = emptyList(),
    stopSearchResults: List<StopSearchEntry>,
    isLoadingStopIndex: Boolean,
    userLocation: UserLocationState?,
    onSelectLine: (String) -> Unit,
    onToggleFavoriteLine: (String) -> Unit = {},
    onSearchStops: (String) -> Unit,
    onSelectStop: (StopSearchEntry) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(PickerTab.LINES) }
    var lineQuery by remember { mutableStateOf("") }
    var stopQuery by remember { mutableStateOf("") }

    // Freeze activeLines snapshot while the picker is open so background live telemetry never causes
    // grid recompositions mid-scroll; updates only if list was initially empty.
    var stableActiveLines by remember { mutableStateOf(activeLines) }
    LaunchedEffect(activeLines.size) {
        if (stableActiveLines.isEmpty() && activeLines.isNotEmpty()) {
            stableActiveLines = activeLines
        }
    }

    LaunchedEffect(activeTab, stopQuery) {
        if (activeTab == PickerTab.STOPS) {
            onSearchStops(stopQuery)
        }
    }

    val cleanedLineQuery = remember(lineQuery) { lineQuery.trim().uppercase() }
    val activeByCode = remember(stableActiveLines) {
        stableActiveLines.associateBy { it.lineCode.uppercase() }
    }
    val favoriteCodesNormalized = remember(favoriteLines) {
        favoriteLines.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
    }
    val favoriteSet = remember(favoriteCodesNormalized) { favoriteCodesNormalized.toSet() }
    val favoriteSummaries = remember(favoriteCodesNormalized, activeByCode, cleanedLineQuery) {
        favoriteCodesNormalized
            .filter { code -> cleanedLineQuery.isEmpty() || code.contains(cleanedLineQuery, ignoreCase = true) }
            .map { code ->
                activeByCode[code] ?: LineSummary(
                    lineCode = code,
                    rawRouteIds = setOf(code),
                    activeVehicleCount = 0,
                )
            }
    }
    val filteredLines = remember(stableActiveLines, cleanedLineQuery) {
        if (cleanedLineQuery.isEmpty()) {
            stableActiveLines
        } else {
            stableActiveLines.filter { it.lineCode.contains(cleanedLineQuery, ignoreCase = true) }
        }
    }
    val regularLines = remember(filteredLines, favoriteSet) {
        filteredLines.filterNot { favoriteSet.contains(it.lineCode.uppercase()) }
    }
    val exactMatchExists = remember(filteredLines, favoriteSummaries, cleanedLineQuery) {
        cleanedLineQuery.isNotEmpty() &&
            (filteredLines.any { it.lineCode.equals(cleanedLineQuery, ignoreCase = true) } ||
                favoriteSummaries.any { it.lineCode.equals(cleanedLineQuery, ignoreCase = true) })
    }

    val latestOnSelectLine by rememberUpdatedState(onSelectLine)
    val latestOnToggleFavoriteLine by rememberUpdatedState(onToggleFavoriteLine)
    val latestOnSelectStop by rememberUpdatedState(onSelectStop)
    val latestOnDismiss by rememberUpdatedState(onDismiss)

    val sheetScope = rememberCoroutineScope()
    val headerDragOffsetY = remember { Animatable(0f) }
    val dismissThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val linesListState = rememberLazyListState()
    val stopListState = rememberLazyListState()

    BackHandler(onBack = latestOnDismiss)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Clickable scrim behind sheet (absorbs map touches)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xA6000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = latestOnDismiss,
                ),
        )

        // Opaque dark-glass sheet body — NO outer .clip(SheetTopShape) so the GPU never
        // evaluates a rounded-rect path clip over the scrolling list every frame.
        Column(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 28.dp)
                .widthIn(max = 580.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .graphicsLayer { translationY = headerDragOffsetY.value }
                .background(SheetOpaqueContainerColor, SheetTopShape)
                .border(width = 1.dp, brush = SheetTopRimBrush, shape = SheetTopShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // Dedicated top grab handle & header — ONLY this strip handles swipe-to-dismiss.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SheetHeaderTopWash, SheetTopShape)
                    .pointerInput(dismissThresholdPx) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                val next = (headerDragOffsetY.value + dragAmount).coerceAtLeast(0f)
                                sheetScope.launch { headerDragOffsetY.snapTo(next) }
                            },
                            onDragEnd = {
                                if (headerDragOffsetY.value >= dismissThresholdPx) {
                                    latestOnDismiss()
                                } else {
                                    sheetScope.launch {
                                        headerDragOffsetY.animateTo(0f, animationSpec = glassSpring())
                                    }
                                }
                            },
                            onDragCancel = {
                                sheetScope.launch {
                                    headerDragOffsetY.animateTo(0f, animationSpec = glassSpring())
                                }
                            },
                        )
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 10.dp)
                        .width(40.dp)
                        .height(4.dp)
                        .background(Color(0x45FFFFFF), CircleShape),
                )
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
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (activeTab == PickerTab.LINES) {
                                if (stableActiveLines.isNotEmpty()) {
                                    "${stableActiveLines.size} linee attive ora su Torino"
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
                        onClick = latestOnDismiss,
                        modifier = Modifier.testTag("close_line_picker_button"),
                    )
                }
            }

            // Segmented switcher & search input
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            ) {
                PickerSegmentedToggle(
                    selected = activeTab,
                    onSelect = { activeTab = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("picker_mode_segmented_toggle"),
                )

                Spacer(modifier = Modifier.height(12.dp))

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
                        trailingIcon = if (lineQuery.isNotEmpty()) {
                            {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .clickable { lineQuery = "" },
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
                        shape = PillShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = RegularTileFill,
                            unfocusedContainerColor = RegularTileFill,
                            focusedBorderColor = Color(0x88FFFFFF),
                            unfocusedBorderColor = GlassPillBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextBody,
                            cursorColor = AccentGold,
                        ),
                    )

                    if (cleanedLineQuery.isNotEmpty() && !exactMatchExists) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val customInteraction = remember { MutableInteractionSource() }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .glassInteractive(customInteraction) {
                                    latestOnSelectLine(cleanedLineQuery)
                                    latestOnDismiss()
                                }
                                .testTag("select_custom_line_button"),
                            shape = PillShape,
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
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .clickable { stopQuery = "" },
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
                        shape = PillShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = RegularTileFill,
                            unfocusedContainerColor = RegularTileFill,
                            focusedBorderColor = AccentCyan.copy(alpha = 0.72f),
                            unfocusedBorderColor = GlassPillBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextBody,
                            cursorColor = AccentCyan,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable content owns 100% of remaining vertical space with zero gesture wrappers.
            if (activeTab == PickerTab.LINES) {
                if (favoriteSummaries.isEmpty() && regularLines.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (stableActiveLines.isEmpty()) {
                                "Caricamento linee dai feed GTT…"
                            } else {
                                "Nessuna linea attiva per \"$cleanedLineQuery\""
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSubtitle,
                        )
                    }
                } else {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        val columnCount = remember(maxWidth) {
                            ((maxWidth - 40.dp + 10.dp) / 104.dp).toInt().coerceIn(3, 5)
                        }
                        val favoriteRows = remember(favoriteSummaries, columnCount) {
                            favoriteSummaries.chunked(columnCount)
                        }
                        val regularRows = remember(regularLines, columnCount) {
                            regularLines.chunked(columnCount)
                        }

                        // Pre-chunked LazyColumn cuts lazy subcomposition slots by 3x-5x during flings
                        // compared to LazyVerticalGrid, keeping scrolling locked at 90/120fps from frame 1.
                        LazyColumn(
                            state = linesListState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("active_lines_grid"),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 20.dp),
                        ) {
                            if (favoriteRows.isNotEmpty()) {
                                item(
                                    key = "header_favorites",
                                    contentType = "section_header",
                                ) {
                                    LineSectionHeader(
                                        title = "Linee preferite",
                                        count = favoriteSummaries.size,
                                        isFavoriteHeader = true,
                                        modifier = Modifier.testTag("favorite_lines_section_header"),
                                    )
                                }
                                items(
                                    items = favoriteRows,
                                    key = { row -> "fav_row_${row.first().lineCode}" },
                                    contentType = { "fav_line_row" },
                                ) { rowItems ->
                                    LineTileRow(
                                        items = rowItems,
                                        columnCount = columnCount,
                                        selectedLine = selectedLine,
                                        isFavoriteRow = true,
                                        onSelectLine = { code ->
                                            latestOnSelectLine(code)
                                            latestOnDismiss()
                                        },
                                        onToggleFavorite = latestOnToggleFavoriteLine,
                                    )
                                }
                                if (regularRows.isNotEmpty()) {
                                    item(
                                        key = "header_all_lines",
                                        contentType = "section_header",
                                    ) {
                                        LineSectionHeader(
                                            title = "Tutte le linee attive",
                                            count = regularLines.size,
                                            isFavoriteHeader = false,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                    }
                                }
                            }

                            items(
                                items = regularRows,
                                key = { row -> "reg_row_${row.first().lineCode}" },
                                contentType = { "line_row" },
                            ) { rowItems ->
                                LineTileRow(
                                    items = rowItems,
                                    columnCount = columnCount,
                                    selectedLine = selectedLine,
                                    isFavoriteRow = false,
                                    onSelectLine = { code ->
                                        latestOnSelectLine(code)
                                        latestOnDismiss()
                                    },
                                    onToggleFavorite = latestOnToggleFavoriteLine,
                                )
                            }
                        }
                    }
                }
            } else {
                when {
                    isLoadingStopIndex && stopSearchResults.isEmpty() -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
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
                                .weight(1f),
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
                        val userLat = userLocation?.lat
                        val userLon = userLocation?.lon
                        LazyColumn(
                            state = stopListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .testTag("stop_search_results_list"),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 20.dp),
                        ) {
                            items(
                                items = stopSearchResults,
                                key = { it.stopId },
                                contentType = { "stop_row" },
                            ) { entry ->
                                val distMeters = remember(entry.stopId, userLat, userLon) {
                                    if (userLat != null && userLon != null) {
                                        GttFleetAnalyzer.haversineMeters(userLat, userLon, entry.lat, entry.lon).roundToInt()
                                    } else {
                                        null
                                    }
                                }
                                val onStopClick = remember(entry.stopId) {
                                    {
                                        latestOnSelectStop(entry)
                                        latestOnDismiss()
                                    }
                                }
                                GlassStopSearchRow(
                                    entry = entry,
                                    selectedLine = selectedLine,
                                    distanceMeters = distMeters,
                                    onClick = onStopClick,
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
        shape = PillShape,
        color = RegularTileFill,
        border = BorderStroke(1.dp, RegularTileBorder),
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
                    .background(
                        brush = PickerSliderBrush,
                        shape = PillShape,
                    )
                    .border(
                        width = 1.dp,
                        brush = PickerSliderRimBrush,
                        shape = PillShape,
                    ),
            )
            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEach { tab ->
                    val isSelected = tab == selected
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
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
    val hasSelectedLine = remember(entry.lines, selectedLine) {
        entry.lines.any { it.equals(selectedLine, ignoreCase = true) }
    }
    val displayName = remember(entry.name) { shortTurinPlaceName(entry.name) }
    val headsignSummary = remember(entry.headsigns) {
        entry.headsigns.take(2).joinToString(" · ").takeIf { it.isNotBlank() }
    }
    val distLabel = remember(distanceMeters) {
        distanceMeters?.let { m ->
            if (m < 1000) "$m m" else "${(m / 100) / 10.0} km".replace('.', ',')
        }
    }
    val borderColor = if (hasSelectedLine) AccentGold.copy(alpha = 0.45f) else RegularTileBorder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(if (hasSelectedLine) RegularTileSelectedFill else RegularTileFill, StopRowShape)
            .border(1.dp, borderColor, StopRowShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 10.dp)
            .testTag("stop_search_item_${entry.stopId}"),
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
                    .background(Color(0x22FFFFFF), PillShape)
                    .border(1.dp, RegularTileBorder, PillShape)
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleLines.forEach { lineCode ->
                    val isActiveLine = lineCode.equals(selectedLine, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .background(
                                color = if (isActiveLine) AccentGold else Color(0x1EFFFFFF),
                                shape = LineBadgeShape,
                            )
                            .border(
                                width = 1.dp,
                                color = if (isActiveLine) AccentGold else RegularTileBorder,
                                shape = LineBadgeShape,
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

@Composable
private fun LineSectionHeader(
    title: String,
    count: Int,
    isFavoriteHeader: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (isFavoriteHeader) {
            Icon(
                imageVector = Icons.Rounded.Star,
                contentDescription = null,
                tint = AccentGold,
                modifier = Modifier.size(15.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = if (isFavoriteHeader) AccentGold else TextSubtitle,
        )
        Box(
            modifier = Modifier
                .background(if (isFavoriteHeader) Color(0x22FBBF24) else Color(0x18FFFFFF), PillShape)
                .border(
                    width = 0.8.dp,
                    color = if (isFavoriteHeader) FavoriteTileBorder else RegularTileBorder,
                    shape = PillShape,
                )
                .padding(horizontal = 7.dp, vertical = 1.dp),
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (isFavoriteHeader) AccentGold else TextSubtitle,
            )
        }
    }
}

@Composable
private fun LineTileRow(
    items: List<LineSummary>,
    columnCount: Int,
    selectedLine: String,
    isFavoriteRow: Boolean,
    onSelectLine: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (idx in 0 until columnCount) {
            val item = items.getOrNull(idx)
            if (item != null) {
                val isSelected = item.lineCode.equals(selectedLine, ignoreCase = true)
                GlassLineTile(
                    summary = item,
                    isSelected = isSelected,
                    isFavorite = isFavoriteRow,
                    highlightedFavorite = isFavoriteRow,
                    onToggleFavorite = { onToggleFavorite(item.lineCode) },
                    onClick = { onSelectLine(item.lineCode) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * Ultra-lightweight line tile engineered for locked 90/120fps first-pass scrolling:
 * - Zero `.clip()` hardware outline masks.
 * - Zero `Icon` / `VectorPainter` allocations (star & live status dot rendered directly via Canvas).
 * - Zero Material Ripple indication nodes during flings.
 */
@Composable
private fun GlassLineTile(
    summary: LineSummary,
    isSelected: Boolean,
    isFavorite: Boolean,
    highlightedFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val countLabel = when (summary.activeVehicleCount) {
        0 -> "Nessun bus"
        1 -> "1 mezzo"
        else -> "${summary.activeVehicleCount} mezzi"
    }
    val fillColor = when {
        highlightedFavorite && isSelected -> FavoriteTileSelectedFill
        highlightedFavorite -> FavoriteTileFill
        isSelected -> RegularTileSelectedFill
        else -> RegularTileFill
    }
    val borderColor = when {
        isSelected -> GlassRowActiveBorder
        highlightedFavorite -> FavoriteTileBorder
        else -> RegularTileBorder
    }
    val borderWidthDp = if (isSelected || highlightedFavorite) 1.2.dp else 1.dp
    val dotColor = if (summary.activeVehicleCount > 0) AccentEmerald else TextTertiary
    val titleText = if (isSelected) "${summary.lineCode} ✓" else summary.lineCode
    val starContentDesc = if (isFavorite) {
        "Rimuovi linea ${summary.lineCode} dalle preferite"
    } else {
        "Aggiungi linea ${summary.lineCode} alle preferite"
    }

    Box(
        modifier = modifier
            .height(74.dp)
            .drawWithCache {
                val radiusPx = 14.dp.toPx()
                val corner = CornerRadius(radiusPx, radiusPx)
                val stroke = Stroke(width = borderWidthDp.toPx())
                onDrawBehind {
                    drawRoundRect(color = fillColor, cornerRadius = corner)
                    drawRoundRect(color = borderColor, cornerRadius = corner, style = stroke)
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .testTag("line_tile_${summary.lineCode}"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleLarge,
                color = if (isSelected || highlightedFavorite) AccentGold else TextPrimary,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelSmall,
                color = if (summary.activeVehicleCount > 0) TextSubtitle else TextTertiary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .drawBehind {
                        drawCircle(
                            color = dotColor,
                            radius = 3.dp.toPx(),
                            center = Offset(3.dp.toPx(), size.height * 0.5f),
                        )
                    }
                    .padding(start = 10.dp),
            )
        }

        // Top-right favorite star toggle — cached Canvas Path (zero per-frame allocations)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(32.dp)
                .semantics { contentDescription = starContentDesc }
                .drawWithCache {
                    val cx = size.width * 0.5f
                    val cy = size.height * 0.5f
                    val outerR = 6.8.dp.toPx()
                    val innerR = outerR * 0.44f
                    val starPath = Path()
                    for (i in 0 until 10) {
                        val r = if (i % 2 == 0) outerR else innerR
                        val angleRad = Math.toRadians(-90.0 + i * 36.0)
                        val x = cx + (r * kotlin.math.cos(angleRad)).toFloat()
                        val y = cy + (r * kotlin.math.sin(angleRad)).toFloat()
                        if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
                    }
                    starPath.close()
                    val outlineStroke = Stroke(width = 1.25.dp.toPx())
                    onDrawBehind {
                        if (isFavorite) {
                            drawPath(path = starPath, color = AccentGold)
                        } else {
                            drawPath(path = starPath, color = TextTertiary, style = outlineStroke)
                        }
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onToggleFavorite,
                )
                .testTag("favorite_line_toggle_${summary.lineCode}"),
        )
    }
}
