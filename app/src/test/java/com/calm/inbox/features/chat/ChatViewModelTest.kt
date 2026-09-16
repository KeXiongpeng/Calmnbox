package com.calm.inbox.features.chat

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.calm.inbox.core.database.dao.CategoryCount
import com.calm.inbox.core.database.dao.ChatMessageDao
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.ChatMessageEntity
import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.core.model.FakeLlmEngine
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun askClearsPreviousStreamAccumulatesChunksAndStoresDoneState() = runTest {
        val repository = FakeChatRepository(
            flow {
                emit(ChatEvent.Chunk("旧"))
                emit(ChatEvent.Done(emptyList()))
            },
            flow {
                emit(ChatEvent.Chunk("你"))
                emit(ChatEvent.Chunk("好"))
                emit(ChatEvent.Done(listOf(Citation(7L, "登录验证码"))))
            }
        )
        val viewModel = ChatViewModel(repository)

        viewModel.ask("第一问")
        assertThat(viewModel.streamingAnswer.value).isEqualTo("旧")
        assertThat(viewModel.citations.value).isEmpty()

        viewModel.ask("第二问")

        assertThat(repository.questions).containsExactly("第一问", "第二问").inOrder()
        assertThat(viewModel.streamingAnswer.value).isEqualTo("你好")
        assertThat(viewModel.citations.value)
            .containsExactly(Citation(7L, "登录验证码"))
        assertThat(viewModel.isStreaming.value).isFalse()
    }

    @Test
    fun historyIsExposedThroughStateIn() = runTest {
        val message = ChatMessageEntity(
            role = "assistant",
            content = "通知中没有找到",
            citationIds = "7",
            createdAt = 1_000L
        )
        val repository = FakeChatRepository()
        val viewModel = ChatViewModel(repository)

        viewModel.history.test {
            assertThat(awaitItem()).isEmpty()
            repository.history.value = listOf(message)
            assertThat(awaitItem()).containsExactly(message)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private class FakeNotificationDao : NotificationDao {
        override suspend fun insert(item: NotificationEntity): Long = 1L
        override suspend fun getByDateRange(start: Long, end: Long): List<NotificationEntity> =
            emptyList()
        override fun observeAll(): Flow<List<NotificationEntity>> = MutableStateFlow(emptyList())
        override suspend fun getUnclassified(limit: Int): List<NotificationEntity> = emptyList()
        override suspend fun updateClassification(
            id: Long,
            category: String,
            importance: Int,
            summary: String
        ) = Unit
        override suspend fun searchByKeyword(
            keyword: String,
            start: Long,
            end: Long
        ): List<NotificationEntity> = emptyList()
        override fun observeCountsByCategory(): Flow<List<CategoryCount>> =
            MutableStateFlow(emptyList())
    }

    private class FakeChatMessageDao : ChatMessageDao {
        val messages = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
        override suspend fun insert(message: ChatMessageEntity): Long {
            messages.value = messages.value + message
            return messages.value.size.toLong()
        }
        override fun observeAll(): Flow<List<ChatMessageEntity>> = messages
        override suspend fun clear() {
            messages.value = emptyList()
        }
    }

    private class FakeChatRepository(
        vararg answers: Flow<ChatEvent>
    ) : ChatRepository(
        FakeLlmEngine(),
        FakeNotificationDao(),
        FakeChatMessageDao(),
        Clock.systemUTC()
    ) {
        val questions = mutableListOf<String>()
        val history = MutableStateFlow<List<ChatMessageEntity>>(emptyList())
        private val remaining = ArrayDeque(answers.toList())

        override suspend fun ask(question: String): Flow<ChatEvent> {
            questions += question
            return remaining.removeFirst()
        }

        override fun history(): Flow<List<ChatMessageEntity>> = history
    }
}
