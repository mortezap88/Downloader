package com.example.torstream.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.example.torstream.data.local.AppDatabase
import com.example.torstream.data.model.StreamItem
import com.example.torstream.data.model.StreamTelemetry
import com.example.torstream.data.model.TorrentFileInfo
import com.example.torstream.data.repository.StreamRepository
import com.example.torstream.engine.MagnetParser
import com.example.torstream.engine.TorrentStreamingEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class ActiveModal {
    MAGNET_INPUT,
    FILE_SELECTOR,
    STREAM_INSPECTOR,
    SPEED_SELECTOR
}

class StreamViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: StreamRepository
    val streamingEngine: TorrentStreamingEngine

    // List of previous files that have been added by the user
    val addedStreams: StateFlow<List<StreamItem>>

    val currentStream: StateFlow<StreamItem?>
    val telemetry: StateFlow<StreamTelemetry>
    val isPlaying: StateFlow<Boolean>
    val currentPositionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val playbackSpeed: StateFlow<Float>

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _activeModal = MutableStateFlow<ActiveModal?>(null)
    val activeModal: StateFlow<ActiveModal?> = _activeModal.asStateFlow()

    private val _inspectingStream = MutableStateFlow<StreamItem?>(null)
    val inspectingStream: StateFlow<StreamItem?> = _inspectingStream.asStateFlow()

    private val _pendingParsedMagnet = MutableStateFlow<MagnetParser.ParsedMagnet?>(null)
    val pendingParsedMagnet: StateFlow<MagnetParser.ParsedMagnet?> = _pendingParsedMagnet.asStateFlow()

    private var pendingRawInput: String = ""

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        val db = AppDatabase.getInstance(application)
        repository = StreamRepository(db.streamHistoryDao())
        streamingEngine = TorrentStreamingEngine(application, viewModelScope)

        addedStreams = repository.allStreams.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

        currentStream = streamingEngine.currentStream
        telemetry = streamingEngine.telemetry
        isPlaying = streamingEngine.isPlaying
        currentPositionMs = streamingEngine.currentPositionMs
        durationMs = streamingEngine.durationMs
        playbackSpeed = streamingEngine.playbackSpeed

        // Periodic playback position sync
        viewModelScope.launch {
            combine(currentStream, currentPositionMs) { stream, pos ->
                Pair(stream, pos)
            }.collect { (stream, pos) ->
                if (stream != null && pos > 1000) {
                    repository.updatePlaybackPosition(stream.id, pos)
                }
            }
        }
    }

    fun getPlayer(): ExoPlayer = streamingEngine.getPlayer()

    fun playStream(item: StreamItem, startPositionMs: Long = 0L) {
        val resumePos = if (startPositionMs > 0L) startPositionMs else item.lastPlayedPositionMs
        streamingEngine.playStream(item, resumePos)
        viewModelScope.launch {
            repository.saveStreamItem(item, resumePos)
        }
    }

    fun togglePlayPause() {
        streamingEngine.togglePlayPause()
    }

    fun seekTo(positionMs: Long) {
        streamingEngine.seekTo(positionMs)
    }

    fun seekRelative(offsetMs: Long) {
        streamingEngine.seekRelative(offsetMs)
    }

    fun setPlaybackSpeed(speed: Float) {
        streamingEngine.setPlaybackSpeed(speed)
    }

    fun stopAndExitStream() {
        currentStream.value?.let { stream ->
            val pos = currentPositionMs.value
            viewModelScope.launch {
                repository.updatePlaybackPosition(stream.id, pos)
            }
        }
        streamingEngine.stopStream()
    }

    fun deleteStream(id: String) {
        viewModelScope.launch {
            repository.deleteStream(id)
        }
    }

    fun clearAllStreams() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun showModal(modal: ActiveModal, inspectItem: StreamItem? = null) {
        _inspectingStream.value = inspectItem ?: currentStream.value
        _activeModal.value = modal
    }

    fun dismissModal() {
        _activeModal.value = null
        _errorMessage.value = null
        _pendingParsedMagnet.value = null
    }

    fun onProceedToFileSelector(parsed: MagnetParser.ParsedMagnet, rawMagnet: String) {
        _pendingParsedMagnet.value = parsed
        pendingRawInput = rawMagnet
        _activeModal.value = ActiveModal.FILE_SELECTOR
    }

    fun addSelectedFilesToList(selectedFiles: List<TorrentFileInfo>, allFiles: List<TorrentFileInfo>) {
        val parsed = _pendingParsedMagnet.value ?: return
        val effectiveFiles = if (allFiles.isNotEmpty()) allFiles else parsed.files
        val rawInput = pendingRawInput
        viewModelScope.launch {
            selectedFiles.forEach { file ->
                val streamItem = MagnetParser.toStreamItem(parsed.copy(files = effectiveFiles), rawInput, file)
                    .copy(files = effectiveFiles)
                repository.saveStreamItem(streamItem)
            }
        }
        dismissModal()
    }

    fun onFileSelectedAndStartStream(selectedFile: TorrentFileInfo, allFiles: List<TorrentFileInfo> = emptyList()) {
        val parsed = _pendingParsedMagnet.value ?: return
        val effectiveFiles = if (allFiles.isNotEmpty()) allFiles else parsed.files
        val streamItem = MagnetParser.toStreamItem(parsed.copy(files = effectiveFiles), pendingRawInput, selectedFile)
            .copy(files = effectiveFiles)
        dismissModal()
        playStream(streamItem)
    }

    fun switchEpisodeForStream(streamItem: StreamItem, newFile: TorrentFileInfo) {
        val updated = streamItem.copy(
            selectedFileName = newFile.name,
            selectedFileIndex = newFile.index,
            fileSizeBytes = newFile.sizeBytes,
            lastPlayedPositionMs = 0L
        )
        viewModelScope.launch {
            repository.updateSelectedFile(streamItem.id, newFile.name, newFile.index)
        }
        playStream(updated, 0L)
    }

    fun openFileSelectorForExistingStream(item: StreamItem) {
        val parsed = MagnetParser.parse(item.magnetUri) ?: MagnetParser.ParsedMagnet(
            infoHash = item.infoHash,
            displayName = item.title,
            trackers = item.trackers,
            webSeeds = emptyList(),
            files = if (item.files.isNotEmpty()) item.files else listOf(
                TorrentFileInfo(
                    index = item.selectedFileIndex,
                    name = item.selectedFileName.ifBlank { item.title },
                    sizeBytes = item.fileSizeBytes,
                    isVideo = true,
                    extension = "mkv"
                )
            )
        )
        _pendingParsedMagnet.value = parsed
        pendingRawInput = item.magnetUri
        _activeModal.value = ActiveModal.FILE_SELECTOR
    }

    fun handleExternalMagnet(input: String) {
        val parsed = MagnetParser.parse(input)
        if (parsed != null) {
            onProceedToFileSelector(parsed, input)
        }
    }

    override fun onCleared() {
        super.onCleared()
        streamingEngine.release()
    }
}
