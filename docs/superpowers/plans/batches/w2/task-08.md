### Task 8: ModelManager 模型下载

> **已核查事实（写入本任务的具体值）**
> - ModelScope 模型仓库 ID：`MNN/Qwen2.5-1.5B-Instruct-MNN`（来源：MNN 官方文档预转换模型页 mnn-docs.readthedocs.io → modelscope.cn/organization/MNN）。
> - 仓库核心文件清单（已从仓库页核实）：`config.json`、`llm_config.json`、`llm.mnn`、`llm.mnn.weight`、`tokenizer.mtok`，int4 权重约 1GB。
> - ⚠️ ModelScope 文件直链格式采用其标准 resolve 模式：`https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/<文件名>`。若真机验证返回 404，备用端点为 `https://modelscope.cn/api/v1/models/MNN/Qwen2.5-1.5B-Instruct-MNN/repo?FilePath=<文件名>`，只需替换 `MODEL_BASE_URL` 常量与拼接方式，其余代码不变。
> - ⚠️ 若 ModelScope 该仓库快照中额外包含 `embeddings_bf16.bin`（不同批次发布物偶有此文件），需将其追加到 `MODEL_FILES` 列表，其余逻辑不变。

**前置依赖（W1 已就绪，直接使用）**
- `gradle/libs.versions.toml` 已含 `okhttp = "4.12.0"`（`com.squareup.okhttp3:okhttp`）；若 W1 未入目录，先在 `[versions]` 补 `okhttp = "4.12.0"`、`[libraries]` 补 `okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }`，并在 `app/build.gradle.kts` 加 `implementation(libs.okhttp)`。

## Files

**Create:**
- `app/src/main/java/com/calm/inbox/core/model/DownloadState.kt`
- `app/src/main/java/com/calm/inbox/core/model/OkHttpDownloader.kt`
- `app/src/main/java/com/calm/inbox/core/model/ModelManager.kt`
- `app/src/main/java/com/calm/inbox/di/ModelModule.kt`

**Test:**
- `app/src/test/java/com/calm/inbox/core/model/ModelManagerTest.kt`
- `app/src/test/java/com/calm/inbox/core/model/OkHttpDownloaderTest.kt`

## Interfaces

**Produces（骨架契约，逐字遵守）：**

```kotlin
// app/src/main/java/com/calm/inbox/core/model/DownloadState.kt
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
```

```kotlin
// app/src/main/java/com/calm/inbox/core/model/ModelManager.kt
class ModelManager(private val context: android.content.Context, private val downloader: Downloader) {
    fun modelDir(): File        // <filesDir>/models/Qwen2.5-1.5B-Instruct-MNN
    fun isModelReady(): Boolean // 目录存在且包含 config.json 且 totalSize > 500MB
    fun downloadModel(): Flow<DownloadState>
    suspend fun deleteModel()
}
```

**Consumes：** 无 W1 契约依赖（纯新增）。

## Step 1: 写失败测试

创建 `app/src/test/java/com/calm/inbox/core/model/ModelManagerTest.kt`（Robolectric 提供 `Context.filesDir`；「已就绪」场景用稀疏文件模拟 600MB 模型，避免真实下载）：

