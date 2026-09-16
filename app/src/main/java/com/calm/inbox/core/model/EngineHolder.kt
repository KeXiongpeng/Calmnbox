package com.calm.inbox.core.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EngineHolder(
    private val engine: LlmEngine,
    private val scope: CoroutineScope,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS
) {
    private var idleJob: Job? = null
    private var idleGeneration = 0

    suspend fun acquire(modelDir: String): LlmEngine {
        if (engine.state.value != EngineState.READY) {
            engine.load(modelDir)
        }
        scheduleIdleRelease()
        return engine
    }

    fun notifyActivity() {
        if (engine.state.value == EngineState.READY) {
            scheduleIdleRelease()
        }
    }

    private fun scheduleIdleRelease() {
        val generation = ++idleGeneration
        val existingJob = idleJob
        if (existingJob?.isActive == true) return

        idleJob = scope.launch {
            var observedGeneration = generation
            var elapsedMs = 0L
            while (true) {
                delay(IDLE_TICK_MS)
                elapsedMs += IDLE_TICK_MS

                if (observedGeneration != idleGeneration) {
                    observedGeneration = idleGeneration
                    elapsedMs = 0L
                }

                if (elapsedMs >= idleTimeoutMs) {
                    if (observedGeneration == idleGeneration) {
                        engine.release()
                    }
                    break
                }
            }
        }
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 10L * 60 * 1000
        private const val IDLE_TICK_MS = 1_000L
    }
}
