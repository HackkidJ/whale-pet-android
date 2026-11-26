package com.dsdaughter.pet

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 独立截图能力：截屏 → 存相册 → 可作为图片分享。 */
object ScreenCapture {
    suspend fun capture(service: PetAccessibilityService): Bitmap? = service.captureScreenshot()

    fun save(context: Context, bitmap: Bitmap): Uri? {
        val name = "whale_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
            .format(Date()) + ".jpg"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/鲸鲸")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        runCatching {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
        }.onFailure { return null }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            runCatching { resolver.update(uri, values, null, null) }
        }
        return uri
    }

    fun share(context: Context, uri: Uri, packageName: String?): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("screenshot", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!packageName.isNullOrBlank()) setPackage(packageName)
        }
        return runCatching {
            if (packageName.isNullOrBlank()) {
                context.startActivity(
                    Intent.createChooser(intent, "发送截图").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } else {
                context.startActivity(intent)
            }
            true
        }.getOrDefault(false)
    }
}
