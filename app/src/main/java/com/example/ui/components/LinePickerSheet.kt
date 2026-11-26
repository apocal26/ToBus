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
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import com.example.ui.icons.ToBusIcons
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.GttFleetAnalyzer
import com.example.data.model.LineSummary
import com.example.data.model.StopSearchEntry
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

@androidx.compose.runtime.Immutable
private data class LineRowItems(
    val key: String,
    val items: List<LineSummary>,
)

@androidx.compose.runtime.Immutable
private data class StopRowUiData(
    val entry: StopSearchEntry,
    val hasSelectedLine: Boolean,
    val visibleLines: List<String>,
    val overflowCount: Int,
    val distLabel: String?,
)

private fun buildSharedStarPath(boxSizePx: Float, outerR: Float): Path {
    val cx = boxSizePx * 0.5f
    val cy = boxSizePx * 0.5f
    val innerR = outerR * 0.44f
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outerR else innerR
        val angleRad = Math.toRadians(-90.0 + i * 36.0)
        val x = cx + (r * kotlin.math.cos(angleRad)).toFloat()
        val y = cy + (r * kotlin.math.sin(angleRad)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
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
    isLoadingLines: Boolean = false,
    lineCatalogError: String? = null,
    userLocation: UserLocationState?,
    onSelectLine: (String) -> Unit,
    onToggleFavoriteLine: (String) -> Unit = {},
    onSearchStops: (String) -> Unit,
    onSelectStop: (StopSearchEntry) -> Unit,
    onRetryLoadLines: () -> Unit = {},
    onDismiss: () -> Unit,
    isInitialSetup: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var activeTab by remember { mutableStateOf(PickerTab.LINES) }
    var lineQuery by remember { mutableStateOf("") }
    var stopQuery by remember { mutableStateOf("") }

    // Freeze activeLines snapshot while the picker is open so background telemetry never causes
    // grid recompositions mid-scroll; allow upgrade from empty, single-line, or 0-count fallback.
    var stableActiveLines by remember { mutableStateOf(activeLines) }
    LaunchedEffect(activeLines) {
        val shouldUpdate = when {
            stableActiveLines.isEmpty() && activeLines.isNotEmpty() -> true
            stableActiveLines.size <= 1 && activeLines.size > 1 -> true
            stableActiveLines.none { it.activeVehicleCount > 0 } && activeLines.any { it.activeVehicleCount > 0 } -> true
            else -> false
        }
        if (shouldUpdate) {
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
    val latestOnRetryLoadLines by rememberUpdatedState(onRetryLoadLines)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val activeNowCount = remember(stableActiveLines) {
        stableActiveLines.count { it.activeVehicleCount > 0 }
    }

    val sheetScope = rememberCoroutineScope()
    val headerDragOffsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 72.dp.toPx() }
    val sharedStarPath = remember(density) {
        with(density) { buildSharedStarPath(32.dp.toPx(), 6.8.dp.toPx()) }
    }
    val sharedStarOutlineStroke = remember(density) {
        with(density) { Stroke(width = 1.25.dp.toPx()) }
    }
    val linesListState = rememberLazyListState()
    val stopListState = rememberLazyListState()

    BackHandler(enabled = !isInitialSetup, onBack = latestOnDismiss)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Clickable scrim behind sheet (absorbs map touches; non-dismissible during initial line choice)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xA6000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !isInitialSetup,
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
                    .then(
                        if (!isInitialSetup) {
                            Modifier.pointerInput(dismissThresholdPx) {
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
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                if (!isInitialSetup) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 10.dp)
                            .width(40.dp)
                            .height(4.dp)
                            .background(Color(0x45FFFFFF), CircleShape),
                    )
                } else {
                    Spacer(modifier = Modifier.height(6.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                isInitialSetup && activeTab == PickerTab.LINES -> "Scegli la tua linea"
                                activeTab == PickerTab.LINES -> "Linea GTT"
                                else -> "Cerca Fermata"
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextHero,
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = if (activeTab == PickerTab.LINES) {
                                when {
                                    isInitialSetup && activeNowCount > 0 ->
                                        "$activeNowCount linee attive ora · usata su mappa e widget"
                                    isInitialSetup && stableActiveLines.isNotEmpty() ->
                                        "${stableActiveLines.size} linee GTT disponibili · usata su mappa e widget"
                                    isInitialSetup ->
                                        "Seleziona la linea da seguire su mappa e widget"
                                    activeNowCount > 0 ->
                                        "$activeNowCount linee attive ora su Torino"
                                    stableActiveLines.isNotEmpty() ->
                                        "${stableActiveLines.size} linee GTT disponibili · tocca o cerca"
                                    else ->
                                        "Cerca o inserisci il numero della linea"
                                }
                            } else {
                                if (stopQuery.isBlank() && userLocation != null) {
                                    "Paline più vicine con linee e direzione"
                                } else if (stopQuery.isBlank()) {
                                    "Scrivi nome, #palina o anche la linea (es. Porta Nuova 4)"
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
                    if (!isInitialSetup) {
                        GlassCircleIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_close),
                            onClick = latestOnDismiss,
                            modifier = Modifier.testTag("close_line_picker_button"),
                        )
                    }
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
                                        .size(48.dp)
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
                                    imageVector = ToBusIcons.DirectionsBus,
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
                                        .size(48.dp)
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
                            .weight(1f)
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cleanedLineQuery.isEmpty()) {
                            if (isLoadingLines) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = AccentGold,
                                        )
                                        Text(
                                            text = "Caricamento linee GTT…",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = TextPrimary,
                                        )
                                    }
                                    Text(
                                        text = "Puoi comunque digitare subito un numero linea qui sopra",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSubtitle,
                                        textAlign = TextAlign.Center,
                                    )
                                }
                            } else {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        text = lineCatalogError ?: "Elenco linee non disponibile",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextPrimary,
                                        textAlign = TextAlign.Center,
                                    )
                                    Text(
                                        text = "Cerca manualmente qualsiasi numero linea qui sopra oppure riprova",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextSubtitle,
                                        textAlign = TextAlign.Center,
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val retryInteraction = remember { MutableInteractionSource() }
                                    Row(
                                        modifier = Modifier
                                            .heightIn(min = 48.dp)
                                            .glassInteractive(retryInteraction) {
                                                latestOnRetryLoadLines()
                                            }
                                            .background(RegularTileSelectedFill, PillShape)
                                            .border(1.dp, AccentGold.copy(alpha = 0.65f), PillShape)
                                            .padding(horizontal = 20.dp, vertical = 12.dp)
                                            .testTag("retry_load_lines_button"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Refresh,
                                            contentDescription = null,
                                            tint = AccentGold,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            text = "Riprova",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = AccentGold,
                                        )
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Nessuna linea trovata per \"$cleanedLineQuery\"",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSubtitle,
                                textAlign = TextAlign.Center,
                            )
                        }
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
                            favoriteSummaries.chunked(columnCount).map { row ->
                                LineRowItems(key = "fav_row_${row.first().lineCode}", items = row)
                            }
                        }
                        val regularRows = remember(regularLines, columnCount) {
                            regularLines.chunked(columnCount).map { row ->
                                LineRowItems(key = "reg_row_${row.first().lineCode}", items = row)
                            }
                        }
                        val selectAndDismiss = remember {
                            { code: String ->
                                latestOnSelectLine(code)
                                latestOnDismiss()
                            }
                        }
                        val toggleFavoriteStable = remember {
                            { code: String -> latestOnToggleFavoriteLine(code) }
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
                                    key = { it.key },
                                    contentType = { "fav_line_row" },
                                ) { rowData ->
                                    LineTileRow(
                                        row = rowData,
                                        columnCount = columnCount,
                                        selectedLine = selectedLine,
                                        isFavoriteRow = true,
                                        starPath = sharedStarPath,
                                        starOutlineStroke = sharedStarOutlineStroke,
                                        onSelectLine = selectAndDismiss,
                                        onToggleFavorite = toggleFavoriteStable,
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
                                key = { it.key },
                                contentType = { "line_row" },
                            ) { rowData ->
                                LineTileRow(
                                    row = rowData,
                                    columnCount = columnCount,
                                    selectedLine = selectedLine,
                                    isFavoriteRow = false,
                                    starPath = sharedStarPath,
                                    starOutlineStroke = sharedStarOutlineStroke,
                                    onSelectLine = selectAndDismiss,
                                    onToggleFavorite = toggleFavoriteStable,
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
                        val preparedStopRows = remember(stopSearchResults, selectedLine, userLat, userLon) {
                            val selectedUpper = selectedLine.trim().uppercase()
                            stopSearchResults.map { entry ->
                                val hasSelected = selectedUpper.isNotEmpty() && entry.searchLinesUpper.contains(selectedUpper)
                                val visibleLines = if (entry.lines.isEmpty()) {
                                    emptyList()
                                } else if (!hasSelected) {
                                    if (entry.lines.size <= 8) entry.lines else entry.lines.subList(0, 8)
                                } else {
                                    val ordered = ArrayList<String>(minOf(8, entry.lines.size))
                                    for (code in entry.lines) {
                                        if (code.equals(selectedUpper, ignoreCase = true)) {
                                            ordered.add(code)
                                            break
                                        }
                                    }
                                    for (code in entry.lines) {
                                        if (ordered.size >= 8) break
                                        if (!code.equals(selectedUpper, ignoreCase = true)) {
                                            ordered.add(code)
                                        }
                                    }
                                    ordered
                                }
                                val distLabel = if (userLat != null && userLon != null) {
                                    val m = GttFleetAnalyzer.haversineMeters(userLat, userLon, entry.lat, entry.lon).roundToInt()
                                    if (m < 1000) "$m m" else "${(m / 100) / 10.0} km".replace('.', ',')
                                } else {
                                    null
                                }
                                StopRowUiData(
                                    entry = entry,
                                    hasSelectedLine = hasSelected,
                                    visibleLines = visibleLines,
                                    overflowCount = (entry.lines.size - visibleLines.size).coerceAtLeast(0),
                                    distLabel = distLabel,
                                )
                            }
                        }
                        val selectStopAndDismiss = remember {
                            { entry: StopSearchEntry ->
                                latestOnSelectStop(entry)
                                latestOnDismiss()
                            }
                        }
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
                                items = preparedStopRows,
                                key = { it.entry.stopId },
                                contentType = { "stop_row" },
                            ) { rowData ->
                                GlassStopSearchRow(
                                    row = rowData,
                                    selectedLine = selectedLine,
                                    onSelect = selectStopAndDismiss,
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
                                ToBusIcons.DirectionsBus
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
    row: StopRowUiData,
    selectedLine: String,
    onSelect: (StopSearchEntry) -> Unit,
) {
    val entry = row.entry
    val hasSelectedLine = row.hasSelectedLine
    val fillColor = if (hasSelectedLine) RegularTileSelectedFill else RegularTileFill
    val borderColor = if (hasSelectedLine) AccentGold.copy(alpha = 0.45f) else RegularTileBorder

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .drawBehind {
                val radius = 16.dp.toPx()
                val cr = CornerRadius(radius, radius)
                drawRoundRect(color = fillColor, cornerRadius = cr)
                drawRoundRect(color = borderColor, cornerRadius = cr, style = Stroke(width = 1.dp.toPx()))
            }
            .clickable(
                interactionSource = null,
                indication = null,
                onClick = { onSelect(entry) },
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
                text = entry.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "#${entry.stopCode}",
                style = MaterialTheme.typography.labelSmall,
                color = TextBody,
                modifier = Modifier
                    .drawBehind {
                        val cr = CornerRadius(size.height * 0.5f, size.height * 0.5f)
                        drawRoundRect(color = Color(0x22FFFFFF), cornerRadius = cr)
                        drawRoundRect(color = RegularTileBorder, cornerRadius = cr, style = Stroke(width = 1.dp.toPx()))
                    }
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            )
            if (row.distLabel != null) {
                Text(
                    text = row.distLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentCyan,
                )
            }
        }

        if (entry.headsignSummary != null) {
            Text(
                text = "→ ${entry.headsignSummary}",
                style = MaterialTheme.typography.bodySmall,
                color = TextSubtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (row.visibleLines.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (lineCode in row.visibleLines) {
                    val isActiveLine = lineCode.equals(selectedLine, ignoreCase = true)
                    val badgeFill = if (isActiveLine) AccentGold else Color(0x1EFFFFFF)
                    val badgeBorder = if (isActiveLine) AccentGold else RegularTileBorder
                    Text(
                        text = lineCode,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActiveLine) DarkInkOnAccent else TextPrimary,
                        modifier = Modifier
                            .drawBehind {
                                val r = 8.dp.toPx()
                                val cr = CornerRadius(r, r)
                                drawRoundRect(color = badgeFill, cornerRadius = cr)
                                drawRoundRect(color = badgeBorder, cornerRadius = cr, style = Stroke(width = 1.dp.toPx()))
                            }
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                if (row.overflowCount > 0) {
                    Text(
                        text = "+${row.overflowCount}",
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
    row: LineRowItems,
    columnCount: Int,
    selectedLine: String,
    isFavoriteRow: Boolean,
    starPath: Path,
    starOutlineStroke: Stroke,
    onSelectLine: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val items = row.items
        for (idx in 0 until columnCount) {
            val item = items.getOrNull(idx)
            if (item != null) {
                val isSelected = item.lineCode.equals(selectedLine, ignoreCase = true)
                GlassLineTile(
                    summary = item,
                    isSelected = isSelected,
                    isFavorite = isFavoriteRow,
                    highlightedFavorite = isFavoriteRow,
                    starPath = starPath,
                    starOutlineStroke = starOutlineStroke,
                    onToggleFavorite = onToggleFavorite,
                    onSelectLine = onSelectLine,
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
 * - Zero `Icon` / `VectorPainter` allocations (shared pre-built star Path & live status dot rendered directly via Canvas).
 * - Zero `MutableInteractionSource` or Material Ripple allocations during flings.
 */
@Composable
private fun GlassLineTile(
    summary: LineSummary,
    isSelected: Boolean,
    isFavorite: Boolean,
    highlightedFavorite: Boolean,
    starPath: Path,
    starOutlineStroke: Stroke,
    onToggleFavorite: (String) -> Unit,
    onSelectLine: (String) -> Unit,
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
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = { onSelectLine(summary.lineCode) },
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

        // Top-right favorite star toggle — 48dp touch target preserving visual 32dp top-right star placement
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp)
                .semantics { contentDescription = starContentDesc }
                .drawBehind {
                    translate(left = size.width - 32.dp.toPx(), top = 0f) {
                        if (isFavorite) {
                            drawPath(path = starPath, color = AccentGold)
                        } else {
                            drawPath(path = starPath, color = TextTertiary, style = starOutlineStroke)
                        }
                    }
                }
                .clickable(
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClick = { onToggleFavorite(summary.lineCode) },
                )
                .testTag("favorite_line_toggle_${summary.lineCode}"),
        )
    }
}
