### Task 11: HybridClassifier 管线（攒批调度 + 规则先行 + LLM 兜底 + 降级）

> **设计决定（对齐骨架全局约束与设计文档 §5/§6）**
> - **规则先行**：`RuleEngine.classify` 命中的通知直接采用规则结果（`category` + `importanceOf`），**不进入 LLM**；规则命中的 `summary` 取 `item.title`（规则引擎不产摘要）。
> - **LLM 兜底**：未命中规则的条目按 `chunked(ClassificationPrompts.MAX_BATCH_SIZE)` 分批调 `LlmEngine`；解析结果按 `id` 映射回原条目，LLM 漏答的 `id` 走降级。
> - **降级链路（逐字执行）**：`engine == null` 或 `state != READY` → 纯规则模式；LLM 输出非法 JSON / 引擎抛异常 → **重试 1 次**（同 prompt 共 2 次尝试）→ 仍失败则该批全部降级。降级结果 = `Classification(id, "UNCATEGORIZED", 1, "")`——`importance=1` 契约要求 1~5，取噪音档；`category="UNCATEGORIZED"` 使条目仍可被 `getUnclassified()` 拾起，模型就绪后可重新打标。
> - **攒批策略（逐字执行）**：`ClassificationQueue` 阈值 **10 条**立即冲刷，或队列从空变非空起 **5 分钟**后冲刷（计时从最旧一条入队起算，后续 offer 不重置）；冲刷后逐条 `dao.updateClassification` 写库。计时用协程 `delay`，测试用 `runTest` 虚拟时钟（骨架允许 Clock / TestDispatcher 二选一）。
> - ⚠️ 本任务 Modify 的 `app/src/main/java/com/calm/inbox/core/notifications/CalmNotificationListenerService.kt` 为 **W1 Task 3 交付物**，此处按设计文档 §4.2 包结构推定文件名；若 W1 实际文件名不同，在 W1 实际的监听服务同位置追加同样两处改动即可，代码不变。
> - ⚠️ `ClassifyModule` 中的 `provideRuleEngine`：若 W1 Task 4 已在 `AppModule` 提供 `RuleEngine`，**删除本模块中的该 provides** 以免重复绑定，其余不变。

**前置依赖（已就绪，直接使用）**
- `RuleEngine` / `RuleResult`（W1 Task 4 契约，`core/classify/RuleEngine.kt`）。
- `LlmEngine` / `EngineState`（Task 9 契约）与 `FakeLlmEngine`（Task 10 测试替身）。
- `ClassificationPrompts` / `ClassificationJsonParser` / `ParsedClassification`（Task 10 产出）。
- `NotificationDao.updateClassification(id, category, importance, summary)`（W1 Task 2 契约）。

## Files

**Create:**
- `app/src/main/java/com/calm/inbox/core/classify/HybridClassifier.kt`（含契约数据类 `Classification`）
- `app/src/main/java/com/calm/inbox/core/classify/ClassificationQueue.kt`
- `app/src/main/java/com/calm/inbox/di/ClassifyModule.kt`

**Modify:**
- `app/src/main/java/com/calm/inbox/core/notifications/CalmNotificationListenerService.kt`（入库成功后 `offer` 到分类队列，⚠️ W1 文件名见上）

**Test:**
- `app/src/test/java/com/calm/inbox/core/classify/HybridClassifierTest.kt`
- `app/src/test/java/com/calm/inbox/core/classify/ClassificationQueueTest.kt`（含内联 `FakeNotificationDao`）

## Interfaces

**Produces（骨架契约，逐字遵守）：**

```kotlin
// app/src/main/java/com/calm/inbox/core/classify/HybridClassifier.kt
package com.calm.inbox.core.classify

import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.core.model.LlmEngine

data class Classification(
    val id: Long,
    val category: String,
    val importance: Int,     // 1~5
    val summary: String
)

class HybridClassifier(
    private val rules: RuleEngine,
    private val engine: LlmEngine?,     // null 或 state != READY 表示降级纯规则
) {
    suspend fun classifyBatch(items: List<NotificationEntity>): List<Classification>
}
```

