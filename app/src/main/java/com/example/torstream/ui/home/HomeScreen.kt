package com.example.torstream.ui.home

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.torstream.data.model.StreamItem
import com.example.torstream.viewmodel.StreamViewModel
import com.example.ui.theme.*

@Composable
fun HomeScreen(
    viewModel: StreamViewModel,
    onOpenMagnetDialog: () -> Unit,
    onInspectStream: (StreamItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val addedStreams by viewModel.addedStreams.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    val displayList = remember(addedStreams, searchQuery) {
        if (searchQuery.isBlank()) addedStreams
        else addedStreams.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                    it.selectedFileName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
        containerColor = DarkBackground,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onOpenMagnetDialog,
                containerColor = CyanPrimary,
                contentColor = Color.Black,
                shape = CircleShape,
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("fab_add_magnet")
            ) {
                Icon(
                    imageVector = Icons.Default.AddLink,
                    contentDescription = "Add Magnet Link",
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            // Minimal clean top header without clutter
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Added Files",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        color = TextPrimary
                    )

                    if (addedStreams.isNotEmpty()) {
                        Text(
                            text = "${addedStreams.size} files",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
            }

            // Search filter if there are items added
            if (addedStreams.size > 3) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("search_stream_input"),
                        placeholder = {
                            Text(
                                "Search added files or episodes...",
                                color = TextMuted,
                                fontSize = 13.sp
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Clear",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanPrimary,
                            unfocusedBorderColor = OutlineDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        )
                    )
                }
            }

            // Stream files list or empty state
            if (displayList.isEmpty()) {
                item {
                    EmptyStreamsState(
                        isFiltered = searchQuery.isNotBlank(),
                        onOpenMagnet = onOpenMagnetDialog
                    )
                }
            } else {
                items(displayList, key = { it.id }) { item ->
                    AddedStreamItemCard(
                        item = item,
                        onStreamClick = { viewModel.playStream(item) },
                        onSelectOtherEpisode = { viewModel.openFileSelectorForExistingStream(item) },
                        onInspectClick = { onInspectStream(item) },
                        onDeleteClick = { viewModel.deleteStream(item.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddedStreamItemCard(
    item: StreamItem,
    onStreamClick: () -> Unit,
    onSelectOtherEpisode: () -> Unit,
    onInspectClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("stream_file_card_${item.id}"),
        shape = RoundedCornerShape(16.dp),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Row: Selected File Name + Delete Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(CyanPrimary.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Movie,
                            contentDescription = "Video File",
                            tint = CyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.displayFileTitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (item.selectedFileName.isNotBlank() && item.title != item.selectedFileName) {
                            Text(
                                text = "From: ${item.title}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("delete_stream_file_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Remove file",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Info Badges Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DarkSurfaceElevated
                ) {
                    Text(
                        text = item.formattedSize,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DarkSurfaceElevated
                ) {
                    Text(
                        text = "Hash: ${item.infoHash.take(8).uppercase()}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = TextMuted,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (item.lastPlayedPositionMs > 1000L) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = CyanPrimary.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "Resume at ${formatMs(item.lastPlayedPositionMs)}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            ),
                            color = CyanPrimary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Change / Switch episode button if multi-file
                if (item.files.size > 1) {
                    OutlinedButton(
                        onClick = onSelectOtherEpisode,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1.2f)
                            .testTag("switch_episode_button_${item.id}"),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VideoLibrary,
                            contentDescription = "Episodes",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Files (${item.files.size})", fontSize = 12.sp)
                    }
                } else {
                    OutlinedButton(
                        onClick = onInspectClick,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(0.9f)
                            .testTag("inspect_stream_button_${item.id}"),
                        border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Details",
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Info", fontSize = 12.sp)
                    }
                }

                // Primary Stream Button
                Button(
                    onClick = onStreamClick,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1.8f)
                        .testTag("stream_file_button_${item.id}"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyanPrimary,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Stream",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (item.lastPlayedPositionMs > 1000L) "Resume Stream" else "Stream Now",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStreamsState(
    isFiltered: Boolean,
    onOpenMagnet: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 60.dp)
            .testTag("empty_streams_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(DarkSurfaceElevated, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AddLink,
                contentDescription = "Empty",
                tint = CyanPrimary,
                modifier = Modifier.size(36.dp)
            )
        }

        Text(
            text = if (isFiltered) "No matching stream files" else "No Stream Files Added",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary
        )

        Text(
            text = if (isFiltered) "Try a different search term or add a new magnet link."
            else "TorStream is a minimalistic online streamer. It streams directly in-memory without downloading to disk.\n\nTap the button below to add a magnet link and choose which episode or sub-file to stream.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Button(
            onClick = onOpenMagnet,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CyanPrimary,
                contentColor = Color.Black
            ),
            modifier = Modifier.testTag("empty_state_add_button")
        ) {
            Icon(imageVector = Icons.Default.AddLink, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add Magnet Link", fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
