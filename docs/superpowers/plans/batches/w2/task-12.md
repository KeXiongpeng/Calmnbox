### Task 12: 每日简报（BriefWorker 22:00 + 简报生成 + 本地推送）

> **设计决定（对齐骨架契约与设计文档 §3/§6）**
> - **BriefGenerator 契约逐字**：构造参数 `(engine: LlmEngine?, dao: NotificationDao, clock: java.time.Clock)`；`generateFor(date)` 只**生成并返回 `BriefEntity`（id=0 不入库）**，入库与推送由 `BriefWorker` 完成（契约注释「仍入库存推送」描述整条链路；`BriefGenerator` 只持有 `NotificationDao`，无 `BriefDao`）。
> - **降级简报（模型未就绪 / LLM 空白输出 / 引擎异常）**：纯统计模板——「今日 Top5 重要事项」（`importance` 降序，同分按 `postedAt` 降序；全部未打标时按 `postedAt` 降序）+「分类统计」（按条数降序）。当日无通知 → 「今天没有新通知」一句话简报。
> - **BriefWorker（类名固定）幂等**：`briefDao.getByDate(今日)` 已存在 → 直接 `success`（退避重试不重复生成/推送）；任一环节抛异常 → `Result.retry()`（走 WorkManager 指数退避）。
> - **调度**：`BriefScheduler.schedule(context)` 在 `CalmInboxApp.onCreate` 执行——`PeriodicWorkRequest`（24h）+ `initialDelayToNext22(now)`（纯函数：下一个**严格未来**的 22:00；恰为 22:00 时排到明天，避免与刚结束的当日任务重叠）+ `ExistingPeriodicWorkPolicy.KEEP`（重启 App 不重排）+ 退避 `EXPONENTIAL, 10 分钟`。周期任务随系统存在分钟级漂移，MVP 接受（W3 打磨任务可换日历对齐）。
> - **推送**：channel id **`"daily_brief"`**（骨架固定值）；`minSdk 26` 无需版本判断建 channel；targetSdk 33+ 未授予 `POST_NOTIFICATIONS` 时 `notify` 抛 `SecurityException` → 捕获后静默跳过（简报仍在应用内可见，权限引导归 W1 设置页）。
> - ⚠️ 版本目录别名沿用 W1 Task 1 的命名惯例；若 W1 的 `libs.versions.toml` 命名与下文不同（如 `libs.hilt.work`），只改别名拼写，**坐标与版本号照抄下文**（hilt-work 1.2.0 / hilt-compiler 1.2.0 / work-testing 2.9.1），其余不变。

**前置依赖（已就绪，直接使用）**
- `BriefDao` / `BriefEntity` / `NotificationDao`（W1 Task 2 契约；`AppModule` 已提供 DAO）。
- `LlmEngine` / `EngineState`（Task 9）与 `FakeLlmEngine`（Task 10 测试替身）。
- `androidx.core:core-ktx`（W1 基线，`NotificationCompat` 来源）。
- `app/src/main/java/com/calm/inbox/CalmInboxApp.kt`（W1 Task 1，骨架固定类名 `CalmInboxApp`）。

## Files

**Create:**
- `app/src/main/java/com/calm/inbox/features/brief/BriefPrompts.kt`
- `app/src/main/java/com/calm/inbox/features/brief/BriefGenerator.kt`
- `app/src/main/java/com/calm/inbox/features/brief/BriefWorker.kt`
- `app/src/main/java/com/calm/inbox/features/brief/BriefScheduler.kt`
- `app/src/main/java/com/calm/inbox/core/notifications/BriefNotifier.kt`
- `app/src/main/java/com/calm/inbox/di/BriefModule.kt`

**Modify:**
- `app/src/main/AndroidManifest.xml`（移除 WorkManager 默认初始化器，启用按需初始化）
- `app/src/main/java/com/calm/inbox/CalmInboxApp.kt`（实现 `Configuration.Provider` + 启动时调度简报）
- `gradle/libs.versions.toml` + `app/build.gradle.kts`（补充 hilt-work / hilt-compiler / work-testing 三个坐标，⚠️ 别名以 W1 为准）

**Test:**
- `app/src/test/java/com/calm/inbox/features/brief/BriefGeneratorTest.kt`
- `app/src/test/java/com/calm/inbox/features/brief/BriefSchedulerTest.kt`
- `app/src/test/java/com/calm/inbox/features/brief/BriefWorkerTest.kt`（Robolectric + work-testing）

## Interfaces

**Produces（骨架契约，逐字遵守）：**

