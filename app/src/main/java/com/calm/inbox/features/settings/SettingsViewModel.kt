package com.calm.inbox.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calm.inbox.core.model.DownloadState
import com.calm.inbox.core.model.ModelManager
import com.calm.inbox.core.notifications.NotificationAccessMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ModelUiState(
    val isReady: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    notificationAccessMonitor: NotificationAccessMonitor,
    latencyRecorder: LatencyRecorder,
    private val modelManager: ModelManager
) : ViewModel() {

    val blacklist: StateFlow<Set<String>> = repository.blacklist
        .stateIn(viewModelScope, SharingStarted.Eagerly, setOf(SettingsRepository.SELF_PACKAGE))

    val noiseThreshold: StateFlow<Int> = repository.noiseThreshold
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SettingsRepository.DEFAULT_NOISE_THRESHOLD
        )

    val firstTokenLatency: StateFlow<LatencyStats?> = latencyRecorder.stats()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val notificationAccessGranted: StateFlow<Boolean> = notificationAccessMonitor
        .observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val mutableModelState = MutableStateFlow(
        ModelUiState(isReady = modelManager.isModelReady())
    )
    val modelState: StateFlow<ModelUiState> = mutableModelState.asStateFlow()



    fun downloadModel() {
        val current = mutableModelState.value
        if (current.isDownloading || current.isReady) return

        viewModelScope.launch {
            mutableModelState.value = current.copy(
                isDownloading = true,
                progress = 0f,
                error = null
            )
            try {
                modelManager.downloadModel().collect { state ->
                    when (state) {
                        DownloadState.Idle -> Unit
                        is DownloadState.Downloading -> {
                            mutableModelState.value = mutableModelState.value.copy(
                                progress = state.progress.coerceIn(0f, 1f)
                            )
                        }
                        is DownloadState.Done -> {
                            mutableModelState.value = ModelUiState(
                                isReady = true,
                                progress = 1f
                            )
                        }
                        is DownloadState.Failed -> {
                            mutableModelState.value = mutableModelState.value.copy(
                                isDownloading = false,
                                error = state.message
                            )
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableModelState.value = mutableModelState.value.copy(
                    isDownloading = false,
                    error = error.message ?: "\u6a21\u578b\u4e0b\u8f7d\u5931\u8d25"
                )
            }
        }
    }

    fun deleteModel() {
        if (mutableModelState.value.isDownloading) return

        viewModelScope.launch {
            try {
                modelManager.deleteModel()
                mutableModelState.value = ModelUiState()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableModelState.value = mutableModelState.value.copy(
                    error = error.message ?: "\u6a21\u578b\u4e0b\u8f7d\u5931\u8d25"
                )
            }
        }
    }

    fun addPackage(rawPackage: String) {
        viewModelScope.launch { repository.addBlacklist(rawPackage) }
    }

    fun removePackage(pkg: String) {
        viewModelScope.launch { repository.removeBlacklist(pkg) }
    }

    fun setNoiseThreshold(value: Int) {
        viewModelScope.launch { repository.setNoiseThreshold(value) }
    }
}
