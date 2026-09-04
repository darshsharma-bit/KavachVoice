package com.kavachvoice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class KavachAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: KavachAccessibilityService? = null
    }

    private val tag = "KavachA11y"

    private val upiPackages = setOf(
        "com.google.android.apps.nbu.paisa.user",   // Google Pay
        "net.one97.paytm",                           // Paytm
        "com.phonepe.app",                           // PhonePe
        "in.org.npci.upiapp",                        // BHIM
        "com.amazon.mShop.android.shopping",         // Amazon Pay
    )

    // Layer 1 — VoiceArmor
    private var voiceArmor: VoiceArmorEngine? = null

    // Layer 2 — CallGuard + keyword scanner
    private var callGuard: CallGuardEngine? = null
    private var keywordScanner: KeywordScanner? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(tag, "KavachVoice Accessibility Service connected")

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }

        voiceArmor = VoiceArmorEngine(this).also { it.start() }
        callGuard = CallGuardEngine(this, upiPackages).also { it.start() }

        keywordScanner = KeywordScanner(this) { keywords ->
            // Only escalate to CallGuard when a call is actually active
            if (isCallActive()) callGuard?.onKeywordsDetected(keywords)
        }.also { it.start() }

        startForegroundService(Intent(this, KavachForegroundService::class.java))
    }

    private var lastWindowChangeTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // Filter: Must be an interactive Activity, ignoring background push notifications & toasts
        val className = event.className?.toString() ?: return
        if (!className.contains("Activity")) return

        // Debounce: 500ms window to ignore transient UI transitions
        val now = System.currentTimeMillis()
        if (now - lastWindowChangeTime < 500) return
        lastWindowChangeTime = now

        val pkg = event.packageName?.toString() ?: return
        Log.d(tag, "Foreground Activity: $className ($pkg)")

        // Layer 2: UPI payment app detection during active call
        if (pkg in upiPackages && isCallActive()) {
            Log.w(tag, "UPI payment app $pkg foregrounded during active call — triggering Layer 2 Alert")
            callGuard?.onUpiAppDetectedDuringCall(pkg)
        }
    }

    override fun onInterrupt() {
        Log.w(tag, "Accessibility service interrupted")
        voiceArmor?.stop()
        callGuard?.stop()
        keywordScanner?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        voiceArmor?.stop()
        callGuard?.stop()
        keywordScanner?.stop()
        sendBroadcast(Intent("com.kavachvoice.RESTART_SERVICE"))
    }

    fun getKeywordScanner(): KeywordScanner? = keywordScanner

    fun triggerSimulation() {
        Log.w(tag, "Triggering internal Demo Moment 3 simulation")
        callGuard?.onKeywordsDetected(listOf("otp", "बताइए", "jaldi"))
    }

    fun isCallActive(): Boolean {
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager

        // 1. Standard SIM Cellular call
        val isCellular = tm.callState == TelephonyManager.CALL_STATE_OFFHOOK

        // 2. VoIP / WhatsApp / Telegram call (Android sets MODE_IN_COMMUNICATION)
        val isVoip = am.mode == android.media.AudioManager.MODE_IN_COMMUNICATION ||
                am.mode == android.media.AudioManager.MODE_IN_CALL

        return isCellular || isVoip
    }
}
