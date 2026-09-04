package com.kavachvoice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Authoritative Layer 2 Audio Capture & Keyword Scanning Module.
 *
 * Architecture Constraints (Ratified for API 26+ Compatibility):
 * 1. Single authoritative microphone capture path: ENCODING_PCM_16BIT, 16000 Hz, MONO.
 *    PCM_FLOAT is avoided at the hardware level due to HAL incompatibilities on older Android 8.0/8.1 chipsets.
 *    Short PCM16 samples are converted to in-memory normalized floats [-1.0f, 1.0f] only where needed.
 * 2. Honest Keyword Spotting: The legacy amplitude-burst heuristic has been completely eliminated.
 *    No noise, clapping, or tapping can falsely trigger OTP / urgency alerts.
 *    The bundled keyword_model.tflite is an 8-byte placeholder and is explicitly recognized as such.
 * 3. Deterministic SIH Demo Triggers: Provides reliable, labeled simulation events for demo evaluation
 *    without faking speech recognition capabilities.
 */
class KeywordScanner(
    private val context: Context,
    private val onDetected: (keywords: List<String>) -> Unit,
) {
    private val tag = "KeywordScanner"
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    private var recorder: AudioRecord? = null
    private var job: Job? = null
    private val isRecording = AtomicBoolean(false)
    private val lock = Any()

    // Calculated buffer size for 16kHz 16-bit mono
    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
    private val bufferSize = if (minBufferSize > 0) minBufferSize * 2 else 4096

    // Bounded rolling buffer for VoiceID RawNet2 (2.0 seconds = 32,000 samples @ 16kHz mono, 800ms hop)
    companion object {
        const val SAMPLE_RATE = 16000
        const val LIVE_WINDOW_MS = 2000 // 2.0s window
        const val LIVE_HOP_MS = 800 // 800ms hop interval (~1.25 Hz dispatch)
        const val WINDOW_SAMPLES = (SAMPLE_RATE * LIVE_WINDOW_MS) / 1000 // 32,000 samples
        const val HOP_SAMPLES = (SAMPLE_RATE * LIVE_HOP_MS) / 1000 // 12,800 samples

        // Frame-level VAD parameters
        const val FRAME_SIZE_MS = 20 // 20ms frame
        const val FRAME_SAMPLES = (SAMPLE_RATE * FRAME_SIZE_MS) / 1000 // 320 samples
        const val FRAME_ENERGY_THRESHOLD = 0.012f // frame noise floor (~-38 dBFS)

        // Window-level speech activity gate (Phase 4)
        const val MIN_SPEECH_RMS = 0.018f // minimum overall RMS energy
        const val MIN_SPEECH_PEAK = 0.040f // minimum peak amplitude
        const val MIN_SPEECH_DURATION_MS = 500 // minimum active speech duration within 2.0s window
    }

    private val rollingBuffer = ShortArray(WINDOW_SAMPLES)
    private var writeHead = 0
    @Volatile private var totalSamplesRecorded = 0L
    private val bufferLock = Any()

    // Analysis worker job for periodic VoiceID window emission
    private var analysisJob: Job? = null
    var onAudioWindowAvailable: ((ShortArray) -> Unit)? = null
    var onAudioWindowAvailableWithStats: ((ShortArray, Int, Float, Float, Boolean, Int, Int) -> Unit)? = null

    // Speech presence metrics (for diagnostic VAD, NOT lexical classification)
    @Volatile var currentRms: Float = 0f
    @Volatile var currentPeak: Float = 0f
    @Volatile var isSpeechPresent: Boolean = false
    @Volatile var windowCount: Int = 0

    fun start() {
        synchronized(lock) {
            if (isRecording.get()) {
                Log.d(tag, "KeywordScanner is already running")
                return
            }

            if (minBufferSize <= 0) {
                Log.e(tag, "Invalid AudioRecord buffer configuration: minBufferSize=$minBufferSize")
                return
            }

            try {
                val newRecorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioEncoding,
                    bufferSize
                )

                if (newRecorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(tag, "AudioRecord initialization failed (state != STATE_INITIALIZED)")
                    newRecorder.release()
                    return
                }

                newRecorder.startRecording()
                if (newRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.e(tag, "AudioRecord failed to enter RECORDING state")
                    newRecorder.release()
                    return
                }

                recorder = newRecorder
                isRecording.set(true)
                Log.i(tag, "Authoritative microphone capture started (16kHz, Mono, PCM-16, API 26+ safe)")
            } catch (e: SecurityException) {
                Log.e(tag, "Missing RECORD_AUDIO permission: ${e.message}")
                return
            } catch (e: Exception) {
                Log.e(tag, "Exception during AudioRecord startup: ${e.message}", e)
                return
            }

            // AudioRecord Capture Loop (Zero network or disk I/O, non-blocking)
            job = CoroutineScope(Dispatchers.Default).launch {
                val shortBuffer = ShortArray(bufferSize / 2)

                while (isActive && isRecording.get()) {
                    val currentRec = recorder ?: break
                    val readSamples = currentRec.read(shortBuffer, 0, shortBuffer.size, AudioRecord.READ_BLOCKING)

                    if (readSamples <= 0) {
                        when (readSamples) {
                            AudioRecord.ERROR_INVALID_OPERATION ->
                                Log.w(tag, "AudioRecord read returned ERROR_INVALID_OPERATION")
                            AudioRecord.ERROR_BAD_VALUE ->
                                Log.w(tag, "AudioRecord read returned ERROR_BAD_VALUE")
                            AudioRecord.ERROR_DEAD_OBJECT -> {
                                Log.e(tag, "AudioRecord returned ERROR_DEAD_OBJECT — stopping recording")
                                break
                            }
                        }
                        delay(10)
                        continue
                    }

                    // Convert PCM16 to float in memory only for RMS / VAD observation
                    val (rms, peak) = computePcm16RmsAndPeak(shortBuffer, readSamples)
                    currentRms = rms
                    currentPeak = peak
                    isSpeechPresent = rms > MIN_SPEECH_RMS

                    // Write into bounded circular rolling buffer for VoiceID (32,000 samples = 2.0s)
                    synchronized(bufferLock) {
                        for (i in 0 until readSamples) {
                            rollingBuffer[writeHead] = shortBuffer[i]
                            writeHead = (writeHead + 1) % WINDOW_SAMPLES
                        }
                        totalSamplesRecorded += readSamples
                    }
                }
            }

            // Analysis Worker: Periodically dispatches 2.0-second audio windows every 800ms to VoiceIdClient
            // Robust lightweight frame-based VAD (Phase 4): only emits when sufficient speech is present
            analysisJob = CoroutineScope(Dispatchers.Default).launch {
                windowCount = 0
                // Initial accumulation delay for first 2.0s window
                delay(LIVE_WINDOW_MS.toLong())
                while (isActive && isRecording.get()) {
                    if (totalSamplesRecorded >= WINDOW_SAMPLES) {
                        val window = getLatestAudioWindow()
                        if (window != null) {
                            val (rms, peak) = computePcm16RmsAndPeak(window, window.size)
                            currentRms = rms
                            currentPeak = peak

                            // Lightweight frame-based speech activity detection (Phase 4)
                            val activeSpeechMs = computeActiveSpeechDurationMs(window)
                            val isSpeech = (rms >= MIN_SPEECH_RMS) &&
                                    (peak >= MIN_SPEECH_PEAK) &&
                                    (activeSpeechMs >= MIN_SPEECH_DURATION_MS)
                            isSpeechPresent = isSpeech

                            windowCount++
                            val winNum = windowCount
                            val stateStr = when (recorder?.recordingState) {
                                AudioRecord.RECORDSTATE_RECORDING -> "RECORDSTATE_RECORDING"
                                AudioRecord.RECORDSTATE_STOPPED -> "RECORDSTATE_STOPPED"
                                else -> "UNKNOWN"
                            }

                            if (!isSpeech) {
                                Log.d(tag, "VAD Gate: Window #$winNum skipped — below speech threshold (RMS=$rms, peak=$peak, activeSpeechMs=$activeSpeechMs < $MIN_SPEECH_DURATION_MS)")
                            } else {
                                Log.i(tag, "AudioRecord Capture: windowNumber=$winNum, state=$stateStr, samplesRecorded=$totalSamplesRecorded, RMS=$rms, peak=$peak, speechPresent=true, activeSpeechMs=$activeSpeechMs")
                                onAudioWindowAvailable?.invoke(window)
                                onAudioWindowAvailableWithStats?.invoke(window, winNum, rms, peak, true, LIVE_WINDOW_MS, window.size)
                            }
                        }
                    }
                    delay(LIVE_HOP_MS.toLong()) // Emit windows with ~1.2s overlap every 800ms
                }
            }
        }
    }

    /**
     * Retrieve the latest 4.0-second window (64,000 samples) in chronological order.
     */
    fun getLatestAudioWindow(): ShortArray? {
        synchronized(bufferLock) {
            if (totalSamplesRecorded < WINDOW_SAMPLES) return null
            val window = ShortArray(WINDOW_SAMPLES)
            val start = writeHead
            for (i in 0 until WINDOW_SAMPLES) {
                window[i] = rollingBuffer[(start + i) % WINDOW_SAMPLES]
            }
            return window
        }
    }

    fun getTotalSamplesRecorded(): Long = totalSamplesRecorded
    fun isRecordingActive(): Boolean = isRecording.get()

    fun stop() {
        synchronized(lock) {
            isRecording.set(false)
            job?.cancel()
            job = null
            analysisJob?.cancel()
            analysisJob = null

            try {
                recorder?.let {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        it.stop()
                    }
                    it.release()
                }
            } catch (e: Exception) {
                Log.w(tag, "Error during AudioRecord stop/release: ${e.message}")
            } finally {
                recorder = null
                isSpeechPresent = false
                currentRms = 0f
                synchronized(bufferLock) {
                    rollingBuffer.fill(0)
                    writeHead = 0
                    totalSamplesRecorded = 0L
                }
                Log.i(tag, "KeywordScanner stopped: AudioRecord released and rolling buffer zeroed")
            }
        }
    }

    /**
     * Backward-compatible trigger for MainActivity 3x-tap demo.
     */
    fun triggerDemoKeywords() {
        triggerDemoFraudKeywords()
    }

    /**
     * Explicit deterministic DEMO/SIMULATION trigger for Fraud Keywords (SIH Demo Moment 3).
     * Dispatches explicit fraud tokens without hallucinating ML capability.
     */
    fun triggerDemoFraudKeywords(keywords: List<String> = listOf("otp", "बताइए")) {
        Log.i(tag, "Deterministic DEMO fraud keyword signal triggered: $keywords")
        CoroutineScope(Dispatchers.Main).launch {
            onDetected(keywords)
        }
    }

    /**
     * Explicit deterministic DEMO/SIMULATION trigger for Urgency Keywords.
     */
    fun triggerDemoUrgencyKeywords(keywords: List<String> = listOf("jaldi", "तुरंत")) {
        Log.i(tag, "Deterministic DEMO urgency keyword signal triggered: $keywords")
        CoroutineScope(Dispatchers.Main).launch {
            onDetected(keywords)
        }
    }

    /**
     * Explicit deterministic DEMO/SIMULATION trigger for combined Fraud + Urgency Keywords.
     */
    fun triggerDemoAllKeywords() {
        Log.i(tag, "Deterministic DEMO combined fraud+urgency keyword signal triggered")
        CoroutineScope(Dispatchers.Main).launch {
            onDetected(listOf("otp", "बताइए", "jaldi", "तुरंत"))
        }
    }

    /**
     * Frame-based Voice Activity Detection (VAD).
     * Partitions window into 20ms frames and evaluates energy above the adaptive noise floor.
     * Returns total active speech duration in milliseconds.
     */
    private fun computeActiveSpeechDurationMs(window: ShortArray): Int {
        val frameSamples = FRAME_SAMPLES
        val numFrames = window.size / frameSamples
        if (numFrames <= 0) return 0
        var activeFrames = 0

        for (f in 0 until numFrames) {
            val offset = f * frameSamples
            var sumSquares = 0.0
            for (i in 0 until frameSamples) {
                val s = window[offset + i] / 32768.0f
                sumSquares += (s * s)
            }
            val frameRms = sqrt(sumSquares / frameSamples).toFloat()
            if (frameRms >= FRAME_ENERGY_THRESHOLD) {
                activeFrames++
            }
        }
        return activeFrames * FRAME_SIZE_MS
    }

    /**
     * Compute Root Mean Square (RMS) energy and peak amplitude directly from PCM16 samples normalized to [0.0, 1.0].
     */
    private fun computePcm16RmsAndPeak(buffer: ShortArray, length: Int): Pair<Float, Float> {
        if (length <= 0) return Pair(0f, 0f)
        var sumSquares = 0.0
        var maxPeak = 0f
        for (i in 0 until length) {
            val normalized = kotlin.math.abs(buffer[i] / 32768.0f)
            if (normalized > maxPeak) maxPeak = normalized
            sumSquares += (normalized * normalized)
        }
        val rms = sqrt(sumSquares / length).toFloat()
        return Pair(rms, maxPeak)
    }

    /**
     * Compute Root Mean Square (RMS) energy directly from PCM16 samples normalized to [-1.0, 1.0].
     */
    private fun computePcm16Rms(buffer: ShortArray, length: Int): Float {
        return computePcm16RmsAndPeak(buffer, length).first
    }
}
