package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.example.ui.icons.ToBusIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.data.network.AppUpdateChecker
import com.example.data.network.AppUpdateDownloader
import com.example.data.network.AppUpdateInfo
import com.example.data.network.UpdateInstallState
import com.example.ui.glass.AccentIconBadge
import com.example.ui.glass.liquidGlassRimBrush
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentEmerald
import com.example.ui.theme.AccentGold
import com.example.ui.theme.AccentRed
import com.example.ui.theme.DarkInkOnAccent
import com.example.ui.theme.GlassPillBorder
import com.example.ui.theme.GlassPillFill
import com.example.ui.theme.GlassRowBorder
import com.example.ui.theme.GlassRowFill
import com.example.ui.theme.SmokedGlassFill
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextHero
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle

private val DialogShape = RoundedCornerShape(24.dp)
private val DialogRimBrush = liquidGlassRimBrush(topAlpha = 0.42f, midAlpha = 0.14f, bottomAlpha = 0.24f)

@Composable
fun UpdateAvailableDialog(
    update: AppUpdateInfo,
    installState: UpdateInstallState,
    onDownloadClick: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenInstallPermission: () -> Unit,
    onInstallNow: () -> Unit,
    onOpenBrowserFallback: () -> Unit,
    onDismissLater: () -> Unit,
    onIgnoreVersion: () -> Unit,
) {
    val isDownloading = installState is UpdateInstallState.Downloading
    Dialog(
        onDismissRequest = {
            if (!isDownloading) onDismissLater()
        },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("update_available_dialog"),
            shape = DialogShape,
            color = SmokedGlassFill,
            border = BorderStroke(1.2.dp, DialogRimBrush),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val badgeAccent = when (installState) {
                        is UpdateInstallState.ReadyToInstall -> AccentEmerald
                        is UpdateInstallState.NeedsInstallPermission -> AccentGold
                        is UpdateInstallState.Error -> AccentRed
                        else -> AccentCyan
                    }
                    AccentIconBadge(
                        icon = ToBusIcons.SystemUpdate,
                        accent = badgeAccent,
                        size = 42.dp,
                        iconSize = 21.dp,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (installState) {
                                is UpdateInstallState.Downloading -> "Download aggiornamento…"
                                is UpdateInstallState.ReadyToInstall -> "Pronto per l'installazione"
                                is UpdateInstallState.NeedsInstallPermission -> "Consenti installazione"
                                is UpdateInstallState.Error -> "Errore aggiornamento"
                                UpdateInstallState.Idle -> "Aggiornamento disponibile"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = TextHero,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${update.tagName} · installata v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = badgeAccent,
                            maxLines = 1,
                        )
                    }
                }

                if (update.releaseNotes.isNotBlank() && installState is UpdateInstallState.Idle) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp)
                            .background(GlassRowFill, RoundedCornerShape(14.dp))
                            .border(1.dp, GlassRowBorder, RoundedCornerShape(14.dp))
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = update.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBody,
                        )
                    }
                } else if (installState is UpdateInstallState.Idle) {
                    Text(
                        text = "Nuova versione pronta da scaricare direttamente nell'app. Al termine verrà chiesta la conferma di Android e l'APK verrà eliminato automaticamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSubtitle,
                    )
                }

                when (installState) {
                    UpdateInstallState.Idle -> {
                        Spacer(modifier = Modifier.height(2.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .clickable(onClick = onDownloadClick)
                                .testTag("update_download_button"),
                            shape = RoundedCornerShape(999.dp),
                            color = AccentCyan,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (update.apkDownloadUrl != null) "Scarica e installa" else "Apri release su GitHub",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = DarkInkOnAccent,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(GlassPillFill, RoundedCornerShape(999.dp))
                                    .border(1.dp, GlassPillBorder, RoundedCornerShape(999.dp))
                                    .clickable(onClick = onDismissLater)
                                    .testTag("update_later_button"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Più tardi",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color.Transparent, RoundedCornerShape(999.dp))
                                    .border(1.dp, GlassRowBorder, RoundedCornerShape(999.dp))
                                    .clickable(onClick = onIgnoreVersion)
                                    .testTag("update_ignore_button"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Ignora versione",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSubtitle,
                                )
                            }
                        }
                    }

                    is UpdateInstallState.Downloading -> {
                        val fraction = installState.progressFraction
                        val downloadedLabel = AppUpdateDownloader.formatByteSize(installState.bytesDownloaded)
                        val progressCaption = if (fraction != null) {
                            val pct = (fraction * 100f).toInt().coerceIn(0, 100)
                            val totalLabel = AppUpdateDownloader.formatByteSize(installState.totalBytes)
                            "$downloadedLabel / $totalLabel · $pct%"
                        } else if (installState.bytesDownloaded > 0L) {
                            "Scaricati $downloadedLabel…"
                        } else {
                            "Connessione a GitHub…"
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(GlassRowFill, RoundedCornerShape(14.dp))
                                .border(1.dp, GlassRowBorder, RoundedCornerShape(14.dp))
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (fraction != null) {
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(999.dp)),
                                    color = AccentCyan,
                                    trackColor = GlassPillFill,
                                    strokeCap = StrokeCap.Round,
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(999.dp)),
                                    color = AccentCyan,
                                    trackColor = GlassPillFill,
                                    strokeCap = StrokeCap.Round,
                                )
                            }
                            Text(
                                text = progressCaption,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextBody,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(GlassPillFill, RoundedCornerShape(999.dp))
                                .border(1.dp, GlassPillBorder, RoundedCornerShape(999.dp))
                                .clickable(onClick = onCancelDownload)
                                .testTag("update_cancel_download_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Annulla download",
                                style = MaterialTheme.typography.labelLarge,
                                color = TextPrimary,
                            )
                        }
                    }

                    is UpdateInstallState.NeedsInstallPermission -> {
                        Text(
                            text = "Download completato. Attiva «Consenti da questa origine» per ToBus nelle impostazioni Android: appena torni indietro partirà subito il prompt di installazione.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBody,
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .clickable(onClick = onOpenInstallPermission)
                                .testTag("update_grant_install_permission_button"),
                            shape = RoundedCornerShape(999.dp),
                            color = AccentGold,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Consenti installazione",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = DarkInkOnAccent,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(GlassPillFill, RoundedCornerShape(999.dp))
                                .border(1.dp, GlassPillBorder, RoundedCornerShape(999.dp))
                                .clickable(onClick = onDismissLater)
                                .testTag("update_later_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Annulla",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                            )
                        }
                    }

                    is UpdateInstallState.ReadyToInstall -> {
                        Text(
                            text = "Pacchetto verificato e pronto. Conferma l'aggiornamento nella finestra di Android; il file APK verrà eliminato automaticamente dopo l'installazione.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBody,
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .clickable(onClick = onInstallNow)
                                .testTag("update_install_now_button"),
                            shape = RoundedCornerShape(999.dp),
                            color = AccentEmerald,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Installa ora",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = DarkInkOnAccent,
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(GlassPillFill, RoundedCornerShape(999.dp))
                                .border(1.dp, GlassPillBorder, RoundedCornerShape(999.dp))
                                .clickable(onClick = onDismissLater)
                                .testTag("update_later_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Chiudi",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                            )
                        }
                    }

                    is UpdateInstallState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(GlassRowFill, RoundedCornerShape(14.dp))
                                .border(1.dp, AccentRed.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                .padding(12.dp),
                        ) {
                            Text(
                                text = installState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = TextBody,
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .clickable(onClick = onDownloadClick)
                                .testTag("update_retry_download_button"),
                            shape = RoundedCornerShape(999.dp),
                            color = AccentCyan,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "Riprova download",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = DarkInkOnAccent,
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(GlassPillFill, RoundedCornerShape(999.dp))
                                    .border(1.dp, GlassPillBorder, RoundedCornerShape(999.dp))
                                    .clickable(onClick = onOpenBrowserFallback)
                                    .testTag("update_browser_fallback_button"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Apri su GitHub",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary,
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(Color.Transparent, RoundedCornerShape(999.dp))
                                    .border(1.dp, GlassRowBorder, RoundedCornerShape(999.dp))
                                    .clickable(onClick = onDismissLater)
                                    .testTag("update_later_button"),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Chiudi",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSubtitle,
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
fun ConfigureGithubRepoDialog(
    initialRepo: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var repoText by remember(initialRepo) { mutableStateOf(initialRepo) }
    val normalized = remember(repoText) { AppUpdateChecker.normalizeRepoSlug(repoText) }
    val canSave = repoText.isBlank() || normalized != null

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("github_repo_dialog"),
            shape = DialogShape,
            color = SmokedGlassFill,
            border = BorderStroke(1.2.dp, DialogRimBrush),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Repository GitHub",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextHero,
                )
                Text(
                    text = "Inserisci utente/nome-repo (es. mario/ToBus) o l'URL GitHub per controllare una volta al giorno le nuove release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSubtitle,
                )
                OutlinedTextField(
                    value = repoText,
                    onValueChange = { repoText = it },
                    singleLine = true,
                    placeholder = { Text("utente/repository", color = TextSubtitle) },
                    isError = repoText.isNotBlank() && normalized == null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = AccentGold,
                        unfocusedBorderColor = GlassPillBorder,
                        cursorColor = AccentGold,
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("github_repo_input"),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .clickable(onClick = onDismiss)
                            .padding(horizontal = 16.dp)
                            .testTag("github_repo_cancel_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Annulla",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextSubtitle,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .height(48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (canSave) AccentGold else GlassPillFill, RoundedCornerShape(999.dp))
                            .clickable(enabled = canSave) {
                                onSave(normalized.orEmpty())
                            }
                            .padding(horizontal = 18.dp)
                            .testTag("github_repo_save_button"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Salva",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (canSave) DarkInkOnAccent else TextSubtitle,
                        )
                    }
                }
            }
        }
    }
}
