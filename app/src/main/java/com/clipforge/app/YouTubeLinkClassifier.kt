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
        return uri.host?.lowercase() in allowedHosts
    }

    fun importGuidance(): String =
        "YouTube links are watch pages, not direct MP4/WebM files. ClipForge cannot download YouTube playback. For a live broadcast, use Live to monitor chat and find highlight timestamps. For a video you own or are authorised to use, export/download it through YouTube Studio or your original editor, save it to your phone, then choose Import video."
}
