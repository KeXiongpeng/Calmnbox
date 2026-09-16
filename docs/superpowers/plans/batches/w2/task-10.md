### Task 10: Prompt 构造与容错解析（分类 prompt + JSON 容错解析 + FakeLlmEngine）

> **已核查事实（写入本任务的具体值）**
> - **MNN 会自动套用对话模板，prompt 不要手写 ChatML**。Task 9 的 C++ 桥调用 `Llm::chat({input}, callback)`（`app/src/main/cpp/calm_llm_jni.cpp` 已实现），MNN 依据模型包 `config.json` 的 `prompt_template`（Qwen2.5-1.5B-Instruct 为 ChatML 格式）自动包装 user/assistant 轮次。因此本任务 `buildBatchPrompt` 只产出**纯指令文本**，禁止包含 `<|im_start|>` / `<|im_end|>`（已对照 MNN 官方 App `LlmSession` 的 `submit` 用法核实）。
> - **JSON 解析使用 Android 内置 `org.json`**（`JSONObject` / `JSONException`，无需新增依赖）。单元测试中 `org.json` 需要 Android 运行时，故解析器测试用 Robolectric（骨架已含 Robolectric 4.13）；prompt 构造与 FakeLlmEngine 测试为纯 JUnit4 + `runTest`。
> - **容错策略与降级链路对齐**（设计文档 §6）：解析器对「部分条目非法」返回合法子集；对「整段无可解析对象」返回 `null`，由 Task 11 的 `HybridClassifier` 执行「重试 1 次 → 降级规则」。

**前置依赖（已就绪，直接使用）**
- `app/src/main/java/com/calm/inbox/core/database/entity/NotificationEntity.kt`（W1 Task 2 契约，`id/appName/title/text` 字段逐字消费）。
- `app/src/main/java/com/calm/inbox/core/database/Category.kt`（W1 Task 2 契约，10 个枚举值逐字消费）。
- `app/src/main/java/com/calm/inbox/core/model/LlmEngine.kt`（Task 9 契约，`FakeLlmEngine` 实现该接口）。

## Files

**Create:**
- `app/src/main/java/com/calm/inbox/core/classify/ClassificationPrompts.kt`
- `app/src/main/java/com/calm/inbox/core/classify/ClassificationJsonParser.kt`
- `app/src/test/java/com/calm/inbox/core/model/FakeLlmEngine.kt`（骨架指定路径，W3 复用）

**Modify:** 无

**Test:**
- `app/src/test/java/com/calm/inbox/core/classify/ClassificationPromptsTest.kt`
- `app/src/test/java/com/calm/inbox/core/classify/ClassificationJsonParserTest.kt`
- `app/src/test/java/com/calm/inbox/core/model/FakeLlmEngineTest.kt`

## Interfaces

**Consumes（契约逐字引用，不得改动）：**

```kotlin
// W1 Task 2 生产：app/src/main/java/com/calm/inbox/core/database/entity/NotificationEntity.kt
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,            // 已截断 ≤100 字
    val postedAt: Long,
    val category: String = "UNCATEGORIZED",
    val importance: Int = 0,
    val summary: String = "",
    val digest: String
)

// W1 Task 2 生产：app/src/main/java/com/calm/inbox/core/database/Category.kt
enum class Category {
    UNCATEGORIZED, VERIFICATION, EXPRESS, FINANCE, SOCIAL,
    WORK, SHOPPING, SYSTEM, MARKETING, OTHER
}

// Task 9 生产：app/src/main/java/com/calm/inbox/core/model/LlmEngine.kt
interface LlmEngine {
    val state: StateFlow<EngineState>
    suspend fun load(modelDir: String)
    suspend fun generateStream(prompt: String): Flow<String>
    fun release()
}
```

**Produces（本任务新增，Task 11 / Task 12 / W3 直接消费）：**

