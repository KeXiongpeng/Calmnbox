package com.calm.inbox.core.notifications

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationAccessMonitorTest {

    @Test
    fun emitsInitialStateThenChangeAndSkipsRepeatedValues() = runTest {
        var granted = false
        val monitor = NotificationAccessMonitor(
            isGranted = { granted },
            refreshInterval = Duration.ofSeconds(1)
        )

        monitor.observe().test {
            assertThat(awaitItem()).isFalse()
            granted = true
            advanceTimeBy(1_000)
            assertThat(awaitItem()).isTrue()
            advanceTimeBy(2_000)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun cancelledCollectorStopsPolling() = runTest {
        var granted = true
        var checks = 0
        val monitor = NotificationAccessMonitor(
            isGranted = {
                checks++
                granted
            },
            refreshInterval = Duration.ofSeconds(1)
        )
        val collected = mutableListOf<Boolean>()
        val collector = launch { monitor.observe().collect { collected += it } }

        runCurrent()
        assertThat(checks).isEqualTo(1)
        advanceTimeBy(1_001)
        assertThat(checks).isEqualTo(2)
        collector.cancel()
        advanceTimeBy(5_000)

        assertThat(checks).isEqualTo(2)
        assertThat(collected).containsExactly(true)
    }
}
