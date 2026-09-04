package com.kavachvoice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*

/**
 * Layer 1 — VoiceArmor
 * Captures mic audio, applies pre-computed UAP perturbation, and plays back
 * the perturbed audio. This poisons the source before any cloning can occur.
 *
 * UAP profile is loaded from assets/uap_profile.bin (float32, same length as buffer).
 * Applied as: output[i] = input[i] + uap[i % uapLength], clamped to [-1.0, 1.0].
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

        recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufferSize
        )

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

        Log.d(tag, "VoiceArmor started — UAP ${if (uapProfile != null) "LOADED" else "PASSTHROUGH"}")
    }

    fun stop() {
        job?.cancel()
        recorder?.stop()
        recorder?.release()
        player?.stop()
        player?.release()
        Log.d(tag, "VoiceArmor stopped")
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
                val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
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
