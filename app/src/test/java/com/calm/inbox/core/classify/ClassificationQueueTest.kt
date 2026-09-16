package com.calm.inbox.core.classify

import com.calm.inbox.core.database.dao.CategoryCount
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.NotificationEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClassificationQueueTest {

    private data class UpdateRecord(
        val id: Long,
        val category: String,
        val importance: Int,
        val summary: String,
    )

    /** 内存版 NotificationDao，只实现本测试用到的行为。 */
    private class FakeNotificationDao : NotificationDao {
        val stored = mutableListOf<NotificationEntity>()
        val updates = mutableListOf<UpdateRecord>()

        override suspend fun insert(item: NotificationEntity): Long {
            if (stored.any { it.digest == item.digest }) return -1L
            val id = (stored.size + 1).toLong()
            stored += item.copy(id = id)
            return id
        }

        override suspend fun getByDateRange(start: Long, end: Long): List<NotificationEntity> =
            stored.filter { it.postedAt in start..end }

        override fun observeAll(): Flow<List<NotificationEntity>> =
            MutableStateFlow(stored.toList())

        override suspend fun getUnclassified(limit: Int): List<NotificationEntity> =
            stored.filter { it.category == "UNCATEGORIZED" }.take(limit)

        override suspend fun updateClassification(
            id: Long,
            category: String,
            importance: Int,
            summary: String,
        ) {
            updates += UpdateRecord(id, category, importance, summary)
            val index = stored.indexOfFirst { it.id == id }
            if (index >= 0) {
                stored[index] = stored[index].copy(
                    category = category, importance = importance, summary = summary
                )
            }
        }

        override suspend fun searchByKeyword(
            keyword: String,
            start: Long,
            end: Long,
        ): List<NotificationEntity> = emptyList()

        override fun observeCountsByCategory(): Flow<List<CategoryCount>> =
            MutableStateFlow(emptyList())
    }

    private fun item(id: Long) = NotificationEntity(
        id = id,
        packageName = "unknown.pkg",
        appName = "App$id",
        title = "标题$id",
        text = "内容$id",
        postedAt = 1_700_000_000_000 + id,
        digest = "digest-$id",
    )

    /** 规则表为空 + 引擎 null → 全部确定性降级为 UNCATEGORIZED/1。 */
    private fun newClassifier() = HybridClassifier(RuleEngine(emptyMap()), null)

    @Test
    fun `攒够 10 条立即冲刷并写库`() = runTest {
        val dao = FakeNotificationDao()
        val queue = ClassificationQueue(newClassifier(), dao, this)

        repeat(9) { queue.offer(item(it.toLong())) }
        runCurrent()
        assertThat(dao.updates).isEmpty()

        queue.offer(item(9))
        advanceUntilIdle()

        assertThat(dao.updates).hasSize(10)
        assertThat(queue.pendingCount).isEqualTo(0)
        dao.updates.forEach {
            assertThat(it.category).isEqualTo("UNCATEGORIZED")
            assertThat(it.importance).isEqualTo(1)
        }
    }

    @Test
    fun `不足阈值 5 分钟后自动冲刷`() = runTest {
        val dao = FakeNotificationDao()
        val queue = ClassificationQueue(newClassifier(), dao, this)

        queue.offer(item(1))
        advanceTimeBy(ClassificationQueue.DEFAULT_FLUSH_INTERVAL_MS - 1)
        runCurrent()
        assertThat(dao.updates).isEmpty()

        advanceTimeBy(1)
        advanceUntilIdle()

        assertThat(dao.updates).hasSize(1)
    }

    @Test
    fun `定时器从首条入队起算不被后续 offer 重置`() = runTest {
        val dao = FakeNotificationDao()
        val queue = ClassificationQueue(newClassifier(), dao, this)

        queue.offer(item(1))
        advanceTimeBy(4 * 60_000)
        queue.offer(item(2))
        advanceTimeBy(59_000)
        runCurrent()
        assertThat(dao.updates).isEmpty()

        advanceTimeBy(1_000)
        advanceUntilIdle()

        assertThat(dao.updates).hasSize(2)
    }

    @Test
    fun `冲刷后队列清空可继续攒批`() = runTest {
        val dao = FakeNotificationDao()
        val queue = ClassificationQueue(newClassifier(), dao, this)

        repeat(10) { queue.offer(item(it.toLong())) }
        advanceUntilIdle()
        assertThat(dao.updates).hasSize(10)

        queue.offer(item(100))
        assertThat(queue.pendingCount).isEqualTo(1)

        advanceTimeBy(ClassificationQueue.DEFAULT_FLUSH_INTERVAL_MS)
        advanceUntilIdle()

        assertThat(dao.updates).hasSize(11)
        assertThat(dao.updates.last().id).isEqualTo(100)
    }
}
