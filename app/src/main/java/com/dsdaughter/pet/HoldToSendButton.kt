package com.dsdaughter.pet

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

fun vibrate(context: Context, millis: Long) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(millis)
    }
}

/** 防误触发送键：按住满 1.5 秒才发送，按下即震动，进度条走满再震动一次。 */
@Composable
fun HoldToSendButton(enabled: Boolean, onSend: () -> Unit) {
    val context = LocalContext.current
    var pressed by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(pressed) {
        if (!pressed) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        vibrate(context, 25)
        progress.animateTo(1f, animationSpec = tween(1500, easing = LinearEasing))
        delay(10)
        vibrate(context, 70)
    }

    val background = if (enabled) Color(0xFF2E5A88) else Color(0xFF2A2F3A)
    Box(
        modifier = Modifier
            .width(88.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(background)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        val startedAt = System.currentTimeMillis()
                        pressed = true
                        try {
                            awaitRelease()
                        } finally {
                            pressed = false
                        }
                        val held = System.currentTimeMillis() - startedAt
                        if (held >= 1500) {
                            vibrate(context, 40)
                            onSend()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            Text(
                text = if (pressed) "按住…" else "发送",
                color = if (enabled) Color(0xFFE7E9EF) else Color(0xFF6F7789),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 14.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(if (pressed) Color(0xFF9ECBFF).copy(alpha = 0.9f) else Color.Transparent),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                        .height(3.dp)
                        .background(Color(0xFF9ECBFF)),
                )
            }
        }
    }
}
