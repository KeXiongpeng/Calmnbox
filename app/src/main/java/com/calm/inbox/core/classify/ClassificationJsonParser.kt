package com.calm.inbox.core.classify

import com.calm.inbox.core.database.Category
import org.json.JSONException
import org.json.JSONObject

data class ParsedClassification(
    val id: Long,
    val category: String,
    val importance: Int,     // 已收敛到 1~5
    val summary: String,
)

object ClassificationJsonParser {

    /**
     * 容错解析 LLM 输出。策略：扫描出文本中所有顶层完整 {...} 对象逐个解析，
     * 天然容忍 markdown 代码块、前后闲聊、数组截断、单对象输出等包装层噪声。
     * 单条缺 id / category 非法 / importance 缺失或不可解析 → 跳过该条；
     * 没有任何合法条目 → 返回 null（调用方执行重试 1 次后降级）。
     */
    fun parse(raw: String): List<ParsedClassification>? {
        val results = mutableListOf<ParsedClassification>()
        for (candidate in extractObjectCandidates(raw)) {
            parseOne(candidate)?.let { results += it }
        }
        return results.ifEmpty { null }
    }

    /** 按大括号配对扫描顶层完整对象；忽略字符串字面量内的花括号；未闭合的对象丢弃。 */
    private fun extractObjectCandidates(text: String): List<String> {
        val candidates = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            when {
                escaped -> {
                    current.append(ch)
                    escaped = false
                }
                ch == '\\' && inString -> {
                    current.append(ch)
                    escaped = true
                }
                ch == '"' -> {
                    current.append(ch)
                    inString = !inString
                }
                ch == '{' -> {
                    current.append(ch)
                    depth++
                }
                ch == '}' -> {
                    current.append(ch)
                    depth--
                    if (depth == 0) {
                        candidates += current.toString()
                        current.clear()
                    } else if (depth < 0) {
                        depth = 0
                        current.clear()
                    }
                }
                depth > 0 -> current.append(ch)
            }
        }
        return candidates
    }

    private fun parseOne(candidate: String): ParsedClassification? {
        return try {
            val obj = JSONObject(candidate)
            val id = obj.optLong("id", -1L)
            if (id <= 0) return null
            val category = normalizeCategory(obj.optString("category", "")) ?: return null
            val importance = normalizeImportance(obj.opt("importance")) ?: return null
            ParsedClassification(
                id = id,
                category = category,
                importance = importance,
                summary = obj.optString("summary", "").trim(),
            )
        } catch (e: JSONException) {
            null
        }
    }

    private fun normalizeCategory(raw: String): String? {
        val upper = raw.trim().uppercase()
        return if (Category.entries.any { it.name == upper }) upper else null
    }

    private fun normalizeImportance(value: Any?): Int? {
        val asInt = when (value) {
            null -> return null
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: return null
            else -> return null
        }
        return asInt.coerceIn(1, 5)
    }
}
