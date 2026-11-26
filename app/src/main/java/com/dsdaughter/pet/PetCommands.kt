package com.dsdaughter.pet

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 语音/文字基本指令：让桌宠走到指定区域或回家。 */
enum class PetCommand(val reply: String) {
    HOME("好，我回家啦，想我了再叫我。"),
    LEFT("好，我去左边。"),
    RIGHT("好，我去右边。"),
    TOP("好，我上去一点。"),
    BOTTOM("好，我下来一点。"),
    CENTER("好，去中间。"),
    TOP_LEFT("好，去左上角。"),
    TOP_RIGHT("好，去右上角。"),
    BOTTOM_LEFT("好，去左下角。"),
    BOTTOM_RIGHT("好，去右下角。"),
}

object PetCommandBus {
    private val _commands = MutableSharedFlow<PetCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<PetCommand> = _commands.asSharedFlow()

    fun send(command: PetCommand): Boolean = _commands.tryEmit(command)
}

object PetCommandParser {
    private data class Rule(val pattern: Regex, val command: PetCommand)

    // 顺序很重要：先匹配"回家"和四角，再匹配单个方向
    private val rules = listOf(
        Rule(Regex("回家|回去|回原位|回窝|回老地方|\\bhome\\b", RegexOption.IGNORE_CASE), PetCommand.HOME),
        Rule(Regex("左上"), PetCommand.TOP_LEFT),
        Rule(Regex("右上"), PetCommand.TOP_RIGHT),
        Rule(Regex("左下"), PetCommand.BOTTOM_LEFT),
        Rule(Regex("右下"), PetCommand.BOTTOM_RIGHT),
        Rule(Regex("左边|左面|左侧|往左|向左|去左|到左|靠左|左一点|^左$|\\bleft\\b", RegexOption.IGNORE_CASE), PetCommand.LEFT),
        Rule(Regex("右边|右面|右侧|往右|向右|去右|到右|靠右|右一点|^右$|\\bright\\b", RegexOption.IGNORE_CASE), PetCommand.RIGHT),
        Rule(Regex("上面|上边|上侧|往上|向上|上去|到上|靠上|上一点|高处|^上$|\\bup\\b", RegexOption.IGNORE_CASE), PetCommand.TOP),
        Rule(Regex("下面|下边|下侧|往下|向下|下来|到下|靠下|下一点|底部|^下$|\\bdown\\b", RegexOption.IGNORE_CASE), PetCommand.BOTTOM),
        Rule(Regex("中间|中央|正中间|去中间|到中间|^中$|\\bcenter\\b", RegexOption.IGNORE_CASE), PetCommand.CENTER),
    )

    fun parse(text: String): PetCommand? {
        val value = text.trim()
        if (value.isEmpty() || value.length > 30) return null
        for (rule in rules) {
            if (rule.pattern.containsMatchIn(value)) return rule.command
        }
        return null
    }
}
