### Task 6: 收件箱 UI（通知流 + 分类 tab + 降噪聚合）

**前置依赖：** Task 2 NotificationDao，Task 4 RuleEngine，Task 5 SettingsRepository。

## Files

**Create:**
- app/src/main/java/com/calm/inbox/features/inbox/InboxViewModel.kt
- app/src/main/java/com/calm/inbox/features/inbox/InboxScreen.kt
- app/src/main/java/com/calm/inbox/core/classify/NotificationClassifierApplier.kt
- app/src/test/java/com/calm/inbox/features/inbox/InboxViewModelTest.kt
- app/src/test/java/com/calm/inbox/core/classify/NotificationClassifierApplierTest.kt

**Modify:**
- app/src/main/java/com/calm/inbox/AppNavHost.kt
- app/src/main/java/com/calm/inbox/core/notifications/CalmNotificationListenerService.kt
- app/src/main/java/com/calm/inbox/di/AppModule.kt

## Interfaces

**Produces:**

~~~kotlin
enum class InboxFilter(val label: String) { ALL("全部"), IMPORTANT("重要"), NOISY("降噪") }

data class NoiseGroup(
    val category: String,
    val count: Int,
    val latestTitle: String,
    val notifications: List<NotificationEntity>,
)

data class InboxState(
    val selectedFilter: InboxFilter = InboxFilter.ALL,
    val important: List<NotificationEntity> = emptyList(),
    val noiseGroups: List<NoiseGroup> = emptyList(),
    val expandedNoiseCategories: Set<String> = emptySet(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class InboxViewModel(
    notificationDao: NotificationDao,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<InboxState>
    fun select(filter: InboxFilter)
    fun toggleNoise(category: String)
}
~~~

附加规则应用入口：

~~~kotlin
class NotificationClassifierApplier(
    private val dao: NotificationDao,
    private val rules: RuleEngine,
) {
    suspend fun classifyPending(limit: Int = 100): Int
}
~~~

**Consumes:** observeAll()；noiseThreshold（默认 1）；RuleEngine.classify；updateClassification。

## Step 1: 写失败测试

InboxViewModelTest 使用内存 Room 或 FakeDao，预置：
- importance 5 的验证码；
- importance 1 的营销 A；
- importance 2 的购物 B；
- threshold 设为 1。

必须断言：

1. important 只含 importance > threshold 且 category != MARKETING 的通知。
2. MARKETING 恒定进入降噪组。
3. 低重要度通知 importance <= noiseThreshold 进入降噪组。
4. toggleNoise 后组内通知展开，再调用一次折叠。
5. select 不改变底层数据，只影响 UI 状态筛选。

核心片段：

~~~kotlin
@Test
fun marketingAndLowImportanceCollapseByCategory() = runTest {
    val state = viewModel.state.first { !it.isLoading }
    assertThat(state.important.map { it.id }).containsExactly(verification.id)
    assertThat(state.noiseGroups.map { it.category }).containsExactly("MARKETING").inOrder()
    assertThat(state.noiseGroups.single().count).isEqualTo(1)
}
~~~

NotificationClassifierApplier 测试覆盖：未知包名保持 UNCATEGORIZED；已知包名更新 category/importance；重复调用不重复更新。

## Step 2: 确认失败

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.inbox.*"
~~~

**Expected:** Unresolved reference InboxViewModel。

## Step 3: 最小实现

NotificationClassifierApplier：

~~~kotlin
class NotificationClassifierApplier(
    private val dao: NotificationDao,
    private val rules: RuleEngine,
) {
    suspend fun classifyPending(limit: Int = 100): Int {
        val pending = dao.getUnclassified(limit)
        var updated = 0
        for (item in pending) {
            val result = rules.classify(item) ?: continue
            dao.updateClassification(
                id = item.id,
                category = result.category.name,
                importance = result.importance,
                summary = item.title.ifBlank { item.text.take(40) }
            )
            updated++
        }
        return updated
    }
}
~~~

修改 CalmNotificationListenerService 注入 NotificationClassifierApplier。dao.insert(entity) 返回 rowId 且不等于 -1 时，调用 applier.classifyPending(1)，使 W1 入库后立即规则打标。W2 Task 11 在同一 rowId 分支追加 ClassificationQueue.offer(entity.copy(id = rowId))，由攒批 HybridClassifier 接管后续升级；Room 契约不变。

AppModule 提供 RuleEngine 与 NotificationClassifierApplier，并把 Applier 注入 CalmNotificationListenerService；只对成功插入的新通知调用 classifyPending(1)，插入返回 -1 时不调用。

InboxViewModel 用 combine(notificationDao.observeAll(), settingsRepository.noiseThreshold) 构建状态；isLoading 在首个列表到达后置 false。重要项按 importance DESC、postedAt DESC 排序。噪音按 category 分组，每组按 latest postedAt DESC 排序。

InboxScreen：
- 顶部三个 FilterChip：全部、重要、降噪；ALL 同时显示重要列表与折叠噪音组。
- 重要项 Card 显示 appName、title、summary、category、importance、相对时间。
- 噪音组 Card 显示 category、count、latestTitle 与展开/折叠按钮；展开后 LazyColumn 渲染组内全部通知。
- 空态文案：“暂无通知。授权通知访问后，CalmInbox 会在本地整理收件箱。”
- AppNavHost 的 inbox 临时路由替换为 hiltViewModel 的 InboxScreen。

## Step 4: 确认通过

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.inbox.*"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
~~~

**Expected:** InboxViewModel 测试通过，全量回归与 APK 构建成功。

## Step 5: Commit

~~~powershell
git add app/src/main/java/com/calm/inbox/features/inbox app/src/main/java/com/calm/inbox/core/classify/NotificationClassifierApplier.kt app/src/main/java/com/calm/inbox/AppNavHost.kt app/src/main/java/com/calm/inbox/core/notifications/CalmNotificationListenerService.kt app/src/main/java/com/calm/inbox/di/AppModule.kt app/src/test/java/com/calm/inbox/features/inbox app/src/test/java/com/calm/inbox/core/classify/NotificationClassifierApplierTest.kt
git commit -m "feat(inbox): show classified notification stream with collapsed noise groups"
~~~
