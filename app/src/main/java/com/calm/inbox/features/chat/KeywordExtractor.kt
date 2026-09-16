package com.calm.inbox.features.chat

object KeywordExtractor {
    const val MAX_KEYWORDS = 3

    private val knownPhrases = listOf(
        "验证码",
        "取件码",
        "快递柜",
        "驿站",
        "快递",
        "物流",
        "外卖",
        "账单",
        "还款",
        "会议",
        "日程",
        "订单",
        "支付",
        "登录"
    )

    private val timePhrases = listOf(
        Regex("最近\\s*\\d{1,2}\\s*天"),
        Regex("前天以前(全部)?"),
        Regex("这个星期"),
        Regex("这周"),
        Regex("今天"),
        Regex("昨天"),
        Regex("前天")
    )

    private val stopwords = setOf(
        "我的",
        "我",
        "是多少",
        "多少",
        "是什么",
        "什么",
        "哪里",
        "请问",
        "告诉我",
        "查一下",
        "帮我",
        "看看",
        "the",
        "is",
        "are",
        "where",
        "my",
        "what",
        "jd",
        "a",
        "an",
        "of",
        "for",
        "in",
        "on"
    )

    fun extract(question: String): List<String> {
        var normalized = question.lowercase().replace("\\s+".toRegex(), " ").trim()
        if (normalized.isEmpty()) return emptyList()

        timePhrases.forEach { phrase -> normalized = phrase.replace(normalized, " ") }

        val known = knownPhrases
            .filter { normalized.contains(it) }
            .distinct()
            .sortedByDescending { it.length }
        if (known.isNotEmpty()) return known.take(MAX_KEYWORDS)

        val latin = Regex("[a-z][a-z0-9_-]{1,40}")
            .findAll(normalized)
            .map { it.value }
            .filter { it !in stopwords }
            .distinct()
            .toList()
        val chinese = normalized
            .split("，", "。", "？", "?", "！", "!", "；", ";", ",", " ")
            .map { segment ->
                stopwords.fold(segment) { acc, stop -> acc.replace(stop, "") }.trim()
            }
            .filter { it.length >= 2 && it !in stopwords }

        return (latin + chinese).distinct().take(MAX_KEYWORDS)
    }
}
