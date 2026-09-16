package com.calm.inbox.core.model

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class MnnLlmEngineTest {

    private class NativeFake(createResult: Long = 1L) {
        val createCalls = AtomicInteger()
        val releases = AtomicInteger()
        var onGenerate: ((MnnNative.StreamListener) -> Unit)? = null
        val create: (String) -> Long = {
            createCalls.incrementAndGet()
            createResult
        }
        val generate: (Long, String, MnnNative.StreamListener) -> Unit = { _, _, listener ->
            onGenerate?.invoke(listener) ?: listener.onToken(null)
        }
        val release: (Long) -> Unit = { releases.incrementAndGet() }
    }

    @Test
    fun loadTransitionsToReadyAndIsIdempotent() = runTest {
        val fake = NativeFake()
        val engine = MnnLlmEngine(fake.create, fake.generate, fake.release)
        engine.load("/models/qwen")
        assertThat(engine.state.value).isEqualTo(EngineState.READY)
        engine.load("/models/qwen")
        assertThat(fake.createCalls.get()).isEqualTo(1)
    }

    @Test
    fun loadFailureSetsErrorAndRethrows() = runTest {
        val boom: (String) -> Long = { throw RuntimeException("native boom") }
        val fake = NativeFake()
        val engine = MnnLlmEngine(boom, fake.generate, fake.release)
        val thrown = runCatching { engine.load("/models/qwen") }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
        assertThat(engine.state.value).isEqualTo(EngineState.ERROR)
    }

    @Test
    fun loadWithZeroPointerSetsError() = runTest {
        val fake = NativeFake(createResult = 0L)
        val engine = MnnLlmEngine(fake.create, fake.generate, fake.release)
        runCatching { engine.load("/models/qwen") }
        assertThat(engine.state.value).isEqualTo(EngineState.ERROR)
    }

    @Test
    fun generateStreamFailsWhenNotReady() = runTest(UnconfinedTestDispatcher()) {
        val fake = NativeFake()
        val engine = MnnLlmEngine(fake.create, fake.generate, fake.release)
        engine.generateStream("prompt").test {
            assertThat(awaitError()).isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun generateStreamEmitsTokensAndCompletesOnNull() = runTest(UnconfinedTestDispatcher()) {
        val fake = NativeFake()
        fake.onGenerate = { listener ->
            listener.onToken("验")
            listener.onToken("证")
            listener.onToken("码")
            listener.onToken(null)
        }
        val engine = MnnLlmEngine(fake.create, fake.generate, fake.release)
        engine.load("/models/qwen")
        engine.generateStream("prompt").test {
            assertThat(awaitItem()).isEqualTo("验")
            assertThat(awaitItem()).isEqualTo("证")
            assertThat(awaitItem()).isEqualTo("码")
            awaitComplete()
        }
    }

    @Test
    fun firstTokenLatencyListenerInvokedExactlyOncePerStream() = runTest(UnconfinedTestDispatcher()) {
        val fake = NativeFake()
        fake.onGenerate = { listener ->
            listener.onToken("a")
            listener.onToken("b")
            listener.onToken(null)
        }
        val engine = MnnLlmEngine(fake.create, fake.generate, fake.release)
        engine.load("/models/qwen")
        var calls = 0
        engine.firstTokenLatencyListener = { calls++ }
        engine.generateStream("prompt").toList()
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun releaseResetsStateAndNativeReleaseCalled() = runTest(UnconfinedTestDispatcher()) {
        val fake = NativeFake()
        val engine = MnnLlmEngine(fake.create, fake.generate, fake.release)
        engine.load("/models/qwen")
        engine.release()
        assertThat(engine.state.value).isEqualTo(EngineState.NOT_LOADED)
        assertThat(fake.releases.get()).isEqualTo(1)
        engine.generateStream("prompt").test {
            assertThat(awaitError()).isInstanceOf(IllegalStateException::class.java)
        }
    }
}
