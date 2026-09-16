# CalmInbox 三周实施计划（W1 原生闭环 → W2 MNN 集成 → W3 问答打磨与发布）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按设计文档 `docs/superpowers/specs/2026-09-15-calminbox-design.md` 交付 CalmInbox MVP：端侧 AI 通知管家（通知接入 → 本地分类降噪 → 每日简报 → 自然语言问答），全程零上传。

**Architecture:** 单模块分层 Android 应用（`core/`: database、model、notifications、classify；`features/`: inbox、brief、chat、settings）。规则引擎先行、LLM 批处理兜底；MNN Android LLM SDK 封装为 `LlmEngine` 单例，惰性加载 + 空闲 10 分钟自动释放；聊天与后台批处理共享同一实例；降级链路保证无模型时应用完整可用。

**Tech Stack:** Kotlin 2.0.20 + Jetpack Compose（Material 3）+ Hilt 2.52 + Room 2.6.1 + WorkManager 2.9.1 + DataStore 1.1.1 + MNN Android LLM SDK（Qwen2.5-1.5B-Instruct int4，约 1GB）+ JUnit4/Robolectric + GitHub Actions

## Global Constraints

以下约束对**所有任务**生效，任何任务不得违反（值一律照抄，不得改名或改值）：

- 工程基线：单模块 `:app`，app id `com.calm.inbox`；Gradle 8.9 / AGP 8.5.2 / Kotlin 2.0.20 / compileSdk 34 / minSdk 26 / targetSdk 34；Java 17 toolchain；版本统一收敛到 `gradle/libs.versions.toml`
- 版本目录其余关键坐标：Compose BOM `2024.09.03`、`androidx.compose.ui:ui`、Material3、`androidx.navigation:navigation-compose:2.8.0`、`androidx.hilt:hilt-navigation-compose:1.2.0`、`androidx.hilt:hilt-work:1.2.0`、Room `2.6.1`（compiler 含 kapt）、WorkManager `2.9.1`、DataStore Preferences `1.1.1`、`com.squareup.okhttp3:okhttp:4.12.0`、Robolectric `4.13`、`org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1`、Turbine `app.cash.turbine:turbine:1.1.0`、Truth `com.google.truth:truth:1.4.4`
- 包结构分层不拆模块（照设计文档 4.2 节）：`core/database`、`core/model`、`core/notifications`、`core/classify`、`features/inbox`、`features/brief`、`features/chat`、`features/settings`
- 主力模型：Qwen2.5-1.5B-Instruct int4（约 1GB），首启从 ModelScope 下载，设置页管理（进度/删除）
- 上下文控制：每条通知文本截断 ≤100 字；LLM 批处理每批 ≤20 条
- 批处理策略：攒批阈值 10 条或 5 分钟（时钟必须可注入，用 `Clock` 接口 / `TestDispatcher` 测试）
- 降级链路：模型未就绪 → 分类降级纯规则引擎；LLM 输出非法 JSON → 重试 1 次 → 降级规则
- 空闲超时释放：`LlmEngine` 空闲 10 分钟自动 `release()`
- 去重：`digest = sha256("$packageName|$postedAt|$title")`，数据库唯一索引，插入冲突忽略
- 检索用 LIKE 关键词匹配（FTS 中文分词列入二期）；明确不做：短信、云端模型/账号、embedding RAG、iOS、多模块拆分
- 通知过滤黑名单：包名集合存 DataStore，默认必须包含本 App 自身（`com.calm.inbox`）
- 每日简报：WorkManager 每日 22:00 触发，失败退避重试；简报存库并本地通知推送
- 成功标准：真机抽样 50 条人工核对分类准确率 ≥80%；首 token 延迟 <3s；clone 后 Android Studio 打开即可构建运行
- 提交规范：conventional commits（`feat:` / `test:` / `build:` / `docs:` / `chore:` 前缀），每个任务内至少一次提交
- 命令约定：本机为 Windows PowerShell，Gradle 命令写 `.\gradlew.bat <task>`；CI（Linux）中写 `./gradlew <task>`
- 测试文件位置：`app/src/test/java/com/calm/inbox/...`（Room DAO 用 Robolectric，纯逻辑用普通 JUnit4 + `runTest`）；所有 Flow 断言用 Turbine

