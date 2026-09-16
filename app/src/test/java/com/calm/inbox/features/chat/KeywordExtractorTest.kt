package com.calm.inbox.features.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeywordExtractorTest {

    @Test
    fun extractsKnownChinesePhraseFromNaturalQuestion() {
        assertThat(KeywordExtractor.extract("我的验证码是多少？"))
            .containsExactly("验证码")
    }

    @Test
    fun extractsMultipleKnownPhrasesAndKeepsAtMostThree() {
        val result = KeywordExtractor.extract("昨天快递、外卖和账单分别是什么")

        assertThat(result.size).isAtMost(3)
        assertThat(result).containsAtLeast("快递", "外卖", "账单")
    }

    @Test
    fun extractsLatinKeywordAndLowercasesIt() {
        assertThat(KeywordExtractor.extract("Where is JD order")).containsExactly("order")
    }

    @Test
    fun stripsQuestionAndPossessiveWords() {
        assertThat(KeywordExtractor.extract("我的取件码是多少")).containsExactly("取件码")
    }

    @Test
    fun blankQuestionReturnsEmptyList() {
        assertThat(KeywordExtractor.extract("   ")).isEmpty()
    }
}
