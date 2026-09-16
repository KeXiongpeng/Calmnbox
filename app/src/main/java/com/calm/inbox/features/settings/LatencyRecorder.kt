package com.calm.inbox.features.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.calm.inbox.core.model.LlmEngine
import com.calm.inbox.core.model.MnnLlmEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class LatencyStats(val latestMs: Long, val averageMs: Long, val sampleCount: Int)

/**
 * 首 token 延迟打点：挂接 [MnnLlmEngine.firstTokenLatencyListener]，
 * DataStore 持久化最近 [MAX_SAMPLES] 条，供设置页基准卡片与 W3 README 基准表消费。
 */
class LatencyRecorder(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {

    /** 挂接打点回调；非 MNN 实现（测试替身）安全 no-op。 */
    fun attachTo(engine: LlmEngine) {
        val mnn = engine as? MnnLlmEngine ?: return
        mnn.firstTokenLatencyListener = { latencyMs ->
            scope.launch { record(latencyMs) }
        }
    }

    suspend fun record(latencyMs: Long) {
        dataStore.edit { prefs ->
            val samples = parse(prefs[SAMPLES_KEY]).toMutableList()
            samples += latencyMs
            prefs[SAMPLES_KEY] = samples.takeLast(MAX_SAMPLES).joinToString(",")
        }
    }

    fun stats(): Flow<LatencyStats?> = dataStore.data.map { prefs ->
        val samples = parse(prefs[SAMPLES_KEY])
        if (samples.isEmpty()) {
            null
        } else {
            LatencyStats(
                latestMs = samples.last(),
                averageMs = samples.sum() / samples.size,
                sampleCount = samples.size,
            )
        }
    }

    private fun parse(raw: String?): List<Long> =
        raw?.split(",")?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    companion object {
        const val MAX_SAMPLES = 20
        private val SAMPLES_KEY = stringPreferencesKey("first_token_latency_samples")
    }
}