---

## 共享接口契约（所有周次必须逐字遵守）

以下签名是跨周依赖的**唯一事实源**。后续任务生产或消费这些类型时，字段名、方法签名、包路径必须与本节完全一致。

### 数据模型（W1 Task 2 生产）

```kotlin
// app/src/main/java/com/calm/inbox/core/database/Category.kt
package com.calm.inbox.core.database

enum class Category {
    UNCATEGORIZED, VERIFICATION, EXPRESS, FINANCE, SOCIAL,
    WORK, SHOPPING, SYSTEM, MARKETING, OTHER
}
```

```kotlin
// app/src/main/java/com/calm/inbox/core/database/entity/NotificationEntity.kt
package com.calm.inbox.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notifications",
    indices = [Index(value = ["digest"], unique = true)]
)
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,            // 已截断 ≤100 字
    val postedAt: Long,          // epoch millis
    val category: String = "UNCATEGORIZED",
    val importance: Int = 0,     // 0 = 未分类；LLM/规则给 1~5
    val summary: String = "",
    val digest: String           // sha256("$packageName|$postedAt|$title")
)
```

```kotlin
// app/src/main/java/com/calm/inbox/core/database/entity/BriefEntity.kt
package com.calm.inbox.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "briefs")
data class BriefEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,            // LocalDate.toString()，如 "2026-09-15"
    val content: String,         // 简报正文（Markdown 纯文本）
    val createdAt: Long
)
```

```kotlin
// app/src/main/java/com/calm/inbox/core/database/entity/ChatMessageEntity.kt
package com.calm.inbox.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,            // "user" | "assistant"
    val content: String,
    val citationIds: String = "", // 引用的通知 id，逗号分隔，如 "12,34"
    val createdAt: Long
)
```

### DAO（W1 Task 2 生产）

```kotlin
// app/src/main/java/com/calm/inbox/core/database/dao/NotificationDao.kt
package com.calm.inbox.core.database.dao

import kotlinx.coroutines.flow.Flow
import com.calm.inbox.core.database.entity.NotificationEntity

data class CategoryCount(val category: String, val count: Int)

interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: NotificationEntity): Long   // 冲突返回 -1 即去重

    suspend fun getByDateRange(start: Long, end: Long): List<NotificationEntity>

    fun observeAll(): Flow<List<NotificationEntity>>     // ORDER BY postedAt DESC

    suspend fun getUnclassified(limit: Int): List<NotificationEntity> // category='UNCATEGORIZED' ORDER BY postedAt ASC

    suspend fun updateClassification(id: Long, category: String, importance: Int, summary: String)

    suspend fun searchByKeyword(keyword: String, start: Long, end: Long): List<NotificationEntity>
    // WHERE postedAt BETWEEN :start AND :end AND (title LIKE '%'||:keyword||'%' OR text LIKE '%'||:keyword||'%')
    // ORDER BY postedAt DESC LIMIT 50

    fun observeCountsByCategory(): Flow<List<CategoryCount>>  // GROUP BY category
}

interface BriefDao {
    @Insert suspend fun insert(brief: BriefEntity): Long
    fun observeAll(): Flow<List<BriefEntity>>                  // ORDER BY date DESC
    suspend fun getByDate(date: String): BriefEntity?
}

interface ChatMessageDao {
    @Insert suspend fun insert(msg: ChatMessageEntity): Long
    fun observeAll(): Flow<List<ChatMessageEntity>>            // ORDER BY createdAt ASC
    suspend fun clear()
}
```

