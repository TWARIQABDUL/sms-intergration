package com.smsgateway.app.service

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

const val EXTRA_MESSAGE_ID = "message_id"
const val ACTION_SMS_SENT = "com.smsgateway.app.SMS_SENT"
const val ACTION_SMS_DELIVERED = "com.smsgateway.app.SMS_DELIVERED"

/** Reports the result of handing a message (or one part of it) to the radio. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return
        val success = resultCode == Activity.RESULT_OK
        val error = if (success) null else describeSendError(resultCode)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmsSendCoordinator.onPartSent(context, messageId, success, error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun describeSendError(resultCode: Int): String = when (resultCode) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off (airplane mode?)"
        else -> "send error (code $resultCode)"
    }
}
