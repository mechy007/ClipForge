package com.clipforge.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import kotlin.math.roundToLong

/** Resolve finite recording duration before planning independently playable sections. */
internal object YouTubeDurationProbe {
    suspend fun durationMs(context: Context, url: String): Long = runInterruptible(Dispatchers.IO) {
        require(YouTubeLinkClassifier.isYouTubeUrl(url))
        val id = YouTubeLiveApiClient.parseVideoId(url)
        YoutubeDL.init(context.applicationContext)
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
            .addOption("--no-playlist")
            .addOption("--skip-download")
            .addOption("--dump-single-json")
            .addOption("--no-warnings")
            .addOption("--socket-timeout", 20)
        val result = YoutubeDL.execute(request)
        val output = result.out
        val json = JSONObject(output.substring(output.indexOf('{').takeIf { it >= 0 }
            ?: throw IllegalStateException("Video metadata unavailable")))
        val seconds = json.optDouble("duration")
        check(seconds.isFinite() && seconds > 0.0 && seconds <= 24 * 60 * 60) {
            "A finite recording duration is required; live or unknown-duration videos need standard import."
        }
        (seconds * 1000.0).roundToLong()
    }
}
