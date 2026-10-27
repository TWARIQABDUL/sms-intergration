package com.smsgateway.app.service

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Reports carrier-level delivery confirmation (or non-delivery) for one SMS part. */
class SmsDeliveredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID) ?: return
        val success = resultCode == Activity.RESULT_OK

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SmsSendCoordinator.onPartDelivered(context, messageId, success)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
