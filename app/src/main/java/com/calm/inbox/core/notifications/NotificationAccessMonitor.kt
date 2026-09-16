package com.calm.inbox.core.notifications

import java.time.Duration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

class NotificationAccessMonitor(
    private val isGranted: () -> Boolean,
    private val refreshInterval: Duration = Duration.ofSeconds(1)
) {
    fun observe(): Flow<Boolean> = flow {
        while (currentCoroutineContext().isActive) {
            emit(isGranted())
            delay(refreshInterval.toMillis())
        }
    }.distinctUntilChanged()
}
