package com.kavachvoice

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnBatteryOpt).setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val batteryOptDisabled = isBatteryOptimizationDisabled()

        findViewById<TextView>(R.id.tvAccessibilityStatus).text =
            if (accessibilityEnabled) "Accessibility Service: ACTIVE" else "Accessibility Service: OFF — tap below"

        findViewById<TextView>(R.id.tvBatteryStatus).text =
            if (batteryOptDisabled) "Battery Optimization: DISABLED (good)" else "Battery Optimization: ENABLED — tap below"

        findViewById<TextView>(R.id.tvProtectionStatus).text =
            if (accessibilityEnabled && batteryOptDisabled) "KavachVoice: PROTECTING" else "KavachVoice: NOT FULLY ACTIVE"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${KavachAccessibilityService::class.java.canonicalName}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(service) == true
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}
