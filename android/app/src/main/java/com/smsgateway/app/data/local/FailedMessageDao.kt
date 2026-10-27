package com.smsgateway.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface FailedMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: FailedMessage)

    @Update
    suspend fun update(message: FailedMessage)

    @Delete
    suspend fun delete(message: FailedMessage)

    @Query("SELECT * FROM failed_messages WHERE nextRetryAtMillis <= :nowMillis ORDER BY nextRetryAtMillis ASC")
    suspend fun dueForRetry(nowMillis: Long): List<FailedMessage>

    @Query("SELECT * FROM failed_messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FailedMessage?
}
