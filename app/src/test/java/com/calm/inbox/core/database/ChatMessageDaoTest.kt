package com.calm.inbox.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calm.inbox.core.database.dao.ChatMessageDao
import com.calm.inbox.core.database.entity.ChatMessageEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatMessageDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: ChatMessageDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.chatMessageDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeAllSortsByCreatedAtAscending() = runTest {
        dao.insert(ChatMessageEntity(role = "assistant", content = "answer", createdAt = 20L))
        dao.insert(ChatMessageEntity(role = "user", content = "question", createdAt = 10L))

        val messages = dao.observeAll().first()

        assertThat(messages.map { it.role }).containsExactly("user", "assistant").inOrder()
    }

    @Test
    fun citationIdsArePersistedAsString() = runTest {
        val id = dao.insert(
            ChatMessageEntity(
                role = "assistant",
                content = "验证码是 123456",
                citationIds = "12,34",
                createdAt = 30L
            )
        )

        val saved = dao.observeAll().first().single { it.id == id }

        assertThat(saved.citationIds).isEqualTo("12,34")
    }

    @Test
    fun clearRemovesAllMessages() = runTest {
        dao.insert(ChatMessageEntity(role = "user", content = "question", createdAt = 10L))
        dao.insert(ChatMessageEntity(role = "assistant", content = "answer", createdAt = 20L))

        dao.clear()

        assertThat(dao.observeAll().first()).isEmpty()
    }
}
