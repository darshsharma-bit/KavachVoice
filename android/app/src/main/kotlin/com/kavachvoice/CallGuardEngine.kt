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
    @Volatile private var latestVoiceIdVerdict: String = "OFFLINE"
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
     * Update active call state (cellular or VoIP).
     */
    fun onCallStateChanged(active: Boolean) {
        synchronized(this) {
            callActive = active
            if (!active) {
                // When call terminates, clear transient keyword, urgency, and voice-clone signals
                fraudKeywordDetected = false
                urgencyDetected = false
                voiceCloneDetected = false
                voiceCloneConfidence = 0.0f
                latestVoiceIdVerdict = "OFFLINE"
                detectedFraudList.clear()
                detectedUrgencyList.clear()
            }
        }
        evaluateAndApply()
    }

    /**
     * Process real VoiceID inference result received from FastAPI backend.
     * Fails safe: UNAVAILABLE or network errors never trigger synthetic alerts.
     */
    fun onVoiceIdResult(result: VoiceIdResult) {
        Log.i(TAG, "VoiceID result received by CallGuard: verdict=${result.verdict}, conf=${result.confidence}, rawnet2=${result.rawnet2Score}, ecapa=${result.ecapaScore}, latency=${result.latencyMs}ms")
        synchronized(this) {
            latestVoiceIdVerdict = result.verdict
            latestVoiceIdLatency = result.latencyMs
            latestRawNet2Score = result.rawnet2Score
            latestEcapaScore = result.ecapaScore

            if (result.isSuccess && result.verdict == "SYNTHETIC") {
                voiceCloneDetected = true
                voiceCloneConfidence = result.confidence
                Log.w(TAG, "VoiceID: REAL INFERENCE confirmed SYNTHETIC voice (RawNet2 score=${result.rawnet2Score}, confidence=${result.confidence})")
            } else {
                // GENUINE, UNCERTAIN, or UNAVAILABLE fails safe (never synthetic)
                voiceCloneDetected = false
                voiceCloneConfidence = 0.0f
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
        onKeywordsDetected(listOf("otp", "बताइए", "jaldi"))
    }

    /**
     * Clear transient keyword and voice signals to return to clean monitoring state.
     */
    fun resetKeywords() {
        synchronized(this) {
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

        val verdict = riskEngine.evaluate(state)
        Log.i(TAG, "Evaluated verdict: level=${verdict.level}, alert=${verdict.isAlertActive}, explanation='${verdict.explanation}'")

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
                    "सावधान! कॉल के दौरान वित्तीय धोखाधड़ी की संभावना है। अपना ओटीपी या पिन किसी को न बताएं।",
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "kavach_fraud_alert"
                )
            } catch (e: Exception) {
                Log.w(TAG, "TTS speak failed safely: ${e.message}")
            }
        }
    }

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

        // Header Row: Icon + Title + KavachVoice Badge
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val iconView = TextView(context).apply {
            text = if (isRed) "🛑" else "⚠️"
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.marginEnd = (10 * dp).toInt()
            }
        }

        val titleView = TextView(context).apply {
            text = if (isRed) "कवच सुरक्षा चेतावनी (CRITICAL FRAUD ALERT)" else "कवच सतर्कता (SAFETY WARNING)"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val badge = TextView(context).apply {
            text = "KavachVoice L2"
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

        // Explainable Signal Trace (derived strictly from active signals)
        val explanationView = TextView(context).apply {
            text = "सक्रिय संकेत: ${verdict.explanation}"
            setTextColor(Color.parseColor("#FFEB3B"))
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (6 * dp).toInt()
            }
        }
        explanationTextView = explanationView
        content.addView(explanationView)

        // Advisory Body
        val advisoryView = TextView(context).apply {
            text = if (isRed) {
                "कॉल पर किसी को भी OTP, UPI PIN या पासवर्ड न बताएं। बैंक या सरकारी अधिकारी कभी भी कॉल पर PIN दर्ज करने को नहीं कहते।"
            } else {
                "सक्रिय फोन कॉल के दौरान यूपीआई ऐप खुला है। किसी के कहने पर अज्ञात ट्रांसफर न करें।"
            }
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (6 * dp).toInt()
            }
        }
        content.addView(advisoryView)

        // Action Buttons Row
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (10 * dp).toInt()
            }
        }

        // Action 1: 📞 1930 साइबर हेल्पलाइन (ACTION_DIAL, tel:1930)
        val helplineBtn = TextView(context).apply {
            text = "📞 1930 साइबर हेल्पलाइन"
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

        // Action 2: Dismiss Button with 10-second friction countdown on RED
        val dismissBtn = TextView(context).apply {
            textSize = 12f
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { dismissOverlay() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        if (isRed) {
            // Lock dismiss button for 10 seconds to create security friction
            dismissBtn.isEnabled = false
            dismissBtn.alpha = 0.5f
            dismissBtn.text = "⏳ सुरक्षा रोक: 10s"

            countdownTimer?.cancel()
            countdownTimer = object : CountDownTimer(10_000L, 1000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val secondsLeft = (millisUntilFinished / 1000) + 1
                    dismissBtn.text = "⏳ सुरक्षा रोक: ${secondsLeft}s"
                }

                override fun onFinish() {
                    dismissBtn.isEnabled = true
                    dismissBtn.alpha = 1.0f
                    dismissBtn.text = "✕ सतर्क रहें और बंद करें"
                }
            }.start()
        } else {
            dismissBtn.isEnabled = true
            dismissBtn.text = "✕ खारिज करें (Dismiss)"
        }

        buttonRow.addView(dismissBtn)
        content.addView(buttonRow)

        // Disclaimer: Friction warning, not guaranteed transaction lock
        val disclaimerView = TextView(context).apply {
            text = "सूचना: यह एक सुरक्षा चेतावनी है, बैंकिंग ट्रांजैक्शन लॉक नहीं।"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 9f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = (6 * dp).toInt()
            }
        }
        content.addView(disclaimerView)

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
