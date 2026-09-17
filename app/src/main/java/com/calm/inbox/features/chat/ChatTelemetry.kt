package com.calm.inbox.features.chat

import java.util.Locale

object ChatTelemetry {
    const val LOG_TAG = "CalmBenchmark"

    fun tokensPerSecond(tokenCount: Int, elapsedMs: Long): Double {
        if (tokenCount <= 0 || elapsedMs <= 0L) return 0.0
        return tokenCount * 1000.0 / elapsedMs
    }

    fun generationLog(tokenCount: Int, elapsedMs: Long): String =
        "chat_generation token_count=" + tokenCount +
            " elapsed_ms=" + elapsedMs +
            " tokens_per_second=" + twoDecimals(tokensPerSecond(tokenCount, elapsedMs))

    private fun twoDecimals(value: Double): String =
        String.format(Locale.ROOT, "%.2f", value)
}
