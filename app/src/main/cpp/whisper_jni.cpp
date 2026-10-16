// JNI bridge between app.instantrecorder.transcribe.WhisperLib and whisper.cpp.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>

#include "whisper.h"

#define TAG "WhisperJNI"

static std::atomic<int> g_progress{0};
static std::atomic<bool> g_abort{false};

static void log_callback(ggml_log_level level, const char *text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) {
        __android_log_write(level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, TAG, text);
    }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_instantrecorder_transcribe_WhisperLib_nativeInit(JNIEnv *env, jobject, jstring model_path) {
    whisper_log_set(log_callback, nullptr);
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_app_instantrecorder_transcribe_WhisperLib_nativeFree(JNIEnv *, jobject, jlong ctx_ptr) {
    whisper_free(reinterpret_cast<whisper_context *>(ctx_ptr));
}

// Returns UTF-8 bytes (not a jstring: whisper can emit byte sequences that are not
// valid "modified UTF-8", which would abort NewStringUTF under CheckJNI), or null on failure.
JNIEXPORT jbyteArray JNICALL
Java_app_instantrecorder_transcribe_WhisperLib_nativeTranscribe(
        JNIEnv *env, jobject, jlong ctx_ptr, jfloatArray audio, jint n_threads, jint audio_ctx, jstring language) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctx_ptr);
    const jsize n_samples = env->GetArrayLength(audio);
    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);
    const char *lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = n_threads;
    params.language = lang;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.suppress_blank = true;
    params.suppress_nst = true;
    params.audio_ctx = audio_ctx; // 0 = full 30 s window

    g_progress.store(0);
    g_abort.store(false);
    params.progress_callback = [](whisper_context *, whisper_state *, int progress, void *) {
        g_progress.store(progress);
    };
    params.abort_callback = [](void *) -> bool { return g_abort.load(); };

    const int rc = whisper_full(ctx, params, samples, n_samples);

    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    if (rc != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "whisper_full failed: %d", rc);
        return nullptr;
    }

    std::string text;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        text += whisper_full_get_segment_text(ctx, i);
    }
    g_progress.store(100);

    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()),
                            reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

JNIEXPORT jint JNICALL
Java_app_instantrecorder_transcribe_WhisperLib_nativeProgress(JNIEnv *, jobject) {
    return g_progress.load();
}

JNIEXPORT void JNICALL
Java_app_instantrecorder_transcribe_WhisperLib_nativeAbort(JNIEnv *, jobject) {
    g_abort.store(true);
}

JNIEXPORT jstring JNICALL
Java_app_instantrecorder_transcribe_WhisperLib_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
