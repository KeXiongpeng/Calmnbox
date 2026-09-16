package com.calm.inbox.core.classify

import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.core.model.EngineState
import com.calm.inbox.core.model.LlmEngine
import kotlinx.coroutines.flow.toList

data class Classification(
    val id: Long,
    val category: String,
    val importance: Int,     // 1~5
    val summary: String
)

class HybridClassifier(
    private val rules: RuleEngine,
    private val engine: LlmEngine?,     // null 或 state != READY 表示降级纯规则
) {
    /**
     * 规则先行 → 未命中分批调 LLM → 非法输出重试 1 次 → 仍失败降级。
     * 返回条目覆盖入参全部 id，顺序不保证与输入一致，调用方按 id 使用。
     */
    suspend fun classifyBatch(items: List<NotificationEntity>): List<Classification> {
        if (items.isEmpty()) return emptyList()
        val results = mutableListOf<Classification>()
        val unmatched = mutableListOf<NotificationEntity>()
        for (item in items) {
            val rule = rules.classify(item)
            if (rule != null) {
                results += Classification(
                    id = item.id,
                    category = rule.category.name,
                    importance = rule.importance,
                    summary = item.title,
                )
            } else {
                unmatched += item
            }
        }
        if (unmatched.isEmpty()) return results

        val activeEngine = engine?.takeIf { it.state.value == EngineState.READY }
        if (activeEngine == null) {
            return results + unmatched.map { fallback(it) }
        }
        for (batch in unmatched.chunked(ClassificationPrompts.MAX_BATCH_SIZE)) {
            val parsed = generateWithRetry(activeEngine, batch)
            if (parsed == null) {
                results += batch.map { fallback(it) }
            } else {
                val byId = parsed.associateBy { it.id }
                results += batch.map { item ->
                    val hit = byId[item.id]
                    if (hit == null) {
                        fallback(item)
                    } else {
                        Classification(item.id, hit.category, hit.importance, hit.summary)
                    }
                }
            }
        }
        return results
    }

    /** 非法 JSON / 引擎异常各算一次失败；共尝试 MAX_ATTEMPTS 次后返回 null（降级）。 */
    private suspend fun generateWithRetry(
        engine: LlmEngine,
        batch: List<NotificationEntity>,
    ): List<ParsedClassification>? {
        val prompt = ClassificationPrompts.buildBatchPrompt(batch)
        repeat(MAX_ATTEMPTS) {
            try {
                val raw = engine.generateStream(prompt).toList().joinToString(separator = "")
                ClassificationJsonParser.parse(raw)?.let { return it }
            } catch (e: Exception) {
                // 引擎异常视同本次尝试失败
            }
        }
        return null
    }

    private fun fallback(item: NotificationEntity): Classification =
        Classification(id = item.id, category = "UNCATEGORIZED", importance = 1, summary = "")

    companion object {
        const val MAX_ATTEMPTS = 2   // 首次 + 重试 1 次（对齐设计文档 §6 降级链路）
    }
}
