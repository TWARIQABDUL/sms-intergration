package com.smsgateway.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.time.LocalDate

/**
 * Stores server URL, device token and settings in EncryptedSharedPreferences
 * so the device token (a bearer secret) is never persisted in plaintext.
 */
class SecurePrefs(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "sms_gateway_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    var pollIntervalSeconds: Int
        get() = prefs.getInt(KEY_POLL_INTERVAL, DEFAULT_POLL_INTERVAL_SECONDS)
        set(value) = prefs.edit().putInt(KEY_POLL_INTERVAL, value).apply()

    /** Preferred subscription id for outgoing SMS on dual-SIM devices, or -1 for "ask/default". */
    var preferredSubscriptionId: Int
        get() = prefs.getInt(KEY_SUBSCRIPTION_ID, -1)
        set(value) = prefs.edit().putInt(KEY_SUBSCRIPTION_ID, value).apply()

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var lastPollAtMillis: Long
        get() = prefs.getLong(KEY_LAST_POLL_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_POLL_AT, value).apply()

    fun incrementSentToday(): Int {
        val today = LocalDate.now().toString()
        val storedDate = prefs.getString(KEY_SENT_TODAY_DATE, null)
        val current = if (storedDate == today) prefs.getInt(KEY_SENT_TODAY_COUNT, 0) else 0
        val updated = current + 1
        prefs.edit()
            .putString(KEY_SENT_TODAY_DATE, today)
            .putInt(KEY_SENT_TODAY_COUNT, updated)
            .apply()
        return updated
    }

    fun sentToday(): Int {
        val today = LocalDate.now().toString()
        val storedDate = prefs.getString(KEY_SENT_TODAY_DATE, null)
        return if (storedDate == today) prefs.getInt(KEY_SENT_TODAY_COUNT, 0) else 0
    }

    fun isConfigured(): Boolean = !serverUrl.isNullOrBlank() && !deviceToken.isNullOrBlank()

    companion object {
        const val DEFAULT_POLL_INTERVAL_SECONDS = 20
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_POLL_INTERVAL = "poll_interval_seconds"
        private const val KEY_SUBSCRIPTION_ID = "preferred_subscription_id"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_LAST_POLL_AT = "last_poll_at_millis"
        private const val KEY_SENT_TODAY_DATE = "sent_today_date"
        private const val KEY_SENT_TODAY_COUNT = "sent_today_count"
    }
}
