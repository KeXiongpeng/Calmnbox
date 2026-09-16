package com.calm.inbox.core.classify

import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.NotificationEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 打标攒批队列：攒够 [flushThreshold] 条立即冲刷，或首条入队起 [flushIntervalMs] 毫秒后冲刷
 * （后续 offer 不重置计时）。冲刷 = classifyBatch + 逐条 updateClassification 写库。
 */
class ClassificationQueue(
    private val classifier: HybridClassifier,
    private val dao: NotificationDao,
    private val scope: CoroutineScope,
    private val flushThreshold: Int = DEFAULT_FLUSH_THRESHOLD,
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS,
) {
    private val pending = mutableListOf<NotificationEntity>()
    private var flushJob: Job? = null

    val pendingCount: Int
        @Synchronized get() = pending.size

    @Synchronized
    fun offer(item: NotificationEntity) {
        pending += item
        if (pending.size >= flushThreshold) {
            flushNow()
        } else if (flushJob == null) {
            scheduleFlush()
        }
    }

    /** 立即冲刷当前积压（达到阈值自动触发，或外部主动触发）。 */
    fun flushNow() {
        val batch = takeBatch()
        if (batch.isEmpty()) return
        scope.launch {
            val results = classifier.classifyBatch(batch)
            results.forEach {
                dao.updateClassification(it.id, it.category, it.importance, it.summary)
            }
        }
    }

    @Synchronized
    private fun takeBatch(): List<NotificationEntity> {
        flushJob?.cancel()
        flushJob = null
        val batch = pending.toList()
        pending.clear()
        return batch
    }

    private fun scheduleFlush() {
        flushJob = scope.launch {
            delay(flushIntervalMs)
            flushNow()
        }
    }

    companion object {
        const val DEFAULT_FLUSH_THRESHOLD = 10
        const val DEFAULT_FLUSH_INTERVAL_MS = 5L * 60 * 1000
    }
}