Database 单例：`AppDatabase`（版本 1），暴露三个 DAO。

### 通知过滤（W1 Task 3 生产）

```kotlin
// app/src/main/java/com/calm/inbox/core/notifications/NotificationFilter.kt
package com.calm.inbox.core.notifications

class NotificationFilter(private val blacklist: Set<String>) {
    fun shouldAccept(pkg: String): Boolean
    companion object {
        const val MAX_TEXT_LENGTH = 100
        fun truncate(s: String, max: Int = MAX_TEXT_LENGTH): String
        fun digest(pkg: String, postedAt: Long, title: String): String  // sha256 十六进制
    }
}
```

### 规则引擎（W1 Task 4 生产）

```kotlin
// app/src/main/java/com/calm/inbox/core/classify/RuleEngine.kt
package com.calm.inbox.core.classify

import com.calm.inbox.core.database.Category
import com.calm.inbox.core.database.entity.NotificationEntity

data class RuleResult(val category: Category, val importance: Int)

class RuleEngine(private val packageMap: Map<String, Category> = DEFAULT_PACKAGE_MAP) {
    fun classify(item: NotificationEntity): RuleResult?   // 未命中返回 null
    companion object {
        val DEFAULT_PACKAGE_MAP: Map<String, Category>    // 覆盖国内主流 App ≥30 个包名
        fun importanceOf(category: Category): Int
        // VERIFICATION→5, EXPRESS→4, FINANCE→4, WORK→4, SOCIAL→2,
        // SHOPPING→2, SYSTEM→2, OTHER→2, MARKETING→1, UNCATEGORIZED→0
    }
}
```

### LLM 引擎（W2 Task 9 生产）

```kotlin
// app/src/main/java/com/calm/inbox/core/model/LlmEngine.kt
package com.calm.inbox.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class EngineState { NOT_LOADED, LOADING, READY, ERROR }

interface LlmEngine {
    val state: StateFlow<EngineState>
    suspend fun load(modelDir: String)
    suspend fun generateStream(prompt: String): Flow<String>   // 逐 token 流
    fun release()
}
```

测试替身：`FakeLlmEngine`（可注入预设回复序列，位于 `app/src/test/java/com/calm/inbox/core/model/FakeLlmEngine.kt`，W2 Task 10 生产，W3 复用）。

### 模型管理（W2 Task 8 生产）

```kotlin
// app/src/main/java/com/calm/inbox/core/model/ModelManager.kt
package com.calm.inbox.core.model

import java.io.File

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val progress: Float) : DownloadState  // 0f..1f
    data class Done(val modelDir: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

interface Downloader {   // 生产实现用 OkHttp，测试用 Fake
    fun download(url: String, dest: File): Flow<DownloadState>
}

class ModelManager(private val context: android.content.Context, private val downloader: Downloader) {
    fun modelDir(): File        // <filesDir>/models/Qwen2.5-1.5B-Instruct-MNN
    fun isModelReady(): Boolean // 目录存在且包含 config.json 且 totalSize > 500MB
    fun downloadModel(): Flow<DownloadState>
    suspend fun deleteModel()
}
```

### 分类管线（W2 Task 11 生产）

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

### 每日简报（W2 Task 12 生产）

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

WorkManager：`BriefWorker`（类名固定），每日 22:00 周期任务 + 退避重试；推送通知 channel id `"daily_brief"`。

### 问答（W3 Task 14/15 生产）

```kotlin
// app/src/main/java/com/calm/inbox/features/chat/ChatRepository.kt
package com.calm.inbox.features.chat

import com.calm.inbox.core.database.dao.ChatMessageDao
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.model.LlmEngine

data class Citation(val notificationId: Long, val title: String)

sealed interface ChatEvent {
    data class Chunk(val text: String) : ChatEvent
    data class Done(val citations: List<Citation>) : ChatEvent
}

class ChatRepository(
    private val engine: LlmEngine,
    private val notificationDao: NotificationDao,
    private val chatDao: ChatMessageDao,
    private val clock: java.time.Clock,
) {
    suspend fun ask(question: String): Flow<ChatEvent>
    fun history(): kotlinx.coroutines.flow.Flow<List<com.calm.inbox.core.database.entity.ChatMessageEntity>>
}
```

