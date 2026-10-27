package com.smsgateway.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.smsgateway.app.data.SecurePrefs

/** Restarts the gateway service after reboot if the user had it running before. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = SecurePrefs(context)
        if (prefs.serviceEnabled && prefs.isConfigured()) {
            val serviceIntent = Intent(context, SmsGatewayForegroundService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
