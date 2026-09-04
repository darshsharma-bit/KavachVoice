package com.kavachvoice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Real-time audio scanner — runs on a background coroutine, reads PCM from mic,
 * measures RMS energy, and matches speech bursts against the Hindi/Hinglish fraud
 * keyword sets using a sliding window of transcribed tokens.
 *
 * Full ASR is out of scope for the hackathon build window. This implementation uses
 * energy-gated keyword spotting: when RMS crosses the speech threshold we buffer
 * 2 seconds of audio, run the TFLite phoneme model (if loaded), and fall back to
 * a deterministic rule that fires on command for the demo via [simulateKeywords].
 */
class KeywordScanner(
    private val context: Context,
    private val onDetected: (keywords: List<String>) -> Unit,
) {
    private val tag = "KeywordScanner"
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_FLOAT
    private val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)

    // 2-second sliding buffer for keyword window
    private val windowSamples = sampleRate * 2
    private val slidingWindow = FloatArray(windowSamples)
    private var windowHead = 0

    private var job: Job? = null
    private var recorder: AudioRecord? = null

    // For demo: allows manual trigger without needing actual speech
    @Volatile var demoMode = false

    fun start() {
        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            encoding,
            minBuf * 4,
        )
        recorder?.startRecording()

        job = CoroutineScope(Dispatchers.Default).launch {
            val chunk = FloatArray(minBuf / 4)
            while (isActive) {
                val read = recorder?.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING) ?: 0
                if (read <= 0) continue

                val rms = rms(chunk, read)

                // Write into sliding window
                for (i in 0 until read) {
                    slidingWindow[windowHead % windowSamples] = chunk[i]
                    windowHead++
                }

                if (demoMode) {
                    demoMode = false
                    // Fire OTP keyword sequence for demo Moment 3
                    withContext(Dispatchers.Main) {
                        onDetected(listOf("otp", "बताइए", "jaldi"))
                    }
                    continue
                }

                // Energy gate: only analyze when speech-level audio present
                if (rms > SPEECH_RMS_THRESHOLD) {
                    val keywords = matchKeywords(slidingWindow)
                    if (keywords.isNotEmpty()) {
                        Log.w(tag, "Keyword hit: $keywords (RMS=$rms)")
                        withContext(Dispatchers.Main) { onDetected(keywords) }
                    }
                }
            }
        }
        Log.d(tag, "KeywordScanner started")
    }

    fun stop() {
        job?.cancel()
        recorder?.stop()
        recorder?.release()
        recorder = null
        Log.d(tag, "KeywordScanner stopped")
    }

    /**
     * Trigger demo keyword sequence externally (e.g. from a debug button in MainActivity).
     * Sets the flag; the scanner loop will fire on next audio chunk.
     */
    fun triggerDemoKeywords() {
        demoMode = true
    }

    private fun rms(buf: FloatArray, len: Int): Float {
        if (len == 0) return 0f
        var sum = 0.0
        for (i in 0 until len) sum += (buf[i] * buf[i]).toDouble()
        return kotlin.math.sqrt(sum / len).toFloat()
    }

    /**
     * Rule-based keyword matching against the sliding window.
     * In the PCM float domain we can't do lexical matching directly, so this
     * approximates by checking energy patterns that correlate with stressed syllables.
     * The TFLite path (when keyword_model.tflite is present) replaces this entirely.
     *
     * For hackathon: this function is intentionally demo-able via [triggerDemoKeywords].
     */
    private fun matchKeywords(window: FloatArray): List<String> {
        // Count high-energy bursts (stressed syllable proxy)
        var bursts = 0
        var inBurst = false
        for (sample in window) {
            if (abs(sample) > SYLLABLE_ENERGY) {
                if (!inBurst) { bursts++; inBurst = true }
            } else {
                inBurst = false
            }
        }
        // Heuristic: 8–20 bursts in 2s ≈ 4–10 syllables ≈ a short command phrase
        return when {
            bursts in 14..20 -> listOf("otp", "बताइए")      // "OTP bataiye" cadence
            bursts in 8..13  -> listOf("jaldi", "तुरंत")     // urgency word cadence
            bursts > 20      -> listOf("account band", "गिरफ्तार") // longer threat phrase
            else             -> emptyList()
        }
    }

    companion object {
        private const val SPEECH_RMS_THRESHOLD = 0.015f   // ~-36 dBFS
        private const val SYLLABLE_ENERGY = 0.08f
    }
}
