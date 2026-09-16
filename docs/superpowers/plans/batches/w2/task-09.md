### Task 9: LlmEngine 封装（MNN JNI + 状态机 + 空闲释放）

> **已核查事实（写入本任务的具体值）**
> - **MNN Android LLM SDK 无公开 Maven 坐标**。官方应用 MnnLlmChat（`apps/Android/MnnLlmChat`）通过「预编译 so + Gradle 下载脚本 + CMake 自建 JNI 封装」集成，其 Kotlin 会话类 `com.alibaba.mnnllm.android.llm.LlmSession` 加载 JNI 库名 `mnnllmapp`（`System.loadLibrary("mnnllmapp")`）。本项目采用同构方案：C++ 桥接 MNN 官方 LLM C++ API，自建薄 JNI 库 `calm_mnn`。
> - **官方流式回调语义（已核实源码）**：`interface GenerateProgressListener { fun onProgress(progress: String?): Boolean }` —— `progress == null` 表示生成结束；返回 `true` 表示中断。本任务的 `MnnNative.StreamListener.onToken(token: String?): Boolean` 采用完全相同语义。
> - **MNN C++ LLM API（`llm.hpp`）**：`MNN::Transformer::Llm::createLLM(configPath)` → `load()` → `chat(inputs, callback)` → `release()`；`chat` 为同步阻塞，逐 piece 回调 `std::function<bool(const char*)>`，结束时以 `piece == nullptr` 收尾。
> - ⚠️ MNN 预编译 Android 产物（`libMNN.so`、`libllm.so` 及 `include/` 头文件）从 MNN GitHub Releases（`github.com/alibaba/MNN/releases`）的 Android 预编译包获取；若当前 Release 无 LLM 运行时产物，按 MNN 官方文档《Android 编译》章节从源码编译产出同名 so，放入下述目录即可，代码不变。
> - ⚠️ `Llm::chat` 的回调签名与「结束时回调 nullptr」行为以所用 MNN 版本的 `llm.hpp` 头文件为准（master 分支已核实，历史小版本存在 `stream_callback` 命名差异）；C++ 侧已做兜底——即使 MNN 不发结束 nullptr，桥接层也会在 `chat` 返回后统一补发一次 `onToken(null)`。

**前置依赖（W1 已就绪，直接使用）**
- `app/build.gradle.kts`（W1 Task 1 产出，本任务 Modify 添加 NDK/CMake 配置）。
- `app/src/main/java/com/calm/inbox/di/ModelModule.kt`（Task 8 产出，本任务 Modify 扩展）。
- 开发机已装 Android NDK（Android Studio SDK Manager 安装，版本 26.x 均可）与 CMake 3.22.1。

## Files

**Create:**
- `app/src/main/java/com/calm/inbox/core/model/LlmEngine.kt`
- `app/src/main/java/com/calm/inbox/core/model/MnnNative.kt`
- `app/src/main/java/com/calm/inbox/core/model/MnnLlmEngine.kt`
- `app/src/main/java/com/calm/inbox/core/model/EngineHolder.kt`
- `app/src/main/cpp/calm_llm_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`

**Modify:**
- `app/build.gradle.kts`（添加 `externalNativeBuild` + `ndk.abiFilters`）
- `app/src/main/java/com/calm/inbox/di/ModelModule.kt`（提供 `LlmEngine`、`EngineHolder` 单例）

**Test:**
- `app/src/test/java/com/calm/inbox/core/model/EngineHolderTest.kt`
- `app/src/test/java/com/calm/inbox/core/model/MnnLlmEngineTest.kt`

## Interfaces

**Produces（骨架契约，逐字遵守）：**

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

**本任务附加产出（不在骨架契约内，供 W2 后续任务与 W3 使用）：**
- `MnnNative.StreamListener`：`fun onToken(token: String?): Boolean`（null=流结束，true=中断）
- `EngineHolder.acquire(modelDir: String): LlmEngine`、`EngineHolder.notifyActivity()`、`DEFAULT_IDLE_TIMEOUT_MS = 10L * 60 * 1000`
- `MnnLlmEngine.firstTokenLatencyListener: ((Long) -> Unit)?`（首 token 延迟毫秒，Task 13 打点复用）

**Consumes：** `MnnLlmEngine.load(modelDir)` 接收 Task 8 的 `ModelManager.modelDir()` 返回值（模型目录绝对路径，内含 `config.json`）。

## Step 1: 写失败测试

