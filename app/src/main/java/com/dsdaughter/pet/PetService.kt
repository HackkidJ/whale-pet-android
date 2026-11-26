package com.dsdaughter.pet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

/** 悬浮桌宠：精灵动画 + 自动散步 + 边界探头 + 拖动。 */
class PetService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var petView: PetView
    private lateinit var params: WindowManager.LayoutParams
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var behaviourJob: Job? = null
    private lateinit var bubble: BubbleOverlay
    private lateinit var menu: PetMenuOverlay
    private val menuHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null
    @Volatile
    private var sleeping = false
    @Volatile
    private var idleMode = false
    private var idleSwitchJob: Job? = null
    private var lastIdleRow = -1
    private var lastPeekAt = 0L
    private var peekInFlight = false
    private val density by lazy { resources.displayMetrics.density }
    private val random = Random(System.currentTimeMillis())

    @Volatile
    private var dragging = false
    @Volatile
    private var commandInProgress = false
    private var screenW = 1080
    private var screenH = 2400
    private var homeY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble = BubbleOverlay(this, windowManager)
        menu = PetMenuOverlay(this, windowManager)
        val metrics = resources.displayMetrics
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels
        showPet()
        startBehaviour()
        startPeekLoop()
        startDiaryLoop()
        scope.launch {
            PetCommandBus.commands.collect { command ->
                try {
                    executeCommand(command)
                } catch (exc: Exception) {
                    logCommandError(exc)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_BUBBLE -> {
                if (::bubble.isInitialized) bubble.toggle()
                return START_STICKY
            }
            ACTION_SHOW_MENU -> {
                if (::menu.isInitialized) showMenu()
                return START_STICKY
            }
            ACTION_PEEK -> {
                scope.launch { peekTick(force = true) }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        bubble.destroy()
        menu.destroy()
        scope.cancel()
        petView.let { runCatching { windowManager.removeView(it) } }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 窗口
    private fun showPet() {
        val sheet = assets.open("pet/spritesheet.webp").use { BitmapFactory.decodeStream(it) }
        val width = (112 * density).toInt()
        val height = (width * 208f / 192f).toInt()
        homeY = randomInYRange(height)
        petView = PetView(this).apply {
            this.sheet = sheet
            contentDescription = "鲸鲸"
            setOnTouchListener(DragTouchListener())
        }
        params = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - width - (24 * density).toInt()
            y = homeY
        }
        windowManager.addView(petView, params)
        playIdle()
    }

    private fun moveWindow(x: Int, y: Int) {
        params.x = x.coerceIn(-params.width, screenW)
        params.y = y.coerceIn(-params.height / 3, screenH)
        runCatching { windowManager.updateViewLayout(petView, params) }
    }

    // ------------------------------------------------------------------ 行为
    private fun startBehaviour() {
        behaviourJob = scope.launch {
            delay(1800)
            while (isActive) {
                if (dragging || bubble.isShown || menu.isShown || sleeping || commandInProgress) {
                    delay(200)
                    continue
                }
                when (random.nextInt(100)) {
                    in 0..54 -> walkToRandomX()
                    in 55..73 -> peekFromBottom()
                    else -> hangAtSide()
                }
                delay(random.nextLong(4000, 11000))
            }
        }
    }

    /** 待机：在 R1/R7/R8/R9 里随机挑一个，并每 4.5~8.5 秒随机换一个。 */
    private fun playIdle() {
        idleMode = true
        val rows = IDLE_ROWS
        var row = rows[random.nextInt(rows.size)]
        if (rows.size > 1 && row == lastIdleRow) {
            row = rows[(rows.indexOf(row) + 1) % rows.size]
        }
        lastIdleRow = row
        petView.play(row, 6, 5f)
        startIdleSwitcher()
    }

    private fun startIdleSwitcher() {
        if (idleSwitchJob?.isActive == true) return
        idleSwitchJob = scope.launch {
            while (isActive) {
                delay(random.nextLong(4500, 8500))
                if (!idleMode || dragging || sleeping || bubble.isShown || menu.isShown || commandInProgress) {
                    continue
                }
                val candidates = IDLE_ROWS.filter { it != lastIdleRow }
                if (candidates.isEmpty()) continue
                val row = candidates[random.nextInt(candidates.size)]
                lastIdleRow = row
                petView.play(row, 6, 5f)
            }
        }
    }

    private fun randomInYRange(height: Int): Int {
        val yMin = (screenH * 0.12f).toInt()
        val yMax = (screenH * 0.85f).toInt() - height
        return random.nextInt(yMin, yMax.coerceAtLeast(yMin + 1))
    }

    private suspend fun walkToRandomX() {
        val margin = (24 * density).toInt()
        val targetX = random.nextInt(margin, (screenW - params.width - margin).coerceAtLeast(margin + 1))
        val targetY = randomInYRange(params.height)
        walkToX(targetX, targetY)
    }

    private suspend fun walkToX(targetX: Int, targetY: Int = homeY) {
        val goingRight = targetX > params.x
        idleMode = false
        petView.play(if (goingRight) ROW_WALK_RIGHT else ROW_WALK_LEFT, 8, 9f)
        val maxX = (screenW - params.width).coerceAtLeast(0)
        val safeX = targetX.coerceIn(0, maxX)
        while (currentCoroutineContext().isActive && !dragging) {
            val dx = safeX - params.x
            val dy = targetY - params.y
            if (abs(dx) <= 2 && abs(dy) <= 2) break
            // 两轴分别限制步长，避免一个轴到了目标另一个轴还带着它继续走
            val stepX = dx.coerceIn(-6, 6)
            val stepY = dy.coerceIn(-8, 8)
            moveWindow(params.x + stepX, params.y + stepY)
            delay(26)
        }
        if (!dragging) {
            homeY = targetY
            moveWindow(safeX, targetY)
            playIdle()
        }
    }

    /** 走到屏幕下边缘，只露出上半身探头，再爬回来。 */
    private suspend fun peekFromBottom() {
        if (dragging) return
        val peekY = screenH - (params.height * 0.42f).toInt()
        val goingDown = peekY > params.y
        idleMode = false
        petView.play(if (goingDown) ROW_PEEK else ROW_PEEK, 5, 8f)
        val step = if (goingDown) 9 else -9
        while (currentCoroutineContext().isActive && !dragging && abs(peekY - params.y) > 6) {
            moveWindow(params.x, params.y + step)
            delay(20)
        }
        if (dragging) return
        petView.play(ROW_PEEK, 5, 7f, loopValue = false)
        delay(2200)
        if (dragging) return
        // 爬回来
        petView.play(ROW_PEEK, 5, 7f, loopValue = false)
        while (currentCoroutineContext().isActive && !dragging && params.y > homeY + 6) {
            moveWindow(params.x, params.y - 10)
            delay(20)
        }
        if (!dragging) {
            moveWindow(params.x, homeY)
            playIdle()
        }
    }

    /** 走到左/右边缘，半个身子藏在屏幕外，只露头和手，挂一会儿再走开。 */
    private suspend fun hangAtSide() {
        if (dragging) return
        val left = random.nextBoolean()
        val targetX = if (left) -(params.width * 0.52f).toInt() else screenW - (params.width * 0.48f).toInt()
        val goingRight = targetX > params.x
        idleMode = false
        petView.play(if (goingRight) ROW_WALK_RIGHT else ROW_WALK_LEFT, 8, 10f)
        val step = if (goingRight) 9 else -9
        while (currentCoroutineContext().isActive && !dragging && abs(targetX - params.x) > 8) {
            moveWindow(params.x + step, homeY)
            delay(20)
        }
        if (dragging) return
        playIdle()
        delay(random.nextLong(2600, 5200))
        if (dragging) return
        val backX = if (left) (24 * density).toInt() else screenW - params.width - (24 * density).toInt()
        walkToX(backX, randomInYRange(params.height))
    }

    // ------------------------------------------------------------------ 拖动
    private inner class DragTouchListener : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var downRawX = 0f
        private var downRawY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    startX = params.x
                    startY = params.y
                    downRawX = event.rawX
                    downRawY = event.rawY
                    moved = false
                    longPressRunnable?.let { menuHandler.removeCallbacks(it) }
                    longPressRunnable = Runnable {
                        if (!moved) {
                            dragging = false
                            petView.play(ROW_WAVE, 4, 6f, loopValue = false) {
                                if (!sleeping) playIdle()
                            }
                            showMenu()
                        }
                    }.also { menuHandler.postDelayed(it, 600) }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                        longPressRunnable?.let { menuHandler.removeCallbacks(it) }
                    }
                    moveWindow(startX + dx.toInt(), startY + dy.toInt())
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let { menuHandler.removeCallbacks(it) }
                    longPressRunnable = null
                    dragging = false
                    if (menu.isShown) return true
                    if (!moved) {
                        // 招手只播一次，结束后回待机；关掉气泡也不会一直招手
                        petView.play(ROW_WAVE, 4, 6f, loopValue = false) {
                            if (!sleeping) playIdle()
                        }
                        bubble.toggle()
                    } else {
                        homeY = params.y.coerceIn(0, screenH - params.height)
                        playIdle()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun startDiaryLoop() {
        scope.launch {
            delay(60_000)
            while (isActive) {
                runCatching { DiaryWriter.ensureDaily(this@PetService) }
                delay(10 * 60 * 1000L)
            }
        }
    }

    private fun startPeekLoop() {
        scope.launch {
            delay(20_000)
            while (isActive) {
                runCatching { peekTick() }
                delay(30_000)
            }
        }
    }

    /** 视奸：亮屏 + 无障碍可用时才看；高频 App 5 分钟，其它 30 分钟。 */
    private suspend fun peekTick(force: Boolean = false) {
        if (peekInFlight) return
        val accessibility = PetAccessibilityService.instance ?: return
        if (!accessibility.screenOn()) return
        if (bubble.isShown || menu.isShown || sleeping || commandInProgress) return
        val pkg = PetAccessibilityService.lastPackage
        if (pkg.isBlank()) return
        if (!force && pkg == packageName) return
        val interval = if (pkg in HIGH_FREQ_PACKAGES) PEEK_HIGH_MS else PEEK_LOW_MS
        val now = System.currentTimeMillis()
        if (!force && now - lastPeekAt < interval) return
        val snapshot = accessibility.snapshot()
        if (snapshot.lines.isEmpty()) return
        peekInFlight = true
        lastPeekAt = now
        try {
            val screenText = snapshot.lines.take(50).joinToString("\n")
            val appLabel = AppInfoUtil.label(this, pkg)
            android.util.Log.i("PetPeek", "app=$appLabel($pkg) lines=${snapshot.lines.size}")
            val prompt = """
你刚瞄了一眼老爸的手机屏幕。当前前台应用是「${appLabel}」（包名：${pkg}）。
请结合应用名和屏幕内容判断他在干嘛。
屏幕内容：
$screenText

请用一两句话主动搭话：只聊这个应用和屏幕里具体能看到的东西，自然、像随口一说；
不要说"我看了你屏幕/我在监视你"这类话，不要编造你操作了什么，不要长篇大论。
            """.trimIndent()
            val comment = DeepSeekClient.complete(
                this,
                DeepSeekClient.systemPrompt(this),
                prompt,
            )
            if (comment.isNotBlank()) {
                ChatRepo.add(this, fromUser = false, text = comment)
                bubble.showProactive(comment)
            }
        } catch (exc: Exception) {
            android.util.Log.w("PetService", "peek failed", exc)
        } finally {
            peekInFlight = false
        }
    }

    private fun showMenu() {
        if (!::menu.isInitialized) return
        menu.show(params.x, params.y, sleeping) { action ->
            when (action) {
                PetMenuOverlay.Action.HOME -> {
                    bubble.hide()
                    stopSelf()
                }
                PetMenuOverlay.Action.SLEEP -> {
                    sleeping = !sleeping
                    if (sleeping) {
                        idleMode = false
                        petView.play(ROW_SLEEPY, 8, 3f)
                    } else {
                        playIdle()
                    }
                }
                PetMenuOverlay.Action.DRESS -> {
                    Toast.makeText(this, "还没有新衣服，等老爸给我买～", Toast.LENGTH_SHORT).show()
                }
                PetMenuOverlay.Action.BUBBLE -> bubble.show()
                PetMenuOverlay.Action.CLOSE -> Unit
            }
        }
    }

    private fun logCommandError(exc: Exception) {
        android.util.Log.w("PetService", "command failed", exc)
    }

    private suspend fun executeCommand(command: PetCommand) {
        commandInProgress = true
        try {
            val margin = (24 * density).toInt()
            val maxX = (screenW - params.width).coerceAtLeast(margin)
            val yMin = (screenH * 0.12f).toInt()
            val yMax = (screenH * 0.85f).toInt() - params.height
            val centerX = margin + (maxX - margin) / 2
            val centerY = yMin + (yMax - yMin) / 2
            val homeX = screenW - params.width - margin
            val homeY = (screenH * 0.72f).toInt().coerceIn(yMin, yMax.coerceAtLeast(yMin))
            val target = when (command) {
                PetCommand.HOME -> homeX to homeY
                PetCommand.LEFT -> margin to params.y
                PetCommand.RIGHT -> maxX to params.y
                PetCommand.TOP -> params.x to yMin
                PetCommand.BOTTOM -> params.x to yMax
                PetCommand.CENTER -> centerX to centerY
                PetCommand.TOP_LEFT -> margin to yMin
                PetCommand.TOP_RIGHT -> maxX to yMin
                PetCommand.BOTTOM_LEFT -> margin to yMax
                PetCommand.BOTTOM_RIGHT -> maxX to yMax
            }
            walkToX(target.first, target.second)
        } finally {
            commandInProgress = false
        }
    }

    private fun openChat() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    // ------------------------------------------------------------------ 通知
    private fun buildNotification(): Notification {
        val channelId = "pet_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(channelId, "桌宠常驻", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("鲸鲸在桌面上")
            .setContentText("拖一拖她，或者点一下回来聊天")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.dsdaughter.pet.STOP"
        const val ACTION_SHOW_BUBBLE = "com.dsdaughter.pet.SHOW_BUBBLE"
        const val ACTION_SHOW_MENU = "com.dsdaughter.pet.SHOW_MENU"
        const val ACTION_PEEK = "com.dsdaughter.pet.PEEK"
        private const val PEEK_HIGH_MS = 5 * 60 * 1000L
        private const val PEEK_LOW_MS = 30 * 60 * 1000L
        private val HIGH_FREQ_PACKAGES = setOf(
            "tv.danmaku.bili",
            "com.tencent.mobileqq",
            "com.tencent.mm",
            "com.quark.browser",
            "com.quark.mobile",
            "com.sankuai.meituan",
            "com.taobao.taobao",
            "com.taobao.idlefish",
        )
        private const val ROW_SLEEPY = 5 // R6 半闭眼，最适合困倦待机
        // 四种待机动作：R1 / R7 / R8 / R9，随机切换
        private val IDLE_ROWS = intArrayOf(0, 6, 7, 8)
        const val NOTIFICATION_ID = 4101

        // 精灵表行号（0-based）：R1 待机 / R2 右走 / R3 左走 / R4 招手 / R5 探头
        private const val ROW_IDLE = 0
        private const val ROW_WALK_RIGHT = 1
        private const val ROW_WALK_LEFT = 2
        private const val ROW_WAVE = 3
        private const val ROW_PEEK = 4

        @Volatile
        var running: Boolean = false
            private set
    }
}
