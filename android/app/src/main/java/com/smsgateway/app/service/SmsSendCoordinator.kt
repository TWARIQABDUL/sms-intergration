package com.smsgateway.app.service

import android.content.Context
import android.util.Log
import com.smsgateway.app.data.SecurePrefs
import com.smsgateway.app.data.local.AppDatabase
import com.smsgateway.app.data.local.FailedMessage
import com.smsgateway.app.data.local.SentLogEntry
import com.smsgateway.app.network.ApiClient
import com.smsgateway.app.network.dto.StatusRequestDto
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "SmsSendCoordinator"

/**
 * Aggregates the per-part SMS_SENT / SMS_DELIVERED broadcast results for a
 * message (a message may be split into several parts via
 * sendMultipartTextMessage) into a single delivered/failed outcome, then
 * reports that outcome to the server and local log/retry queue.
 *
 * State lives in-process only. If the app process is killed between send
 * and the carrier's broadcast arriving, the in-flight message is simply
 * lost from tracking; the Room retry queue is what provides durability
 * across process death for messages that fail outright.
 */
object SmsSendCoordinator {

    private data class Tracking(
        val to: String,
        val body: String,
        val totalParts: Int,
        var sentOk: Int = 0,
        var sentFail: Int = 0,
        var deliveredOk: Int = 0,
        var deliveredFail: Int = 0,
        var lastError: String? = null
    )

    private val inFlight = ConcurrentHashMap<String, Tracking>()

    fun beginTracking(messageId: String, to: String, body: String, totalParts: Int) {
        inFlight[messageId] = Tracking(to, body, totalParts)
    }

    /** For failures that never reach SmsManager at all (e.g. no active SIM). */
    suspend fun failImmediately(context: Context, messageId: String, to: String, body: String, error: String) {
        beginTracking(messageId, to, body, 1)
        onPartSent(context, messageId, success = false, error = error)
    }

    suspend fun onPartSent(context: Context, messageId: String, success: Boolean, error: String?) {
        val tracking = inFlight[messageId] ?: return
        synchronized(tracking) {
            if (success) tracking.sentOk++ else tracking.sentFail++
            if (!success) tracking.lastError = error
        }
        val sentSoFar = tracking.sentOk + tracking.sentFail
        if (sentSoFar < tracking.totalParts) return

        if (tracking.sentFail > 0) {
            // At least one part never made it off the device - no point waiting
            // for delivery reports that will never arrive for it.
            finalizeFailure(context, messageId, tracking.lastError ?: "send failed")
        } else {
            // Every part reached the radio. It must come out of the local retry
            // queue now, regardless of whether a delivery confirmation ever
            // arrives (many carriers never send one) - otherwise the next poll
            // cycle's processDueRetries() would resend an already-sent message.
            withContext(Dispatchers.IO) {
                val dao = AppDatabase.get(context).failedMessageDao()
                dao.getById(messageId)?.let { dao.delete(it) }
                AppDatabase.get(context).sentLogDao().insert(
                    SentLogEntry(
                        messageId = messageId,
                        to = tracking.to,
                        status = "sent",
                        timestampMillis = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    suspend fun onPartDelivered(context: Context, messageId: String, success: Boolean) {
        val tracking = inFlight[messageId] ?: return
        synchronized(tracking) {
            if (success) tracking.deliveredOk++ else tracking.deliveredFail++
        }
        val deliveredSoFar = tracking.deliveredOk + tracking.deliveredFail
        if (deliveredSoFar == tracking.totalParts) {
            if (tracking.deliveredFail == 0) {
                finalizeDelivered(context, messageId)
            } else {
                finalizeFailure(context, messageId, "carrier reported non-delivery")
            }
        }
    }

    private suspend fun finalizeDelivered(context: Context, messageId: String) {
        val tracking = inFlight.remove(messageId) ?: return
        withContext(Dispatchers.IO) {
            val prefs = SecurePrefs(context)
            reportStatusBestEffort(prefs, messageId, "delivered", null)
            AppDatabase.get(context).sentLogDao().insert(
                SentLogEntry(
                    messageId = messageId,
                    to = tracking.to,
                    status = "delivered",
                    timestampMillis = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun finalizeFailure(context: Context, messageId: String, error: String) {
        val tracking = inFlight.remove(messageId) ?: return
        withContext(Dispatchers.IO) {
            val dao = AppDatabase.get(context).failedMessageDao()
            val existing = dao.getById(messageId)
            val attempts = (existing?.attempts ?: 0) + 1

            AppDatabase.get(context).sentLogDao().insert(
                SentLogEntry(
                    messageId = messageId,
                    to = tracking.to,
                    status = if (attempts >= FailedMessage.MAX_ATTEMPTS) "gave_up" else "retrying",
                    detail = error,
                    timestampMillis = System.currentTimeMillis()
                )
            )

            if (attempts >= FailedMessage.MAX_ATTEMPTS) {
                val prefs = SecurePrefs(context)
                reportStatusBestEffort(prefs, messageId, "failed", error)
                existing?.let { dao.delete(it) }
            } else {
                dao.upsert(
                    FailedMessage(
                        id = messageId,
                        to = tracking.to,
                        body = tracking.body,
                        attempts = attempts,
                        nextRetryAtMillis = System.currentTimeMillis() + FailedMessage.backoffDelayMillis(attempts),
                        lastError = error
                    )
                )
            }
        }
    }

    private suspend fun reportStatusBestEffort(prefs: SecurePrefs, id: String, status: String, error: String?) {
        if (!prefs.isConfigured()) return
        try {
            ApiClient.create(prefs).postStatus(StatusRequestDto(id = id, status = status, error = error))
        } catch (e: Exception) {
            Log.w(TAG, "failed to report status for $id: ${e.message}")
        }
    }
}
