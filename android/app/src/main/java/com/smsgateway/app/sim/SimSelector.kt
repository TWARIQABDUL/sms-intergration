package com.smsgateway.app.sim

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

/**
 * Wraps SubscriptionManager so the rest of the app doesn't need to think
 * about single- vs dual-SIM devices. [preferredSubscriptionId] of -1 means
 * "use the device default" (relevant on single-SIM devices or when the user
 * hasn't picked a slot).
 */
object SimSelector {

    fun hasReadPhoneStatePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    /** Active subscriptions (SIM slots), or an empty list if none/no permission. */
    fun activeSubscriptions(context: Context): List<SubscriptionInfo> {
        if (!hasReadPhoneStatePermission(context)) return emptyList()
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
            ?: return emptyList()
        return try {
            subscriptionManager.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun isDualSim(context: Context): Boolean = activeSubscriptions(context).size > 1

    /**
     * SmsManager bound to [preferredSubscriptionId] when it's a currently
     * active subscription, otherwise the device's default SmsManager.
     */
    fun smsManagerFor(context: Context, preferredSubscriptionId: Int): SmsManager {
        val subscriptions = activeSubscriptions(context)
        val match = subscriptions.firstOrNull { it.subscriptionId == preferredSubscriptionId }
        return if (match != null) {
            SmsManager.getSmsManagerForSubscriptionId(match.subscriptionId)
        } else {
            context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        }
    }

    fun hasNoSim(context: Context): Boolean = activeSubscriptions(context).isEmpty()
}
