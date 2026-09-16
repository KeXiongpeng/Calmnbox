package com.calm.inbox.features.brief

import com.calm.inbox.core.database.dao.CategoryCount
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.core.model.FakeLlmEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BriefGeneratorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val clock: Clock = Clock.fixed(Instant.parse("2026-09-15T14:00:00Z"), zone)  // 北京时间 22:00
    private val date: LocalDate = LocalDate.of(2026, 9, 15)

    private class FakeNotificationDao : NotificationDao {
        val stored = mutableListOf<NotificationEntity>()
        var capturedStart = 0L
        var capturedEnd = 0L

        override suspend fun insert(item: NotificationEntity): Long {
            if (stored.any { it.digest == item.digest }) return -1L
            val id = (stored.size + 1).toLong()
            stored += item.copy(id = id)
            return id
        }

        override suspend fun getByDateRange(start: Long, end: Long): List<NotificationEntity> {
            capturedStart = start
            capturedEnd = end
            return stored.filter { it.postedAt in start until end }
        }

        override fun observeAll(): Flow<List<NotificationEntity>> = MutableStateFlow(stored.toList())

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

    private fun item(
        id: Long,
        importance: Int,
        category: String = "WORK",
        postedAtHour: Long = 10,
    ) = NotificationEntity(
        id = id,
        packageName = "com.example.app",
        appName = "App$id",
        title = "标题$id",
        text = "内容$id",
        postedAt = date.atStartOfDay(zone).toInstant().toEpochMilli() + postedAtHour * 3_600_000,
        category = category,
        importance = importance,
        summary = "摘要$id",
        digest = "digest-$id",
    )

    @Test
    fun `模型未就绪时生成降级模板简报`() = runTest {
        val dao = FakeNotificationDao().apply {
            stored += item(1, 5)
            stored += item(2, 2, category = "MARKETING")
        }
        val generator = BriefGenerator(engine = null, dao = dao, clock = clock)

        val brief = generator.generateFor(date)

        assertThat(brief.date).isEqualTo("2026-09-15")
        assertThat(brief.createdAt).isEqualTo(clock.millis())
        assertThat(brief.content).contains("今日 Top5 重要事项")
        assertThat(brief.content).contains("[WORK] App1：标题1")
        assertThat(brief.content).contains("分类统计")
        assertThat(brief.content).contains("WORK：1 条")
        assertThat(brief.content).contains("MARKETING：1 条")
    }

    @Test
    fun `Top5 按 importance 降序同分按时间降序且只取前五`() = runTest {
        val dao = FakeNotificationDao().apply {
            stored += item(1, importance = 3)
            stored += item(2, importance = 5)
            stored += item(3, importance = 4)
            stored += item(4, importance = 4, postedAtHour = 12)   // 与 id=3 同分但更晚 → 排前
            stored += item(5, importance = 2)
            stored += item(6, importance = 5, postedAtHour = 20)   // 与 id=2 同分但更晚 → 全场第一
            stored += item(7, importance = 1)                       // 第 7 名被截掉
        }
        val generator = BriefGenerator(engine = null, dao = dao, clock = clock)

        val content = generator.generateFor(date).content

        val topSection = content.substringAfter("重要事项\n").substringBefore("\n### 分类统计")
        val orderedIds = Regex("App(\\d)：").findAll(topSection).map { it.groupValues[1] }.toList()
        assertThat(orderedIds).containsExactly("6", "2", "4", "3", "1").inOrder()
    }

    @Test
    fun `模型就绪时简报为 LLM 输出且 prompt 包含日期与通知`() = runTest {
        val dao = FakeNotificationDao().apply { stored += item(1, 5) }
        val fake = FakeLlmEngine(responses = listOf("## 2026-09-15 简报\n\n- 今日验证码 1 条"))
        fake.load("/models")
        val generator = BriefGenerator(engine = fake, dao = dao, clock = clock)

        val brief = generator.generateFor(date)

        assertThat(brief.content).isEqualTo("## 2026-09-15 简报\n\n- 今日验证码 1 条")
        assertThat(fake.receivedPrompts).hasSize(1)
        assertThat(fake.receivedPrompts[0]).contains("2026-09-15")
        assertThat(fake.receivedPrompts[0]).contains("标题1")
    }

    @Test
    fun `LLM 输出空白时降级模板`() = runTest {
        val dao = FakeNotificationDao().apply { stored += item(1, 5) }
        val fake = FakeLlmEngine(responses = listOf("   "))
        fake.load("/models")
        val generator = BriefGenerator(engine = fake, dao = dao, clock = clock)

        val brief = generator.generateFor(date)

        assertThat(brief.content).contains("今日 Top5 重要事项")
    }

    @Test
    fun `LLM 抛异常时降级模板`() = runTest {
        val dao = FakeNotificationDao().apply { stored += item(1, 5) }
        val fake = FakeLlmEngine(responses = listOf("x"))
        fake.load("/models")
        fake.generateError = RuntimeException("推理失败")
        val generator = BriefGenerator(engine = fake, dao = dao, clock = clock)

        val brief = generator.generateFor(date)

        assertThat(brief.content).contains("今日 Top5 重要事项")
    }

    @Test
    fun `当日无通知时生成空简报`() = runTest {
        val generator = BriefGenerator(engine = null, dao = FakeNotificationDao(), clock = clock)

        val brief = generator.generateFor(date)

        assertThat(brief.content).contains("今天没有新通知")
    }

    @Test
    fun `查询区间为当日零点到次日零点`() = runTest {
        val dao = FakeNotificationDao()
        val generator = BriefGenerator(engine = null, dao = dao, clock = clock)

        generator.generateFor(date)

        assertThat(dao.capturedStart)
            .isEqualTo(date.atStartOfDay(zone).toInstant().toEpochMilli())
        assertThat(dao.capturedEnd)
            .isEqualTo(date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }
}
