package com.kavachvoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.InputStream
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSIONS_REQUEST_CODE = 1001
        private const val PREFS_LANG = "pref_selected_lang"
    }

    private var tapCount = 0
    private val tapHandler = Handler(Looper.getMainLooper())
    private val uiRefreshHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private lateinit var voiceIdClient: VoiceIdClient

    // Consumer UI Elements
    private lateinit var tvProtectionStatus: TextView
    private lateinit var tvHeroIcon: TextView
    private lateinit var tvHeroExplanation: TextView
    private lateinit var tvCallMonitoringStatus: TextView
    private lateinit var tvVoiceIdStatusIndicator: TextView
    private lateinit var tvMicStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnLangEn: TextView
    private lateinit var btnLangHi: TextView

    // Collapsible Diagnostics Elements
    private lateinit var btnToggleDiagnostics: TextView
    private lateinit var layoutDiagnostics: LinearLayout
    private lateinit var tvVoiceIdStatus: TextView
    private lateinit var tvVoiceIdDetails: TextView
    private lateinit var tvDiagnosticsMetrics: TextView

    private var isHindi = false
    private var backendOnline = false
    private var lastBackendInfo = ""

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            uiRefreshHandler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        voiceIdClient = VoiceIdClient(this)

        val prefs = getSharedPreferences("kavach_prefs", MODE_PRIVATE)
        isHindi = prefs.getString(PREFS_LANG, "en") == "hi"

        // Initialize Consumer Views
        tvProtectionStatus = findViewById(R.id.tvProtectionStatus)
        tvHeroIcon = findViewById(R.id.tvHeroIcon)
        tvHeroExplanation = findViewById(R.id.tvHeroExplanation)
        tvCallMonitoringStatus = findViewById(R.id.tvCallMonitoringStatus)
        tvVoiceIdStatusIndicator = findViewById(R.id.tvVoiceIdStatusIndicator)
        tvMicStatus = findViewById(R.id.tvMicStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        btnLangEn = findViewById(R.id.btnLangEn)
        btnLangHi = findViewById(R.id.btnLangHi)

        // Initialize Collapsible Diagnostics Views
        btnToggleDiagnostics = findViewById(R.id.btnToggleDiagnostics)
        layoutDiagnostics = findViewById(R.id.layoutDiagnostics)
        tvVoiceIdStatus = findViewById(R.id.tvVoiceIdStatus)
        tvVoiceIdDetails = findViewById(R.id.tvVoiceIdDetails)
        tvDiagnosticsMetrics = findViewById(R.id.tvDiagnosticsMetrics)

        checkAndRequestPermissions()
        initLanguageOnboarding()

        // Setup Language Switching
        btnLangEn.setOnClickListener { setLanguage(false) }
        btnLangHi.setOnClickListener { setLanguage(true) }

        // Setup Collapsible Diagnostics Toggle
        btnToggleDiagnostics.setOnClickListener {
            if (layoutDiagnostics.visibility == View.VISIBLE) {
                layoutDiagnostics.visibility = View.GONE
                btnToggleDiagnostics.text = "🛠️ Developer & Demo Diagnostics  [▼]"
            } else {
                layoutDiagnostics.visibility = View.VISIBLE
                btnToggleDiagnostics.text = "🛠️ Developer & Demo Diagnostics  [▲]"
            }
        }

        // Essential Permission Actions
        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnBatteryOpt).setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }

        // Diagnostics Actions
        findViewById<Button>(R.id.btnVoiceIdHealth).setOnClickListener { pingBackendHealth() }
        findViewById<Button>(R.id.btnTestLiveMicVoice).setOnClickListener { testLiveMicVoice() }
        findViewById<Button>(R.id.btnTestGenuineVoice).setOnClickListener { testAssetVoice("genuine_test.wav", "GENUINE") }
        findViewById<Button>(R.id.btnTestSyntheticVoice).setOnClickListener { testAssetVoice("synthetic_test.wav", "SYNTHETIC") }

        // SIH Demo Moment 3 Triggers
        findViewById<Button>(R.id.btnDemoClonedVoice).setOnClickListener {
            val svc = KavachAccessibilityService.instance
            if (svc != null) {
                svc.getCallGuard()?.triggerDemoSimulation()
                Toast.makeText(this, "SIH Demo: Moment 3 Cloned Voice Simulated", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.btnDemoFraudKeywords).setOnClickListener {
            val svc = KavachAccessibilityService.instance
            svc?.getCallGuard()?.resetKeywords()
            svc?.getKeywordScanner()?.triggerDemoFraudKeywords()
            Toast.makeText(this, "DEMO: Deterministic fraud keywords signaled", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnDemoUrgencyKeywords).setOnClickListener {
            val svc = KavachAccessibilityService.instance
            svc?.getCallGuard()?.resetKeywords()
            svc?.getKeywordScanner()?.triggerDemoUrgencyKeywords()
            Toast.makeText(this, "DEMO: Deterministic urgency keywords signaled", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnDemoReset).setOnClickListener {
            val svc = KavachAccessibilityService.instance
            svc?.getCallGuard()?.resetKeywords()
            Toast.makeText(this, "CallGuard State Reset", Toast.LENGTH_SHORT).show()
            updateStatus()
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

        updateLanguageUI()
        updateStatus()
        handleIntent(intent)
    }

    private fun setLanguage(hindi: Boolean) {
        isHindi = hindi
        val prefs = getSharedPreferences("kavach_prefs", MODE_PRIVATE)
        prefs.edit().putString(PREFS_LANG, if (hindi) "hi" else "en").apply()
        updateLanguageUI()
        updateStatus()
    }

    private fun updateLanguageUI() {
        if (isHindi) {
            btnLangHi.setBackgroundColor(Color.WHITE)
            btnLangHi.setTextColor(Color.parseColor("#0F172A"))
            btnLangEn.setBackgroundColor(Color.TRANSPARENT)
            btnLangEn.setTextColor(Color.parseColor("#64748B"))

            findViewById<TextView>(R.id.tvAppTitle).text = "कवच (KAVACH)"
            findViewById<TextView>(R.id.tvAppSubtitle).text = "आवाज़ सुरक्षा"
            findViewById<TextView>(R.id.tvSectionProtection).text = "सिस्टम स्थिति"
            findViewById<TextView>(R.id.tvLabelCallMonitoring).text = "कॉल सुरक्षा"
            findViewById<TextView>(R.id.tvLabelVoiceIdStatus).text = "वॉइस आईडी"
            findViewById<TextView>(R.id.tvLabelMic).text = "माइक्रोफोन"
        } else {
            btnLangEn.setBackgroundColor(Color.WHITE)
            btnLangEn.setTextColor(Color.parseColor("#0F172A"))
            btnLangHi.setBackgroundColor(Color.TRANSPARENT)
            btnLangHi.setTextColor(Color.parseColor("#64748B"))

            findViewById<TextView>(R.id.tvAppTitle).text = "KAVACH"
            findViewById<TextView>(R.id.tvAppSubtitle).text = "Voice Safety"
            findViewById<TextView>(R.id.tvSectionProtection).text = "SYSTEM STATUS"
            findViewById<TextView>(R.id.tvLabelCallMonitoring).text = "Call Protection"
            findViewById<TextView>(R.id.tvLabelVoiceIdStatus).text = "VoiceID"
            findViewById<TextView>(R.id.tvLabelMic).text = "Microphone"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // Handle Backend URL Configuration
        val backendUrl = intent.getStringExtra("set_backend")
        if (!backendUrl.isNullOrBlank()) {
            voiceIdClient.setBackendUrl(backendUrl)
            Toast.makeText(this, "Backend URL set: $backendUrl", Toast.LENGTH_SHORT).show()
        }

        // Handle Real VoiceID Test Triggers
        val testVoice = intent.getStringExtra("test_voice")
        when (testVoice) {
            "genuine" -> testAssetVoice("genuine_test.wav", "GENUINE")
            "synthetic" -> testAssetVoice("synthetic_test.wav", "SYNTHETIC")
            "mic" -> testLiveMicVoice()
            "health" -> pingBackendHealth()
        }

        // Handle Deterministic DEMO Keyword Triggers
        val demoMode = intent.getStringExtra("demo_mode")
        val svc = KavachAccessibilityService.instance
        when (demoMode) {
            "fraud" -> {
                svc?.getCallGuard()?.resetKeywords()
                svc?.getKeywordScanner()?.triggerDemoFraudKeywords()
                Toast.makeText(this, "DEMO: Deterministic fraud keywords signaled", Toast.LENGTH_SHORT).show()
            }
            "urgency" -> {
                svc?.getCallGuard()?.resetKeywords()
                svc?.getKeywordScanner()?.triggerDemoUrgencyKeywords()
                Toast.makeText(this, "DEMO: Deterministic urgency keywords signaled", Toast.LENGTH_SHORT).show()
            }
            "all" -> {
                svc?.getCallGuard()?.resetKeywords()
                svc?.getKeywordScanner()?.triggerDemoAllKeywords()
                Toast.makeText(this, "DEMO: Combined fraud+urgency keywords signaled", Toast.LENGTH_SHORT).show()
            }
            "clone" -> {
                svc?.getCallGuard()?.triggerDemoSimulation()
                Toast.makeText(this, "DEMO: Cloned Voice Alert Triggered", Toast.LENGTH_SHORT).show()
            }
            "reset" -> {
                svc?.getCallGuard()?.resetKeywords()
                Toast.makeText(this, "State reset", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        uiRefreshHandler.post(refreshRunnable)
        voiceIdClient.checkHealth { success, info ->
            backendOnline = success
            lastBackendInfo = info
            updateStatus()
        }
    }

    override fun onPause() {
        super.onPause()
        uiRefreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun updateStatus() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val batteryOptDisabled = isBatteryOptimizationDisabled()

        tvAccessibilityStatus.text = if (accessibilityEnabled) {
            if (isHindi) "एक्सेसिबिलिटी सेवा: सक्रिय" else "Accessibility Service: ACTIVE"
        } else {
            if (isHindi) "एक्सेसिबिलिटी सेवा: बंद — नीचे टैप करें" else "Accessibility Service: OFF — tap below"
        }

        tvBatteryStatus.text = if (batteryOptDisabled) {
            if (isHindi) "बैटरी ऑप्टिमाइज़ेशन: अप्रतिबंधित" else "Battery Optimization: DISABLED"
        } else {
            if (isHindi) "बैटरी ऑप्टिमाइज़ेशन: सीमित — नीचे टैप करें" else "Battery Optimization: ENABLED — tap below"
        }

        val svc = KavachAccessibilityService.instance
        val isCallActive = svc?.isCallActive() ?: false
        val isMicActive = svc?.isMicrophoneCapturing() ?: false
        val sessionId = svc?.getCurrentCallSessionId() ?: 0L

        if (isCallActive) {
            tvHeroIcon.text = "●"
            tvHeroIcon.setTextColor(Color.parseColor("#EA580C"))
            tvProtectionStatus.text = if (isHindi) "सुरक्षा सक्रिय" else "Protection Active"
            tvProtectionStatus.setTextColor(Color.parseColor("#C2410C"))
            tvHeroExplanation.text = if (isHindi) {
                "कॉल का पता चला है। आवाज़ सुरक्षा सक्रिय है।"
            } else {
                "Call detected. Real-time protection active."
            }

            tvCallMonitoringStatus.text = if (isHindi) "सक्रिय (सत्र #$sessionId)" else "ON (Session #$sessionId)"
            tvCallMonitoringStatus.setTextColor(Color.parseColor("#EA580C"))

            tvVoiceIdStatusIndicator.text = "ANALYZING"
            tvVoiceIdStatusIndicator.setTextColor(Color.parseColor("#EA580C"))

            tvMicStatus.text = "ACTIVE DURING CALL"
            tvMicStatus.setTextColor(Color.parseColor("#EA580C"))
        } else {
            tvHeroIcon.text = "●"
            tvHeroIcon.setTextColor(Color.parseColor("#16A34A"))
            tvProtectionStatus.text = if (isHindi) "सुरक्षा सक्रिय" else "Protection Active"
            tvProtectionStatus.setTextColor(Color.parseColor("#166534"))
            tvHeroExplanation.text = if (isHindi) {
                "आप सुरक्षित हैं।"
            } else {
                "You're protected."
            }

            tvCallMonitoringStatus.text = if (isHindi) "चालू" else "ON"
            tvCallMonitoringStatus.setTextColor(Color.parseColor("#16A34A"))

            tvVoiceIdStatusIndicator.text = if (backendOnline) "READY" else "STANDBY"
            tvVoiceIdStatusIndicator.setTextColor(if (backendOnline) Color.parseColor("#16A34A") else Color.parseColor("#64748B"))

            tvMicStatus.text = "OFF"
            tvMicStatus.setTextColor(Color.parseColor("#16A34A"))
        }

        if (svc != null) {
            val callGuard = svc.getCallGuard()
            if (callGuard != null) {
                val status = callGuard.getLatestVoiceIdStatus()
                val r2 = (callGuard.getLatestRawNet2Score() * 100).toInt()
                val ec = (callGuard.getLatestEcapaScore() * 100).toInt()
                val lat = callGuard.getLatestVoiceIdLatency().toInt()
                val r2Str = if (r2 > 0) "$r2%" else "--"
                val ecStr = if (ec > 0) "$ec%" else "--"
                val latStr = if (lat > 0) "${lat}ms" else "--"
                val sessStr = if (sessionId > 0L) "#$sessionId" else "--"
                tvDiagnosticsMetrics.text = "RawNet2: $r2Str | ECAPA: $ecStr\nLatency: $latStr | Session ID: $sessStr"

                if (isCallActive) {
                    tvVoiceIdStatus.text = "VoiceID Inference: $status (Active Call)"
                    tvVoiceIdStatus.setTextColor(Color.parseColor("#EA580C"))
                    if (lat > 0) {
                        tvVoiceIdDetails.text = "RawNet2: $r2% | ECAPA: $ec% | Latency: ${lat}ms (REAL ML)"
                    } else {
                        tvVoiceIdDetails.text = "Sampling microphone audio (2.0s rolling window, 800ms hop)..."
                    }
                } else {
                    if (backendOnline) {
                        tvVoiceIdStatus.text = "VoiceID: STANDBY (Server ONLINE)"
                        tvVoiceIdStatus.setTextColor(Color.parseColor("#16A34A"))
                        tvVoiceIdDetails.text = "Connected: ${voiceIdClient.getBackendUrl()} (RawNet2 + ECAPA ready)\nInference activates automatically during phone calls"
                    } else {
                        tvVoiceIdStatus.text = "VoiceID: STANDBY"
                        tvVoiceIdStatus.setTextColor(Color.parseColor("#64748B"))
                        tvVoiceIdDetails.text = "Server: ${voiceIdClient.getBackendUrl()} — Tap 'Backend Health' to test connection"
                    }
                }
            }
        }
    }

    private fun pingBackendHealth() {
        tvVoiceIdStatus.text = "VoiceID: PINGING..."
        tvVoiceIdStatus.setTextColor(Color.parseColor("#2563EB"))
        tvVoiceIdDetails.text = "Checking server connectivity across endpoints..."
        voiceIdClient.checkHealth { success, info ->
            backendOnline = success
            lastBackendInfo = info
            if (success) {
                tvVoiceIdStatus.text = "VoiceID: BACKEND ONLINE"
                tvVoiceIdStatus.setTextColor(Color.parseColor("#16A34A"))
                tvVoiceIdDetails.text = "$info\nEndpoint: ${voiceIdClient.getBackendUrl()} (RawNet2 + ECAPA OK)"
                Toast.makeText(this, "VoiceID Backend ONLINE: $info", Toast.LENGTH_SHORT).show()
            } else {
                tvVoiceIdStatus.text = "VoiceID: UNAVAILABLE"
                tvVoiceIdStatus.setTextColor(Color.parseColor("#DC2626"))
                tvVoiceIdDetails.text = "Failed: $info\nVerify PC backend is running."
                Toast.makeText(this, "VoiceID Offline: $info", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun testLiveMicVoice() {
        val svc = KavachAccessibilityService.instance
        val scanner = svc?.getKeywordScanner()
        if (scanner == null) {
            Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_SHORT).show()
            return
        }

        val window = scanner.getLatestAudioWindow()
        if (window == null) {
            Toast.makeText(this, "Accumulating audio... speak into microphone for 2 seconds", Toast.LENGTH_SHORT).show()
            tvVoiceIdStatus.text = "VoiceID: ACCUMULATING MIC AUDIO..."
            return
        }

        tvVoiceIdStatus.text = "VoiceID: ANALYZING LIVE MIC..."
        tvVoiceIdDetails.text = "Transmitting 32,000 PCM16 samples (2.0s) to RawNet2..."
        Log.i(TAG, "VoiceID: REAL inference started for live microphone window (${window.size} samples)")

        voiceIdClient.analyzeAudioAsync(window, 0L) { result ->
            displayVoiceResult(result, "Live Microphone")
            svc.getCallGuard()?.onVoiceIdResult(result)
        }
    }

    private fun testAssetVoice(assetFilename: String, expectedLabel: String) {
        val bytes = readAssetBytes(assetFilename)
        if (bytes == null || bytes.isEmpty()) {
            Toast.makeText(this, "Asset $assetFilename not found", Toast.LENGTH_SHORT).show()
            return
        }

        tvVoiceIdStatus.text = "VoiceID: ANALYZING $expectedLabel..."
        tvVoiceIdDetails.text = "Sending ${bytes.size} bytes from $assetFilename to RawNet2..."
        Log.i(TAG, "VoiceID: REAL inference started for asset: $assetFilename ($expectedLabel)")

        voiceIdClient.analyzeWavBytesAsync(bytes, 0L) { result ->
            displayVoiceResult(result, "$expectedLabel ($assetFilename)")
            val svc = KavachAccessibilityService.instance
            svc?.getCallGuard()?.onVoiceIdResult(result)
        }
    }

    private fun displayVoiceResult(result: VoiceIdResult, source: String) {
        if (result.isSuccess) {
            val verdict = result.verdict
            val lat = result.latencyMs.toInt()
            val r2 = (result.rawnet2Score * 100).toInt()
            val ec = (result.ecapaScore * 100).toInt()
            tvVoiceIdStatus.text = "VoiceID: $verdict VOICE"
            tvVoiceIdDetails.text = "Source: $source | RawNet2: $r2% | ECAPA: $ec% | Lat: ${lat}ms (REAL ML)"
            Toast.makeText(this, "REAL ML Verdict: $verdict (RawNet2: $r2%) in ${lat}ms", Toast.LENGTH_LONG).show()
            Log.i(TAG, "VoiceID: REAL inference finished: verdict=$verdict, rawnet2=${result.rawnet2Score}, ecapa=${result.ecapaScore}, latency=${lat}ms")
        } else {
            tvVoiceIdStatus.text = "VoiceID: UNAVAILABLE"
            tvVoiceIdDetails.text = "Error: ${result.errorMessage ?: "Network failure"}"
            Toast.makeText(this, "VoiceID Backend Error: ${result.errorMessage}", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "VoiceID: Backend returned UNAVAILABLE (${result.errorMessage})")
        }
    }

    private fun readAssetBytes(filename: String): ByteArray? {
        return try {
            val isStream: InputStream = assets.open(filename)
            val bytes = isStream.readBytes()
            isStream.close()
            bytes
        } catch (e: Exception) {
            Log.e(TAG, "Error loading asset $filename: ${e.message}")
            null
        }
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
            Toast.makeText(this, "DEMO Trigger: Deterministic fraud keywords signaled (OTP request)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }
    }

    private fun initLanguageOnboarding() {
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val prefs = getSharedPreferences("kavach_prefs", MODE_PRIVATE)
            val welcomed = prefs.getBoolean("welcomed", false)
            if (!welcomed) {
                val locale = Locale.getDefault()
                val lang = locale.language
                val prompt = when (lang) {
                    "hi" -> "कवच वॉइस सुरक्षा सक्रिय है।"
                    "mr" -> "कवच व्हॉईस सुरक्षा सुरू आहे."
                    "ta" -> "கவச் வாய்ஸ் பாதுகாப்பு செயல்படுகிறது."
                    "te" -> "కవచ్ వాయిస్ భద్రత సక్రియంగా ఉంది."
                    "bn" -> "কবচ ভয়েস সুরক্ষা সক্রিয় রয়েছে।"
                    else -> "KavachVoice protection is active."
                }
                tts?.language = locale
                tts?.speak(prompt, TextToSpeech.QUEUE_FLUSH, null, "welcome_audio")
                prefs.edit().putBoolean("welcomed", true).apply()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tapHandler.removeCallbacksAndMessages(null)
        uiRefreshHandler.removeCallbacksAndMessages(null)
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}

