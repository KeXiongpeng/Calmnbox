package com.calm.inbox.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineHolderTest {

    private class TestEngine : LlmEngine {
        private val _state = MutableStateFlow(EngineState.NOT_LOADED)
        override val state: StateFlow<EngineState> = _state
        var loadCalls = 0
        var releaseCalls = 0
        var lastModelDir: String? = null

        override suspend fun load(modelDir: String) {
            loadCalls++
            lastModelDir = modelDir
            _state.value = EngineState.READY
        }

        override suspend fun generateStream(prompt: String): Flow<String> = flowOf(prompt)

        override fun release() {
            releaseCalls++
            _state.value = EngineState.NOT_LOADED
        }
    }

    @Test
    fun acquireLoadsEngineOnceAndReturnsIt() = runTest {
        val engine = TestEngine()
        val holder = EngineHolder(engine, this)
        val first = holder.acquire("/models/qwen")
        val second = holder.acquire("/models/qwen")
        assertThat(first).isSameInstanceAs(engine)
        assertThat(second).isSameInstanceAs(engine)
        assertThat(engine.loadCalls).isEqualTo(1)
        assertThat(engine.lastModelDir).isEqualTo("/models/qwen")
    }

    @Test
    fun releaseNotCalledBeforeIdleTimeout() = runTest {
        val engine = TestEngine()
        val holder = EngineHolder(engine, this)
        holder.acquire("/models/qwen")
        runCurrent()
        advanceTimeBy(9 * 60 * 1000)
        assertThat(engine.releaseCalls).isEqualTo(0)
    }

    @Test
    fun releaseCalledAfterTenMinutesIdle() = runTest {
        val engine = TestEngine()
        val holder = EngineHolder(engine, this)
        holder.acquire("/models/qwen")
        runCurrent()
        advanceTimeBy(10 * 60 * 1000 + 1)
        assertThat(engine.releaseCalls).isEqualTo(1)
    }

    @Test
    fun notifyActivityResetsIdleTimer() = runTest {
        val engine = TestEngine()
        val holder = EngineHolder(engine, this)
        holder.acquire("/models/qwen")
        runCurrent()
        advanceTimeBy(9 * 60 * 1000)
        holder.notifyActivity()
        advanceTimeBy(599 * 1000)
        assertThat(engine.releaseCalls).isEqualTo(0)
        advanceTimeBy(1_001)
        assertThat(engine.releaseCalls).isEqualTo(1)
    }

    @Test
    fun customIdleTimeoutIsHonored() = runTest {
        val engine = TestEngine()
        val holder = EngineHolder(engine, this, idleTimeoutMs = 30_000)
        holder.acquire("/models/qwen")
        runCurrent()
        advanceTimeBy(29_999)
        assertThat(engine.releaseCalls).isEqualTo(0)
        advanceTimeBy(2)
        assertThat(engine.releaseCalls).isEqualTo(1)
    }
}
