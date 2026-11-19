package com.clipforge.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini ranks local highlight candidates, never uploads video/audio.
 * API keys are supplied at runtime and never persisted by this component.
 */
object GeminiHighlightRanker {
    private val validModel = Regex("[A-Za-z0-9._-]{1,80}")

    fun reorder(candidates: List<ClipCandidate>, rankedIndices: List<Int>): List<ClipCandidate> {
        val used = mutableSetOf<Int>()
        val ranked = rankedIndices.filter { it in candidates.indices && used.add(it) }
        return ranked.map { candidates[it] } + candidates.indices.filter { it !in used }.map { candidates[it] }
    }

    suspend fun rank(apiKey: String, model: String, candidates: List<ClipCandidate>): List<ClipCandidate> =
        withContext(Dispatchers.IO) {
            if (candidates.isEmpty()) return@withContext candidates
            require(apiKey.isNotBlank()) { "Gemini API key required." }
            require(validModel.matches(model)) { "Invalid Gemini model identifier." }
            val excerpts = JSONArray()
            candidates.forEachIndexed { index, candidate ->
                excerpts.put(JSONObject()
                    .put("index", index)
                    .put("startMs", candidate.startMs)
                    .put("endMs", candidate.endMs)
                    .put("text", candidate.hook.take(700)))
            }
            val instruction = """
                Rank these video highlights by storytelling, self-contained context, novelty, and hook strength.
                Return ONLY a JSON object: {"indices":[0,1,...]} ordered best to worst.
                Include each supplied index once. Do not invent clips or timestamps.
                Candidates: $excerpts
            """.trimIndent()
            val request = JSONObject().put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instruction)))
            )).put("generationConfig", JSONObject().put("temperature", 0.2))
            val connection = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            try {
                connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Gemini request failed (HTTP ${connection.responseCode}).")
                }
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val parts = JSONObject(response).getJSONArray("candidates")
                    .getJSONObject(0).getJSONObject("content").getJSONArray("parts")
                val text = parts.getJSONObject(0).getString("text").trim()
                val json = JSONObject(text.substringAfter('{', "").let { if (it.isEmpty()) text else "{" + it }.substringBeforeLast('}') + "}")
                val indices = json.getJSONArray("indices")
                reorder(candidates, (0 until indices.length()).map { indices.getInt(it) })
            } finally {
                connection.disconnect()
            }
        }
}
