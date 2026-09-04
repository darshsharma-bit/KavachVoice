package com.kavachvoice

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private var tapCount = 0
    private val tapHandler = Handler(Looper.getMainLooper())

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

        // 3x-tap on version label triggers demo keyword mode (Moment 3 fallback)
        findViewById<TextView>(R.id.tvVersion).setOnClickListener {
            tapCount++
            tapHandler.removeCallbacksAndMessages(null)
            if (tapCount >= 3) {
                tapCount = 0
                triggerDemoKeywords()
            } else {
                tapHandler.postDelayed({ tapCount = 0 }, 1500)
            }
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

    private fun triggerDemoKeywords() {
        val svc = KavachAccessibilityService.instance
        if (svc != null) {
            svc.getKeywordScanner()?.triggerDemoKeywords()
            Toast.makeText(this, "Demo mode: keyword alert triggered", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tapHandler.removeCallbacksAndMessages(null)
    }
}
