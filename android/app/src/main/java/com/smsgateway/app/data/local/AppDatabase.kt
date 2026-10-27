package com.smsgateway.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FailedMessage::class, SentLogEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun failedMessageDao(): FailedMessageDao
    abstract fun sentLogDao(): SentLogDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context, AppDatabase::class.java, "sms_gateway.db")
                    .build()
                    .also { instance = it }
            }
    }
}
