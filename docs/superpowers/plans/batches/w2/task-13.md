### Task 13: 简报 UI 与基准打点

**设计决定（跨任务一致性，逐条对照前序任务与骨架）**

- **简报页**：`BriefViewModel` 只消费 `BriefDao.observeAll()`（骨架契约，Room 已 `ORDER BY date DESC`，ViewModel 不再排序），`stateIn(WhileSubscribed(5_000), emptyList())` 暴露 `StateFlow<List<BriefEntity>>`；`BriefScreen` 用 Material 3 `LazyColumn` + `Card` 渲染，空态文案提示「每天 22:00 自动生成」。W1 Task 1 的 NavHost 已含 `"brief"` 路由占位，本任务替换为真实页面。
- **基准打点链路**：Task 9 已在 `MnnLlmEngine` 内置 `firstTokenLatencyListener: ((Long) -> Unit)?`（首 token 延迟毫秒，`System.nanoTime()` 差值）。本任务生产 `LatencyRecorder`：挂接该回调 → `DataStore<Preferences>` 持久化最近 `MAX_SAMPLES = 20` 条（逗号分隔字符串，追加尾部、超出丢头部）→ `stats(): Flow<LatencyStats?>` 输出「最近值 / 平均值 / 样本数」。设计文档 §6「首 token 延迟劣化 → 记录基准并在设置页展示」由此闭环。
- **挂接时机**：`CalmInboxApp.onCreate()` 中 `latencyRecorder.attachTo(llmEngine.get())`。`MnnLlmEngine` 构造是纯字段赋值（模型加载发生在 `load()`，见 Task 9），App 启动即挂接安全、不会触发模型加载。`attachTo` 对非 `MnnLlmEngine`（测试替身 `FakeLlmEngine`）安全 no-op。
- **回调线程模型**：`firstTokenLatencyListener` 在流式回调线程同步触发，非挂起上下文；`LatencyRecorder` 构造注入 `CoroutineScope`，回调内 `scope.launch { record(ms) }` 落盘，不阻塞推理流。
- **设置页展示**：`SettingsViewModel` 追加 `firstTokenLatency: StateFlow<LatencyStats?>`；`SettingsScreen` 追加「模型基准（首 token 延迟）」卡片，无样本时显示引导文案。
- **基准文档**：`docs/benchmarks/benchmark-w2.md` 是测量协议 + 结果记录表（协议与表格模板正文完整给出，实测数值按协议在 Step 4 真机验收时填入记录表——这是测量文档的预期工作流，非代码占位）。指标目标对齐设计文档 §8：首 token 延迟 < 3000 ms。
- **范围裁剪（不镀金）**：本任务只做首 token 延迟（任务名明确）；tokens/sec 与内存峰值基准由 W3 Task 18 真机补测（见任务索引），`benchmark-w2.md` 中以「后续任务」章节引用，不提前实现。
- ⚠️ `LatencyRecorder` 依赖 `DataStore<Preferences>` 绑定：骨架通用约定中 W1 `AppModule` 提供 `SettingsRepository`（基于 DataStore Preferences），按最可能形态假定 W1 已同时提供 `DataStore<Preferences>` 绑定并在 `BenchmarkModule` 中直接注入；若 W1 未提供该绑定，按 Step 3 末尾的备用 provides 补上（同一 DataStore 文件名 `"calm_settings"`，与设置数据同库，避免两个 DataStore 实例）。
- ⚠️ 以下 Modify 的 3 个 W1 产文件名按骨架包结构与命名惯例假定，执行时以 W1 计划实际产出为准：`app/src/main/java/com/calm/inbox/MainActivity.kt`、`app/src/main/java/com/calm/inbox/features/settings/SettingsViewModel.kt`、`app/src/main/java/com/calm/inbox/features/settings/SettingsScreen.kt`。

**前置依赖（W1 / 前序任务已就绪，直接使用）**

