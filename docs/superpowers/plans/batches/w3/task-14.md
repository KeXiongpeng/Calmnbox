### Task 14: 检索层（关键词提取 + 时间解析 + LIKE 查询组装）

**前置依赖：** W1 NotificationDao.searchByKeyword；W2 全部模型链路已完成但不被本任务调用。

## Files

**Create:**
- app/src/main/java/com/calm/inbox/features/chat/KeywordExtractor.kt
- app/src/main/java/com/calm/inbox/features/chat/TimeQueryParser.kt
- app/src/main/java/com/calm/inbox/features/chat/NotificationRetriever.kt
- app/src/test/java/com/calm/inbox/features/chat/KeywordExtractorTest.kt
- app/src/test/java/com/calm/inbox/features/chat/TimeQueryParserTest.kt
- app/src/test/java/com/calm/inbox/features/chat/NotificationRetrieverTest.kt

**Modify:** 无

## Interfaces

**Produces（骨架契约逐字遵守）：**

~~~kotlin
data class TimeRange(val start: Long, val end: Long)

object TimeQueryParser {
    fun parse(question: String, now: java.time.LocalDateTime): TimeRange?
}
~~~

附加产出：

~~~kotlin
object KeywordExtractor {
    const val MAX_KEYWORDS = 3
    fun extract(question: String): List<String>
}
~~~

~~~kotlin
class NotificationRetriever(private val dao: NotificationDao) {
    suspend fun search(
        question: String,
        now: LocalDateTime,
        defaultRange: TimeRange? = null,
    ): List<NotificationEntity>
}
~~~

**Consumes:** NotificationDao.searchByKeyword(keyword, start, end)。DAO SQL 与 W1 保持不变。

## Step 1: 写失败测试

KeywordExtractorTest 覆盖：

~~~kotlin
@Test
fun extractsKnownChinesePhraseFromNaturalQuestion() {
    assertThat(KeywordExtractor.extract("我的验证码是多少？"))
        .containsExactly("验证码")
}

@Test
fun extractsMultipleKnownPhrasesAndKeepsAtMostThree() {
    val result = KeywordExtractor.extract("昨天快递、外卖和账单分别是什么")
    assertThat(result.size).isAtMost(3)
    assertThat(result).containsAtLeast("快递", "外卖", "账单")
}

@Test
fun extractsLatinKeywordAndLowercasesIt() {
    assertThat(KeywordExtractor.extract("Where is JD order")).containsExactly("order")
}

@Test
fun stripsQuestionAndPossessiveWords() {
    assertThat(KeywordExtractor.extract("我的取件码是多少")).containsExactly("取件码")
}

@Test
fun blankQuestionReturnsEmptyList() {
    assertThat(KeywordExtractor.extract("   ")).isEmpty()
}
~~~

TimeQueryParserTest 使用固定 now = LocalDateTime.of(2026, 9, 16, 15, 30)，并在 @Before/@After 固定与恢复 TimeZone Asia/Shanghai。必须断言：

- 今天：2026-09-16 00:00:00.000 至 2026-09-16 23:59:59.999；
- 昨天：2026-09-15 全天；
- 前天：2026-09-14 全天；
- 前天以前全部：0 至 2026-09-14 23:59:59.999；
- 最近3天：now.minusDays(3) 至 now；
- 这周：2026-09-14 00:00:00.000 至 now；
- “验证码是多少”无时间词返回 null；
- “最近0天”与“最近99天”返回 null。

NotificationRetrieverTest 使用内存 Room 预置多条不同时间、标题、正文的通知，验证：
1. 默认时间范围仅查今天；
2. “昨天的验证码”只返回昨天且标题/正文命中；
3. 多关键词结果按 id 去重、postedAt 降序；
4. 最多 50 条；
5. 无关键词返回空列表；
6. LIKE 只做大小写归一，不调用网络。

核心用例：

~~~kotlin
@Test
fun searchCombinesTimeAndKeyword() = runTest {
    dao.insert(item(id = 1, title = "验证码", text = "123456", postedAt = today10))
    dao.insert(item(id = 2, title = "快递", text = "验证码 998", postedAt = yesterday10))
    val result = retriever.search("昨天的验证码是多少", now)
    assertThat(result.map { it.id }).containsExactly(2L)
}
~~~

## Step 2: 确认失败

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.chat.KeywordExtractorTest" --tests "com.calm.inbox.features.chat.TimeQueryParserTest" --tests "com.calm.inbox.features.chat.NotificationRetrieverTest"
~~~

**Expected:** Unresolved reference KeywordExtractor / TimeQueryParser / NotificationRetriever。

## Step 3: 最小实现

KeywordExtractor：

~~~kotlin
object KeywordExtractor {
    const val MAX_KEYWORDS = 3

