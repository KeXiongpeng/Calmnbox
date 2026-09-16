package com.calm.inbox.features.brief

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.calm.inbox.core.database.dao.CategoryCount
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.BriefEntity
import com.calm.inbox.core.database.dao.BriefDao
import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.core.notifications.BriefNotifier
import com.calm.inbox.core.model.FakeLlmEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BriefWorkerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T14:00:00Z"), zone)

    private class FakeBriefDao : BriefDao {
        val briefs = mutableListOf<BriefEntity>()
        override suspend fun insert(brief: BriefEntity): Long {
            briefs += brief
            return briefs.size.toLong()
        }

        override fun observeAll(): Flow<List<BriefEntity>> = MutableStateFlow(briefs.toList())

        override suspend fun getByDate(date: String): BriefEntity? =
            briefs.firstOrNull { it.date == date }
    }

    private class FakeNotificationDao : NotificationDao {
        val stored = mutableListOf<NotificationEntity>()
        var failOnQuery = false

        override suspend fun insert(item: NotificationEntity): Long = stored.size + 1L

        override suspend fun getByDateRange(start: Long, end: Long): List<NotificationEntity> {
            if (failOnQuery) throw RuntimeException("数据库损坏")
            return stored.filter { it.postedAt in start until end }
        }

        override fun observeAll(): Flow<List<NotificationEntity>> = MutableStateFlow(emptyList())

        override suspend fun getUnclassified(limit: Int): List<NotificationEntity> = emptyList()

        override suspend fun updateClassification(
            id: Long,
            category: String,
            importance: Int,
            summary: String,
        ) = Unit

        override suspend fun searchByKeyword(
            keyword: String,
            start: Long,
            end: Long,
        ): List<NotificationEntity> = emptyList()

        override fun observeCountsByCategory(): Flow<List<CategoryCount>> =
            MutableStateFlow(emptyList())
    }

    private class RecordingNotifier : BriefNotifier(ApplicationProvider.getApplicationContext()) {
        val pushed = mutableListOf<BriefEntity>()
        override fun push(brief: BriefEntity) {
            pushed += brief
        }
    }

    private fun notification(id: Long, importance: Int) = NotificationEntity(
        id = id,
        packageName = "com.example.app",
        appName = "App$id",
        title = "标题$id",
        text = "内容$id",
        postedAt = LocalDate.of(2026, 9, 15).atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000,
        category = "WORK",
        importance = importance,
        summary = "",
        digest = "digest-$id",
    )

    private fun buildWorker(
        briefDao: FakeBriefDao,
        notificationDao: FakeNotificationDao,
        notifier: RecordingNotifier,
    ): BriefWorker {
        val context: Context = ApplicationProvider.getApplicationContext()
        val generator = BriefGenerator(FakeLlmEngine(), notificationDao, clock)
        return TestListenableWorkerBuilder<BriefWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker? = BriefWorker(
                    appContext, workerParameters, generator, briefDao, notifier, clock,
                )
            })
            .build() as BriefWorker
    }

    @Test
    fun `首次运行生成简报入库并推送`() = runTest {
        val briefDao = FakeBriefDao()
        val notificationDao = FakeNotificationDao().apply { stored += notification(1, 5) }
        val notifier = RecordingNotifier()

        val result = buildWorker(briefDao, notificationDao, notifier).doWork()

        assertThat(result).isEqualTo(androidx.work.ListenableWorker.Result.success())
        assertThat(briefDao.briefs).hasSize(1)
        assertThat(briefDao.briefs[0].date).isEqualTo("2026-09-15")
        assertThat(notifier.pushed).hasSize(1)
    }

    @Test
    fun `当日已有简报时幂等跳过`() = runTest {
        val briefDao = FakeBriefDao().apply {
            briefs += BriefEntity(date = "2026-09-15", content = "已生成", createdAt = 1L)
        }
        val notificationDao = FakeNotificationDao().apply { stored += notification(1, 5) }
        val notifier = RecordingNotifier()

        val result = buildWorker(briefDao, notificationDao, notifier).doWork()

        assertThat(result).isEqualTo(androidx.work.ListenableWorker.Result.success())
        assertThat(briefDao.briefs).hasSize(1)
        assertThat(briefDao.briefs[0].content).isEqualTo("已生成")
        assertThat(notifier.pushed).isEmpty()
    }

    @Test
    fun `生成过程抛异常时返回 retry`() = runTest {
        val briefDao = FakeBriefDao()
        val notificationDao = FakeNotificationDao().apply { failOnQuery = true }
        val notifier = RecordingNotifier()

        val result = buildWorker(briefDao, notificationDao, notifier).doWork()

        assertThat(result).isEqualTo(androidx.work.ListenableWorker.Result.retry())
        assertThat(briefDao.briefs).isEmpty()
        assertThat(notifier.pushed).isEmpty()
    }
}
