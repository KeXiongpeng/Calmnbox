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