```kotlin
// app/src/main/java/com/calm/inbox/core/classify/ClassificationPrompts.kt
object ClassificationPrompts {
    const val MAX_BATCH_SIZE = 20
    const val MAX_ITEM_TEXT_LENGTH = 100
    val CATEGORY_NAMES: List<String>
    fun buildBatchPrompt(items: List<NotificationEntity>): String
}

// app/src/main/java/com/calm/inbox/core/classify/ClassificationJsonParser.kt
data class ParsedClassification(
    val id: Long,
    val category: String,
    val importance: Int,     // 已收敛到 1~5
    val summary: String,
)

object ClassificationJsonParser {
    fun parse(raw: String): List<ParsedClassification>?   // 无任何合法条目时返回 null
}

// app/src/test/java/com/calm/inbox/core/model/FakeLlmEngine.kt（测试替身，骨架指定路径）
class FakeLlmEngine(
    private val responses: List<String> = emptyList(),
    private val chunkSize: Int = 8,
) : LlmEngine {
    val receivedPrompts: MutableList<String>
    val loadedModelDirs: MutableList<String>
    var releaseCount: Int                                   // private set
    var loadError: RuntimeException?                        // load 时抛出
    var generateError: RuntimeException?                    // generateStream 时抛出
    var requireLoaded: Boolean                              // true=未 load 就 generate 抛异常
}
```

**设计决定（实现时照此执行）：**
- `buildBatchPrompt`：空列表或 `size > 20` 抛 `IllegalArgumentException`（分批由 Task 11 的 `chunked(MAX_BATCH_SIZE)` 负责）；`text` 再做一次 `take(100)` 防御性截断（W1 入库时已截断，双保险）。
- `ClassificationJsonParser.parse`：不先解析整体数组，而是**扫描出所有顶层完整 `{...}` 对象逐个解析**——天然容忍 markdown 代码块、前后闲聊文字、数组截断、单对象输出、尾逗号等一切包装层噪声；单条对象缺 `id` / `category` 非法 / `importance` 缺失或不可解析 → 跳过该条；`importance` 越界收敛到 1~5；`category` 大小写归一；`summary` 缺省空串。

## Step 1: 写失败测试

创建 `app/src/test/java/com/calm/inbox/core/classify/ClassificationPromptsTest.kt`：

