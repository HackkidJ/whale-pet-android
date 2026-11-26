package com.dsdaughter.pet

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 仅 Debug 构建可用：adb 触发一条提醒，用于验证时刻表和熄屏唤醒。 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
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
