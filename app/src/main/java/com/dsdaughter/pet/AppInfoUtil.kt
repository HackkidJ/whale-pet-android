package com.dsdaughter.pet

import android.content.Context

/** 把包名解析成用户看到的应用名，供 Prompt/日志使用。 */
object AppInfoUtil {
    private val cache = mutableMapOf<String, String>()

    fun label(context: Context, packageName: String): String {
        if (packageName.isBlank()) return "未知"
        cache[packageName]?.let { return it }
        val label = runCatching {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrDefault(packageName)
        cache[packageName] = label
        return label
    }

    fun describe(context: Context, packageName: String): String {
        val label = label(context, packageName)
        return if (label == packageName) packageName else "$label（$packageName）"
    }
}
