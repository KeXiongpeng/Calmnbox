package com.calm.inbox.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.calm.inbox.core.database.dao.BriefDao
import com.calm.inbox.core.database.entity.BriefEntity
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
class BriefDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: BriefDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.briefDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndGetByDate() = runTest {
        val id = dao.insert(BriefEntity(date = "2026-09-16", content = "今日要点", createdAt = 100L))

        val saved = dao.getByDate("2026-09-16")

        assertThat(saved?.id).isEqualTo(id)
        assertThat(saved?.content).isEqualTo("今日要点")
        assertThat(dao.getByDate("2026-09-15")).isNull()
    }

    @Test
    fun observeAllSortsByDateDescending() = runTest {
        dao.insert(BriefEntity(date = "2026-09-14", content = "old", createdAt = 1L))
        dao.insert(BriefEntity(date = "2026-09-16", content = "new", createdAt = 2L))
        dao.insert(BriefEntity(date = "2026-09-15", content = "middle", createdAt = 3L))

        val dates = dao.observeAll().first().map { it.date }

        assertThat(dates).containsExactly("2026-09-16", "2026-09-15", "2026-09-14").inOrder()
    }
}
