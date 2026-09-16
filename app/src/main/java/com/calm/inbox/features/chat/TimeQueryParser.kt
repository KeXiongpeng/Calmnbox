package com.calm.inbox.features.chat

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class TimeRange(val start: Long, val end: Long)

object TimeQueryParser {
    fun parse(question: String, now: LocalDateTime): TimeRange? {
        val text = question.trim()

        if (Regex("前天以前(全部)?").containsMatchIn(text)) {
            val boundary = now.toLocalDate().minusDays(2)
            return TimeRange(0L, endOfDay(boundary))
        }

        Regex("最近\\s*(\\d{1,2})\\s*天").find(text)?.let { match ->
            val days = match.groupValues[1].toIntOrNull() ?: return null
            if (days !in 1..31) return null
            return TimeRange(toMillis(now.minusDays(days.toLong())), toMillis(now))
        }

        if (text.contains("这周") || text.contains("这个星期")) {
            val monday = now.toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return TimeRange(startOfDay(monday), toMillis(now))
        }
        if (text.contains("今天")) return fullDay(now.toLocalDate())
        if (text.contains("昨天")) return fullDay(now.toLocalDate().minusDays(1))
        if (text.contains("前天")) return fullDay(now.toLocalDate().minusDays(2))
        return null
    }

    fun fullDay(date: LocalDate): TimeRange =
        TimeRange(startOfDay(date), endOfDay(date))

    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun endOfDay(date: LocalDate): Long =
        date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1

    fun toMillis(value: LocalDateTime): Long =
        value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
