package com.calm.inbox.features.chat

import com.calm.inbox.core.database.entity.NotificationEntity

object ChatAnswers {
    private val codePattern = Regex(
        "(?:验证码|verification\\s+code|code|otp)[^0-9]{0,20}([0-9]{4,8})",
        RegexOption.IGNORE_CASE
    )

    fun verificationCode(
        question: String,
        notifications: List<NotificationEntity>
    ): String? {
        val normalizedQuestion = question.lowercase().trim()
        val asksVerificationCode = normalizedQuestion.contains("验证码") ||
            Regex("(verification\\s+code|code|otp)").containsMatchIn(normalizedQuestion)

        for (notification in notifications) {
            val code = codePattern
                .find(notification.title + " " + notification.text)
                ?.groupValues?.lastOrNull()?.takeIf { it.length in 4..8 }
                ?: continue
            val questionContainsThisCode = normalizedQuestion.contains(code)
            if (asksVerificationCode || questionContainsThisCode) {
                return "验证码是 $code（来自 ${notification.appName}）。"
            }
        }
        return null
    }
}
