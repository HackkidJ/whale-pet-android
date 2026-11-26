package com.dsdaughter.pet

/** 判断一句话是不是"让鲸鲸操作手机"的任务。 */
object PhoneIntent {
    private val verbs = listOf(
        // 打开/导航
        "打开", "点开", "点击", "点一下", "进入", "回到", "返回", "滑动", "滑到", "切换",
        // 搜索/输入
        "搜索", "搜一下", "搜个", "查一下", "输入", "填一下", "粘贴",
        // 消息/社交
        "发消息", "发个消息", "发一句", "发条", "发给", "发送", "发微信", "发qq",
        "告诉", "转告", "回复", "联系", "语音", "打电话", "打给", "邀请",
        // 截图/分享
        "截图", "截屏", "截个屏", "截一张", "屏幕截图", "发截图", "分享截图", "保存截图",
        // 媒体/系统操作
        "播放", "暂停", "调高", "调低", "调到", "设置一下", "打开通知", "点赞", "关注",
        // 泛请求
        "帮我弄", "帮我点", "帮我打开", "帮我搜", "帮我找", "帮我发", "帮我把",
    )
    private val englishVerbs = listOf(
        "open", "click", "tap", "search", "type", "input", "send", "message", "go to", "play",
    )

    private val lookKeywords = listOf(
        "看看我的屏幕", "看下我的屏幕", "看我的屏幕", "看看屏幕", "看下屏幕", "看一眼屏幕",
        "我在和谁聊天", "我在跟谁聊天", "我和谁聊天", "我在和谁", "我在干嘛", "我在干什么",
        "屏幕上是", "屏幕上是什么", "看看我在", "视奸", "偷看", "瞄一眼", "帮我看看手机",
        "你看我在", "看看我在干嘛",
    )

    private val balanceKeywords = listOf(
        "余额", "还有多少钱", "账户余额", "token余额", "token 余额", "额度", "钱还够", "没钱了",
    )

    private val reminderKeywords = listOf(
        "提醒我", "提醒", "叫我", "番茄钟", "闹钟", "定时", "到点", "时间到了",
    )

    fun isReminderRequest(text: String): Boolean =
        text.length <= 120 && reminderKeywords.any { text.contains(it) }

    fun isBalanceRequest(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty() || value.length > 40) return false
        return balanceKeywords.any { value.contains(it, ignoreCase = true) }
    }

    fun isLookRequest(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty() || value.length > 80) return false
        return lookKeywords.any { value.contains(it) }
    }

    fun isTask(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty() || value.length > 120) return false
        if (value.startsWith("操作:") || value.startsWith("操作：")) return true
        val lowered = value.lowercase()
        val hasVerb = verbs.any { value.contains(it) } || englishVerbs.any { lowered.contains(it) }
        if (!hasVerb) return false
        // 提到 App 才算明确任务；"给谁发消息"通常带 微信/QQ，允许 App 名或 联系方式
        if (AppCatalog.mentionsApp(value)) return true
        // 发给具体联系人 / 联系人相关，也视为任务
        if (value.contains("发") && (value.contains("给") || value.contains("告诉") || value.contains("回复"))) {
            return true
        }
        return false
    }
}
