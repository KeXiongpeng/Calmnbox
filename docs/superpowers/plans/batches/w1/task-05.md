### Task 5: 设置页与 DataStore（黑名单 + 阈值 + 权限引导）

**前置依赖：** Task 1 NavHost，Task 3 NotificationEntityFactory 与通知监听服务，Task 2 DAO。

## Files

**Create:**
- app/src/main/java/com/calm/inbox/features/settings/SettingsRepository.kt
- app/src/main/java/com/calm/inbox/features/settings/NotificationAccessChecker.kt
- app/src/main/java/com/calm/inbox/features/settings/SettingsViewModel.kt
- app/src/main/java/com/calm/inbox/features/settings/SettingsScreen.kt
- app/src/test/java/com/calm/inbox/features/settings/SettingsRepositoryTest.kt
- app/src/test/java/com/calm/inbox/features/settings/SettingsViewModelTest.kt

**Modify:**
- app/src/main/java/com/calm/inbox/AppNavHost.kt
- app/src/main/java/com/calm/inbox/core/notifications/CalmNotificationListenerService.kt
- app/src/main/java/com/calm/inbox/di/AppModule.kt

## Interfaces

**Produces（骨架通用契约逐字遵守）：**

~~~kotlin
class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    val blacklist: Flow<Set<String>>
    val noiseThreshold: Flow<Int>
    suspend fun addBlacklist(pkg: String)
    suspend fun removeBlacklist(pkg: String)
}
~~~

附加产出：

~~~kotlin
class NotificationAccessChecker(private val context: Context) {
    fun isGranted(): Boolean
    fun createIntent(): Intent
}
~~~

~~~kotlin
@HiltViewModel
class SettingsViewModel(
    repository: SettingsRepository,
    accessChecker: NotificationAccessChecker,
) : ViewModel() {
    val blacklist: StateFlow<Set<String>>
    val noiseThreshold: StateFlow<Int>
    val notificationAccessGranted: StateFlow<Boolean>
    fun addPackage(rawPackage: String)
    fun removePackage(pkg: String)
    fun setNoiseThreshold(value: Int)
    fun refreshPermission()
}
~~~

**Consumes:** DataStore Preferences；Task 3 NotificationEntityFactory；骨架默认阈值 1。

## Step 1: 写失败测试

SettingsRepositoryTest 使用 @TempDir 或 JUnit TemporaryFolder 创建独立 DataStore 文件，覆盖：

1. blacklist 初始值包含 com.calm.inbox。
2. addBlacklist 追加并去重，包名 trim，空包名拒绝。
3. removeBlacklist 可以移除用户包名，但不能移除 com.calm.inbox。
4. noiseThreshold 初始 1，写入 3 后读回 3。
5. setNoiseThreshold 将 0、6 分别收敛为 1、5。

SettingsViewModelTest 用 Fake repository 与可切换 checker 验证 stateIn 初值、add/remove/set 委托、权限刷新。Flow 断言用 Turbine。

核心测试片段：

~~~kotlin
@Test
fun selfPackageCannotBeRemoved() = runTest {
    repository.addBlacklist("noisy.app")
    repository.removeBlacklist("com.calm.inbox")
    assertThat(repository.blacklist.first()).contains("com.calm.inbox")
    assertThat(repository.blacklist.first()).contains("noisy.app")
}
~~~

## Step 2: 确认失败

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.settings.*"
~~~

**Expected:** Unresolved reference SettingsRepository / SettingsViewModel。

## Step 3: 最小实现

SettingsRepository：

~~~kotlin
class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    val blacklist: Flow<Set<String>> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[BLACKLIST_KEY]?.plus(SELF_PACKAGE) ?: setOf(SELF_PACKAGE) }

    val noiseThreshold: Flow<Int> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[NOISE_THRESHOLD_KEY] ?: DEFAULT_NOISE_THRESHOLD }

    suspend fun addBlacklist(pkg: String) {
        val normalized = pkg.trim()
        if (normalized.isEmpty()) return
        dataStore.edit { it[BLACKLIST_KEY] = (it[BLACKLIST_KEY] ?: emptySet()) + normalized }
    }

    suspend fun removeBlacklist(pkg: String) {
        if (pkg == SELF_PACKAGE) return
        dataStore.edit { prefs ->
            val next = (prefs[BLACKLIST_KEY] ?: emptySet()) - pkg
            prefs[BLACKLIST_KEY] = next
        }
    }

    suspend fun setNoiseThreshold(value: Int) {
        dataStore.edit { it[NOISE_THRESHOLD_KEY] = value.coerceIn(1, 5) }
    }

    companion object {
        const val SELF_PACKAGE = "com.calm.inbox"
        const val DEFAULT_NOISE_THRESHOLD = 1
        private val BLACKLIST_KEY = stringSetPreferencesKey("blacklist")
        private val NOISE_THRESHOLD_KEY = intPreferencesKey("noise_threshold")
    }
}
~~~

骨架契约未列出 setNoiseThreshold，但 ViewModel 需要写入方法；这是同名语义的自然补充，方法名固定为 setNoiseThreshold。

NotificationAccessChecker 用 NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)；Intent 使用 Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS。

SettingsScreen 包含：
- 通知权限卡片：未授权时红色提示 + 打开系统设置按钮；已授权显示绿色状态。
- 黑名单卡片：TextField 输入包名、添加按钮、AssistChip 列表；com.calm.inbox 不可删除。
- 降噪阈值卡片：Slider 范围 1..5，说明“重要度 ≤ 阈值的营销/低价值通知将折叠”。
- 使用 Material 3 Scaffold/Card/Button/TextField，不新增依赖。

AppNavHost 将 settings 临时路由替换为 hiltViewModel 的 SettingsScreen。AppModule 中用同一 DataStore 文件 calm_settings 提供单例：

~~~kotlin
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore("calm_settings")

@Provides
@Singleton
fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.settingsDataStore
~~~

再提供 SettingsRepository、NotificationAccessChecker，并修改 CalmNotificationListenerService 注入 SettingsRepository，将 factory.create 的黑名单参数改为：

~~~kotlin
settingsRepository.blacklist.first()
~~~

## Step 4: 确认通过

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.settings.*"
.\gradlew.bat :app:assembleDebug
~~~

**Expected:** Repository 与 ViewModel 测试全部 PASSED，settings 路由可编译。

## Step 5: Commit

~~~powershell
git add app/src/main/java/com/calm/inbox/features/settings app/src/main/java/com/calm/inbox/AppNavHost.kt app/src/main/java/com/calm/inbox/core/notifications/CalmNotificationListenerService.kt app/src/main/java/com/calm/inbox/di/AppModule.kt app/src/test/java/com/calm/inbox/features/settings
git commit -m "feat(settings): manage notification blacklist, noise threshold, and access guidance"
~~~
