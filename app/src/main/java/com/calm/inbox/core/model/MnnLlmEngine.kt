package com.calm.inbox.core.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File

class MnnLlmEngine(
    private val nativeCreate: (String) -> Long = { path -> MnnNative.create(path) },
    private val nativeGenerate: (Long, String, MnnNative.StreamListener) -> Unit =
        { ptr, prompt, listener -> MnnNative.generate(ptr, prompt, listener) },
    private val nativeRelease: (Long) -> Unit = { ptr -> MnnNative.release(ptr) }
) : LlmEngine {

    private val _state = MutableStateFlow(EngineState.NOT_LOADED)
    override val state: StateFlow<EngineState> = _state

    private var ptr: Long = 0

    var firstTokenLatencyListener: ((Long) -> Unit)? = null

    override suspend fun load(modelDir: String) {
        if (_state.value == EngineState.READY) return
        _state.value = EngineState.LOADING
        try {
            val configPath = File(modelDir, "config.json").absolutePath
            ptr = nativeCreate(configPath)
            check(ptr != 0L) { "native create returned null pointer for $modelDir" }
            _state.value = EngineState.READY
        } catch (t: Throwable) {
            _state.value = EngineState.ERROR
            throw t
        }
    }

    override suspend fun generateStream(prompt: String): Flow<String> = callbackFlow {
        val currentPtr = ptr
        if (_state.value != EngineState.READY || currentPtr == 0L) {
            close(IllegalStateException("engine not READY"))
            return@callbackFlow
        }

        val startNanos = System.nanoTime()
        var firstToken = true
        launch(Dispatchers.Default) {
            try {
                nativeGenerate(currentPtr, prompt, object : MnnNative.StreamListener {
                    override fun onToken(token: String?): Boolean {
                        if (token == null) {
                            close()
                            return true
                        }
                        if (firstToken) {
                            firstToken = false
                            firstTokenLatencyListener?.invoke(
                                (System.nanoTime() - startNanos) / 1_000_000
                            )
                        }
                        return trySend(token).isSuccess
                    }
                })
                close()
            } catch (t: Throwable) {
                close(t)
            }
        }
        awaitClose { }
    }

    override fun release() {
        if (ptr != 0L) {
            nativeRelease(ptr)
            ptr = 0
        }
        _state.value = EngineState.NOT_LOADED
    }
}
