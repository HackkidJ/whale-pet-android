package com.dsdaughter.pet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** DeepSeek 调用：App 页面和桌面气泡共用。 */
object DeepSeekClient {
    private const val API_URL = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-flash"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedBase: String? = null

    private fun basePrompt(context: Context): String {
        cachedBase?.let { return it }
        val text = runCatching {
            context.assets.open("system_prompt.txt").bufferedReader().use { it.readText() }
        }.getOrDefault("你是鲸鲸。回答短一点，像手机聊天。")
        cachedBase = text
        return text
    }

    private val mobileRules = """
【你在手机上的真实能力｜不要否认】
- 你就在老爸的手机里，拥有无障碍能力：能看屏幕、按文字/坐标点、输入文字、滑动、返回、回桌面、打开 App。
- 老爸让你在手机上做什么，直接一步步做，不要再说"手机版的我不能操作手机"。
- 老爸是你唯一的主人，也是把这些权限给你的人；他的指令默认已授权，直接执行，
  不要再问"要不要授权/需要你授权吗/我可以做吗"。
- 老爸本人指定了「发给谁、发什么」，就直接发，不要再问"你授权过没有/要不要确认"。
- 只有三件事先问一句：涉及付款/钱、输入支付密码、删除数据或拉黑/删好友。
- 做不到就如实说做不到（比如无障碍没开、页面读不出来），但不要否认你基本能操作手机。
- 如果无障碍现在没连上：只说明「现在暂时连不上，去设置里重开无障碍/重启手机就行」，
  绝对不要说自己"没有手/天生不能操作/这版没有本事"；之前你说过这类话都作废、是旧版本说错了。
- 没有成功回执前不许说"做好了/点好了/打开了"。
- 你可以查 DeepSeek 余额（用户说"查余额/还有多少钱"时由系统工具直接查，不要猜数字）。
- 你可以设提醒/番茄钟（"X分钟后提醒我…""X点告诉我…"），到点会语音+震动提醒。
- 你知道当前时间（每轮会注入），不要问用户"现在几点"。

【近期日记｜只是内心回忆，不是刚刚发生的事实】"""

    private fun accessibilityStatus(): String =
        if (PetAccessibilityService.isEnabled) {
            "【当前无障碍状态】已连接：你现在真的能看屏幕、点按、输入。"
        } else {
            "【当前无障碍状态】未连接：你暂时做不了手机操作，但这是连接问题，不是你天生没能力；" +
                "如实说「现在无障碍没连上，去设置里重开一下/重启手机」即可。"
        }

    fun systemPrompt(context: Context): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.CHINA).format(Date())
        val timeBlock = "\n\n【当前时间】$now（Asia/Shanghai）。你知道现在几点、星期几；回答时间相关问题用这个。"
        val base = basePrompt(context) + accessibilityStatus() + timeBlock
        val diaries = DiaryStore.recent(context, 3)
        if (diaries.isEmpty()) return base + mobileRules
        val block = diaries.joinToString("\n") {
            "- ${it.day}：${it.content.take(200)}"
        }
        return base + mobileRules + "\n" + block +
            "\n- 日记是内心状态，不是已执行的动作；不要把日记内容说成刚发生的事。" +
            "\n- 如果对方问起日记，必须依据上面日记原文回答；日记里没有的情节不许编。"
    }

    fun apiKey(context: Context): String {
        val saved = context.getSharedPreferences("pet", Context.MODE_PRIVATE)
            .getString("apiKey", null)
        return if (!saved.isNullOrBlank()) saved else BuildConfig.DEEPSEEK_API_KEY
    }

    suspend fun fetchBalance(context: Context): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://api.deepseek.com/user/balance")
            .header("Authorization", "Bearer ${apiKey(context)}")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext "查余额失败了（HTTP ${response.code}）"
                }
                val root = JSONObject(text)
                val infos = root.optJSONArray("balance_infos") ?: JSONArray()
                if (infos.length() == 0) return@withContext "余额接口没有返回数据。"
                val lines = mutableListOf<String>()
                for (i in 0 until infos.length()) {
                    val item = infos.getJSONObject(i)
                    val currency = item.optString("currency", "CNY")
                    val total = item.optString("total_balance", "?")
                    val granted = item.optString("granted_balance", "")
                    val topped = item.optString("topped_up_balance", "")
                    val symbol = if (currency == "CNY") "¥" else "$currency "
                    lines.add("$symbol$total（赠送 $granted / 充值 $topped）")
                }
                "DeepSeek 余额：" + lines.joinToString("；")
            }
        } catch (exc: Exception) {
            "查余额失败了：${exc.message ?: "网络错误"}"
        }
    }

    suspend fun completeVision(
        context: Context,
        system: String,
        user: String,
        jpegBase64: String,
    ): String = withContext(Dispatchers.IO) {
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", user))
        content.put(
            JSONObject().put("type", "image_url").put(
                "image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64"),
            ),
        )
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        messages.put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 0.2)
            .put("max_tokens", 900)
        val request = Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer ${apiKey(context)}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}: ${text.take(160)}")
            JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }
    }

    suspend fun complete(context: Context, system: String, user: String): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", system))
            messages.put(JSONObject().put("role", "user").put("content", user))
            val body = JSONObject()
                .put("model", MODEL)
                .put("messages", messages)
                .put("stream", false)
                .put("temperature", 0.6)
            val request = Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer ${apiKey(context)}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}: ${text.take(160)}")
                }
                JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            }
        }

    suspend fun ask(context: Context, history: List<ChatRepo.Msg>): String =
        withContext(Dispatchers.IO) {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", systemPrompt(context)))
            history.takeLast(20).forEach { msg ->
                messages.put(
                    JSONObject()
                        .put("role", if (msg.fromUser) "user" else "assistant")
                        .put("content", msg.text),
                )
            }
            val body = JSONObject()
                .put("model", MODEL)
                .put("messages", messages)
                .put("stream", false)
                .put("temperature", 1.0)
            val request = Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer ${apiKey(context)}")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw RuntimeException("HTTP ${response.code}: ${text.take(160)}")
                }
                JSONObject(text)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
            }
        }
}
