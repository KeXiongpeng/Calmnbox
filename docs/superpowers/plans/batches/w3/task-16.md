### Task 16: 边界与降级打磨（模型引导 / OOM 释放 / 权限撤销 / 退避验证）

**前置依赖：** Task 15 聊天链路；W2 ModelManager、LlmEngine、EngineHolder、BriefWorker。

## Files

**Create:**
- app/src/main/java/com/calm/inbox/features/chat/EngineReadiness.kt
- app/src/main/java/com/calm/inbox/features/chat/ChatPrecondition.kt
- app/src/main/java/com/calm/inbox/core/notifications/NotificationAccessMonitor.kt
- app/src/test/java/com/calm/inbox/features/chat/EngineReadinessTest.kt
- app/src/test/java/com/calm/inbox/features/chat/ChatViewModelRecoveryTest.kt
- app/src/test/java/com/calm/inbox/core/notifications/NotificationAccessMonitorTest.kt

**Modify:**
- app/src/main/java/com/calm/inbox/features/chat/ChatViewModel.kt
- app/src/main/java/com/calm/inbox/features/chat/ChatScreen.kt
- app/src/main/java/com/calm/inbox/features/settings/SettingsViewModel.kt
- app/src/main/java/com/calm/inbox/features/settings/SettingsScreen.kt
- app/src/main/java/com/calm/inbox/di/AppModule.kt

## Interfaces

**Produces:**

~~~kotlin
sealed interface ChatPrecondition {
    data object Ready : ChatPrecondition
    data object NeedsModel : ChatPrecondition
    data object EngineError : ChatPrecondition
}
~~~

~~~kotlin
class EngineReadiness(
    private val isModelReady: () -> Boolean,
    private val modelPath: () -> String,
    private val engine: LlmEngine,
    private val holder: EngineHolder,
) {
    suspend fun prepare(): ChatPrecondition
    fun releaseAfterFailure()
}
~~~

~~~kotlin
class NotificationAccessMonitor(
    private val isGranted: () -> Boolean,
    private val refreshInterval: Duration = Duration.ofSeconds(1),
) {
    fun observe(): Flow<Boolean>
}
~~~

**Consumes:** ModelManager.isModelReady/modelDir、EngineHolder.acquire、LlmEngine.state/release、W1 NotificationAccessChecker、W2 BriefScheduler 退避配置。

## Step 1: 写失败测试

EngineReadinessTest 覆盖：

1. model 未下载返回 NeedsModel，不调用 holder.acquire，不触发 load。
2. model 已下载且 engine READY 返回 Ready，并调用 holder.acquire 重置空闲计时。
3. load 抛 RuntimeException 返回 EngineError。
4. releaseAfterFailure 调用 engine.release 一次。
5. NOT_LOADED 状态下 releaseAfterFailure 也调用 release，保证 native 指针兜底清理。

测试用 FakeLlmEngine、runTest backgroundScope 构造 EngineHolder，lambda 直接返回临时模型目录路径。

ChatViewModelRecoveryTest 覆盖：

1. NeedsModel 时不调用 repository.ask，UI 状态显示“请先在设置页下载本地模型”；
2. EngineError 时显示“模型加载失败，请重试”；
3. repository.ask 抛 RuntimeException 时捕获、engine.releaseCount 增加、isStreaming=false；
4. repository.ask 抛 OutOfMemoryError 时同样捕获并 release；
5. 成绩流完成后错误被清空。

NotificationAccessMonitorTest 用 runTest 虚拟时间验证：
- 初始 false 立即发射；
- 1 秒后 checker 变 true，第二滴发射 true；
- repeat 期间 distinctUntilChanged，不重复发射 true；
- 取消 collect 后不再轮询。

## Step 2: 确认失败

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.chat.EngineReadinessTest" --tests "com.calm.inbox.features.chat.ChatViewModelRecoveryTest" --tests "com.calm.inbox.core.notifications.NotificationAccessMonitorTest"
~~~

**Expected:** Unresolved reference EngineReadiness / ChatPrecondition / NotificationAccessMonitor。

## Step 3: 最小实现

EngineReadiness：

~~~kotlin
class EngineReadiness(
    private val isModelReady: () -> Boolean,
    private val modelPath: () -> String,
    private val engine: LlmEngine,
    private val holder: EngineHolder,
) {
    suspend fun prepare(): ChatPrecondition {
        if (!isModelReady()) return ChatPrecondition.NeedsModel
        return try {
            holder.acquire(modelPath())
            ChatPrecondition.Ready
        } catch (t: Throwable) {
            ChatPrecondition.EngineError
        }
    }

    fun releaseAfterFailure() {
        engine.release()
    }
}
~~~

MnnLlmEngine.release() 对空 native 指针幂等，因此这里统一释放，确保 ERROR 状态下的残留指针也被清理。

NotificationAccessMonitor：

~~~kotlin
class NotificationAccessMonitor(
    private val isGranted: () -> Boolean,
    private val refreshInterval: Duration = Duration.ofSeconds(1),
) {
    fun observe(): Flow<Boolean> = flow {
        while (currentCoroutineContext().isActive) {
            emit(isGranted())
            delay(refreshInterval.toMillis())
        }
    }.distinctUntilChanged()
}
~~~

ChatViewModel：
- 构造参数追加 EngineReadiness，并在 ask 前调用 prepare；
- 用 ChatUiState 暴露 precondition、userMessage、isStreaming；
- NeedsModel 显示下载引导按钮 onOpenSettings；
- EngineError 显示重试按钮，重试重新调用 prepare；
- repository.ask 包在 try/catch(Throwable)；
- catch 中调用 readiness.releaseAfterFailure()，显示“本地模型已释放，可重试”；
- finally 结束 streaming。

ChatScreen 增加模型引导、错误重试、权限丢失提示条。SettingsViewModel 将一次性 checker 替换为 monitor.observe() 的 stateIn；SettingsScreen 在未授权时用持续可见卡片引导重新授权，不弹系统页面。

WorkManager 退避验证不新增重复实现，执行并核对 W2 BriefSchedulerTest：

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.brief.BriefSchedulerTest"
~~~

同时用 adb 检查一次任务配置：

~~~powershell
adb shell dumpsys jobscheduler | findstr /i "com.calm.inbox"
~~~

验收点：每日周期任务存在，退避策略为 exponential 10 分钟，重复执行时 getByDate 幂等。

## Step 4: 确认通过并回归

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.chat.EngineReadinessTest" --tests "com.calm.inbox.features.chat.ChatViewModelRecoveryTest" --tests "com.calm.inbox.core.notifications.NotificationAccessMonitorTest"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
~~~

**Expected:** 边界测试全部通过；全量测试与构建成功。

## Step 5: Commit

~~~powershell
git add app/src/main/java/com/calm/inbox/features/chat app/src/main/java/com/calm/inbox/core/notifications/NotificationAccessMonitor.kt app/src/main/java/com/calm/inbox/features/settings app/src/main/java/com/calm/inbox/di/AppModule.kt app/src/test/java/com/calm/inbox/features/chat app/src/test/java/com/calm/inbox/core/notifications/NotificationAccessMonitorTest.kt
git commit -m "feat(chat): harden model readiness, OOM recovery, and permission monitoring"
~~~
