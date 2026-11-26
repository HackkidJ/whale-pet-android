package com.dsdaughter.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** 精确闹钟到点：启动提醒服务（语音+震动+气泡），并把一次性提醒从时刻表移除。 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val message = intent.getStringExtra("message") ?: return
        ReminderStore.remove(context, id)
        val serviceIntent = Intent(context, ReminderService::class.java)
            .putExtra("message", message)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }.onFailure { showFallback(context, message) }
    }

    private fun showFallback(context: Context, message: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("whale_remind", "提醒", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            4200,
            Notification.Builder(context, "whale_remind")
                .setContentTitle("鲸鲸提醒你")
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }
}
