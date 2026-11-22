#include <jni.h>
#include <mutex>
#include <string>
#include <vector>
#include <algorithm>
#include <cctype>
#include "whisper.h"

namespace {
std::mutex g_whisper_mutex;
whisper_context *g_whisper_context = nullptr;
std::string g_whisper_model_path;

jstring utf8(JNIEnv *env, jclass string_class, jmethodID constructor, jstring charset,
             const std::string &value) {
    const auto size = static_cast<jsize>(value.size());
    jbyteArray bytes = env->NewByteArray(size);
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, size, reinterpret_cast<const jbyte *>(value.data()));
    auto result = static_cast<jstring>(env->NewObject(string_class, constructor, bytes, charset));
    env->DeleteLocalRef(bytes);
    return result;
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

    const int count = whisper_full_n_segments(g_whisper_context);
    jclass string_class = env->FindClass("java/lang/String");
    jclass row_class = env->FindClass("[Ljava/lang/String;");
    if (string_class == nullptr || row_class == nullptr) return nullptr;
    jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    if (constructor == nullptr) return nullptr;
    jstring charset = env->NewStringUTF("UTF-8");
    if (charset == nullptr) return nullptr;
    jobjectArray output = env->NewObjectArray(count, row_class, nullptr);
    if (output == nullptr) return nullptr;

    for (int i = 0; i < count; ++i) {
        const int64_t start = whisper_full_get_segment_t0(g_whisper_context, i);
        const int64_t end = whisper_full_get_segment_t1(g_whisper_context, i);
        const char *text = whisper_full_get_segment_text(g_whisper_context, i);
        auto timedRow = [](int64_t a, int64_t b, const std::string &value) {
            return std::to_string(a * 10) + "\t" + std::to_string(b * 10) + "\t" + value;
        };
        std::vector<std::string> rows{timedRow(start, end, text == nullptr ? "" : text)};
        std::string word;
        int64_t word_start = -1, word_end = -1;
        auto flushWord = [&]() {
            if (word_start >= start && word_end > word_start && word_end <= end) {
                rows.push_back(timedRow(word_start, word_end, word));
            }
            word.clear();
            word_start = word_end = -1;
        };
        for (int token = 0; token < whisper_full_n_tokens(g_whisper_context, i); ++token) {
            const auto data = whisper_full_get_token_data(g_whisper_context, i, token);
            if (data.id >= whisper_token_eot(g_whisper_context)) continue;
            const char *piece = whisper_full_get_token_text(g_whisper_context, i, token);
            if (piece == nullptr) continue;
            for (; *piece != '\0'; ++piece) {
                if (std::isspace(static_cast<unsigned char>(*piece))) {
                    if (!word.empty()) flushWord();
                } else {
                    word.push_back(*piece);
                    if (data.t0 >= 0 && data.t1 >= data.t0) {
                        if (word_start < 0) word_start = std::max(start, data.t0);
                        word_end = std::max(word_end, std::min(end, data.t1));
                    }
                }
            }
        }
        if (!word.empty()) flushWord();
        jobjectArray segment = env->NewObjectArray(static_cast<jsize>(rows.size()), string_class, nullptr);
        if (segment == nullptr) return nullptr;
        for (jsize j = 0; j < static_cast<jsize>(rows.size()); ++j) {
            jstring item = utf8(env, string_class, constructor, charset, rows[j]);
            if (item == nullptr || env->ExceptionCheck()) return nullptr;
            env->SetObjectArrayElement(segment, j, item);
            env->DeleteLocalRef(item);
            if (env->ExceptionCheck()) return nullptr;
        }
        env->SetObjectArrayElement(output, i, segment);
        env->DeleteLocalRef(segment);
        if (env->ExceptionCheck()) return nullptr;
    }

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
