package com.mediaconverter.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.mediaconverter.app.data.DownloadEngine
import com.mediaconverter.app.data.DownloadWorker
import com.mediaconverter.app.data.MediaFormat
import com.mediaconverter.app.data.db.AppDatabase
import com.mediaconverter.app.data.db.DownloadEntity
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val url: String = "",
    val isValidUrl: Boolean = false,
    val isFetchingInfo: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadMessage: String? = null,
    val videoInfo: com.mediaconverter.app.data.VideoInfo? = null,
    val selectedFormat: MediaFormat = MediaFormat.MP4,
    val selectedQuality: String = "best", // Corresponds to VideoQuality or AudioQuality value
    val error: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val downloadEngine = DownloadEngine(application)
    private val database = AppDatabase.getInstance(application)
    private val workManager = WorkManager.getInstance(application)

    fun onUrlChange(url: String) {
        val isValid = DownloadEngine.isValidUrl(url)
        _uiState.value = _uiState.value.copy(
            url = url,
            isValidUrl = isValid,
            error = null
        )
        if (isValid) {
            fetchVideoInfo(url)
        } else {
            _uiState.value = _uiState.value.copy(videoInfo = null)
        }
    }

    private fun fetchVideoInfo(url: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFetchingInfo = true, error = null)
            val result = downloadEngine.getVideoInfo(url)
            
            if (result.isSuccess) {
                _uiState.value = _uiState.value.copy(
                    isFetchingInfo = false,
                    videoInfo = result.getOrNull()
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isFetchingInfo = false,
                    error = "לא ניתן למצוא מידע על הסרטון" // "Cannot find video info"
                )
            }
        }
    }

    fun onFormatChange(format: MediaFormat) {
        // Set a reasonable default quality when format changes
        val defaultQuality = if (format == MediaFormat.MP4) "best" else "192"
        _uiState.value = _uiState.value.copy(
            selectedFormat = format,
            selectedQuality = defaultQuality
        )
    }

    fun onQualityChange(quality: String) {
        _uiState.value = _uiState.value.copy(selectedQuality = quality)
    }

    fun startDownload() {
        val currentState = _uiState.value
        if (!currentState.isValidUrl) return

        viewModelScope.launch {
            // 1. Create DB entry
            val entity = DownloadEntity(
                url = currentState.url,
                title = currentState.videoInfo?.title ?: "סרטון",
                platform = currentState.videoInfo?.platform ?: "unknown",
                format = currentState.selectedFormat.value,
                quality = currentState.selectedQuality,
                thumbnailUrl = currentState.videoInfo?.thumbnailUrl ?: "",
                status = "pending"
            )
            
            val downloadId = database.downloadDao().insertDownload(entity)

            // 2. Enqueue WorkManager task
            val inputData = Data.Builder()
                .putString(DownloadWorker.KEY_URL, currentState.url)
                .putString(DownloadWorker.KEY_FORMAT, currentState.selectedFormat.value)
                .putString(DownloadWorker.KEY_QUALITY, currentState.selectedQuality)
                .putLong(DownloadWorker.KEY_DOWNLOAD_ID, downloadId)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .build()

            workManager.enqueue(downloadRequest)
            
            // Show feedback to user instead of resetting the screen
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(getApplication(), "ההורדה החלה! בדוק את ההתראות", Toast.LENGTH_LONG).show()
            }
            
            // Observe the download status
            workManager.getWorkInfoByIdFlow(downloadRequest.id).collect { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        androidx.work.WorkInfo.State.ENQUEUED -> {
                            _uiState.value = _uiState.value.copy(
                                isDownloading = true,
                                downloadMessage = "ממתין... (Waiting...)"
                            )
                        }
                        androidx.work.WorkInfo.State.RUNNING -> {
                            _uiState.value = _uiState.value.copy(
                                isDownloading = true,
                                downloadMessage = "מוריד... (Downloading...)"
                            )
                        }
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            _uiState.value = _uiState.value.copy(
                                isDownloading = false,
                                downloadMessage = "ההורדה הושלמה בהצלחה! (Success!)"
                            )
                        }
                        androidx.work.WorkInfo.State.FAILED -> {
                            _uiState.value = _uiState.value.copy(
                                isDownloading = false,
                                downloadMessage = "שגיאה בהורדה (Error downloading)"
                            )
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}
