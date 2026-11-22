package com.clipforge.app

import org.json.JSONArray
import org.json.JSONObject

internal fun List<TimedWord>.toWordJson(): JSONArray = JSONArray().also { array ->
    for (word in this) {
        array.put(JSONObject().put("startMs", word.startMs).put("endMs", word.endMs).put("word", word.word))
    }
}

internal fun JSONArray?.readWordTimings(startMs: Long, endMs: Long): List<TimedWord> {
    val array = this ?: return emptyList()
    return (0 until array.length()).map { index ->
        val row = array.getJSONObject(index)
        val start = row.getLong("startMs")
        val end = row.getLong("endMs")
        val text = row.getString("word")
        require(start in startMs until end && end <= endMs && text.isNotBlank())
        TimedWord(start, end, text)
    }
}
