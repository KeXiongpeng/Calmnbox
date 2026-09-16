package com.calm.inbox.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calm.inbox.core.database.dao.CategoryCount
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.NotificationEntity
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
class NotificationDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NotificationDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.notificationDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun item(
        digest: String,
        postedAt: Long = 1_000L,
        title: String = "Title",
        text: String = "Text"
    ) = NotificationEntity(
        packageName = "com.example.app",
        appName = "Example",
        title = title,
        text = text,
        postedAt = postedAt,
        digest = digest
    )

    @Test
    fun insertIgnoresDuplicateDigest() = runTest {
        assertThat(dao.insert(item("a"))).isGreaterThan(0L)
        assertThat(dao.insert(item("a"))).isEqualTo(-1L)
        assertThat(dao.observeAll().first()).hasSize(1)
    }

    @Test
    fun getByDateRangeFiltersTimeAndSortsNewestFirst() = runTest {
        dao.insert(item("old", postedAt = 15L))
        dao.insert(item("new", postedAt = 20L))
        dao.insert(item("outside", postedAt = 21L))

        val result = dao.getByDateRange(start = 10L, end = 20L)

        assertThat(result.map { it.digest }).containsExactly("new", "old").inOrder()
    }

    @Test
    fun getUnclassifiedReturnsOldestFirst() = runTest {
        dao.insert(item("new", postedAt = 20L))
        dao.insert(item("old", postedAt = 10L))

        val result = dao.getUnclassified(limit = 10)

        assertThat(result.map { it.digest }).containsExactly("old", "new").inOrder()
    }

    @Test
    fun searchUsesTimeAndTitleOrText() = runTest {
        dao.insert(item("a", postedAt = 10L, title = "验证码", text = "123456"))
        dao.insert(item("b", postedAt = 20L, title = "营销", text = "验证码迟到了"))
        dao.insert(item("c", postedAt = 5L, title = "验证码", text = "old"))

        val result = dao.searchByKeyword("验证码", start = 10L, end = 20L)

        assertThat(result.map { it.digest }).containsExactly("b", "a").inOrder()
    }

    @Test
    fun updateClassificationAndCountCategories() = runTest {
        val id = dao.insert(item("a"))
        dao.updateClassification(id, "VERIFICATION", 5, "登录验证码")
        dao.insert(item("b"))

        val saved = dao.observeAll().first().single { it.id == id }
        assertThat(saved.category).isEqualTo("VERIFICATION")
        assertThat(saved.importance).isEqualTo(5)
        assertThat(saved.summary).isEqualTo("登录验证码")
        assertThat(dao.observeCountsByCategory().first()).containsExactly(
            CategoryCount("VERIFICATION", 1),
            CategoryCount("UNCATEGORIZED", 1)
        )
    }
}
