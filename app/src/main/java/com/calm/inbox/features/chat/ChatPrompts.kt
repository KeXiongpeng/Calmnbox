package com.calm.inbox.features.chat

import com.calm.inbox.core.database.entity.NotificationEntity

object ChatPrompts {
    fun build(question: String, notifications: List<NotificationEntity>): String {
        val context = if (notifications.isEmpty()) {
            "（无匹配通知）"
        } else {
            notifications.joinToString("\n") { item ->
                "[" + item.id + "] " + item.appName + "|" + item.title + "|" + item.text
            }
        }
        return buildString {
            append("仅依据以下通知内容回答。")
            append("找不到可靠答案时必须明确说“通知中没有找到”。")
            append("不要编造验证码、快递、金额或时间。回答需简洁。\n\n")
            append("通知内容：\n")
            append(context)
            append("\n\n用户问题：")
            append(question.trim())
        }
    }
}
