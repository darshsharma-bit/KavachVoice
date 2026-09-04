package com.kavachvoice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Layer 1 — VoiceArmor Engine (OEM HAL Blueprint).
 *
 * NOTE FOR CONSUMER BUILD:
 * In consumer Android applications, only one application can reliably record from the microphone
 * at a given sample rate without HAL collisions. Therefore, during consumer CallGuard monitoring,
 * VoiceArmorEngine is isolated/deactivated, and KeywordScanner acts as the single authoritative
 * microphone consumer.
 *
 * In the OEM HAL architecture, VoiceArmor sits directly in the audio HAL / DSP pipeline prior
 * to user-space distribution.
 */
class VoiceArmorEngine(private val context: Context) {

    private val tag = "VoiceArmor"
    private val sampleRate = 16000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_FLOAT
    )

    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var job: Job? = null
    private var uapProfile: FloatArray? = null

    fun start() {
        uapProfile = loadUapProfile()
        if (uapProfile == null) {
            Log.w(tag, "UAP profile not found in assets — VoiceArmor running in passthrough mode")
        }

        if (bufferSize <= 0) {
            Log.e(tag, "Invalid buffer size for VoiceArmor AudioRecord: $bufferSize")
            return
        }

        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_FLOAT,
                bufferSize
            )

            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(tag, "VoiceArmor AudioRecord initialization failed")
                recorder?.release()
                recorder = null
                return
            }

            player = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            recorder?.startRecording()
            player?.play()

            job = CoroutineScope(Dispatchers.Default).launch {
                val buffer = FloatArray(bufferSize / 4)
                while (isActive) {
                    val read = recorder?.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING) ?: 0
                    if (read > 0) {
                        val perturbed = applyUap(buffer, read)
                        player?.write(perturbed, 0, read, AudioTrack.WRITE_BLOCKING)
                    }
                }
            }

            Log.i(tag, "VoiceArmor started — UAP ${if (uapProfile != null) "LOADED" else "PASSTHROUGH"}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start VoiceArmor: ${e.message}", e)
            stop()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null

        try {
            player?.stop()
        } catch (_: Exception) {}
        try {
            player?.release()
        } catch (_: Exception) {}
        player = null
        Log.i(tag, "VoiceArmor stopped")
    }

    private fun applyUap(input: FloatArray, length: Int): FloatArray {
        val uap = uapProfile ?: return input
        return FloatArray(length) { i ->
            (input[i] + uap[i % uap.size]).coerceIn(-1.0f, 1.0f)
        }
    }

    private fun loadUapProfile(): FloatArray? {
        return try {
            context.assets.open("uap_profile.bin").use { stream ->
                val bytes = stream.readBytes()
                val floats = FloatArray(bytes.size / 4)
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                buf.asFloatBuffer().get(floats)
                Log.d(tag, "UAP profile loaded: ${floats.size} samples")
                floats
            }
        } catch (e: Exception) {
            Log.w(tag, "UAP profile not found: ${e.message}")
            null
        }
    }
}
