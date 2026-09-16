package com.calm.inbox.features.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calm.inbox.core.database.entity.ChatMessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository
) : ViewModel() {

    val history: StateFlow<List<ChatMessageEntity>> = repository.history()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _streamingAnswer = MutableStateFlow("")
    val streamingAnswer: StateFlow<String> = _streamingAnswer.asStateFlow()

    private val _citations = MutableStateFlow<List<Citation>>(emptyList())
    val citations: StateFlow<List<Citation>> = _citations.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    fun ask(question: String) {
        if (_isStreaming.value) return

        viewModelScope.launch {
            _isStreaming.value = true
            _streamingAnswer.value = ""
            _citations.value = emptyList()
            try {
                repository.ask(question).collect { event ->
                    when (event) {
                        is ChatEvent.Chunk -> _streamingAnswer.value += event.text
                        is ChatEvent.Done -> _citations.value = event.citations
                    }
                }
            } finally {
                _isStreaming.value = false
            }
        }
    }
}