**本任务附加产出（不在骨架契约内，供 W2/W3 与监听服务使用）：**
- `ClassificationQueue(classifier, dao, scope, flushThreshold = 10, flushIntervalMs = 300_000)`：`offer(item)` / `flushNow()` / `pendingCount`；常量 `DEFAULT_FLUSH_THRESHOLD = 10`、`DEFAULT_FLUSH_INTERVAL_MS = 5L * 60 * 1000`。
- `HybridClassifier.MAX_ATTEMPTS = 2`（首次 + 重试 1 次）。
- `ClassifyModule` 提供 `RuleEngine`、`HybridClassifier`、`ClassificationQueue`（均 `@Singleton`）。

**Consumes（契约逐字引用）：** `RuleEngine.classify(item): RuleResult?`、`LlmEngine.state/generateStream`、`ClassificationPrompts.buildBatchPrompt`、`ClassificationJsonParser.parse`、`NotificationDao.updateClassification`。

## Step 1: 写失败测试

创建 `app/src/test/java/com/calm/inbox/core/classify/HybridClassifierTest.kt`：

```kotlin
package com.calm.inbox.core.classify

import com.calm.inbox.core.database.Category
import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.core.model.EngineState
import com.calm.inbox.core.model.FakeLlmEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HybridClassifierTest {

    private fun item(id: Long, pkg: String = "unknown.pkg") = NotificationEntity(
        id = id,
        packageName = pkg,
        appName = "App$id",
        title = "标题$id",
        text = "内容$id",
        postedAt = 1_700_000_000_000 + id,
        digest = "digest-$id",
    )

    /** 规则表只含一个包名，命中与否完全确定，不依赖 W1 默认映射表内容。 */
    private val rules = RuleEngine(mapOf("com.hit.social" to Category.SOCIAL))

    private fun llmJson(vararg ids: Long) =
        ids.joinToString(separator = ",", prefix = "[", postfix = "]") { id ->
            """{"id": $id, "category": "WORK", "importance": 4, "summary": "LLM摘要$id"}"""
        }

    @Test
    fun `规则命中的通知直接采用规则结果且不调用 LLM`() = runTest {
        val fake = FakeLlmEngine(responses = listOf(llmJson(1)))
        fake.load("/models")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(listOf(item(1, pkg = "com.hit.social")))

        assertThat(result).containsExactly(Classification(1, "SOCIAL", 2, "标题1"))
        assertThat(fake.receivedPrompts).isEmpty()
    }

    @Test
    fun `规则未命中且引擎为 null 时降级纯规则`() = runTest {
        val classifier = HybridClassifier(rules, null)

        val result = classifier.classifyBatch(listOf(item(5), item(6)))

        assertThat(result).containsExactly(
            Classification(5, "UNCATEGORIZED", 1, ""),
            Classification(6, "UNCATEGORIZED", 1, ""),
        )
    }

    @Test
    fun `引擎未就绪时同样降级且不调用 generate`() = runTest {
        val fake = FakeLlmEngine(responses = listOf(llmJson(5)))  // 未 load，state=NOT_LOADED
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(listOf(item(5)))

        assertThat(result).containsExactly(Classification(5, "UNCATEGORIZED", 1, ""))
        assertThat(fake.state.value).isEqualTo(EngineState.NOT_LOADED)
        assertThat(fake.receivedPrompts).isEmpty()
    }

    @Test
    fun `混合批次规则条目走规则未命中条目走 LLM`() = runTest {
        val fake = FakeLlmEngine(responses = listOf(llmJson(2, 3)))
        fake.load("/models")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(
            listOf(item(1, pkg = "com.hit.social"), item(2), item(3))
        )

        assertThat(result).containsExactly(
            Classification(1, "SOCIAL", 2, "标题1"),
            Classification(2, "WORK", 4, "LLM摘要2"),
            Classification(3, "WORK", 4, "LLM摘要3"),
        )
        assertThat(fake.receivedPrompts).hasSize(1)
    }

    @Test
    fun `LLM 漏答的 id 降级 其余采用 LLM 结果`() = runTest {
        val fake = FakeLlmEngine(responses = listOf(llmJson(2)))
        fake.load("/models")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(listOf(item(2), item(3)))

        assertThat(result).containsExactly(
            Classification(2, "WORK", 4, "LLM摘要2"),
            Classification(3, "UNCATEGORIZED", 1, ""),
        )
    }

    @Test
    fun `首次非法 JSON 重试一次后成功`() = runTest {
        val fake = FakeLlmEngine(responses = listOf("抱歉，我无法输出 JSON", llmJson(5)))
        fake.load("/models")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(listOf(item(5)))

        assertThat(result).containsExactly(Classification(5, "WORK", 4, "LLM摘要5"))
        assertThat(fake.receivedPrompts).hasSize(2)
    }

    @Test
    fun `两次非法 JSON 降级规则`() = runTest {
        val fake = FakeLlmEngine(responses = listOf("第一次失败", "第二次也失败"))
        fake.load("/models")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(listOf(item(5)))

        assertThat(result).containsExactly(Classification(5, "UNCATEGORIZED", 1, ""))
        assertThat(fake.receivedPrompts).hasSize(2)
    }

    @Test
    fun `引擎连续抛异常两次后降级`() = runTest {
        val fake = FakeLlmEngine(responses = listOf(llmJson(5)))
        fake.load("/models")
        fake.generateError = RuntimeException("推理崩溃")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(listOf(item(5)))

        assertThat(result).containsExactly(Classification(5, "UNCATEGORIZED", 1, ""))
        assertThat(fake.receivedPrompts).hasSize(2)
    }

    @Test
    fun `空列表返回空结果`() = runTest {
        val classifier = HybridClassifier(rules, null)

        assertThat(classifier.classifyBatch(emptyList())).isEmpty()
    }

    @Test
    fun `超过 20 条未命中分两批调用 LLM`() = runTest {
        val json1 = llmJson(*LongRange(1, 20).toList().toLongArray())
        val json2 = llmJson(21)
        val fake = FakeLlmEngine(responses = listOf(json1, json2))
        fake.load("/models")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch((1L..21L).map { item(it) })

        assertThat(result).hasSize(21)
        assertThat(result.count { it.category == "WORK" }).isEqualTo(21)
        assertThat(fake.receivedPrompts).hasSize(2)
        assertThat(fake.receivedPrompts[0]).contains("id=20")
        assertThat(fake.receivedPrompts[0]).doesNotContain("id=21")
        assertThat(fake.receivedPrompts[1]).contains("id=21")
    }
}
```