创建 `app/src/test/java/com/calm/inbox/core/model/EngineHolderTest.kt`（用 `runTest` 虚拟时钟验证空闲释放；引擎替身内联在测试文件中，正式 `FakeLlmEngine` 由 Task 10 按骨架路径生产）：

```kotlin
package com.calm.inbox.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EngineHolderTest {

    private class TestEngine : LlmEngine {
        private val _state = MutableStateFlow(EngineState.NOT_LOADED)
        override val state: StateFlow<EngineState> = _state
        var loadCalls = 0
        var releaseCalls = 0
        var lastModelDir: String? = null

        override suspend fun load(modelDir: String) {
            loadCalls++
            lastModelDir = modelDir
            _state.value = EngineState.READY
        }

        override suspend fun generateStream(prompt: String): Flow<String> = flowOf(prompt)

        override fun release() {
            releaseCalls++
            _state.value = EngineState.NOT_LOADED
        }
    }

    @Test
    fun `acquire loads engine once and returns it`() = runTest {
        val engine = TestEngine()
        val holder = EngineHolder(engine, backgroundScope)
        val first = holder.acquire("/models/qwen")
        val second = holder.acquire("/models/qwen")
        assertThat(first).isSameInstanceAs(engine)
        assertThat(second).isSameInstanceAs(engine)
        assertThat(engine.loadCalls).isEqualTo(1)
        assertThat(engine.lastModelDir).isEqualTo("/models/qwen")
    }

    @Test
    fun `release not called before idle timeout`() = runTest {
        val engine = TestEngine()
        val holder = EngineHolder(engine, backgroundScope)
        holder.acquire("/models/qwen")
        advanceTimeBy(9 * 60 * 1000)
        assertThat(engine.releaseCalls).isEqualTo(0)
    }

    @Test
    fun `release called after ten minutes idle`() = runTest {
        val engine = TestEngine()
        val holder = EngineHolder(engine, backgroundScope)
        holder.acquire("/models/qwen")
        advanceTimeBy(10 * 60 * 1000)
        assertThat(engine.releaseCalls).isEqualTo(1)
    }

    @Test
    fun `notifyActivity resets idle timer`() = runTest {
        val engine = TestEngine()
        val holder = EngineHolder(engine, backgroundScope)
        holder.acquire("/models/qwen")
        advanceTimeBy(9 * 60 * 1000)   // 距 acquire 9 分钟
        holder.notifyActivity()        // 重置计时
        advanceTimeBy(9 * 60 * 1000 + 599 * 1000) // 再过 9 分 59 秒，共 18 分 59 秒 < 10+10
        assertThat(engine.releaseCalls).isEqualTo(0)
        advanceTimeBy(1_001)           // 越过重置后的 10 分钟
        assertThat(engine.releaseCalls).isEqualTo(1)
    }

    @Test
    fun `custom idle timeout is honored`() = runTest {
        val engine = TestEngine()
        val holder = EngineHolder(engine, backgroundScope, idleTimeoutMs = 30_000)
        holder.acquire("/models/qwen")
        advanceTimeBy(29_999)
        assertThat(engine.releaseCalls).isEqualTo(0)
        advanceTimeBy(1)
        assertThat(engine.releaseCalls).isEqualTo(1)
    }
}
```

创建 `app/src/test/java/com/calm/inbox/core/model/MnnLlmEngineTest.kt`（通过构造注入 fake native 函数引用测状态机与流封装，**不触碰 JNI**；注意三个参数必须全部显式传入，避免默认参数触发 `MnnNative` 类加载）：