```kotlin
// app/src/main/java/com/calm/inbox/features/brief/BriefGenerator.kt
package com.calm.inbox.features.brief

import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.BriefEntity
import com.calm.inbox.core.model.LlmEngine
import java.time.LocalDate

class BriefGenerator(
    private val engine: LlmEngine?,
    private val dao: NotificationDao,
    private val clock: java.time.Clock,     // 必须可注入
) {
    suspend fun generateFor(date: LocalDate): BriefEntity
    // 模型未就绪 → 纯统计模板降级简报（Top5 重要度排序 + 分类计数），仍入库存推送
}
```

**本任务附加产出（不在骨架契约内）：**
- `BriefPrompts.buildBriefPrompt(date: LocalDate, items: List<NotificationEntity>): String`、`BriefPrompts.buildFallbackBrief(date: LocalDate, items: List<NotificationEntity>): String`、常量 `TOP_N = 5`、`MAX_ITEMS_IN_PROMPT = 50`。
- `BriefWorker`（`@HiltWorker`，`UNIQUE_WORK_NAME = "daily_brief_worker"`）。
- `BriefScheduler.initialDelayToNext22(now: LocalDateTime): Duration`（纯函数）、`BriefScheduler.schedule(context)`。
- `BriefNotifier`（`open class`，`open fun push(brief: BriefEntity)`，`CHANNEL_ID = "daily_brief"`，测试可覆写）。
- `BriefModule` 提供 `Clock`、`BriefGenerator`、`BriefNotifier`。

**Consumes（契约逐字引用）：** `NotificationDao.getByDateRange(start, end)`、`BriefDao.insert/getByDate`、`BriefEntity(date: String, content: String, createdAt: Long)`、`LlmEngine.state/generateStream`。

## Step 1: 写失败测试

创建 `app/src/test/java/com/calm/inbox/features/brief/BriefGeneratorTest.kt`（纯 JUnit4 + `runTest`，时钟用 `Clock.fixed`）：

```kotlin
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
```

创建 `app/src/test/java/com/calm/inbox/features/brief/BriefSchedulerTest.kt`（纯函数测试）：

```kotlin
package com.calm.inbox.features.brief

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class BriefSchedulerTest {

    @Test
    fun `21 点 59 分延迟 1 分钟`() {
        val now = LocalDateTime.of(2026, 9, 15, 21, 59)

        assertThat(BriefScheduler.initialDelayToNext22(now)).isEqualTo(Duration.ofMinutes(1))
    }

    @Test
    fun `恰为 22 点延迟到明天 22 点`() {
        val now = LocalDateTime.of(2026, 9, 15, 22, 0)

        assertThat(BriefScheduler.initialDelayToNext22(now)).isEqualTo(Duration.ofHours(24))
    }

    @Test
    fun `22 点 01 分延迟 23 小时 59 分`() {
        val now = LocalDateTime.of(2026, 9, 15, 22, 1)

        assertThat(BriefScheduler.initialDelayToNext22(now))
            .isEqualTo(Duration.ofHours(23).plusMinutes(59))
    }

    @Test
    fun `上午 10 点延迟 12 小时`() {
        val now = LocalDateTime.of(2026, 9, 15, 10, 0)

        assertThat(BriefScheduler.initialDelayToNext22(now)).isEqualTo(Duration.ofHours(12))
    }
}
```

创建 `app/src/test/java/com/calm/inbox/features/brief/BriefWorkerTest.kt`（Robolectric + `work-testing` 的 `TestListenableWorkerBuilder`）：

```kotlin
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
```

## Step 2: 确认测试失败

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.brief.BriefGeneratorTest" --tests "com.calm.inbox.features.brief.BriefSchedulerTest" --tests "com.calm.inbox.features.brief.BriefWorkerTest"
```

**预期失败**：编译错误 `Unresolved reference: BriefGenerator` / `BriefScheduler` / `BriefWorker` / `BriefNotifier`（及 work-testing 未引入时 `Unresolved reference: TestListenableWorkerBuilder`）。必须看到编译失败输出后再进入 Step 3。

## Step 3: 最小实现

先补依赖。`gradle/libs.versions.toml` 的 `[versions]` 追加（已存在则跳过）：

```toml
androidx-hilt-work = "1.2.0"
androidx-hilt-compiler = "1.2.0"
androidx-work-testing = "2.9.1"
```

`[libraries]` 追加（已存在则跳过）：

```toml
androidx-hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "androidx-hilt-work" }
androidx-hilt-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "androidx-hilt-compiler" }
androidx-work-testing = { group = "androidx.work", name = "work-testing", version.ref = "androidx-work-testing" }
```

`app/build.gradle.kts` 的 `dependencies` 块追加（⚠️ 别名以 W1 命名惯例为准）：

```kotlin
implementation(libs.androidx.hilt.work)
kapt(libs.androidx.hilt.compiler)
testImplementation(libs.androidx.work.testing)
```

创建 `app/src/main/java/com/calm/inbox/features/brief/BriefPrompts.kt`：

```kotlin
package com.calm.inbox.features.brief

