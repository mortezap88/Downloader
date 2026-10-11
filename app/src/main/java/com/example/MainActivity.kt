package com.example

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.torstream.ui.components.FileSelectorDialog
import com.example.torstream.ui.components.PasteMagnetDialog
import com.example.torstream.ui.components.SpeedSelectorDialog
import com.example.torstream.ui.components.StreamInspectorDialog
import com.example.torstream.ui.home.HomeScreen
import com.example.torstream.ui.player.TorrentVideoPlayer
import com.example.torstream.viewmodel.ActiveModal
import com.example.torstream.viewmodel.StreamViewModel
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: StreamViewModel by viewModels()
    private var isInPiPMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Handle incoming magnet link from intent
        handleIntent(intent)

        setContent {
            MyApplicationTheme {
                val currentStream by viewModel.currentStream.collectAsState()
                val telemetry by viewModel.telemetry.collectAsState()
                val isPlaying by viewModel.isPlaying.collectAsState()
                val currentPositionMs by viewModel.currentPositionMs.collectAsState()
                val durationMs by viewModel.durationMs.collectAsState()
                val playbackSpeed by viewModel.playbackSpeed.collectAsState()
                val activeModal by viewModel.activeModal.collectAsState()
                val inspectingStream by viewModel.inspectingStream.collectAsState()
                val pendingParsedMagnet by viewModel.pendingParsedMagnet.collectAsState()
                val errorMessage by viewModel.errorMessage.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    if (currentStream != null) {
                        TorrentVideoPlayer(
                            exoPlayer = viewModel.getPlayer(),
                            streamItem = currentStream!!,
                            telemetry = telemetry,
                            isPlaying = isPlaying,
                            currentPositionMs = currentPositionMs,
                            durationMs = durationMs,
                            playbackSpeed = playbackSpeed,
                            onBack = {
                                viewModel.stopAndExitStream()
                            },
                            onTogglePlayPause = {
                                viewModel.togglePlayPause()
                            },
                            onSeekTo = { pos ->
                                viewModel.seekTo(pos)
                            },
                            onSeekRelative = { offset ->
                                viewModel.seekRelative(offset)
                            },
                            onOpenSpeedSelector = {
                                viewModel.showModal(ActiveModal.SPEED_SELECTOR)
                            },
                            onOpenInspector = {
                                viewModel.showModal(ActiveModal.STREAM_INSPECTOR)
                            },
                            onPipRequest = {
                                enterPiP()
                            }
                        )
                    } else {
                        HomeScreen(
                            viewModel = viewModel,
                            onOpenMagnetDialog = {
                                viewModel.showModal(ActiveModal.MAGNET_INPUT)
                            },
                            onInspectStream = { item ->
                                viewModel.showModal(ActiveModal.STREAM_INSPECTOR, item)
                            }
                        )
                    }

                    // Modals
                    when (activeModal) {
                        ActiveModal.MAGNET_INPUT -> {
                            PasteMagnetDialog(
                                onDismiss = { viewModel.dismissModal() },
                                onProceedToFileSelector = { parsed, raw ->
                                    viewModel.onProceedToFileSelector(parsed, raw)
                                },
                                errorMessage = errorMessage
                            )
                        }
                        ActiveModal.FILE_SELECTOR -> {
                            pendingParsedMagnet?.let { parsed ->
                                FileSelectorDialog(
                                    parsedMagnet = parsed,
                                    initialSelectedIndex = inspectingStream?.selectedFileIndex ?: 0,
                                    onAddSelectedFiles = { selectedFiles, allFiles ->
                                        viewModel.addSelectedFilesToList(selectedFiles, allFiles)
                                    },
                                    onStreamNow = { file, allFiles ->
                                        if (inspectingStream != null && inspectingStream!!.infoHash == parsed.infoHash) {
                                            viewModel.switchEpisodeForStream(inspectingStream!!.copy(files = allFiles), file)
                                            viewModel.dismissModal()
                                        } else {
                                            viewModel.onFileSelectedAndStartStream(file, allFiles)
                                        }
                                    },
                                    onDismiss = { viewModel.dismissModal() }
                                )
                            }
                        }
                        ActiveModal.STREAM_INSPECTOR -> {
                            StreamInspectorDialog(
                                streamItem = inspectingStream ?: currentStream,
                                telemetry = telemetry,
                                onDismiss = { viewModel.dismissModal() }
                            )
                        }
                        ActiveModal.SPEED_SELECTOR -> {
                            SpeedSelectorDialog(
                                currentSpeed = playbackSpeed,
                                onSpeedSelected = { speed ->
                                    viewModel.setPlaybackSpeed(speed)
                                },
                                onDismiss = { viewModel.dismissModal() }
                            )
                        }
                        null -> {}
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val dataUri = intent?.dataString
        if (!dataUri.isNullOrBlank() && dataUri.startsWith("magnet:", ignoreCase = true)) {
            viewModel.handleExternalMagnet(dataUri)
        }
    }

    private fun enterPiP() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                // Fallback
                enterPictureInPictureMode()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (viewModel.currentStream.value != null && viewModel.isPlaying.value) {
            enterPiP()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPiPMode = isInPictureInPictureMode
    }
}