时间解析（W3 Task 14 生产）：`TimeQueryParser.parse(question: String, now: java.time.LocalDateTime): TimeRange?`，`data class TimeRange(val start: Long, val end: Long)`，支持「今天 / 昨天 / 前天 / 前天以前全部 / 最近N天 / 这周」。

### 通用约定

- 时钟注入：所有涉及时间的逻辑通过构造参数接收 `java.time.Clock`，默认 `Clock.systemDefaultZone()`，测试用 `Clock.fixed(...)`
- DataStore：`SettingsRepository`（features/settings）暴露 `blacklist: Flow<Set<String>>`、`addBlacklist(pkg)`、`removeBlacklist(pkg)`、`noiseThreshold: Flow<Int>`（默认 1，重要度 ≤ 阈值视为噪音）
- 导航路由名：`"inbox"`、`"brief"`、`"chat"`、`"settings"`；底部导航四 tab
- Hilt：`@HiltAndroidApp` 于 `CalmInboxApp`；`AppModule` 提供 `AppDatabase`、DAO、`SettingsRepository`；`ModelModule` 提供 `ModelManager`、`LlmEngine`（单例）

---

## 任务索引

| 周 | Task | 名称 | 交付物 |
|----|------|------|--------|
| W1 | 1 | 项目脚手架与版本目录 | 可构建的 `:app` 空壳 + Hilt + NavHost |
| W1 | 2 | Room 数据层 | 三实体三 DAO + AppDatabase + Robolectric 测试 |
| W1 | 3 | 通知监听与入库 | NotificationListenerService + 过滤/截断/去重 + 测试 |
| W1 | 4 | 规则分类引擎 | 包名映射表 + 启发式重要度 + 测试 |
| W1 | 5 | 设置页与 DataStore | 黑名单管理、降噪阈值、通知权限引导 |
| W1 | 6 | 收件箱 UI | 通知流 + 分类 tab + 降噪聚合折叠 |
| W1 | 7 | W1 收尾验收 | 手动验收清单 + README 占位 + 里程碑提交 |
| W2 | 8 | ModelManager 模型下载 | ModelScope 下载流/进度/删除 + 测试 |
| W2 | 9 | LlmEngine 封装 | MNN JNI 封装 + 状态机 + 空闲释放 + 测试 |
| W2 | 10 | Prompt 构造与容错解析 | 分类 prompt + JSON 容错解析 + FakeLlmEngine + 测试 |
| W2 | 11 | HybridClassifier 管线 | 攒批调度 + 规则先行 + LLM 兜底 + 降级 + 测试 |
| W2 | 12 | 每日简报 | BriefWorker 22:00 + 简报生成 + 本地推送 + 测试 |
| W2 | 13 | 简报 UI 与基准打点 | 简报页 + 首 token 延迟基准记录 + 设置页展示 |
| W3 | 14 | 检索层 | 关键词提取 + 时间解析 + LIKE 查询组装 + 测试 |
| W3 | 15 | 问答聊天 | ChatRepository + 流式 UI + 引用跳转 + 测试 |
| W3 | 16 | 边界与降级打磨 | 模型未就绪引导 / OOM 释放 / 权限撤销检测 / 退避验证 |
| W3 | 17 | CI 流水线 | GitHub Actions 构建+lint+test，tag 出 Release APK |
| W3 | 18 | README 与作品集叙事 | GIF、架构图、基准表、隐私声明、英文版、AI 工作流章节 |
| W3 | 19 | 发布与准确率评估 | v1.0.0 tag、50 条抽样核对 ≥80%、最终验收 |

---
