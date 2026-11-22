package com.clipforge.app

import org.json.JSONArray
import org.json.JSONObject

internal object TranscriptSegmentJson {
    fun encode(segment: TranscriptSegment): JSONObject {
        val words = JSONArray()
        for (word in segment.words) {
            words.put(JSONObject().put("startMs", word.startMs).put("endMs", word.endMs).put("text", word.word))
        }
        return JSONObject().put("startMs", segment.startMs).put("endMs", segment.endMs)
            .put("text", segment.text).put("words", words)
    }

    fun decode(row: JSONObject): TranscriptSegment {
        val start = row.getLong("startMs")
        val end = row.getLong("endMs")
        val words = if (row.has("words")) row.getJSONArray("words") else JSONArray()
        val timing = List(words.length()) { index ->
            val word = words.getJSONObject(index)
            val a = word.getLong("startMs")
            val b = word.getLong("endMs")
            val text = word.getString("text")
            require(a >= start && b > a && b <= end && text.isNotBlank())
            TimedWord(a, b, text)
        }
        return TranscriptSegment(start, end, row.getString("text"), timing)
    }
}
