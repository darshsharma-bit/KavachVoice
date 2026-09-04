package com.kavachvoice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.io.InputStream

/**
 * KavachAccessibilityService — Host service for Layer 2 CallGuard.
 *
 * Captures foreground activity transitions and synchronizes call state with the
 * explainable multi-signal risk engine.
 *
 * Architecture Compliance:
 * 1. Single authoritative microphone capture path: Only KeywordScanner acquires the microphone.
 *    VoiceArmorEngine (Layer 1 OEM Blueprint) is isolated/deactivated during consumer monitoring
 *    to eliminate dual-AudioRecord collisions on consumer Android devices.
 * 2. Activity Filtering & Debouncing: Ignores transient toasts, notifications, and non-activity windows.
 * 3. Unified State Synchronization: Feeds call state, UPI foreground events, and keyword triggers
 *    into CallGuardEngine for deterministic truth-table evaluation.
 */
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

    // Layer 1 — VoiceArmorEngine is isolated from consumer monitoring to avoid dual-AudioRecord collision.
    // Preserved for OEM HAL Blueprint reference.
    private var voiceArmor: VoiceArmorEngine? = null

    // Layer 2 — CallGuard engine + Authoritative single-path keyword scanner + VoiceID client
    private var callGuard: CallGuardEngine? = null
    private var keywordScanner: KeywordScanner? = null
    private var voiceIdClient: VoiceIdClient? = null

    private var telephonyManager: TelephonyManager? = null
    private var phoneStateListener: PhoneStateListener? = null
    @Volatile private var simulatedCallActive = false
    @Volatile private var currentCallSessionId: Long = 0L
    @Volatile private var isCallCurrentlyActive: Boolean = false

    private var lastWindowChangeTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(tag, "KavachVoice Accessibility Service connected")

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
            packageNames = null
        }

        // Initialize CallGuard Engine and VoiceID Client
        voiceIdClient = VoiceIdClient(this)
        callGuard = CallGuardEngine(this, upiPackages).also { it.start() }

        // Configure Authoritative Microphone Capture (Single capture path for Layer 2)
        // CRITICAL PRIVACY ARCHITECTURE:
        // Microphone capture is NOT started here. It starts strictly when an active call is detected.
        keywordScanner = KeywordScanner(this) { keywords ->
            Log.d(tag, "Audio scanner reported keywords: $keywords")
            callGuard?.onKeywordsDetected(keywords)
        }.also { scanner ->
            // Connect rolling audio buffer to VoiceID FastAPI backend
            scanner.onAudioWindowAvailableWithStats = { pcm16Window, windowNum, rms, peak, speech, durMs, sampleCount ->
                val sessionId = currentCallSessionId
                val activeCall = isCallActive() && isCallCurrentlyActive
                if (activeCall && sessionId != 0L) {
                    val ts = System.currentTimeMillis()
                    Log.i(tag, "VoiceID: Transmitting live WINDOW #$windowNum (samples=$sampleCount, dur=${durMs}ms, RMS=$rms, peak=$peak, speechPresent=$speech, ts=$ts) to backend for session #$sessionId")
                    voiceIdClient?.analyzeAudioAsync(pcm16Window, sessionId) { result ->
                        Log.i(tag, "VoiceID result received by AccessibilityService: sessId=${result.sessionId}, currentCallSessionId=$currentCallSessionId, isCallCurrentlyActive=$isCallCurrentlyActive, isCallActive()=${isCallActive()}")
                        // Session validation: only deliver result if session is still active and matches
                        if (result.sessionId == currentCallSessionId && isCallActive()) {
                            callGuard?.onVoiceIdResult(
                                result,
                                windowNum = windowNum,
                                rms = rms,
                                peak = peak,
                                speechPresent = speech,
                                durationMs = durMs,
                                sampleCount = sampleCount
                            )
                        } else {
                            Log.w(tag, "Discarding stale VoiceID result for expired session #${result.sessionId} (current=$currentCallSessionId, active=${isCallActive()})")
                        }
                    }
                }
            }
        }

        // Register telephony state listener for call transitions
        setupTelephonyListener()

        // Sync initial call state: if phone is currently on a call, start capture; otherwise mic stays off
        handleCallStateTransition(isCallActive())

        // Register testing broadcast receiver for seamless background ADB evaluation
        setupTestReceiver()

        // Start foreground service to maintain background priority
        try {
            startForegroundService(Intent(this, KavachForegroundService::class.java))
        } catch (e: Exception) {
            Log.w(tag, "Failed to start KavachForegroundService: ${e.message}")
        }
    }

    /**
     * Authoritative Call State & Microphone Lifecycle Controller:
     * - NO CALL -> AudioRecord OFF, mic released, buffer empty, no uploads
     * - CALL ACTIVE -> Start AudioRecord, assign new monotonic sessionId, enable VoiceID analysis
     * - CALL ENDS -> Immediately stop AudioRecord, release hardware mic, invalidate session, clear buffer
     */
    fun handleCallStateTransition(active: Boolean) {
        synchronized(this) {
            if (active == isCallCurrentlyActive) return
            isCallCurrentlyActive = active

            if (active) {
                currentCallSessionId = System.currentTimeMillis()
                Log.i(tag, "Call START detected — Initiating Session #$currentCallSessionId, starting microphone capture")
                callGuard?.onCallSessionStarted(currentCallSessionId)
                keywordScanner?.start()
            } else {
                val endedSessionId = currentCallSessionId
                currentCallSessionId = 0L
                Log.i(tag, "Call END detected — Terminating Session #$endedSessionId, releasing microphone capture immediately")
                keywordScanner?.stop()
                callGuard?.onCallSessionEnded(endedSessionId)
            }
        }
    }

    private fun setupTelephonyListener() {
        try {
            telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            phoneStateListener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    super.onCallStateChanged(state, phoneNumber)
                    val active = isCallActive()
                    Log.d(tag, "Phone state changed (rawState=$state, isCallActive=$active)")
                    handleCallStateTransition(active)
                }
            }
            @Suppress("DEPRECATION")
            telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (e: Exception) {
            Log.w(tag, "Telephony listener registration note: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // Ignore transitions caused by our own overlay or system UI decorations
        if (pkg == packageName || pkg == "com.android.systemui" || pkg == "android") {
            return
        }

        // Debounce: 300ms window to ignore transient identical UI transitions
        val now = System.currentTimeMillis()
        if (now - lastWindowChangeTime < 300) return
        lastWindowChangeTime = now

        Log.d(tag, "Window state transition: $className ($pkg)")

        // Keep call state synchronized
        val activeCall = isCallActive()
        handleCallStateTransition(activeCall)

        // Route package transition
        if (pkg in upiPackages) {
            Log.w(tag, "UPI payment app ($pkg) entered foreground (callActive=$activeCall)")
            callGuard?.onUpiAppDetectedDuringCall(pkg)
        } else {
            callGuard?.onNonUpiAppForegrounded()
        }
    }

    override fun onInterrupt() {
        Log.w(tag, "Accessibility service interrupted")
        callGuard?.stop()
        keywordScanner?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null

        try {
            @Suppress("DEPRECATION")
            phoneStateListener?.let { telephonyManager?.listen(it, PhoneStateListener.LISTEN_NONE) }
        } catch (e: Exception) {
            Log.w(tag, "Error unregistering phone state listener: ${e.message}")
        }

        try {
            testReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.w(tag, "Error unregistering test receiver: ${e.message}")
        }

        callGuard?.stop()
        keywordScanner?.stop()
        sendBroadcast(Intent("com.kavachvoice.RESTART_SERVICE"))
        Log.i(tag, "KavachAccessibilityService destroyed and released")
    }

    private var testReceiver: BroadcastReceiver? = null

    private fun setupTestReceiver() {
        val filter = IntentFilter().apply {
            addAction("com.kavachvoice.TEST_VOICE")
            addAction("com.kavachvoice.TEST_DEMO")
            addAction("com.kavachvoice.TEST_CALL_STATE")
            addAction("com.kavachvoice.SET_BACKEND")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    "com.kavachvoice.TEST_CALL_STATE" -> {
                        val active = intent.getBooleanExtra("active", true)
                        simulatedCallActive = active
                        Log.i(tag, "Simulated call state update: simulatedActive=$active")
                        handleCallStateTransition(isCallActive())
                    }
                    "com.kavachvoice.TEST_VOICE" -> {
                        val type = intent.getStringExtra("type") ?: "mic"
                        when (type) {
                            "genuine" -> {
                                val bytes = readAssetBytes("genuine_test.wav")
                                if (bytes != null) testAnalyzeAudio(bytes)
                            }
                            "synthetic" -> {
                                val bytes = readAssetBytes("synthetic_test.wav")
                                if (bytes != null) testAnalyzeAudio(bytes)
                            }
                            "mic" -> {
                                val window = keywordScanner?.getLatestAudioWindow()
                                if (window != null) {
                                    val sessId = currentCallSessionId
                                    val rms = keywordScanner?.currentRms ?: 0f
                                    val peak = keywordScanner?.currentPeak ?: 0f
                                    val speech = keywordScanner?.isSpeechPresent ?: false
                                    voiceIdClient?.analyzeAudioAsync(window, sessId) { result ->
                                        Log.i(tag, "VoiceID result received by AccessibilityService (mic broadcast): sessId=${result.sessionId}, currentCallSessionId=$currentCallSessionId, isCallCurrentlyActive=$isCallCurrentlyActive, isCallActive()=${isCallActive()}")
                                        callGuard?.onVoiceIdResult(
                                            result,
                                            windowNum = 0,
                                            rms = rms,
                                            peak = peak,
                                            speechPresent = speech,
                                            durationMs = KeywordScanner.LIVE_WINDOW_MS,
                                            sampleCount = window.size
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "com.kavachvoice.TEST_DEMO" -> {
                        val mode = intent.getStringExtra("mode")
                        when (mode) {
                            "fraud" -> {
                                callGuard?.resetKeywords()
                                keywordScanner?.triggerDemoFraudKeywords()
                            }
                            "urgency" -> {
                                callGuard?.resetKeywords()
                                keywordScanner?.triggerDemoUrgencyKeywords()
                            }
                            "all" -> {
                                callGuard?.resetKeywords()
                                keywordScanner?.triggerDemoAllKeywords()
                            }
                            "reset" -> {
                                callGuard?.resetKeywords()
                            }
                        }
                    }
                    "com.kavachvoice.SET_BACKEND" -> {
                        val url = intent.getStringExtra("url")
                        if (!url.isNullOrBlank()) voiceIdClient?.setBackendUrl(url)
                    }
                }
            }
        }
        testReceiver = receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun readAssetBytes(filename: String): ByteArray? {
        return try {
            val isStream: InputStream = assets.open(filename)
            val bytes = isStream.readBytes()
            isStream.close()
            bytes
        } catch (e: Exception) {
            Log.e(tag, "Error loading asset $filename: ${e.message}")
            null
        }
    }

    fun getKeywordScanner(): KeywordScanner? = keywordScanner
    fun getCallGuard(): CallGuardEngine? = callGuard
    fun getVoiceIdClient(): VoiceIdClient? = voiceIdClient
    fun getCurrentCallSessionId(): Long = currentCallSessionId
    fun isMicrophoneCapturing(): Boolean = keywordScanner?.isRecordingActive() ?: false

    /**
     * Send arbitrary audio bytes (e.g. test assets) to VoiceID backend and dispatch to CallGuard.
     * Developer/reference asset tests explicitly use sessionId == 0L.
     */
    fun testAnalyzeAudio(wavBytes: ByteArray, onResult: ((VoiceIdResult) -> Unit)? = null) {
        Log.i(tag, "VoiceID: Triggering test inference on ${wavBytes.size} bytes of WAV audio (developer test sessionId=0L)")
        val sessId = 0L // Explicit developer/reference test path
        voiceIdClient?.analyzeWavBytesAsync(wavBytes, sessId) { result ->
            Log.i(tag, "VoiceID result received by AccessibilityService (test asset): sessId=${result.sessionId}, currentCallSessionId=$currentCallSessionId, isCallCurrentlyActive=$isCallCurrentlyActive, isCallActive()=${isCallActive()}")
            callGuard?.onVoiceIdResult(result)
            onResult?.invoke(result)
        }
    }

    /**
     * Deterministic simulation trigger for SIH Demo Moment 3.
     */
    fun triggerSimulation() {
        Log.i(tag, "Triggering deterministic SIH Demo Moment 3 simulation")
        keywordScanner?.triggerDemoAllKeywords()
    }

    /**
     * Reliable dual-check for active phone calls:
     * 1. Standard SIM Cellular call (CALL_STATE_OFFHOOK)
     * 2. VoIP call via WhatsApp, Telegram, etc. (AudioManager MODE_IN_COMMUNICATION or MODE_IN_CALL)
     */
    fun isCallActive(): Boolean {
        if (simulatedCallActive) return true
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val isCellular = tm.callState == TelephonyManager.CALL_STATE_OFFHOOK
            val isVoip = am.mode == AudioManager.MODE_IN_COMMUNICATION ||
                    am.mode == AudioManager.MODE_IN_CALL

            isCellular || isVoip
        } catch (e: Exception) {
            Log.w(tag, "Error determining call state: ${e.message}")
            false
        }
    }
}
