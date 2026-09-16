package com.calm.inbox.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val accessChecker: NotificationAccessChecker,
    latencyRecorder: LatencyRecorder
) : ViewModel() {

    val blacklist: StateFlow<Set<String>> = repository.blacklist
        .stateIn(viewModelScope, SharingStarted.Eagerly, setOf(SettingsRepository.SELF_PACKAGE))

    val noiseThreshold: StateFlow<Int> = repository.noiseThreshold
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsRepository.DEFAULT_NOISE_THRESHOLD)

    val firstTokenLatency: StateFlow<LatencyStats?> = latencyRecorder.stats()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val mutableNotificationAccessGranted = MutableStateFlow(accessChecker.isGranted())
    val notificationAccessGranted: StateFlow<Boolean> = mutableNotificationAccessGranted.asStateFlow()

    fun addPackage(rawPackage: String) {
        viewModelScope.launch { repository.addBlacklist(rawPackage) }
    }

    fun removePackage(pkg: String) {
        viewModelScope.launch { repository.removeBlacklist(pkg) }
    }

    fun setNoiseThreshold(value: Int) {
        viewModelScope.launch { repository.setNoiseThreshold(value) }
    }

    fun refreshPermission() {
        mutableNotificationAccessGranted.value = accessChecker.isGranted()
    }
}
