package com.mediaconverter.app.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mediaconverter.app.data.ConversionFailure
import com.mediaconverter.app.data.DownloadEngine
import com.mediaconverter.app.data.ApiAwareDownloadScheduler
import com.mediaconverter.app.data.DownloadSpec
import com.mediaconverter.app.data.OutputFormat
import com.mediaconverter.app.data.MediaInfoProvider
import com.mediaconverter.app.data.MediaUrlParser
import com.mediaconverter.app.data.NormalizedMediaUrl
import com.mediaconverter.app.data.VideoInfo
import com.mediaconverter.app.data.db.DownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

sealed interface LinkUiState {
    data object Empty : LinkUiState
    data class Invalid(val input: String) : LinkUiState
    data class Loading(val url: NormalizedMediaUrl) : LinkUiState
    data class Ready(val url: NormalizedMediaUrl, val info: VideoInfo) : LinkUiState
    data class Error(val url: NormalizedMediaUrl, val message: String) : LinkUiState
}

data class HomeUiState(
    val url: String = "",
    val linkState: LinkUiState = LinkUiState.Empty,
    val isDownloading: Boolean = false,
    val activeDownloadId: Long? = null,
    val downloadProgress: Int = 0,
    val downloadMessage: String? = null,
    val downloadCompleted: Boolean = false,
    /** The finished download's record, so the UI can open or share the saved file. */
    val completedDownload: DownloadEntity? = null,
    val selectedFormat: OutputFormat = OutputFormat.MP4,
    val selectedQuality: String = "best",
) {
    val isValidUrl: Boolean get() = linkState is LinkUiState.Ready
    val isFetchingInfo: Boolean get() = linkState is LinkUiState.Loading
    val videoInfo: VideoInfo? get() = (linkState as? LinkUiState.Ready)?.info
    val error: String?
        get() = when (val state = linkState) {
            is LinkUiState.Invalid -> "הקישור אינו קישור YouTube או Facebook נתמך"
            is LinkUiState.Error -> state.message
            else -> null
        }
}

interface DownloadCommand {
    suspend fun start(state: HomeUiState): Long
    fun observe(downloadId: Long): Flow<DownloadEntity?>
    suspend fun cancel(downloadId: Long)
}

private class SchedulerDownloadCommand(
    private val scheduler: ApiAwareDownloadScheduler,
) : DownloadCommand {
    override suspend fun start(state: HomeUiState): Long {
        val ready = state.linkState as? LinkUiState.Ready
            ?: throw IllegalStateException("Metadata must be ready")
        return scheduler.schedule(
            DownloadSpec(
                source = ready.url,
                outputFormat = state.selectedFormat,
                quality = state.selectedQuality,
                title = ready.info.title,
                thumbnailUrl = ready.info.thumbnailUrl,
            ),
        )
    }

    override fun observe(downloadId: Long): Flow<DownloadEntity?> = scheduler.observe(downloadId)

    override suspend fun cancel(downloadId: Long) = scheduler.cancel(downloadId)
}

