### Task 15: 问答聊天（ChatRepository + 流式 UI + 引用跳转）

**前置依赖：** Task 14 检索层；W1 ChatMessageDao 与导航；W2 LlmEngine / FakeLlmEngine。

## Files

**Create:**
- app/src/main/java/com/calm/inbox/features/chat/ChatPrompts.kt
- app/src/main/java/com/calm/inbox/features/chat/ChatRepository.kt
- app/src/main/java/com/calm/inbox/features/chat/ChatViewModel.kt
- app/src/main/java/com/calm/inbox/features/chat/ChatScreen.kt
- app/src/test/java/com/calm/inbox/features/chat/ChatPromptsTest.kt
- app/src/test/java/com/calm/inbox/features/chat/ChatRepositoryTest.kt
- app/src/test/java/com/calm/inbox/features/chat/ChatViewModelTest.kt

**Modify:**
- app/src/main/java/com/calm/inbox/AppNavHost.kt
- app/src/main/java/com/calm/inbox/features/inbox/InboxViewModel.kt
- app/src/main/java/com/calm/inbox/features/inbox/InboxScreen.kt

## Interfaces

**Produces（骨架契约逐字遵守）：**

~~~kotlin
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
    fun history(): Flow<List<ChatMessageEntity>>
}
~~~

附加产出：

~~~kotlin
object ChatPrompts {
    fun build(question: String, notifications: List<NotificationEntity>): String
}
~~~

~~~kotlin
@HiltViewModel
class ChatViewModel(private val repository: ChatRepository) : ViewModel() {
    val history: StateFlow<List<ChatMessageEntity>>
    val streamingAnswer: StateFlow<String>
    val citations: StateFlow<List<Citation>>
    val isStreaming: StateFlow<Boolean>
    fun ask(question: String)
}
~~~

**Consumes:** TimeQueryParser、KeywordExtractor、NotificationRetriever、NotificationDao.searchByKeyword、LlmEngine.generateStream、ChatMessageDao。

## Step 1: 写失败测试

ChatPromptsTest 覆盖：
1. prompt 包含“仅依据以下通知内容回答”；
2. 每条通知有 [id]、appName、title、text；
3. 空检索列表输出“（无匹配通知）”，仍包含问题；
4. prompt 不包含 ChatML 标记，MNN 自动套模板；
5. text 使用原始 ≤100 字存储值，不再额外放大上下文。

ChatRepositoryTest 使用内存 Room + FakeLlmEngine + Clock.fixed。预置今天验证码通知，并断言：

~~~kotlin
@Test
fun askStreamsAnswerPersistsHistoryAndCitations() = runTest {
    val notificationId = notificationDao.insert(verification)
    val repository = ChatRepository(engine, notificationDao, chatDao, clock)

    val events = repository.ask("我的验证码是多少").toList()

    assertThat(events.first()).isInstanceOf(ChatEvent.Chunk::class.java)
    assertThat(events.last()).isEqualTo(
        ChatEvent.Done(listOf(Citation(notificationId, "登录验证码")))
    )
    val saved = chatDao.observeAll().first()
    assertThat(saved.map { it.role }).containsExactly("user", "assistant").inOrder()
    assertThat(saved.last().citationIds).isEqualTo(notificationId.toString())
    assertThat(saved.last().content).contains("123456")
    assertThat(engine.receivedPrompts.single()).contains("仅依据以下通知内容回答")
    assertThat(engine.receivedPrompts.single())
        .contains("[" + notificationId + "] Example|登录验证码|验证码 123456")
}
~~~

另需覆盖：
- 无命中时 citations 为空，assistant 仍入库存空 citationIds；
- 多个 Chunk 依序发射；
- history 只读 DAO，不触发 engine.generateStream；
- 空白问题直接抛 IllegalArgumentException，不写 user 消息、不调模型。

ChatViewModelTest 用 FakeChatRepository 验证 ask 前清空 streamingAnswer，Chunk 累加，Done 保存 citations 并结束 isStreaming，history 由 stateIn 暴露。

## Step 2: 确认失败

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.chat.ChatPromptsTest" --tests "com.calm.inbox.features.chat.ChatRepositoryTest" --tests "com.calm.inbox.features.chat.ChatViewModelTest"
~~~

**Expected:** Unresolved reference ChatRepository / ChatViewModel。

## Step 3: 最小实现

ChatPrompts：

