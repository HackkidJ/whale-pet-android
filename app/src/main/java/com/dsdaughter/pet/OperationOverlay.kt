package com.dsdaughter.pet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 鲸鲸操作手机时的特效：全屏呼吸边框 + 顶部状态条 + 停止按钮。 */
class OperationOverlay(
    private val context: Context,
    private val onStop: () -> Unit,
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var border: View? = null
    private var pill: View? = null
    private var stepView: TextView? = null
    private var elapsedView: TextView? = null
    private var animator: ValueAnimator? = null
    private var ticker: Runnable? = null
    private var startedAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var captureHider: OverlayHider.Hider? = null
    private val density = context.resources.displayMetrics.density

    fun show() {
        if (!Settings.canDrawOverlays(context)) return
        if (border != null) return
        val dp = { value: Int -> (value * density).toInt() }

        val borderView = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), 0xFF7FD4FF.toInt())
                cornerRadius = dp(18).toFloat()
            }
            alpha = 0.6f
        }
        val borderParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        runCatching { windowManager.addView(borderView, borderParams) }
        border = borderView

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF2191C23.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF7FD4FF.toInt())
            }
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(context).apply {
                text = "鲸鲸正在操作手机"
                setTextColor(Color.parseColor("#7FD4FF"))
                textSize = 13f
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        titleRow.addView(
            Button(context).apply {
                text = "停止"
                textSize = 12f
                setTextColor(Color.parseColor("#FFFFFF"))
                background = GradientDrawable().apply {
                    setColor(0xFF8A3A3A.toInt())
                    cornerRadius = dp(16).toFloat()
                }
                setPadding(dp(14), dp(4), dp(14), dp(4))
                setOnClickListener { onStop() }
            },
        )
        container.addView(titleRow)
        val step = TextView(context).apply {
            text = "正在思考…"
            setTextColor(Color.parseColor("#E7E9EF"))
            textSize = 12f
            maxLines = 2
            setPadding(0, dp(4), 0, 0)
        }
        stepView = step
        container.addView(step)
        val elapsed = TextView(context).apply {
            text = "已操作 0:00"
            setTextColor(Color.parseColor("#8B93A7"))
            textSize = 11f
            setPadding(0, dp(3), 0, 0)
        }
        elapsedView = elapsed
        container.addView(elapsed)
        startedAt = System.currentTimeMillis()
        ticker = object : Runnable {
            override fun run() {
                val seconds = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                elapsed.text = "已操作 %d:%02d".format(seconds / 60, seconds % 60)
                handler.postDelayed(this, 1000)
            }
        }.also { handler.post(it) }

        val pillParams = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels * 0.90f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(44)
        }
        runCatching { windowManager.addView(container, pillParams) }
        pill = container
        captureHider = OverlayHider.Hider(
            hide = {
                borderView.visibility = View.GONE
                container.visibility = View.GONE
            },
            show = {
                borderView.visibility = View.VISIBLE
                container.visibility = View.VISIBLE
            },
        ).also { OverlayHider.register(it) }

        animator = ValueAnimator.ofFloat(0.35f, 0.95f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                borderView.alpha = animation.animatedValue as Float
            }
            start()
        }
    }

    fun updateStep(text: String) {
        stepView?.post { stepView?.text = "正在：${text.take(40)}" }
    }

    fun hide() {
        captureHider?.let { OverlayHider.unregister(it) }
        captureHider = null
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        animator?.cancel()
        animator = null
        border?.let { runCatching { windowManager.removeView(it) } }
        pill?.let { runCatching { windowManager.removeView(it) } }
        border = null
        pill = null
        stepView = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
