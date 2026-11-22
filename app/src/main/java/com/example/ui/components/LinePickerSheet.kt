package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.LineSummary
import com.example.ui.glass.GlassCircleIconButton
import com.example.ui.glass.glassInteractive
import com.example.ui.theme.AccentEmerald
import com.example.ui.theme.AccentGold
import com.example.ui.theme.DarkInkOnAccent
import com.example.ui.theme.GlassPillBorder
import com.example.ui.theme.GlassPillFill
import com.example.ui.theme.GlassRowActiveBorder
import com.example.ui.theme.GlassRowActiveFill
import com.example.ui.theme.GlassRowBorder
import com.example.ui.theme.GlassRowFill
import com.example.ui.theme.SmokedGlassBorder
import com.example.ui.theme.SmokedGlassFill
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextHero
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle
import com.example.ui.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinePickerSheet(
    selectedLine: String,
    activeLines: List<LineSummary>,
    onSelectLine: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val cleanedQuery = query.trim().uppercase()
    val filteredLines = remember(activeLines, cleanedQuery) {
        if (cleanedQuery.isEmpty()) {
            activeLines
        } else {
            activeLines.filter { it.lineCode.contains(cleanedQuery, ignoreCase = true) }
        }
    }
    val exactMatchExists = filteredLines.any { it.lineCode.equals(cleanedQuery, ignoreCase = true) }

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
                        text = "Linea GTT",
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextHero,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (activeLines.isNotEmpty()) {
                            "${activeLines.size} linee attive ora su Torino"
                        } else {
                            "Cerca o inserisci il numero della linea"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSubtitle,
                    )
                }
                GlassCircleIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_line_picker_button"),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { if (it.length <= 10) query = it },
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

            if (cleanedQuery.isNotEmpty() && !exactMatchExists) {
                Spacer(modifier = Modifier.height(12.dp))
                val customInteraction = remember { MutableInteractionSource() }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .glassInteractive(customInteraction) {
                            onSelectLine(cleanedQuery)
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
                            text = "Monitora linea $cleanedQuery",
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
                            "Nessuna linea attiva per \"$cleanedQuery\""
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
                        .heightIn(max = 390.dp)
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
