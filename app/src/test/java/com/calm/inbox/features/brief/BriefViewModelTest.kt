package com.calm.inbox.features.brief

import com.calm.inbox.core.database.dao.BriefDao
import com.calm.inbox.core.database.entity.BriefEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BriefViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeBriefDao : BriefDao {
        private val briefs = MutableStateFlow<List<BriefEntity>>(emptyList())

        override suspend fun insert(brief: BriefEntity): Long {
            briefs.value = briefs.value + brief
            return brief.id
        }

        override fun observeAll(): Flow<List<BriefEntity>> = briefs

        override suspend fun getByDate(date: String): BriefEntity? =
            briefs.value.firstOrNull { it.date == date }
    }

    @Test
    fun `briefs starts with empty list`() = runTest {
        val viewModel = BriefViewModel(FakeBriefDao())

        viewModel.briefs.test {
            assertThat(awaitItem()).isEmpty()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `briefs emits latest persisted briefs`() = runTest {
        val dao = FakeBriefDao()
        val viewModel = BriefViewModel(dao)

        viewModel.briefs.test {
            assertThat(awaitItem()).isEmpty()

            val brief = BriefEntity(date = "2026-09-15", content = "今日 12 条通知，重要 3 条", createdAt = 1_000L)
            dao.insert(brief)

            assertThat(awaitItem()).containsExactly(brief)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
