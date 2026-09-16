package com.calm.inbox.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calm.inbox.core.notifications.NotificationAccessMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    notificationAccessMonitor: NotificationAccessMonitor,
    latencyRecorder: LatencyRecorder
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