class HomeViewModel(
    private val mediaInfoProvider: MediaInfoProvider,
    private val downloadCommand: DownloadCommand? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var metadataJob: Job? = null
    private var cancellationMessageJob: Job? = null
    private var requestId = 0L
    private var uiSessionId = 0L

    fun onSharedUrl(input: String) {
        uiSessionId += 1
        if (_uiState.value.isDownloading) {
            _uiState.value = _uiState.value.copy(
                isDownloading = false,
                activeDownloadId = null,
                downloadProgress = 0,
                downloadMessage = null,
                downloadCompleted = false,
                completedDownload = null,
            )
        }
        updateUrl(input, forceNewSession = true)
    }

    fun onUrlChange(input: String) {
        if (_uiState.value.isDownloading) return
        updateUrl(input)
    }

    private fun updateUrl(input: String, forceNewSession: Boolean = false) {
        val parsed = MediaUrlParser.parse(input)
        val displayValue = parsed?.value ?: input
        if (!forceNewSession && displayValue == _uiState.value.url) return

        metadataJob?.cancel()
        cancellationMessageJob?.cancel()
        requestId += 1
        val currentRequestId = requestId
        _uiState.value = _uiState.value.copy(
            downloadCompleted = false,
            completedDownload = null,
            downloadMessage = null,
            downloadProgress = 0,
        )

        if (input.isBlank()) {
            _uiState.value = _uiState.value.copy(url = "", linkState = LinkUiState.Empty)
            return
        }
        if (parsed == null) {
            _uiState.value = _uiState.value.copy(
                url = input,
                linkState = LinkUiState.Invalid(input),
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            url = displayValue,
            linkState = LinkUiState.Loading(parsed),
        )
        metadataJob = loadMetadata(parsed, currentRequestId, debounceMillis = 600)
    }

    fun retry() {
        val error = _uiState.value.linkState as? LinkUiState.Error ?: return
        metadataJob?.cancel()
        requestId += 1
        _uiState.value = _uiState.value.copy(linkState = LinkUiState.Loading(error.url))
        metadataJob = loadMetadata(error.url, requestId, debounceMillis = 0)
    }

    fun clear() {
        metadataJob?.cancel()
        cancellationMessageJob?.cancel()
        requestId += 1
        _uiState.value = _uiState.value.copy(
            url = "",
            linkState = LinkUiState.Empty,
            downloadCompleted = false,
            completedDownload = null,
            downloadMessage = null,
            downloadProgress = 0,
        )
    }

    private fun loadMetadata(
        url: NormalizedMediaUrl,
        currentRequestId: Long,
        debounceMillis: Long,
    ) = viewModelScope.launch {
        try {
            delay(debounceMillis)
            val result = mediaInfoProvider.getVideoInfo(url)
            if (currentRequestId != requestId) return@launch
            _uiState.value = if (result.isSuccess) {
                _uiState.value.copy(
                    linkState = LinkUiState.Ready(url, result.getOrThrow()),
                )
            } else {
                _uiState.value.copy(
                    linkState = LinkUiState.Error(url, "לא ניתן למצוא מידע על הסרטון"),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    fun onFormatChange(format: OutputFormat) {
        if (_uiState.value.isDownloading) return
        _uiState.value = _uiState.value.copy(
            selectedFormat = format,
            selectedQuality = if (format == OutputFormat.MP4) "best" else "192",
        )
    }

    fun onQualityChange(quality: String) {
        if (_uiState.value.isDownloading) return
        _uiState.value = _uiState.value.copy(selectedQuality = quality)
    }

    fun startDownload() {
        cancellationMessageJob?.cancel()
        val state = _uiState.value
        if (state.downloadCompleted) {
            _uiState.value = HomeUiState()
            return
        }
        if (state.linkState !is LinkUiState.Ready || state.isDownloading) return
        val command = downloadCommand ?: return
        val startSessionId = uiSessionId
        viewModelScope.launch {
            if (startSessionId == uiSessionId) {
                _uiState.value = _uiState.value.copy(
                    isDownloading = true,
                    downloadMessage = "מוסיף את ההורדה לתור…",
                )
            }
            try {
                val downloadId = command.start(state)
                if (startSessionId == uiSessionId) {
                    _uiState.value = _uiState.value.copy(
                        isDownloading = true,
                        activeDownloadId = downloadId,
                        downloadProgress = 0,
                        downloadMessage = "ההורדה החלה… 0%",
                    )
                    observeDownload(command, downloadId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (startSessionId == uiSessionId) {
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        downloadMessage = "לא ניתן להתחיל את ההורדה",
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        val command = downloadCommand ?: return
        val downloadId = _uiState.value.activeDownloadId ?: return
        val cancelSessionId = uiSessionId
        viewModelScope.launch {
            fun isCurrentDownload() = cancelSessionId == uiSessionId &&
                _uiState.value.activeDownloadId == downloadId
            if (isCurrentDownload()) {
                _uiState.value = _uiState.value.copy(downloadMessage = "מבטל את ההורדה…")
            }
            runCatching { command.cancel(downloadId) }
                .onFailure {
                    if (it is CancellationException) throw it
                    if (isCurrentDownload()) {
                        _uiState.value = _uiState.value.copy(downloadMessage = "לא ניתן לבטל את ההורדה")
                    }
                }
        }
    }

    private fun observeDownload(command: DownloadCommand, downloadId: Long) {
        viewModelScope.launch {
            command.observe(downloadId)
                .filterNotNull()
                .takeWhile { record ->
                    if (_uiState.value.activeDownloadId != downloadId) return@takeWhile false
                    val terminal = record.status in setOf("completed", "failed", "cancelled")
                    val completed = record.status == "completed"
                    _uiState.value = _uiState.value.copy(
                        isDownloading = !terminal,
                        activeDownloadId = if (terminal) null else downloadId,
                        downloadProgress = record.progress,
                        downloadCompleted = completed,
                        completedDownload = record.takeIf { completed && it.outputUri.isNotBlank() },
                        downloadMessage = when (record.status) {
                            "completed" -> "ההורדה הושלמה"
                            "failed" -> downloadFailureMessage(record.errorCode)
                            "cancelled" -> CANCELLED_MESSAGE
                            "pending" -> "ההורדה ממתינה… ${record.progress}%"
                            else -> "מוריד… ${record.progress}%"
                        },
                    )
                    if (record.status == "cancelled") {
                        clearCancellationMessageAfterDelay()
                    }
                    !terminal
                }
                .collect {}
        }
    }

    private fun clearCancellationMessageAfterDelay() {
        cancellationMessageJob?.cancel()
        cancellationMessageJob = viewModelScope.launch {
            delay(CANCELLATION_MESSAGE_MILLIS)
            if (_uiState.value.downloadMessage == CANCELLED_MESSAGE) {
                _uiState.value = _uiState.value.copy(downloadMessage = null)
            }
        }
    }

    class Factory(
        private val application: Application,
        private val downloadCommand: DownloadCommand? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java))
            val command = downloadCommand
                ?: SchedulerDownloadCommand(ApiAwareDownloadScheduler(application))
            return HomeViewModel(DownloadEngine(application), command) as T
        }
    }

    private companion object {
        const val CANCELLATION_MESSAGE_MILLIS = 2_000L
        const val CANCELLED_MESSAGE = "ההורדה בוטלה"
    }
}

/**
 * Maps a persisted failure code to a Hebrew sentence. The raw `errorMessage` stays in the
 * database for diagnostics; it is English and can carry pages of FFmpeg or yt-dlp output,
 * which is not something to show on the home screen.
 */
internal fun downloadFailureMessage(errorCode: String): String {
    val failure = ConversionFailure.entries.firstOrNull { it.code == errorCode }
    val reason = when (failure) {
        ConversionFailure.INVALID_URL -> "הקישור אינו נתמך"
        ConversionFailure.OFFLINE -> "אין חיבור לאינטרנט"
        ConversionFailure.LOGIN_REQUIRED -> "הסרטון פרטי או דורש התחברות"
        ConversionFailure.PROVIDER_CHANGED -> "האתר השתנה, נסו שוב מאוחר יותר"
        ConversionFailure.NO_COMPATIBLE_FORMAT -> "לא נמצא פורמט מתאים להורדה"
        ConversionFailure.STORAGE_FULL -> "אין מקום באחסון או אין הרשאה לשמור קבצים"
        ConversionFailure.PROCESSING_FAILED -> "ההמרה לא הצליחה, נסו שוב"
        ConversionFailure.DOWNLOAD_FAILED, ConversionFailure.CANCELLED, null -> null
    }
    return if (reason == null) "ההורדה נכשלה. נסו שוב" else "ההורדה נכשלה: $reason"
}
