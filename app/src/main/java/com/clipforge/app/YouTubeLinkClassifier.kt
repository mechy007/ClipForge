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
        "YouTube watch links are not direct video files. For a live stream, use Live → Record this livestream on this phone. Android screen capture records visible playback to MP4; protected playback may block recording. For videos you own, use YouTube Studio or your original editor to obtain a source file."
}
