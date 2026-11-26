package com.dsdaughter.pet

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.PowerManager
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 鲸鲸的无障碍服务：读屏 + 模拟点击/输入/滑动。 */
class PetAccessibilityService : AccessibilityService() {
    data class Node(
        val text: String,
        val desc: String,
        val clickable: Boolean,
        val editable: Boolean,
        val focused: Boolean,
        val bounds: Rect,
    )

    data class Snapshot(
        val packageName: String,
        val lines: List<String>,
        val nodes: List<Node>,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log("无障碍服务已连接")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && pkg != packageName) {
            lastPackage = pkg
            lastPackageAt = System.currentTimeMillis()
        }
    }

    @Volatile
    var lastTapX: Int = -1
        private set

    @Volatile
    var lastTapY: Int = -1
        private set

    fun setClipboardText(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("whale", text))
    }

    /** 无障碍读不到页面时的截图兜底（Android 11+，accessibilityService 配置需 canTakeScreenshot）。 */
    suspend fun captureScreenshot(): Bitmap? = suspendCancellableCoroutine { continuation ->
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    runCatching { buffer.close() }
                    continuation.resume(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    continuation.resume(null)
                }
            },
        )
    }

    fun longPressXY(x: Int, y: Int): Boolean {
        lastTapX = x
        lastTapY = y
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 850))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 把文字放剪贴板，并在最近一次点击的位置长按，唤起"粘贴"菜单。 */
    fun preparePaste(text: String): Boolean {
        setClipboardText(text)
        if (lastTapX < 0 || lastTapY < 0) return false
        return longPressXY(lastTapX, lastTapY)
    }

    fun screenOn(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    fun snapshot(): Snapshot {
        val root = rootInActiveWindow ?: return Snapshot(lastPackage, emptyList(), emptyList())
        val nodes = mutableListOf<Node>()
        val lines = mutableListOf<String>()
        collect(root, nodes, lines, depth = 0)
        val pkg = root.packageName?.toString().orEmpty().ifBlank { lastPackage }
        return Snapshot(pkg, lines.take(120), nodes.take(120))
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        nodes: MutableList<Node>,
        lines: MutableList<String>,
        depth: Int,
    ) {
        if (depth > 18 || nodes.size > 260) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val clickable = node.isClickable
        val editable = node.isEditable
        val focused = node.isFocused
        if (text.isNotEmpty() || desc.isNotEmpty() || clickable || editable) {
            if (node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    nodes.add(
                        Node(
                            text = text.take(80),
                            desc = desc.take(80),
                            clickable = clickable,
                            editable = editable,
                            focused = focused,
                            bounds = rect,
                        ),
                    )
                    val flags = buildString {
                        if (clickable) append("可点 ")
                        if (editable) append("可输入 ")
                        if (focused) append("已聚焦 ")
                    }
                    lines.add("[${nodes.size}] $flags${text.ifBlank { desc }}".trim())
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collect(child, nodes, lines, depth + 1)
        }
    }

    /** 点「始终允许打开」左边的复选框；找不到 checkbox 节点就点文字左侧。 */
    fun tapCheckBoxNearText(query: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val textNode = findNode(root, query) ?: return false
        var parent = textNode.parent
        repeat(4) {
            val p = parent ?: return@repeat
            for (i in 0 until p.childCount) {
                val child = p.getChild(i) ?: continue
                val cls = child.className?.toString() ?: ""
                if (cls.contains("CheckBox") || child.isCheckable) {
                    if (child.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    return tapCenter(child)
                }
            }
            parent = p.parent
        }
        val rect = Rect()
        textNode.getBoundsInScreen(rect)
        val offset = (28 * resources.displayMetrics.density).toInt()
        return tapXY(rect.left - offset, rect.centerY())
    }

    fun tapTextExact(query: String): Boolean {
        val target = query.trim()
        if (target.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val found = findNodeExact(root, target) ?: return false
        var clickable: AccessibilityNodeInfo? = found
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tapCenter(found)
    }

    private fun findNodeExact(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 300) {
            val node = queue.removeFirst()
            visited++
            val text = (node.text?.toString() ?: "").trim()
            val desc = (node.contentDescription?.toString() ?: "").trim()
            if (node.isVisibleToUser && (text == query || desc == query)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    fun tapText(query: String): Boolean {
        val target = query.trim()
        if (target.isEmpty()) return false
        val root = rootInActiveWindow ?: return false
        val found = findNode(root, target) ?: return false
        var clickable: AccessibilityNodeInfo? = found
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tapCenter(found)
    }

    private fun findNode(root: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 300) {
            val node = queue.removeFirst()
            visited++
            val text = (node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")
            if (node.isVisibleToUser && text.contains(query, ignoreCase = true)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    fun tapCenter(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return false
        return tapXY(rect.centerX(), rect.centerY())
    }

    fun tapXY(x: Int, y: Int): Boolean {
        lastTapX = x
        lastTapY = y
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findEditable(root) ?: return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text,
            )
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // 有些输入框只认粘贴
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("whale", text))
        if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        return false
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var fallback: AccessibilityNodeInfo? = null
        var visited = 0
        while (queue.isNotEmpty() && visited < 300) {
            val node = queue.removeFirst()
            visited++
            if (node.isEditable && node.isVisibleToUser) {
                if (node.isFocused) return node
                fallback = node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return fallback
    }

    fun swipeUp(): Boolean = swipe(0.5f, 0.75f, 0.5f, 0.35f)
    fun swipeDown(): Boolean = swipe(0.5f, 0.35f, 0.5f, 0.75f)
    fun swipeLeft(): Boolean = swipe(0.8f, 0.5f, 0.2f, 0.5f)
    fun swipeRight(): Boolean = swipe(0.2f, 0.5f, 0.8f, 0.5f)

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val metrics = resources.displayMetrics
        val path = Path().apply {
            moveTo(metrics.widthPixels * x1, metrics.heightPixels * y1)
            lineTo(metrics.widthPixels * x2, metrics.heightPixels * y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 320))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    private fun log(message: String) {
        android.util.Log.i("PetA11y", message)
    }

    companion object {
        @Volatile
        var instance: PetAccessibilityService? = null
            private set

        @Volatile
        var lastPackage: String = ""

        @Volatile
        var lastPackageAt: Long = 0L

        val isEnabled: Boolean get() = instance != null
    }
}