- W1：`BriefDao`（含 `observeAll(): Flow<List<BriefEntity>>`，ORDER BY date DESC）、`BriefEntity`、`AppModule`（DAO 提供）、NavHost `"brief"` 路由占位、`SettingsViewModel` / `SettingsScreen`、`DataStore<Preferences>`（SettingsRepository 同源）。
- Task 9：`MnnLlmEngine`（可测构造 `MnnLlmEngine(create, generate, release)` + `firstTokenLatencyListener`）、`di/ModelModule.kt` 提供 `LlmEngine` 单例。
- Task 10：`FakeLlmEngine`（`app/src/test/java/com/calm/inbox/core/model/FakeLlmEngine.kt`，构造参数 `responses: List<String>`）。
- Task 12：`CalmInboxApp` 已实现 `Configuration.Provider` 并在 `onCreate` 调度 `BriefScheduler.schedule(this)`，本任务在同一 `onCreate` 追加挂接。

## Files

**Create:**
- `app/src/main/java/com/calm/inbox/features/brief/BriefViewModel.kt`
- `app/src/main/java/com/calm/inbox/features/brief/BriefScreen.kt`
- `app/src/main/java/com/calm/inbox/features/settings/LatencyRecorder.kt`
- `app/src/main/java/com/calm/inbox/di/BenchmarkModule.kt`
- `docs/benchmarks/benchmark-w2.md`

**Modify:**
- `app/src/main/java/com/calm/inbox/features/settings/SettingsViewModel.kt`（⚠️ W1 产，追加 `firstTokenLatency`）
- `app/src/main/java/com/calm/inbox/features/settings/SettingsScreen.kt`（⚠️ W1 产，追加基准卡片）
- `app/src/main/java/com/calm/inbox/MainActivity.kt`（⚠️ W1 产，`"brief"` 路由替换占位为 `BriefScreen()`）
- `app/src/main/java/com/calm/inbox/CalmInboxApp.kt`（Task 12 已改，追加打点挂接）

**Test:**
- `app/src/test/java/com/calm/inbox/features/settings/LatencyRecorderTest.kt`
- `app/src/test/java/com/calm/inbox/features/brief/BriefViewModelTest.kt`

## Interfaces

**Consumes（骨架契约 / 前序任务产出，逐字遵守）：**

```kotlin
// W1 Task 2 生产
interface BriefDao {
    suspend fun insert(brief: BriefEntity): Long
    fun observeAll(): Flow<List<BriefEntity>>                  // ORDER BY date DESC
    suspend fun getByDate(date: String): BriefEntity?
}

// app/src/main/java/com/calm/inbox/core/database/entity/BriefEntity.kt（W1 Task 2）
data class BriefEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val content: String,
    val createdAt: Long
)

// Task 9 生产
class MnnLlmEngine(...) : LlmEngine {
    var firstTokenLatencyListener: ((Long) -> Unit)?   // 首 token 延迟毫秒
}

// Task 10 生产（测试替身，本任务测试直接 import）
class FakeLlmEngine(private val responses: List<String> = emptyList(), ...) : LlmEngine
```

**Produces（本任务新增，W3 直接消费）：**

```kotlin
// app/src/main/java/com/calm/inbox/features/settings/LatencyRecorder.kt
data class LatencyStats(val latestMs: Long, val averageMs: Long, val sampleCount: Int)

class LatencyRecorder(private val dataStore: DataStore<Preferences>, private val scope: CoroutineScope) {
    fun attachTo(engine: LlmEngine)          // 非 MnnLlmEngine 安全 no-op
    suspend fun record(latencyMs: Long)      // 追加样本，仅保留最近 MAX_SAMPLES 条
    fun stats(): Flow<LatencyStats?>         // 无样本时发射 null
    companion object { const val MAX_SAMPLES = 20 }
}

// app/src/main/java/com/calm/inbox/features/brief/BriefViewModel.kt
@HiltViewModel
class BriefViewModel(briefDao: BriefDao) : ViewModel() {
    val briefs: StateFlow<List<BriefEntity>>
}
```

