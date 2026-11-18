package com.clipforge.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

data class YouTubeLiveSession(
    val videoId: String,
    val title: String,
    val liveChatId: String,
    val actualStartTimeMs: Long
)

data class YouTubeChatMessage(
    val id: String,
    val publishedAtMs: Long,
    val authorChannelId: String?,
    val displayText: String,
    val type: String
)

data class YouTubeChatPage(
    val messages: List<YouTubeChatMessage>,
    val nextPageToken: String?,
    val pollingIntervalMs: Long,
    val offline: Boolean
)

/**
 * Uses only documented YouTube Data API endpoints.
 *
 * This client reads public live metadata/chat. It deliberately does not resolve
 * private playback URLs or download audiovisual content from YouTube.
 */
class YouTubeLiveApiClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000
) {
    suspend fun resolveLiveSession(videoUrlOrId: String, apiKey: String): YouTubeLiveSession =
        withContext(Dispatchers.IO) {
            require(apiKey.isNotBlank()) { "Enter a YouTube Data API key." }
            val videoId = parseVideoId(videoUrlOrId)
            val url = URL(
                "https://www.googleapis.com/youtube/v3/videos" +
                    "?part=snippet%2CliveStreamingDetails&id=${encode(videoId)}&key=${encode(apiKey.trim())}"
            )
            val root = getJson(url)
            val items = root.optJSONArray("items")
                ?: throw IllegalStateException("YouTube did not return video details.")
            if (items.length() == 0) throw IllegalArgumentException("YouTube video was not found or is not publicly accessible.")
            val item = items.getJSONObject(0)
            val live = item.optJSONObject("liveStreamingDetails")
                ?: throw IllegalArgumentException("This video is not a live broadcast.")
            val chatId = live.optString("activeLiveChatId").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("This broadcast has no active live chat.")
            val startedAt = live.optString("actualStartTime").takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("YouTube has not reported the broadcast start time yet.")
            YouTubeLiveSession(
                videoId = videoId,
                title = item.optJSONObject("snippet")?.optString("title").orEmpty().ifBlank { "YouTube Live" },
                liveChatId = chatId,
                actualStartTimeMs = Instant.parse(startedAt).toEpochMilli()
            )
        }

    suspend fun getChatPage(
        session: YouTubeLiveSession,
        apiKey: String,
        pageToken: String? = null
    ): YouTubeChatPage = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Enter a YouTube Data API key." }
        val query = buildString {
            append("https://www.googleapis.com/youtube/v3/liveChat/messages")
            append("?part=snippet%2CauthorDetails")
            append("&liveChatId=").append(encode(session.liveChatId))
            append("&maxResults=2000")
            append("&key=").append(encode(apiKey.trim()))
            if (!pageToken.isNullOrBlank()) append("&pageToken=").append(encode(pageToken))
        }
        val root = getJson(URL(query))
        val rows = root.optJSONArray("items")
        val messages = buildList {
            if (rows != null) {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val snippet = row.optJSONObject("snippet") ?: continue
                    val author = row.optJSONObject("authorDetails")
                    val publishedAt = snippet.optString("publishedAt")
                    val publishedAtMs = runCatching { Instant.parse(publishedAt).toEpochMilli() }.getOrNull() ?: continue
                    val type = snippet.optString("type", "unknown")
                    val text = snippet.optJSONObject("textMessageDetails")
                        ?.optString("messageText")
                        ?.takeIf { it.isNotBlank() }
                        ?: snippet.optString("displayMessage").takeIf { it.isNotBlank() }
                        ?: type
                    add(
                        YouTubeChatMessage(
                            id = row.optString("id"),
                            publishedAtMs = publishedAtMs,
                            authorChannelId = author?.optString("channelId")?.takeIf { it.isNotBlank() },
                            displayText = text,
                            type = type
                        )
                    )
                }
            }
        }
        YouTubeChatPage(
            messages = messages,
            nextPageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() },
            pollingIntervalMs = root.optLong("pollingIntervalMillis", 5_000L).coerceIn(1_000L, 60_000L),
            offline = root.has("offlineAt")
        )
    }

    companion object {
        fun parseVideoId(input: String): String {
            val raw = input.trim()
            val id = if (raw.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                raw
            } else {
                val uri = runCatching { Uri.parse(raw) }.getOrNull()
                    ?: throw IllegalArgumentException("Enter a valid YouTube Live URL or video ID.")
                val host = uri.host?.lowercase().orEmpty()
                require(host == "youtube.com" || host == "www.youtube.com" ||
                    host == "m.youtube.com" || host == "youtu.be") {
                    "Only YouTube video links are supported."
                }
                when {
                    host == "youtu.be" -> uri.lastPathSegment.orEmpty()
                    uri.path == "/watch" -> uri.getQueryParameter("v").orEmpty()
                    uri.pathSegments.firstOrNull() in setOf("live", "embed", "shorts") ->
                        uri.pathSegments.getOrNull(1).orEmpty()
                    else -> ""
                }
            }
            require(id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                "Could not find a valid 11-character YouTube video ID."
            }
            return id
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())

        private fun getJson(url: URL): JSONObject {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val reason = runCatching {
                        JSONObject(body).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    throw IllegalStateException(
                        if (reason.isNotBlank()) "YouTube API error ($code): $reason"
                        else "YouTube API request failed ($code)."
                    )
                }
                return JSONObject(body)
            } finally {
                connection.disconnect()
            }
        }
    }
}
