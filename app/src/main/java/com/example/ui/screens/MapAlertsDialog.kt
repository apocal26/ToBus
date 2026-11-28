package com.example.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ServiceAlertCategory
import com.example.data.model.ServiceAlertInfo
import com.example.data.model.ServiceAlertOrganizer
import com.example.data.model.highestAlertCategory
import com.example.ui.glass.AccentIconBadge
import com.example.ui.glass.GlassCircleIconButton
import com.example.ui.glass.GlassModalDialog
import com.example.ui.glass.GlassShimmerBox
import com.example.ui.glass.glassInteractive
import com.example.ui.glass.rememberGlassShimmerBrush
import com.example.ui.icons.ToBusIcons
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentGold
import com.example.ui.theme.GlassRowActiveBorder
import com.example.ui.theme.GlassRowActiveFill
import com.example.ui.theme.GlassRowBorder
import com.example.ui.theme.GlassRowFill
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import com.example.ui.theme.visualStyle
import java.util.Locale

private enum class AlertsScopeFilter {
    ALL,
    ACTIVE_LINE,
    FAVORITES,
    NETWORK_WIDE,
}

/**
 * Centered Liquid Glass modal displaying deduplicated, intelligently organized GTT service alerts:
 * - Auto-groups into Active Line -> Other Favorite Lines -> Network-wide (Tutte le linee) -> Other Lines.
 * - Provides scope tabs, semantic category filter chips, quick search, and live on-demand refresh.
 */
