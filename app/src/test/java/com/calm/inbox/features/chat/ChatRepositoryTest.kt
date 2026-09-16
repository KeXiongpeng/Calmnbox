package com.calm.inbox.features.chat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calm.inbox.core.database.AppDatabase
import com.calm.inbox.core.database.dao.ChatMessageDao
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.core.model.FakeLlmEngine
import com.google.common.truth.Truth.assertThat
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var notificationDao: NotificationDao
    private lateinit var chatDao: ChatMessageDao
    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 16, 15, 30)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        notificationDao = database.notificationDao()
        chatDao = database.chatMessageDao()
    }

    @After
    fun tearDown() {
        database.close()
        TimeZone.setDefault(originalTimeZone)
    }

    private fun clock(): Clock =
        Clock.fixed(Instant.from(now.atZone(zone)), zone)

    private fun verification(): NotificationEntity = NotificationEntity(
        packageName = "com.example.app",
        appName = "Example",
        title = "登录验证码",
        text = "验证码 123456",
        postedAt = LocalDateTime.of(2026, 9, 16, 15, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli(),
        digest = "verification"
    )

    @Test
    fun askStreamsAnswerPersistsHistoryAndCitations() = runTest {
        val notificationId = notificationDao.insert(verification())
        val engine = FakeLlmEngine(listOf("验证码是 123456"), chunkSize = 3)
        engine.load("model")
        val repository = ChatRepository(engine, notificationDao, chatDao, clock())

        val events = repository.ask("我的验证码是多少").toList()

        assertThat(events.first()).isInstanceOf(ChatEvent.Chunk::class.java)
        assertThat(events.last()).isEqualTo(
            ChatEvent.Done(listOf(Citation(notificationId, "登录验证码")))
        )
        val chunks = events.filterIsInstance<ChatEvent.Chunk>()
        assertThat(chunks.map { it.text }).containsExactly("验证码", "是 1", "234", "56").inOrder()
        val saved = chatDao.observeAll().first()
        assertThat(saved.map { it.role }).containsExactly("user", "assistant").inOrder()
        assertThat(saved.last().citationIds).isEqualTo(notificationId.toString())
        assertThat(saved.last().content).contains("123456")
        assertThat(engine.receivedPrompts.single()).contains("仅依据以下通知内容回答")
        assertThat(engine.receivedPrompts.single())
            .contains("[" + notificationId + "] Example|登录验证码|验证码 123456")
    }

    @Test
    fun askWithoutMatchesStillStreamsAndPersistsEmptyCitations() = runTest {
        val engine = FakeLlmEngine(listOf("通知中没有找到"))
        engine.load("model")
        val repository = ChatRepository(engine, notificationDao, chatDao, clock())

        val events = repository.ask("where is unknownword").toList()

        assertThat(events.last()).isEqualTo(ChatEvent.Done(emptyList()))
        val saved = chatDao.observeAll().first()
        assertThat(saved.map { it.role }).containsExactly("user", "assistant").inOrder()
        assertThat(saved.last().citationIds).isEmpty()
        assertThat(saved.last().content).isEqualTo("通知中没有找到")
    }

    @Test
    fun historyReadsDaoWithoutCallingEngine() = runTest {
        val engine = FakeLlmEngine()
        val repository = ChatRepository(engine, notificationDao, chatDao, clock())
        val saved = com.calm.inbox.core.database.entity.ChatMessageEntity(role = "user", content = "????????", createdAt = 1_000L)
        val savedId = chatDao.insert(saved)

        val history = repository.history().first()

        assertThat(history).containsExactly(saved.copy(id = savedId))
        assertThat(engine.receivedPrompts).isEmpty()
    }

    @Test
    fun blankQuestionFailsBeforeDatabaseOrEngineAccess() = runTest {
        val engine = FakeLlmEngine()
        val repository = ChatRepository(engine, notificationDao, chatDao, clock())
        var thrown: IllegalArgumentException? = null

        try {
            repository.ask("   ")
        } catch (error: IllegalArgumentException) {
            thrown = error
        }

        assertThat(thrown).isNotNull()
        assertThat(chatDao.observeAll().first()).isEmpty()
        assertThat(engine.receivedPrompts).isEmpty()
    }
}