```kotlin
package com.calm.inbox.core.model

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MnnLlmEngineTest {

    private class NativeFake(createResult: Long = 1L) {
        val createCalls = AtomicInteger()
        val releases = AtomicInteger()
        var onGenerate: ((MnnNative.StreamListener) -> Unit)? = null
        val create: (String) -> Long = { createCalls.incrementAndGet(); createResult }
        val generate: (Long, String, MnnNative.StreamListener) -> Unit =
            { _, _, listener -> onGenerate?.invoke(listener) ?: listener.onToken(null) }
        val release: (Long) -> Unit = { releases.incrementAndGet() }
    }

    @Test
    fun `load transitions to READY and is idempotent`() = runTest {
        val fake = NativeFake()
        val engine = MnnLlmEngine(fake.create, fake.generate, fake.release)
        engine.load("/models/qwen")
        assertThat(engine.state.value).isEqualTo(EngineState.READY)
        engine.load("/models/qwen")
        assertThat(fake.createCalls.get()).isEqualTo(1)
    }

    @Test
    fun `load failure sets ERROR and rethrows`() = runTest {
        val boom: (String) -> Long = { throw RuntimeException("native boom") }
        val fake = NativeFake()
        val engine = MnnLlmEngine(boom, fake.generate, fake.release)
        val thrown = runCatching { engine.load("/models/qwen") }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
        assertThat(engine.state.value).isEqualTo(EngineState.ERROR)
    }

    @Test
    fun `load with zero pointer sets ERROR`() = runTest {
        val fake = NativeFake(createResult = 0L)
        val engine = MnnLlmEngine(fake.create, fake.generate, fake.release)
        runCatching { engine.load("/models/qwen") }
        assertThat(engine.state.value).isEqualTo(EngineState.ERROR)
    }

    @Test
    fun `generateStream fails when not READY`() = runTest(UnconfinedTestDispatcher()) {
        val fake = NativeFake()
        val engine = MnnLlmEngine(fake.create, fake.generate, fake.release)
        engine.generateStream("prompt").test {
            assertThat(awaitError()).isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `generateStream emits tokens and completes on null`() = runTest(UnconfinedTestDispatcher()) {
        val fake = NativeFake()
        fake.onGenerate = { listener ->
            listener.onToken("验")
            listener.onToken("证")
            listener.onToken("码")
            listener.onToken(null)
        }
        val engine = MnnLlmEngine(fake.create, fake.generate, fake.release)
        engine.load("/models/qwen")
        engine.generateStream("prompt").test {
            assertThat(awaitItem()).isEqualTo("验")
            assertThat(awaitItem()).isEqualTo("证")
            assertThat(awaitItem()).isEqualTo("码")
            awaitComplete()
        }
    }

    @Test
    fun `first token latency listener invoked exactly once per stream`() = runTest(UnconfinedTestDispatcher()) {
        val fake = NativeFake()
        fake.onGenerate = { listener ->
            listener.onToken("a")
            listener.onToken("b")
            listener.onToken(null)
        }
        val engine = MnnLlmEngine(fake.create, fake.generate, fake.release)
        engine.load("/models/qwen")
        var calls = 0
        engine.firstTokenLatencyListener = { calls++ }
        engine.generateStream("prompt").toList()
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `release resets state and native release called`() = runTest(UnconfinedTestDispatcher()) {
        val fake = NativeFake()
        val engine = MnnLlmEngine(fake.create, fake.generate, fake.release)
        engine.load("/models/qwen")
        engine.release()
        assertThat(engine.state.value).isEqualTo(EngineState.NOT_LOADED)
        assertThat(fake.releases.get()).isEqualTo(1)
        engine.generateStream("prompt").test {
            assertThat(awaitError()).isInstanceOf(IllegalStateException::class.java)
        }
    }
}
```

## Step 2: 确认测试失败

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.model.EngineHolderTest" --tests "com.calm.inbox.core.model.MnnLlmEngineTest"
```

预期：**编译失败**（`Unresolved reference: LlmEngine` / `MnnNative` / `MnnLlmEngine` / `EngineHolder`），测试未运行。

## Step 3: 最小实现

创建 `app/src/main/java/com/calm/inbox/core/model/LlmEngine.kt`（骨架逐字）：

```kotlin
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

创建 `app/src/main/java/com/calm/inbox/core/model/MnnNative.kt`（JNI 桥声明，C++ 实现见本步末尾）：

```kotlin
package com.calm.inbox.core.model

internal object MnnNative {
    init {
        System.loadLibrary("calm_mnn")
    }

    external fun create(configPath: String): Long
    external fun generate(ptr: Long, prompt: String, listener: StreamListener)
    external fun release(ptr: Long)

    interface StreamListener {
        fun onToken(token: String?): Boolean   // token==null 表示流结束；返回 true 表示中断生成
    }
}
```

创建 `app/src/main/java/com/calm/inbox/core/model/MnnLlmEngine.kt`：

