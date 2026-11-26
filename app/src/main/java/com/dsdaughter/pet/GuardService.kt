package com.dsdaughter.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 常驻守护：保持进程不被 ColorOS 回收；无障碍掉线时通知一键重连。 */
class GuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var manager: NotificationManager? = null
    private var wasDisconnected = false
    private var disconnectedSince = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannels()
        startForeground(ONLINE_ID, buildOnlineNotification())
        scope.launch { watchAccessibility() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watchAccessibility() {
        while (scope.isActive) {
            delay(5000)
            when (AccessibilityUtil.status(this@GuardService)) {
                AccessibilityUtil.Status.CONNECTED -> {
                    disconnectedSince = 0L
                    if (wasDisconnected) {
                        wasDisconnected = false
                        manager?.cancel(RECONNECT_ID)
                    }
                }
                AccessibilityUtil.Status.ENABLED_NOT_CONNECTED -> {
                    if (disconnectedSince == 0L) disconnectedSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - disconnectedSince > 8_000 && !wasDisconnected) {
                        wasDisconnected = true
                        manager?.notify(RECONNECT_ID, buildReconnectNotification())
                    }
                }
                AccessibilityUtil.Status.OFF -> {
                    disconnectedSince = 0L
                    wasDisconnected = false
                    manager?.cancel(RECONNECT_ID)
                }
            }
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ONLINE, "鲸鲸守护", NotificationManager.IMPORTANCE_MIN),
            )
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERT, "无障碍掉线提醒", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }

    private fun buildOnlineNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ONLINE)
            .setContentTitle("鲸鲸在线")
            .setContentText("无障碍守护中；掉线会提醒你")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun buildReconnectNotification(): Notification {
        val pending = PendingIntent.getActivity(
            this,
            2,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ALERT)
            .setContentTitle("鲸鲸的无障碍掉线了")
            .setContentText("点这里去设置里关掉「鲸鲸无障碍」再打开，或重启手机")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ONLINE = "whale_guard"
        private const val CHANNEL_ALERT = "whale_alert"
        private const val ONLINE_ID = 4102
        private const val RECONNECT_ID = 4103

        fun start(context: android.content.Context) {
            runCatching {
                context.startForegroundService(Intent(context, GuardService::class.java))
            }
        }
    }
}
