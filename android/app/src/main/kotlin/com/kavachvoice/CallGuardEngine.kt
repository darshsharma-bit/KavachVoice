package com.kavachvoice

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Layer 2 — CallGuard Engine.
 *
 * Implements deterministic multi-signal fraud evaluation and explainable overlay intervention.
 *
 * Truth Table:
 * - no call + no UPI             -> GREEN (Safe)
 * - no call + UPI                -> GREEN (Benign payment)
 * - call + no UPI                -> GREEN / MONITORING (Ambient observation)
 * - call + UPI                   -> ORANGE (Elevated risk context)
 * - call + UPI + fraud signal    -> RED (Direct scam intervention)
 * - call + UPI + urgency only    -> ORANGE (Elevated risk context)
 * - call + UPI + fraud + urgency -> RED (High-confidence scam intervention)
 *
 * Hardened RED intervention:
 * - High-visibility crimson warning banner with real explainable signals
 * - Hindi TTS playback with safe failure isolation (TTS failure never crashes CallGuard)
 * - 10-second safety cooldown countdown before dismiss button is enabled
 * - "📞 1930 साइबर हेल्पलाइन" ACTION_DIAL action (tel:1930), never auto-calling
 * - Explicitly framed as a warning/friction mechanism, not a guaranteed banking transaction lock
 */
