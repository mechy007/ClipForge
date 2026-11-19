package com.clipforge.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeStreamDiscoveryClientTest {
    @Test
    fun mapsLiveSearchResultsToStreamCandidates() {
        val search = JSONObject(
            """{"items":[{"id":{"videoId":"abcdefghijk"},"snippet":{"title":"GTA 6 launch stream","channelTitle":"Player One","thumbnails":{"high":{"url":"https://i.ytimg.com/high.jpg"}}}}]}"""
        )
        val details = JSONObject(
            """{"items":[{"id":"abcdefghijk","snippet":{"title":"GTA 6 launch stream","channelTitle":"Player One"},"liveStreamingDetails":{"actualStartTime":"2026-11-19T10:00:00Z","concurrentViewers":"4200"}}]}"""
        )

        val streams = YouTubeStreamDiscoveryClient.parseResults(search, details)

        assertEquals(1, streams.size)
        assertEquals(LiveStreamPlatform.YOUTUBE, streams[0].platform)
        assertEquals("abcdefghijk", streams[0].platformId)
        assertEquals("GTA 6 launch stream", streams[0].title)
        assertEquals("Player One", streams[0].channelName)
        assertEquals(4200L, streams[0].viewerCount)
        assertEquals("https://i.ytimg.com/high.jpg", streams[0].thumbnailUrl)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", streams[0].watchUrl)
    }

    @Test
    fun toleratesMissingViewerCountsAndStartTimes() {
        val search = JSONObject(
            """{"items":[{"id":{"videoId":"abcdefghijk"},"snippet":{"title":"Live now","channelTitle":"Creator"}}]}"""
        )
        val details = JSONObject(
            """{"items":[{"id":"abcdefghijk","snippet":{"title":"Live now","channelTitle":"Creator"},"liveStreamingDetails":{}}]}"""
        )

        val stream = YouTubeStreamDiscoveryClient.parseResults(search, details).single()

        assertNull(stream.viewerCount)
        assertNull(stream.startedAtEpochMs)
    }

    @Test
    fun ignoresSearchItemsWithoutMatchingVideoDetails() {
        val search = JSONObject(
            """{"items":[{"id":{"videoId":"abcdefghijk"},"snippet":{"title":"Live now","channelTitle":"Creator"}}]}"""
        )
        val details = JSONObject("""{"items":[]}""")

        assertTrue(YouTubeStreamDiscoveryClient.parseResults(search, details).isEmpty())
    }
}