创建 `app/src/test/java/com/calm/inbox/core/classify/ClassificationQueueTest.kt`（用 `runTest` 虚拟时钟验证攒批；`backgroundScope` 保证测试结束时未触发的定时器自动取消不挂起）：

```kotlin
package com.calm.inbox.core.classify

import com.calm.inbox.core.database.dao.CategoryCount
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.NotificationEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

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
        val queue = ClassificationQueue(newClassifier(), dao, backgroundScope)

        repeat(9) { queue.offer(item(it.toLong())) }
        advanceUntilIdle()
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
        val queue = ClassificationQueue(newClassifier(), dao, backgroundScope)

        queue.offer(item(1))
        advanceTimeBy(ClassificationQueue.DEFAULT_FLUSH_INTERVAL_MS - 1)
        advanceUntilIdle()
        assertThat(dao.updates).isEmpty()

        advanceTimeBy(1)
        advanceUntilIdle()

        assertThat(dao.updates).hasSize(1)
    }

    @Test
    fun `定时器从首条入队起算不被后续 offer 重置`() = runTest {
        val dao = FakeNotificationDao()
        val queue = ClassificationQueue(newClassifier(), dao, backgroundScope)

        queue.offer(item(1))
        advanceTimeBy(4 * 60_000)
        queue.offer(item(2))
        advanceTimeBy(59_000)
        advanceUntilIdle()
        assertThat(dao.updates).isEmpty()

        advanceTimeBy(1_000)
        advanceUntilIdle()

        assertThat(dao.updates).hasSize(2)
    }

    @Test
    fun `冲刷后队列清空可继续攒批`() = runTest {
        val dao = FakeNotificationDao()
        val queue = ClassificationQueue(newClassifier(), dao, backgroundScope)

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
```

## Step 2: 确认测试失败

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.classify.HybridClassifierTest" --tests "com.calm.inbox.core.classify.ClassificationQueueTest"
```

**预期失败**：编译错误 `Unresolved reference: HybridClassifier` / `ClassificationQueue`。必须看到编译失败输出后再进入 Step 3。

## Step 3: 最小实现

创建 `app/src/main/java/com/calm/inbox/core/classify/HybridClassifier.kt`（含契约数据类，签名逐字）：

```kotlin
package com.calm.inbox.core.classify

