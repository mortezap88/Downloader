package com.example.torstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.torstream.data.model.TorrentFileInfo
import com.example.torstream.engine.MagnetParser
import com.example.torstream.engine.bittorrent.TorrentMetadataResolver
import com.example.ui.theme.*
import okhttp3.OkHttpClient

@Composable
fun FileSelectorDialog(
    parsedMagnet: MagnetParser.ParsedMagnet,
    initialSelectedIndex: Int = 0,
    onAddSelectedFiles: (List<TorrentFileInfo>, List<TorrentFileInfo>) -> Unit,
    onStreamNow: (TorrentFileInfo, List<TorrentFileInfo>) -> Unit,
    onDismiss: () -> Unit
) {
    var filesList by remember { mutableStateOf(parsedMagnet.files) }
    var isFetchingMetadata by remember { mutableStateOf(false) }
    var filterQuery by remember { mutableStateOf("") }

    // Multi-selection state
    val selectedIndices = remember { mutableStateMapOf<Int, Boolean>() }

    // If file list is not resolved yet, fetch real torrent metadata
    LaunchedEffect(parsedMagnet.infoHash) {
        if (filesList.size <= 1 && (filesList.firstOrNull()?.sizeBytes ?: 0L) == 0L) {
            isFetchingMetadata = true
            try {
                val metadata = TorrentMetadataResolver.resolve(
                    infoHashHex = parsedMagnet.infoHash,
                    fallbackName = parsedMagnet.displayName,
                    trackers = parsedMagnet.trackers,
                    client = OkHttpClient()
                )
                if (metadata.files.isNotEmpty()) {
                    filesList = metadata.files
                }
            } catch (ignored: Exception) {}
            finally {
                isFetchingMetadata = false
            }
        }
    }

    // Default selection: when filesList is available, initialize selection
    LaunchedEffect(filesList) {
        if (selectedIndices.isEmpty() && filesList.isNotEmpty()) {
            if (filesList.size == 1) {
                selectedIndices[0] = true
            } else {
                // Select initial selected index, or all videos if none selected
                if (initialSelectedIndex in filesList.indices) {
                    selectedIndices[initialSelectedIndex] = true
                } else {
                    filesList.filter { it.isVideo }.forEach { selectedIndices[it.index] = true }
                }
            }
        }
    }

    val filteredFiles = remember(filesList, filterQuery) {
        if (filterQuery.isBlank()) filesList
        else filesList.filter { it.name.contains(filterQuery, ignoreCase = true) }
    }

    val selectedCount = selectedIndices.values.count { it }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .padding(horizontal = 4.dp, vertical = 12.dp)
                .testTag("file_selector_dialog"),
            shape = RoundedCornerShape(20.dp),
            color = DarkSurface,
            tonalElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(CyanPrimary.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.VideoLibrary,
                                contentDescription = "Episodes",
                                tint = CyanPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "Select Episodes / Files",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary
                            )
                            Text(
                                text = if (isFetchingMetadata) "Resolving episodes from network..."
                                else "${filesList.size} files found • $selectedCount selected",
                                style = MaterialTheme.typography.labelSmall,
                                color = CyanPrimary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp).testTag("close_file_selector_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Torrent Name Box
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = DarkSurfaceElevated
                ) {
                    Text(
                        text = parsedMagnet.displayName,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Loading Indicator if resolving metadata
                if (isFetchingMetadata) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = CyanPrimary,
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = "Fetching real files & episodes from swarm...",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanPrimary
                        )
                    }
                }

                // Multi-select quick action buttons
                if (filesList.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                filesList.forEach { selectedIndices[it.index] = true }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(30.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark)
                        ) {
                            Text("Select All", fontSize = 11.sp, color = TextSecondary)
                        }

                        OutlinedButton(
                            onClick = {
                                selectedIndices.clear()
                                filesList.filter { it.isVideo }.forEach { selectedIndices[it.index] = true }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(30.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark)
                        ) {
                            Text("Only Videos", fontSize = 11.sp, color = CyanPrimary)
                        }

                        OutlinedButton(
                            onClick = { selectedIndices.clear() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(30.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark)
                        ) {
                            Text("Clear", fontSize = 11.sp, color = TextMuted)
                        }
                    }
                }

                // Filter Field (if more than 3 files)
                if (filesList.size > 3) {
                    OutlinedTextField(
                        value = filterQuery,
                        onValueChange = { filterQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("file_search_input"),
                        placeholder = { Text("Filter episodes / files...", fontSize = 12.sp, color = TextMuted) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = OutlineDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }

                // List of Files / Episodes with Checkboxes
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredFiles, key = { it.index }) { file ->
                        val isChecked = selectedIndices[file.index] == true
                        Surface(
                            onClick = {
                                selectedIndices[file.index] = !isChecked
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isChecked) CyanPrimary.copy(alpha = 0.12f) else DarkSurfaceElevated,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isChecked) CyanPrimary.copy(alpha = 0.8f) else Color.Transparent
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("file_item_${file.index}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                                    .fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedIndices[file.index] = checked
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = CyanPrimary,
                                        checkmarkColor = Color.Black,
                                        uncheckedColor = TextMuted
                                    )
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 13.sp
                                        ),
                                        color = if (isChecked) CyanPrimary else TextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = file.formattedSize,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = "• ${file.extension.uppercase()}",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                            color = TextMuted
                                        )
                                    }
                                }

                                // Quick stream now icon button
                                IconButton(
                                    onClick = { onStreamNow(file, filesList) },
                                    modifier = Modifier.size(34.dp).testTag("quick_stream_button_${file.index}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayCircle,
                                        contentDescription = "Stream immediately",
                                        tint = CyanPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Add to List Primary Action Button
                Button(
                    onClick = {
                        val selectedList = filesList.filter { selectedIndices[it.index] == true }
                        val toAdd = if (selectedList.isNotEmpty()) selectedList else listOfNotNull(filesList.firstOrNull())
                        if (toAdd.isNotEmpty()) {
                            onAddSelectedFiles(toAdd, filesList)
                        }
                    },
                    enabled = selectedCount > 0 || filesList.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("add_selected_files_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(imageVector = Icons.Default.PlaylistAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (selectedCount > 1) "Add $selectedCount Episodes to List"
                        else if (selectedCount == 1) "Add 1 File to List"
                        else "Add Files to List",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
