package com.calm.inbox.core.model

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeLlmEngineTest {

    @Test
    fun `初始状态为 NOT_LOADED`() {
        val engine = FakeLlmEngine(responses = listOf("ok"))

        assertThat(engine.state.value).isEqualTo(EngineState.NOT_LOADED)
        assertThat(engine.releaseCount).isEqualTo(0)
        assertThat(engine.receivedPrompts).isEmpty()
    }

    @Test
    fun `load 后进入 READY 并记录 modelDir`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("ok"))

        engine.load("/data/models/Qwen2.5-1.5B-Instruct-MNN")

        assertThat(engine.state.value).isEqualTo(EngineState.READY)
        assertThat(engine.loadedModelDirs).containsExactly("/data/models/Qwen2.5-1.5B-Instruct-MNN")
    }

    @Test
    fun `loadError 会被抛出`() = runTest {
        val engine = FakeLlmEngine()
        engine.loadError = RuntimeException("模型加载失败")

        val thrown = runCatching {
            engine.load("/data/models/Qwen2.5-1.5B-Instruct-MNN")
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `未 load 调用 generateStream 抛出 IllegalStateException`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("ok"))

        val thrown = runCatching {
            engine.generateStream("prompt")
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `流式输出的 chunk 拼接等于完整回复`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("0123456789ABCDEF"), chunkSize = 8)
        engine.load("/models")

        engine.generateStream("prompt").test {
            assertThat(awaitItem()).isEqualTo("01234567")
            assertThat(awaitItem()).isEqualTo("89ABCDEF")
            awaitComplete()
        }
        assertThat(engine.receivedPrompts).containsExactly("prompt")
    }

    @Test
    fun `连续调用依次消费回复序列`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("first", "second"), chunkSize = 8)
        engine.load("/models")

        engine.generateStream("p1").test {
            assertThat(awaitItem()).isEqualTo("first")
            awaitComplete()
        }
        engine.generateStream("p2").test {
            assertThat(awaitItem()).isEqualTo("second")
            awaitComplete()
        }
        assertThat(engine.receivedPrompts).containsExactly("p1", "p2").inOrder()
    }

    @Test
    fun `回复序列耗尽后重复最后一条`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("only"), chunkSize = 8)
        engine.load("/models")

        repeat(2) {
            engine.generateStream("p").test {
                assertThat(awaitItem()).isEqualTo("only")
                awaitComplete()
            }
        }
    }

    @Test
    fun `空回复列表输出空流`() = runTest {
        val engine = FakeLlmEngine(responses = emptyList())
        engine.load("/models")

        engine.generateStream("p").test {
            awaitComplete()
        }
    }

    @Test
    fun `release 后回到 NOT_LOADED 且计数递增`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("ok"))
        engine.load("/models")

        engine.release()
        engine.release()

        assertThat(engine.state.value).isEqualTo(EngineState.NOT_LOADED)
        assertThat(engine.releaseCount).isEqualTo(2)
    }

    @Test
    fun `generateError 在记录 prompt 后抛出`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("ok"))
        engine.load("/models")
        engine.generateError = RuntimeException("推理失败")

        val thrown = runCatching {
            engine.generateStream("prompt")
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
        assertThat(engine.receivedPrompts).containsExactly("prompt")
    }
}
