package com.smsgateway.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.smsgateway.app.data.SecurePrefs
import com.smsgateway.app.databinding.ActivitySettingsBinding
import com.smsgateway.app.sim.SimSelector

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SecurePrefs

    private val requestReadPhoneState = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showSimPickerDialog() else Toast.makeText(this, "Permission needed to list SIMs", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = SecurePrefs(this)

        binding.editServerUrl.setText(prefs.serverUrl ?: "")
        binding.editDeviceToken.setText(prefs.deviceToken ?: "")
        binding.editPollIntervalSeconds.setText(prefs.pollIntervalSeconds.toString())
        updateSimLabel()

        binding.buttonChooseSim.setOnClickListener { onChooseSimClicked() }
        binding.buttonRequestBatteryExemption.setOnClickListener { requestBatteryExemption() }
        binding.buttonSave.setOnClickListener { onSaveClicked() }
    }

    private fun onChooseSimClicked() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (granted) showSimPickerDialog() else requestReadPhoneState.launch(Manifest.permission.READ_PHONE_STATE)
    }

    private fun showSimPickerDialog() {
        val subscriptions = SimSelector.activeSubscriptions(this)
        if (subscriptions.size <= 1) {
            Toast.makeText(this, "Only one SIM detected; automatic selection will be used", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = subscriptions.map { "SIM: ${it.displayName} (slot ${it.simSlotIndex})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose SIM for outgoing SMS")
            .setItems(labels) { _, index ->
                val chosen = subscriptions[index]
                prefs.preferredSubscriptionId = chosen.subscriptionId
                updateSimLabel()
            }
            .show()
    }

    private fun updateSimLabel() {
        val subId = prefs.preferredSubscriptionId
        val subscriptions = SimSelector.activeSubscriptions(this)
        val chosen = subscriptions.firstOrNull { it.subscriptionId == subId }
        binding.textSimPicker.text = if (chosen != null) "SIM: ${chosen.displayName}" else "SIM: automatic"
    }

    private fun requestBatteryExemption() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Already exempt from battery optimization", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun onSaveClicked() {
        val serverUrl = binding.editServerUrl.text.toString().trim()
        val deviceToken = binding.editDeviceToken.text.toString().trim()
        val pollIntervalText = binding.editPollIntervalSeconds.text.toString().trim()

        val error = validate(serverUrl, deviceToken, pollIntervalText)
        if (error != null) {
            binding.textValidationError.text = error
            return
        }

        prefs.serverUrl = serverUrl
        prefs.deviceToken = deviceToken
        prefs.pollIntervalSeconds = pollIntervalText.toInt()
        binding.textValidationError.text = ""
        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun validate(serverUrl: String, deviceToken: String, pollIntervalText: String): String? {
        if (serverUrl.isEmpty()) return "Server URL is required"
        if (!serverUrl.startsWith("https://")) return "Server URL must start with https:// (plain http is rejected)"
        if (runCatching { Uri.parse(serverUrl).host.isNullOrBlank() }.getOrDefault(true)) return "Server URL is not a valid URL"
        if (deviceToken.isEmpty()) return "Device token is required"
        val interval = pollIntervalText.toIntOrNull()
        if (interval == null || interval < 5 || interval > 3600) return "Polling interval must be a number between 5 and 3600 seconds"
        return null
    }
}
