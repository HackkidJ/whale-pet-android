package com.dsdaughter.pet

import kotlinx.coroutines.delay

/** 截图/读屏前临时隐藏鲸鲸自己的悬浮窗，避免遮挡模型判断。 */
object OverlayHider {
    data class Hider(val hide: () -> Unit, val show: () -> Unit)

    private val hiders = mutableListOf<Hider>()

    fun register(hider: Hider) {
        synchronized(hiders) { hiders.add(hider) }
    }

    fun unregister(hider: Hider) {
        synchronized(hiders) { hiders.remove(hider) }
    }

    fun hideAll() {
        synchronized(hiders) { hiders.toList() }.forEach { runCatching { it.hide() } }
    }

    fun showAll() {
        synchronized(hiders) { hiders.toList() }.reversed().forEach { runCatching { it.show() } }
    }

    suspend fun <T> withHidden(block: suspend () -> T): T {
        hideAll()
        try {
            delay(180)
            return block()
        } finally {
            showAll()
        }
    }
}
