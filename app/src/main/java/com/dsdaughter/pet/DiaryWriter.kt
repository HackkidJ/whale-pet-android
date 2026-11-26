package com.dsdaughter.pet

import android.content.Context
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** 日记机制对齐 Mac 端 behavior/diary.py：凌晨 03:30 写前一天，第一人称，最近 3 篇注入 Prompt。 */
object DiaryWriter {
    private const val DIARY_AT = "03:30"

    private val promptTemplate = """
你是鲸鲸。请根据下面这一天的聊天记录，用第一人称写一篇日记（100~300 字）。

要求：
- 像真的日记：只写你今天的感受、在意的小事、对对方的观察；不要逐条复述聊天。
- 可以有小情绪、小吐槽；不要编造聊天里没发生的物理事件（比如你没做过的事）。
- 不要写日期标题、不要用 Markdown、不要写"今天"以外的总结腔。

聊天记录：
%s
    """.trimIndent()

    /** 写某一天的日记；当天聊天少于 6 轮就不写。返回写了什么 / null。 */
    suspend fun writeFor(context: Context, day: LocalDate, force: Boolean = false): String? {
        val zone = ZoneId.systemDefault()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val turns = ChatRepo.allMessages(context).filter { it.ts in start until end }
        if (!force && turns.size < 6) return null
        if (turns.isEmpty()) return null
        val transcript = turns.takeLast(80)
            .joinToString("\n") { "${if (it.fromUser) "对方" else "我"}：${it.text.take(200)}" }
        val prompt = promptTemplate.format(transcript)
        val content = try {
            DeepSeekClient.complete(
                context,
                "你写日记时不用讨好任何人，诚实、简短、像自己。",
                prompt,
            ).replace("\n", " ").trim()
        } catch (_: Exception) {
            return null
        }
        if (content.isBlank()) return null
        DiaryStore.add(context, day.toString(), content)
        return content
    }

    /** 每天 03:30 之后补写前一天；已经写过就跳过。 */
    suspend fun ensureDaily(context: Context) {
        val now = java.time.LocalDateTime.now()
        val due = LocalTime.parse(DIARY_AT)
        val yesterday = now.toLocalDate().minusDays(1)
        if (now.toLocalTime() < due) return
        if (DiaryStore.load(context).any { it.day == yesterday.toString() }) return
        writeFor(context, yesterday)
    }
}
