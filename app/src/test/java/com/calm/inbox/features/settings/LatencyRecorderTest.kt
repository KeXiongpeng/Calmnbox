package com.calm.inbox.features.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.calm.inbox.core.model.FakeLlmEngine
import com.calm.inbox.core.model.MnnLlmEngine
import com.calm.inbox.core.model.MnnNative
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LatencyRecorderTest {

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder()

    /** 每个用例独立的 DataStore 文件 + 挂在 testScheduler 上的 DataStore 作用域。 */
    private fun TestScope.createRecorder(): LatencyRecorder {
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
            produceFile = { File(tmpFolder.newFolder(), "benchmarks.preferences_pb") },
        )
        return LatencyRecorder(dataStore, backgroundScope)
    }

    @Test
    fun `stats emits null when no samples recorded`() = runTest {
        val recorder = createRecorder()

        assertThat(recorder.stats().first()).isNull()
    }

    @Test
    fun `record computes latest and average across samples`() = runTest {
        val recorder = createRecorder()

        recorder.record(100)
        recorder.record(300)

        assertThat(recorder.stats().first())
            .isEqualTo(LatencyStats(latestMs = 300, averageMs = 200, sampleCount = 2))
    }

    @Test
    fun `record keeps only the most recent MAX_SAMPLES`() = runTest {
        val recorder = createRecorder()

        repeat(25) { index -> recorder.record((index + 1).toLong()) }

        val stats = recorder.stats().first()
        assertThat(stats!!.sampleCount).isEqualTo(LatencyRecorder.MAX_SAMPLES)
        assertThat(stats.latestMs).isEqualTo(25)
        assertThat(stats.averageMs).isEqualTo(15)   // (6+7+…+25)/20 = 310/20 = 15
    }

    @Test
    fun `attachTo is a no-op for non MNN engines`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = createRecorder()
        val fake = FakeLlmEngine(responses = listOf("回复文本"))
        fake.load("/models/qwen")

        recorder.attachTo(fake)
        fake.generateStream("prompt").toList()

        assertThat(recorder.stats().first()).isNull()
    }

    @Test
    fun `attachTo records first token latency of MnnLlmEngine streams`() = runTest(UnconfinedTestDispatcher()) {
        val recorder = createRecorder()
        var onGenerate: ((MnnNative.StreamListener) -> Unit)? = null
        val create: (String) -> Long = { 1L }
        val generate: (Long, String, MnnNative.StreamListener) -> Unit =
            { _, _, listener -> onGenerate?.invoke(listener) ?: listener.onToken(null) }
        val release: (Long) -> Unit = { }
        onGenerate = { listener ->
            listener.onToken("你")
            listener.onToken(null)
        }
        val engine = MnnLlmEngine(create, generate, release)

        recorder.attachTo(engine)
        engine.load("/models/qwen")
        engine.generateStream("prompt").toList()
        advanceUntilIdle()

        val stats = recorder.stats().first()
        assertThat(stats!!.sampleCount).isEqualTo(1)
        assertThat(stats.latestMs).isAtLeast(0L)
    }
}
