package com.calm.inbox.features.chat

import com.calm.inbox.core.model.EngineHolder
import com.calm.inbox.core.model.LlmEngine

class EngineReadiness(
    private val isModelReady: () -> Boolean,
    private val modelPath: () -> String,
    private val engine: LlmEngine,
    private val holder: EngineHolder
) {
    suspend fun prepare(): ChatPrecondition {
        if (!isModelReady()) return ChatPrecondition.NeedsModel
        return try {
            holder.acquire(modelPath())
            ChatPrecondition.Ready
        } catch (error: Throwable) {
            ChatPrecondition.EngineError
        }
    }

    fun releaseAfterFailure() {
        engine.release()
    }
}
