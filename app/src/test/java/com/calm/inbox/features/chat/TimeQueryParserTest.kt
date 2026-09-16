package com.calm.inbox.features.chat

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class TimeQueryParserTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 16, 15, 30)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun millis(value: LocalDateTime): Long =
        value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun startOfDay(year: Int, month: Int, day: Int): Long =
        millis(LocalDateTime.of(year, month, day, 0, 0, 0, 0))

    private fun endOfDay(year: Int, month: Int, day: Int): Long =
        millis(LocalDateTime.of(year, month, day, 0, 0, 0, 0)) + 86_400_000L - 1L

    @Test
    fun todayParsesToFullCurrentDay() {
        assertThat(TimeQueryParser.parse("今天的验证码", now))
            .isEqualTo(TimeRange(startOfDay(2026, 9, 16), endOfDay(2026, 9, 16)))
    }

    @Test
    fun yesterdayParsesToFullPreviousDay() {
        assertThat(TimeQueryParser.parse("昨天的验证码", now))
            .isEqualTo(TimeRange(startOfDay(2026, 9, 15), endOfDay(2026, 9, 15)))
    }

    @Test
    fun dayBeforeYesterdayParsesToFullDay() {
        assertThat(TimeQueryParser.parse("前天的验证码", now))
            .isEqualTo(TimeRange(startOfDay(2026, 9, 14), endOfDay(2026, 9, 14)))
    }

    @Test
    fun beforeDayBeforeYesterdayParsesAllHistory() {
        assertThat(TimeQueryParser.parse("前天以前全部的验证码", now))
            .isEqualTo(TimeRange(0L, endOfDay(2026, 9, 14)))
    }

    @Test
    fun lastThreeDaysStartsExactlyThreeDaysBeforeNow() {
        assertThat(TimeQueryParser.parse("最近3天的验证码", now))
            .isEqualTo(TimeRange(millis(now.minusDays(3)), millis(now)))
    }

    @Test
    fun thisWeekStartsMondayAndEndsNow() {
        assertThat(TimeQueryParser.parse("这周的验证码", now))
            .isEqualTo(TimeRange(startOfDay(2026, 9, 14), millis(now)))
    }

    @Test
    fun questionWithoutTimeWordReturnsNull() {
        assertThat(TimeQueryParser.parse("验证码是多少", now)).isNull()
    }

    @Test
    fun recentZeroDaysReturnsNull() {
        assertThat(TimeQueryParser.parse("最近0天的验证码", now)).isNull()
    }

    @Test
    fun recentNinetyNineDaysReturnsNull() {
        assertThat(TimeQueryParser.parse("最近99天的验证码", now)).isNull()
    }
}
