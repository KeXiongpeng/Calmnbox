package com.calm.inbox.features.chat

import com.calm.inbox.core.model.EngineHolder
import com.calm.inbox.core.model.FakeLlmEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineReadinessTest {

    @Test
    fun missingModelReturnsNeedsModelWithoutAcquiringEngine() = runTest {
        val engine = FakeLlmEngine()
        val holder = EngineHolder(engine, backgroundScope)
        val readiness = EngineReadiness(
            isModelReady = { false },
            modelPath = { "/models/qwen" },
            engine = engine,
            holder = holder
        )

        val result = readiness.prepare()

        assertThat(result).isEqualTo(ChatPrecondition.NeedsModel)
        runCurrent()
        advanceTimeBy(10 * 60 * 1000 + 1)
        assertThat(engine.loadedModelDirs).isEmpty()
        assertThat(engine.releaseCount).isEqualTo(0)
    }

    @Test
    fun readyModelAcquiresEngineAndResetsIdleTimer() = runTest {
        val engine = FakeLlmEngine()
        engine.load("/models/qwen")
        val holder = EngineHolder(engine, backgroundScope)
        val readiness = EngineReadiness(
            isModelReady = { true },
            modelPath = { "/models/qwen" },
            engine = engine,
            holder = holder
        )

        assertThat(readiness.prepare()).isEqualTo(ChatPrecondition.Ready)
        runCurrent()
        advanceTimeBy(9 * 60 * 1000)
        assertThat(readiness.prepare()).isEqualTo(ChatPrecondition.Ready)
        advanceTimeBy(61_000)
        assertThat(engine.releaseCount).isEqualTo(0)
        advanceTimeBy(10 * 60 * 1000 - 61_000 + 1_001)
        assertThat(engine.releaseCount).isEqualTo(1)
    }

    @Test
    fun loadFailureReturnsEngineError() = runTest {
        val engine = FakeLlmEngine()
        engine.loadError = RuntimeException("native load failed")
        val holder = EngineHolder(engine, backgroundScope)
        val readiness = EngineReadiness(
            isModelReady = { true },
            modelPath = { "/models/qwen" },
            engine = engine,
            holder = holder
        )

        val result = readiness.prepare()

        assertThat(result).isEqualTo(ChatPrecondition.EngineError)
    }

    @Test
    fun releaseAfterFailureReleasesReadyEngineOnce() = runTest {
        val engine = FakeLlmEngine()
        engine.load("/models/qwen")
        val readiness = EngineReadiness(
            isModelReady = { true },
            modelPath = { "/models/qwen" },
            engine = engine,
            holder = EngineHolder(engine, backgroundScope)
        )

        readiness.releaseAfterFailure()

        assertThat(engine.releaseCount).isEqualTo(1)
    }

    @Test
    fun releaseAfterFailureAlsoReleasesNotLoadedEngine() = runTest {
        val engine = FakeLlmEngine()
        val readiness = EngineReadiness(
            isModelReady = { true },
            modelPath = { "/models/qwen" },
            engine = engine,
            holder = EngineHolder(engine, backgroundScope)
        )

        readiness.releaseAfterFailure()

        assertThat(engine.releaseCount).isEqualTo(1)
    }
}
