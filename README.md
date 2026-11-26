# 鲸鲸 · 安卓桌宠

基于 Kotlin + Jetpack Compose 的悬浮桌宠 App：会聊天、有日记、能看屏幕并帮你操作手机、支持提醒/番茄钟。

> 后端（QQ 机器人 + 记忆/人格 + 视觉）在独立仓库 `whale-daughter-core`。本仓库只包含 Android 端。

## 功能

- 悬浮桌宠：精灵动画（待机/走路/招手/探头/睡觉），可拖动、点按聊天、长按菜单
- 对话：多会话列表（最后对话时间精确到分钟），共享同一份人格/记忆/日记
- 日记：每天 03:30 自动写前一天，最近 3 篇注入对话；App 内可查看/补写
- 提醒/番茄钟：「1小时后提醒我吃饭」「X分钟后提醒我」「我要在X分钟内…」；到点火山语音 + 震动 + 气泡 + 全屏提醒，熄屏也会触发
- 手机操作：无障碍读屏 + 点击/输入/滑动/打开 App；微信/QQ 页面自动改用截图 + DeepSeek 视觉兜底
- 视奸：高频 App（B站/QQ/微信/夸克/美团/淘宝/闲鱼）5 分钟一次，其它 30 分钟；仅亮屏时执行
- 余额：查询 DeepSeek 账户余额
- 当前时间：每轮注入真实时间

## 构建

要求：JDK 17、Android SDK 35、Gradle（仓库自带 wrapper 8.9）

```bash
cp local.properties.example local.properties
# 编辑 local.properties：sdk.dir、DeepSeek Key、火山 TTS Key/音色
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Android 14+ 还需要在手机上手动授权：

1. 无障碍：设置 → 无障碍 → 已下载的应用 → 鲸鲸无障碍 → 打开
2. 悬浮窗：首次点「出来玩」按提示开启「显示在其他应用上层」
3. 通知/全屏通知/闹钟和提醒：应用信息 → 权限/通知里允许
4. 电池优化：允许忽略；OPPO/ColorOS 再开「自启动/允许后台活动」

## 隐私说明

- `local.properties`（含 API Key/TTS Key）不会提交
- `app/src/main/assets/system_prompt.txt` 目前是脱敏示例；真实的人格 + 记忆快照请从后端导出后覆盖它
- 本项目不上传任何聊天记录/日记/通讯录数据

## 代码结构

```
app/src/main/java/com/dsdaughter/pet/
├── MainActivity.kt          # 对话页/设置/日记/提醒/会话列表
├── PetService.kt            # 悬浮桌宠、散步/待机/动画、视奸调度
├── PetView.kt / PetMenuOverlay.kt / BubbleOverlay.kt
├── PetAccessibilityService.kt  # 无障碍读屏/点击/输入/截图
├── AccessibilityAgent.kt / VisionPhoneAgent.kt  # 任务代理 + 视觉兜底
├── PhoneOperator.kt / OperationOverlay.kt       # 操作控制/特效/停止
├── ReminderParser.kt / ReminderStore.kt / ReminderScheduler.kt
├── ReminderReceiver.kt / ReminderService.kt / ReminderActivity.kt
├── VolcTts.kt / DeepSeekClient.kt / ChatRepo.kt / DiaryStore.kt / DiaryWriter.kt
└── GuardService.kt          # 守护前台服务/无障碍掉线提醒
```
