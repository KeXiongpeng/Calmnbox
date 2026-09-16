package com.calm.inbox.features.brief

import com.calm.inbox.core.database.entity.NotificationEntity
import java.time.LocalDate

object BriefPrompts {

    const val TOP_N = 5
    const val MAX_ITEMS_IN_PROMPT = 50

    fun buildBriefPrompt(date: LocalDate, items: List<NotificationEntity>): String {
        require(items.isNotEmpty()) { "items must not be empty" }
        val lines = rank(items).take(MAX_ITEMS_IN_PROMPT).joinToString(separator = "\n") { item ->
            "- [${item.importance}分][${item.category}] ${item.appName}：${item.title} ${item.summary}".trim()
        }
        return buildString {
            append("你是手机通知管家。根据下面的已分类通知生成 ")
            append(date)
            append(" 的每日简报，Markdown 纯文本，只输出简报正文，包含两部分：\n")
            append("## 今日 Top").append(TOP_N).append(" 重要事项（每条一行：[分类] 应用：标题（摘要），按重要度降序）\n")
            append("## 分类统计（每类一行：类别：N 条，按数量降序）\n\n")
            append("通知（已按重要度降序）：\n")
            append(lines)
            append("\n\n现在输出简报：")
        }
    }

    /** 模型未就绪时的纯统计降级模板。 */
    fun buildFallbackBrief(date: LocalDate, items: List<NotificationEntity>): String {
        if (items.isEmpty()) {
            return "## $date 简报\n\n今天没有新通知。"
        }
        val top = rank(items).take(TOP_N)
        val counts = items.groupingBy { it.category }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenComparing { it.key })
            .joinToString(separator = "\n") { "- ${it.key}：${it.value} 条" }
        return buildString {
            append("## ").append(date).append(" 简报\n\n")
            append("### 今日 Top").append(TOP_N).append(" 重要事项\n")
            top.forEachIndexed { index, item ->
                append(index + 1).append(". [").append(item.category).append("] ")
                    .append(item.appName).append("：").append(item.title)
                if (item.summary.isNotEmpty()) {
                    append("（").append(item.summary).append("）")
                }
                append("\n")
            }
            append("\n### 分类统计\n").append(counts).append("\n")
        }
    }

    /** 已打标条目按 importance 降序、同分按 postedAt 降序；全部未打标时按 postedAt 降序。 */
    private fun rank(items: List<NotificationEntity>): List<NotificationEntity> {
        val classified = items.filter { it.importance > 0 }
            .sortedWith(
                compareByDescending<NotificationEntity> { it.importance }.thenByDescending { it.postedAt }
            )
        return if (classified.isNotEmpty()) {
            classified
        } else {
            items.sortedByDescending { it.postedAt }
        }
    }
}
