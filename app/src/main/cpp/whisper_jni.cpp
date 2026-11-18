#include <jni.h>
#include <mutex>
#include <string>
#include <vector>
#include "whisper.h"

namespace {
std::mutex g_whisper_mutex;
whisper_context *g_whisper_context = nullptr;
std::string g_whisper_model_path;
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

    const int result = whisper_full(g_whisper_context, params, samples, sample_count);
    env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);
    if (language_chars != nullptr) env->ReleaseStringUTFChars(language, language_chars);

    if (result != 0) return nullptr;

    const int count = whisper_full_n_segments(g_whisper_context);
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray output = env->NewObjectArray(count, string_class, nullptr);
    if (output == nullptr) {
        env->DeleteLocalRef(string_class);
        return nullptr;
    }

    for (int i = 0; i < count; ++i) {
        const int64_t start_ms = whisper_full_get_segment_t0(g_whisper_context, i) * 10;
        const int64_t end_ms = whisper_full_get_segment_t1(g_whisper_context, i) * 10;
        const char *segment_text = whisper_full_get_segment_text(g_whisper_context, i);
        std::string row = std::to_string(start_ms) + "\t" +
                          std::to_string(end_ms) + "\t" +
                          (segment_text == nullptr ? "" : segment_text);
        jstring item = env->NewStringUTF(row.c_str());
        if (item == nullptr) break;
        env->SetObjectArrayElement(output, i, item);
        env->DeleteLocalRef(item);
        if (env->ExceptionCheck()) break;
    }

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
