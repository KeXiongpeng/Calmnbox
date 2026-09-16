### Task 18: README 与作品集叙事（GIF / 架构图 / 基准 / 隐私 / 英文版 / AI 工作流）

**前置依赖：** Task 15 聊天闭环；Task 16 边界处理；Task 17 CI；W2 benchmark-w2.md 已有首 token 记录。

## Files

**Create:**
- docs/ARCHITECTURE.md
- docs/PRIVACY.md
- docs/README.en.md
- docs/benchmarks/benchmark-final.md
- docs/assets/demo.gif
- docs/assets/inbox.png
- docs/assets/chat-citation.png
- app/src/main/java/com/calm/inbox/features/chat/ChatTelemetry.kt
- app/src/test/java/com/calm/inbox/features/chat/ChatTelemetryTest.kt

**Modify:**
- README.md
- app/src/main/java/com/calm/inbox/features/chat/ChatViewModel.kt

## Interfaces

**Produces:**

~~~kotlin
object ChatTelemetry {
    const val LOG_TAG = "CalmBenchmark"
    fun tokensPerSecond(tokenCount: Int, elapsedMs: Long): Double
    fun generationLog(tokenCount: Int, elapsedMs: Long): String
}
~~~

文档产出：中文 README、英文 README、隐私声明、架构图、演示 GIF、最终基准记录、AI 工作流章节。

**Consumes:** Task 13 LatencyRecorder 的首 token 最近值/平均值；Task 15 ChatEvent.Chunk；Android dumpsys meminfo。

## Step 1: 写失败测试

ChatTelemetryTest：

~~~kotlin
@Test
fun tokensPerSecondUsesElapsedMillis() {
    assertThat(ChatTelemetry.tokensPerSecond(tokenCount = 20, elapsedMs = 4_000)).isWithin(1e-9).of(5.0)
}

@Test
fun generationLogNeverContainsNotificationContent() {
    val log = ChatTelemetry.generationLog(tokenCount = 31, elapsedMs = 6_200)
    assertThat(log).contains("token_count=31")
    assertThat(log).contains("elapsed_ms=6200")
    assertThat(log).contains("tokens_per_second=5.0")
    assertThat(log).doesNotContain("title=")
    assertThat(log).doesNotContain("text=")
}
~~~

运行：

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.chat.ChatTelemetryTest"
~~~

**Expected:** Unresolved reference ChatTelemetry。

## Step 2: 实现本地指标

ChatTelemetry 只输出聚合计数，不输出通知内容：

~~~kotlin
object ChatTelemetry {
    const val LOG_TAG = "CalmBenchmark"

    fun tokensPerSecond(tokenCount: Int, elapsedMs: Long): Double {
        if (tokenCount <= 0 || elapsedMs <= 0L) return 0.0
        return tokenCount * 1000.0 / elapsedMs
    }

    fun generationLog(tokenCount: Int, elapsedMs: Long): String =
        "chat_generation token_count=" + tokenCount +
            " elapsed_ms=" + elapsedMs +
            " tokens_per_second=" + twoDecimals(tokensPerSecond(tokenCount, elapsedMs))

