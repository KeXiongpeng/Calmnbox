package com.calm.inbox.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.calm.inbox.core.database.dao.BriefDao
import com.calm.inbox.core.database.dao.ChatMessageDao
import com.calm.inbox.core.database.dao.NotificationDao
import com.calm.inbox.core.database.entity.BriefEntity
import com.calm.inbox.core.database.entity.ChatMessageEntity
import com.calm.inbox.core.database.entity.NotificationEntity

@Database(
    entities = [
        NotificationEntity::class,
        BriefEntity::class,
        ChatMessageEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao
    abstract fun briefDao(): BriefDao
    abstract fun chatMessageDao(): ChatMessageDao
}
