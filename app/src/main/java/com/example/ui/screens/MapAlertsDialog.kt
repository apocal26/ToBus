package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.ServiceAlertInfo
import com.example.data.model.highestAlertCategory
import com.example.ui.glass.AccentIconBadge
import com.example.ui.glass.GlassCircleIconButton
import com.example.ui.glass.GlassModalDialog
import com.example.ui.icons.ToBusIcons
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.GlassRowBorder
import com.example.ui.theme.GlassRowFill
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import com.example.ui.theme.visualStyle

/**
 * Centered Liquid Glass modal displaying active and upcoming GTT service alerts,
 * grouped by the currently viewed line first and color-coded by semantic severity.
 */
@Composable
internal fun GttAlertsDialog(
    selectedLine: String,
    lineAlerts: List<ServiceAlertInfo>,
    allAlerts: List<ServiceAlertInfo>,
    onDismiss: () -> Unit,
) {
    val nowSec = remember { System.currentTimeMillis() / 1000L }
    val sortedLineAlerts = remember(lineAlerts, nowSec) {
        lineAlerts.sortedWith(
            compareByDescending<ServiceAlertInfo> { it.isActiveNow(nowSec) }
                .thenByDescending { it.classifyCategory().priority },
        )
    }
    val lineAlertIds = remember(sortedLineAlerts) { sortedLineAlerts.map { it.id }.toHashSet() }
    val otherAlerts = remember(allAlerts, lineAlertIds, nowSec) {
        allAlerts
            .filterNot { it.id in lineAlertIds }
            .sortedWith(
                compareByDescending<ServiceAlertInfo> { it.isActiveNow(nowSec) }
                    .thenByDescending { it.classifyCategory().priority },
            )
    }
    val highestLineStyle = remember(sortedLineAlerts) {
        sortedLineAlerts.highestAlertCategory()?.visualStyle()
    }
    val headerAccent = highestLineStyle?.accent ?: AccentCyan
    val headerSubtitleColor = highestLineStyle?.softText ?: TextSubtitle

    GlassModalDialog(
        onDismissRequest = onDismiss,
        accentTint = headerAccent,
        maxWidth = 540.dp,
    ) {
        // Glass Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AccentIconBadge(
                icon = if (allAlerts.isNotEmpty()) {
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
                Text(
                    text = when {
                        sortedLineAlerts.isNotEmpty() -> "${sortedLineAlerts.size} per Linea $selectedLine · ${allAlerts.size} recenti"
                        allAlerts.isNotEmpty() -> "${allAlerts.size} eventi, lavori e avvisi recenti"
                        else -> "Nessun avviso recente o nei prossimi 7 gg"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = headerSubtitleColor,
                )
            }
            GlassCircleIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.cd_close),
                onClick = onDismiss,
                modifier = Modifier.testTag("close_alerts_dialog_button"),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (allAlerts.isEmpty()) {
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (sortedLineAlerts.isNotEmpty()) {
                    val lineSectionColor = highestLineStyle?.softText ?: AccentCyan
                    val lineSectionAccent = highestLineStyle?.accent ?: AccentCyan
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(lineSectionAccent, CircleShape),
                        )
                        Text(
                            text = "LINEA $selectedLine",
                            style = MaterialTheme.typography.labelMedium,
                            color = lineSectionColor,
                        )
                        Box(
                            modifier = Modifier
                                .background(lineSectionAccent.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                                .border(1.dp, lineSectionAccent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                                .padding(horizontal = 7.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = "Linea attiva",
                                style = MaterialTheme.typography.labelSmall,
                                color = lineSectionColor,
                            )
                        }
                    }
                    sortedLineAlerts.forEach { alert ->
                        AlertItemCard(
                            alert = alert,
                            selectedLine = selectedLine,
                            nowSec = nowSec,
                            highlightForLine = true,
                        )
                    }
                }

                if (otherAlerts.isNotEmpty()) {
                    if (sortedLineAlerts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ALTRI AVVISI ATTUALI",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSubtitle,
                        )
                    }
                    otherAlerts.forEach { alert ->
                        AlertItemCard(
                            alert = alert,
                            selectedLine = selectedLine,
                            nowSec = nowSec,
                            highlightForLine = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertItemCard(
    alert: ServiceAlertInfo,
    selectedLine: String,
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

        // Row 2: Affected line chips (highlighting the currently viewed line clearly)
        if (alert.affectedLines.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(
                    alert.affectedLines.toList().sortedWith(
                        compareByDescending<String> { it.equals(selectedLine, ignoreCase = true) }
                            .thenBy { it },
                    ),
                ) { line ->
                    val isSelectedLineChip = highlightForLine && line.equals(selectedLine, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelectedLineChip) style.accent else Color(0x18FFFFFF),
                                RoundedCornerShape(999.dp),
                            )
                            .border(
                                1.dp,
                                if (isSelectedLineChip) style.accent else Color(0x30FFFFFF),
                                RoundedCornerShape(999.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "Linea $line",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelectedLineChip) style.onAccentInk else TextSubtitle,
                        )
                    }
                }
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
            )
        }
    }
}
