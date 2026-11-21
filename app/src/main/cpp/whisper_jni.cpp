#include <jni.h>
#include <algorithm>
#include <cctype>
#include <mutex>
#include <string>
#include <vector>
#include "whisper.h"

namespace {
std::mutex g_whisper_mutex;
whisper_context *g_whisper_context = nullptr;
std::string g_whisper_model_path;

std::string timed_row(char kind, int64_t start_ms, int64_t end_ms, const std::string &text) {
    return std::string(1, kind) + "\t" + std::to_string(start_ms) + "\t" +
           std::to_string(end_ms) + "\t" + text;
}

void append_word_rows(std::vector<std::string> &rows, int index, int64_t segment_start, int64_t segment_end) {
    std::string word;
    int64_t start = -1;
    int64_t end = -1;
    const auto flush = [&]() {
        if (!word.empty() && start >= segment_start && end <= segment_end && end > start) {
            rows.push_back(timed_row('W', start, end, word));
        }
        word.clear();
        start = -1;
        end = -1;
    };
    for (int j = 0; j < whisper_full_n_tokens(g_whisper_context, index); ++j) {
        const auto token = whisper_full_get_token_data(g_whisper_context, index, j);
        if (token.id >= whisper_token_eot(g_whisper_context)) continue;
        const char *piece = whisper_full_get_token_text(g_whisper_context, index, j);
        if (piece == nullptr || *piece == '\0') continue;
        if (std::isspace(static_cast<unsigned char>(*piece))) {
            flush();
            while (std::isspace(static_cast<unsigned char>(*piece))) ++piece;
        }
        if (*piece == '\0') continue;
        // Concatenate UTF-8 token bytes first. A token can contain part of a character.
        word += piece;
        if (token.t0 >= 0 && token.t1 > token.t0) {
            const auto token_start = std::max(segment_start, token.t0 * 10);
            const auto token_end = std::min(segment_end, token.t1 * 10);
            if (token_end > token_start) {
                if (start < 0) start = token_start;
                end = std::max(end, token_end);
            }
        }
    }
    flush();
}
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_clipforge_app_WhisperEngine_loadModel(
        JNIEnv *env,
        jobject,
        jstring model_path) {
    if (model_path == nullptr) return JNI_FALSE;

    const char *model_path_chars = env->GetStringUTFChars(model_path, nullptr);
    if (model_path_chars == nullptr) return JNI_FALSE;
    const std::string requested_path(model_path_chars);
    env->ReleaseStringUTFChars(model_path, model_path_chars);

    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    if (g_whisper_context != nullptr && g_whisper_model_path == requested_path) {
        return JNI_TRUE;
    }

    if (g_whisper_context != nullptr) {
        whisper_free(g_whisper_context);
        g_whisper_context = nullptr;
        g_whisper_model_path.clear();
    }

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    g_whisper_context = whisper_init_from_file_with_params(requested_path.c_str(), context_params);
    if (g_whisper_context == nullptr) return JNI_FALSE;

    g_whisper_model_path = requested_path;
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_clipforge_app_WhisperEngine_transcribe(
        JNIEnv *env,
        jobject,
        jfloatArray audio_data,
        jint thread_count,
        jstring language) {
    if (audio_data == nullptr) return nullptr;

    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    if (g_whisper_context == nullptr) return nullptr;

    const char *language_chars = language == nullptr
            ? nullptr
            : env->GetStringUTFChars(language, nullptr);

    jfloat *samples = env->GetFloatArrayElements(audio_data, nullptr);
    const jsize sample_count = env->GetArrayLength(audio_data);
    if (samples == nullptr || sample_count == 0) {
        if (samples != nullptr) env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);
        if (language_chars != nullptr) env->ReleaseStringUTFChars(language, language_chars);
        return nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.language = language_chars;
    params.n_threads = thread_count > 0 ? thread_count : 2;
    params.no_context = true;
    params.single_segment = false;
    params.token_timestamps = true;

    const int result = whisper_full(g_whisper_context, params, samples, sample_count);
    env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);
    if (language_chars != nullptr) env->ReleaseStringUTFChars(language, language_chars);

    if (result != 0) return nullptr;

    std::vector<std::string> rows;
    const int count = whisper_full_n_segments(g_whisper_context);
    for (int i = 0; i < count; ++i) {
        const int64_t start_ms = whisper_full_get_segment_t0(g_whisper_context, i) * 10;
        const int64_t end_ms = whisper_full_get_segment_t1(g_whisper_context, i) * 10;
        const char *segment_text = whisper_full_get_segment_text(g_whisper_context, i);
        rows.push_back(timed_row('S', start_ms, end_ms, segment_text == nullptr ? "" : segment_text));
        append_word_rows(rows, i, start_ms, end_ms);
    }
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jmethodID string_constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    if (string_constructor == nullptr) return nullptr;
    jstring utf8 = env->NewStringUTF("UTF-8");
    if (utf8 == nullptr) return nullptr;
    jobjectArray output = env->NewObjectArray(static_cast<jsize>(rows.size()), string_class, nullptr);
    if (output == nullptr) return nullptr;
    for (size_t i = 0; i < rows.size(); ++i) {
        const auto size = static_cast<jsize>(rows[i].size());
        jbyteArray bytes = env->NewByteArray(size);
        if (bytes == nullptr) return nullptr;
        env->SetByteArrayRegion(bytes, 0, size, reinterpret_cast<const jbyte *>(rows[i].data()));
        if (env->ExceptionCheck()) return nullptr;
        const auto row = env->NewObject(string_class, string_constructor, bytes, utf8);
        env->DeleteLocalRef(bytes);
        if (row == nullptr) return nullptr;
        env->SetObjectArrayElement(output, static_cast<jsize>(i), row);
        env->DeleteLocalRef(row);
        if (env->ExceptionCheck()) return nullptr;
    }

    env->DeleteLocalRef(utf8);
    env->DeleteLocalRef(string_class);
    return output;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_clipforge_app_WhisperEngine_releaseModel(
        JNIEnv *,
        jobject) {
    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    if (g_whisper_context != nullptr) {
        whisper_free(g_whisper_context);
        g_whisper_context = nullptr;
        g_whisper_model_path.clear();
    }
}
