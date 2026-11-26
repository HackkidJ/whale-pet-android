package com.dsdaughter.pet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 日记本地存储（SharedPreferences JSON），机制对齐 Mac 端 behavior.db 的 diary 表。 */
object DiaryStore {
    data class Diary(val day: String, val content: String, val createdAt: Long)

    private const val PREFS = "pet"
    private const val KEY = "diaries"
    private const val MAX = 90

    fun load(context: Context): List<Diary> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Diary(
                    day = item.optString("day"),
                    content = item.optString("content"),
                    createdAt = item.optLong("ts"),
                )
            }.sortedByDescending { it.day }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, day: String, content: String) {
        val diaries = load(context).filterNot { it.day == day }
        val updated = (diaries + Diary(day, content, System.currentTimeMillis()))
            .sortedByDescending { it.day }
            .take(MAX)
        val array = JSONArray()
        updated.forEach {
            array.put(
                JSONObject().put("day", it.day).put("content", it.content).put("ts", it.createdAt),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    fun recent(context: Context, limit: Int = 3): List<Diary> = load(context).take(limit)
}
