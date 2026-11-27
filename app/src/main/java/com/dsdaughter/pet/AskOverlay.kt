package com.dsdaughter.pet

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 操作中缺信息时，悬浮输入框问用户；返回 null 表示取消/停止。 */
class AskOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var pending: CompletableDeferred<String?>? = null
    private val density = context.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    suspend fun ask(question: String): String? = withContext(Dispatchers.Main) {
        dismiss()
        val deferred = CompletableDeferred<String?>()
        pending = deferred
        val dp = { value: Int -> (value * density).toInt() }
        val container = FrameLayout(context).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF191C23.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF7FD4FF.toInt())
            }
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }
        card.addView(
            TextView(context).apply {
                text = "鲸鲸需要你补充"
                setTextColor(Color.parseColor("#7FD4FF"))
                textSize = 15f
            },
        )
        card.addView(
            TextView(context).apply {
                text = question
                setTextColor(Color.parseColor("#E7E9EF"))
                textSize = 14f
                setPadding(0, dp(8), 0, dp(10))
            },
        )
        val edit = EditText(context).apply {
            hint = "在这里输入…"
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#8B93A7"))
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine(false)
            maxLines = 3
        }
        card.addView(edit)
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        val cancel = TextView(context).apply {
            text = "取消"
            setTextColor(Color.parseColor("#8B93A7"))
            textSize = 14f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener {
                pending?.complete(null)
                dismiss()
            }
        }
        val send = TextView(context).apply {
            text = "发给她"
            setTextColor(Color.parseColor("#0E1116"))
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(0xFF7FD4FF.toInt())
                cornerRadius = dp(12).toFloat()
            }
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener {
                val answer = edit.text.toString().trim()
                if (answer.isEmpty()) {
                    edit.error = "写点什么再发"
                    return@setOnClickListener
                }
                pending?.complete(answer)
                dismiss()
            }
        }
        buttonRow.addView(cancel)
        buttonRow.addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(10)
        })
        card.addView(buttonRow)
        container.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dp(22)
                marginEnd = dp(22)
            },
        )
        root = container
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        runCatching { windowManager.addView(container, params) }
        edit.requestFocus()
        handler.postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(edit, InputMethodManager.SHOW_FORCED)
        }, 250)
        deferred.await()
    }

    fun dismiss() {
        pending?.let {
            if (!it.isCompleted) it.complete(null)
        }
        pending = null
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    private fun overlayType(): Int =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
