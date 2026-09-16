package com.calm.inbox.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * LlmEngine 测试替身：可注入预设回复序列（按调用次序消费，耗尽后重复最后一条），
 * 按 chunkSize 切片模拟逐 token 流。Task 11/12 与 W3 复用。
 */
class FakeLlmEngine(
    private val responses: List<String> = emptyList(),
    private val chunkSize: Int = 8,
) : LlmEngine {

    private val _state = MutableStateFlow(EngineState.NOT_LOADED)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    val receivedPrompts = mutableListOf<String>()
    val loadedModelDirs = mutableListOf<String>()
    var releaseCount = 0
        private set

    /** load 时抛出，模拟模型加载失败。 */
    var loadError: RuntimeException? = null

    /** generateStream 时抛出（先记录 prompt 再抛，模拟真实引擎收到请求后失败）。 */
    var generateError: RuntimeException? = null

    /** true 时未 load 就 generate 抛 IllegalStateException，与 MnnLlmEngine 行为一致。 */
    var requireLoaded: Boolean = true

    private var responseIndex = 0

    override suspend fun load(modelDir: String) {
        loadError?.let { throw it }
        loadedModelDirs += modelDir
        _state.value = EngineState.READY
    }

    override suspend fun generateStream(prompt: String): Flow<String> {
        if (requireLoaded && _state.value != EngineState.READY) {
            throw IllegalStateException("engine not loaded")
        }
        receivedPrompts += prompt
        generateError?.let { throw it }
        val response = if (responses.isEmpty()) {
            ""
        } else {
            responses[responseIndex.coerceAtMost(responses.lastIndex)]
        }
        if (responseIndex < responses.lastIndex) {
            responseIndex++
        }
        return flow {
            response.chunked(chunkSize.coerceAtLeast(1)).forEach { chunk ->
                emit(chunk)
            }
        }
    }

    override fun release() {
        releaseCount++
        _state.value = EngineState.NOT_LOADED
    }
}
