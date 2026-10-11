package com.example.torstream.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.torstream.data.model.StreamItem
import com.example.torstream.data.model.StreamStatus
import com.example.torstream.data.model.StreamTelemetry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TorrentStreamingEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val server = TorrentStreamServer()

    private var exoPlayer: ExoPlayer? = null

    private val _currentStream = MutableStateFlow<StreamItem?>(null)
    val currentStream: StateFlow<StreamItem?> = _currentStream.asStateFlow()

    val telemetry: StateFlow<StreamTelemetry> = server.telemetry

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private var progressTrackerJob: Job? = null

    init {
        server.start(scope)
    }

    fun getPlayer(): ExoPlayer {
        if (exoPlayer == null) {
            val player = ExoPlayer.Builder(context).build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {}
                            Player.STATE_READY -> {
                                if (duration > 0) {
                                    _durationMs.value = duration
                                }
                            }
                            Player.STATE_ENDED -> {
                                _isPlaying.value = false
                            }
                            Player.STATE_IDLE -> {}
                        }
                    }

                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        if (duration > 0) {
                            _durationMs.value = duration
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        android.util.Log.e("TorrentPlayer", "ExoPlayer playback error: ${error.message}", error)
                    }
                })
            }
            exoPlayer = player
            startProgressTracker()
        }
        return exoPlayer!!
    }

    fun playStream(item: StreamItem, startPositionMs: Long = 0L) {
        _currentStream.value = item
        val player = getPlayer()

        // Get local streaming proxy URL
        val streamUrl = server.getStreamUrl(item)

        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        player.setMediaItem(mediaItem)
        player.prepare()

        if (startPositionMs > 0L) {
            player.seekTo(startPositionMs)
        }
        player.play()
    }

    private fun startProgressTracker() {
        progressTrackerJob?.cancel()
        progressTrackerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                exoPlayer?.let { player ->
                    _currentPositionMs.value = player.currentPosition.coerceAtLeast(0L)
                    if (player.duration > 0) {
                        _durationMs.value = player.duration
                    }
                }
                delay(300)
            }
        }
    }

    fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs.coerceAtLeast(0L))
        _currentPositionMs.value = positionMs
    }

    fun seekRelative(offsetMs: Long) {
        exoPlayer?.let { player ->
            val target = (player.currentPosition + offsetMs).coerceIn(0L, player.duration.coerceAtLeast(0L))
            player.seekTo(target)
            _currentPositionMs.value = target
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        exoPlayer?.playbackParameters = PlaybackParameters(speed)
    }

    fun stopStream() {
        progressTrackerJob?.cancel()
        exoPlayer?.stop()
        _isPlaying.value = false
        _currentStream.value = null
        _currentPositionMs.value = 0L
    }

    fun release() {
        stopStream()
        exoPlayer?.release()
        exoPlayer = null
        server.stop()
    }
}
