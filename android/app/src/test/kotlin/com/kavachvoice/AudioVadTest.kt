package com.kavachvoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit Tests for Audio VAD and Energy Gating (Phase 4 / Section 21):
 * 21. silence rejected by gate
 * 22. low noise rejected by gate
 * 23. speech accepted by gate
 * 24. buffer bounds & rolling window invariants
 */
class AudioVadTest {

    private fun computePcm16RmsAndPeak(buffer: ShortArray, length: Int): Pair<Float, Float> {
        if (length <= 0) return Pair(0f, 0f)
        var sumSquares = 0.0
        var maxPeak = 0f

        for (i in 0 until length) {
            val normalized = buffer[i] / 32768.0f
            sumSquares += (normalized * normalized)
            val absVal = kotlin.math.abs(normalized)
            if (absVal > maxPeak) maxPeak = absVal
        }

        val rms = sqrt(sumSquares / length).toFloat()
        return Pair(rms, maxPeak)
    }

    private fun computeActiveSpeechDurationMs(window: ShortArray): Int {
        val frameSamples = KeywordScanner.FRAME_SAMPLES
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
            if (frameRms >= KeywordScanner.FRAME_ENERGY_THRESHOLD) {
                activeFrames++
            }
        }
        return activeFrames * KeywordScanner.FRAME_SIZE_MS
    }

    @Test
    fun test21_SilenceRejectedByGate() {
        val silenceBuffer = ShortArray(KeywordScanner.WINDOW_SAMPLES) { 0 }
        val (rms, peak) = computePcm16RmsAndPeak(silenceBuffer, silenceBuffer.size)
        val activeSpeechMs = computeActiveSpeechDurationMs(silenceBuffer)

        assertEquals(0.0f, rms, 0.0001f)
        assertEquals(0.0f, peak, 0.0001f)
        assertEquals(0, activeSpeechMs)

        val isSpeech = (rms >= KeywordScanner.MIN_SPEECH_RMS) &&
                (peak >= KeywordScanner.MIN_SPEECH_PEAK) &&
                (activeSpeechMs >= KeywordScanner.MIN_SPEECH_DURATION_MS)

        assertFalse("Pure silence must be rejected by speech activity gate", isSpeech)
    }

    @Test
    fun test22_LowNoiseRejectedByGate() {
        // Low ambient noise (~100 to 200 amplitude out of 32768, RMS ~ 0.004)
        val lowNoiseBuffer = ShortArray(KeywordScanner.WINDOW_SAMPLES) { i ->
            if (i % 2 == 0) 150.toShort() else (-150).toShort()
        }
        val (rms, peak) = computePcm16RmsAndPeak(lowNoiseBuffer, lowNoiseBuffer.size)
        val activeSpeechMs = computeActiveSpeechDurationMs(lowNoiseBuffer)

        assertTrue("Low noise RMS must be below MIN_SPEECH_RMS", rms < KeywordScanner.MIN_SPEECH_RMS)
        assertTrue("Low noise peak must be below MIN_SPEECH_PEAK", peak < KeywordScanner.MIN_SPEECH_PEAK)

        val isSpeech = (rms >= KeywordScanner.MIN_SPEECH_RMS) &&
                (peak >= KeywordScanner.MIN_SPEECH_PEAK) &&
                (activeSpeechMs >= KeywordScanner.MIN_SPEECH_DURATION_MS)

        assertFalse("Low noise must be rejected by speech activity gate", isSpeech)
    }

    @Test
    fun test23_SpeechAcceptedByGate() {
        // Human speech simulation: 400Hz acoustic wave with amplitude 6000 (RMS ~ 0.13, peak ~ 0.18)
        // lasting for 1.2 seconds (> 500ms required)
        val speechBuffer = ShortArray(KeywordScanner.WINDOW_SAMPLES) { i ->
            if (i < 16000 * 1.2) {
                (6000 * kotlin.math.sin(2.0 * Math.PI * 400.0 * i / 16000.0)).toInt().toShort()
            } else {
                0
            }
        }
        val (rms, peak) = computePcm16RmsAndPeak(speechBuffer, speechBuffer.size)
        val activeSpeechMs = computeActiveSpeechDurationMs(speechBuffer)

        assertTrue("Speech RMS ($rms) must exceed threshold", rms >= KeywordScanner.MIN_SPEECH_RMS)
        assertTrue("Speech peak ($peak) must exceed threshold", peak >= KeywordScanner.MIN_SPEECH_PEAK)
        assertTrue("Active speech ($activeSpeechMs ms) must exceed 500ms", activeSpeechMs >= KeywordScanner.MIN_SPEECH_DURATION_MS)

        val isSpeech = (rms >= KeywordScanner.MIN_SPEECH_RMS) &&
                (peak >= KeywordScanner.MIN_SPEECH_PEAK) &&
                (activeSpeechMs >= KeywordScanner.MIN_SPEECH_DURATION_MS)

        assertTrue("Valid acoustic speech must be accepted by gate", isSpeech)
    }

    @Test
    fun test24_BufferBoundsAndRollingWindowInvariants() {
        assertEquals("Live window must be exactly 2.0s = 32000 samples @ 16kHz", 32000, KeywordScanner.WINDOW_SAMPLES)
        assertEquals("Hop interval must be exactly 800ms = 12800 samples @ 16kHz", 12800, KeywordScanner.HOP_SAMPLES)
        assertEquals("Frame size must be 20ms = 320 samples @ 16kHz", 320, KeywordScanner.FRAME_SAMPLES)

        // Rolling buffer allocation invariant
        val rollingBuffer = ShortArray(KeywordScanner.WINDOW_SAMPLES)
        assertEquals(32000, rollingBuffer.size)
    }
}
