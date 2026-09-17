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
            append("\u6709\u901a\u77e5\u4e0a\u4e0b\u6587\u65f6\uff0c\u7b2c\u4e00\u53e5\u5fc5\u987b\u76f4\u63a5\u56de\u7b54\u7528\u6237\u95ee\u9898\uff1b\u7981\u6b62\u5728\u5df2\u6709\u53ef\u56de\u7b54\u4fe1\u606f\u65f6\u8bf4\u201c\u901a\u77e5\u4e2d\u6ca1\u6709\u627e\u5230\u201d\u3002")
            append("找不到可靠答案时必须明确说“通知中没有找到”。")
            append("不要编造验证码、快递、金额或时间。回答需简洁。\n\n")
            append("通知内容：\n")
            append(context)
            append("\n\n用户问题：")
            append(question.trim())
        }
    }
}
