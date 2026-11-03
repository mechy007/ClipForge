#include <jni.h>
#include <string>
#include <vector>
#include "whisper.h"

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_clipforge_app_WhisperNative_transcribe(
        JNIEnv *env,
        jobject,
        jstring model_path,
        jfloatArray audio_data,
        jint thread_count,
        jstring language) {
    if (model_path == nullptr || audio_data == nullptr) {
        return nullptr;
    }

    const char *model_path_chars = env->GetStringUTFChars(model_path, nullptr);
    if (model_path_chars == nullptr) {
        return nullptr;
    }

    const char *language_chars = language == nullptr
            ? nullptr
            : env->GetStringUTFChars(language, nullptr);

    whisper_context_params context_params = whisper_context_default_params();
    context_params.use_gpu = false;
    whisper_context *context = whisper_init_from_file_with_params(model_path_chars, context_params);
    env->ReleaseStringUTFChars(model_path, model_path_chars);

    if (language_chars != nullptr) {
        // The language string is released after transcription below.
    }

    if (context == nullptr) {
        if (language_chars != nullptr) env->ReleaseStringUTFChars(language, language_chars);
        return nullptr;
    }

    jfloat *samples = env->GetFloatArrayElements(audio_data, nullptr);
    const jsize sample_count = env->GetArrayLength(audio_data);
    if (samples == nullptr || sample_count == 0) {
        if (samples != nullptr) env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);
        if (language_chars != nullptr) env->ReleaseStringUTFChars(language, language_chars);
        whisper_free(context);
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

    const int result = whisper_full(context, params, samples, sample_count);
    env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);
    if (language_chars != nullptr) env->ReleaseStringUTFChars(language, language_chars);

    if (result != 0) {
        whisper_free(context);
        return nullptr;
    }

    const int count = whisper_full_n_segments(context);
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray output = env->NewObjectArray(count, string_class, nullptr);

    for (int i = 0; i < count; ++i) {
        const int64_t start_ms = whisper_full_get_segment_t0(context, i) * 10;
        const int64_t end_ms = whisper_full_get_segment_t1(context, i) * 10;
        const char *segment_text = whisper_full_get_segment_text(context, i);
        std::string row = std::to_string(start_ms) + "\t" +
                          std::to_string(end_ms) + "\t" +
                          (segment_text == nullptr ? "" : segment_text);
        jstring item = env->NewStringUTF(row.c_str());
        env->SetObjectArrayElement(output, i, item);
        env->DeleteLocalRef(item);
    }

    env->DeleteLocalRef(string_class);
    whisper_free(context);
    return output;
}
