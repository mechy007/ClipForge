package com.clipforge.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Installs the verified Whisper model that is already bundled inside the APK.
 * No model download or network connection is performed by the app at runtime.
 */
object WhisperModelManager {
    const val MODEL_NAME = "Whisper tiny · multilingual"
    const val MODEL_SIZE_BYTES = 77_691_713L
    private const val MODEL_FILE = "ggml-tiny.bin"
    private const val MODEL_ASSET = "models/ggml-tiny.bin"
    private const val VERIFIED_MARKER = "ggml-tiny.bin.sha256"
    private const val EXPECTED_SHA256 =
        "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"

    fun modelFile(context: Context): File =
        File(File(context.filesDir, "models"), MODEL_FILE)

    private fun markerFile(context: Context): File =
        File(File(context.filesDir, "models"), VERIFIED_MARKER)

    fun isModelAvailable(context: Context): Boolean {
        val model = modelFile(context)
        return model.isFile && model.length() == MODEL_SIZE_BYTES &&
            markerFile(context).let { it.isFile && it.readText() == EXPECTED_SHA256 }
    }

    suspend fun ensureModel(
        context: Context,
        onProgress: suspend (copied: Long, total: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val destination = modelFile(context)
        val marker = markerFile(context)
        if (destination.isFile && destination.length() == MODEL_SIZE_BYTES &&
            marker.isFile && marker.readText() == EXPECTED_SHA256
        ) {
            return@withContext destination
        }

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "$MODEL_FILE.part")
        temporary.delete()

        try {
            context.assets.open(MODEL_ASSET).use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied, MODEL_SIZE_BYTES)
                    }
                    check(copied == MODEL_SIZE_BYTES) {
                        "The bundled AI model is incomplete ($copied bytes). Reinstall the app."
                    }
                }
            }

            check(sha256(temporary) == EXPECTED_SHA256) {
                "The bundled AI model failed its integrity check. Reinstall the app."
            }
            check(temporary.renameTo(destination)) {
                "Could not install the bundled AI model in app storage."
            }
            marker.writeText(EXPECTED_SHA256)
            destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
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
        return digest.digest().joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
