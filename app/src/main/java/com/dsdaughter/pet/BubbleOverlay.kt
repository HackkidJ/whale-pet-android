package com.dsdaughter.pet

import android.app.Service
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 桌面气泡对话框：点桌宠弹出，回复按长度自动消失；对话写入共享记录。 */
class BubbleOverlay(
    private val service: Service,
    private val windowManager: WindowManager,
) {
    private var root: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private var hideJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var replyText: TextView? = null
    private var inputRow: LinearLayout? = null
    private var editText: EditText? = null
    private var sendButton: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var holdStartedAt = 0L
    private var holdTicker: Runnable? = null
    private var holdReadyVibrated = false
    private var statusText: TextView? = null
    private val density by lazy { service.resources.displayMetrics.density }

    val isShown: Boolean get() = root != null

    fun toggle() {
        if (isShown) hide() else show()
    }

    fun show() {
        if (isShown) return
        ChatRepo.ensureLoaded(service)
        val metrics = service.resources.displayMetrics
        val width = (metrics.widthPixels * 0.90f).toInt()
        val dp = { value: Int -> (value * density).toInt() }

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(246, 25, 28, 35), dp(16))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        // 标题行
        val titleRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(service).apply {
            text = "鲸鲸"
            setTextColor(Color.parseColor("#9ECBFF"))
            textSize = 15f
        }
        val close = TextView(service).apply {
            text = "×"
            setTextColor(Color.parseColor("#8B93A7"))
            textSize = 22f
            setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener { hide() }
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(close)
        container.addView(titleRow)

        // 回复区
        val reply = TextView(service).apply {
            setTextColor(Color.parseColor("#E7E9EF"))
            textSize = 15f
            setPadding(0, dp(6), 0, dp(6))
            visibility = View.GONE
            setOnClickListener { hide() }
        }
        replyText = reply
        container.addView(reply)

        // 输入行
        val inputRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val edit = EditText(service).apply {
            hint = "说点什么…"
            setHintTextColor(Color.parseColor("#6F7789"))
            setTextColor(Color.parseColor("#E7E9EF"))
            textSize = 15f
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = rounded(Color.parseColor("#232833"), dp(10))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val send = TextView(service).apply {
            text = "发送"
            textSize = 13f
            setTextColor(Color.parseColor("#E7E9EF"))
            gravity = Gravity.CENTER
            background = rounded(0xFF2E5A88.toInt(), dp(10))
            setPadding(dp(14), dp(9), dp(14), dp(9))
            attachHoldToSend(this)
        }
        inputRow.addView(
            edit,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        inputRow.addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        this.inputRow = inputRow
        this.editText = edit
        this.sendButton = send
        container.addView(inputRow)

        val status = TextView(service).apply {
            setTextColor(Color.parseColor("#8B93A7"))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
            visibility = View.GONE
        }
        statusText = status
        container.addView(status)

        params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (120 * density).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        root = container
        windowManager.addView(container, params)
        edit.requestFocus()
        container.post {
            val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun attachHoldToSend(button: TextView) {
        button.setOnTouchListener { view, event ->
            val textView = view as TextView
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    holdStartedAt = System.currentTimeMillis()
                    holdReadyVibrated = false
                    textView.text = "按住…"
                    vibrate(service, 25)
                    startHoldTicker(textView)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    stopHoldTicker()
                    val held = System.currentTimeMillis() - holdStartedAt
                    textView.text = "发送"
                    if (held >= 1500) send()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    stopHoldTicker()
                    textView.text = "发送"
                    true
                }
                else -> true
            }
        }
    }

    private fun startHoldTicker(button: TextView) {
        stopHoldTicker()
        holdTicker = object : Runnable {
            override fun run() {
                val held = System.currentTimeMillis() - holdStartedAt
                if (held >= 1500) {
                    if (!holdReadyVibrated) {
                        holdReadyVibrated = true
                        vibrate(service, 70)
                    }
                    button.text = "松开发送"
                } else {
                    button.text = "按住 %.1fs".format(held / 1000.0)
                }
                handler.postDelayed(this, 100)
            }
        }.also { handler.post(it) }
    }

    private fun stopHoldTicker() {
        holdTicker?.let { handler.removeCallbacks(it) }
        holdTicker = null
    }

    private fun rounded(color: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }

    private fun send() {
        val edit = editText ?: return
        val text = edit.text.toString().trim()
        if (text.isEmpty()) return
        val reply = replyText ?: return
        val inputRow = inputRow ?: return
        val status = statusText
        ChatRepo.add(service, fromUser = true, text = text)
        edit.setText("")
        val command = PetCommandParser.parse(text)
        if (command != null) {
            ChatRepo.add(service, fromUser = false, text = command.reply)
            if (command == PetCommand.HOME) {
                hide()
                service.stopSelf()
                return
            }
            PetCommandBus.send(command)
            reply.visibility = View.VISIBLE
            inputRow.visibility = View.GONE
            reply.text = command.reply
            hideJob = scope.launch {
                kotlinx.coroutines.delay(displayMillis(command.reply))
                hide()
            }
            return
        }
        val reminder = ReminderParser.parse(text)
        if (reminder != null) {
            ReminderScheduler.schedule(service, reminder.message, reminder.atMillis)
            val replyText = "好，${ReminderScheduler.label(reminder.atMillis)}提醒你：${reminder.message}"
            ChatRepo.add(service, fromUser = false, text = replyText)
            reply.visibility = View.VISIBLE
            inputRow.visibility = View.GONE
            reply.text = replyText
            hideJob = scope.launch {
                kotlinx.coroutines.delay(displayMillis(replyText))
                hide()
            }
            return
        }
        if (PhoneIntent.isReminderRequest(text)) {
            val replyText = "这个提醒我没排上：说具体一点，比如「1小时后提醒我吃饭」「30分钟后提醒我喝水」。"
            ChatRepo.add(service, fromUser = false, text = replyText)
            reply.visibility = View.VISIBLE
            inputRow.visibility = View.GONE
            reply.text = replyText
            hideJob = scope.launch {
                kotlinx.coroutines.delay(displayMillis(replyText))
                hide()
            }
            return
        }
        if (PhoneIntent.isBalanceRequest(text)) {
            reply.visibility = View.VISIBLE
            reply.text = "我查一下余额…"
            inputRow.visibility = View.GONE
            scope.launch {
                val answer = DeepSeekClient.fetchBalance(service)
                ChatRepo.add(service, fromUser = false, text = answer)
                reply.post {
                    reply.text = answer
                    hideJob = scope.launch {
                        kotlinx.coroutines.delay(displayMillis(answer))
                        hide()
                    }
                }
            }
            return
        }
        if (PhoneIntent.isLookRequest(text)) {
            reply.visibility = View.VISIBLE
            reply.text = "我看看你屏幕…"
            inputRow.visibility = View.GONE
            status?.visibility = View.GONE
            scope.launch {
                val answer = ScreenLookup.answer(service, text)
                ChatRepo.add(service, fromUser = false, text = answer)
                reply.post {
                    reply.text = answer
                    hideJob = scope.launch {
                        kotlinx.coroutines.delay(displayMillis(answer))
                        hide()
                    }
                }
            }
            return
        }
        // 手机操作任务：气泡里直接启动任务代理（和 App 内共用同一个操作控制器）
        if (PhoneIntent.isTask(text)) {
            reply.visibility = View.VISIBLE
            reply.text = "我看看屏幕，稍等…"
            ChatRepo.add(service, fromUser = false, text = "我看看屏幕，稍等…")
            inputRow.visibility = View.GONE
            status?.visibility = View.GONE
            PhoneOperator.start(
                service,
                text,
                onProgress = { progress ->
                    reply.post { reply.text = progress }
                },
                onDone = { report ->
                    ChatRepo.add(service, fromUser = false, text = report)
                    reply.post {
                        reply.text = report
                        hideJob = scope.launch {
                            kotlinx.coroutines.delay(displayMillis(report))
                            hide()
                        }
                    }
                },
            )
            return
        }
        inputRow.visibility = View.GONE
        reply.visibility = View.VISIBLE
        reply.text = "……"
        status?.apply {
            visibility = View.VISIBLE
            this.text = "正在回你"
        }
        hideJob?.cancel()
        scope.launch {
            try {
                val answer = DeepSeekClient.ask(service, ChatRepo.messages.value)
                ChatRepo.add(service, fromUser = false, text = answer)
                if (root != null) {
                    reply.text = answer
                    status?.visibility = View.GONE
                    hideJob = scope.launch {
                        kotlinx.coroutines.delay(displayMillis(answer))
                        hide()
                    }
                }
            } catch (exc: Exception) {
                reply.text = "连不上：${exc.message ?: "网络错误"}"
                status?.visibility = View.GONE
            }
        }
    }

    /** 回复越长，停留越久：1.5s + 80ms/字，限制在 2.5s～12s。 */
    private fun displayMillis(text: String): Long {
        val fixedSeconds = service.getSharedPreferences("pet", Context.MODE_PRIVATE)
            .getInt("bubbleSeconds", 0)
        if (fixedSeconds > 0) return fixedSeconds * 1000L
        return (1500L + text.length * 80L).coerceIn(2500L, 12000L)
    }

    /** 视奸后的主动搭话：只显示、不聚焦、不弹键盘，按长度自动消失。 */
    fun showProactive(text: String, minMillis: Long = 0L) {
        if (isShown || text.isBlank()) return
        val dp = { value: Int -> (value * density).toInt() }
        val metrics = service.resources.displayMetrics
        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(246, 25, 28, 35), dp(16))
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }
        container.addView(
            TextView(service).apply {
                this.text = "鲸鲸"
                setTextColor(Color.parseColor("#9ECBFF"))
                textSize = 12f
            },
        )
        container.addView(
            TextView(service).apply {
                this.text = text
                setTextColor(Color.parseColor("#E7E9EF"))
                textSize = 15f
                setPadding(0, dp(6), 0, 0)
                setOnClickListener { hide() }
            },
        )
        params = WindowManager.LayoutParams(
            (metrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (110 * density).toInt()
        }
        root = container
        windowManager.addView(container, params)
        hideJob = scope.launch {
            kotlinx.coroutines.delay(maxOf(displayMillis(text), minMillis))
            hide()
        }
    }

    fun hide() {
        hideJob?.cancel()
        hideJob = null
        val view = root ?: return
        root = null
        val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
        runCatching { windowManager.removeView(view) }
    }

    fun destroy() {
        hide()
        scope.cancel()
    }
}
