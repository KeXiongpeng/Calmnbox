### Task 3: 通知监听与入库（NotificationListenerService + 过滤/截断/去重）

**前置依赖：** Task 2 的 NotificationDao 与 NotificationEntity。

## Files

**Create:**
- app/src/main/java/com/calm/inbox/core/notifications/NotificationFilter.kt
- app/src/main/java/com/calm/inbox/core/notifications/PostedNotification.kt
- app/src/main/java/com/calm/inbox/core/notifications/NotificationEntityFactory.kt
- app/src/main/java/com/calm/inbox/core/notifications/CalmNotificationListenerService.kt
- app/src/test/java/com/calm/inbox/core/notifications/NotificationFilterTest.kt
- app/src/test/java/com/calm/inbox/core/notifications/NotificationEntityFactoryTest.kt

**Modify:**
- app/src/main/AndroidManifest.xml
- app/src/main/java/com/calm/inbox/di/AppModule.kt

## Interfaces

**Produces:** NotificationFilter 契约与骨架完全一致。附加产出：

~~~kotlin
data class PostedNotification(
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
)

class NotificationEntityFactory(private val clock: Clock) {
    fun create(item: PostedNotification, blacklist: Set<String>): NotificationEntity?
}
~~~

Service 内保留局部变量 rowId，W2 Task 11 会在 rowId != -1L 后追加 ClassificationQueue.offer(entity.copy(id = rowId))。

## Step 1: 写失败测试

NotificationFilterTest 必须覆盖：

~~~kotlin
@Test
fun digestUsesSha256OfPackageNamePostedAtAndTitle() {
    val actual = NotificationFilter.digest("com.example", 123L, "Title")
    val expected = MessageDigest.getInstance("SHA-256")
        .digest("com.example|123|Title".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    assertThat(actual).isEqualTo(expected)
}

@Test
fun truncateKeepsAtMostOneHundredCharacters() {
    assertThat(NotificationFilter.truncate("a".repeat(180), 100).length).isEqualTo(100)
}

@Test
fun blacklistRejectsPackageAndSelfIsAlwaysRejected() {
    assertThat(NotificationFilter(setOf("noisy.app")).shouldAccept("noisy.app")).isFalse()
    assertThat(NotificationFilter(setOf("com.calm.inbox")).shouldAccept("com.calm.inbox")).isFalse()
}
~~~

NotificationEntityFactoryTest 用 Clock.fixed 验证：
- 黑名单或自身包返回 null；
- 正常通知生成完整 NotificationEntity；
- text 截断至 100 字；
- digest 等于 sha256(packageName|postedAt|title)；
- postedAt 为 0 或负数时回退 clock.millis()。

## Step 2: 确认失败

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.notifications.*"
~~~

**Expected:** Unresolved reference NotificationFilter / NotificationEntityFactory。

## Step 3: 最小实现

~~~kotlin
class NotificationFilter(private val blacklist: Set<String>) {
    fun shouldAccept(pkg: String): Boolean =
        pkg.isNotBlank() && pkg != SELF_PACKAGE && pkg !in blacklist

    companion object {
        const val SELF_PACKAGE = "com.calm.inbox"
        const val MAX_TEXT_LENGTH = 100

        fun truncate(s: String, max: Int = MAX_TEXT_LENGTH): String = s.take(max.coerceAtLeast(0))

        fun digest(pkg: String, postedAt: Long, title: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest("$pkg|$postedAt|$title".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
~~~

~~~kotlin
class NotificationEntityFactory(private val clock: Clock) {
    fun create(item: PostedNotification, blacklist: Set<String>): NotificationEntity? {
        if (!NotificationFilter(blacklist).shouldAccept(item.packageName)) return null
        val postedAt = if (item.postedAt > 0L) item.postedAt else clock.millis()
        return NotificationEntity(
            packageName = item.packageName,
            appName = item.appName,
            title = item.title,
            text = NotificationFilter.truncate(item.text),
            postedAt = postedAt,
            digest = NotificationFilter.digest(item.packageName, postedAt, item.title)
        )
    }
}
~~~

CalmNotificationListenerService：

~~~kotlin
@AndroidEntryPoint
class CalmNotificationListenerService : NotificationListenerService() {
    @Inject lateinit var dao: NotificationDao
    @Inject lateinit var factory: NotificationEntityFactory

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val posted = PostedNotification(sbn.packageName, appName, title, text, sbn.postTime)

        scope.launch {
            val entity = factory.create(posted, setOf(NotificationFilter.SELF_PACKAGE)) ?: return@launch
            val rowId = dao.insert(entity)
            // W2 Task 11 在这里追加 classifyQueue.offer(entity.copy(id = rowId))。
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
~~~

Manifest 根元素加入 xmlns:tools，并添加：

~~~xml
<uses-permission
    android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    tools:ignore="ProtectedPermissions" />

<service
    android:name=".core.notifications.CalmNotificationListenerService"
    android:exported="true"
    android:label="@string/notification_listener_label"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>
~~~

AppModule 提供：

~~~kotlin
@Provides
@Singleton
fun provideClock(): Clock = Clock.systemDefaultZone()

@Provides
@Singleton
fun provideNotificationEntityFactory(clock: Clock): NotificationEntityFactory =
    NotificationEntityFactory(clock)
~~~

## Step 4: 确认通过

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.notifications.*"
.\gradlew.bat :app:assembleDebug
~~~

**Expected:** 过滤与实体工厂测试全部 PASSED，Manifest 合并成功。

## Step 5: Commit

~~~powershell
git add app/src/main/AndroidManifest.xml app/src/main/java/com/calm/inbox/core/notifications app/src/main/java/com/calm/inbox/di/AppModule.kt app/src/test/java/com/calm/inbox/core/notifications
git commit -m "feat(notifications): capture, filter, truncate, and deduplicate notifications"
~~~