~~~kotlin
object ChatPrompts {
    fun build(question: String, notifications: List<NotificationEntity>): String {
        val context = if (notifications.isEmpty()) {
            "（无匹配通知）"
        } else {
            notifications.joinToString("\n") { item ->
                "[" + item.id + "] " + item.appName + "|" + item.title + "|" + item.text
            }
        }
        return buildString {
            append("仅依据以下通知内容回答。")
            append("找不到可靠答案时必须明确说“通知中没有找到”。")
            append("不要编造验证码、快递、金额或时间。回答需简洁。\n\n")
            append("通知内容：\n")
            append(context)
            append("\n\n用户问题：")
            append(question.trim())
        }
    }
}
~~~

ChatRepository 构造函数保持骨架四个参数，不在其中追加 retriever；内部每次创建 NotificationRetriever(notificationDao)，避免破坏 W3 调用方契约：

~~~kotlin
class ChatRepository(
    private val engine: LlmEngine,
    private val notificationDao: NotificationDao,
    private val chatDao: ChatMessageDao,
    private val clock: Clock,
) {
    suspend fun ask(question: String): Flow<ChatEvent> {
        val normalized = question.trim()
        require(normalized.isNotEmpty()) { "question must not be blank" }

        val now = LocalDateTime.now(clock)
        val today = TimeQueryParser.fullDay(now.toLocalDate())
        val notifications = NotificationRetriever(notificationDao)
            .search(normalized, now, defaultRange = today)
        val citations = notifications.map { Citation(it.id, it.title) }
        chatDao.insert(
            ChatMessageEntity(role = "user", content = normalized, createdAt = clock.millis())
        )

        return flow {
            val answer = StringBuilder()
            engine.generateStream(ChatPrompts.build(normalized, notifications))
                .collect { chunk ->
                    answer.append(chunk)
                    emit(ChatEvent.Chunk(chunk))
                }
            chatDao.insert(
                ChatMessageEntity(
                    role = "assistant",
                    content = answer.toString(),
                    citationIds = citations.joinToString(",") { it.notificationId.toString() },
                    createdAt = clock.millis()
                )
            )
            emit(ChatEvent.Done(citations))
        }
    }

    fun history(): Flow<List<ChatMessageEntity>> = chatDao.observeAll()
}
~~~

ChatViewModel 在 viewModelScope 中 ask：
- 开始时 isStreaming=true、streamingAnswer=""、citations=emptyList；
- Chunk 累加；
- Done 保存 citations；
- finally isStreaming=false，streamingAnswer 保留至下一次提问；
- history 用 stateIn(WhileSubscribed(5000), emptyList())。

ChatScreen：
- LazyColumn 渲染 history；用户消息右侧主色气泡，assistant 左侧表面色气泡；
- 底部 OutlinedTextField + 发送 IconButton；
- streaming 中 CircularProgressIndicator，并禁用发送；
- assistant 下方 citation chips，点击调用 onCitationClick(notificationId)；
- 空态文案：“问我今天的通知，例如：我的验证码是多少？”。

引用跳转：
- AppNavHost 的 inbox composable 改为 route "inbox?notificationId={notificationId}"，arguments 为 optional NavArgument.Long，默认 -1；startDestination 仍是 "inbox"，导航 route 名仍是 inbox；
- Citation chip 调用 navController.navigate("inbox?notificationId=" + id)；
- InboxViewModel 增加 SavedStateHandle 参数并暴露 highlightId: Long；
- InboxScreen 给 highlightId 对应 Card 使用 MaterialTheme.colorScheme.tertiary 的 2dp 边框，并滚动到可见位置；
- 已在收件箱时不重复压栈，使用 launchSingleTop。

## Step 4: 确认通过并回归

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.chat.ChatPromptsTest" --tests "com.calm.inbox.features.chat.ChatRepositoryTest" --tests "com.calm.inbox.features.chat.ChatViewModelTest"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
~~~

**Expected:** 三个测试类全部 PASSED；全量单测与 Debug 构建成功。

## Step 5: Commit

~~~powershell
git add app/src/main/java/com/calm/inbox/features/chat app/src/main/java/com/calm/inbox/AppNavHost.kt app/src/main/java/com/calm/inbox/features/inbox app/src/test/java/com/calm/inbox/features/chat
git commit -m "feat(chat): answer notification questions with local citations and streaming UI"
~~~