```kotlin
package com.calm.inbox.core.classify

import com.calm.inbox.core.database.entity.NotificationEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ClassificationPromptsTest {

    private fun item(id: Long, text: String = "内容") = NotificationEntity(
        id = id,
        packageName = "com.example.app",
        appName = "示例App",
        title = "标题$id",
        text = text,
        postedAt = 1_700_000_000_000 + id,
        digest = "digest-$id",
    )

    @Test
    fun `prompt 包含全部 10 个类别枚举名`() {
        val prompt = ClassificationPrompts.buildBatchPrompt(listOf(item(1)))

        ClassificationPrompts.CATEGORY_NAMES.forEach { name ->
            assertThat(prompt).contains(name)
        }
        assertThat(ClassificationPrompts.CATEGORY_NAMES).hasSize(10)
    }

    @Test
    fun `prompt 包含每条通知的 id 应用名 标题 文本`() {
        val prompt = ClassificationPrompts.buildBatchPrompt(
            listOf(item(7, text = "您的快递已到菜鸟驿站"))
        )

        assertThat(prompt).contains("id=7")
        assertThat(prompt).contains("app=示例App")
        assertThat(prompt).contains("title=标题7")
        assertThat(prompt).contains("text=您的快递已到菜鸟驿站")
    }

    @Test
    fun `超过 100 字的文本被截断`() {
        val longText = "长".repeat(150)
        val prompt = ClassificationPrompts.buildBatchPrompt(listOf(item(1, text = longText)))

        assertThat(prompt).contains("text=" + "长".repeat(100))
        assertThat(prompt).doesNotContain("text=" + "长".repeat(101))
    }

    @Test
    fun `prompt 包含 JSON 输出格式说明`() {
        val prompt = ClassificationPrompts.buildBatchPrompt(listOf(item(1)))

        assertThat(prompt).contains("\"category\"")
        assertThat(prompt).contains("\"importance\"")
        assertThat(prompt).contains("\"summary\"")
        assertThat(prompt).contains("JSON")
    }

    @Test
    fun `空列表抛出 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ClassificationPrompts.buildBatchPrompt(emptyList())
        }
    }

    @Test
    fun `超过 20 条抛出 IllegalArgumentException`() {
        val items = (1..21).map { item(it.toLong()) }

        assertThrows(IllegalArgumentException::class.java) {
            ClassificationPrompts.buildBatchPrompt(items)
        }
    }
}
```

创建 `app/src/test/java/com/calm/inbox/core/classify/ClassificationJsonParserTest.kt`（`org.json` 需要 Android 运行时，用 Robolectric）：

```kotlin
package com.calm.inbox.core.classify

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClassificationJsonParserTest {

    @Test
    fun `合法 JSON 数组全部解析`() {
        val raw = """
            [{"id": 1, "category": "VERIFICATION", "importance": 5, "summary": "验证码1234"},
             {"id": 2, "category": "EXPRESS", "importance": 4, "summary": "快递已到驿站"}]
        """.trimIndent()

        val result = ClassificationJsonParser.parse(raw)!!

        assertThat(result).hasSize(2)
        assertThat(result[0]).isEqualTo(
            ParsedClassification(id = 1, category = "VERIFICATION", importance = 5, summary = "验证码1234")
        )
        assertThat(result[1]).isEqualTo(
            ParsedClassification(id = 2, category = "EXPRESS", importance = 4, summary = "快递已到驿站")
        )
    }

    @Test
    fun `markdown 代码块包裹可解析`() {
        val raw = """
            ```json
            [{"id": 1, "category": "WORK", "importance": 4, "summary": "会议提醒"}]
            ```
        """.trimIndent()

        val result = ClassificationJsonParser.parse(raw)!!

        assertThat(result).hasSize(1)
        assertThat(result[0].category).isEqualTo("WORK")
    }

    @Test
    fun `前后含闲聊文字可提取`() {
        val raw = "好的，以下是分类结果：\n[{\"id\": 9, \"category\": \"SOCIAL\", \"importance\": 2, \"summary\": \"群消息\"}]\n希望对你有帮助！"

        val result = ClassificationJsonParser.parse(raw)!!

        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(9)
    }

    @Test
    fun `importance 越界上界收敛到 5`() {
        val raw = """[{"id": 1, "category": "FINANCE", "importance": 99, "summary": "账单"}]"""

        assertThat(ClassificationJsonParser.parse(raw)!![0].importance).isEqualTo(5)
    }

    @Test
    fun `importance 越界下界收敛到 1`() {
        val raw = """[{"id": 1, "category": "MARKETING", "importance": 0, "summary": "促销"}]"""

        assertThat(ClassificationJsonParser.parse(raw)!![0].importance).isEqualTo(1)
    }

    @Test
    fun `importance 为数字字符串可容错`() {
        val raw = """[{"id": 1, "category": "WORK", "importance": "3", "summary": "日报"}]"""

        assertThat(ClassificationJsonParser.parse(raw)!![0].importance).isEqualTo(3)
    }

    @Test
    fun `缺 summary 默认空串`() {
        val raw = """[{"id": 1, "category": "SYSTEM", "importance": 2}]"""

        assertThat(ClassificationJsonParser.parse(raw)!![0].summary).isEmpty()
    }

    @Test
    fun `非法 category 条目被跳过其余保留`() {
        val raw = """
            [{"id": 1, "category": "GAME", "importance": 3, "summary": "非法"},
             {"id": 2, "category": "SHOPPING", "importance": 2, "summary": "发货"}]
        """.trimIndent()

        val result = ClassificationJsonParser.parse(raw)!!

        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(2)
    }

    @Test
    fun `缺 id 条目被跳过`() {
        val raw = """[{"category": "WORK", "importance": 3, "summary": "无id"}]"""

        assertThat(ClassificationJsonParser.parse(raw)).isNull()
    }

    @Test
    fun `importance 缺失条目被跳过`() {
        val raw = """[{"id": 1, "category": "WORK", "summary": "缺重要度"}]"""

        assertThat(ClassificationJsonParser.parse(raw)).isNull()
    }

    @Test
    fun `小写 category 归一化为大写`() {
        val raw = """[{"id": 1, "category": "verification", "importance": 5, "summary": "验证码"}]"""

        assertThat(ClassificationJsonParser.parse(raw)!![0].category).isEqualTo("VERIFICATION")
    }

    @Test
    fun `单个对象输出包装为单元素列表`() {
        val raw = """{"id": 3, "category": "OTHER", "importance": 2, "summary": "其他"}"""

        val result = ClassificationJsonParser.parse(raw)!!

        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(3)
    }

    @Test
    fun `截断 JSON 保留已完成对象`() {
        val raw = """[{"id": 1, "category": "WORK", "importance": 4, "summary": "完整"},{"id": 2, "category": "SOC"""

        val result = ClassificationJsonParser.parse(raw)!!

        assertThat(result).hasSize(1)
        assertThat(result[0].summary).isEqualTo("完整")
    }

    @Test
    fun `完全无 JSON 返回 null`() {
        assertThat(ClassificationJsonParser.parse("抱歉，我无法完成分类。")).isNull()
        assertThat(ClassificationJsonParser.parse("")).isNull()
    }
}
```

创建 `app/src/test/java/com/calm/inbox/core/model/FakeLlmEngineTest.kt`（Flow 断言按骨架约定用 Turbine）：

```kotlin
package com.calm.inbox.core.model

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class FakeLlmEngineTest {

    @Test
    fun `初始状态为 NOT_LOADED`() {
        val engine = FakeLlmEngine(responses = listOf("ok"))

        assertThat(engine.state.value).isEqualTo(EngineState.NOT_LOADED)
        assertThat(engine.releaseCount).isEqualTo(0)
        assertThat(engine.receivedPrompts).isEmpty()
    }

    @Test
    fun `load 后进入 READY 并记录 modelDir`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("ok"))

        engine.load("/data/models/Qwen2.5-1.5B-Instruct-MNN")

        assertThat(engine.state.value).isEqualTo(EngineState.READY)
        assertThat(engine.loadedModelDirs).containsExactly("/data/models/Qwen2.5-1.5B-Instruct-MNN")
    }

    @Test
    fun `loadError 会被抛出`() = runTest {
        val engine = FakeLlmEngine()
        engine.loadError = RuntimeException("模型加载失败")

        assertThrows(RuntimeException::class.java) {
            engine.load("/data/models/Qwen2.5-1.5B-Instruct-MNN")
        }
    }

    @Test
    fun `未 load 调用 generateStream 抛出 IllegalStateException`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("ok"))

        assertThrows(IllegalStateException::class.java) {
            engine.generateStream("prompt")
        }
    }

    @Test
    fun `流式输出的 chunk 拼接等于完整回复`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("0123456789ABCDEF"), chunkSize = 8)
        engine.load("/models")

        engine.generateStream("prompt").test {
            assertThat(awaitItem()).isEqualTo("01234567")
            assertThat(awaitItem()).isEqualTo("89ABCDEF")
            awaitComplete()
        }
        assertThat(engine.receivedPrompts).containsExactly("prompt")
    }

    @Test
    fun `连续调用依次消费回复序列`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("first", "second"), chunkSize = 8)
        engine.load("/models")

        engine.generateStream("p1").test {
            assertThat(awaitItem()).isEqualTo("first")
            awaitComplete()
        }
        engine.generateStream("p2").test {
            assertThat(awaitItem()).isEqualTo("second")
            awaitComplete()
        }
        assertThat(engine.receivedPrompts).containsExactly("p1", "p2").inOrder()
    }

    @Test
    fun `回复序列耗尽后重复最后一条`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("only"), chunkSize = 8)
        engine.load("/models")

        repeat(2) {
            engine.generateStream("p").test {
                assertThat(awaitItem()).isEqualTo("only")
                awaitComplete()
            }
        }
    }

    @Test
    fun `空回复列表输出空流`() = runTest {
        val engine = FakeLlmEngine(responses = emptyList())
        engine.load("/models")

        engine.generateStream("p").test {
            awaitComplete()
        }
    }

    @Test
    fun `release 后回到 NOT_LOADED 且计数递增`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("ok"))
        engine.load("/models")

        engine.release()
        engine.release()

        assertThat(engine.state.value).isEqualTo(EngineState.NOT_LOADED)
        assertThat(engine.releaseCount).isEqualTo(2)
    }

    @Test
    fun `generateError 在记录 prompt 后抛出`() = runTest {
        val engine = FakeLlmEngine(responses = listOf("ok"))
        engine.load("/models")
        engine.generateError = RuntimeException("推理失败")

        assertThrows(RuntimeException::class.java) {
            engine.generateStream("prompt")
        }
        assertThat(engine.receivedPrompts).containsExactly("prompt")
    }
}
```

## Step 2: 确认测试失败

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.classify.ClassificationPromptsTest" --tests "com.calm.inbox.core.classify.ClassificationJsonParserTest" --tests "com.calm.inbox.core.model.FakeLlmEngineTest"
```

