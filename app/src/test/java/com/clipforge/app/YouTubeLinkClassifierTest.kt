package com.clipforge.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLinkClassifierTest {
    @Test
    fun recognizesCommonYouTubeLinkFormats() {
        assertTrue(YouTubeLinkClassifier.isYouTubeUrl("https://youtu.be/iGRN1JW3Af8"))
        assertTrue(YouTubeLinkClassifier.isYouTubeUrl("https://www.youtube.com/watch?v=iGRN1JW3Af8"))
        assertTrue(YouTubeLinkClassifier.isYouTubeUrl("https://m.youtube.com/shorts/iGRN1JW3Af8"))
        assertTrue(YouTubeLinkClassifier.isYouTubeUrl("https://www.youtube.com/live/iGRN1JW3Af8"))
    }

    @Test
    fun acceptsLiveWatchLinksForScreenRecording() {
        assertTrue(YouTubeLinkClassifier.isYouTubeUrl("https://www.youtube.com/live/abcdefghijk"))
        assertTrue(YouTubeLinkClassifier.isYouTubeUrl("https://youtube.com/watch?v=abcdefghijk"))
    }

    @Test
    fun rejectsLookalikeAndNonYouTubeHosts() {
        assertFalse(YouTubeLinkClassifier.isYouTubeUrl("https://youtube.com.example.org/watch?v=abcdefghijk"))
        assertFalse(YouTubeLinkClassifier.isYouTubeUrl("https://example.com/watch?v=abcdefghijk"))
        assertFalse(YouTubeLinkClassifier.isYouTubeUrl("file:///youtube.com/video.mp4"))
        assertFalse(YouTubeLinkClassifier.isYouTubeUrl("not a URL"))
    }
}
