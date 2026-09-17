package com.calm.inbox.features.chat

import com.calm.inbox.core.database.entity.NotificationEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatPromptsTest {

    private fun notification(
        id: Long = 7L,
        appName: String = "Example",
        title: String = "登录验证码",
        text: String = "验证码 123456"
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
    fun promptConstrainsAnswersToLocalNotifications() {
        val prompt = ChatPrompts.build("我的验证码是多少", listOf(notification()))

        assertThat(prompt).contains("仅依据以下通知内容回答")
        assertThat(prompt).contains("找不到可靠答案时必须明确说“通知中没有找到”")
    }

    @Test
    fun promptIncludesIdAppNameTitleAndTextForEachNotification() {
        val prompt = ChatPrompts.build("今天有什么快递", listOf(notification()))

        assertThat(prompt).contains("[7] Example|登录验证码|验证码 123456")
    }

    @Test
    fun promptRequiresDirectAnswerWhenContextExists() {
        val prompt = ChatPrompts.build("code?", listOf(notification()))

        assertThat(prompt).contains("\u6709\u901a\u77e5\u4e0a\u4e0b\u6587\u65f6\uff0c\u7b2c\u4e00\u53e5\u5fc5\u987b\u76f4\u63a5\u56de\u7b54\u7528\u6237\u95ee\u9898")
        assertThat(prompt).contains("\u7981\u6b62\u5728\u5df2\u6709\u53ef\u56de\u7b54\u4fe1\u606f\u65f6\u8bf4\u201c\u901a\u77e5\u4e2d\u6ca1\u6709\u627e\u5230\u201d")

    }
    @Test
    fun emptyRetrievalUsesPlaceholderAndKeepsQuestion() {
        val prompt = ChatPrompts.build("我的验证码是多少", emptyList())

        assertThat(prompt).contains("（无匹配通知）")
        assertThat(prompt).contains("用户问题：我的验证码是多少")
    }

    @Test
    fun promptDoesNotAddChatmlMarkers() {
        val prompt = ChatPrompts.build("我的验证码是多少", listOf(notification()))

        assertThat(prompt).doesNotContain("<|im_start|>")
        assertThat(prompt).doesNotContain("<|im_end|>")
    }

    @Test
    fun promptUsesStoredNotificationTextOnlyOnce() {
        val text = "a".repeat(100)
        val prompt = ChatPrompts.build(" unknown ", listOf(notification(text = text)))

        assertThat(prompt.split(text).size - 1).isEqualTo(1)
    }
}