- `BriefScreen`：完成 `"brief"` 路由的正式页面（底部导航四 tab 之一）。
- `LatencyRecorder.stats()` / `LatencyStats`：W3 Task 18 README 真机基准表直接读取数据源；`docs/benchmarks/benchmark-w2.md` 提供测量协议与记录模板。

## Step 1: 写失败测试

创建 `app/src/test/java/com/calm/inbox/features/settings/LatencyRecorderTest.kt`（DataStore 测试实例挂在 `testScheduler` 上，`advanceUntilIdle()` 可完整排空读写，避免文件 IO 竞态；`MnnLlmEngine` 用 Task 9 同款函数引用注入构造，JVM 单测不触碰 JNI）：

```kotlin
package com.calm.inbox.features.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.calm.inbox.core.model.FakeLlmEngine
import com.calm.inbox.core.model.MnnLlmEngine
import com.calm.inbox.core.model.MnnNative
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LatencyRecorderTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    /** 每个用例独立的 DataStore 文件 + 挂在 testScheduler 上的 DataStore 作用域。 */
    private fun TestScope.createRecorder(): LatencyRecorder {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
            produceFile = { File(tmpFolder.newFolder(), "benchmarks.preferences_pb") },
        )
        return LatencyRecorder(dataStore, backgroundScope)
    }

    @Test
    fun `stats emits null when no samples recorded`() = runTest {
        val recorder = createRecorder()

        assertThat(recorder.stats().first()).isNull()
    }

    @Test
    fun `record computes latest and average across samples`() = runTest {
        val recorder = createRecorder()

        recorder.record(100)
        recorder.record(300)

        assertThat(recorder.stats().first())
            .isEqualTo(LatencyStats(latestMs = 300, averageMs = 200, sampleCount = 2))
    }

    @Test
    fun `record keeps only the most recent MAX_SAMPLES`() = runTest {
        val recorder = createRecorder()

        repeat(25) { index -> recorder.record((index + 1).toLong()) }

        val stats = recorder.stats().first()
        assertThat(stats!!.sampleCount).isEqualTo(LatencyRecorder.MAX_SAMPLES)
        assertThat(stats.latestMs).isEqualTo(25)
        assertThat(stats.averageMs).isEqualTo(15)   // (6+7+…+25)/20 = 310/20 = 15
    }

    @Test
    fun `attachTo is a no-op for non MNN engines`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = createRecorder()
        val fake = FakeLlmEngine(responses = listOf("回复文本"))
        fake.load("/models/qwen")

        recorder.attachTo(fake)
        fake.generateStream("prompt").toList()

        assertThat(recorder.stats().first()).isNull()
    }

    @Test
    fun `attachTo records first token latency of MnnLlmEngine streams`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = createRecorder()
        var onGenerate: ((MnnNative.StreamListener) -> Unit)? = null
        val create: (String) -> Long = { 1L }
        val generate: (Long, String, MnnNative.StreamListener) -> Unit =
            { _, _, listener -> onGenerate?.invoke(listener) ?: listener.onToken(null) }
        val release: (Long) -> Unit = { }
        onGenerate = { listener ->
            listener.onToken("你")
            listener.onToken(null)
        }
        val engine = MnnLlmEngine(create, generate, release)

        recorder.attachTo(engine)
        engine.load("/models/qwen")
        engine.generateStream("prompt").toList()
        advanceUntilIdle()

        val stats = recorder.stats().first()
        assertThat(stats!!.sampleCount).isEqualTo(1)
        assertThat(stats.latestMs).isAtLeast(0L)
    }
}
```

创建 `app/src/test/java/com/calm/inbox/features/brief/BriefViewModelTest.kt`（内联 Fake BriefDao；Turbine 断言 `stateIn` 初值与转发）：

```kotlin
package com.calm.inbox.features.brief

import com.calm.inbox.core.database.dao.BriefDao
import com.calm.inbox.core.database.entity.BriefEntity
import com.google.common.truth.Truth.assertThat
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
```

