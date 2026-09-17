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
        File(dir, "tokenizer.txt").writeText("")
    }

    @Test
    fun modelFilesMatchCurrentModelScopeManifest() {
        assertThat(ModelManager.MODEL_FILES).containsExactly(
            "config.json",
            "llm_config.json",
            "llm.mnn",
            "llm.mnn.weight",
            "tokenizer.txt"
        ).inOrder()
    }

    @Test
    fun missingTokenizerMakesModelNotReady() {
        val dir = manager().modelDir()
        writeSparseModel(dir)
        File(dir, "tokenizer.txt").delete()

        assertThat(manager().isModelReady()).isFalse()
    }

    @Test
    fun modelDirIsFilesDirModelsQwenDir() {
        val expected = File(context.filesDir, "models/Qwen2.5-1.5B-Instruct-MNN")
        assertThat(manager().modelDir()).isEqualTo(expected)
    }

    @Test
    fun isModelReadyIsFalseWhenDirectoryMissing() {
        assertThat(manager().isModelReady()).isFalse()
    }

    @Test
    fun isModelReadyIsFalseWhenSizeBelow500MB() = runTest {
        val dir = manager().modelDir()
        dir.mkdirs()
        File(dir, "config.json").writeText("{}")
        assertThat(manager().isModelReady()).isFalse()
    }

    @Test
    fun isModelReadyIsTrueWhenConfigAndSizeOk() = runTest {
        writeSparseModel(manager().modelDir())
        assertThat(manager().isModelReady()).isTrue()
    }

    @Test
    fun downloadModelEmitsAggregatedProgressThenDone() = runTest {
        manager().downloadModel().test {
            val states = mutableListOf<DownloadState>()
            while (true) {
                val state = awaitItem()
                states.add(state)
                if (state is DownloadState.Done || state is DownloadState.Failed) break
            }
            cancelAndIgnoreRemainingEvents()

            val progress = states.filterIsInstance<DownloadState.Downloading>().map { it.progress }
            assertThat(progress).containsExactly(
                0.1f, 0.3f, 0.5f, 0.7f, 0.9f
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
    fun downloadModelShortCircuitsToDoneWhenModelReady() = runTest {
        writeSparseModel(manager().modelDir())
        manager().downloadModel().test {
            val first = awaitItem()
            assertThat(first).isInstanceOf(DownloadState.Done::class.java)
            cancelAndIgnoreRemainingEvents()
            assertThat(downloader.requests).isEmpty()
        }
    }

    @Test
    fun downloadModelEmitsFailedAndStopsOnFileFailure() = runTest {
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
            assertThat(downloader.requests).hasSize(3)
        }
    }

    @Test
    fun deleteModelRemovesDirectory() = runTest {
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
            dest.writeText("fake-" + name)
            emit(DownloadState.Done(dest))
        }
    }
}
