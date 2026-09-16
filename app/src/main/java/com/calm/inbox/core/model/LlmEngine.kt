package com.calm.inbox.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class EngineState { NOT_LOADED, LOADING, READY, ERROR }

interface LlmEngine {
    val state: StateFlow<EngineState>
    suspend fun load(modelDir: String)
    suspend fun generateStream(prompt: String): Flow<String>
    fun release()
}
