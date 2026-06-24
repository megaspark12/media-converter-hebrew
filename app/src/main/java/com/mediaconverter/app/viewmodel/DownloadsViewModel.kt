package com.mediaconverter.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mediaconverter.app.data.DownloadRepository
import com.mediaconverter.app.data.db.DownloadEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = DownloadRepository(application)
    
    val downloads: StateFlow<List<DownloadEntity>> = repository.getAllDownloads()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
        
    fun deleteDownloadRecord(download: DownloadEntity) {
        viewModelScope.launch {
            repository.deleteDownload(download)
            // Optional: delete actual file as well
            if (download.filePath.isNotEmpty()) {
                val file = java.io.File(download.filePath)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }
    
    fun clearHistory() {
        viewModelScope.launch {
            repository.deleteAll()
        }
    }
}
