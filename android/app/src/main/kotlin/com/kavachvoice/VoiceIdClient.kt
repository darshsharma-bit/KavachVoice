package com.kavachvoice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class VoiceIdResult(
    val isSuccess: Boolean,
    val verdict: String, // "GENUINE", "SYNTHETIC", "UNCERTAIN", "UNAVAILABLE"
    val confidence: Float = 0.0f,
    val calibratedScore: Float = 0.0f,
    val rawnet2Score: Float = 0.0f,
    val ecapaScore: Float = 0.0f,
    val latencyMs: Float = 0.0f,
    val errorMessage: String? = null
)

/**
 * Asynchronous VoiceID Network Client.
 *
 * Connects the Android app to the real RawNet2 + ECAPA FastAPI backend:
 * 1. Wraps raw 16kHz PCM16 samples into a valid in-memory RIFF WAV stream.
 * 2. Transmits audio to POST /api/v1/analyze using HttpURLConnection (zero third-party deps).
 * 3. Parses genuine ML inference scores without blocking the AudioRecord capture loop.
 * 4. Fails safe (network errors yield UNAVAILABLE, never false SYNTHETIC).
 */
class VoiceIdClient(private val context: Context) {

    companion object {
        private const val TAG = "VoiceIdClient"
        private const val PREFS_NAME = "kavach_voiceid_prefs"
        private const val KEY_BACKEND_URL = "backend_url"
        
        // Default backend address: PC host LAN IP or USB reverse proxy (tcp:8000)
        const val DEFAULT_BACKEND_URL = "http://10.5.1.87:8000"
        const val FALLBACK_REVERSE_URL = "http://127.0.0.1:8000"
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    fun getBackendUrl(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL) ?: DEFAULT_BACKEND_URL
    }

    fun setBackendUrl(url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BACKEND_URL, url.trimEnd('/')).apply()
        Log.i(TAG, "Configured VoiceID backend URL: $url")
    }

    /**
     * Check backend health asynchronously.
     */
    fun checkHealth(onResult: (Boolean, String) -> Unit) {
        scope.launch {
            val baseUrl = getBackendUrl()
            try {
                val url = URL("$baseUrl/health")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2000
                    readTimeout = 2000
                    requestMethod = "GET"
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    Log.i(TAG, "Backend health check OK: $body")
                    withContext(Dispatchers.Main) { onResult(true, body) }
                } else {
                    withContext(Dispatchers.Main) { onResult(false, "HTTP $responseCode") }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Backend health check failed: ${e.message}")
                withContext(Dispatchers.Main) { onResult(false, e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * Send a real audio window (16kHz PCM16) to the FastAPI backend for real RawNet2 + ECAPA inference.
     */
    fun analyzeAudioAsync(
        pcm16Samples: ShortArray,
        onResult: (VoiceIdResult) -> Unit
    ) {
        if (pcm16Samples.isEmpty()) {
            onResult(VoiceIdResult(false, "UNAVAILABLE", errorMessage = "Empty audio buffer"))
            return
        }
        val wavBytes = pcm16ToWav(pcm16Samples)
        analyzeWavBytesAsync(wavBytes, onResult)
    }

    /**
     * Transmit WAV audio bytes to the FastAPI backend POST /api/v1/analyze for genuine inference.
     */
    fun analyzeWavBytesAsync(
        wavBytes: ByteArray,
        onResult: (VoiceIdResult) -> Unit
    ) {
        if (wavBytes.isEmpty()) {
            onResult(VoiceIdResult(false, "UNAVAILABLE", errorMessage = "Empty audio bytes"))
            return
        }

        scope.launch {
            val t0 = System.currentTimeMillis()
            val baseUrl = getBackendUrl()

            Log.d(TAG, "VoiceID: REAL inference started — sending ${wavBytes.size} bytes to $baseUrl/api/v1/analyze")

            var conn: HttpURLConnection? = null
            try {
                val boundary = "===KavachVoiceBoundary" + System.currentTimeMillis() + "==="
                val lineFeed = "\r\n"
                val url = URL("$baseUrl/api/v1/analyze")

                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 5000
                    requestMethod = "POST"
                    doOutput = true
                    doInput = true
                    useCaches = false
                    setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                }

                conn.outputStream.use { os ->
                    val writer = PrintWriter(OutputStreamWriter(os, "UTF-8"), true)

                    // Write multipart file header
                    writer.append("--$boundary").append(lineFeed)
                    writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"voice_window.wav\"").append(lineFeed)
                    writer.append("Content-Type: audio/wav").append(lineFeed)
                    writer.append(lineFeed).flush()

                    // Write binary WAV bytes
                    os.write(wavBytes)
                    os.flush()

                    // Close multipart boundary
                    writer.append(lineFeed).flush()
                    writer.append("--$boundary--").append(lineFeed).flush()
                }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val responseStr = reader.readText()
                    reader.close()

                    val json = JSONObject(responseStr)
                    val verdict = json.optString("verdict", "UNCERTAIN")
                    val confidence = json.optDouble("confidence", 0.0).toFloat()
                    val calibratedScore = json.optDouble("calibrated_score", 0.0).toFloat()
                    val rawnet2Score = json.optDouble("rawnet2_score", 0.0).toFloat()
                    val ecapaScore = json.optDouble("ecapa_score", 0.0).toFloat()
                    val latencyMs = json.optDouble("latency_ms", (System.currentTimeMillis() - t0).toDouble()).toFloat()

                    Log.i(TAG, "VoiceID: REAL inference response: verdict=$verdict, confidence=$confidence, rawnet2=$rawnet2Score, ecapa=$ecapaScore, backendLatency=${latencyMs}ms")

                    val result = VoiceIdResult(
                        isSuccess = true,
                        verdict = verdict,
                        confidence = confidence,
                        calibratedScore = calibratedScore,
                        rawnet2Score = rawnet2Score,
                        ecapaScore = ecapaScore,
                        latencyMs = latencyMs
                    )
                    withContext(Dispatchers.Main) { onResult(result) }
                } else {
                    val err = "HTTP $responseCode: ${conn.responseMessage}"
                    Log.w(TAG, "VoiceID request returned error: $err")
                    withContext(Dispatchers.Main) {
                        onResult(VoiceIdResult(false, "UNAVAILABLE", errorMessage = err))
                    }
                }
            } catch (e: Exception) {
                val err = e.message ?: "Connection error"
                Log.w(TAG, "VoiceID backend request failed safely: $err")
                withContext(Dispatchers.Main) {
                    onResult(VoiceIdResult(false, "UNAVAILABLE", errorMessage = err))
                }
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Constructs a compliant 44-byte standard RIFF PCM WAV file from 16kHz mono PCM16 samples.
     */
    private fun pcm16ToWav(pcm16: ShortArray): ByteArray {
        val totalAudioLen = pcm16.size * 2
        val totalDataLen = totalAudioLen + 36
        val sampleRate = 16000L
        val channels = 1
        val byteRate = 16000L * 2 * channels

        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // Subchunk1Size for PCM
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // AudioFormat: 1 = PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte() // BlockAlign: channels * bytesPerSample
        header[33] = 0
        header[34] = 16 // BitsPerSample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
        header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        val out = ByteArray(44 + totalAudioLen)
        System.arraycopy(header, 0, out, 0, 44)

        val byteBuf = ByteBuffer.wrap(out, 44, totalAudioLen).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm16) {
            byteBuf.putShort(sample)
        }
        return out
    }
}