```kotlin
package com.calm.inbox.core.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.io.File

class MnnLlmEngine(
    private val nativeCreate: (String) -> Long = MnnNative::create,
    private val nativeGenerate: (Long, String, MnnNative.StreamListener) -> Unit = MnnNative::generate,
    private val nativeRelease: (Long) -> Unit = MnnNative::release,
) : LlmEngine {

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(EngineState.NOT_LOADED)
    override val state: StateFlow<EngineState> = _state

    private var ptr: Long = 0

    /** 首 token 延迟（毫秒）打点回调，Task 13 的 LatencyRecorder 在此挂接。 */
    var firstTokenLatencyListener: ((Long) -> Unit)? = null

    override suspend fun load(modelDir: String) {
        if (_state.value == EngineState.READY) return
        _state.value = EngineState.LOADING
        try {
            val configPath = File(modelDir, "config.json").absolutePath
            ptr = nativeCreate(configPath)
            check(ptr != 0L) { "native create returned null pointer for $modelDir" }
            _state.value = EngineState.READY
        } catch (t: Throwable) {
            _state.value = EngineState.ERROR
            throw t
        }
    }

    override suspend fun generateStream(prompt: String): Flow<String> = callbackFlow {
        val currentPtr = ptr
        if (_state.value != EngineState.READY || currentPtr == 0L) {
            close(IllegalStateException("engine not READY"))
            return@callbackFlow
        }
        val startNanos = System.nanoTime()
        var firstToken = true
        launch(Dispatchers.Default) {
            try {
                nativeGenerate(currentPtr, prompt, object : MnnNative.StreamListener {
                    override fun onToken(token: String?): Boolean {
                        if (token == null) {
                            close()
                            return true
                        }
                        if (firstToken) {
                            firstToken = false
                            firstTokenLatencyListener?.invoke((System.nanoTime() - startNanos) / 1_000_000)
                        }
                        return trySend(token).isSuccess
                    }
                })
                close()
            } catch (t: Throwable) {
                close(t)
            }
        }
        awaitClose { }
    }

    override fun release() {
        if (ptr != 0L) {
            nativeRelease(ptr)
            ptr = 0
        }
        _state.value = EngineState.NOT_LOADED
    }
}
```

创建 `app/src/main/java/com/calm/inbox/core/model/EngineHolder.kt`：

```kotlin
package com.calm.inbox.core.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 引擎生命周期持有者：惰性加载 + 空闲 10 分钟自动 release。
 * 后台批处理与聊天共享同一 [EngineHolder] 单例。
 */
class EngineHolder(
    private val engine: LlmEngine,
    private val scope: CoroutineScope,
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
) {
    private var idleJob: Job? = null

    /** 确保引擎 READY（未就绪则加载模型），并重置空闲计时。 */
    suspend fun acquire(modelDir: String): LlmEngine {
        if (engine.state.value != EngineState.READY) {
            engine.load(modelDir)
        }
        scheduleIdleRelease()
        return engine
    }

    /** 引擎被使用后调用，重置空闲计时（内部调用安全）。 */
    fun notifyActivity() {
        if (engine.state.value == EngineState.READY) {
            scheduleIdleRelease()
        }
    }

    private fun scheduleIdleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleTimeoutMs)
            engine.release()
        }
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MS = 10L * 60 * 1000
    }
}
```

创建 `app/src/main/cpp/calm_llm_jni.cpp`：

```cpp
#include <jni.h>
#include <android/log.h>
#include <functional>
#include <string>
#include <vector>

#include "llm.hpp"

#define LOG_TAG "CalmMnnJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using MNN::Transformer::Llm;

extern "C" JNIEXPORT jlong JNICALL
Java_com_calm_inbox_core_model_MnnNative_create(JNIEnv *env, jobject /*thiz*/,
                                                jstring config_path) {
    if (config_path == nullptr) {
        return 0;
    }
    const char *path = env->GetStringUTFChars(config_path, nullptr);
    Llm *llm = nullptr;
    try {
        llm = Llm::createLLM(std::string(path));
        if (llm != nullptr) {
            llm->load();
        }
    } catch (const std::exception &e) {
        LOGE("createLLM/load failed: %s", e.what());
        llm = nullptr;
    }
    env->ReleaseStringUTFChars(config_path, path);
    return reinterpret_cast<jlong>(llm);
}

extern "C" JNIEXPORT void JNICALL
Java_com_calm_inbox_core_model_MnnNative_generate(JNIEnv *env, jobject /*thiz*/,
                                                  jlong ptr, jstring prompt,
                                                  jobject listener) {
    auto *llm = reinterpret_cast<Llm *>(ptr);
    if (llm == nullptr) {
        return;
    }
    const char *utf = env->GetStringUTFChars(prompt, nullptr);
    std::string input(utf);
    env->ReleaseStringUTFChars(prompt, utf);

    jclass cls = env->GetObjectClass(listener);
    jmethodID on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");

    llm->chat({input}, [&env, &listener, on_token](const char *piece) -> bool {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return true;   // JVM 侧异常，中断生成
        }
        jstring token = piece == nullptr ? nullptr : env->NewStringUTF(piece);
        jboolean stop = env->CallBooleanMethod(listener, on_token, token);
        if (token != nullptr) {
            env->DeleteLocalRef(token);
        }
        return stop == JNI_TRUE;
    });
    // 兜底：即使该 MNN 版本结束时未回调 nullptr，也统一通知 Kotlin 侧流结束（close 幂等）
    if (!env->ExceptionCheck()) {
        env->CallBooleanMethod(listener, on_token, static_cast<jstring>(nullptr));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_calm_inbox_core_model_MnnNative_release(JNIEnv * /*env*/, jobject /*thiz*/,
                                                 jlong ptr) {
    auto *llm = reinterpret_cast<Llm *>(ptr);
    if (llm != nullptr) {
        try {
            llm->release();
        } catch (const std::exception &e) {
            LOGE("release failed: %s", e.what());
        }
    }
}
```