```kotlin
package com.calm.inbox.core.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
class ModelManagerTest {

    private lateinit var context: Context
    private lateinit var downloader: FakeDownloader

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        downloader = FakeDownloader()
    }

    private fun manager(): ModelManager = ModelManager(context, downloader)

    private fun writeSparseModel(dir: File) {
        dir.mkdirs()
        File(dir, "config.json").writeText("{}")
        File(dir, "llm_config.json").writeText("{}")
        RandomAccessFile(File(dir, "llm.mnn"), "rw").use { it.setLength(600L * 1024 * 1024) }
        File(dir, "llm.mnn.weight").writeText("")
        File(dir, "tokenizer.mtok").writeText("")
    }

    @Test
    fun `modelDir is filesDir models Qwen dir`() {
        val expected = File(context.filesDir, "models/Qwen2.5-1.5B-Instruct-MNN")
        assertThat(manager().modelDir()).isEqualTo(expected)
    }

    @Test
    fun `isModelReady is false when directory missing`() {
        assertThat(manager().isModelReady()).isFalse()
    }

    @Test
    fun `isModelReady is false when size below 500MB`() = runTest {
        val dir = manager().modelDir()
        dir.mkdirs()
        File(dir, "config.json").writeText("{}")
        assertThat(manager().isModelReady()).isFalse()
    }

    @Test
    fun `isModelReady is true when config and size ok`() = runTest {
        writeSparseModel(manager().modelDir())
        assertThat(manager().isModelReady()).isTrue()
    }

    @Test
    fun `downloadModel emits aggregated progress then Done`() = runTest {
        manager().downloadModel().test {
            val states = mutableListOf<DownloadState>()
            while (true) {
                val state = awaitItem()
                states.add(state)
                if (state is DownloadState.Done || state is DownloadState.Failed) break
            }
            cancelAndIgnoreRemainingEvents()
            // 5 个文件，Fake 每个文件先发 Downloading(0.5f) 再 Done（单文件 Done 不透传）
            val progress = states.filterIsInstance<DownloadState.Downloading>().map { it.progress }
            assertThat(progress).containsExactly(
                0.1f, 0.3f, 0.5f, 0.7f, 0.9f,
            ).inOrder()
            val done = states.last()
            assertThat(done).isInstanceOf(DownloadState.Done::class.java)
            assertThat((done as DownloadState.Done).modelDir).isEqualTo(manager().modelDir())
            assertThat(downloader.requests).hasSize(ModelManager.MODEL_FILES.size)
            ModelManager.MODEL_FILES.forEach { name ->
                assertThat(File(manager().modelDir(), name).isFile).isTrue()
            }
        }
    }

    @Test
    fun `downloadModel short-circuits to Done when model ready`() = runTest {
        writeSparseModel(manager().modelDir())
        manager().downloadModel().test {
            val first = awaitItem()
            assertThat(first).isInstanceOf(DownloadState.Done::class.java)
            cancelAndIgnoreRemainingEvents()
            assertThat(downloader.requests).isEmpty()
        }
    }

    @Test
    fun `downloadModel emits Failed and stops on file failure`() = runTest {
        downloader.failOn = "llm.mnn"
        manager().downloadModel().test {
            val states = mutableListOf<DownloadState>()
            while (true) {
                val state = awaitItem()
                states.add(state)
                if (state is DownloadState.Done || state is DownloadState.Failed) break
            }
            cancelAndIgnoreRemainingEvents()
            val failed = states.last()
            assertThat(failed).isInstanceOf(DownloadState.Failed::class.java)
            assertThat((failed as DownloadState.Failed).message).isEqualTo("boom")
            // config.json、llm_config.json 已请求，llm.mnn 失败后不再请求后续文件
            assertThat(downloader.requests).hasSize(3)
        }
    }

    @Test
    fun `deleteModel removes directory`() = runTest {
        writeSparseModel(manager().modelDir())
        manager().deleteModel()
        assertThat(manager().modelDir().exists()).isFalse()
        assertThat(manager().isModelReady()).isFalse()
    }

    private class FakeDownloader : Downloader {
        val requests = mutableListOf<String>()
        var failOn: String? = null

        override fun download(url: String, dest: File): Flow<DownloadState> = flow {
            requests.add(url)
            val name = url.substringAfterLast('/')
            if (name == failOn) {
                emit(DownloadState.Failed("boom"))
                return@flow
            }
            emit(DownloadState.Downloading(0.5f))
            dest.parentFile?.mkdirs()
            dest.writeText("fake-$name")
            emit(DownloadState.Done(dest))
        }
    }
}
```

创建 `app/src/test/java/com/calm/inbox/core/model/OkHttpDownloaderTest.kt`（纯函数测试，普通 JUnit4）：

```kotlin
package com.calm.inbox.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OkHttpDownloaderTest {

    @Test
    fun `computeProgress returns zero when total unknown`() {
        assertThat(OkHttpDownloader.computeProgress(1024L, -1L)).isEqualTo(0f)
        assertThat(OkHttpDownloader.computeProgress(1024L, 0L)).isEqualTo(0f)
    }

    @Test
    fun `computeProgress returns ratio`() {
        assertThat(OkHttpDownloader.computeProgress(50L, 200L)).isEqualTo(0.25f)
    }

    @Test
    fun `computeProgress clamps to 1f when over`() {
        assertThat(OkHttpDownloader.computeProgress(300L, 200L)).isEqualTo(1f)
    }
}
```

## Step 2: 确认测试失败

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.model.ModelManagerTest" --tests "com.calm.inbox.core.model.OkHttpDownloaderTest"
```

预期：**编译失败**（`Unresolved reference: ModelManager` / `DownloadState` / `OkHttpDownloader`），测试未运行。这是 TDD 红灯。

## Step 3: 最小实现

创建 `app/src/main/java/com/calm/inbox/core/model/DownloadState.kt`：

```kotlin
package com.calm.inbox.core.model

