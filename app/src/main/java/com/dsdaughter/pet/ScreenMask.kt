package com.dsdaughter.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/** 给视觉模型的截图：遮掉状态栏/导航栏，避免顶部通知图标干扰判断（坐标不变）。 */
object ScreenMask {
    fun forModel(context: Context, bitmap: Bitmap): Bitmap {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val paint = Paint().apply { color = Color.BLACK }
        val status = dimen(context, "status_bar_height").coerceAtLeast(60)
        val nav = dimen(context, "navigation_bar_height").coerceAtLeast(40)
        canvas.drawRect(0f, 0f, copy.width.toFloat(), status.toFloat(), paint)
        canvas.drawRect(
            0f,
            (copy.height - nav).toFloat(),
            copy.width.toFloat(),
            copy.height.toFloat(),
            paint,
        )
        return copy
    }

    private fun dimen(context: Context, name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
