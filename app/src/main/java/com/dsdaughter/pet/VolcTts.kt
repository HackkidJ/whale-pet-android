package com.dsdaughter.pet

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 火山引擎豆包语音 v3：跟 NapCat 项目同一套 key/音色，合成后本地播放。 */
object VolcTts {
    private const val ENDPOINT = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun synthesize(context: Context, text: String): ByteArray? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.VOLC_API_KEY
        if (apiKey.isBlank()) return@withContext null
        val body = JSONObject().apply {
            put("user", JSONObject().put("uid", "dsdaughter_mobile"))
            put(
                "req_params",
                JSONObject()
                    .put("text", text.take(300))
                    .put("speaker", BuildConfig.VOLC_VOICE_TYPE)
                    .put(
                        "audio_params",
                        JSONObject().put("format", "wav").put("sample_rate", 24000),
                    ),
            )
        }
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("X-Api-Key", apiKey)
            .header("X-Api-Resource-Id", BuildConfig.VOLC_RESOURCE_ID)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val source = response.body?.source() ?: return@use null
                val out = ByteArrayOutputStream()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line()?.trim().orEmpty()
                    if (line.isEmpty()) continue
                    val jsonText = if (line.startsWith("data:")) line.substring(5).trim() else line
                    runCatching {
                        val item = JSONObject(jsonText)
                        val encoded = item.optString("data")
                        if (encoded.isNotEmpty()) {
                            out.write(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                        }
                    }
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /** 播放 WAV；返回 MediaPlayer 以便调用方感知结束。 */
    fun play(context: Context, wav: ByteArray, onDone: (() -> Unit)? = null): MediaPlayer? {
        val file = File(context.cacheDir, "whale_tts_${System.currentTimeMillis()}.wav")
        file.writeBytes(ensureWav(wav))
        return runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    file.delete()
                    onDone?.invoke()
                }
                setOnErrorListener { player, _, _ ->
                    player.release()
                    file.delete()
                    onDone?.invoke()
                    true
                }
                prepare()
                start()
            }
        }.getOrNull()
    }

    private fun ensureWav(audio: ByteArray): ByteArray {
        if (audio.size > 4 && String(audio, 0, 4, Charsets.US_ASCII) == "RIFF") return audio
        val pcm = audio
        val out = ByteArrayOutputStream()
        val dataLen = pcm.size
        val byteRate = 24000 * 2
        out.write("RIFF".toByteArray())
        out.write(intLe(36 + dataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intLe(16))
        out.write(shortLe(1))
        out.write(shortLe(1))
        out.write(intLe(24000))
        out.write(intLe(byteRate))
        out.write(shortLe(2))
        out.write(shortLe(16))
        out.write("data".toByteArray())
        out.write(intLe(dataLen))
        out.write(pcm)
        return out.toByteArray()
    }

    private fun intLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte(),
    )

    private fun shortLe(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
    )
}
