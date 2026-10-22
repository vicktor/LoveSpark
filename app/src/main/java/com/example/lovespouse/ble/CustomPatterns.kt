package com.example.lovespouse.ble

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence for user-recorded vibration patterns (stored as JSON in SharedPreferences).
 * Each pattern is the same shape the player consumes: name + intensity stages + interval.
 */
object CustomPatterns {
    private const val PREFS = "custom_patterns"
    private const val KEY = "patterns"

    fun load(context: Context): List<VibePattern> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val stagesArr = o.getJSONArray("stages")
                VibePattern(
                    name = o.getString("name"),
                    stages = IntArray(stagesArr.length()) { stagesArr.getInt(it) },
                    intervalMs = o.getLong("interval"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, patterns: List<VibePattern>) {
        val arr = JSONArray()
        patterns.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("interval", p.intervalMs)
                put("stages", JSONArray().apply { p.stages.forEach { put(it) } })
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }

    fun add(context: Context, pattern: VibePattern): List<VibePattern> {
        val list = load(context).toMutableList()
        list.removeAll { it.name == pattern.name }   // overwrite same-name
        list.add(pattern)
        save(context, list)
        return list
    }

    fun delete(context: Context, name: String): List<VibePattern> {
        val list = load(context).filterNot { it.name == name }
        save(context, list)
        return list
    }
}
