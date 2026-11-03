package com.clipforge.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the multilingual Whisper tiny model on first use.
 * The model is stored in app-private storage and is never uploaded.
 */
object WhisperModelManager {
    const val MODEL_NAME = "Whisper tiny · multilingual"
    const val MODEL_SIZE_BYTES = 77_691_713L
    private const val MODEL_FILE = "ggml-tiny.bin"
    private const val MODEL_URL =
        "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
    private const val EXPECTED_SHA256 =
        "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_FILE)

    fun isModelAvailable(context: Context): Boolean = modelFile(context).isFile

    suspend fun ensureModel(
        context: Context,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val destination = modelFile(context)
        if (destination.isFile && destination.length() == MODEL_SIZE_BYTES &&
            sha256(destination) == EXPECTED_SHA256
        ) {
            return@withContext destination
        }

        destination.parentFile?.mkdirs()
        destination.delete()
        val temporary = File(destination.parentFile, "$MODEL_FILE.part")
        temporary.delete()

        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            connection.connect()
            check(connection.responseCode in 200..299) {
                "Model download failed (HTTP ${connection.responseCode})."
            }
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: MODEL_SIZE_BYTES
            var downloaded = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                }
            }

            check(downloaded == MODEL_SIZE_BYTES) {
                "Downloaded model size did not match the expected file size."
            }
            check(sha256(temporary) == EXPECTED_SHA256) {
                "Model integrity check failed. The incomplete file was discarded."
            }
            check(temporary.renameTo(destination)) {
                "Could not save the verified model in app storage."
            }
            destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
