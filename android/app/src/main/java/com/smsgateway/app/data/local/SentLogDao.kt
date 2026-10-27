package com.smsgateway.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface SentLogDao {
    @Insert
    suspend fun insert(entry: SentLogEntry)

    @Query("SELECT * FROM sent_log ORDER BY timestampMillis DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<SentLogEntry>
}
