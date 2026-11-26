package com.dsdaughter.pet

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object ReminderScheduler {
    fun schedule(context: Context, message: String, atMillis: Long): Int {
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        ReminderStore.add(context, ReminderStore.Reminder(id, message, atMillis))
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(context, id, message, atMillis)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        runCatching {
            if (exact) {
                val show = PendingIntent.getActivity(
                    context,
                    id,
                    Intent(context, ReminderActivity::class.java)
                        .putExtra("message", message)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                alarm.setAlarmClock(
                    AlarmManager.AlarmClockInfo(atMillis, show),
                    pending,
                )
            } else {
                alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
            }
        }.onFailure {
            runCatching { alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending) }
        }
        return id
    }

    fun cancel(context: Context, id: Int) {
        val reminder = ReminderStore.remove(context, id) ?: return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching { alarm.cancel(pending) }
    }

    fun label(atMillis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = atMillis }
        val now = Calendar.getInstance()
        val sameDay = calendar.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) &&
            calendar.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val hm = "%02d:%02d".format(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
        return if (sameDay) "今天 $hm" else "${calendar.get(Calendar.MONTH) + 1}月${calendar.get(Calendar.DAY_OF_MONTH)}日 $hm"
    }

    private fun pendingIntent(context: Context, id: Int, message: String, atMillis: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra("id", id)
            .putExtra("message", message)
            .putExtra("at", atMillis)
        return PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
