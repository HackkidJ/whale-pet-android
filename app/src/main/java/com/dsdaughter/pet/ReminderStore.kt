package com.dsdaughter.pet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 提醒时刻表：SharedPreferences JSON。 */
object ReminderStore {
    data class Reminder(val id: Int, val message: String, val atMillis: Long)

    private const val PREFS = "pet"
    private const val KEY = "reminders"

    fun load(context: Context): List<Reminder> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                Reminder(
                    id = item.optInt("id"),
                    message = item.optString("message"),
                    atMillis = item.optLong("at"),
                )
            }.sortedBy { it.atMillis }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, reminder: Reminder) {
        val list = load(context).filterNot { it.id == reminder.id } + reminder
        save(context, list)
    }

    fun remove(context: Context, id: Int): Reminder? {
        val list = load(context)
        val target = list.firstOrNull { it.id == id }
        save(context, list.filterNot { it.id == id })
        return target
    }

    private fun save(context: Context, list: List<Reminder>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("id", it.id).put("message", it.message).put("at", it.atMillis))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }
}
