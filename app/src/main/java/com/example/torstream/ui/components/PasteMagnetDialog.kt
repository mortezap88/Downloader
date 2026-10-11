package com.example.torstream.ui.components

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.torstream.data.model.TorrentFileInfo
import com.example.torstream.engine.MagnetParser
import com.example.torstream.engine.bittorrent.TorrentMetadataResolver
import com.example.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@Composable
fun PasteMagnetDialog(
    onDismiss: () -> Unit,
    onProceedToFileSelector: (MagnetParser.ParsedMagnet, String) -> Unit,
    errorMessage: String? = null
) {
    var magnetInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    val parsedPreview = remember(magnetInput) {
        if (magnetInput.isNotBlank()) MagnetParser.parse(magnetInput) else null
    }

    var isResolvingMetadata by remember { mutableStateOf(false) }
    var resolvedFiles by remember { mutableStateOf<List<TorrentFileInfo>>(emptyList()) }
    var resolvedName by remember { mutableStateOf("") }

    // When magnet is entered, asynchronously query the real torrent metadata network
    LaunchedEffect(parsedPreview?.infoHash) {
        val hash = parsedPreview?.infoHash
        if (!hash.isNullOrBlank()) {
            isResolvingMetadata = true
            try {
                val client = OkHttpClient()
                val metadata = TorrentMetadataResolver.resolve(
                    infoHashHex = hash,
                    fallbackName = parsedPreview.displayName,
                    trackers = parsedPreview.trackers,
                    client = client
                )
                resolvedFiles = metadata.files
                resolvedName = metadata.name
            } catch (e: Exception) {
                // Keep base preview
            } finally {
                isResolvingMetadata = false
            }
        } else {
            resolvedFiles = emptyList()
            resolvedName = ""
            isResolvingMetadata = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("paste_magnet_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = DarkSurface,
            tonalElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(CyanPrimary.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddLink,
                                contentDescription = "Add Magnet Link",
                                tint = CyanPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Add Magnet Link",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Direct stream • Zero disk storage",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp).testTag("close_magnet_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Text Field Input
                OutlinedTextField(
                    value = magnetInput,
                    onValueChange = { magnetInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("magnet_url_input"),
                    placeholder = {
                        Text(
                            "Paste magnet:?xt=urn:btih:... or URL",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    },
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanPrimary,
                        unfocusedBorderColor = OutlineDark,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    trailingIcon = {
                        if (magnetInput.isNotEmpty()) {
                            IconButton(onClick = { magnetInput = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                )

                // Paste from Clipboard Button
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                        if (!clip.isNullOrBlank()) {
                            magnetInput = clip.trim()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("paste_clipboard_button"),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.4f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = CyanPrimary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentPaste,
                        contentDescription = "Paste from clipboard",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Paste from Clipboard", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                // Live Validation Preview with Real Swarm Metadata Resolution
                if (parsedPreview != null) {
                    val displayName = if (resolvedName.isNotBlank()) resolvedName else parsedPreview.displayName
                    val fileCount = if (resolvedFiles.isNotEmpty()) resolvedFiles.size else parsedPreview.files.size

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = DarkSurfaceElevated,
                        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldAccent.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Valid",
                                    tint = EmeraldAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Valid Magnet Link",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = EmeraldAccent
                                )
                            }

                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = TextPrimary
                            )

                            if (isResolvingMetadata) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = CyanPrimary,
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = "Fetching all episodes & files from torrent network...",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanPrimary
                                    )
                                }
                            } else {
                                Text(
                                    text = "$fileCount real sub-files / episodes detected",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = CyanPrimary
                                )
                            }
                        }
                    }
                } else if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = RedWarning
                    )
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).testTag("cancel_magnet_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel", color = TextSecondary)
                    }

                    Button(
                        onClick = {
                            if (parsedPreview != null) {
                                val effectiveFiles = if (resolvedFiles.isNotEmpty()) resolvedFiles else parsedPreview.files
                                val effectiveName = if (resolvedName.isNotBlank()) resolvedName else parsedPreview.displayName
                                val updatedMagnet = parsedPreview.copy(
                                    displayName = effectiveName,
                                    files = effectiveFiles
                                )
                                onProceedToFileSelector(updatedMagnet, magnetInput.trim())
                            }
                        },
                        enabled = parsedPreview != null,
                        modifier = Modifier.weight(1.8f).testTag("proceed_to_files_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanPrimary,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = "Select File",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isResolvingMetadata) "Choose Files..." else "Choose Files", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
