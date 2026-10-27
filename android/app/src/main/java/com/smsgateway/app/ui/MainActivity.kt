package com.smsgateway.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.smsgateway.app.R
import com.smsgateway.app.data.SecurePrefs
import com.smsgateway.app.data.local.AppDatabase
import com.smsgateway.app.databinding.ActivityMainBinding
import com.smsgateway.app.service.GatewayServiceState
import com.smsgateway.app.service.SmsGatewayForegroundService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SecurePrefs

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results[Manifest.permission.SEND_SMS] == true) {
            maybeRequestBatteryExemption()
        } else {
            Toast.makeText(this, "SMS permission is required to send messages", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = SecurePrefs(this)

        binding.buttonSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.buttonToggleService.setOnClickListener { onToggleServiceClicked() }

        GatewayServiceState.update { it.copy(sentToday = prefs.sentToday(), lastPollAtMillis = prefs.lastPollAtMillis) }

        ensureSmsPermission()
        observeServiceState()
        observeRecentLog()
    }

    private fun onToggleServiceClicked() {
        val isRunning = GatewayServiceState.status.value.isRunning
        if (isRunning) {
            stopService(Intent(this, SmsGatewayForegroundService::class.java))
            return
        }

        if (!hasSmsPermission()) {
            ensureSmsPermission()
            return
        }
        if (!prefs.isConfigured()) {
            Toast.makeText(this, "Configure server URL and device token in Settings first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, SmsGatewayForegroundService::class.java))
    }

    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun ensureSmsPermission() {
        if (hasSmsPermission()) {
            maybeRequestBatteryExemption()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sms_permission_rationale_title)
            .setMessage(R.string.sms_permission_rationale_body)
            .setCancelable(false)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                val permissions = mutableListOf(Manifest.permission.SEND_SMS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                requestPermissions.launch(permissions.toTypedArray())
            }
            .show()
    }

    private fun maybeRequestBatteryExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle(R.string.battery_optimization_title)
            .setMessage(R.string.battery_optimization_body)
            .setPositiveButton(R.string.request_exemption) { _, _ ->
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    @SuppressLint("SetTextI18n")
    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GatewayServiceState.status.collect { status ->
                    binding.textStatus.text = getString(if (status.isRunning) R.string.status_running else R.string.status_stopped)
                    binding.buttonToggleService.text = getString(if (status.isRunning) R.string.stop_service else R.string.start_service)
                    binding.textSentToday.text = "Sent today: ${status.sentToday}"
                    binding.textLastPoll.text = "Last poll: ${formatTimestamp(status.lastPollAtMillis)}"
                    binding.textSignalStrength.text = "Signal: ${status.signalStrengthLabel}"
                }
            }
        }
    }

    private fun observeRecentLog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val entries = AppDatabase.get(this@MainActivity).sentLogDao().recent(50)
                    val lines = entries.map { entry ->
                        "${formatTimestamp(entry.timestampMillis)}  ${entry.status}  ${entry.to}" +
                            (entry.detail?.let { "  ($it)" } ?: "")
                    }
                    binding.listLog.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, lines)
                    delay(5_000)
                }
            }
        }
    }

    private fun formatTimestamp(millis: Long): String {
        if (millis <= 0L) return "never"
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(millis))
    }
}
