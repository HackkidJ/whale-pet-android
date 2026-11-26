package com.dsdaughter.pet

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/** "看看我屏幕 / 我在和谁聊天"：读屏回答；微信/QQ 或读不到时用截图+视觉。 */
object ScreenLookup {
    suspend fun answer(context: Context, question: String): String {
        val service = PetAccessibilityService.instance ?: return when (AccessibilityUtil.status(context)) {
            AccessibilityUtil.Status.ENABLED_NOT_CONNECTED ->
                "我这边无障碍没有连上（系统显示开着但服务没绑上），看不到你屏幕。重启一下手机，或去设置里把「鲸鲸无障碍」关掉再开一次。"
            else ->
                "我这边无障碍没开，看不到你屏幕。去设置 → 无障碍里把「鲸鲸无障碍」打开。"
        }
        if (!service.screenOn()) return "你手机现在锁屏/熄屏了，亮屏我再看。"
        val snapshot = service.snapshot()
        val tracked = PetAccessibilityService.lastPackage
        val pkg = if (tracked.isNotBlank() && tracked != context.packageName) {
            tracked
        } else {
            snapshot.packageName.ifBlank { tracked }
        }
        val appDesc = AppInfoUtil.describe(context, pkg)
        val chatApp = pkg == "com.tencent.mm" || pkg == "com.tencent.mobileqq"
        if (chatApp || snapshot.lines.isEmpty()) {
            val bitmap = runCatching { service.captureScreenshot() }.getOrNull()
                ?: return "这个页面连截图都拿不到（可能被应用禁止截屏），我看不见屏幕内容。"
            val base64 = toBase64(bitmap)
            val prompt = """
这是老爸的手机截图。
他问：$question
当前前台应用：$appDesc
请只根据截图里真实能看到的内容回答：他在和谁聊天/屏幕上是什么。
- 这张截图里可能混着鲸鲸自己的气泡/悬浮窗，忽略它，重点看后面的聊天界面。
- 如果是聊天界面，说出聊天对象的名字（如果能看清）和大概在聊什么。
- 看不清就说看不清，不要编。
- 回答一两句，像聊天。
            """.trimIndent()
            return runCatching {
                DeepSeekClient.completeVision(
                    context,
                    "你是鲸鲸，在帮老爸看他自己的手机屏幕。只根据截图回答，简短诚实。",
                    prompt,
                    base64,
                )
            }.getOrElse { "我看屏幕的时候出错了：${it.message ?: "视觉分析失败"}" }
        }
        val lines = snapshot.lines.take(70).joinToString("\n")
        val prompt = """
老爸问：$question
当前前台应用：$appDesc
屏幕内容：
$lines

请只根据屏幕内容回答（和谁聊天/在干嘛/屏幕是什么），一两句话；看不清就说看不清，不要编。
        """.trimIndent()
        return runCatching {
            DeepSeekClient.complete(
                context,
                "你是鲸鲸，在帮老爸看他自己的手机屏幕。只根据屏幕内容回答，简短诚实。",
                prompt,
            )
        }.getOrElse { "我看屏幕的时候出错了：${it.message ?: "分析失败"}" }
    }

    private fun toBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