import com.calm.inbox.core.database.entity.NotificationEntity
import java.time.LocalDate

object BriefPrompts {

    const val TOP_N = 5
    const val MAX_ITEMS_IN_PROMPT = 50

    fun buildBriefPrompt(date: LocalDate, items: List<NotificationEntity>): String {
        require(items.isNotEmpty()) { "items must not be empty" }
        val lines = rank(items).take(MAX_ITEMS_IN_PROMPT).joinToString(separator = "\n") { item ->
            "- [${item.importance}分][${item.category}] ${item.appName}：${item.title} ${item.summary}".trim()
        }
        return buildString {
            append("你是手机通知管家。根据下面的已分类通知生成 ")
            append(date)
            append(" 的每日简报，Markdown 纯文本，只输出简报正文，包含两部分：\n")
            append("## 今日 Top").append(TOP_N).append(" 重要事项（每条一行：[分类] 应用：标题（摘要），按重要度降序）\n")
            append("## 分类统计（每类一行：类别：N 条，按数量降序）\n\n")
            append("通知（已按重要度降序）：\n")
            append(lines)
            append("\n\n现在输出简报：")
        }
    }

    /** 模型未就绪时的纯统计降级模板。 */
    fun buildFallbackBrief(date: LocalDate, items: List<NotificationEntity>): String {
        if (items.isEmpty()) {
            return "## $date 简报\n\n今天没有新通知。"
        }
        val top = rank(items).take(TOP_N)
        val counts = items.groupingBy { it.category }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenComparing { it.key })
            .joinToString(separator = "\n") { "- ${it.key}：${it.value} 条" }
        return buildString {
            append("## ").append(date).append(" 简报\n\n")
            append("### 今日 Top").append(TOP_N).append(" 重要事项\n")
            top.forEachIndexed { index, item ->
                append(index + 1).append(". [").append(item.category).append("] ")
                    .append(item.appName).append("：").append(item.title)
                if (item.summary.isNotEmpty()) {
                    append("（").append(item.summary).append("）")
                }
                append("\n")
            }
            append("\n### 分类统计\n").append(counts).append("\n")
        }
    }

    /** 已打标条目按 importance 降序、同分按 postedAt 降序；全部未打标时按 postedAt 降序。 */
    private fun rank(items: List<NotificationEntity>): List<NotificationEntity> {
        val classified = items.filter { it.importance > 0 }
            .sortedWith(
                compareByDescending<NotificationEntity> { it.importance }.thenByDescending { it.postedAt }
            )
        return if (classified.isNotEmpty()) {
            classified
        } else {
            items.sortedByDescending { it.postedAt }
        }
    }
}
```

创建 `app/src/main/java/com/calm/inbox/features/brief/BriefGenerator.kt`（契约签名逐字）：

```kotlin
package com.calm.inbox.features.brief

import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.BriefEntity
import com.calm.inbox.core.model.EngineState
import com.calm.inbox.core.model.LlmEngine
import kotlinx.coroutines.flow.toList
import java.time.Clock
import java.time.LocalDate

class BriefGenerator(
    private val engine: LlmEngine?,
    private val dao: NotificationDao,
    private val clock: Clock,     // 必须可注入
) {
    suspend fun generateFor(date: LocalDate): BriefEntity {
        val start = date.atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val items = dao.getByDateRange(start, end)
        val content = generateContent(date, items)
        return BriefEntity(date = date.toString(), content = content, createdAt = clock.millis())
        // 模型未就绪 → 纯统计模板降级简报（Top5 重要度排序 + 分类计数），仍入库存推送（由 BriefWorker 执行）
    }

    private suspend fun generateContent(date: LocalDate, items: List<NotificationEntity>): String {
        if (items.isEmpty()) return BriefPrompts.buildFallbackBrief(date, items)
        val activeEngine = engine?.takeIf { it.state.value == EngineState.READY }
        if (activeEngine != null) {
            try {
                val prompt = BriefPrompts.buildBriefPrompt(date, items)
                val raw = activeEngine.generateStream(prompt).toList().joinToString(separator = "")
                if (raw.isNotBlank()) return raw.trim()
            } catch (e: Exception) {
                // 引擎异常 → 降级模板
            }
        }
        return BriefPrompts.buildFallbackBrief(date, items)
    }
}