class CallGuardEngine(
    private val context: Context,
    private val upiPackages: Set<String>,
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "CallGuard"
    }
    private val riskEngine = CallGuardRiskEngine()

    // Multi-signal evaluated state
    @Volatile private var callActive = false
    @Volatile private var upiForeground = false
    @Volatile private var currentUpiPackage: String? = null
    @Volatile private var fraudKeywordDetected = false
    @Volatile private var urgencyDetected = false
    @Volatile private var voiceCloneDetected = false
    @Volatile private var voiceCloneConfidence = 0.0f
    @Volatile private var latestVoiceIdVerdict: String = "STANDBY"
    @Volatile private var latestVoiceIdLatency: Float = 0f
    @Volatile private var latestRawNet2Score: Float = 0f
    @Volatile private var latestEcapaScore: Float = 0f
    private val detectedFraudList = mutableListOf<String>()
    private val detectedUrgencyList = mutableListOf<String>()

    // Overlay state
    private var currentLevel: CallGuardRiskLevel = CallGuardRiskLevel.GREEN
    private var overlayContainer: LinearLayout? = null
    private var explanationTextView: TextView? = null
    private var countdownTimer: CountDownTimer? = null

    // TTS state
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val handler = Handler(Looper.getMainLooper())
    private val wm by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    val sessionTracker = CallSessionTracker()

    fun start() {
        inspectPlaceholderModel()
        try {
            tts = TextToSpeech(context, this)
        } catch (e: Exception) {
            Log.w(TAG, "TTS initialization failed safely: ${e.message}")
        }
        Log.i(TAG, "CallGuard Engine started with Explainable Multi-Signal Risk Engine")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                val result = tts?.setLanguage(Locale("hi", "IN"))
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) {
                    tts?.setLanguage(Locale.getDefault())
                    ttsReady = true
                }
                Log.d(TAG, "CallGuard TTS initialized successfully (ready=$ttsReady)")
            } catch (e: Exception) {
                Log.w(TAG, "TTS language setup error: ${e.message}")
                ttsReady = false
            }
        } else {
            Log.w(TAG, "TTS onInit returned error status: $status")
            ttsReady = false
        }
    }

    fun stop() {
        handler.post { dismissOverlay() }
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "TTS shutdown error: ${e.message}")
        }
        tts = null
        ttsReady = false
        Log.i(TAG, "CallGuard Engine stopped")
    }

    /**
     * Called when a call session starts.
     */
    fun onCallSessionStarted(sessionId: Long) {
        synchronized(this) {
            sessionTracker.startSession(sessionId)
            callActive = true
            voiceCloneDetected = false
            voiceCloneConfidence = 0.0f
            fraudKeywordDetected = false
            urgencyDetected = false
            latestVoiceIdVerdict = "MONITORING"
            detectedFraudList.clear()
            detectedUrgencyList.clear()
        }
        Log.i(TAG, "CallGuard: Session #$sessionId started — initialized clean state")
        evaluateAndApply()
    }

    /**
     * Called when a call session ends. Immediately releases and dismisses any overlay.
     */
    fun onCallSessionEnded(sessionId: Long) {
        synchronized(this) {
            sessionTracker.endSession(sessionId)
            callActive = false
            voiceCloneDetected = false
            voiceCloneConfidence = 0.0f
            fraudKeywordDetected = false
            urgencyDetected = false
            latestVoiceIdVerdict = "STANDBY"
            latestVoiceIdLatency = 0f
            latestRawNet2Score = 0f
            latestEcapaScore = 0f
            detectedFraudList.clear()
            detectedUrgencyList.clear()
        }
        Log.i(TAG, "CallGuard: Session #$sessionId ended — state cleared, overlay dismissed")
        evaluateAndApply()
    }

    /**
     * Backward-compatible call state updater.
     */
    fun onCallStateChanged(active: Boolean) {
        if (active) {
            if (sessionTracker.activeSessionId == 0L) {
                onCallSessionStarted(System.currentTimeMillis())
            }
        } else {
            onCallSessionEnded(sessionTracker.activeSessionId)
        }
    }

    /**
     * Process real VoiceID inference result received from FastAPI backend.
     * Enforces session validation and multi-window temporal confirmation:
     * - Stale results from expired sessions or inactive calls are discarded.
     * - A single transient synthetic score does NOT latch RED; requires 2 consecutive synthetic windows.
     * - Fails safe: UNAVAILABLE or UNCERTAIN never trigger synthetic alerts.
     */
    fun onVoiceIdResult(
        result: VoiceIdResult,
        windowNum: Int? = null,
        rms: Float? = null,
        peak: Float? = null,
        speechPresent: Boolean? = null
    ) {
        val countBefore = sessionTracker.candidateSyntheticCount
        Log.i(TAG, "VoiceID result received by CallGuard (session #${result.sessionId}): verdict=${result.verdict}, conf=${result.confidence}, rawnet2=${result.rawnet2Score}, ecapa=${result.ecapaScore}, latency=${result.latencyMs}ms, candidateSyntheticCount BEFORE=$countBefore")
        synchronized(this) {
            val accepted = sessionTracker.processVoiceIdResult(result)
            val countAfter = sessionTracker.candidateSyntheticCount
            Log.i(TAG, "CallSessionTracker processed result: accepted=$accepted, candidateSyntheticCount AFTER=$countAfter, isSyntheticConfirmed=${sessionTracker.isSyntheticConfirmed}")
            if (!accepted) {
                Log.w(TAG, "Dropping VoiceID result for session #${result.sessionId} (active=#${sessionTracker.activeSessionId}, callActive=${sessionTracker.isCallActive})")
                return
            }

            latestVoiceIdVerdict = result.verdict
            latestVoiceIdLatency = result.latencyMs
            latestRawNet2Score = result.rawnet2Score
            latestEcapaScore = result.ecapaScore

            voiceCloneDetected = sessionTracker.isSyntheticConfirmed
            voiceCloneConfidence = sessionTracker.confirmedConfidence
            Log.i(TAG, "CallGuard state updated: voiceCloneDetected=$voiceCloneDetected, voiceCloneConfidence=$voiceCloneConfidence")

            val winLabel = windowNum?.let { "WINDOW #$it" } ?: if (result.sessionId == 0L) "DEV_ASSET" else "LIVE_AUDIO"
            Log.i(TAG, "$winLabel: RMS=${rms ?: "N/A"}, peak=${peak ?: "N/A"}, speechPresent=${speechPresent ?: "N/A"}, RawNet2=${result.rawnet2Score}, ECAPA=${result.ecapaScore}, verdict=${result.verdict}, confidence=${result.confidence}, sessionId=${result.sessionId}, candidateSyntheticCount=$countAfter, confirmedSynthetic=${sessionTracker.isSyntheticConfirmed}")

            if (voiceCloneDetected) {
                Log.w(TAG, "VoiceID: Confirmed SYNTHETIC voice (windows=${sessionTracker.candidateSyntheticCount}, RawNet2=${result.rawnet2Score}, conf=${result.confidence})")
            } else if (result.verdict == "SYNTHETIC") {
                Log.i(TAG, "VoiceID: Candidate synthetic window 1/2 noted — waiting for temporal confirmation")
            }
        }
        evaluateAndApply()
    }

    /**
     * Called when a recognized UPI application enters foreground during active call.
     */
    fun onUpiAppDetectedDuringCall(pkg: String) {
        synchronized(this) {
            upiForeground = true
            currentUpiPackage = pkg
        }
        evaluateAndApply()
    }

    /**
     * Called when a non-UPI application enters foreground.
     */
    fun onNonUpiAppForegrounded() {
        synchronized(this) {
            upiForeground = false
            currentUpiPackage = null
        }
        evaluateAndApply()
    }

    /**
     * Called when keywords are detected (via deterministic demo signals or future neural model).
     */
    fun onKeywordsDetected(keywords: List<String>) {
        val (fraud, urgency) = riskEngine.categorizeKeywords(keywords)
        synchronized(this) {
            if (fraud.isNotEmpty()) {
                fraudKeywordDetected = true
                for (f in fraud) {
                    if (!detectedFraudList.contains(f)) detectedFraudList.add(f)
                }
            }
            if (urgency.isNotEmpty()) {
                urgencyDetected = true
                for (u in urgency) {
                    if (!detectedUrgencyList.contains(u)) detectedUrgencyList.add(u)
                }
            }
        }
        evaluateAndApply()
    }

    /**
     * Deterministic simulation trigger for SIH Demo Moment 3.
     */
    fun triggerDemoSimulation() {
        synchronized(this) {
            voiceCloneDetected = true
            voiceCloneConfidence = 0.96f
            fraudKeywordDetected = true
            urgencyDetected = true
        }
        evaluateAndApply()
    }

    /**
     * Clear transient keyword and voice signals to return to clean monitoring state.
     */
    fun resetKeywords() {
        synchronized(this) {
            sessionTracker.resetSyntheticState()
            fraudKeywordDetected = false
            urgencyDetected = false
            voiceCloneDetected = false
            voiceCloneConfidence = 0.0f
            detectedFraudList.clear()
            detectedUrgencyList.clear()
        }
        evaluateAndApply()
    }

    fun getLatestVoiceIdStatus(): String = latestVoiceIdVerdict
    fun getLatestVoiceIdLatency(): Float = latestVoiceIdLatency
    fun getLatestRawNet2Score(): Float = latestRawNet2Score
    fun getLatestEcapaScore(): Float = latestEcapaScore
    fun getActiveSessionId(): Long = sessionTracker.activeSessionId

    /**
     * Deterministic evaluation of multi-signal state and overlay synchronization.
     */
    fun evaluateAndApply() {
        val state: CallGuardState
        synchronized(this) {
            state = CallGuardState(
                callActive = callActive,
                upiForeground = upiForeground,
                currentPackage = currentUpiPackage,
                fraudKeywordDetected = fraudKeywordDetected,
                urgencyDetected = urgencyDetected,
                detectedFraudKeywords = detectedFraudList.toList(),
                detectedUrgencyKeywords = detectedUrgencyList.toList(),
                voiceCloneDetected = voiceCloneDetected,
                voiceCloneConfidence = voiceCloneConfidence,
            )
        }

        Log.i(TAG, "CallGuardRiskEngine.evaluate() INPUT: callActive=${state.callActive}, upiForeground=${state.upiForeground}, currentUpiPackage=${state.currentPackage}, voiceCloneDetected=${state.voiceCloneDetected}, voiceCloneConfidence=${state.voiceCloneConfidence}")
        val verdict = riskEngine.evaluate(state)
        Log.i(TAG, "CallGuardRiskEngine.evaluate() VERDICT: level=${verdict.level}, isAlertActive=${verdict.isAlertActive}, explanation='${verdict.explanation}'")

        handler.post {
            if (!verdict.isAlertActive) {
                if (currentLevel != CallGuardRiskLevel.GREEN) {
                    dismissOverlay()
                    currentLevel = CallGuardRiskLevel.GREEN
                }
                return@post
            }

            // If already displaying the identical level, update explanation without destroying the countdown
            if (overlayContainer != null && currentLevel == verdict.level) {
                explanationTextView?.text = verdict.explanation
                return@post
            }

            currentLevel = verdict.level
            renderOverlay(verdict)
        }
    }

    private fun renderOverlay(verdict: CallGuardVerdict) {
        dismissOverlay()

        val isRed = verdict.level == CallGuardRiskLevel.RED

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
        }

        val root = buildOverlayView(verdict)
        overlayContainer = root

        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach CallGuard overlay: ${e.message}")
            return
        }

        // Trigger Hindi TTS on RED alerts (fail-safe)
        if (isRed && ttsReady && tts != null) {
            try {
                tts?.speak(
                    "सावधान! संभावित नकली आवाज़ का पता चला है। पैसे ट्रांसफर न करें।",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "kavach_fraud_alert"
                )
            } catch (e: Exception) {
                Log.w(TAG, "TTS speak failed safely: ${e.message}")
            }
        }
    }

    /**
     * Builds a clean, minimal, non-cluttered bilingual overlay:
     * - Distinct English and Hindi blocks (zero Hinglish).
     * - Technical jargon (RawNet2, ECAPA, session IDs) hidden behind an expandable Details toggle.
     */
    private fun buildOverlayView(verdict: CallGuardVerdict): LinearLayout {
        val dp = context.resources.displayMetrics.density
        val isRed = verdict.level == CallGuardRiskLevel.RED

        val bgColor = if (isRed) Color.parseColor("#B71C1C") else Color.parseColor("#E65100")
        val accentStripeColor = if (isRed) Color.parseColor("#FF1744") else Color.parseColor("#FFD600")

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(bgColor)
            elevation = 16 * dp
        }

        // Left accent stripe
        val stripe = View(context).apply {
            setBackgroundColor(accentStripeColor)
            layoutParams = LinearLayout.LayoutParams((6 * dp).toInt(), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        root.addView(stripe)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Header Row: Icon + Clean Title
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val iconView = TextView(context).apply {
            text = if (isRed) "🛑" else "⚠️"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = (8 * dp).toInt()
            }
        }

        val titleView = TextView(context).apply {
            text = if (isRed) "CRITICAL SECURITY ALERT" else "CALL SAFETY WARNING"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val badge = TextView(context).apply {
            text = "KavachVoice"
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            setBackgroundColor(Color.parseColor("#33000000"))
        }

        headerRow.addView(iconView)
        headerRow.addView(titleView)
        headerRow.addView(badge)
        content.addView(headerRow)

        // Clean English Block
        val englishBody = TextView(context).apply {
            text = if (isRed) {
                "Possible cloned voice detected.\nDo not transfer money. Do not share OTP, PIN or passwords."
            } else {
                "You are on a call and a payment app is open.\nTake a moment before transferring money."
            }
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (8 * dp).toInt()
            }
        }
        content.addView(englishBody)

        // Clean Hindi Block (strictly separated, zero Hinglish)
        val hindiBody = TextView(context).apply {
            text = if (isRed) {
                "संभावित नकली आवाज़ का पता चला है।\nपैसे ट्रांसफर न करें। OTP, PIN या पासवर्ड साझा न करें।"
            } else {
                "आप कॉल पर हैं और एक भुगतान ऐप खुला है।\nपैसे ट्रांसफर करने से पहले सावधानी से जांच करें।"
            }
            setTextColor(Color.parseColor("#FFF9C4")) // Soft light yellow for clear reading
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (6 * dp).toInt()
            }
        }
        content.addView(hindiBody)

        // Hidden Technical Details section (Collapsible)
        val detailsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (6 * dp).toInt()
            }
        }

        val technicalDetails = TextView(context).apply {
            val r2 = (latestRawNet2Score * 100).toInt()
            val ec = (latestEcapaScore * 100).toInt()
            val lat = latestVoiceIdLatency.toInt()
            val sessId = sessionTracker.activeSessionId
            text = "Diagnostics: Session #$sessId | RawNet2: $r2% | ECAPA: $ec% | Latency: ${lat}ms\nExplanation: ${verdict.explanation}"
            setTextColor(Color.parseColor("#CFD8DC"))
            textSize = 10f
        }
        explanationTextView = technicalDetails
        detailsContainer.addView(technicalDetails)
        content.addView(detailsContainer)

        // Details toggle button
        val detailsToggle = TextView(context).apply {
            text = "▶ Technical Details / तकनीकी विवरण"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 10f
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
            setOnClickListener {
                if (detailsContainer.visibility == View.VISIBLE) {
                    detailsContainer.visibility = View.GONE
                    text = "▶ Technical Details / तकनीकी विवरण"
                } else {
                    detailsContainer.visibility = View.VISIBLE
                    text = "▼ Hide Details / विवरण छिपाएं"
                }
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (4 * dp).toInt()
            }
        }
        content.addView(detailsToggle)

        // Action Buttons Row
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (10 * dp).toInt()
            }
        }

        // Action 1: 📞 1930 Cyber Helpline
        val helplineBtn = TextView(context).apply {
            text = "📞 Call 1930"
            setTextColor(Color.WHITE)
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setBackgroundColor(Color.parseColor("#1B5E20")) // Forest green
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setOnClickListener {
                try {
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:1930")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(dialIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to launch 1930 helpline dialer: ${e.message}")
                }
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = (10 * dp).toInt()
            }
        }
        buttonRow.addView(helplineBtn)

        // Action 2: Dismiss Button with safety countdown on RED
        val dismissBtn = TextView(context).apply {
            textSize = 12f
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { dismissOverlay() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        if (isRed) {
            dismissBtn.isEnabled = false
            dismissBtn.alpha = 0.5f
            dismissBtn.text = "⏳ Hold: 10s"

            countdownTimer?.cancel()
            countdownTimer = object : CountDownTimer(10_000L, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val secondsLeft = (millisUntilFinished / 1000) + 1
                    dismissBtn.text = "⏳ Hold: ${secondsLeft}s"
                }

                override fun onFinish() {
                    dismissBtn.isEnabled = true
                    dismissBtn.alpha = 1.0f
                    dismissBtn.text = "✕ Dismiss / बंद करें"
                }
            }.start()
        } else {
            dismissBtn.isEnabled = true
            dismissBtn.text = "✕ Dismiss / बंद करें"
        }

        buttonRow.addView(dismissBtn)
        content.addView(buttonRow)

        root.addView(content)
        return root
    }

    private fun dismissOverlay() {
        countdownTimer?.cancel()
        countdownTimer = null
        overlayContainer?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view: ${e.message}")
            }
        }
        overlayContainer = null
        explanationTextView = null
    }

    private fun updateOverlayExplanation(explanation: String) {
        explanationTextView?.text = "सक्रिय संकेत: $explanation"
    }

    /**
     * Inspects keyword_model.tflite asset.
     * Explicitly recognizes that the 8-byte file is a placeholder and logs this honestly.
     * Prevents instantiating TensorFlow Lite Interpreter on empty bytes which would crash.
     */
    private fun inspectPlaceholderModel() {
        try {
            val afd = context.assets.openFd("keyword_model.tflite")
            val length = afd.declaredLength
            afd.close()

            if (length < 1024) {
                Log.i(
                    TAG,
                    "keyword_model.tflite is an 8-byte placeholder asset ($length bytes). " +
                            "TensorFlow Lite neural KWS inference is inactive. " +
                            "CallGuard multi-signal fusion is operating deterministically with demo simulation triggers."
                )
            } else {
                Log.i(TAG, "Valid keyword model asset detected ($length bytes).")
            }
        } catch (e: Exception) {
            Log.w(TAG, "keyword_model.tflite inspection note: ${e.message}")
        }
    }
}
