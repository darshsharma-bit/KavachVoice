package com.kavachvoice

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Layer 2 — CallGuard
 * Detects UPI fraud patterns: payment app during call + Hindi/Hinglish OTP/urgency keywords.
 * Alert levels: YELLOW (watch) → ORANGE (warning) → RED (block). Overlay rendered via
 * TYPE_ACCESSIBILITY_OVERLAY — no SYSTEM_ALERT_WINDOW permission required.
 */
class CallGuardEngine(
    private val context: Context,
    private val upiPackages: Set<String>,
) {
    private val tag = "CallGuard"
    private var tflite: Interpreter? = null
    private var overlayContainer: LinearLayout? = null
    private val handler = Handler(Looper.getMainLooper())
    private val wm by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }

    enum class AlertLevel(
        val bgColor: Int,
        val accentColor: Int,
        val icon: String,
        val title: String,
        val body: String,
        val autoDismissMs: Long,
    ) {
        YELLOW(
            Color.parseColor("#1A1200"),
            Color.parseColor("#FFC107"),
            "⚠",
            "CAUTION — Possible Fraud",
            "Suspicious call pattern. Stay alert — do not share personal details.",
            8_000L,
        ),
        ORANGE(
            Color.parseColor("#1A0800"),
            Color.parseColor("#FF6D00"),
            "🚨",
            "WARNING — Likely Fraud",
            "Payment app opened during call. Do NOT transfer money or share account details.",
            0L,
        ),
        RED(
            Color.parseColor("#1A0000"),
            Color.parseColor("#D50000"),
            "🛑",
            "STOP — Do NOT Share OTP",
            "OTP request detected. No legitimate caller ever asks for this. Hang up now.",
            0L,
        ),
    }

    fun start() {
        loadKeywordModel()
        Log.d(tag, "CallGuard started — model ${if (tflite != null) "LOADED" else "rule-based fallback"}")
    }

    fun stop() {
        dismissOverlay()
        tflite?.close()
        Log.d(tag, "CallGuard stopped")
    }

    fun onUpiAppDetectedDuringCall(pkg: String) {
        Log.w(tag, "UPI app during call: $pkg")
        showOverlay(AlertLevel.ORANGE)
    }

    fun onKeywordsDetected(keywords: List<String>) {
        val level = when {
            keywords.any { it in RED_KEYWORDS }    -> AlertLevel.RED
            keywords.any { it in ORANGE_KEYWORDS } -> AlertLevel.ORANGE
            else                                   -> AlertLevel.YELLOW
        }
        Log.w(tag, "Keywords=$keywords → $level")
        showOverlay(level)
    }

    private fun showOverlay(level: AlertLevel) {
        handler.post {
            dismissOverlay()

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP }

            val root = buildOverlayView(level)
            overlayContainer = root
            wm.addView(root, params)

            if (level.autoDismissMs > 0) {
                handler.postDelayed({ dismissOverlay() }, level.autoDismissMs)
            }
        }
    }

    private fun buildOverlayView(level: AlertLevel): LinearLayout {
        val dp = context.resources.displayMetrics.density

        // Outer row: left accent stripe | content column
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(level.bgColor)
        }

        // 4dp coloured left stripe
        val stripe = android.view.View(context).apply {
            setBackgroundColor(level.accentColor)
            layoutParams = LinearLayout.LayoutParams(
                (4 * dp).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Title row: icon + title + "KavachVoice" right-aligned badge
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val iconView = TextView(context).apply {
            text = level.icon
            textSize = 19f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.marginEnd = (8 * dp).toInt() }
        }

        val titleView = TextView(context).apply {
            text = level.title
            setTextColor(level.accentColor)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val badge = TextView(context).apply {
            text = "KavachVoice"
            setTextColor(Color.parseColor("#88FFFFFF"))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        titleRow.addView(iconView)
        titleRow.addView(titleView)
        titleRow.addView(badge)

        val bodyView = TextView(context).apply {
            text = level.body
            setTextColor(Color.parseColor("#CCFFFFFF"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.topMargin = (5 * dp).toInt() }
        }

        content.addView(titleRow)
        content.addView(bodyView)

        root.addView(stripe)
        root.addView(content)
        return root
    }

    private fun dismissOverlay() {
        overlayContainer?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        overlayContainer = null
    }

    private fun loadKeywordModel() {
        try {
            val afd = context.assets.openFd("keyword_model.tflite")
            val stream = FileInputStream(afd.fileDescriptor)
            val buffer: MappedByteBuffer = stream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength,
            )
            tflite = Interpreter(buffer)
        } catch (e: Exception) {
            Log.w(tag, "Keyword model not found — rule-based fallback active: ${e.message}")
        }
    }

    companion object {
        val RED_KEYWORDS = setOf(
            "otp", "ओटीपी", "pin", "पिन", "password", "पासवर्ड",
            "share karo", "batao", "बताइए", "बताओ",
        )
        val ORANGE_KEYWORDS = setOf(
            "account band", "अकाउंट बंद", "arrest", "गिरफ्तार",
            "abhi", "अभी", "turant", "तुरंत", "jaldi", "जल्दी",
            "fir", "police", "cyber crime", "refund",
        )
        val YELLOW_KEYWORDS = setOf(
            "helpline", "customer care", "rbi", "bank officer",
            "verify", "सत्यापित", "kyc", "केवाईसी",
        )
    }
}