    private val knownPhrases = listOf(
        "验证码", "取件码", "快递", "物流", "外卖", "账单", "还款",
        "会议", "日程", "订单", "支付", "登录", "快递柜", "驿站"
    )

    private val timePhrases = listOf(
        "前天以前全部", "最近", "这周", "今天", "昨天", "前天", "这个星期"
    )

    private val stopwords = setOf(
        "我的", "我", "是多少", "多少", "是什么", "什么", "哪里", "请问",
        "告诉我", "查一下", "帮我", "看看", "the", "is", "are", "where", "my",
        "what", "a", "an", "of", "for", "in", "on"
    )

    fun extract(question: String): List<String> {
        var normalized = question.lowercase().replace("\\s+".toRegex(), " ").trim()
        if (normalized.isEmpty()) return emptyList()
        timePhrases.forEach { phrase ->
            Regex("\\d{1,2}\\s*$phrase|$phrase").findAll(normalized).toList()
                .sortedByDescending { it.value.length }
                .forEach { normalized = normalized.replace(it.value, " ") }
        }

        val known = knownPhrases.filter { normalized.contains(it) }.distinct()
        if (known.isNotEmpty()) return known.take(MAX_KEYWORDS)

        val latin = Regex("[a-z][a-z0-9_-]{1,40}").findAll(normalized)
            .map { it.value }
            .filter { it !in stopwords }
            .distinct()
        val chinese = normalized.split("，", "。", "？", "?", "！", "!", "；", ";", "，", ",", " ")
            .map { segment ->
                stopwords.fold(segment) { acc, stop -> acc.replace(stop, "") }.trim()
            }
            .filter { it.length >= 2 && it !in stopwords }
        return (latin + chinese).distinct().take(MAX_KEYWORDS)
    }
}
~~~

TimeQueryParser：

~~~kotlin
data class TimeRange(val start: Long, val end: Long)

object TimeQueryParser {
    fun parse(question: String, now: LocalDateTime): TimeRange? {
        val text = question.trim()
        val specialBefore = Regex("前天以前(全部)?")
        if (specialBefore.containsMatchIn(text)) {
            val boundary = now.toLocalDate().minusDays(2)
            return TimeRange(0L, endOfDay(boundary))
        }

        Regex("最近\\s*(\\d{1,2})\\s*天").find(text)?.let { match ->
            val days = match.groupValues[1].toIntOrNull() ?: return null
            if (days !in 1..31) return null
            return TimeRange(toMillis(now.minusDays(days.toLong())), toMillis(now))
        }

        if (text.contains("这周") || text.contains("这个星期")) {
            val monday = now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return TimeRange(startOfDay(monday), toMillis(now))
        }
        if (text.contains("今天")) return fullDay(now.toLocalDate())
        if (text.contains("昨天")) return fullDay(now.toLocalDate().minusDays(1))
        if (text.contains("前天")) return fullDay(now.toLocalDate().minusDays(2))
        return null
    }

    fun fullDay(date: LocalDate): TimeRange =
        TimeRange(startOfDay(date), endOfDay(date))

    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun endOfDay(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

    fun toMillis(value: LocalDateTime): Long =
        value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
~~~

NotificationRetriever：

~~~kotlin
class NotificationRetriever(private val dao: NotificationDao) {
    suspend fun search(
        question: String,
        now: LocalDateTime,
        defaultRange: TimeRange? = null,
    ): List<NotificationEntity> {
        val range = TimeQueryParser.parse(question, now) ?: defaultRange ?: return emptyList()
        val keywords = KeywordExtractor.extract(question)
        if (keywords.isEmpty()) return emptyList()

        val byId = LinkedHashMap<Long, NotificationEntity>()
        for (keyword in keywords) {
            dao.searchByKeyword(keyword = keyword, start = range.start, end = range.end)
                .forEach { item -> byId.putIfAbsent(item.id, item) }
        }
        return byId.values
            .sortedWith(compareByDescending<NotificationEntity> { it.postedAt }.thenByDescending { it.id })
            .take(MAX_RESULTS)
    }

    companion object { const val MAX_RESULTS = 50 }
}
~~~

## Step 4: 确认通过并回归

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.chat.KeywordExtractorTest" --tests "com.calm.inbox.features.chat.TimeQueryParserTest" --tests "com.calm.inbox.features.chat.NotificationRetrieverTest"
.\gradlew.bat :app:testDebugUnitTest
~~~

**Expected:** 三个测试类全部 PASSED；W1/W2 既有测试无回归。

## Step 5: Commit

~~~powershell
git add app/src/main/java/com/calm/inbox/features/chat/KeywordExtractor.kt app/src/main/java/com/calm/inbox/features/chat/TimeQueryParser.kt app/src/main/java/com/calm/inbox/features/chat/NotificationRetriever.kt app/src/test/java/com/calm/inbox/features/chat
git commit -m "feat(chat): extract keywords, parse time ranges, and retrieve local citations"
~~~