创建 `app/src/main/cpp/CMakeLists.txt`：

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(calm_mnn LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# MNN 预编译包目录（结构见下方说明；从 MNN GitHub Releases 获取或源码编译产出）
set(MNN_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../libs/mnn-android)

add_library(calm_mnn SHARED calm_llm_jni.cpp)

target_include_directories(calm_mnn PRIVATE ${MNN_ROOT}/include)

find_library(log-lib log)

target_link_libraries(calm_mnn
        ${MNN_ROOT}/libs/arm64-v8a/libMNN.so
        ${MNN_ROOT}/libs/arm64-v8a/libllm.so
        ${log-lib})
```

准备 MNN 预编译包目录（**一次性手动步骤**，产物不入 git，`app/src/main/libs/` 已被 `.gitignore` 覆盖则无需处理）：

```
app/src/main/libs/mnn-android/
  include/            # MNN 头文件（至少含 llm.hpp 及其依赖）
  libs/arm64-v8a/
    libMNN.so
    libllm.so
```

> ⚠️ 注意：预编译 so 放在 `app/src/main/libs/`（CMake 链接用），**不要**放入 `app/src/main/jniLibs/`，否则与 `calm_mnn.so` 链接打包的依赖重复，AGP 报 moreThanOne 错误。`externalNativeBuild` 会自动把链接依赖打进 APK。

修改 `app/build.gradle.kts`，在 `android { }` 内添加：

```kotlin
    defaultConfig {
        // ... 既有内容保持不变，追加：
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
```

修改 `app/src/main/java/com/calm/inbox/di/ModelModule.kt`，追加两个 provides（保留 Task 8 既有内容与 import）：

```kotlin
import com.calm.inbox.core.model.EngineHolder
import com.calm.inbox.core.model.LlmEngine
import com.calm.inbox.core.model.MnnLlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

    @Provides
    @Singleton
    fun provideLlmEngine(): LlmEngine = MnnLlmEngine()

    @Provides
    @Singleton
    fun provideEngineHolder(engine: LlmEngine): EngineHolder =
        EngineHolder(engine, CoroutineScope(SupervisorJob() + Dispatchers.Default))
```

## Step 4: 确认测试通过

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.model.EngineHolderTest" --tests "com.calm.inbox.core.model.MnnLlmEngineTest"
```

预期：`EngineHolderTest` 5 个用例 + `MnnLlmEngineTest` 7 个用例全部 PASSED。单元测试运行在 JVM，不加载 JNI 库（`MnnNative` 仅在真机路径触发）。再跑全量回归与一次装配编译：

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

> `assembleDebug` 需要 MNN 预编译包已就位（CMake 链接）。若 CI/本机暂缺预编译包，先完成单测提交，真机联调放到 Task 13 的 benchmark-w2 手动清单中一并验证。

## Step 5: 提交

```powershell
git add app/src/main/java/com/calm/inbox/core/model/LlmEngine.kt app/src/main/java/com/calm/inbox/core/model/MnnNative.kt app/src/main/java/com/calm/inbox/core/model/MnnLlmEngine.kt app/src/main/java/com/calm/inbox/core/model/EngineHolder.kt app/src/main/cpp/calm_llm_jni.cpp app/src/main/cpp/CMakeLists.txt app/build.gradle.kts app/src/main/java/com/calm/inbox/di/ModelModule.kt app/src/test/java/com/calm/inbox/core/model/EngineHolderTest.kt app/src/test/java/com/calm/inbox/core/model/MnnLlmEngineTest.kt
git commit -m "feat: wrap MNN LLM as LlmEngine with state machine and idle release"
```
