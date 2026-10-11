package com.example.torstream.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.torstream.data.model.StreamItem
import com.example.torstream.data.model.StreamStatus
import com.example.torstream.data.model.StreamTelemetry
import com.example.torstream.ui.components.PieceMapVisualizer
import com.example.torstream.ui.components.SwarmTelemetryBar
import com.example.ui.theme.*
import kotlinx.coroutines.delay

enum class VideoResizeMode(val displayName: String, val modeInt: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZOOM("Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FILL("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL)
}

@OptIn(UnstableApi::class)
@Composable
fun TorrentVideoPlayer(
    exoPlayer: ExoPlayer,
    streamItem: StreamItem,
    telemetry: StreamTelemetry,
    isPlaying: Boolean,
    currentPositionMs: Long,
    durationMs: Long,
    playbackSpeed: Float,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekRelative: (Long) -> Unit,
    onOpenSpeedSelector: () -> Unit,
    onOpenInspector: () -> Unit,
    onPipRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity

    BackHandler {
        onBack()
    }

    var showControls by remember { mutableStateOf(true) }
    var resizeMode by remember { mutableStateOf(VideoResizeMode.FIT) }
    var isTelemetryExpanded by remember { mutableStateOf(false) }
    var skipFeedbackText by remember { mutableStateOf<String?>(null) }
    var isSeekingByUser by remember { mutableStateOf(false) }
    var userSeekPosition by remember { mutableFloatStateOf(0f) }

    // Auto-hide controls after inactivity
    LaunchedEffect(showControls, isPlaying, isSeekingByUser) {
        if (showControls && isPlaying && !isSeekingByUser) {
            delay(4000)
            showControls = false
        }
    }

    // Auto-clear skip feedback
    LaunchedEffect(skipFeedbackText) {
        if (skipFeedbackText != null) {
            delay(900)
            skipFeedbackText = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("torrent_video_player")
    ) {
        // ExoPlayer AndroidView
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            showControls = !showControls
                        },
                        onDoubleTap = { offset ->
                            val screenWidth = size.width
                            if (offset.x < screenWidth / 2) {
                                onSeekRelative(-10000L)
                                skipFeedbackText = "-10s"
                            } else {
                                onSeekRelative(10000L)
                                skipFeedbackText = "+10s"
                            }
                            showControls = true
                        }
                    )
                },
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    this.resizeMode = resizeMode.modeInt
                    this.player = exoPlayer
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
                playerView.resizeMode = resizeMode.modeInt
            }
        )

        // Double tap feedback overlay
        AnimatedVisibility(
            visible = skipFeedbackText != null,
            enter = fadeIn(tween(150)) + scaleIn(tween(150)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Surface(
                shape = CircleShape,
                color = OverlayGlass,
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (skipFeedbackText?.startsWith("+") == true) Icons.Default.FastForward else Icons.Default.FastRewind,
                            contentDescription = "Seek",
                            tint = CyanPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = skipFeedbackText ?: "",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = CyanPrimary
                        )
                    }
                }
            }
        }

        // Fullscreen Cinema HUD Controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(250))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                OverlayGlass,
                                Color.Transparent,
                                Color.Transparent,
                                OverlayGlass
                            )
                        )
                    )
            ) {
                // Top Control Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("player_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = TextPrimary
                            )
                        }

                        Column(modifier = Modifier.padding(start = 4.dp)) {
                            Text(
                                text = streamItem.displayFileTitle,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (streamItem.selectedFileName.isNotBlank() && streamItem.title != streamItem.selectedFileName) {
                                Text(
                                    text = streamItem.title,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Top Action Icons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Aspect Ratio Cycle
                        IconButton(
                            onClick = {
                                resizeMode = when (resizeMode) {
                                    VideoResizeMode.FIT -> VideoResizeMode.ZOOM
                                    VideoResizeMode.ZOOM -> VideoResizeMode.FILL
                                    VideoResizeMode.FILL -> VideoResizeMode.FIT
                                }
                            },
                            modifier = Modifier.testTag("player_aspect_ratio_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AspectRatio,
                                contentDescription = "Resize mode: ${resizeMode.displayName}",
                                tint = CyanPrimary
                            )
                        }

                        // Stream Swarm Inspector
                        IconButton(
                            onClick = onOpenInspector,
                            modifier = Modifier.testTag("player_inspector_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hub,
                                contentDescription = "Stream Inspector",
                                tint = EmeraldAccent
                            )
                        }

                        // Picture in Picture
                        IconButton(
                            onClick = onPipRequest,
                            modifier = Modifier.testTag("player_pip_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = "Picture in Picture",
                                tint = TextPrimary
                            )
                        }
                    }
                }

                // Center Transport Controls
                if (telemetry.downloadSpeedBps == 0L && currentPositionMs <= 0L && !isPlaying) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            color = CyanPrimary,
                            modifier = Modifier.size(42.dp),
                            strokeWidth = 3.dp
                        )
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = OverlayGlass
                        ) {
                            Text(
                                text = telemetry.statusMessage,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                color = TextPrimary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                        if (telemetry.totalSeeds > 0 || telemetry.connectedPeers > 0) {
                            Text(
                                text = "Swarm: ${telemetry.totalSeeds} seeds • ${telemetry.connectedPeers} connected peers",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                color = EmeraldAccent
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Rewind 10s
                        IconButton(
                            onClick = { onSeekRelative(-10000L) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("player_rewind_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Replay10,
                                contentDescription = "Rewind 10 seconds",
                                tint = TextPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        // Play / Pause Primary Button
                        Surface(
                            onClick = onTogglePlayPause,
                            shape = CircleShape,
                            color = CyanPrimary,
                            modifier = Modifier
                                .size(64.dp)
                                .testTag("player_play_pause_button"),
                            shadowElevation = 8.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.Black,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        // Fast Forward 10s
                        IconButton(
                            onClick = { onSeekRelative(10000L) },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("player_forward_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Forward10,
                                contentDescription = "Forward 10 seconds",
                                tint = TextPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                }

                // Bottom HUD Controls
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .align(Alignment.BottomCenter),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Scrubber Bar & Times
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(if (isSeekingByUser) userSeekPosition.toLong() else currentPositionMs),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = TextPrimary
                        )

                        Text(
                            text = formatDuration(durationMs, isPlaying),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }

                    // Seek Slider
                    val sliderMax = durationMs.coerceAtLeast(1L).toFloat()
                    val sliderVal = if (isSeekingByUser) {
                        userSeekPosition.coerceIn(0f, sliderMax)
                    } else {
                        currentPositionMs.toFloat().coerceIn(0f, sliderMax)
                    }

                    Slider(
                        value = sliderVal,
                        enabled = durationMs > 0,
                        onValueChange = {
                            isSeekingByUser = true
                            userSeekPosition = it
                        },
                        onValueChangeFinished = {
                            onSeekTo(userSeekPosition.toLong())
                            isSeekingByUser = false
                        },
                        valueRange = 0f..sliderMax,
                        colors = SliderDefaults.colors(
                            thumbColor = CyanPrimary,
                            activeTrackColor = CyanPrimary,
                            inactiveTrackColor = OutlineDark
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(24.dp)
                            .testTag("player_seek_slider")
                    )

                    // Secondary Bottom Row: Telemetry pill & Speed toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Swarm Telemetry Pill (Tap to expand/collapse)
                        Surface(
                            onClick = { isTelemetryExpanded = !isTelemetryExpanded },
                            shape = RoundedCornerShape(16.dp),
                            color = OverlayGlass,
                            border = androidx.compose.foundation.BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.3f)),
                            modifier = Modifier.testTag("toggle_telemetry_hud_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(EmeraldAccent)
                                )
                                Text(
                                    text = telemetry.downloadSpeedFormatted,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    ),
                                    color = CyanPrimary
                                )
                                Text(
                                    text = "• ${telemetry.connectedPeers} peers",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                    color = TextSecondary
                                )
                                Icon(
                                    imageVector = if (isTelemetryExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Expand Swarm Stats",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Playback Speed Pill
                        Surface(
                            onClick = onOpenSpeedSelector,
                            shape = RoundedCornerShape(16.dp),
                            color = OverlayGlass,
                            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineDark),
                            modifier = Modifier.testTag("player_speed_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = "Speed",
                                    tint = CyanPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "${playbackSpeed}x",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = TextPrimary
                                )
                            }
                        }
                    }

                    // Expanded Telemetry and Piece Map
                    AnimatedVisibility(
                        visible = isTelemetryExpanded,
                        enter = expandVertically(tween(200)) + fadeIn(),
                        exit = shrinkVertically(tween(200)) + fadeOut()
                    ) {
                        SwarmTelemetryBar(
                            telemetry = telemetry,
                            modifier = Modifier.padding(top = 4.dp),
                            isCompact = false
                        )
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes % 60, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

private fun formatDuration(durationMs: Long, isPlaying: Boolean): String {
    return if (durationMs > 0) formatTime(durationMs)
    else if (isPlaying) "Live Stream"
    else "Buffering..."
}
