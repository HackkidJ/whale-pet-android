package com.dsdaughter.pet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 多会话聊天记录：像 DeepSeek 一样有对话列表，但鲸鲸的记忆/人格/状态只有一份。 */
object ChatRepo {
    data class Msg(val fromUser: Boolean, val text: String, val ts: Long = System.currentTimeMillis())

    data class Conversation(
        val id: String,
        val messages: List<Msg>,
        val updatedAt: Long,
    ) {
        fun title(): String {
            val firstUser = messages.firstOrNull { it.fromUser }
            return firstUser?.text?.trim()?.take(16) ?: "新对话"
        }

        fun lastPreview(): String = messages.lastOrNull()?.text?.trim()?.take(40) ?: "（空对话）"

        fun lastTimeLabel(): String = formatTime(updatedAt)
    }

    private const val PREFS = "pet"
    private const val KEY = "conversations"
    private const val LEGACY_KEY = "history"
    private const val MAX_CONVERSATIONS = 100

    private val _messages = MutableStateFlow<List<Msg>>(emptyList())
    val messages: StateFlow<List<Msg>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _currentId = MutableStateFlow("")
    val currentId: StateFlow<String> = _currentId.asStateFlow()

    @Volatile
    private var loaded = false

    private fun greeting(): Msg =
        Msg(false, "爸，我在你手机里啦。能看屏幕，也能帮你点 App——直接跟我说要做什么就行。")

    fun formatTime(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ts))

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY, null)
            var list = emptyList<Conversation>()
            var current = ""
            if (!raw.isNullOrBlank()) {
                runCatching {
                    val root = JSONObject(raw)
                    current = root.optString("current")
                    val array = root.optJSONArray("conversations") ?: JSONArray()
                    for (i in 0 until array.length()) {
                        val item = array.getJSONObject(i)
                        val messages = mutableListOf<Msg>()
                        val msgs = item.optJSONArray("messages") ?: JSONArray()
                        for (j in 0 until msgs.length()) {
                            val m = msgs.getJSONObject(j)
                            messages.add(
                                Msg(
                                    fromUser = m.optBoolean("u"),
                                    text = m.optString("t"),
                                    ts = m.optLong("ts"),
                                ),
                            )
                        }
                        list = list + Conversation(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            messages = messages,
                            updatedAt = item.optLong("updatedAt"),
                        )
                    }
                }
            }
            // 迁移旧版单会话 history
            if (list.isEmpty()) {
                val legacy = prefs.getString(LEGACY_KEY, null)
                val migration = mutableListOf<Msg>()
                if (!legacy.isNullOrBlank()) {
                    runCatching {
                        val array = JSONArray(legacy)
                        for (i in 0 until array.length()) {
                            val item = array.getJSONObject(i)
                            migration.add(
                                Msg(
                                    fromUser = item.optBoolean("u"),
                                    text = item.optString("t"),
                                    ts = item.optLong("ts"),
                                ),
                            )
                        }
                    }
                }
                if (migration.isEmpty()) migration.add(greeting())
                list = listOf(
                    Conversation(
                        id = UUID.randomUUID().toString(),
                        messages = migration,
                        updatedAt = migration.lastOrNull()?.ts ?: System.currentTimeMillis(),
                    ),
                )
                current = list.first().id
            }
            if (list.none { it.id == current }) {
                current = list.maxByOrNull { it.updatedAt }?.id ?: ""
            }
            _conversations.value = list.sortedByDescending { it.updatedAt }
            _currentId.value = current
            _messages.value = list.firstOrNull { it.id == current }?.messages ?: emptyList()
            persist(context)
        }
    }

    fun add(context: Context, fromUser: Boolean, text: String) {
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        val msg = Msg(fromUser, text, now)
        val current = _conversations.value.firstOrNull { it.id == _currentId.value }
            ?: return
        val updated = current.copy(messages = current.messages + msg, updatedAt = now)
        _conversations.value = _conversations.value
            .filterNot { it.id == current.id }
            .plus(updated)
            .sortedByDescending { it.updatedAt }
            .take(MAX_CONVERSATIONS)
        _messages.value = updated.messages
        persist(context)
    }

    fun newConversation(context: Context): String {
        ensureLoaded(context)
        val conv = Conversation(
            id = UUID.randomUUID().toString(),
            messages = listOf(greeting()),
            updatedAt = System.currentTimeMillis(),
        )
        _conversations.value = (listOf(conv) + _conversations.value).take(MAX_CONVERSATIONS)
        _currentId.value = conv.id
        _messages.value = conv.messages
        persist(context)
        return conv.id
    }

    fun switchTo(context: Context, id: String) {
        ensureLoaded(context)
        val conv = _conversations.value.firstOrNull { it.id == id } ?: return
        _currentId.value = id
        _messages.value = conv.messages
        persist(context)
    }

    /** 所有会话的消息按时间合并：日记/记忆用同一条流。 */
    fun allMessages(context: Context): List<Msg> {
        ensureLoaded(context)
        return _conversations.value.flatMap { it.messages }.sortedBy { it.ts }
    }

    private fun persist(context: Context) {
        val conversations = JSONArray()
        _conversations.value.forEach { conv ->
            val messages = JSONArray()
            conv.messages.forEach { msg ->
                messages.put(
                    JSONObject().put("u", msg.fromUser).put("t", msg.text).put("ts", msg.ts),
                )
            }
            conversations.put(
                JSONObject()
                    .put("id", conv.id)
                    .put("updatedAt", conv.updatedAt)
                    .put("messages", messages),
            )
        }
        val root = JSONObject()
            .put("current", _currentId.value)
            .put("conversations", conversations)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, root.toString()).apply()
    }
}
