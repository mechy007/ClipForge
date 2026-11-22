package com.clipforge.app

import org.json.JSONArray
import org.json.JSONObject

internal object TranscriptSegmentJson {
    fun encodeWords(timing: List<TimedWord>): JSONArray = JSONArray().apply {
        for (word in timing) {
            put(JSONObject().put("startMs", word.startMs).put("endMs", word.endMs).put("text", word.word))
        }
    }

    fun encode(segment: TranscriptSegment): JSONObject =
        JSONObject().put("startMs", segment.startMs).put("endMs", segment.endMs)
            .put("text", segment.text).put("words", encodeWords(segment.words))

    fun decode(row: JSONObject): TranscriptSegment {
        val segment = TranscriptSegment(row.getLong("startMs"), row.getLong("endMs"), row.getString("text"))
        return if (row.has("words")) withWords(segment, row.getJSONArray("words")) else segment
    }

    fun withWords(segment: TranscriptSegment, words: JSONArray): TranscriptSegment {
        val timing = List(words.length()) { index ->
            val word = words.getJSONObject(index)
            val a = word.getLong("startMs")
            val b = word.getLong("endMs")
            val text = word.getString("text")
            require(a >= segment.startMs && b > a && b <= segment.endMs && text.isNotBlank())
            TimedWord(a, b, text)
        }
        return segment.withCompleteWordTiming(timing)
    }
}
