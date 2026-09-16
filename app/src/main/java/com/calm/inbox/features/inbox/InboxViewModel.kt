package com.calm.inbox.features.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.features.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class InboxFilter(val label: String) {
    ALL("全部"),
    IMPORTANT("重要"),
    NOISY("降噪")
}

data class NoiseGroup(
    val category: String,
    val count: Int,
    val latestTitle: String,
    val notifications: List<NotificationEntity>
)

data class InboxState(
    val selectedFilter: InboxFilter = InboxFilter.ALL,
    val important: List<NotificationEntity> = emptyList(),
    val noiseGroups: List<NoiseGroup> = emptyList(),
    val expandedNoiseCategories: Set<String> = emptySet(),
    val isLoading: Boolean = true
)

@HiltViewModel
class InboxViewModel @Inject constructor(
    notificationDao: NotificationDao,
    settingsRepository: SettingsRepository
) : ViewModel() {

    private val selectedFilter = MutableStateFlow(InboxFilter.ALL)
    private val expandedNoiseCategories = MutableStateFlow(emptySet<String>())

    val state: StateFlow<InboxState> = combine(
        notificationDao.observeAll(),
        settingsRepository.noiseThreshold,
        selectedFilter,
        expandedNoiseCategories
    ) { notifications, threshold, filter, expanded ->
        buildState(notifications, threshold, filter, expanded)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, InboxState())

    fun select(filter: InboxFilter) {
        selectedFilter.value = filter
    }

    fun toggleNoise(category: String) {
        expandedNoiseCategories.value = expandedNoiseCategories.value.let {
            if (category in it) it - category else it + category
        }
    }

    private fun buildState(
        notifications: List<NotificationEntity>,
        threshold: Int,
        filter: InboxFilter,
        expanded: Set<String>
    ): InboxState {
        val important = notifications
            .filter { it.category != "MARKETING" && it.importance > threshold }
            .sortedWith(compareByDescending<NotificationEntity> { it.importance }.thenByDescending { it.postedAt })

        val noise = notifications
            .filter { it.category == "MARKETING" || it.importance <= threshold }
            .sortedByDescending { it.postedAt }

        val noiseGroups = noise
            .groupBy { it.category }
            .map { (category, items) ->
                NoiseGroup(
                    category = category,
                    count = items.size,
                    latestTitle = items.first().title.ifBlank { items.first().text },
                    notifications = items
                )
            }
            .sortedByDescending { group -> group.notifications.maxOf { it.postedAt } }

        return InboxState(
            selectedFilter = filter,
            important = if (filter == InboxFilter.NOISY) emptyList() else important,
            noiseGroups = if (filter == InboxFilter.IMPORTANT) emptyList() else noiseGroups,
            expandedNoiseCategories = expanded,
            isLoading = false
        )
    }
}
