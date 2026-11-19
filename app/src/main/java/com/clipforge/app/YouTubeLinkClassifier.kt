package com.clipforge.app

import java.net.URI

/** Identifies YouTube watch-page links so they are never mistaken for direct media files. */
object YouTubeLinkClassifier {
    private val allowedHosts = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com",
        "music.youtube.com", "youtu.be"
    )

    fun isYouTubeUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", true) && !uri.scheme.equals("http", true)) return false
        if (uri.userInfo != null) return false
        return uri.host?.lowercase()?.let { it in allowedHosts } == true
    }

    fun importGuidance(): String =
        "For recorded videos, paste the YouTube link in Studio and choose Download and clip. For an active broadcast, use Live to monitor chat and mark highlights."
}
