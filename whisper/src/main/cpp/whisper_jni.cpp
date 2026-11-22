// JNI bridge to whisper.cpp for Open Essential (com.craft.capturespace.ai.NativeWhisper).
// Input: 16 kHz mono float PCM. Logs timings to logcat (tag OE-whisper), never the transcript.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "OE-whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
struct Engine {
    whisper_context * ctx = nullptr;
    std::atomic<bool> cancel{false};
};
std::mutex g_mu;
std::string g_err;
void set_error(const std::string & e) { std::lock_guard<std::mutex> l(g_mu); g_err = e; LOGE("%s", e.c_str()); }
int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}
bool abort_cb(void * d) { return static_cast<Engine *>(d)->cancel.load(); }
void log_cb(enum ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, "OE-whisper-lib", "%s", text);
}
void throw_rt(JNIEnv * env, const std::string & m) {
    set_error(m);
    env->ThrowNew(env->FindClass("java/lang/RuntimeException"), m.c_str());
}
} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_craft_capturespace_ai_NativeWhisper_nativeLastError(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> l(g_mu);
    return env->NewStringUTF(g_err.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_craft_capturespace_ai_NativeWhisper_nativeSystemInfo(JNIEnv * env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_craft_capturespace_ai_NativeWhisper_nativeLoad(JNIEnv * env, jclass, jstring jpath) {
    whisper_log_set(log_cb, nullptr);
    const char * p = env->GetStringUTFChars(jpath, nullptr);
    std::string path(p);
    env->ReleaseStringUTFChars(jpath, p);
    int64_t t0 = now_ms();
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    auto * e = new Engine();
    e->ctx = whisper_init_from_file_with_params(path.c_str(), cp);
    if (!e->ctx) { delete e; set_error("The speech model couldn't be loaded: " + path); return 0; }
    LOGI("speech model loaded in %lld ms (multilingual=%d)", (long long)(now_ms() - t0), whisper_is_multilingual(e->ctx));
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_craft_capturespace_ai_NativeWhisper_nativeCancel(JNIEnv *, jclass, jlong h) {
    if (h) reinterpret_cast<Engine *>(h)->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_craft_capturespace_ai_NativeWhisper_nativeFree(JNIEnv *, jclass, jlong h) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) return;
    if (e->ctx) whisper_free(e->ctx);
    delete e;
    LOGI("speech engine freed");
}

/** Transcribes PCM; returns "lang\n" followed by the text, as UTF-8 bytes. language "auto" detects. */
JNIEXPORT jbyteArray JNICALL
Java_com_craft_capturespace_ai_NativeWhisper_nativeTranscribe(JNIEnv * env, jclass, jlong h, jfloatArray jpcm, jstring jlang, jint n_threads) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) { throw_rt(env, "The speech model isn't loaded."); return nullptr; }
    e->cancel.store(false);
    jsize n = env->GetArrayLength(jpcm);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());
    const char * lc = env->GetStringUTFChars(jlang, nullptr);
    std::string lang(lc);
    env->ReleaseStringUTFChars(jlang, lc);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.n_threads = n_threads;
    wp.translate = false;
    wp.language = lang.c_str();
    wp.print_progress = false;
    wp.print_realtime = false;
    wp.print_special = false;
    wp.print_timestamps = false;
    wp.no_context = true;
    wp.abort_callback = abort_cb;
    wp.abort_callback_user_data = e;

    int64_t t0 = now_ms();
    int r = whisper_full(e->ctx, wp, pcm.data(), n);
    int64_t t1 = now_ms();
    if (e->cancel.load()) { throw_rt(env, "Cancelled."); return nullptr; }
    if (r != 0) { throw_rt(env, "Speech recognition failed (whisper_full returned " + std::to_string(r) + ")."); return nullptr; }

    std::string out = whisper_lang_str(whisper_full_lang_id(e->ctx));
    out += "\n";
    const int segs = whisper_full_n_segments(e->ctx);
    for (int i = 0; i < segs; ++i) out += whisper_full_get_segment_text(e->ctx, i);
    LOGI("transcribed %.1f s of audio in %lld ms, %d segments", n / 16000.0, (long long)(t1 - t0), segs);

    jbyteArray res = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(res, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}

} // extern "C"
