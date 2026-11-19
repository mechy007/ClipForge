package com.clipforge.app

import android.content.Context

/**
 * Speech recognition backend contract. Audio is decoded and consumed locally;
 * implementations must not send PCM, transcripts, or video to a remote service.
 */
internal interface OfflineSpeechBackend {
    val id: String
    suspend fun prepare(context: Context)
    fun begin(context: Context)
    fun transcribe(context: Context, pcm16Khz: FloatArray, language: String?): List<TranscriptSegment>
    fun close()
}

internal object BundledWhisperBackend : OfflineSpeechBackend {
    override val id = "whisper-tiny-multilingual"
    override suspend fun prepare(context: Context) {
        WhisperModelManager.ensureModel(context) { _, _ -> }
    }
    override fun begin(context: Context) = WhisperEngine.beginSession(context)
    override fun transcribe(context: Context, pcm16Khz: FloatArray, language: String?) =
        WhisperEngine.transcribe16Khz(context, pcm16Khz, language)
    override fun close() = WhisperEngine.endSession()
}

/** Only audited, bundled, on-device implementations may be registered here. */
internal object OfflineSpeechBackends {
    val default: OfflineSpeechBackend = BundledWhisperBackend
}
