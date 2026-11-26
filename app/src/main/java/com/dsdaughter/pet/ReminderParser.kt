package com.dsdaughter.pet

import java.util.Calendar

/** 解析提醒/番茄钟指令：X分钟后提醒我… / X点告诉我… / 我要在X分钟内… 到点提醒我。 */
object ReminderParser {
    data class Parsed(val message: String, val atMillis: Long)

    private val cnDigits = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10,
    )

    fun parse(text: String, now: Calendar = Calendar.getInstance()): Parsed? {
        val value = text.trim()
        if (value.isEmpty()) return null
        parseHours(value, now)?.let { return it }
        parseMinutes(value, now)?.let { return it }
        parseClock(value, now)?.let { return it }
        return null
    }

    private fun parseHours(text: String, now: Calendar): Parsed? {
        val reminderWord = Regex("(提醒我|提醒|告诉我|叫我|让我)").find(text)
        val tomato = text.contains("番茄钟") || text.contains("工作")
        if (reminderWord == null && !tomato) return null
        val halfHour = Regex("半\\s*个?小时").find(text)
        val hourMatch = Regex("([0-9一二两三四五六七八九十]+)\\s*个?小时").find(text)
        val minutes = if (halfHour != null) {
            30
        } else {
            val raw = hourMatch?.groupValues?.get(1) ?: return null
            (parseNumber(raw) ?: return null) * 60
        }
        if (minutes <= 0) return null
        val message = if (reminderWord != null) {
            text.substring(reminderWord.range.last + 1)
        } else if (tomato) {
            "工作结束"
        } else {
            "时间到了"
        }
        val at = now.clone() as Calendar
        at.add(Calendar.MINUTE, minutes)
        return Parsed(cleanMessage(message), at.timeInMillis)
    }

    private fun parseNumber(raw: String): Int? {
        if (raw.isBlank()) return null
        raw.toIntOrNull()?.let { return it }
        if (raw == "半") return 0 // 半分钟按 0 处理不是我们需要的，交给分钟规则
        var total = 0
        var current = 0
        for (ch in raw) {
            val d = cnDigits[ch] ?: return null
            if (ch == '十') {
                total += (if (current == 0) 1 else current) * 10
                current = 0
            } else {
                current = d
            }
        }
        return total + current
    }

    private fun cleanMessage(raw: String): String {
        val message = raw.trim().trim('，', ',', '。', '！', '!', ' ')
            .replace(Regex("^(去|要|做|记得|帮我|给我)"), "")
            .trim()
        return message.ifBlank { "时间到了" }
    }

    private fun parseMinutes(text: String, now: Calendar): Parsed? {
        val patterns = listOf(
            Regex("(?:过|等|再)\\s*([0-9一二两三四五六七八九十]+)\\s*分钟(?:后|之后)?(?:提醒我|提醒|告诉我|叫我|让我)(.*)$"),
            Regex("([0-9一二两三四五六七八九十]+)\\s*分钟(?:后|之后)(?:提醒我|提醒|告诉我|叫我|让我)(.*)$"),
            Regex("我要在\\s*([0-9一二两三四五六七八九十]+)\\s*分钟(?:内|之内|以内)?\\s*(.*?)(?:[，,]?\\s*(?:到时间|时间到|结束|做完|完了).*(?:提醒我|提醒|告诉我).*)?$"),
            Regex("([0-9一二两三四五六七八九十]+)\\s*分钟(?:内|之内)\\s*(.*?)(?:[，,]?\\s*(?:到时间|时间到).*)$"),
        )
        for ((index, pattern) in patterns.withIndex()) {
            val match = pattern.find(text) ?: continue
            val minutes = parseNumber(match.groupValues[1]) ?: continue
            if (minutes <= 0) continue
            var message = match.groupValues.getOrNull(2).orEmpty()
            if (index >= 2) {
                message = message.substringBefore("到时间").substringBefore("时间到")
            }
            val at = now.clone() as Calendar
            at.add(Calendar.MINUTE, minutes)
            return Parsed(cleanMessage(message), at.timeInMillis)
        }
        return null
    }

    private fun parseClock(text: String, now: Calendar): Parsed? {
        val match = Regex(
            "(上午|早上|中午|下午|晚上)?\\s*(\\d{1,2}|[一二两三四五六七八九十]+)\\s*[点:：]\\s*(\\d{1,2})?\\s*分?\\s*(?:提醒我|提醒|告诉我|叫我|让我)\\s*(.*)$",
        ).find(text) ?: return null
        val period = match.groupValues[1]
        val hour = parseNumber(match.groupValues[2]) ?: return null
        val minute = parseNumber(match.groupValues[3]).let { if (match.groupValues[3].isBlank()) 0 else (it ?: 0) }
        if (hour !in 0..23 || minute !in 0..59) return null
        var hour24 = hour
        if ((period == "下午" || period == "晚上") && hour24 < 12) hour24 += 12
        if (period == "中午" && hour24 < 11) hour24 += 12
        val at = now.clone() as Calendar
        at.set(Calendar.HOUR_OF_DAY, hour24)
        at.set(Calendar.MINUTE, minute)
        at.set(Calendar.SECOND, 0)
        at.set(Calendar.MILLISECOND, 0)
        if (at.timeInMillis <= now.timeInMillis) at.add(Calendar.DAY_OF_MONTH, 1)
        return Parsed(cleanMessage(match.groupValues[4]), at.timeInMillis)
    }
}
