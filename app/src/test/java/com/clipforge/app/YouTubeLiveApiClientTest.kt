package com.clipforge.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeLiveApiClientTest {
    @Test
    fun parsesBareVideoId() {
        assertEquals("abcdefghijk", YouTubeLiveApiClient.parseVideoId("abcdefghijk"))
    }

    @Test
    fun parsesWatchAndLiveUrls() {
        assertEquals("abcdefghijk", YouTubeLiveApiClient.parseVideoId("https://www.youtube.com/watch?v=abcdefghijk"))
        assertEquals("abcdefghijk", YouTubeLiveApiClient.parseVideoId("https://www.youtube.com/live/abcdefghijk"))
        assertEquals("abcdefghijk", YouTubeLiveApiClient.parseVideoId("https://youtu.be/abcdefghijk"))
    }

    @Test
    fun rejectsOtherHostsAndMalformedIds() {
        assertThrows(IllegalArgumentException::class.java) {
            YouTubeLiveApiClient.parseVideoId("https://example.com/watch?v=abcdefghijk")
        }
        assertThrows(IllegalArgumentException::class.java) {
            YouTubeLiveApiClient.parseVideoId("https://youtube.com/watch?v=short")
        }
    }
}