**预期失败**：编译错误 `Unresolved reference: ClassificationPrompts` / `ClassificationJsonParser` / `FakeLlmEngine`（三个被测类型尚未创建）。禁止跳过此步——必须看到编译失败输出后再进入 Step 3。

## Step 3: 最小实现

创建 `app/src/main/java/com/calm/inbox/core/classify/ClassificationPrompts.kt`：

```kotlin
package com.calm.inbox.core.classify

import com.calm.inbox.core.database.Category
import com.calm.inbox.core.database.entity.NotificationEntity

object ClassificationPrompts {

    const val MAX_BATCH_SIZE = 20
    const val MAX_ITEM_TEXT_LENGTH = 100

    val CATEGORY_NAMES: List<String> = Category.entries.map { it.name }

    fun buildBatchPrompt(items: List<NotificationEntity>): String {
        require(items.isNotEmpty()) { "items must not be empty" }
        require(items.size <= MAX_BATCH_SIZE) {
            "batch size ${items.size} exceeds MAX_BATCH_SIZE=$MAX_BATCH_SIZE"
        }
        val notificationLines = items.joinToString(separator = "\n") { item ->
            val text = item.text.take(MAX_ITEM_TEXT_LENGTH)
            "- id=${item.id} | app=${item.appName} | title=${item.title} | text=$text"
        }
        return buildString {
            append("你是手机通知分类助手。对下面的通知列表逐条分类打分，只输出一个 JSON 数组，不要输出任何其他文字。\n\n")
            append("category 只能取以下枚举值：")
            append(CATEGORY_NAMES.joinToString(separator = ", "))
            append("\n含义：VERIFICATION=验证码，EXPRESS=快递物流，FINANCE=财务账单，SOCIAL=社交消息，WORK=工作协作，SHOPPING=购物订单，SYSTEM=系统状态，MARKETING=营销推广，OTHER=其他，UNCATEGORIZED=无法判断。\n\n")
            append("importance 是 1~5 的整数：5=必须立刻看到（验证码、紧急告警）；4=重要（快递到达、账单提醒、工作消息）；3=一般有用；2=低价值；1=纯营销噪音。\n")
            append("summary 用不超过 20 个字概括通知内容。\n\n")
            append("输出格式（严格 JSON 数组，id 与输入一致）：\n")
            append("[{\"id\": 1, \"category\": \"VERIFICATION\", \"importance\": 5, \"summary\": \"淘宝验证码1234\"}]\n\n")
            append("通知列表：\n")
            append(notificationLines)
            append("\n\n现在只输出 JSON 数组：")
        }
    }
}
```

