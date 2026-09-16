package com.calm.inbox.features.chat

import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.NotificationEntity
import java.time.LocalDateTime

class NotificationRetriever(private val dao: NotificationDao) {
    suspend fun search(
        question: String,
        now: LocalDateTime,
        defaultRange: TimeRange? = null
    ): List<NotificationEntity> {
        val range = TimeQueryParser.parse(question, now) ?: defaultRange ?: return emptyList()
        val keywords = KeywordExtractor.extract(question)
        if (keywords.isEmpty()) return emptyList()

        val resultsById = LinkedHashMap<Long, NotificationEntity>()
        keywords.forEach { keyword ->
            dao.searchByKeyword(
                keyword = keyword,
                start = range.start,
                end = range.end
            ).forEach { item ->
                resultsById.putIfAbsent(item.id, item)
            }
        }

        return resultsById.values
            .sortedWith(
                compareByDescending<NotificationEntity> { it.postedAt }
                    .thenByDescending { it.id }
            )
            .take(MAX_RESULTS)
    }

    companion object {
        const val MAX_RESULTS = 50
    }
}
