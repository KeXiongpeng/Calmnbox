package com.calm.inbox.core.classify

import com.calm.inbox.core.database.dao.NotificationDao

class NotificationClassifierApplier(
    private val dao: NotificationDao,
    private val rules: RuleEngine
) {

    suspend fun classifyPending(limit: Int = 100): Int {
        val pending = dao.getUnclassified(limit)
        var updated = 0
        for (item in pending) {
            val result = rules.classify(item) ?: continue
            dao.updateClassification(
                id = item.id,
                category = result.category.name,
                importance = result.importance,
                summary = item.title.ifBlank { item.text.take(40) }
            )
            updated++
        }
        return updated
    }
}