创建 `app/src/main/java/com/calm/inbox/core/classify/ClassificationJsonParser.kt`：

```kotlin
package com.calm.inbox.core.classify

import com.calm.inbox.core.database.Category
import org.json.JSONException
import org.json.JSONObject

data class ParsedClassification(
    val id: Long,
    val category: String,
    val importance: Int,     // 已收敛到 1~5
    val summary: String,
)

object ClassificationJsonParser {

    /**
     * 容错解析 LLM 输出。策略：扫描出文本中所有顶层完整 {...} 对象逐个解析，
     * 天然容忍 markdown 代码块、前后闲聊、数组截断、单对象输出等包装层噪声。
     * 单条缺 id / category 非法 / importance 缺失或不可解析 → 跳过该条；
     * 没有任何合法条目 → 返回 null（调用方执行重试 1 次后降级）。
     */
    fun parse(raw: String): List<ParsedClassification>? {
        val results = mutableListOf<ParsedClassification>()
        for (candidate in extractObjectCandidates(raw)) {
            parseOne(candidate)?.let { results += it }
        }
        return results.ifEmpty { null }
    }

    /** 按大括号配对扫描顶层完整对象；忽略字符串字面量内的花括号；未闭合的对象丢弃。 */
    private fun extractObjectCandidates(text: String): List<String> {
        val candidates = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' && inString -> {
                    current.append(ch)
                    escaped = true
                }
                ch == '"' -> {
                    current.append(ch)
                    inString = !inString
                }
                ch == '{' -> {
                    current.append(ch)
                    depth++
                }
                ch == '}' -> {
                    current.append(ch)
                    depth--
                    if (depth == 0) {
                        candidates += current.toString()
                        current.clear()
                    } else if (depth < 0) {
                        depth = 0
                        current.clear()
                    }
                }
                depth > 0 -> current.append(ch)
            }
        }
        return candidates
    }

    private fun parseOne(candidate: String): ParsedClassification? {
        return try {
            val obj = JSONObject(candidate)
            val id = obj.optLong("id", -1L)
            if (id <= 0) return null
            val category = normalizeCategory(obj.optString("category", "")) ?: return null
            val importance = normalizeImportance(obj.opt("importance")) ?: return null
            ParsedClassification(
                id = id,
                category = category,
                importance = importance,
                summary = obj.optString("summary", "").trim(),
            )
        } catch (e: JSONException) {
            null
        }
    }

    private fun normalizeCategory(raw: String): String? {
        val upper = raw.trim().uppercase()
        return if (Category.entries.any { it.name == upper }) upper else null
    }

    private fun normalizeImportance(value: Any?): Int? {
        val asInt = when (value) {
            null -> return null
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: return null
            else -> return null
        }
        return asInt.coerceIn(1, 5)
    }
}
```

