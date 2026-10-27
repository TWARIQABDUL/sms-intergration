package com.smsgateway.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Rolling log of send attempts, shown on the status screen. */
@Entity(tableName = "sent_log")
data class SentLogEntry(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val messageId: String,
    val to: String,
    val status: String, // "sent" | "delivered" | "failed" | "retrying" | "gave_up"
    val detail: String? = null,
    val timestampMillis: Long
)