import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.core.model.EngineState
import com.calm.inbox.core.model.LlmEngine
import kotlinx.coroutines.flow.toList

data class Classification(
    val id: Long,
    val category: String,
    val importance: Int,     // 1~5
    val summary: String
)

class HybridClassifier(
    private val rules: RuleEngine,
    private val engine: LlmEngine?,     // null 或 state != READY 表示降级纯规则
) {
    /**
     * 规则先行 → 未命中分批调 LLM → 非法输出重试 1 次 → 仍失败降级。
     * 返回条目覆盖入参全部 id，顺序不保证与输入一致，调用方按 id 使用。
     */
    suspend fun classifyBatch(items: List<NotificationEntity>): List<Classification> {
        if (items.isEmpty()) return emptyList()
        val results = mutableListOf<Classification>()
        val unmatched = mutableListOf<NotificationEntity>()
        for (item in items) {
            val rule = rules.classify(item)
            if (rule != null) {
                results += Classification(
                    id = item.id,
                    category = rule.category.name,
                    importance = rule.importance,
                    summary = item.title,
                )
            } else {
                unmatched += item
            }
        }
        if (unmatched.isEmpty()) return results

        val activeEngine = engine?.takeIf { it.state.value == EngineState.READY }
        if (activeEngine == null) {
            return results + unmatched.map { fallback(it) }
        }
        for (batch in unmatched.chunked(ClassificationPrompts.MAX_BATCH_SIZE)) {
            val parsed = generateWithRetry(activeEngine, batch)
            if (parsed == null) {
                results += batch.map { fallback(it) }
            } else {
                val byId = parsed.associateBy { it.id }
                results += batch.map { item ->
                    val hit = byId[item.id]
                    if (hit == null) {
                        fallback(item)
                    } else {
                        Classification(item.id, hit.category, hit.importance, hit.summary)
                    }
                }
            }
        }
        return results
    }

    /** 非法 JSON / 引擎异常各算一次失败；共尝试 MAX_ATTEMPTS 次后返回 null（降级）。 */
    private suspend fun generateWithRetry(
        engine: LlmEngine,
        batch: List<NotificationEntity>,
    ): List<ParsedClassification>? {
        val prompt = ClassificationPrompts.buildBatchPrompt(batch)
        repeat(MAX_ATTEMPTS) {
            try {
                val raw = engine.generateStream(prompt).toList().joinToString(separator = "")
                ClassificationJsonParser.parse(raw)?.let { return it }
            } catch (e: Exception) {
                // 引擎异常视同本次尝试失败
            }
        }
        return null
    }

    private fun fallback(item: NotificationEntity): Classification =
        Classification(id = item.id, category = "UNCATEGORIZED", importance = 1, summary = "")

    companion object {
        const val MAX_ATTEMPTS = 2   // 首次 + 重试 1 次（对齐设计文档 §6 降级链路）
    }
}
```

创建 `app/src/main/java/com/calm/inbox/core/classify/ClassificationQueue.kt`：

```kotlin
package com.calm.inbox.core.classify

import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 打标攒批队列：攒够 [flushThreshold] 条立即冲刷，或首条入队起 [flushIntervalMs] 毫秒后冲刷
 * （后续 offer 不重置计时）。冲刷 = classifyBatch + 逐条 updateClassification 写库。
 */