private typealias NotificationEntityAlias = com.calm.inbox.core.database.entity.NotificationEntity
```

创建 `app/src/main/java/com/calm/inbox/core/notifications/BriefNotifier.kt`：

```kotlin
package com.calm.inbox.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.calm.inbox.core.database.entity.BriefEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 每日简报本地推送。open 供测试覆写。 */
@Singleton
open class BriefNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    open fun push(brief: BriefEntity) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "每日简报",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("CalmInbox 简报 ${brief.date}")
            .setContentText(
                brief.content.lineSequence().firstOrNull { it.isNotBlank() } ?: "今日简报已生成"
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(brief.content.take(500)))
            .setContentIntent(
                android.app.PendingIntent.getActivity(
                    context,
                    0,
                    context.packageManager.getLaunchIntentForPackage(context.packageName),
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // targetSdk 33+ 未授予 POST_NOTIFICATIONS：跳过推送，简报在应用内仍可见
        }
    }

    companion object {
        const val CHANNEL_ID = "daily_brief"
        const val NOTIFICATION_ID = 2001
    }
}
```

创建 `app/src/main/java/com/calm/inbox/features/brief/BriefWorker.kt`：

```kotlin
package com.calm.inbox.features.brief

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.calm.inbox.core.database.dao.BriefDao
import com.calm.inbox.core.notifications.BriefNotifier
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Clock
import java.time.LocalDate

@HiltWorker
class BriefWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val generator: BriefGenerator,
    private val briefDao: BriefDao,
    private val notifier: BriefNotifier,
    private val clock: Clock,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val date = LocalDate.now(clock)
        return try {
            if (briefDao.getByDate(date.toString()) != null) {
                return Result.success()   // 幂等：退避重试不重复生成与推送
            }
            val brief = generator.generateFor(date)
            briefDao.insert(brief)
            notifier.push(brief)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "daily_brief_worker"
    }
}
```

创建 `app/src/main/java/com/calm/inbox/features/brief/BriefScheduler.kt`：

```kotlin
package com.calm.inbox.features.brief

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object BriefScheduler {

    /** 下一个严格未来的 22:00（now == 22:00 整点时排到明天，避免与当日任务重叠）。 */
    fun initialDelayToNext22(now: LocalDateTime): Duration {
        val today22 = now.toLocalDate().atTime(22, 0)
        val target = if (now.isBefore(today22)) today22 else today22.plusDays(1)
        return Duration.between(now, target)
    }

    /** 在 Application.onCreate 调用；KEEP 策略保证重复调用不重置周期。 */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<BriefWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelayToNext22(LocalDateTime.now()).toMillis(), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            BriefWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
```

创建 `app/src/main/java/com/calm/inbox/di/BriefModule.kt`：

```kotlin
package com.calm.inbox.di

import android.content.Context
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.model.LlmEngine
import com.calm.inbox.core.notifications.BriefNotifier
import com.calm.inbox.features.brief.BriefGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BriefModule {

    @Provides
    fun provideClock(): Clock = Clock.systemDefaultZone()

    @Provides
    @Singleton
    fun provideBriefGenerator(
        engine: dagger.Lazy<LlmEngine>,
        dao: NotificationDao,
        clock: Clock,
    ): BriefGenerator = BriefGenerator(engine.get(), dao, clock)

    @Provides
    @Singleton
    fun provideBriefNotifier(@ApplicationContext context: Context): BriefNotifier =
        BriefNotifier(context)
}
```

修改 `app/src/main/java/com/calm/inbox/CalmInboxApp.kt`（保留 `@HiltAndroidApp`，追加接口实现与调度）：

```kotlin
package com.calm.inbox

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.calm.inbox.features.brief.BriefScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CalmInboxApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        BriefScheduler.schedule(this)
    }
}
```

修改 `app/src/main/AndroidManifest.xml`：确认 `<manifest>` 根元素含 `xmlns:tools="http://schemas.android.com/tools"`，并在 `<application>` 内追加（停用 WorkManager 默认初始化，改用上文的按需初始化）：

```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

## Step 4: 确认测试通过

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.brief.BriefGeneratorTest" --tests "com.calm.inbox.features.brief.BriefSchedulerTest" --tests "com.calm.inbox.features.brief.BriefWorkerTest"
```

**预期**：`BUILD SUCCESSFUL`，Generator 7 用例 + Scheduler 4 用例 + Worker 3 用例全部通过。若 Worker 用例报 `WorkManager is not initialized properly`，检查 Manifest 是否遗漏 `tools:node="remove"` 的默认初始化器条目；若 `doWork()` 空指针，检查自定义 `WorkerFactory` 是否返回了 `BriefWorker` 实例。

## Step 5: 提交

```powershell
git add app/src/main/java/com/calm/inbox/features/brief/ app/src/main/java/com/calm/inbox/core/notifications/BriefNotifier.kt app/src/main/java/com/calm/inbox/di/BriefModule.kt app/src/main/java/com/calm/inbox/CalmInboxApp.kt app/src/main/AndroidManifest.xml gradle/libs.versions.toml app/build.gradle.kts app/src/test/java/com/calm/inbox/features/brief/
git commit -m "feat(brief): add daily brief generation with 22:00 worker, fallback template and local push"
```
