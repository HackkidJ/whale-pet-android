package com.dsdaughter.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/** 仅 Debug 构建可用：adb 触发一条提醒，用于验证时刻表和熄屏唤醒。 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.action == "com.dsdaughter.pet.DEBUG_INTENT") {
            val text = intent.getStringExtra("text").orEmpty()
            android.util.Log.i(
                "WhaleIntent",
                "text=$text task=${PhoneIntent.isTask(text)} look=${PhoneIntent.isLookRequest(text)} balance=${PhoneIntent.isBalanceRequest(text)}",
            )
            return
        }
        if (intent.action == "com.dsdaughter.pet.DEBUG_ASK") {
            val question = intent.getStringExtra("question") ?: "测试：你要喝什么？"
            val overlay = AskOverlay(context)
            CoroutineScope(Dispatchers.Main).launch {
                val answer = overlay.ask(question)
                android.util.Log.i("WhaleAsk", "answer=$answer")
            }
            return
        }
        if (intent.action == "com.dsdaughter.pet.DEBUG_OVERLAY") {
            val overlay = OperationOverlay(context) {}
            overlay.show()
            overlay.updateStep("测试步骤：打开设置")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { overlay.hide() }
            }, 15_000)
            return
        }
        if (intent.action == "com.dsdaughter.pet.DEBUG_TASK") {
            val request = intent.getStringExtra("request") ?: "打开设置"
            PhoneOperator.start(context, request)
            Toast.makeText(context, "开始测试操作：$request", Toast.LENGTH_SHORT).show()
            return
        }
        if (intent.action == "com.dsdaughter.pet.DEBUG_CAPTURE") {
            val service = PetAccessibilityService.instance ?: return
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                val bitmap = runCatching { service.captureScreenshot() }.getOrNull()
                if (bitmap != null) {
                    runCatching {
                        File(context.filesDir, "capture_test.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                    android.util.Log.i("WhaleCapture", "ok ${bitmap.width}x${bitmap.height}")
                } else {
                    android.util.Log.e("WhaleCapture", "capture returned null")
                }
                pending.finish()
            }
            return
        }
        if (intent.action != "com.dsdaughter.pet.DEBUG_REMINDER") return
        val message = intent.getStringExtra("message") ?: "测试提醒"
        val minutes = intent.getIntExtra("minutes", 1).coerceIn(0, 120)
        val at = System.currentTimeMillis() + minutes * 60_000L
        ReminderScheduler.schedule(context, message, at)
        Toast.makeText(
            context,
            "已排提醒：$message（${ReminderScheduler.label(at)}）",
            Toast.LENGTH_LONG,
        ).show()
    }
}
