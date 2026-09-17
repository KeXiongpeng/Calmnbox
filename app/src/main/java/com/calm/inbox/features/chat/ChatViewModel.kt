package com.calm.inbox.features.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calm.inbox.core.database.entity.ChatMessageEntity
import com.calm.inbox.core.notifications.NotificationAccessMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

data class ChatUiState(
    val precondition: ChatPrecondition = ChatPrecondition.Ready,
    val userMessage: String? = null,
    val isStreaming: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val readiness: EngineReadiness,
    notificationAccessMonitor: NotificationAccessMonitor
) : ViewModel() {

    val history: StateFlow<List<ChatMessageEntity>> = repository.history()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _streamingAnswer = MutableStateFlow("")
    val streamingAnswer: StateFlow<String> = _streamingAnswer.asStateFlow()

    private val _citations = MutableStateFlow<List<Citation>>(emptyList())
    val citations: StateFlow<List<Citation>> = _citations.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val notificationAccessGranted: StateFlow<Boolean> = notificationAccessMonitor
        .observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private var lastQuestion: String? = null

    fun ask(question: String) {
        if (_isStreaming.value) return

        lastQuestion = question
        viewModelScope.launch {
            val generationStartedAtNano = System.nanoTime()
            var chunkCount = 0
            _isStreaming.value = true
            _streamingAnswer.value = ""
            _citations.value = emptyList()
            updateUiState(isStreaming = true, userMessage = null)
            try {
                when (readiness.prepare()) {
                    ChatPrecondition.NeedsModel -> {
                        updateUiState(
                            precondition = ChatPrecondition.NeedsModel,
                            userMessage = "请先在设置页下载本地模型",
                            isStreaming = false
                        )
                        return@launch
                    }
                    ChatPrecondition.EngineError -> {
                        updateUiState(
                            precondition = ChatPrecondition.EngineError,
                            userMessage = "模型加载失败，请重试",
                            isStreaming = false
                        )
                        return@launch
                    }
                    ChatPrecondition.Ready -> Unit
                }

                repository.ask(question).collect { event ->
                    when (event) {
                        is ChatEvent.Chunk -> {
                            chunkCount++
                            _streamingAnswer.value += event.text
                        }
                        is ChatEvent.Done -> {
                            _citations.value = event.citations
                            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime() - generationStartedAtNano
                            )
                            Log.i(
                                ChatTelemetry.LOG_TAG,
                                ChatTelemetry.generationLog(chunkCount, elapsedMs)
                            )
                        }
                    }
                }
                updateUiState(
                    precondition = ChatPrecondition.Ready,
                    userMessage = null,
                    isStreaming = false
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                readiness.releaseAfterFailure()
                updateUiState(
                    precondition = ChatPrecondition.EngineError,
                    userMessage = "本地模型已释放，可重试",
                    isStreaming = false
                )
            } finally {
                _isStreaming.value = false
            }
        }
    }

    fun retry() {
        lastQuestion?.let(::ask)
    }

    private fun updateUiState(
        precondition: ChatPrecondition = _uiState.value.precondition,
        userMessage: String? = _uiState.value.userMessage,
        isStreaming: Boolean = _uiState.value.isStreaming
    ) {
        _uiState.value = ChatUiState(
            precondition = precondition,
            userMessage = userMessage,
            isStreaming = isStreaming
        )
    }
}
