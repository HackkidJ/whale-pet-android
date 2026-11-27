package com.dsdaughter.pet

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.delay
import org.json.JSONObject

/** 根据用户的一句话，在无障碍服务上一步步看屏幕、点按，完成简单手机任务。 */
object AccessibilityAgent {
    private data class Action(
        val name: String,
        val text: String = "",
        val x: Int = 0,
        val y: Int = 0,
        val direction: String = "",
        val packageName: String = "",
        val summary: String = "",
        val reason: String = "",
    )

    fun looksLikePhoneTask(text: String): Boolean = PhoneIntent.isTask(text)

    suspend fun run(
        context: Context,
        request: String,
        isCancelled: () -> Boolean = { false },
        onAsk: suspend (String) -> String? = { null },
        onProgress: (String) -> Unit,
    ): String {
        val service = PetAccessibilityService.instance
            ?: return when (AccessibilityUtil.status(context)) {
                AccessibilityUtil.Status.ENABLED_NOT_CONNECTED ->
                    "无障碍已经授权了，但服务没连上（系统没绑定）。重启一下手机，或者去设置里把「鲸鲸无障碍」关掉再开一次。"
                else -> "无障碍还没开，先去设置里把「鲸鲸无障碍」打开，我才能看屏幕帮你点。"
            }
        if (!service.screenOn()) return "手机现在锁屏/熄屏了，先亮屏再让我操作。"
        val cleanedRequest = request
            .replace(Regex("进入鲸鲸操作|鲸鲸操作|进入操作|帮我操作手机|操作我的手机|操作手机|帮我操作|你操作|去操作"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { request }
        val history = mutableListOf<String>()
        val signatures = mutableListOf<String>()
        var lastScreenshot: Uri? = null
        var lastLines: List<String> = emptyList()
        repeat(14) { round ->
            if (isCancelled()) return "好，我停下了，没继续操作。"
            val snapshot = OverlayHider.withHidden { service.snapshot() }
            lastLines = snapshot.lines
            val visionOnly = snapshot.lines.isEmpty() ||
                snapshot.packageName == "com.tencent.mm" ||
                snapshot.packageName == "com.tencent.mobileqq"
            if (visionOnly) {
                onProgress("这个页面无障碍读不到，我截图看看…")
                return VisionPhoneAgent.run(
                    context,
                    service,
                    request,
                    isCancelled,
                    onAsk,
                    onProgress,
                )
            }
            val prompt = buildPrompt(cleanedRequest, snapshot, history, context)
            val raw = try {
                DeepSeekClient.complete(
                    context,
                    "你在用无障碍操作一部 Android 手机。只输出一个 JSON 决策，不要解释。",
                    prompt,
                )
            } catch (exc: Exception) {
                return "分析屏幕时出错了：${exc.message ?: "网络问题"}"
            }
            val action = parseAction(raw)
                ?: run {
                    history.add("第${round + 1}步：输出不是合法 JSON，重试")
                    return@repeat
                }
            val signature = "${action.name}|${action.text}|${action.x},${action.y}|${action.direction}"
            signatures.add(signature)
            if (signatures.takeLast(3).size == 3 && signatures.takeLast(3).distinct().size == 1) {
                return "我在同一个操作上卡住了，先停一下。现在屏幕上是：${lastLines.take(5).joinToString("；")}"
            }
            when (action.name) {
                "done" -> return action.summary.ifBlank { "弄好了。" }
                "ask_user" -> {
                    val question = action.text.ifBlank { "我还需要一点信息，能告诉我吗？" }
                    onProgress("我需要问你一下…")
                    val answer = onAsk(question)
                    if (answer == null) return "好，我先停下。"
                    history.add("第${round + 1}步：我问「$question」，用户回答：$answer")
                    delay(300)
                }
                "failed" -> return "没弄成：${action.reason.ifBlank { "我看不明白这个页面" }}"
                "tap_text" -> {
                    onProgress("我点一下「${action.text}」…")
                    val ok = service.tapText(action.text)
                    history.add("第${round + 1}步：点文字「${action.text}」→ ${if (ok) "成功" else "没找到"}")
                    delay(700)
                }
                "tap_xy" -> {
                    val ok = service.tapXY(action.x, action.y)
                    history.add("第${round + 1}步：点坐标(${action.x},${action.y}) → ${if (ok) "成功" else "失败"}")
                    delay(600)
                }
                "input" -> {
                    onProgress("我输入「${action.text}」…")
                    val ok = service.inputText(action.text)
                    history.add("第${round + 1}步：输入「${action.text}」→ ${if (ok) "成功" else "没找到输入框"}")
                    delay(500)
                }
                "scroll" -> {
                    val ok = when (action.direction.lowercase()) {
                        "up" -> service.swipeUp()
                        "down" -> service.swipeDown()
                        "left" -> service.swipeLeft()
                        else -> service.swipeRight()
                    }
                    history.add("第${round + 1}步：滑动 ${action.direction} → ${if (ok) "成功" else "失败"}")
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
                "open_app" -> {
                    var pkg = resolvePackage(action.packageName) ?: action.packageName
                    if (pkg == context.packageName || action.packageName.contains("鲸鲸")) {
                        history.add("第${round + 1}步：不能打开自己（鲸鲸），请直接打开目标 App")
                        delay(300)
                        return@repeat
                    }
                    onProgress("我打开${appName(context, pkg)}…")
                    val ok = openApp(context, pkg)
                    history.add("第${round + 1}步：打开 $pkg → ${if (ok) "请求已发出" else "没装/打不开"}")
                    if (!ok) {
                        history.add("第${round + 1}步：应用不存在或启动失败")
                    } else {
                        var handled = false
                        repeat(2) { attempt ->
                            delay(if (attempt == 0) 1800 else 1200)
                            val guard = runCatching { OverlayHider.withHidden { service.snapshot() } }.getOrNull()
                            val guardText = guard?.lines?.joinToString(" ").orEmpty()
                            if (guardText.contains("想要打开") || guardText.contains("始终允许")) {
                                service.tapCheckBoxNearText("始终允许打开")
                                delay(350)
                                service.tapTextExact("打开")
                                history.add("第${round + 1}步：处理启动确认弹窗（第${attempt + 1}次）")
                                handled = true
                                delay(1500)
                            }
                        }
                        if (handled) delay(800)
                    }
                }
                "screenshot" -> {
                    onProgress("截图保存中…")
                    val bitmap = runCatching { ScreenCapture.capture(service) }.getOrNull()
                    val uri = bitmap?.let { ScreenCapture.save(context, it) }
                    lastScreenshot = uri
                    if (uri != null) {
                        history.add("第${round + 1}步：截图并保存到相册（$uri）")
                    } else {
                        history.add("第${round + 1}步：截图失败（可能被应用禁止截屏）")
                    }
                    delay(600)
                }
                "share_image" -> {
                    val uri = lastScreenshot ?: run {
                        val bitmap = runCatching { ScreenCapture.capture(service) }.getOrNull()
                        bitmap?.let { ScreenCapture.save(context, it) }
                    }
                    if (uri == null) {
                        history.add("第${round + 1}步：没有可分享的截图")
                    } else {
                        val pkg = resolvePackage(action.packageName)
                            ?: AppCatalog.resolve("qq")
                        onProgress("把截图分享到${appName(context, pkg ?: "")}…")
                        val ok = ScreenCapture.share(context, uri, pkg)
                        history.add("第${round + 1}步：分享截图到 $pkg → ${if (ok) "成功" else "失败"}")
                    }
                    delay(1500)
                }
                "wait" -> delay(900)
                else -> history.add("第${round + 1}步：不认识的指令 ${action.name}")
            }
        }
        return "我试了十几步还没做完。现在屏幕上是：${lastLines.take(5).joinToString("；")}"
    }

    private fun buildPrompt(
        request: String,
        snapshot: PetAccessibilityService.Snapshot,
        history: List<String>,
        context: Context,
    ): String {
        val lines = snapshot.lines.take(80).joinToString("\n")
        val historyText = if (history.isEmpty()) "（还没做任何操作）" else history.takeLast(8).joinToString("\n")
        return """
用户请求：$request
当前前台 App：${AppInfoUtil.describe(context, snapshot.packageName)}
屏幕内容（行首 [数字] 是节点，可点=能点击，可输入=输入框）：
$lines

已经做过的步骤：
$historyText

请选择下一步，只输出 JSON：
{"action":"tap_text","text":"要点的文字"}
{"action":"tap_xy","x":数字,"y":数字}
{"action":"input","text":"要输入的文字"}
{"action":"scroll","direction":"up|down|left|right"}
{"action":"back"} 或 {"action":"home"}
{"action":"open_app","package":"目标应用名或包名（不能填鲸鲸自己）"}
{"action":"screenshot"}  截图保存到相册
{"action":"share_image","package":"qq"}  把最近一张截图分享到指定 App
{"action":"ask_user","question":"缺少信息时向用户询问的问题（地址/规格/数量/选哪个/是否继续）"}
{"action":"wait"}
{"action":"done","summary":"完成了什么"}
{"action":"failed","reason":"为什么做不了"}
注意：一次只做一步；缺地址/规格/数量/选哪个/是否继续等必要信息时，先 ask_user 问用户，不要瞎猜，也不要直接 failed；输入前先点输入框；不要重复已经做过的动作。
        """.trimIndent()
    }

    private fun parseAction(raw: String): Action? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val json = JSONObject(raw.substring(start, end + 1))
            Action(
                name = json.optString("action"),
                text = json.optString("text").ifBlank { json.optString("question") },
                x = json.optInt("x"),
                y = json.optInt("y"),
                direction = json.optString("direction"),
                packageName = json.optString("package"),
                summary = json.optString("summary"),
                reason = json.optString("reason"),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun resolvePackage(name: String): String? = AppCatalog.resolve(name)

    private fun appName(context: Context, pkg: String): String =
        AppInfoUtil.label(context, pkg)

    private fun openApp(context: Context, pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
