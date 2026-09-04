package com.kavachvoice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.io.InputStream
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSIONS_REQUEST_CODE = 1001
    }

    private var tapCount = 0
    private val tapHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private lateinit var voiceIdClient: VoiceIdClient

    private lateinit var tvProtectionStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var tvVoiceIdStatus: TextView
    private lateinit var tvVoiceIdDetails: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        voiceIdClient = VoiceIdClient(this)

        tvProtectionStatus = findViewById(R.id.tvProtectionStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        tvVoiceIdStatus = findViewById(R.id.tvVoiceIdStatus)
        tvVoiceIdDetails = findViewById(R.id.tvVoiceIdDetails)

        checkAndRequestPermissions()
        initLanguageOnboarding()

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnBatteryOpt).setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }

        // VoiceID Backend Actions
        findViewById<Button>(R.id.btnVoiceIdHealth).setOnClickListener {
            pingBackendHealth()
        }

        findViewById<Button>(R.id.btnTestLiveMicVoice).setOnClickListener {
            testLiveMicVoice()
        }

        findViewById<Button>(R.id.btnTestGenuineVoice).setOnClickListener {
            testAssetVoice("genuine_test.wav", "GENUINE")
        }

        findViewById<Button>(R.id.btnTestSyntheticVoice).setOnClickListener {
            testAssetVoice("synthetic_test.wav", "SYNTHETIC")
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
        handleIntent(intent)
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
            "reset" -> {
                svc?.getCallGuard()?.resetKeywords()
                Toast.makeText(this, "State reset", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val accessibilityEnabled = isAccessibilityServiceEnabled()
        val batteryOptDisabled = isBatteryOptimizationDisabled()

        tvAccessibilityStatus.text =
            if (accessibilityEnabled) "Accessibility Service: ACTIVE" else "Accessibility Service: OFF — tap below"

        tvBatteryStatus.text =
            if (batteryOptDisabled) "Battery Optimization: DISABLED (good)" else "Battery Optimization: ENABLED — tap below"

        tvProtectionStatus.text =
            if (accessibilityEnabled && batteryOptDisabled) "KavachVoice: PROTECTING" else "KavachVoice: NOT FULLY ACTIVE"

        val svc = KavachAccessibilityService.instance
        if (svc != null) {
            val callGuard = svc.getCallGuard()
            if (callGuard != null) {
                val status = callGuard.getLatestVoiceIdStatus()
                tvVoiceIdStatus.text = "VoiceID: $status"
                if (status != "OFFLINE" && status != "UNAVAILABLE") {
                    val r2 = callGuard.getLatestRawNet2Score()
                    val ec = callGuard.getLatestEcapaScore()
                    val lat = callGuard.getLatestVoiceIdLatency().toInt()
                    tvVoiceIdDetails.text = "RawNet2: $r2 | ECAPA: $ec | Latency: ${lat}ms (REAL ML)"
                }
            }
        }
    }

    private fun pingBackendHealth() {
        tvVoiceIdStatus.text = "VoiceID: PINGING..."
        tvVoiceIdDetails.text = "Connecting to ${voiceIdClient.getBackendUrl()}/health..."
        voiceIdClient.checkHealth { success, info ->
            if (success) {
                tvVoiceIdStatus.text = "VoiceID: BACKEND ONLINE"
                tvVoiceIdDetails.text = "Health OK: $info"
                Toast.makeText(this, "VoiceID Backend Online: $info", Toast.LENGTH_SHORT).show()
            } else {
                tvVoiceIdStatus.text = "VoiceID: UNAVAILABLE"
                tvVoiceIdDetails.text = "Failed: $info"
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
            Toast.makeText(this, "Accumulating audio... speak into microphone for 4 seconds", Toast.LENGTH_SHORT).show()
            tvVoiceIdStatus.text = "VoiceID: ACCUMULATING MIC AUDIO..."
            return
        }

        tvVoiceIdStatus.text = "VoiceID: ANALYZING LIVE MIC..."
        tvVoiceIdDetails.text = "Transmitting 64,000 PCM16 samples (4.0s) to RawNet2..."
        Log.i(TAG, "VoiceID: REAL inference started for live microphone window (${window.size} samples)")

        voiceIdClient.analyzeAudioAsync(window) { result ->
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

        voiceIdClient.analyzeWavBytesAsync(bytes) { result ->
            displayVoiceResult(result, "$expectedLabel ($assetFilename)")
            val svc = KavachAccessibilityService.instance
            svc?.getCallGuard()?.onVoiceIdResult(result)
        }
    }

    private fun displayVoiceResult(result: VoiceIdResult, source: String) {
        if (result.isSuccess) {
            val verdict = result.verdict
            val lat = result.latencyMs.toInt()
            tvVoiceIdStatus.text = "VoiceID: $verdict VOICE"
            tvVoiceIdDetails.text = "Source: $source | RawNet2: ${result.rawnet2Score} | ECAPA: ${result.ecapaScore} | Lat: ${lat}ms (REAL ML)"
            Toast.makeText(this, "REAL ML Verdict: $verdict (RawNet2: ${result.rawnet2Score}) in ${lat}ms", Toast.LENGTH_LONG).show()
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
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}
