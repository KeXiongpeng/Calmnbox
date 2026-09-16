package com.calm.inbox.core.classify

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClassificationJsonParserTest {

    @Test
    fun validJsonArrayParsesAllItems() {
        val raw = """
            [{"id": 1, "category": "VERIFICATION", "importance": 5, "summary": "验证码1234"},
             {"id": 2, "category": "EXPRESS", "importance": 4, "summary": "快递已到驿站"}]
        """.trimIndent()

        val result = ClassificationJsonParser.parse(raw)!!

        assertThat(result).hasSize(2)
        assertThat(result[0]).isEqualTo(
            ParsedClassification(id = 1, category = "VERIFICATION", importance = 5, summary = "验证码1234")
        )
        assertThat(result[1]).isEqualTo(
            ParsedClassification(id = 2, category = "EXPRESS", importance = 4, summary = "快递已到驿站")
        )
    }

    @Test
    fun markdownCodeBlockIsTolerated() {
        val raw = "```json\n[{\"id\": 1, \"category\": \"WORK\", \"importance\": 4, \"summary\": \"会议提醒\"}]\n```"

        val result = ClassificationJsonParser.parse(raw)!!

        assertThat(result).hasSize(1)
        assertThat(result[0].category).isEqualTo("WORK")
    }

    @Test
    fun surroundingChatterIsTolerated() {
        val raw = "好的，以下是分类结果：\n[{\"id\": 9, \"category\": \"SOCIAL\", \"importance\": 2, \"summary\": \"群消息\"}]\n希望对你有帮助！"

        val result = ClassificationJsonParser.parse(raw)!!

        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(9)
    }

    @Test
    fun importanceUpperBoundCoercesToFive() {
        val raw = """[{"id": 1, "category": "FINANCE", "importance": 99, "summary": "账单"}]"""

        assertThat(ClassificationJsonParser.parse(raw)!![0].importance).isEqualTo(5)
    }

    @Test
    fun importanceLowerBoundCoercesToOne() {
        val raw = """[{"id": 1, "category": "MARKETING", "importance": 0, "summary": "促销"}]"""

        assertThat(ClassificationJsonParser.parse(raw)!![0].importance).isEqualTo(1)
    }

    @Test
    fun numericStringImportanceIsTolerated() {
        val raw = """[{"id": 1, "category": "WORK", "importance": "3", "summary": "日报"}]"""

        assertThat(ClassificationJsonParser.parse(raw)!![0].importance).isEqualTo(3)
    }

    @Test
    fun missingSummaryDefaultsToEmpty() {
        val raw = """[{"id": 1, "category": "SYSTEM", "importance": 2}]"""

        assertThat(ClassificationJsonParser.parse(raw)!![0].summary).isEmpty()
    }

    @Test
    fun invalidCategoryIsSkippedAndValidItemKept() {
        val raw = """
            [{"id": 1, "category": "GAME", "importance": 3, "summary": "非法"},
             {"id": 2, "category": "SHOPPING", "importance": 2, "summary": "发货"}]
        """.trimIndent()

        val result = ClassificationJsonParser.parse(raw)!!

        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(2)
    }

    @Test
    fun missingIdReturnsNull() {
        val raw = """[{"category": "WORK", "importance": 3, "summary": "无id"}]"""

        assertThat(ClassificationJsonParser.parse(raw)).isNull()
    }

    @Test
    fun missingImportanceReturnsNull() {
        val raw = """[{"id": 1, "category": "WORK", "summary": "缺重要度"}]"""

        assertThat(ClassificationJsonParser.parse(raw)).isNull()
    }

    @Test
    fun lowercaseCategoryIsNormalized() {
        val raw = """[{"id": 1, "category": "verification", "importance": 5, "summary": "验证码"}]"""

        assertThat(ClassificationJsonParser.parse(raw)!![0].category).isEqualTo("VERIFICATION")
    }

    @Test
    fun singleObjectBecomesSingleElementList() {
        val raw = """{"id": 3, "category": "OTHER", "importance": 2, "summary": "其他"}"""

        val result = ClassificationJsonParser.parse(raw)!!

        assertThat(result).hasSize(1)
        assertThat(result[0].id).isEqualTo(3)
    }

    @Test
    fun truncatedJsonKeepsCompletedObjects() {
        val raw = """[{"id": 1, "category": "WORK", "importance": 4, "summary": "完整"},{"id": 2, "category": "SOC"""

        val result = ClassificationJsonParser.parse(raw)!!

        assertThat(result).hasSize(1)
        assertThat(result[0].summary).isEqualTo("完整")
    }

    @Test
    fun textWithoutJsonReturnsNull() {
        assertThat(ClassificationJsonParser.parse("抱歉，我无法完成分类。")).isNull()
        assertThat(ClassificationJsonParser.parse("")).isNull()
    }
}
