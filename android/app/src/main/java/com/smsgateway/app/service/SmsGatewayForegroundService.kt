package com.smsgateway.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.smsgateway.app.R
import com.smsgateway.app.data.SecurePrefs
import com.smsgateway.app.data.local.AppDatabase
import com.smsgateway.app.data.local.FailedMessage
import com.smsgateway.app.network.ApiClient
import com.smsgateway.app.sim.SimSelector
import com.smsgateway.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "SmsGatewayService"
private const val NOTIFICATION_CHANNEL_ID = "sms_gateway_service"
private const val NOTIFICATION_ID = 1

class SmsGatewayForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var pollingJob: Job? = null
    private var telephonyManager: TelephonyManager? = null
    private var signalListener: PhoneStateListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithNotification(SecurePrefs(applicationContext).sentToday())
        registerSignalStrengthListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = SecurePrefs(applicationContext)
        prefs.serviceEnabled = true
        GatewayServiceState.update { it.copy(isRunning = true, sentToday = prefs.sentToday(), lastPollAtMillis = prefs.lastPollAtMillis) }

        if (pollingJob == null) {
            pollingJob = scope.launch { pollingLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        signalListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
        SecurePrefs(applicationContext).serviceEnabled = false
        GatewayServiceState.update { it.copy(isRunning = false) }
        super.onDestroy()
    }

    private suspend fun pollingLoop() {
        val context = applicationContext
        while (scope.isActive) {
            try {
                processDueRetries(context)
                pollOnce(context)
            } catch (e: Exception) {
                Log.w(TAG, "poll cycle failed: ${e.message}")
            }
            val intervalSeconds = SecurePrefs(context).pollIntervalSeconds
                .coerceAtLeast(5) // guard against a mistakenly tiny/zero interval
            delay(intervalSeconds * 1000L)
        }
    }

    private suspend fun pollOnce(context: android.content.Context) {
        val prefs = SecurePrefs(context)
        if (!prefs.isConfigured()) return

        val response = ApiClient.create(prefs).getPending()
        val now = System.currentTimeMillis()
        prefs.lastPollAtMillis = now
        GatewayServiceState.update { it.copy(lastPollAtMillis = now) }

        if (!response.isSuccessful) {
            Log.w(TAG, "pending poll returned HTTP ${response.code()}")
            return
        }

        val messages = response.body()?.messages.orEmpty()
        for (message in messages) {
            sendOneMessage(context, prefs, message.id, message.to, message.body)
        }
    }

    private suspend fun processDueRetries(context: android.content.Context) {
        val prefs = SecurePrefs(context)
        if (!prefs.isConfigured()) return
        val dao = AppDatabase.get(context).failedMessageDao()
        val due = dao.dueForRetry(System.currentTimeMillis())
        for (failed: FailedMessage in due) {
            sendOneMessage(context, prefs, failed.id, failed.to, failed.body)
        }
    }

    private suspend fun sendOneMessage(context: android.content.Context, prefs: SecurePrefs, id: String, to: String, body: String) {
        if (SimSelector.hasNoSim(context)) {
            SmsSendCoordinator.failImmediately(context, id, to, body, "no active SIM")
            return
        }
        try {
            val smsManager = SimSelector.smsManagerFor(context, prefs.preferredSubscriptionId)
            SmsSender.send(context, smsManager, id, to, body)
            val sentToday = prefs.incrementSentToday()
            GatewayServiceState.update { it.copy(sentToday = sentToday) }
            updateNotification(sentToday)
        } catch (e: Exception) {
            Log.w(TAG, "send failed for $id: ${e.message}")
            SmsSendCoordinator.failImmediately(context, id, to, body, e.message ?: "unknown send error")
        }
    }

    private fun registerSignalStrengthListener() {
        telephonyManager = getSystemService(TelephonyManager::class.java)
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in API 31+; still functional, kept for minSdk 26 simplicity.")
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                val level = signalStrength.level // 0 (none) .. 4 (great)
                GatewayServiceState.update { it.copy(signalStrengthLabel = "Level $level/4") }
            }
        }
        signalListener = listener
        try {
            @Suppress("DEPRECATION")
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot observe signal strength: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(sentToday: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title_format, sentToday))
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun startForegroundWithNotification(sentToday: Int) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(sentToday),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        )
    }

    private fun updateNotification(sentToday: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(sentToday))
    }
}
