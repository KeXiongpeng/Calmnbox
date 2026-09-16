package com.calm.inbox.features.chat

import com.calm.inbox.core.database.dao.ChatMessageDao
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.ChatMessageEntity
import com.calm.inbox.core.model.LlmEngine
import java.time.Clock
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class Citation(val notificationId: Long, val title: String)

sealed interface ChatEvent {
    data class Chunk(val text: String) : ChatEvent
    data class Done(val citations: List<Citation>) : ChatEvent
}

@Singleton
open class ChatRepository @Inject constructor(
    private val engine: LlmEngine,
    private val notificationDao: NotificationDao,
    private val chatDao: ChatMessageDao,
    private val clock: Clock
) {
    open suspend fun ask(question: String): Flow<ChatEvent> {
        val normalized = question.trim()
        require(normalized.isNotEmpty()) { "question must not be blank" }

        val now = LocalDateTime.now(clock)
        val today = TimeQueryParser.fullDay(now.toLocalDate())
        val notifications = NotificationRetriever(notificationDao)
            .search(normalized, now, defaultRange = today)
        val citations = notifications.map { Citation(it.id, it.title) }
        chatDao.insert(
            ChatMessageEntity(
                role = "user",
                content = normalized,
                createdAt = clock.millis()
            )
        )

        return flow {
            val answer = StringBuilder()
            engine.generateStream(ChatPrompts.build(normalized, notifications))
                .collect { chunk ->
                    answer.append(chunk)
                    emit(ChatEvent.Chunk(chunk))
                }
            chatDao.insert(
                ChatMessageEntity(
                    role = "assistant",
                    content = answer.toString(),
                    citationIds = citations.joinToString(",") { it.notificationId.toString() },
                    createdAt = clock.millis()
                )
            )
            emit(ChatEvent.Done(citations))
        }
    }

    open fun history(): Flow<List<ChatMessageEntity>> = chatDao.observeAll()
}