@Composable
internal fun GttAlertsDialog(
    selectedLine: String,
    favoriteLines: List<String>,
    lineAlerts: List<ServiceAlertInfo>,
    allAlerts: List<ServiceAlertInfo>,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) {
        if (allAlerts.isEmpty() && !isRefreshing) {
            onRefresh()
        }
    }

    val nowSec = remember(allAlerts) { System.currentTimeMillis() / 1000L }
    val normalizedSelected = remember(selectedLine) { selectedLine.trim().uppercase(Locale.ROOT) }
    val favoriteSet = remember(favoriteLines) {
        favoriteLines.map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
    }

    val deduplicatedAll = remember(allAlerts, lineAlerts, nowSec) {
        val mergedSource = if (allAlerts.isNotEmpty()) allAlerts else lineAlerts
        ServiceAlertOrganizer.cleanAndDeduplicate(mergedSource, nowEpochSeconds = nowSec)
    }

    val organized = remember(deduplicatedAll, normalizedSelected, favoriteLines, nowSec) {
        ServiceAlertOrganizer.organize(
            alerts = deduplicatedAll,
            selectedLine = normalizedSelected,
            favoriteLines = favoriteLines,
            nowEpochSeconds = nowSec,
        )
    }

    val allFavoritesCount = remember(deduplicatedAll, favoriteSet) {
        if (favoriteSet.isEmpty()) 0
        else deduplicatedAll.count { alert ->
            favoriteSet.any { fav -> ServiceAlertOrganizer.matchesLine(alert, fav) }
        }
    }

    var scopeFilter by rememberSaveable { mutableStateOf(AlertsScopeFilter.ALL) }
    var selectedCategory by rememberSaveable { mutableStateOf<ServiceAlertCategory?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // Keep scope valid if context changes
    val effectiveScope = when {
        scopeFilter == AlertsScopeFilter.ACTIVE_LINE && normalizedSelected.isEmpty() -> AlertsScopeFilter.ALL
        scopeFilter == AlertsScopeFilter.FAVORITES && favoriteSet.isEmpty() -> AlertsScopeFilter.ALL
        scopeFilter == AlertsScopeFilter.NETWORK_WIDE && organized.networkWideAlerts.isEmpty() -> AlertsScopeFilter.ALL
        else -> scopeFilter
    }

    val scopeAlerts = remember(effectiveScope, organized, deduplicatedAll, favoriteSet) {
        when (effectiveScope) {
            AlertsScopeFilter.ALL -> organized.allOrdered
            AlertsScopeFilter.ACTIVE_LINE -> organized.selectedLineAlerts
            AlertsScopeFilter.FAVORITES -> deduplicatedAll.filter { alert ->
                favoriteSet.any { fav -> ServiceAlertOrganizer.matchesLine(alert, fav) }
            }
            AlertsScopeFilter.NETWORK_WIDE -> organized.networkWideAlerts
        }
    }

    val categoryCounts = remember(scopeAlerts) {
        val counts = LinkedHashMap<ServiceAlertCategory, Int>()
        for (cat in ServiceAlertCategory.entries) {
            val c = scopeAlerts.count { it.classifyCategory() == cat }
            if (c > 0) counts[cat] = c
        }
        counts
    }
    val effectiveCategory = selectedCategory?.takeIf { (categoryCounts[it] ?: 0) > 0 }

    val normalizedQuery = remember(searchQuery) { ServiceAlertOrganizer.normalizeForMatch(searchQuery) }
    val rawQueryUpper = remember(searchQuery) { searchQuery.trim().uppercase(Locale.ROOT) }

    fun matchesFilters(alert: ServiceAlertInfo): Boolean {
        if (effectiveCategory != null && alert.classifyCategory() != effectiveCategory) return false
        if (normalizedQuery.isEmpty()) return true
        if (alert.affectedLines.any { it.equals(rawQueryUpper, ignoreCase = true) || it.contains(rawQueryUpper, ignoreCase = true) }) {
            return true
        }
        if (alert.affectedLines.isEmpty() && ("rete".contains(normalizedQuery) || "tutte".contains(normalizedQuery))) {
            return true
        }
        return ServiceAlertOrganizer.normalizeForMatch(alert.headerText).contains(normalizedQuery) ||
            ServiceAlertOrganizer.normalizeForMatch(alert.descriptionText).contains(normalizedQuery)
    }

    val filteredSelected = remember(organized.selectedLineAlerts, effectiveCategory, normalizedQuery, rawQueryUpper) {
        organized.selectedLineAlerts.filter(::matchesFilters)
    }
    val filteredFavorites = remember(organized.favoriteLineAlerts, effectiveCategory, normalizedQuery, rawQueryUpper) {
        organized.favoriteLineAlerts.filter(::matchesFilters)
    }
    val filteredNetwork = remember(organized.networkWideAlerts, effectiveCategory, normalizedQuery, rawQueryUpper) {
        organized.networkWideAlerts.filter(::matchesFilters)
    }
    val filteredOther = remember(organized.otherLineAlerts, effectiveCategory, normalizedQuery, rawQueryUpper) {
        organized.otherLineAlerts.filter(::matchesFilters)
    }
    val filteredScopedFlat = remember(scopeAlerts, effectiveCategory, normalizedQuery, rawQueryUpper) {
        scopeAlerts.filter(::matchesFilters)
    }

    val highestHeaderStyle = remember(organized.selectedLineAlerts, organized.networkWideAlerts) {
        (organized.selectedLineAlerts.highestAlertCategory()
            ?: organized.networkWideAlerts.highestAlertCategory()?.takeIf { it == ServiceAlertCategory.CRITICAL })
            ?.visualStyle()
    }
    val headerAccent = highestHeaderStyle?.accent ?: AccentCyan
    val headerSubtitleColor = highestHeaderStyle?.softText ?: TextSubtitle

    GlassModalDialog(
        onDismissRequest = onDismiss,
        accentTint = headerAccent,
        maxWidth = 560.dp,
    ) {
        // Glass Header with live refresh & close
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AccentIconBadge(
                icon = if (deduplicatedAll.isNotEmpty()) {
                    ToBusIcons.NotificationsActive
                } else {
                    ToBusIcons.NotificationsNone
                },
                accent = headerAccent,
                size = 42.dp,
                iconSize = 20.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Avvisi GTT",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                )
                val subtitleText = when {
                    isRefreshing && deduplicatedAll.isEmpty() -> "Caricamento avvisi GTT in corso…"
                    deduplicatedAll.isEmpty() -> "Nessun avviso attivo o nei prossimi 7 gg"
                    else -> buildList {
                        if (organized.selectedLineAlerts.isNotEmpty()) {
                            add("${organized.selectedLineAlerts.size} Linea $normalizedSelected")
                        }
                        if (organized.networkWideAlerts.isNotEmpty()) {
                            add("${organized.networkWideAlerts.size} rete")
                        }
                        add("${deduplicatedAll.size} unici")
                    }.joinToString(" · ")
                }
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = headerSubtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isRefreshing) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(GlassRowFill, CircleShape)
                        .border(1.dp, GlassRowBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = headerAccent,
                    )
                }
            } else {
                GlassCircleIconButton(
                    icon = Icons.Rounded.Refresh,
                    contentDescription = "Aggiorna avvisi GTT",
                    onClick = onRefresh,
                    modifier = Modifier.testTag("refresh_alerts_dialog_button"),
                )
            }
            GlassCircleIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.cd_close),
                onClick = onDismiss,
                modifier = Modifier.testTag("close_alerts_dialog_button"),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isRefreshing && deduplicatedAll.isEmpty()) {
            val shimmer = rememberGlassShimmerBrush()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) {
                    GlassShimmerBox(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(82.dp),
                        shape = RoundedCornerShape(16.dp),
                        brush = shimmer,
                    )
                }
            }
        } else if (deduplicatedAll.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(GlassRowFill, RoundedCornerShape(16.dp))
                    .border(1.dp, GlassRowBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp),
            ) {
                Text(
                    text = "Nessuna deviazione o avviso GTT attivo oggi o previsto nella prossima settimana.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextBody,
                )
            }
        } else {
            // Scope Filter Tabs
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    AlertFilterPill(
                        label = "Tutti (${deduplicatedAll.size})",
                        selected = effectiveScope == AlertsScopeFilter.ALL,
                        accent = AccentCyan,
                        onClick = { scopeFilter = AlertsScopeFilter.ALL },
                        testTag = "alerts_scope_all",
                    )
                }
                if (normalizedSelected.isNotEmpty()) {
                    item {
                        AlertFilterPill(
                            label = "Linea $normalizedSelected (${organized.selectedLineAlerts.size})",
                            selected = effectiveScope == AlertsScopeFilter.ACTIVE_LINE,
                            accent = highestHeaderStyle?.accent ?: AccentGold,
                            onClick = { scopeFilter = AlertsScopeFilter.ACTIVE_LINE },
                            testTag = "alerts_scope_active_line",
                        )
                    }
                }
                if (favoriteSet.isNotEmpty()) {
                    item {
                        AlertFilterPill(
                            label = "★ Preferite ($allFavoritesCount)",
                            selected = effectiveScope == AlertsScopeFilter.FAVORITES,
                            accent = AccentGold,
                            onClick = { scopeFilter = AlertsScopeFilter.FAVORITES },
                            testTag = "alerts_scope_favorites",
                        )
                    }
                }
                if (organized.networkWideAlerts.isNotEmpty()) {
                    item {
                        AlertFilterPill(
                            label = "Rete GTT (${organized.networkWideAlerts.size})",
                            selected = effectiveScope == AlertsScopeFilter.NETWORK_WIDE,
                            accent = AccentCyan,
                            onClick = { scopeFilter = AlertsScopeFilter.NETWORK_WIDE },
                            testTag = "alerts_scope_network",
                        )
                    }
                }
            }

            // Semantic Category Chips (only if >= 2 categories exist in current scope)
            if (categoryCounts.size >= 2) {
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        AlertFilterPill(
                            label = "Tutte le tipologie",
                            selected = effectiveCategory == null,
                            accent = AccentCyan,
                            compact = true,
                            onClick = { selectedCategory = null },
                            testTag = "alerts_cat_all",
                        )
                    }
                    items(categoryCounts.entries.toList(), key = { it.key.id }) { (category, count) ->
                        val style = category.visualStyle()
                        AlertFilterPill(
                            label = "${category.label} ($count)",
                            selected = effectiveCategory == category,
                            accent = style.accent,
                            compact = true,
                            onClick = {
                                selectedCategory = if (effectiveCategory == category) null else category
                            },
                            testTag = "alerts_cat_${category.id}",
                        )
                    }
                }
            }

            // Quick Search Field when many alerts exist
            if (deduplicatedAll.size >= 6) {
                Spacer(modifier = Modifier.height(8.dp))
                AlertsSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            val hasAnyVisible = if (effectiveScope == AlertsScopeFilter.ALL) {
                filteredSelected.isNotEmpty() ||
                    filteredFavorites.isNotEmpty() ||
                    filteredNetwork.isNotEmpty() ||
                    filteredOther.isNotEmpty()
            } else {
                filteredScopedFlat.isNotEmpty()
            }

            if (!hasAnyVisible) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(GlassRowFill, RoundedCornerShape(16.dp))
                        .border(1.dp, GlassRowBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (normalizedQuery.isNotEmpty()) {
                            "Nessun avviso trovato per \"$searchQuery\"."
                        } else if (effectiveScope == AlertsScopeFilter.ACTIVE_LINE) {
                            "Nessun avviso specifico attivo per la Linea $normalizedSelected."
                        } else {
                            "Nessun avviso corrisponde ai filtri selezionati."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextBody,
                    )
                    if (effectiveScope != AlertsScopeFilter.ALL || effectiveCategory != null || searchQuery.isNotEmpty()) {
                        val resetInt = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .heightIn(min = 44.dp)
                                .glassInteractive(resetInt, enableHoverScale = false) {
                                    scopeFilter = AlertsScopeFilter.ALL
                                    selectedCategory = null
                                    searchQuery = ""
                                }
                                .background(GlassRowActiveFill, RoundedCornerShape(999.dp))
                                .border(1.dp, GlassRowActiveBorder, RoundedCornerShape(999.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .testTag("alerts_reset_filters_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Mostra tutti gli avvisi",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 4.dp),
                ) {
                    if (effectiveScope == AlertsScopeFilter.ALL) {
                        if (filteredSelected.isNotEmpty()) {
                            alertSectionBlock(
                                sectionKey = "sec_active",
                                title = "LINEA $normalizedSelected",
                                badge = "Linea attiva",
                                accent = highestHeaderStyle?.accent ?: AccentCyan,
                                alerts = filteredSelected,
                                selectedLine = normalizedSelected,
                                favoriteSet = favoriteSet,
                                nowSec = nowSec,
                                highlightForLine = true,
                            )
                        }
                        if (filteredFavorites.isNotEmpty()) {
                            alertSectionBlock(
                                sectionKey = "sec_favorites",
                                title = "LINEE PREFERITE ★",
                                badge = "${filteredFavorites.size}",
                                accent = AccentGold,
                                alerts = filteredFavorites,
                                selectedLine = normalizedSelected,
                                favoriteSet = favoriteSet,
                                nowSec = nowSec,
                                highlightForLine = false,
                            )
                        }
                        if (filteredNetwork.isNotEmpty()) {
                            alertSectionBlock(
                                sectionKey = "sec_network",
                                title = "RETE GTT · TUTTE LE LINEE",
                                badge = "${filteredNetwork.size}",
                                accent = AccentCyan,
                                alerts = filteredNetwork,
                                selectedLine = normalizedSelected,
                                favoriteSet = favoriteSet,
                                nowSec = nowSec,
                                highlightForLine = false,
                            )
                        }
                        if (filteredOther.isNotEmpty()) {
                            alertSectionBlock(
                                sectionKey = "sec_other",
                                title = "ALTRE LINEE",
                                badge = "${filteredOther.size}",
                                accent = TextSubtitle,
                                alerts = filteredOther,
                                selectedLine = normalizedSelected,
                                favoriteSet = favoriteSet,
                                nowSec = nowSec,
                                highlightForLine = false,
                            )
                        }
                    } else {
                        items(
                            items = filteredScopedFlat,
                            key = { "flat_${it.id}_${it.headerText.hashCode()}" },
                        ) { alert ->
                            AlertItemCard(
                                alert = alert,
                                selectedLine = normalizedSelected,
                                favoriteSet = favoriteSet,
                                nowSec = nowSec,
                                highlightForLine = effectiveScope == AlertsScopeFilter.ACTIVE_LINE,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.alertSectionBlock(
    sectionKey: String,
    title: String,
    badge: String,
    accent: Color,
    alerts: List<ServiceAlertInfo>,
    selectedLine: String,
    favoriteSet: Set<String>,
    nowSec: Long,
    highlightForLine: Boolean,
) {
    item(key = "${sectionKey}_header") {
        AlertSectionHeader(
            title = title,
            badge = badge,
            accent = accent,
        )
    }
    items(
        items = alerts,
        key = { "${sectionKey}_${it.id}_${it.headerText.hashCode()}" },
    ) { alert ->
        AlertItemCard(
            alert = alert,
            selectedLine = selectedLine,
            favoriteSet = favoriteSet,
            nowSec = nowSec,
            highlightForLine = highlightForLine,
        )
    }
}

@Composable
private fun AlertSectionHeader(
    title: String,
    badge: String,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(accent, CircleShape),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (accent == TextSubtitle) TextBody else accent,
        )
        Box(
            modifier = Modifier
                .background(accent.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
                .border(1.dp, accent.copy(alpha = 0.38f), RoundedCornerShape(999.dp))
                .padding(horizontal = 7.dp, vertical = 1.dp),
        ) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = if (accent == TextSubtitle) TextSubtitle else accent,
            )
        }
    }
}

@Composable
private fun AlertFilterPill(
    label: String,
    selected: Boolean,
    accent: Color,
    compact: Boolean = false,
    onClick: () -> Unit,
    testTag: String,
) {
    val interaction = remember(testTag) { MutableInteractionSource() }
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .heightIn(min = if (compact) 36.dp else 40.dp)
            .glassInteractive(interaction, enableHoverScale = false, onClick = onClick)
            .background(
                color = if (selected) accent.copy(alpha = 0.22f) else GlassRowFill,
                shape = shape,
            )
            .border(
                width = if (selected) 1.2.dp else 1.dp,
                color = if (selected) accent.copy(alpha = 0.70f) else GlassRowBorder,
                shape = shape,
            )
            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 8.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = if (selected) TextPrimary else TextSubtitle,
            maxLines = 1,
        )
    }
}

@Composable
private fun AlertsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(GlassRowFill, shape)
            .border(1.dp, GlassRowBorder, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = TextSubtitle,
            modifier = Modifier.size(17.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "Cerca linea, via o evento…",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtitle,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                cursorBrush = SolidColor(AccentCyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("alerts_search_input"),
            )
        }
        if (query.isNotEmpty()) {
            val clearInt = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clickable(interactionSource = clearInt, indication = null) { onQueryChange("") }
                    .testTag("alerts_search_clear"),
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
    }
}

@Composable
private fun AlertItemCard(
    alert: ServiceAlertInfo,
    selectedLine: String,
    favoriteSet: Set<String>,
    nowSec: Long,
    highlightForLine: Boolean,
) {
    val category = remember(alert) { alert.classifyCategory() }
    val style = remember(category) { category.visualStyle() }
    val cardShape = remember { RoundedCornerShape(16.dp) }
    val bgColor = if (highlightForLine) style.accent.copy(alpha = 0.16f) else GlassRowFill
    val borderColor = if (highlightForLine) style.accent.copy(alpha = 0.56f) else GlassRowBorder
    val validity = remember(alert, nowSec) { alert.validityLabel(nowSec) }
    val activeNow = remember(alert, nowSec) { alert.isActiveNow(nowSec) }
    val isLongDesc = alert.descriptionText.length > 170
    var isExpanded by remember(alert.id, alert.headerText) { mutableStateOf(false) }

    val sortedLines = remember(alert.affectedLines, selectedLine, favoriteSet) {
        alert.affectedLines.sortedWith(
            compareByDescending<String> { it.equals(selectedLine, ignoreCase = true) }
                .thenByDescending { it.uppercase(Locale.ROOT) in favoriteSet }
                .then(ServiceAlertOrganizer.naturalLineComparator),
        )
    }
    val visibleLines = remember(sortedLines) { sortedLines.take(7) }
    val extraLinesCount = (sortedLines.size - visibleLines.size).coerceAtLeast(0)
    val isNetworkWide = remember(alert) { ServiceAlertOrganizer.isNetworkWide(alert) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(bgColor, cardShape)
            .drawBehind {
                val barWidth = (if (highlightForLine) 4.dp else 2.5.dp).toPx()
                drawRect(
                    color = style.accent.copy(alpha = if (highlightForLine) 0.95f else 0.72f),
                    size = Size(width = barWidth, height = size.height),
                )
            }
            .border(1.dp, borderColor, cardShape)
            .then(
                if (isLongDesc) {
                    Modifier.clickable { isExpanded = !isExpanded }
                } else {
                    Modifier
                },
            )
            .animateContentSize()
            .padding(start = 15.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Row 1: Semantic category pill + validity status
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .background(
                        style.accent.copy(alpha = if (highlightForLine) 0.24f else 0.16f),
                        RoundedCornerShape(999.dp),
                    )
                    .border(
                        1.dp,
                        style.accent.copy(alpha = if (highlightForLine) 0.58f else 0.42f),
                        RoundedCornerShape(999.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.softText,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = style.softText,
                )
            }

            if (validity != null) {
                val vColor = if (activeNow) TextPrimary.copy(alpha = 0.88f) else TextSubtitle
                Box(
                    modifier = Modifier
                        .background(Color(0x1CFFFFFF), RoundedCornerShape(999.dp))
                        .border(1.dp, Color(0x2EFFFFFF), RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = validity,
                        style = MaterialTheme.typography.labelSmall,
                        color = vColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Row 2: Affected line chips or "Tutta la rete" badge
        if (visibleLines.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(visibleLines, key = { it }) { line ->
                    val isSelectedLineChip = highlightForLine && line.equals(selectedLine, ignoreCase = true)
                    val isFavLine = line.uppercase(Locale.ROOT) in favoriteSet
                    Box(
                        modifier = Modifier
                            .background(
                                color = when {
                                    isSelectedLineChip -> style.accent
                                    isFavLine -> AccentGold.copy(alpha = 0.18f)
                                    else -> Color(0x18FFFFFF)
                                },
                                shape = RoundedCornerShape(999.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = when {
                                    isSelectedLineChip -> style.accent
                                    isFavLine -> AccentGold.copy(alpha = 0.48f)
                                    else -> Color(0x30FFFFFF)
                                },
                                shape = RoundedCornerShape(999.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = if (isFavLine && !isSelectedLineChip) "★ $line" else "Linea $line",
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                isSelectedLineChip -> style.onAccentInk
                                isFavLine -> AccentGold
                                else -> TextSubtitle
                            },
                        )
                    }
                }
                if (extraLinesCount > 0) {
                    item(key = "more_lines") {
                        Box(
                            modifier = Modifier
                                .background(Color(0x18FFFFFF), RoundedCornerShape(999.dp))
                                .border(1.dp, Color(0x28FFFFFF), RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "+$extraLinesCount altre",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSubtitle,
                            )
                        }
                    }
                }
            }
        } else if (isNetworkWide) {
            Box(
                modifier = Modifier
                    .background(AccentCyan.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                    .border(1.dp, AccentCyan.copy(alpha = 0.40f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "Tutta la rete GTT",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentCyan,
                )
            }
        }

        if (alert.headerText.isNotBlank()) {
            Text(
                text = alert.headerText,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
        }
        if (alert.descriptionText.isNotBlank() && alert.descriptionText != alert.headerText) {
            Text(
                text = alert.descriptionText,
                style = MaterialTheme.typography.bodySmall,
                color = TextBody,
                maxLines = if (isExpanded || !isLongDesc) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (isLongDesc) {
                Text(
                    text = if (isExpanded) "Riduci" else "Leggi tutto",
                    style = MaterialTheme.typography.labelSmall,
                    color = style.softText,
                )
            }
        }
    }
}
