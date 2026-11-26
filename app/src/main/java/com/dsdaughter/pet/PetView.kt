package com.dsdaughter.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View

/** 精灵表动画视图：8 列 × 9 行，单帧 192×208。 */
class PetView(context: Context) : View(context) {
    var sheet: Bitmap? = null
        set(value) {
            field = value
            if (value != null) {
                frameW = value.width / COLS
                frameH = value.height / ROWS
            }
        }

    private var row = 0
    private var startCol = 0
    private var frameCount = 6
    private var fps = 6f
    private var loop = true
    private var frameW = 192
    private var frameH = 208
    private var startedAt = 0L
    private var lastDrawnFrame = -1
    private var onEnd: (() -> Unit)? = null

    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
    }
    private val src = Rect()
    private val dst = Rect()

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            if (isAttachedToWindow) postDelayed(this, 16)
        }
    }

    fun play(
        rowIndex: Int,
        count: Int,
        fpsValue: Float = 8f,
        startColumn: Int = 0,
        loopValue: Boolean = true,
        onEnded: (() -> Unit)? = null,
    ) {
        row = rowIndex
        startCol = startColumn
        frameCount = count.coerceAtLeast(1)
        fps = fpsValue
        loop = loopValue
        onEnd = onEnded
        startedAt = SystemClock.uptimeMillis()
        lastDrawnFrame = -1
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = sheet ?: return
        val elapsed = SystemClock.uptimeMillis() - startedAt
        var frame = ((elapsed / 1000f) * fps).toInt()
        if (loop) {
            frame %= frameCount
            if (frame != lastDrawnFrame) lastDrawnFrame = frame
        } else if (frame >= frameCount) {
            frame = frameCount - 1
            if (lastDrawnFrame != -2) {
                lastDrawnFrame = -2
                onEnd?.invoke()
                onEnd = null
            }
        }
        val col = startCol + frame
        src.set(col * frameW, row * frameH, (col + 1) * frameW, (row + 1) * frameH)
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, src, dst, paint)
    }

    companion object {
        const val COLS = 8
        const val ROWS = 9
    }
}
