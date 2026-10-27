package com.smsgateway.app.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sends a message (splitting into multipart SMS when it exceeds 160 chars)
 * and wires up SMS_SENT / SMS_DELIVERED PendingIntents per part so
 * [SmsSendCoordinator] can observe the carrier-level outcome.
 */
object SmsSender {
    private val requestCodeCounter = AtomicInteger(1000)

    fun send(context: Context, smsManager: SmsManager, messageId: String, to: String, body: String) {
        val parts = if (body.length > 160) smsManager.divideMessage(body) else arrayListOf(body)
        SmsSendCoordinator.beginTracking(messageId, to, body, parts.size)

        if (parts.size == 1) {
            smsManager.sendTextMessage(
                to, null, parts[0],
                sentPendingIntent(context, messageId),
                deliveredPendingIntent(context, messageId)
            )
        } else {
            val sentIntents = ArrayList(parts.map { sentPendingIntent(context, messageId) })
            val deliveredIntents = ArrayList(parts.map { deliveredPendingIntent(context, messageId) })
            smsManager.sendMultipartTextMessage(to, null, parts, sentIntents, deliveredIntents)
        }
    }

    private fun sentPendingIntent(context: Context, messageId: String): PendingIntent {
        val intent = Intent(context, SmsSentReceiver::class.java).apply {
            action = ACTION_SMS_SENT
            putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        return PendingIntent.getBroadcast(
            context, requestCodeCounter.incrementAndGet(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun deliveredPendingIntent(context: Context, messageId: String): PendingIntent {
        val intent = Intent(context, SmsDeliveredReceiver::class.java).apply {
            action = ACTION_SMS_DELIVERED
            putExtra(EXTRA_MESSAGE_ID, messageId)
        }
        return PendingIntent.getBroadcast(
            context, requestCodeCounter.incrementAndGet(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
