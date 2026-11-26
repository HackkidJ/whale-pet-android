package com.dsdaughter.pet

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 提醒触发时点亮屏幕显示的全屏提醒页。 */
class ReminderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val message = intent?.getStringExtra("message").orEmpty().ifBlank { "时间到了" }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF14161C.toInt())
            setPadding(48, 48, 48, 48)
        }
        container.addView(
            TextView(this).apply {
                text = "⏰ 鲸鲸提醒你"
                setTextColor(0xFF7FD4FF.toInt())
                textSize = 20f
            },
        )
        container.addView(
            TextView(this).apply {
                text = message
                setTextColor(0xFFE7E9EF.toInt())
                textSize = 28f
                setPadding(0, 32, 0, 48)
            },
        )
        container.addView(
            Button(this).apply {
                text = "知道了"
                setOnClickListener { finish() }
            },
        )
        setContentView(container)
    }
}
