package com.dsdaughter.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 提醒触发：火山语音播报 + 震动（时长可设）+ 主动气泡，并写入聊天记录。 */
class ReminderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var bubble: BubbleOverlay? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel("whale_remind", "提醒", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        startForeground(4200, buildNotification("时间到了"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra("message")?.trim().orEmpty().ifBlank { "时间到了" }
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "whale:reminder")
            .also { runCatching { it.acquire(60_000L) } }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(4200, buildNotification(message))
        ChatRepo.add(this, fromUser = false, text = "⏰ 时间到了：$message")
        val prefs = getSharedPreferences("pet", Context.MODE_PRIVATE)
        val vibrateSeconds = prefs.getInt("vibrateSeconds", 3).coerceIn(0, 30)
        if (vibrateSeconds > 0) {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(
                        vibrateSeconds * 1000L,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(vibrateSeconds * 1000L)
            }
        }
        // 熄屏时点亮屏幕并显示全屏提醒（部分 ROM 需要闹钟/全屏通知权限）
        runCatching {
            startActivity(
                Intent(this, ReminderActivity::class.java)
                    .putExtra("message", message)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        runCatching {
            val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            bubble = BubbleOverlay(this, windowManager).also { it.showProactive("⏰ $message") }
        }
        scope.launch {
            val wav = runCatching {
                VolcTts.synthesize(this@ReminderService, "爸，时间到了。$message")
            }.getOrNull()
            if (wav != null) {
                VolcTts.play(this@ReminderService, wav)
            }
            delay(12_000)
            bubble?.hide()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        bubble?.hide()
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(message: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "whale_remind")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val fullScreen = android.app.PendingIntent.getActivity(
            this,
            77,
            Intent(this, ReminderActivity::class.java)
                .putExtra("message", message)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return builder
            .setContentTitle("鲸鲸提醒你")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setOngoing(true)
            .build()
    }
}