创建 `app/src/test/java/com/calm/inbox/core/model/FakeLlmEngine.kt`（骨架指定路径，Task 11/12 与 W3 复用）：

```kotlin
package com.calm.inbox.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * LlmEngine 测试替身：可注入预设回复序列（按调用次序消费，耗尽后重复最后一条），
 * 按 chunkSize 切片模拟逐 token 流。Task 11/12 与 W3 复用。
 */
class FakeLlmEngine(
    private val responses: List<String> = emptyList(),
    private val chunkSize: Int = 8,
) : LlmEngine {

    private val _state = MutableStateFlow(EngineState.NOT_LOADED)
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    val receivedPrompts = mutableListOf<String>()
    val loadedModelDirs = mutableListOf<String>()
    var releaseCount = 0
        private set

    /** load 时抛出，模拟模型加载失败。 */
    var loadError: RuntimeException? = null

    /** generateStream 时抛出（先记录 prompt 再抛，模拟真实引擎收到请求后失败）。 */
    var generateError: RuntimeException? = null

    /** true 时未 load 就 generate 抛 IllegalStateException，与 MnnLlmEngine 行为一致。 */
    var requireLoaded: Boolean = true

    private var responseIndex = 0

    override suspend fun load(modelDir: String) {
        loadError?.let { throw it }
        loadedModelDirs += modelDir
        _state.value = EngineState.READY
    }

    override suspend fun generateStream(prompt: String): Flow<String> {
        if (requireLoaded && _state.value != EngineState.READY) {
            throw IllegalStateException("engine not loaded")
        }
        receivedPrompts += prompt
        generateError?.let { throw it }
        val response = if (responses.isEmpty()) {
            ""
        } else {
            responses[responseIndex.coerceAtMost(responses.lastIndex)]
        }
        if (responseIndex < responses.lastIndex) {
            responseIndex++
        }
        return flow {
            response.chunked(chunkSize.coerceAtLeast(1)).forEach { chunk ->
                emit(chunk)
            }
        }
    }

    override fun release() {
        releaseCount++
        _state.value = EngineState.NOT_LOADED
    }
}
```

## Step 4: 确认测试通过

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.classify.ClassificationPromptsTest" --tests "com.calm.inbox.core.classify.ClassificationJsonParserTest" --tests "com.calm.inbox.core.model.FakeLlmEngineTest"
```

**预期**：`BUILD SUCCESSFUL`，三个测试类全部通过（Prompts 6 用例 + Parser 14 用例 + FakeLlmEngine 10 用例，共 30 个）。若 Parser 测试出现 `Method ... not mocked`，说明漏标 `@RunWith(RobolectricTestRunner::class)`；若 Turbine 用例超时，检查是否遗漏 `runTest`。

## Step 5: 提交

```powershell
git add app/src/main/java/com/calm/inbox/core/classify/ClassificationPrompts.kt app/src/main/java/com/calm/inbox/core/classify/ClassificationJsonParser.kt app/src/test/java/com/calm/inbox/core/model/FakeLlmEngine.kt app/src/test/java/com/calm/inbox/core/classify/ClassificationPromptsTest.kt app/src/test/java/com/calm/inbox/core/classify/ClassificationJsonParserTest.kt app/src/test/java/com/calm/inbox/core/model/FakeLlmEngineTest.kt
git commit -m "feat(classify): add batch classification prompt, tolerant JSON parser and FakeLlmEngine test double"
```
