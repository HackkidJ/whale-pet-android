package com.dsdaughter.pet

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 统一管理"鲸鲸操作手机"：气泡/App 共用，带视觉特效和停止入口。 */
object PhoneOperator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _step = MutableStateFlow("")
    val step: StateFlow<String> = _step.asStateFlow()
    private var job: Job? = null
    private var overlay: OperationOverlay? = null

    fun start(
        context: Context,
        request: String,
        onProgress: (String) -> Unit = {},
        onDone: (String) -> Unit = {},
    ) {
        if (_running.value) {
            onProgress("我还在操作上一个任务，先点「停止」再试。")
            return
        }
        val appContext = context.applicationContext
        _running.value = true
        _step.value = "准备中…"
        overlay = OperationOverlay(appContext) { stop() }
        overlay?.show()
        job = scope.launch {
            var report = ""
            try {
                report = AccessibilityAgent.run(
                    appContext,
                    request,
                    isCancelled = { !_running.value },
                ) { progress ->
                    _step.value = progress
                    overlay?.updateStep(progress)
                    onProgress(progress)
                }
            } catch (_: CancellationException) {
                report = "好，我停下了，没继续操作。"
            } catch (exc: Exception) {
                report = "操作的时候出错了：${exc.message ?: "未知错误"}"
            } finally {
                _running.value = false
                overlay?.hide()
                overlay = null
                job = null
                onDone(report)
            }
        }
    }

    fun stop() {
        _running.value = false
        job?.cancel()
        job = null
        overlay?.hide()
        overlay = null
    }
}
