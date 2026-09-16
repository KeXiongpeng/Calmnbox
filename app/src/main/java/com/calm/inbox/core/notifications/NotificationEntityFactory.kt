package com.calm.inbox.core.notifications

import com.calm.inbox.core.database.entity.NotificationEntity
import java.time.Clock

class NotificationEntityFactory(private val clock: Clock) {

    fun create(item: PostedNotification, blacklist: Set<String>): NotificationEntity? {
        if (!NotificationFilter(blacklist).shouldAccept(item.packageName)) return null

        val postedAt = if (item.postedAt > 0L) item.postedAt else clock.millis()
        return NotificationEntity(
            packageName = item.packageName,
            appName = item.appName,
            title = item.title,
            text = NotificationFilter.truncate(item.text),
            postedAt = postedAt,
            digest = NotificationFilter.digest(item.packageName, postedAt, item.title)
        )
    }
}
