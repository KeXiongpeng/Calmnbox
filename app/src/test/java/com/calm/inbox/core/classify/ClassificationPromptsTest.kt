package com.calm.inbox.core.classify

import com.calm.inbox.core.database.entity.NotificationEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ClassificationPromptsTest {

    private fun item(id: Long, text: String = "内容") = NotificationEntity(
        id = id,
        packageName = "com.example.app",
        appName = "示例App",
        title = "标题$id",
        text = text,
        postedAt = 1_700_000_000_000 + id,
        digest = "digest-$id",
    )

    @Test
    fun `prompt 包含全部 10 个类别枚举名`() {
        val prompt = ClassificationPrompts.buildBatchPrompt(listOf(item(1)))

        ClassificationPrompts.CATEGORY_NAMES.forEach { name ->
            assertThat(prompt).contains(name)
        }
        assertThat(ClassificationPrompts.CATEGORY_NAMES).hasSize(10)
    }

    @Test
    fun `prompt 包含每条通知的 id 应用名 标题 文本`() {
        val prompt = ClassificationPrompts.buildBatchPrompt(
            listOf(item(7, text = "您的快递已到菜鸟驿站"))
        )

        assertThat(prompt).contains("id=7")
        assertThat(prompt).contains("app=示例App")
        assertThat(prompt).contains("title=标题7")
        assertThat(prompt).contains("text=您的快递已到菜鸟驿站")
    }

    @Test
    fun `超过 100 字的文本被截断`() {
        val longText = "长".repeat(150)
        val prompt = ClassificationPrompts.buildBatchPrompt(listOf(item(1, text = longText)))

        assertThat(prompt).contains("text=" + "长".repeat(100))
        assertThat(prompt).doesNotContain("text=" + "长".repeat(101))
    }

    @Test
    fun `prompt 包含 JSON 输出格式说明`() {
        val prompt = ClassificationPrompts.buildBatchPrompt(listOf(item(1)))

        assertThat(prompt).contains("\"category\"")
        assertThat(prompt).contains("\"importance\"")
        assertThat(prompt).contains("\"summary\"")
        assertThat(prompt).contains("JSON")
    }

    @Test
    fun `空列表抛出 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            ClassificationPrompts.buildBatchPrompt(emptyList())
        }
    }

    @Test
    fun `超过 20 条抛出 IllegalArgumentException`() {
        val items = (1..21).map { item(it.toLong()) }

        assertThrows(IllegalArgumentException::class.java) {
            ClassificationPrompts.buildBatchPrompt(items)
        }
    }
}
