package com.calm.inbox.core.classify

import com.calm.inbox.core.database.Category
import com.calm.inbox.core.database.entity.NotificationEntity
import com.calm.inbox.core.model.EngineState
import com.calm.inbox.core.model.FakeLlmEngine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HybridClassifierTest {

    private fun item(id: Long, pkg: String = "unknown.pkg") = NotificationEntity(
        id = id,
        packageName = pkg,
        appName = "App$id",
        title = "标题$id",
        text = "内容$id",
        postedAt = 1_700_000_000_000 + id,
        digest = "digest-$id",
    )

    /** 规则表只含一个包名，命中与否完全确定，不依赖 W1 默认映射表内容。 */
    private val rules = RuleEngine(mapOf("com.hit.social" to Category.SOCIAL))

    private fun llmJson(vararg ids: Long) =
        ids.joinToString(separator = ",", prefix = "[", postfix = "]") { id ->
            """{"id": $id, "category": "WORK", "importance": 4, "summary": "LLM摘要$id"}"""
        }

    @Test
    fun `规则命中的通知直接采用规则结果且不调用 LLM`() = runTest {
        val fake = FakeLlmEngine(responses = listOf(llmJson(1)))
        fake.load("/models")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(listOf(item(1, pkg = "com.hit.social")))

        assertThat(result).containsExactly(Classification(1, "SOCIAL", 2, "标题1"))
        assertThat(fake.receivedPrompts).isEmpty()
    }

    @Test
    fun `规则未命中且引擎为 null 时降级纯规则`() = runTest {
        val classifier = HybridClassifier(rules, null)

        val result = classifier.classifyBatch(listOf(item(5), item(6)))

        assertThat(result).containsExactly(
            Classification(5, "UNCATEGORIZED", 1, ""),
            Classification(6, "UNCATEGORIZED", 1, ""),
        )
    }

    @Test
    fun `引擎未就绪时同样降级且不调用 generate`() = runTest {
        val fake = FakeLlmEngine(responses = listOf(llmJson(5)))  // 未 load，state=NOT_LOADED
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(listOf(item(5)))

        assertThat(result).containsExactly(Classification(5, "UNCATEGORIZED", 1, ""))
        assertThat(fake.state.value).isEqualTo(EngineState.NOT_LOADED)
        assertThat(fake.receivedPrompts).isEmpty()
    }

    @Test
    fun `混合批次规则条目走规则未命中条目走 LLM`() = runTest {
        val fake = FakeLlmEngine(responses = listOf(llmJson(2, 3)))
        fake.load("/models")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(
            listOf(item(1, pkg = "com.hit.social"), item(2), item(3))
        )

        assertThat(result).containsExactly(
            Classification(1, "SOCIAL", 2, "标题1"),
            Classification(2, "WORK", 4, "LLM摘要2"),
            Classification(3, "WORK", 4, "LLM摘要3"),
        )
        assertThat(fake.receivedPrompts).hasSize(1)
    }

    @Test
    fun `LLM 漏答的 id 降级 其余采用 LLM 结果`() = runTest {
        val fake = FakeLlmEngine(responses = listOf(llmJson(2)))
        fake.load("/models")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(listOf(item(2), item(3)))

        assertThat(result).containsExactly(
            Classification(2, "WORK", 4, "LLM摘要2"),
            Classification(3, "UNCATEGORIZED", 1, ""),
        )
    }

    @Test
    fun `首次非法 JSON 重试一次后成功`() = runTest {
        val fake = FakeLlmEngine(responses = listOf("抱歉，我无法输出 JSON", llmJson(5)))
        fake.load("/models")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(listOf(item(5)))

        assertThat(result).containsExactly(Classification(5, "WORK", 4, "LLM摘要5"))
        assertThat(fake.receivedPrompts).hasSize(2)
    }

    @Test
    fun `两次非法 JSON 降级规则`() = runTest {
        val fake = FakeLlmEngine(responses = listOf("第一次失败", "第二次也失败"))
        fake.load("/models")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(listOf(item(5)))

        assertThat(result).containsExactly(Classification(5, "UNCATEGORIZED", 1, ""))
        assertThat(fake.receivedPrompts).hasSize(2)
    }

    @Test
    fun `引擎连续抛异常两次后降级`() = runTest {
        val fake = FakeLlmEngine(responses = listOf(llmJson(5)))
        fake.load("/models")
        fake.generateError = RuntimeException("推理崩溃")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch(listOf(item(5)))

        assertThat(result).containsExactly(Classification(5, "UNCATEGORIZED", 1, ""))
        assertThat(fake.receivedPrompts).hasSize(2)
    }

    @Test
    fun `空列表返回空结果`() = runTest {
        val classifier = HybridClassifier(rules, null)

        assertThat(classifier.classifyBatch(emptyList())).isEmpty()
    }

    @Test
    fun `超过 20 条未命中分两批调用 LLM`() = runTest {
        val json1 = llmJson(*LongRange(1, 20).toList().toLongArray())
        val json2 = llmJson(21)
        val fake = FakeLlmEngine(responses = listOf(json1, json2))
        fake.load("/models")
        val classifier = HybridClassifier(rules, fake)

        val result = classifier.classifyBatch((1L..21L).map { item(it) })

        assertThat(result).hasSize(21)
        assertThat(result.count { it.category == "WORK" }).isEqualTo(21)
        assertThat(fake.receivedPrompts).hasSize(2)
        assertThat(fake.receivedPrompts[0]).contains("id=20")
        assertThat(fake.receivedPrompts[0]).doesNotContain("id=21")
        assertThat(fake.receivedPrompts[1]).contains("id=21")
    }
}
