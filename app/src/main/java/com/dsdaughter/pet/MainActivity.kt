package com.dsdaughter.pet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GuardService.start(this)
        setContent { ChatScreen(this) }
    }

    fun maybeRequestBatteryExemption() {
        val power = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (power.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("pet", MODE_PRIVATE)
        if (prefs.getBoolean("batteryAsked", false)) return
        prefs.edit().putBoolean("batteryAsked", true).apply()
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }
}

private fun startPet(activity: ComponentActivity) {
    if (!Settings.canDrawOverlays(activity)) {
        activity.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}"),
            ),
        )
        return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }
    activity.startForegroundService(Intent(activity, PetService::class.java))
}

@Composable
private fun ChatScreen(activity: ComponentActivity) {
    val prefs = remember { activity.getSharedPreferences("pet", Context.MODE_PRIVATE) }
    var apiKey by remember {
        mutableStateOf(prefs.getString("apiKey", null) ?: BuildConfig.DEEPSEEK_API_KEY)
    }
    var showSettings by remember { mutableStateOf(false) }
    var showDiary by remember { mutableStateOf(false) }
    var showConversations by remember { mutableStateOf(false) }
    var showReminders by remember { mutableStateOf(false) }
    var reminders by remember { mutableStateOf(emptyList<ReminderStore.Reminder>()) }
    var diaries by remember { mutableStateOf(emptyList<DiaryStore.Diary>()) }
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val messages by ChatRepo.messages.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    fun doSend() {
        if (loading || input.isBlank()) return
        val text = input.trim()
        input = ""
        error = ""
        ChatRepo.add(activity, fromUser = true, text = text)
        // 先识别"走动指令"，不走大模型
        val command = PetCommandParser.parse(text)
        if (command != null) {
            if (command == PetCommand.HOME) {
                // 回家 = 收起来，不再悬浮
                if (PetService.running) {
                    activity.stopService(Intent(activity, PetService::class.java))
                }
            } else {
                if (!PetService.running) startPet(activity)
                PetCommandBus.send(command)
            }
            ChatRepo.add(activity, fromUser = false, text = command.reply)
            return
        }
        val reminder = ReminderParser.parse(text)
        if (reminder != null) {
            ReminderScheduler.schedule(activity, reminder.message, reminder.atMillis)
            ChatRepo.add(
                activity,
                fromUser = false,
                text = "好，${ReminderScheduler.label(reminder.atMillis)}提醒你：${reminder.message}",
            )
            return
        }
        if (PhoneIntent.isReminderRequest(text)) {
            ChatRepo.add(
                activity,
                fromUser = false,
                text = "这个提醒我没排上：说具体一点，比如「1小时后提醒我吃饭」「30分钟后提醒我喝水」。",
            )
            return
        }
        if (PhoneIntent.isBalanceRequest(text)) {
            ChatRepo.add(activity, fromUser = false, text = "我查一下余额…")
            scope.launch {
                val answer = DeepSeekClient.fetchBalance(activity)
                ChatRepo.add(activity, fromUser = false, text = answer)
            }
            return
        }
        if (PhoneIntent.isLookRequest(text)) {
            ChatRepo.add(activity, fromUser = false, text = "我看看你屏幕…")
            scope.launch {
                val answer = ScreenLookup.answer(activity, text)
                ChatRepo.add(activity, fromUser = false, text = answer)
            }
            return
        }
        if (PhoneIntent.isTask(text)) {
            if (PetAccessibilityService.instance == null) {
                val hint = when (AccessibilityUtil.status(activity)) {
                    AccessibilityUtil.Status.ENABLED_NOT_CONNECTED ->
                        "无障碍已经授权了，但服务没连上：重启一下手机，或者去设置里关掉再开一次。"
                    else -> "先开一下「鲸鲸无障碍」，我才能看屏幕帮你操作。"
                }
                ChatRepo.add(activity, fromUser = false, text = hint)
                return
            }
            ChatRepo.add(activity, fromUser = false, text = "我看看屏幕，稍等…")
            PhoneOperator.start(
                activity,
                text,
                onDone = { report -> ChatRepo.add(activity, fromUser = false, text = report) },
            )
            return
        }
        if (apiKey.isBlank()) {
            error = "还没填 API Key"
            return
        }
        loading = true
        scope.launch {
            try {
                val reply = DeepSeekClient.ask(activity, ChatRepo.messages.value)
                ChatRepo.add(activity, fromUser = false, text = reply)
            } catch (e: Exception) {
                error = "发不出去：${e.message ?: e::class.simpleName}"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        ChatRepo.ensureLoaded(activity)
        runCatching { DiaryWriter.ensureDaily(activity) }
        (activity as? MainActivity)?.maybeRequestBatteryExemption()
    }
    LaunchedEffect(showDiary) {
        if (showDiary) diaries = DiaryStore.load(activity)
    }
    LaunchedEffect(showReminders) {
        if (showReminders) {
            reminders = ReminderStore.load(activity)
            while (true) {
                delay(1500)
                reminders = ReminderStore.load(activity)
            }
        }
    }

    var petRunning by remember { mutableStateOf(PetService.running) }
    var a11yStatus by remember { mutableStateOf(AccessibilityUtil.status(activity)) }
    val conversations by ChatRepo.conversations.collectAsState()
    val operating by PhoneOperator.running.collectAsState()
    LaunchedEffect(Unit) {
        while (true) {
            petRunning = PetService.running
            a11yStatus = AccessibilityUtil.status(activity)
            delay(800)
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF14161C)) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF191C23))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.avatar),
                        contentDescription = "鲸鲸头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(34.dp).clip(CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("鲸鲸", color = Color(0xFFE7E9EF), fontSize = 18.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { showConversations = true }) {
                        Text("对话", color = Color(0xFF9ECBFF))
                    }
                    TextButton(onClick = { showDiary = true }) {
                        Text("日记", color = Color(0xFF9ECBFF))
                    }
                    TextButton(onClick = { showReminders = true }) {
                        Text("提醒", color = Color(0xFF9ECBFF))
                    }
                    TextButton(onClick = { showSettings = true }) {
                        Text("设置", color = Color(0xFF9ECBFF))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (petRunning) "鲸鲸正在桌面上散步" else "让鲸鲸到桌面上玩",
                        color = Color(0xFFAAB2C5),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = {
                            if (petRunning) {
                                activity.stopService(Intent(activity, PetService::class.java))
                            } else {
                                startPet(activity)
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(if (petRunning) "收起来" else "出来玩")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        AccessibilityUtil.describe(activity),
                        color = if (a11yStatus == AccessibilityUtil.Status.CONNECTED) {
                            Color(0xFFAAB2C5)
                        } else {
                            Color(0xFFFFB4AB)
                        },
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (a11yStatus != AccessibilityUtil.Status.CONNECTED) {
                        TextButton(
                            onClick = {
                                activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                        ) {
                            Text(
                                if (a11yStatus == AccessibilityUtil.Status.ENABLED_NOT_CONNECTED) {
                                    "去处理"
                                } else {
                                    "去开启"
                                },
                                color = Color(0xFF9ECBFF),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }

                if (operating) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "鲸鲸正在操作手机…${PhoneOperator.step.value.take(20)}",
                            color = Color(0xFF7FD4FF),
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { PhoneOperator.stop() }) {
                            Text("停止操作", color = Color(0xFFFFB4AB), fontSize = 12.sp)
                        }
                    }
                }

                if (apiKey.isBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1F1F)),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text("还没有 API Key", color = Color(0xFFFFB4AB))
                            Spacer(Modifier.height(4.dp))
                            Text("点右上角「设置」填入 DeepSeek API Key 就能聊天。",
                                color = Color(0xFFC9CFDB), fontSize = 13.sp)
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages) { msg -> Bubble(msg) }
                }

                if (error.isNotBlank()) {
                    Text(
                        error,
                        color = Color(0xFFFFB4AB),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF191C23))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("说点什么…", color = Color(0xFF6F7789)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(10.dp))
                    HoldToSendButton(
                        enabled = !loading && input.isNotBlank(),
                        onSend = { doSend() },
                    )
                }
            }
        }
    }

    if (showConversations) {
        Dialog(onDismissRequest = { showConversations = false }) {
            Surface(
                color = Color(0xFF191C23),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.72f),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("对话", color = Color(0xFF9ECBFF), fontSize = 17.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            ChatRepo.newConversation(activity)
                            showConversations = false
                        }) { Text("新对话", color = Color(0xFF9ECBFF)) }
                        TextButton(onClick = { showConversations = false }) {
                            Text("关闭", color = Color(0xFF8B93A7))
                        }
                    }
                    Text(
                        "记忆、人格、日记是同一份；对话只是分开看。",
                        color = Color(0xFF8B93A7),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(conversations, key = { it.id }) { conv ->
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (conv.id == ChatRepo.currentId.value) {
                                        Color(0xFF2A3550)
                                    } else {
                                        Color(0xFF232833)
                                    },
                                ),
                                modifier = Modifier.fillMaxWidth().clickable {
                                    ChatRepo.switchTo(activity, conv.id)
                                    showConversations = false
                                },
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            conv.title(),
                                            color = Color(0xFFE7E9EF),
                                            fontSize = 14.sp,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text(
                                            conv.lastTimeLabel(),
                                            color = Color(0xFF8B93A7),
                                            fontSize = 11.sp,
                                        )
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        conv.lastPreview(),
                                        color = Color(0xFF9AA3B5),
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDiary) {
        Dialog(onDismissRequest = { showDiary = false }) {
            Surface(
                color = Color(0xFF191C23),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.74f),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("鲸鲸的日记", color = Color(0xFF9ECBFF), fontSize = 17.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { showDiary = false }) {
                            Text("关闭", color = Color(0xFF8B93A7))
                        }
                    }
                    if (diaries.isEmpty()) {
                        Text(
                            "还没有日记。每天凌晨 03:30 会自动写前一天的；也可以现在让她补一篇。",
                            color = Color(0xFF8B93A7),
                            fontSize = 13.sp,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(diaries) { diary ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF232833)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(diary.day, color = Color(0xFF9ECBFF), fontSize = 13.sp)
                                    Spacer(Modifier.height(6.dp))
                                    Text(diary.content, color = Color(0xFFE7E9EF), fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    DiaryWriter.writeFor(
                                        activity, LocalDate.now().minusDays(1), force = false,
                                    )
                                }
                                diaries = DiaryStore.load(activity)
                            }
                        }) { Text("补写昨天", color = Color(0xFF9ECBFF)) }
                        TextButton(onClick = {
                            scope.launch {
                                runCatching {
                                    DiaryWriter.writeFor(activity, LocalDate.now(), force = true)
                                }
                                diaries = DiaryStore.load(activity)
                            }
                        }) { Text("写今天的", color = Color(0xFF9ECBFF)) }
                    }
                }
            }
        }
    }

    if (showReminders) {
        Dialog(onDismissRequest = { showReminders = false }) {
            Surface(
                color = Color(0xFF191C23),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.6f),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("提醒（时刻表）", color = Color(0xFF9ECBFF), fontSize = 17.sp,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { showReminders = false }) {
                            Text("关闭", color = Color(0xFF8B93A7))
                        }
                    }
                    Text(
                        "直接对我说：\"25分钟后提醒我喝水\" / \"下午3点告诉我开会\" / \"我要在25分钟内写代码，到点提醒我\"",
                        color = Color(0xFF8B93A7),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    if (reminders.isEmpty()) {
                        Text("还没有提醒。", color = Color(0xFF8B93A7), fontSize = 13.sp)
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(reminders, key = { it.id }) { reminder ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF232833)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            ReminderScheduler.label(reminder.atMillis),
                                            color = Color(0xFF9ECBFF),
                                            fontSize = 13.sp,
                                        )
                                        Text(reminder.message, color = Color(0xFFE7E9EF), fontSize = 14.sp)
                                    }
                                    TextButton(onClick = {
                                        ReminderScheduler.cancel(activity, reminder.id)
                                        reminders = ReminderStore.load(activity)
                                    }) { Text("取消", color = Color(0xFFFFB4AB), fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        var draft by remember { mutableStateOf(apiKey) }
        var vibrateDraft by remember {
            mutableStateOf(prefs.getInt("vibrateSeconds", 3).toString())
        }
        var bubbleDraft by remember {
            mutableStateOf(prefs.getInt("bubbleSeconds", 0).toString())
        }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("设置") },
            text = {
                Column {
                    Text("DeepSeek API Key", color = Color(0xFFAAB2C5), fontSize = 12.sp)
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text("sk-…") },
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("提醒震动时长（秒，0=不震）", color = Color(0xFFAAB2C5), fontSize = 12.sp)
                    OutlinedTextField(
                        value = vibrateDraft,
                        onValueChange = { vibrateDraft = it.filter(Char::isDigit).take(2) },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("气泡停留时长（秒，0=按字数自动）", color = Color(0xFFAAB2C5), fontSize = 12.sp)
                    OutlinedTextField(
                        value = bubbleDraft,
                        onValueChange = { bubbleDraft = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    apiKey = draft.trim()
                    prefs.edit()
                        .putString("apiKey", apiKey)
                        .putInt("vibrateSeconds", vibrateDraft.toIntOrNull()?.coerceIn(0, 30) ?: 3)
                        .putInt("bubbleSeconds", bubbleDraft.toIntOrNull()?.coerceIn(0, 120) ?: 0)
                        .apply()
                    showSettings = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSettings = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun Bubble(msg: ChatRepo.Msg) {
    val bubbleColor = if (msg.fromUser) Color(0xFF2E5A88) else Color(0xFF232833)
    val alignment = if (msg.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Text(
            msg.text,
            color = Color(0xFFE7E9EF),
            fontSize = 15.sp,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(bubbleColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}