import kotlinx.coroutines.flow.Flow
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
```

创建 `app/src/main/java/com/calm/inbox/core/model/OkHttpDownloader.kt`：

```kotlin
package com.calm.inbox.core.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class OkHttpDownloader(private val client: OkHttpClient = OkHttpClient()) : Downloader {

    override fun download(url: String, dest: File): Flow<DownloadState> = flow {
        emit(DownloadState.Downloading(0f))
        dest.parentFile?.mkdirs()
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("empty body for $url")
            val totalBytes = body.contentLength()
            var bytesRead = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        emit(DownloadState.Downloading(computeProgress(bytesRead, totalBytes)))
                    }
                }
            }
        }
        emit(DownloadState.Done(dest))
    }.flowOn(Dispatchers.IO).catch { e ->
        emit(DownloadState.Failed(e.message ?: "download failed: $url"))
    }

    companion object {
        const val BUFFER_SIZE = 64 * 1024

        fun computeProgress(bytesRead: Long, totalBytes: Long): Float =
            if (totalBytes <= 0L) 0f
            else (bytesRead.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}
```

创建 `app/src/main/java/com/calm/inbox/core/model/ModelManager.kt`：

```kotlin
package com.calm.inbox.core.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class ModelManager(
    private val context: android.content.Context,
    private val downloader: Downloader,
) {
    fun modelDir(): File = File(context.filesDir, "models/$MODEL_DIR_NAME")

    fun isModelReady(): Boolean {
        val dir = modelDir()
        if (!dir.isDirectory) return false
        if (!File(dir, "config.json").isFile) return false
        val totalBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return totalBytes > MIN_MODEL_BYTES
    }

    fun downloadModel(): Flow<DownloadState> = flow {
        if (isModelReady()) {
            emit(DownloadState.Done(modelDir()))
            return@flow
        }
        val dir = modelDir()
        dir.mkdirs()
        val total = MODEL_FILES.size
        for ((index, name) in MODEL_FILES.withIndex()) {
            var failure: String? = null
            var fileDone = false
            downloader.download("$MODEL_BASE_URL/$name", File(dir, name)).collect { state ->
                when (state) {
                    is DownloadState.Downloading ->
                        emit(DownloadState.Downloading((index + state.progress) / total))
                    is DownloadState.Done -> fileDone = true
                    is DownloadState.Failed -> failure = state.message
                    DownloadState.Idle -> Unit
                }
            }
            failure?.let {
                emit(DownloadState.Failed(it))
                return@flow
            }
            if (!fileDone) {
                emit(DownloadState.Failed("download interrupted: $name"))
                return@flow
            }
        }
        emit(DownloadState.Done(dir))
    }.flowOn(Dispatchers.IO)

    suspend fun deleteModel() {
        modelDir().deleteRecursively()
    }

    companion object {
        const val MODEL_DIR_NAME = "Qwen2.5-1.5B-Instruct-MNN"
        const val MIN_MODEL_BYTES = 500L * 1024 * 1024
        val MODEL_FILES = listOf(
            "config.json",
            "llm_config.json",
            "llm.mnn",
            "llm.mnn.weight",
            "tokenizer.mtok",
        )
        const val MODEL_BASE_URL =
            "https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master"
    }
}
```

创建 `app/src/main/java/com/calm/inbox/di/ModelModule.kt`（Task 9 将在此扩展 `LlmEngine`）：

```kotlin
package com.calm.inbox.di

import android.content.Context
import com.calm.inbox.core.model.Downloader
import com.calm.inbox.core.model.ModelManager
import com.calm.inbox.core.model.OkHttpDownloader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ModelModule {

    @Provides
    @Singleton
    fun provideDownloader(): Downloader = OkHttpDownloader()

    @Provides
    @Singleton
    fun provideModelManager(
        @ApplicationContext context: Context,
        downloader: Downloader,
    ): ModelManager = ModelManager(context, downloader)
}
```

## Step 4: 确认测试通过

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.calm.inbox.core.model.ModelManagerTest" --tests "com.calm.inbox.core.model.OkHttpDownloaderTest"
```

预期：`ModelManagerTest` 8 个用例 + `OkHttpDownloaderTest` 3 个用例全部 PASSED（BUILD SUCCESSFUL）。随后跑全量回归确认无破坏：

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

## Step 5: 提交

```powershell
git add app/src/main/java/com/calm/inbox/core/model/DownloadState.kt app/src/main/java/com/calm/inbox/core/model/OkHttpDownloader.kt app/src/main/java/com/calm/inbox/core/model/ModelManager.kt app/src/main/java/com/calm/inbox/di/ModelModule.kt app/src/test/java/com/calm/inbox/core/model/ModelManagerTest.kt app/src/test/java/com/calm/inbox/core/model/OkHttpDownloaderTest.kt
git commit -m "feat: add ModelManager with ModelScope download flow and progress states"
```
