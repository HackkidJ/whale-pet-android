package com.dsdaughter.pet

import android.app.Service
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** 长按桌宠弹出的圆形/竖排菜单。 */
class PetMenuOverlay(
    private val service: Service,
    private val windowManager: WindowManager,
) {
    enum class Action { HOME, SLEEP, DRESS, BUBBLE, CLOSE }

    private var root: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private val density by lazy { service.resources.displayMetrics.density }
    val isShown: Boolean get() = root != null

    fun show(
        anchorX: Int,
        anchorY: Int,
        sleeping: Boolean,
        onAction: (Action) -> Unit,
    ) {
        hide()
        val dp = { value: Int -> (value * density).toInt() }
        val metrics = service.resources.displayMetrics
        val width = dp(132)
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(245, 25, 28, 35), dp(16))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(8).toFloat()
        }

        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(2), dp(4))
        }
        header.addView(
            TextView(service).apply {
                text = "鲸鲸"
                setTextColor(Color.parseColor("#9ECBFF"))
                textSize = 12f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            TextView(service).apply {
                text = "×"
                setTextColor(Color.parseColor("#8B93A7"))
                textSize = 18f
                setPadding(dp(10), 0, dp(6), dp(4))
                setOnClickListener {
                    hide()
                    onAction(Action.CLOSE)
                }
            },
        )
        container.addView(header)

        fun item(label: String, color: String, action: Action) {
            container.addView(
                TextView(service).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Color.parseColor(color))
                    setPadding(dp(12), dp(11), dp(12), dp(11))
                    setOnClickListener {
                        hide()
                        onAction(action)
                    }
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        item(if (sleeping) "起床" else "睡觉", "#E7E9EF", Action.SLEEP)
        item("换装", "#E7E9EF", Action.DRESS)
        item("气泡聊天", "#9ECBFF", Action.BUBBLE)
        item("回家（收起来）", "#FFB4AB", Action.HOME)

        params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (anchorX + dp(4)).coerceIn(0, (metrics.widthPixels - width).coerceAtLeast(0))
            y = (anchorY - dp(20)).coerceIn(dp(40), (metrics.heightPixels - dp(320)).coerceAtLeast(dp(40)))
        }
        root = container
        windowManager.addView(container, params)
    }

    private fun rounded(color: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }

    fun hide() {
        val view = root ?: return
        root = null
        runCatching { windowManager.removeView(view) }
    }

    fun destroy() = hide()
}
