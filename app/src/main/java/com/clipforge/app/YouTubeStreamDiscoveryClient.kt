package com.clipforge.app

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class LiveStreamPlatform {
    YOUTUBE,
    TWITCH
}

data class DiscoveredLiveStream(
    val platform: LiveStreamPlatform,
    val platformId: String,
    val title: String,
    val channelName: String,
    val category: String?,
    val viewerCount: Long?,
    val startedAtEpochMs: Long?,
    val thumbnailUrl: String?,
    val watchUrl: String
)

/**
 * Platform-neutral discovery contract. Each platform adapter owns its
 * authentication and API-policy requirements.
 */
interface LiveStreamDiscoveryProvider {
    val platform: LiveStreamPlatform

    suspend fun searchLiveStreams(
        query: String,
        maxResults: Int = 25
    ): List<DiscoveredLiveStream>
}

/**
 * Discovers currently live YouTube videos through the documented YouTube
 * Data API. It never resolves or downloads audiovisual playback URLs.
 */
class YouTubeStreamDiscoveryClient(
    private val apiKey: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000
) : LiveStreamDiscoveryProvider {

    override val platform: LiveStreamPlatform = LiveStreamPlatform.YOUTUBE

    override suspend fun searchLiveStreams(
        query: String,
        maxResults: Int
    ): List<DiscoveredLiveStream> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Enter a YouTube Data API key." }
        require(query.isNotBlank()) { "Enter a game or topic to search." }
        require(maxResults in 1..50) { "Choose between 1 and 50 results." }

        val searchUrl = URL(
            "https://www.googleapis.com/youtube/v3/search" +
                "?part=snippet&type=video&eventType=live" +
                "&maxResults=" + maxResults +
                "&q=" + encode(query.trim()) +
                "&key=" + encode(apiKey.trim())
        )
        val searchResponse = getJson(searchUrl)
        val searchItems = searchResponse.optJSONArray("items") ?: return@withContext emptyList()

        val videoIds = buildList {
            for (index in 0 until searchItems.length()) {
                val item = searchItems.optJSONObject(index) ?: continue
                val id = item.optJSONObject("id")?.optString("videoId").orEmpty()
                if (id.matches(Regex("[A-Za-z0-9_-]{11}"))) add(id)
            }
        }.distinct()

        if (videoIds.isEmpty()) return@withContext emptyList()

        val detailsUrl = URL(
            "https://www.googleapis.com/youtube/v3/videos" +
                "?part=snippet%2CliveStreamingDetails" +
                "&id=" + encode(videoIds.joinToString(",")) +
                "&key=" + encode(apiKey.trim())
        )
        val detailsResponse = getJson(detailsUrl)
        parseResults(searchResponse, detailsResponse)
    }

    private fun getJson(url: URL): JSONObject {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
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
                    if (reason.isNotBlank()) "YouTube API error (" + code + "): " + reason
                    else "YouTube stream search failed (" + code + ")."
                )
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        internal fun parseResults(
            searchResponse: JSONObject,
            detailsResponse: JSONObject
        ): List<DiscoveredLiveStream> {
            val detailsById = HashMap<String, JSONObject>()
            val detailsItems = detailsResponse.optJSONArray("items")
            if (detailsItems != null) {
                for (index in 0 until detailsItems.length()) {
                    val item = detailsItems.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    if (id.isNotBlank()) detailsById[id] = item
                }
            }

            val searchItems = searchResponse.optJSONArray("items") ?: return emptyList()
            return buildList {
                for (index in 0 until searchItems.length()) {
                    val searchItem = searchItems.optJSONObject(index) ?: continue
                    val videoId = searchItem.optJSONObject("id")?.optString("videoId").orEmpty()
                    val detail = detailsById[videoId] ?: continue
                    val searchSnippet = searchItem.optJSONObject("snippet")
                    val snippet = detail.optJSONObject("snippet") ?: searchSnippet
                    val live = detail.optJSONObject("liveStreamingDetails")
                    val thumbnails = snippet?.optJSONObject("thumbnails")
                    val thumbnail = thumbnails?.optJSONObject("high")?.optString("url")
                        ?.takeIf { it.startsWith("https://") }
                        ?: thumbnails?.optJSONObject("medium")?.optString("url")
                            ?.takeIf { it.startsWith("https://") }
                        ?: thumbnails?.optJSONObject("default")?.optString("url")
                            ?.takeIf { it.startsWith("https://") }
                    val startedAt = live?.optString("actualStartTime")
                        ?.takeIf { it.isNotBlank() && it != "null" }
                        ?: live?.optString("scheduledStartTime")
                            ?.takeIf { it.isNotBlank() && it != "null" }

                    if (videoId.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                        add(
                            DiscoveredLiveStream(
                                platform = LiveStreamPlatform.YOUTUBE,
                                platformId = videoId,
                                title = snippet?.optString("title").orEmpty(),
                                channelName = snippet?.optString("channelTitle").orEmpty(),
                                category = null,
                                viewerCount = live?.optString("concurrentViewers")?.toLongOrNull(),
                                startedAtEpochMs = startedAt?.let {
                                    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                                },
                                thumbnailUrl = thumbnail,
                                watchUrl = "https://www.youtube.com/watch?v=" + videoId
                            )
                        )
                    }
                }
            }
        }

        private fun encode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
