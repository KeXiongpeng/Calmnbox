package com.calm.inbox.core.classify

import com.calm.inbox.core.database.Category
import com.calm.inbox.core.database.entity.NotificationEntity

object ClassificationPrompts {

    const val MAX_BATCH_SIZE = 20
    const val MAX_ITEM_TEXT_LENGTH = 100

    val CATEGORY_NAMES: List<String> = Category.entries.map { it.name }

    fun buildBatchPrompt(items: List<NotificationEntity>): String {
        require(items.isNotEmpty()) { "items must not be empty" }
        require(items.size <= MAX_BATCH_SIZE) {
            "batch size ${items.size} exceeds MAX_BATCH_SIZE=$MAX_BATCH_SIZE"
        }
        val notificationLines = items.joinToString(separator = "\n") { item ->
            val text = item.text.take(MAX_ITEM_TEXT_LENGTH)
            "- id=${item.id} | app=${item.appName} | title=${item.title} | text=$text"
        }
        return buildString {
            append("你是手机通知分类助手。对下面的通知列表逐条分类打分，只输出一个 JSON 数组，不要输出任何其他文字。\n\n")
            append("category 只能取以下枚举值：")
            append(CATEGORY_NAMES.joinToString(separator = ", "))
            append("\n含义：VERIFICATION=验证码，EXPRESS=快递物流，FINANCE=财务账单，SOCIAL=社交消息，WORK=工作协作，SHOPPING=购物订单，SYSTEM=系统状态，MARKETING=营销推广，OTHER=其他，UNCATEGORIZED=无法判断。\n\n")
            append("importance 是 1~5 的整数：5=必须立刻看到（验证码、紧急告警）；4=重要（快递到达、账单提醒、工作消息）；3=一般有用；2=低价值；1=纯营销噪音。\n")
            append("summary 用不超过 20 个字概括通知内容。\n\n")
            append("输出格式（严格 JSON 数组，id 与输入一致）：\n")
            append("[{\"id\": 1, \"category\": \"VERIFICATION\", \"importance\": 5, \"summary\": \"淘宝验证码1234\"}]\n\n")
            append("通知列表：\n")
            append(notificationLines)
            append("\n\n现在只输出 JSON 数组：")
        }
    }
}
