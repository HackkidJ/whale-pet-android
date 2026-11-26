package com.dsdaughter.pet

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** 无障碍读不到页面时（比如微信）：截图 → DeepSeek 视觉 → 模拟点击/滑动/粘贴。 */
object VisionPhoneAgent {
    private data class Action(
        val name: String,
        val x: Int = 0,
        val y: Int = 0,
        val text: String = "",
        val direction: String = "",
        val packageName: String = "",
        val summary: String = "",
        val reason: String = "",
    )

    suspend fun run(
        context: Context,
        service: PetAccessibilityService,
        request: String,
        isCancelled: () -> Boolean,
        onProgress: (String) -> Unit,
    ): String {
        val history = mutableListOf<String>()
        var lastScreenshot: Uri? = null
        repeat(10) { round ->
            if (isCancelled()) return "好，我停下了，没继续操作。"
            val bitmap = service.captureScreenshot()
                ?: return "这个页面连截图都拿不到（可能被应用禁止截屏），我操作不了。"
            onProgress("我截图看看屏幕…")
            val appDesc = AppInfoUtil.describe(context, PetAccessibilityService.lastPackage)
            val prompt = buildPrompt(request, appDesc, bitmap, history)
            val raw = try {
                DeepSeekClient.completeVision(
                    context,
                    "你在用截图操作一部 Android 手机。只输出一个 JSON 决策，不要解释。",
                    prompt,
                    toBase64(bitmap),
                )
            } catch (exc: Exception) {
                return "视觉分析失败：${exc.message ?: "网络错误"}"
            }
            val action = parse(raw) ?: run {
                history.add("第${round + 1}步：输出不是合法 JSON")
                return@repeat
            }
            when (action.name) {
                "done" -> return action.summary.ifBlank { "弄好了。" }
                "failed" -> return "没弄成：${action.reason.ifBlank { "这个页面我操作不了" }}"
                "tap_xy" -> {
                    service.tapXY(action.x, action.y)
                    history.add("第${round + 1}步：点 (${action.x},${action.y})")
                    delay(700)
                }
                "long_press_xy" -> {
                    service.longPressXY(action.x, action.y)
                    history.add("第${round + 1}步：长按 (${action.x},${action.y})")
                    delay(600)
                }
                "input" -> {
                    onProgress("我输入「${action.text}」…")
                    val ok = service.inputText(action.text)
                    if (!ok) {
                        service.preparePaste(action.text)
                        history.add("第${round + 1}步：无障碍输入失败，已复制并长按准备粘贴")
                    } else {
                        history.add("第${round + 1}步：输入「${action.text}」")
                    }
                    delay(700)
                }
                "paste" -> {
                    service.setClipboardText(action.text)
                    service.preparePaste(action.text)
                    history.add("第${round + 1}步：复制并长按准备粘贴「${action.text}」")
                    delay(700)
                }
                "scroll" -> {
                    when (action.direction.lowercase()) {
                        "up" -> service.swipeUp()
                        "down" -> service.swipeDown()
                        "left" -> service.swipeLeft()
                        else -> service.swipeRight()
                    }
                    history.add("第${round + 1}步：滑动 ${action.direction}")
                    delay(600)
                }
                "back" -> {
                    service.pressBack()
                    history.add("第${round + 1}步：返回")
                    delay(600)
                }
                "home" -> {
                    service.pressHome()
                    history.add("第${round + 1}步：回桌面")
                    delay(600)
                }
                "screenshot" -> {
                    onProgress("截图保存中…")
                    val bitmap = runCatching { ScreenCapture.capture(service) }.getOrNull()
                    val uri = bitmap?.let { ScreenCapture.save(context, it) }
                    lastScreenshot = uri
                    history.add(
                        if (uri != null) "第${round + 1}步：截图并保存到相册" else "第${round + 1}步：截图失败",
                    )
                    delay(600)
                }
                "share_image" -> {
                    val uri = lastScreenshot ?: run {
                        val bitmap = runCatching { ScreenCapture.capture(service) }.getOrNull()
                        bitmap?.let { ScreenCapture.save(context, it) }
                    }
                    val pkg = AppCatalog.resolve(action.packageName) ?: AppCatalog.resolve("qq")
                    if (uri != null) {
                        onProgress("把截图分享到${AppInfoUtil.label(context, pkg ?: "")}…")
                        ScreenCapture.share(context, uri, pkg)
                        history.add("第${round + 1}步：分享截图")
                    } else {
                        history.add("第${round + 1}步：没有可分享的截图")
                    }
                    delay(1500)
                }
                "wait" -> delay(900)
                else -> history.add("第${round + 1}步：未知动作 ${action.name}")
            }
        }
        return "我截图看了 10 步还没做完，先停下了。"
    }

    private fun buildPrompt(
        request: String,
        appDesc: String,
        bitmap: Bitmap,
        history: List<String>,
    ): String {
        val historyText = if (history.isEmpty()) "（还没操作）" else history.takeLast(8).joinToString("\n")
        return """
任务：$request
当前前台应用：$appDesc
截图尺寸：${bitmap.width} x ${bitmap.height}（坐标就用这个尺寸的像素）

已经做过的步骤：
$historyText

只输出一个 JSON：
{"action":"tap_xy","x":整数,"y":整数,"reason":"..."}
{"action":"long_press_xy","x":整数,"y":整数}
{"action":"input","text":"要输入的文字"}
{"action":"paste","text":"要粘贴的文字"}
{"action":"scroll","direction":"up|down|left|right"}
{"action":"back"} / {"action":"home"} / {"action":"wait"}
{"action":"done","summary":"完成了什么"}
{"action":"failed","reason":"为什么做不了"}

要求：一次只做一步；坐标根据当前截图判断；要输入文字时先点输入框，
下一屏如果看到输入框再 input；如果 input 失败会自动长按准备粘贴，下一步在菜单里点「粘贴」。
不要重复做过的动作，不确定就 failed。
        """.trimIndent()
    }

    private fun toBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 65, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun parse(raw: String): Action? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching {
            val json = JSONObject(raw.substring(start, end + 1))
            Action(
                name = json.optString("action"),
                x = json.optInt("x"),
                y = json.optInt("y"),
                text = json.optString("text"),
                direction = json.optString("direction"),
                packageName = json.optString("package"),
                summary = json.optString("summary"),
                reason = json.optString("reason"),
            )
        }.getOrNull()
    }
}
