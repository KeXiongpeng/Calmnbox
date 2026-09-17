package com.calm.inbox.features.chat

import com.calm.inbox.core.database.entity.NotificationEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatAnswersTest {

    private fun notification(
        id: Long = 7L,
        appName: String = "Example",
        title: String = "登录验证码",
        text: String = "验证码 123456，5 分钟内有效"
    ) = NotificationEntity(
        id = id,
        packageName = "com.example.app",
        appName = appName,
        title = title,
        text = text,
        postedAt = 1_000L,
        digest = "digest-$id"
    )

    @Test
    fun extractsChineseVerificationCodeFromNotification() {
        val answer = ChatAnswers.verificationCode(
            question = "今天的验证码是多少",
            notifications = listOf(notification())
        )

        assertThat(answer).isEqualTo("验证码是 123456（来自 Example）。")
    }

    @Test
    fun extractsEnglishVerificationCode() {
        val answer = ChatAnswers.verificationCode(
            question = "135790",
            notifications = listOf(
                notification(
                    appName = "Shell",
                    title = "Login code",
                    text = "Your verification code is 135790 and expires in 5 minutes"
                )
            )
        )

        assertThat(answer).isEqualTo("验证码是 135790（来自 Shell）。")
    }

    @Test
    fun returnsNullForNonVerificationQuestion() {
        val answer = ChatAnswers.verificationCode(
            question = "快递到哪了",
            notifications = listOf(notification(title = "快递", text = "已送达"))
        )

        assertThat(answer).isNull()
    }

    @Test
    fun returnsNullWhenNotificationHasNoCode() {
        val answer = ChatAnswers.verificationCode(
            question = "验证码是多少",
            notifications = listOf(notification(title = "验证码", text = "请稍后再试"))
        )

        assertThat(answer).isNull()
    }
}