class ClassificationQueue(
    private val classifier: HybridClassifier,
    private val dao: NotificationDao,
    private val scope: CoroutineScope,
    private val flushThreshold: Int = DEFAULT_FLUSH_THRESHOLD,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
) {
    private val pending = mutableListOf<NotificationEntity>()
    private var flushJob: Job? = null

    val pendingCount: Int
        @Synchronized get() = pending.size

    @Synchronized
    fun offer(item: NotificationEntity) {
        pending += item
        if (pending.size >= flushThreshold) {
            flushNow()
        } else if (flushJob == null) {
            scheduleFlush()
        }
    }

    /** 立即冲刷当前积压（达到阈值自动触发，或外部主动触发）。 */
    fun flushNow() {
        val batch = takeBatch()
        if (batch.isEmpty()) return
        scope.launch {
            val results = classifier.classifyBatch(batch)
            results.forEach {
                dao.updateClassification(it.id, it.category, it.importance, it.summary)
            }
        }
    }

    @Synchronized
    private fun takeBatch(): List<NotificationEntity> {
        flushJob?.cancel()
        flushJob = null
        val batch = pending.toList()
        pending.clear()
        return batch
    }

    private fun scheduleFlush() {
        flushJob = scope.launch {
            delay(flushIntervalMs)
            flushNow()
        }
    }

    companion object {
        const val DEFAULT_FLUSH_THRESHOLD = 10
        const val DEFAULT_FLUSH_INTERVAL_MS = 5L * 60 * 1000
    }
}
```

创建 `app/src/main/java/com/calm/inbox/di/ClassifyModule.kt`：

```kotlin
package com.calm.inbox.di

import com.calm.inbox.core.classify.ClassificationQueue
import com.calm.inbox.core.classify.HybridClassifier
import com.calm.inbox.core.classify.RuleEngine
import com.calm.inbox.core.database.dao.NotificationDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ClassifyModule {

    // ⚠️ 若 W1 已在 AppModule 提供 RuleEngine，删除本方法避免重复绑定
    @Provides
    @Singleton
    fun provideRuleEngine(): RuleEngine = RuleEngine()

    @Provides
    @Singleton
    fun provideHybridClassifier(rules: RuleEngine, engine: dagger.Lazy<com.calm.inbox.core.model.LlmEngine>): HybridClassifier =
        HybridClassifier(rules, engine.get())

    @Provides
    @Singleton
    fun provideClassificationQueue(
        classifier: HybridClassifier,
        dao: NotificationDao,
    ): ClassificationQueue = ClassificationQueue(
        classifier = classifier,
        dao = dao,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}
```

> 说明：`HybridClassifier` 契约参数类型是 `LlmEngine?`，DI 侧传入 Task 9 的单例实例（非 null）——未就绪时由 `classifyBatch` 内部的 `state != READY` 检查走纯规则降级，语义与契约一致；用 `dagger.Lazy` 避免依赖图启动时过早创建引擎。

修改 `app/src/main/java/com/calm/inbox/core/notifications/CalmNotificationListenerService.kt`（⚠️ W1 Task 3 文件，两处追加，保留既有内容）：

```kotlin
// 1) 类成员追加注入（该服务 W1 已标 @AndroidEntryPoint）：
@Inject lateinit var classifyQueue: ClassificationQueue

// 2) onNotificationPosted 的入库分支，insert 返回 rowId 后追加：
//    （rowId 为 W1 Task 3 中 dao.insert(entity) 的返回值；-1 表示 digest 冲突被忽略，不入队）
if (rowId != -1L) {
    classifyQueue.offer(entity.copy(id = rowId))
}
```

追加 import：

```kotlin
import com.calm.inbox.core.classify.ClassificationQueue
import javax.inject.Inject
```

## Step 4: 确认测试通过

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.classify.HybridClassifierTest" --tests "com.calm.inbox.core.classify.ClassificationQueueTest"
```

**预期**：`BUILD SUCCESSFUL`，HybridClassifier 10 用例 + ClassificationQueue 4 用例全部通过。若定时器用例挂起，检查队列 scope 是否用了 `backgroundScope`（未触发定时器不会被自动取消导致 runTest 等待）；若「立即冲刷」用例失败，检查 `offer` 达阈值时是否同步调用了 `flushNow`。

## Step 5: 提交

```powershell
git add app/src/main/java/com/calm/inbox/core/classify/HybridClassifier.kt app/src/main/java/com/calm/inbox/core/classify/ClassificationQueue.kt app/src/main/java/com/calm/inbox/di/ClassifyModule.kt app/src/main/java/com/calm/inbox/core/notifications/CalmNotificationListenerService.kt app/src/test/java/com/calm/inbox/core/classify/HybridClassifierTest.kt app/src/test/java/com/calm/inbox/core/classify/ClassificationQueueTest.kt
git commit -m "feat(classify): add hybrid classification pipeline with batching queue and degradation chain"
```
