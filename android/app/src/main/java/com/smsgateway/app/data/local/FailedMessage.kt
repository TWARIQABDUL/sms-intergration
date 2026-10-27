package com.smsgateway.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local retry queue for messages that failed to send (no signal, SmsManager
 * error, etc.). Retried with exponential backoff, capped at [MAX_ATTEMPTS].
 */
@Entity(tableName = "failed_messages")
data class FailedMessage(
    @PrimaryKey val id: String,
    val to: String,
    val body: String,
    val attempts: Int = 0,
    val nextRetryAtMillis: Long,
    val lastError: String? = null
) {
    companion object {
        const val MAX_ATTEMPTS = 5
        private const val BASE_DELAY_MILLIS = 30_000L // 30s

        /** Exponential backoff: 30s, 60s, 120s, 240s, 480s. */
        fun backoffDelayMillis(attempts: Int): Long =
            BASE_DELAY_MILLIS * (1L shl attempts.coerceIn(0, 10))
    }
}
