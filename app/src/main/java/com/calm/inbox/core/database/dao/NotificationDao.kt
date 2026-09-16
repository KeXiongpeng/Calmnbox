package com.calm.inbox.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.calm.inbox.core.database.entity.NotificationEntity
import kotlinx.coroutines.flow.Flow

data class CategoryCount(
    val category: String,
    val count: Int
)

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: NotificationEntity): Long

    @Query("SELECT * FROM notifications WHERE postedAt BETWEEN :start AND :end ORDER BY postedAt DESC")
    suspend fun getByDateRange(start: Long, end: Long): List<NotificationEntity>

    @Query("SELECT * FROM notifications ORDER BY postedAt DESC")
    fun observeAll(): Flow<List<NotificationEntity>>

    @Query("SELECT * FROM notifications WHERE category = 'UNCATEGORIZED' ORDER BY postedAt ASC LIMIT :limit")
    suspend fun getUnclassified(limit: Int): List<NotificationEntity>

    @Query("UPDATE notifications SET category = :category, importance = :importance, summary = :summary WHERE id = :id")
    suspend fun updateClassification(
        id: Long,
        category: String,
        importance: Int,
        summary: String
    )

    @Query(
        "SELECT * FROM notifications " +
            "WHERE postedAt BETWEEN :start AND :end " +
            "AND (title LIKE '%' || :keyword || '%' OR text LIKE '%' || :keyword || '%') " +
            "ORDER BY postedAt DESC LIMIT 50"
    )
    suspend fun searchByKeyword(keyword: String, start: Long, end: Long): List<NotificationEntity>

    @Query("SELECT category, COUNT(*) as count FROM notifications GROUP BY category")
    fun observeCountsByCategory(): Flow<List<CategoryCount>>
}