    private fun twoDecimals(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
}
~~~

ChatViewModel 在 ask 开始记录 SystemClock.elapsedRealtime 或 System.nanoTime，收集 Chunk 时计数；Done 时调用：

~~~kotlin
Log.i(ChatTelemetry.LOG_TAG, ChatTelemetry.generationLog(chunkCount, elapsedMs))
~~~

不得记录 question、answer、title、text 或 package name。

## Step 3: 采集真机素材与基准

1. 安装 Debug/Release 构建并下载模型。
2. 用 adb 制造固定演示数据：

~~~powershell
adb shell cmd notification post -t "登录验证码" DemoVerification "您的验证码是 246810，5 分钟内有效"
adb shell cmd notification post -t "包裹已到驿站" DemoExpress "取件码 77-88，今日 22 点前领取"
adb shell cmd notification post -t "限时促销" DemoMarketing "全场五折，仅限今天"
~~~

3. 录制 20~30 秒竖屏 raw 视频：

~~~powershell
adb shell screenrecord --time-limit 30 /sdcard/calminbox-demo.mp4
adb pull /sdcard/calminbox-demo.mp4 docs/assets/calminbox-demo.mp4
adb shell rm /sdcard/calminbox-demo.mp4
~~~

4. 转成宽度 ≤720、帧率 12fps、时长 20~30 秒 GIF，输出 docs/assets/demo.gif。若本机有 ffmpeg：

~~~powershell
ffmpeg -i docs/assets/calminbox-demo.mp4 -vf "fps=12,scale=720:-2:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse" -loop 0 docs/assets/demo.gif
~~~

若没有 ffmpeg，用 ScreenToGif 或 Android Studio 录屏导出 GIF；不得提交 raw mp4。
5. 截图收件箱降噪与聊天引用页，分别保存 docs/assets/inbox.png、docs/assets/chat-citation.png；敏感内容必须使用上面的假通知。
6. 采集问答吞吐：
   - 清空 logcat：adb logcat -c
   - 连续提问 5 次；
   - 读取：adb logcat -d -s CalmBenchmark
   - 记录每次 token_count、elapsed_ms、tokens_per_second。
7. 采集内存峰值：问答进行时执行 adb shell dumpsys meminfo com.calm.inbox，记录 TOTAL PSS 与 swap。
8. 读取设置页首 token 最近值/平均值，并把 W2 benchmark-w2.md 的实测值迁移到 benchmark-final.md。

benchmark-final.md 记录模板：

~~~markdown
# CalmInbox final on-device benchmark

## Environment
| Item | Value |
|---|---|
| Device model |  |
| Chipset |  |
| RAM |  |
| Android version |  |
| Build type | Release |
| Model | Qwen2.5-1.5B-Instruct int4 |

## First token latency
| Run | Latency (ms) |
|---|---|
| Warm-up 1 |  |
| Official 1 |  |
| Official 2 |  |
| Official 3 |  |
| Official 4 |  |
| Official 5 |  |
| Latest |  |
| Average |  |
| Target | < 3000 |
| Pass |  |

## Generation throughput
| Run | Token count | Elapsed (ms) | Tokens/sec |
|---|---|---|---|
| 1 |  |  |  |
| 2 |  |  |  |
| 3 |  |  |  |
| 4 |  |  |  |
| 5 |  |  |  |

## Memory
| Scenario | TOTAL PSS (MB) | Swap (MB) |
|---|---|---|
| App foreground before model load |  |  |
| Model loaded idle |  |  |
| During chat generation |  |  |
| After 10-minute idle release |  |  |

## Notes
| Concern | Observation |
|---|---|
| App killed by low memory during 5 runs |  |
| Visible UI jank |  |
| First token regression versus W2 |  |
~~~

## Step 4: 写文档

README.md 必须包含以下完整章节：

1. 一句话定位：端侧 AI 通知管家，全程零上传。
2. CI 徽章。
3. 30 秒 GIF。
4. 功能列表：通知入库、规则+LLM 分类、降噪、每日简报、本地问答、引用跳转、模型管理。
5. 快速开始：JDK 17、Android Studio、SDK 34、clone、.\gradlew.bat assembleDebug、安装、授权通知访问、下载模型。
6. 架构图：Mermaid，从 NotificationListenerService → Filter/Room → HybridClassifier/BriefWorker → LlmEngine → Inbox/Brief/Chat UI。
7. 隐私边界：通知不出设备、无账号、无云端模型、模型来自 ModelScope、CI 不接触用户数据。
8. 真机性能表：引用 benchmark-final.md 的最新/平均首 token、tokens/sec、内存峰值。
9. 工程决策：单例惰性加载、10 分钟空闲释放、规则先行、非法 JSON 重试一次、LIKE 而非 FTS、WorkManager 22:00。
10. 测试与质量：单测范围、CI 命令、手动验收记录路径。
11. Roadmap：短信、embedding RAG、可选云模型、Qwen3 对比。
12. How I built this with AI：需求拆解、规格审查、任务化执行、测试先行、失败输出核查、人工真机验收、代码审查的闭环，并说明哪些决策由人确认。

docs/ARCHITECTURE.md 放大版需包含模块职责、数据流、状态机、失败矩阵、为什么 W1 无模型也完整可用。

docs/PRIVACY.md 明确数据字段、存储位置、保留策略、删除方式（清除应用数据/删除模型）、无网络上传；ModelScope 下载只在用户主动触发时访问网络。

docs/README.en.md 是完整英文说明，不写成摘要；包含 quick start、features、architecture、privacy、benchmark。

## Step 5: 验证与提交

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.features.chat.ChatTelemetryTest"
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
git diff --check
~~~

人工检查：
- README 相对路径可打开；
- GIF 小于 15MB；
- 截图不含真实隐私通知；
- 英文版无中文残留；
- benchmark 表每项均有实测值或明确 Not measured 与原因；
- How I built this with AI 强调工程判断而非自动化提交。

~~~powershell
git add README.md docs app/src/main/java/com/calm/inbox/features/chat/ChatTelemetry.kt app/src/test/java/com/calm/inbox/features/chat/ChatTelemetryTest.kt app/src/main/java/com/calm/inbox/features/chat/ChatViewModel.kt
git commit -m "docs: add portfolio narrative, on-device benchmarks, privacy statement, and English guide"
~~~
