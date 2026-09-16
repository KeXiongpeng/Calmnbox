package com.calm.inbox.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.calm.inbox.core.database.entity.BriefEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BriefDao {
    @Insert
    suspend fun insert(brief: BriefEntity): Long

    @Query("SELECT * FROM briefs ORDER BY date DESC")
    fun observeAll(): Flow<List<BriefEntity>>

    @Query("SELECT * FROM briefs WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): BriefEntity?
}
