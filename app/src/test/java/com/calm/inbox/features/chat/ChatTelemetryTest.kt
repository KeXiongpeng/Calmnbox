package com.calm.inbox.features.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChatTelemetryTest {

    @Test
    fun tokensPerSecondUsesElapsedMillis() {
        assertThat(ChatTelemetry.tokensPerSecond(tokenCount = 20, elapsedMs = 4_000))
            .isWithin(1e-9)
            .of(5.0)
    }

    @Test
    fun generationLogNeverContainsNotificationContent() {
        val log = ChatTelemetry.generationLog(tokenCount = 31, elapsedMs = 6_200)

        assertThat(log).contains("token_count=31")
        assertThat(log).contains("elapsed_ms=6200")
        assertThat(log).contains("tokens_per_second=5.0")
        assertThat(log).doesNotContain("title=")
        assertThat(log).doesNotContain("text=")
    }
}
