package com.clipforge.app

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal class DownloadClipSelections(private val preferences: SharedPreferences, private val key: String) {
    fun selected(): List<ClipCandidate> = runCatching {
        val records = JSONArray(preferences.getString(key, "[]"))
        (0 until records.length()).map { index ->
            val clip = records.getJSONObject(index)
            ClipCandidate(clip.getLong("start"), clip.getLong("end"), clip.getString("title"),
                clip.getString("hook"), clip.getInt("score"), "Selected during download.")
        }
    }.getOrDefault(emptyList())

    fun add(clip: ClipCandidate) {
        val records = JSONArray()
        val retained = (selected() + clip).distinctBy { it.startMs to it.endMs }.takeLast(10)
        retained.forEach {
            records.put(JSONObject().put("start", it.startMs).put("end", it.endMs)
                .put("title", it.title).put("hook", it.hook).put("score", it.score))
        }
        preferences.edit().putString(key, records.toString()).apply()
    }

    fun clear() { preferences.edit().remove(key).apply() }
}
