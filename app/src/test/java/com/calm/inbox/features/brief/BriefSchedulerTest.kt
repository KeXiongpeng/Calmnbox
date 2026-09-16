package com.calm.inbox.features.brief

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class BriefSchedulerTest {

    @Test
    fun `21 点 59 分延迟 1 分钟`() {
        val now = LocalDateTime.of(2026, 9, 15, 21, 59)

        assertThat(BriefScheduler.initialDelayToNext22(now)).isEqualTo(Duration.ofMinutes(1))
    }

    @Test
    fun `恰为 22 点延迟到明天 22 点`() {
        val now = LocalDateTime.of(2026, 9, 15, 22, 0)

        assertThat(BriefScheduler.initialDelayToNext22(now)).isEqualTo(Duration.ofHours(24))
    }

    @Test
    fun `22 点 01 分延迟 23 小时 59 分`() {
        val now = LocalDateTime.of(2026, 9, 15, 22, 1)

        assertThat(BriefScheduler.initialDelayToNext22(now))
            .isEqualTo(Duration.ofHours(23).plusMinutes(59))
    }

    @Test
    fun `上午 10 点延迟 12 小时`() {
        val now = LocalDateTime.of(2026, 9, 15, 10, 0)

        assertThat(BriefScheduler.initialDelayToNext22(now)).isEqualTo(Duration.ofHours(12))
    }
}
