package com.dsdaughter.pet

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo

/** 无障碍状态：已连接（能干活） / 已授权但未连接 / 未开启。 */
object AccessibilityUtil {
    enum class Status { CONNECTED, ENABLED_NOT_CONNECTED, OFF }

    fun status(context: Context): Status {
        if (PetAccessibilityService.isEnabled) return Status.CONNECTED
        return if (isEnabledInSettings(context)) {
            Status.ENABLED_NOT_CONNECTED
        } else {
            Status.OFF
        }
    }

    fun isEnabledInSettings(context: Context): Boolean {
        // 1) 系统 AccessibilityManager（最准，不需要读 secure settings）
        runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val services = manager?.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
            )
            if (services != null) {
                if (services.any { info ->
                        val name = info.resolveInfo?.serviceInfo?.name ?: ""
                        val pkg = info.resolveInfo?.serviceInfo?.packageName ?: ""
                        pkg == context.packageName && name.endsWith("PetAccessibilityService")
                    }
                ) return true
            }
        }
        // 2) secure settings 兜底
        return runCatching {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return@runCatching false
            enabled.contains("${context.packageName}/.PetAccessibilityService") ||
                enabled.contains("${context.packageName}/${PetAccessibilityService::class.java.name}")
        }.getOrDefault(false)
    }

    fun describe(context: Context): String = when (status(context)) {
        Status.CONNECTED -> "无障碍：已连接（能看屏幕、能帮你点）"
        Status.ENABLED_NOT_CONNECTED -> "无障碍：已授权，但服务没连上（重启手机或去设置里关再开一次）"
        Status.OFF -> "无障碍：未开启"
    }
}
