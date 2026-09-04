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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        Log.d(tag, "Window changed: $pkg")

        // Layer 2: UPI app detection during active call
        if (pkg in upiPackages && isCallActive()) {
            Log.w(tag, "UPI app $pkg detected during active call — triggering Layer 2")
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

    private fun isCallActive(): Boolean {
        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        return tm.callState == TelephonyManager.CALL_STATE_OFFHOOK
    }
}