## Step 2: 确认测试失败

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.settings.LatencyRecorderTest" --tests "com.calm.inbox.features.brief.BriefViewModelTest"
```

预期：编译失败（`LatencyRecorder`、`LatencyStats`、`BriefViewModel`、`BenchmarkModule` 不存在；`CalmInboxApp` 未注入挂接）。这是本任务的失败基线。

## Step 3: 最小实现

创建 `app/src/main/java/com/calm/inbox/features/settings/LatencyRecorder.kt`：

```kotlin
package com.calm.inbox.features.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.calm.inbox.core.model.LlmEngine
import com.calm.inbox.core.model.MnnLlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class LatencyStats(val latestMs: Long, val averageMs: Long, val sampleCount: Int)

/**
 * 首 token 延迟打点：挂接 [MnnLlmEngine.firstTokenLatencyListener]，
 * DataStore 持久化最近 [MAX_SAMPLES] 条，供设置页基准卡片与 W3 README 基准表消费。
 */
class LatencyRecorder(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {

    /** 挂接打点回调；非 MNN 实现（测试替身）安全 no-op。 */
    fun attachTo(engine: LlmEngine) {
        val mnn = engine as? MnnLlmEngine ?: return
        mnn.firstTokenLatencyListener = { latencyMs ->
            scope.launch { record(latencyMs) }
        }
    }

    suspend fun record(latencyMs: Long) {
        dataStore.edit { prefs ->
            val samples = parse(prefs[SAMPLES_KEY]).toMutableList()
            samples += latencyMs
            prefs[SAMPLES_KEY] = samples.takeLast(MAX_SAMPLES).joinToString(",")
        }
    }

    fun stats(): Flow<LatencyStats?> = dataStore.data.map { prefs ->
        val samples = parse(prefs[SAMPLES_KEY])
        if (samples.isEmpty()) {
            null
        } else {
            LatencyStats(
                latestMs = samples.last(),
                averageMs = samples.sum() / samples.size,
                sampleCount = samples.size,
            )
        }
    }

    private fun parse(raw: String?): List<Long> =
        raw?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    companion object {
        const val MAX_SAMPLES = 20
        private val SAMPLES_KEY = stringPreferencesKey("first_token_latency_samples")
    }
}
```

创建 `app/src/main/java/com/calm/inbox/features/brief/BriefViewModel.kt`：

```kotlin
package com.calm.inbox.features.brief

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calm.inbox.core.database.dao.BriefDao
import com.calm.inbox.core.database.entity.BriefEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class BriefViewModel @Inject constructor(
    briefDao: BriefDao,
) : ViewModel() {

    /** 简报列表，Room 已按 date 降序返回。 */
    val briefs: StateFlow<List<BriefEntity>> = briefDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
```

创建 `app/src/main/java/com/calm/inbox/features/brief/BriefScreen.kt`：

```kotlin
package com.calm.inbox.features.brief

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.calm.inbox.core.database.entity.BriefEntity

@Composable
fun BriefScreen(viewModel: BriefViewModel = hiltViewModel()) {
    val briefs by viewModel.briefs.collectAsState()

    if (briefs.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("还没有简报", style = MaterialTheme.typography.titleMedium)
            Text(
                "每天 22:00 会自动汇总当日通知生成简报",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(briefs, key = { it.id }) { brief ->
            BriefCard(brief)
        }
    }
}

@Composable
private fun BriefCard(brief: BriefEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(brief.date, style = MaterialTheme.typography.titleMedium)
            Text(brief.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
```

创建 `app/src/main/java/com/calm/inbox/di/BenchmarkModule.kt`：

```kotlin
package com.calm.inbox.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.calm.inbox.features.settings.LatencyRecorder
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
object BenchmarkModule {

    @Provides
    @Singleton
    fun provideLatencyRecorder(dataStore: DataStore<Preferences>): LatencyRecorder =
        LatencyRecorder(dataStore, CoroutineScope(SupervisorJob() + Dispatchers.IO))
}
```

⚠️ 若执行时发现 W1 `AppModule` 未提供 `DataStore<Preferences>` 绑定（编译报缺绑定），在 `BenchmarkModule` 中追加以下 provides（与设置数据共用同一 DataStore 文件名，避免双实例；若 W1 已提供则勿加，否则重复绑定编译失败）：

```kotlin
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext

private val Context.settingsDataStore by preferencesDataStore(name = "calm_settings")

@Provides
@Singleton
fun providePreferencesDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
    context.settingsDataStore
```

修改 `app/src/main/java/com/calm/inbox/features/settings/SettingsViewModel.kt`（⚠️ W1 产文件，保留 W1 既有构造参数与属性，追加以下内容）：

```kotlin
import com.calm.inbox.features.settings.LatencyStats

@HiltViewModel
class SettingsViewModel @Inject constructor(
    // … W1 既有参数保持不变（SettingsRepository 等）…
    private val latencyRecorder: LatencyRecorder,
) : ViewModel() {

    // … W1 既有属性保持不变…

    /** 首 token 延迟基准；无样本时为 null（设置页显示引导文案）。 */
    val firstTokenLatency: StateFlow<LatencyStats?> = latencyRecorder.stats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
```

修改 `app/src/main/java/com/calm/inbox/features/settings/SettingsScreen.kt`（⚠️ W1 产文件，在既有内容尾部追加调用；`BenchmarkCard` 加入同文件）：

```kotlin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    // … W1 既有内容保持不变（黑名单、降噪阈值、权限引导、模型管理卡片）…
    val latency by viewModel.firstTokenLatency.collectAsState()
    BenchmarkCard(latency)
}

@Composable
private fun BenchmarkCard(stats: LatencyStats?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("模型基准（首 token 延迟）", style = MaterialTheme.typography.titleMedium)
            if (stats == null) {
                Text(
                    "暂无记录：完成一次模型分类或问答后自动生成",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "最近 ${stats.latestMs} ms · 平均 ${stats.averageMs} ms · 已采样 ${stats.sampleCount} 条",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
```

修改 `app/src/main/java/com/calm/inbox/MainActivity.kt`（⚠️ W1 产文件，NavHost 中 `"brief"` 路由的占位 Composable 替换为真实页面，其余路由不动）：

```kotlin
import com.calm.inbox.features.brief.BriefScreen

// 替换前（W1 占位形态，示意）：
// composable("brief") { Text("简报") }

// 替换后：
composable("brief") {
    BriefScreen()
}
```

修改 `app/src/main/java/com/calm/inbox/CalmInboxApp.kt`（Task 12 已实现 `Configuration.Provider` 与简报调度，本任务追加打点挂接）：

```kotlin
import com.calm.inbox.core.model.LlmEngine
import com.calm.inbox.features.settings.LatencyRecorder
import dagger.Lazy

@HiltAndroidApp
class CalmInboxApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory        // Task 12
    @Inject lateinit var briefScheduler: BriefScheduler          // Task 12
    @Inject lateinit var latencyRecorder: LatencyRecorder        // 本任务
    @Inject lateinit var llmEngine: Lazy<LlmEngine>              // 本任务；Lazy 构造轻量，不触发模型加载

    override fun onCreate() {
        super.onCreate()
        briefScheduler.schedule(this)                // Task 12 既有
        latencyRecorder.attachTo(llmEngine.get())    // 本任务新增
    }

    // … Configuration.Provider 实现保持 Task 12 原样 …
}
```

创建 `docs/benchmarks/benchmark-w2.md`：

````markdown
# W2 基准记录：首 token 延迟（Qwen2.5-1.5B-Instruct int4 / MNN）

> 本文档是测量协议与结果记录表。两处记录表在执行下方「验收协议」真机实测后，
> 将「结果记录模板」整段复制出代码块并填入实测值（测量文档的预期工作流）。
> 采集链路：`MnnLlmEngine.firstTokenLatencyListener`（`System.nanoTime()` 差值，ms）
> → `LatencyRecorder`（DataStore 持久化最近 20 条）→ 设置页「模型基准」卡片。

## 指标定义

- **首 token 延迟（ms）**：从 `generateStream(prompt)` 调用到第一个 token 回调的耗时。
- **目标**：`< 3000 ms`（设计文档 §8 成功标准）。
- **样本口径**：冷启动后首次推理是「权重页加载 + 首次前向」混合，验收时先做 1 次预热推理（不计入），随后连续 5 次正式测量取「最近值 / 平均值」（即设置页卡片显示值）。

## 验收协议（真机，按序执行）

1. 记录测量环境（设备型号、芯片、内存、Android 版本、室温大致区间）到「环境记录」表。
2. 安装 Release 构建：`.\gradlew.bat :app:assembleRelease`（或 Debug 构建，注明即可），安装到真机。
3. 设置页下载模型至完成（ModelManager 进度 100%）。
4. 杀掉 App 进程后冷启动，进入聊天或等待一次批量分类触发模型加载；第 1 次推理为预热，丢弃。
5. 连续触发 5 次推理（问答页连续提问 5 次，或分 5 批攒批分类触发）。
6. 打开设置页读取「模型基准（首 token 延迟）」卡片的「最近 / 平均 / 样本数」。
7. （可选）`adb shell dumpsys meminfo com.calm.inbox` 抓内存快照，附在结果记录之后，供 W3 Task 18 引用。
8. 按模板填入结果，`docs(benchmark): record W2 first-token latency measurements` 提交。

## 环境记录 / 结果记录模板

```markdown
### 环境记录
| 项目 | 实测值 |
|---|---|
| 设备型号 |  |
| 芯片 |  |
| 内存 |  |
| Android 版本 |  |
| 构建类型 |  |

### 结果记录（预热 1 次后连续 5 次）
| 推测序号 | 首 token 延迟（ms） |
|---|---|
| 1 |  |
| 2 |  |
| 3 |  |
| 4 |  |
| 5 |  |
| 卡片平均值（ms） |  |
| 是否达标（<3000ms） |  |

### 备注
（异常情况、电量状态、后台负载等影响因子）
```

## 后续任务（不在本任务范围）

- tokens/sec、内存峰值、Qwen3 系列对比基准：W3 Task 18（README 真机性能基准表）真机补测，
  届时把本文件的协议与结果并入 README 基准章节。
````

## Step 4: 确认测试通过

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.settings.LatencyRecorderTest" --tests "com.calm.inbox.features.brief.BriefViewModelTest"
```

预期：`LatencyRecorderTest` 5 个用例 + `BriefViewModelTest` 2 个用例全部 PASSED。Compose UI（`BriefScreen` / `BenchmarkCard`）最小可编译验证与全量回归：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
```

真机验收（非阻塞项，按 `docs/benchmarks/benchmark-w2.md` 验收协议执行并回填记录表）：

```powershell
.\gradlew.bat :app:installDebug
adb shell am start -n com.calm.inbox/.MainActivity
```

## Step 5: 提交

```powershell
git status
git diff
git log --oneline -5

git add app/src/main/java/com/calm/inbox/features/brief/BriefViewModel.kt app/src/main/java/com/calm/inbox/features/brief/BriefScreen.kt app/src/main/java/com/calm/inbox/features/settings/LatencyRecorder.kt app/src/main/java/com/calm/inbox/di/BenchmarkModule.kt app/src/main/java/com/calm/inbox/features/settings/SettingsViewModel.kt app/src/main/java/com/calm/inbox/features/settings/SettingsScreen.kt app/src/main/java/com/calm/inbox/MainActivity.kt app/src/main/java/com/calm/inbox/CalmInboxApp.kt app/src/test/java/com/calm/inbox/features/settings/LatencyRecorderTest.kt app/src/test/java/com/calm/inbox/features/brief/BriefViewModelTest.kt
git commit -m "feat(ui): add brief screen and first-token latency benchmark recorder"

git add docs/benchmarks/benchmark-w2.md
git commit -m "docs(benchmark): add W2 first-token latency measurement protocol"
git status
```
