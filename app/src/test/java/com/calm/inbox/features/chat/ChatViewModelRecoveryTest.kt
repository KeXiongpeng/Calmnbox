package com.calm.inbox.features.chat

import androidx.lifecycle.SavedStateHandle
import com.calm.inbox.core.database.dao.CategoryCount
import com.calm.inbox.core.database.dao.ChatMessageDao
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.ChatMessageEntity
import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.core.model.EngineHolder
import com.calm.inbox.core.model.FakeLlmEngine
import com.calm.inbox.core.notifications.NotificationAccessMonitor
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatViewModelRecoveryTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun readiness(
        scope: CoroutineScope,
        engine: FakeLlmEngine,
        isModelReady: () -> Boolean
    ): EngineReadiness = EngineReadiness(
        isModelReady = isModelReady,
        modelPath = { "/models/qwen" },
        engine = engine,
        holder = EngineHolder(engine, scope)
    )

    @Test
    fun needsModelGuidesDownloadWithoutAskingRepository() = runTest {
        val engine = FakeLlmEngine()
        val repository = FailingRepository()
        val viewModel = ChatViewModel(
            repository = repository,
            readiness = readiness(backgroundScope, engine) { false },
            notificationAccessMonitor = NotificationAccessMonitor(isGranted = { true }),
        )

        viewModel.ask("我的验证码是多少")

        assertThat(repository.questions).isEmpty()
        assertThat(viewModel.uiState.value.precondition)
            .isEqualTo(ChatPrecondition.NeedsModel)
        assertThat(viewModel.uiState.value.userMessage)
            .isEqualTo("请先在设置页下载本地模型")
        assertThat(viewModel.isStreaming.value).isFalse()
    }

    @Test
    fun engineErrorShowsRetryMessageWithoutAskingRepository() = runTest {
        val engine = FakeLlmEngine()
        engine.loadError = RuntimeException("native load failed")
        val repository = FailingRepository()
        val viewModel = ChatViewModel(
            repository = repository,
            readiness = readiness(backgroundScope, engine) { true },
            notificationAccessMonitor = NotificationAccessMonitor(isGranted = { true }),
        )

        viewModel.ask("我的验证码是多少")

        assertThat(repository.questions).isEmpty()
        assertThat(viewModel.uiState.value.precondition)
            .isEqualTo(ChatPrecondition.EngineError)
        assertThat(viewModel.uiState.value.userMessage)
            .isEqualTo("模型加载失败，请重试")
    }

    @Test
    fun runtimeFailureReleasesEngineAndEndsStreaming() = runTest {
        val engine = FakeLlmEngine()
        engine.load("/models/qwen")
        val repository = FailingRepository(RuntimeException("stream failed"))
        val viewModel = ChatViewModel(
            repository = repository,
            readiness = readiness(backgroundScope, engine) { true },
            notificationAccessMonitor = NotificationAccessMonitor(isGranted = { true }),
        )

        viewModel.ask("我的验证码是多少")

        assertThat(engine.releaseCount).isEqualTo(1)
        assertThat(viewModel.isStreaming.value).isFalse()
        assertThat(viewModel.uiState.value.precondition)
            .isEqualTo(ChatPrecondition.EngineError)
        assertThat(viewModel.uiState.value.userMessage)
            .isEqualTo("本地模型已释放，可重试")
    }

    @Test
    fun outOfMemoryFailureIsCaughtAndReleasesEngine() = runTest {
        val engine = FakeLlmEngine()
        engine.load("/models/qwen")
        val repository = FailingRepository(OutOfMemoryError("native OOM"))
        val viewModel = ChatViewModel(
            repository = repository,
            readiness = readiness(backgroundScope, engine) { true },
            notificationAccessMonitor = NotificationAccessMonitor(isGranted = { true }),
        )

        viewModel.ask("我的验证码是多少")

        assertThat(engine.releaseCount).isEqualTo(1)
        assertThat(viewModel.isStreaming.value).isFalse()
        assertThat(viewModel.uiState.value.userMessage)
            .isEqualTo("本地模型已释放，可重试")
    }

    @Test
    fun successfulStreamClearsPreviousError() = runTest {
        val engine = FakeLlmEngine()
        var modelReady = false
        val repository = FailingRepository(
            answer = flow {
                emit(ChatEvent.Chunk("通知中没有找到"))
                emit(ChatEvent.Done(emptyList()))
            }
        )
        val viewModel = ChatViewModel(
            repository = repository,
            readiness = readiness(backgroundScope, engine) { modelReady },
            notificationAccessMonitor = NotificationAccessMonitor(isGranted = { true }),
        )

        viewModel.ask("我的验证码是多少")
        assertThat(viewModel.uiState.value.userMessage).isNotNull()

        modelReady = true
        viewModel.ask("我的验证码是多少")

        assertThat(viewModel.uiState.value.precondition)
            .isEqualTo(ChatPrecondition.Ready)
        assertThat(viewModel.uiState.value.userMessage).isNull()
        assertThat(viewModel.streamingAnswer.value).isEqualTo("通知中没有找到")
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

    private class FailingRepository(
        private val error: Throwable? = null,
        private val answer: Flow<ChatEvent> = flowOf()
    ) : ChatRepository(
        FakeLlmEngine(),
        FakeNotificationDao(),
        FakeChatMessageDao(),
        Clock.systemUTC()
    ) {
        val questions = mutableListOf<String>()

        override suspend fun ask(question: String): Flow<ChatEvent> {
            questions += question
            error?.let { throw it }
            return answer
        }

        override fun history(): Flow<List<ChatMessageEntity>> =
            MutableStateFlow(emptyList())
    }
}
