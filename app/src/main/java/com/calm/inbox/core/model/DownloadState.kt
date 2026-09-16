package com.calm.inbox.core.model

import kotlinx.coroutines.flow.Flow
import java.io.File

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data class Done(val modelDir: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

interface Downloader {
    fun download(url: String, dest: File): Flow<DownloadState>
}
