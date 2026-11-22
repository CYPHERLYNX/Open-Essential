// JNI bridge to llama.cpp + libmtmd for Open Essential (com.craft.capturespace.ai.NativeLlama).
// One engine at a time; the Kotlin side serialises calls on one background thread.
// Logs timings and counts to logcat (tag OE-llama), never the content of a capture.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "OE-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Engine {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    mtmd_context  * mctx  = nullptr;
    int n_ctx   = 0;
    int n_batch = 0;
    std::atomic<bool> cancel{false};
};

std::mutex g_err_mu;
std::string g_last_error;

void set_error(const std::string & e) {
    std::lock_guard<std::mutex> lk(g_err_mu);
    g_last_error = e;
    LOGE("%s", e.c_str());
}

int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

void log_cb(enum ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) {
        __android_log_print(level >= GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "OE-llama-lib", "%s", text);
    }
}

bool abort_cb(void * data) {
    return static_cast<Engine *>(data)->cancel.load();
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

std::string jbytes_utf8(JNIEnv * env, jbyteArray a) {
    if (!a) return {};
    jsize n = env->GetArrayLength(a);
    std::string r(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&r[0]));
    return r;
}

void throw_rt(JNIEnv * env, const std::string & msg) {
    set_error(msg);
    jclass c = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(c, msg.c_str());
}

void free_engine(Engine * e) {
    if (!e) return;
    if (e->mctx)  mtmd_free(e->mctx);
    if (e->ctx)   llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

std::once_flag g_init;

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_craft_capturespace_ai_NativeLlama_nativeVersion(JNIEnv * env, jclass) {
    return env->NewStringUTF(llama_version());
}

JNIEXPORT jstring JNICALL
Java_com_craft_capturespace_ai_NativeLlama_nativeLastError(JNIEnv * env, jclass) {
    std::lock_guard<std::mutex> lk(g_err_mu);
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_craft_capturespace_ai_NativeLlama_nativeLoad(JNIEnv * env, jclass, jstring jmodel, jstring jmmproj, jint n_ctx, jint n_threads) {
    std::call_once(g_init, [] {
        llama_log_set(log_cb, nullptr);
        mtmd_helper_log_set(log_cb, nullptr);
        llama_backend_init();
    });
    const std::string model_path  = jstr(env, jmodel);
    const std::string mmproj_path = jstr(env, jmmproj);
    auto * e = new Engine();

    int64_t t0 = now_ms();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU only
    e->model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!e->model) { free_engine(e); set_error("The text model couldn't be loaded: " + model_path); return 0; }
    int64_t t1 = now_ms();
    char desc[256] = {0};
    llama_model_desc(e->model, desc, sizeof(desc));
    LOGI("model loaded in %lld ms: %s, %.1f M params, %d threads", (long long)(t1 - t0), desc,
         llama_model_n_params(e->model) / 1e6, (int)n_threads);

    if (!mmproj_path.empty()) {
        mtmd_context_params cp = mtmd_context_params_default();
        cp.use_gpu = false;
        cp.n_threads = n_threads;
        cp.print_timings = false;
        cp.warmup = false;
        e->mctx = mtmd_init_from_file(mmproj_path.c_str(), e->model, cp);
        if (!e->mctx) { free_engine(e); set_error("The vision projector couldn't be loaded with this text model: " + mmproj_path); return 0; }
        LOGI("vision projector loaded in %lld ms (vision=%d)", (long long)(now_ms() - t1), (int)mtmd_support_vision(e->mctx));
    }

    const bool non_causal = e->mctx && mtmd_decode_use_non_causal(e->mctx, nullptr);
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t)n_ctx;
    cp.n_batch = 2048;
    cp.n_ubatch = non_causal ? 2048 : 512;
    cp.n_seq_max = 1;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = e;
    cp.no_perf = false;
    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) { free_engine(e); set_error("Not enough memory to start the model with a context of " + std::to_string(n_ctx) + " tokens."); return 0; }
    e->n_ctx = (int)llama_n_ctx(e->ctx);
    e->n_batch = (int)cp.n_batch;
    LOGI("context ready: n_ctx=%d n_batch=%d n_ubatch=%u mrope=%d", e->n_ctx, e->n_batch, cp.n_ubatch,
         e->mctx ? (int)mtmd_decode_use_mrope(e->mctx) : -1);
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_craft_capturespace_ai_NativeLlama_nativeCancel(JNIEnv *, jclass, jlong h) {
    if (h) reinterpret_cast<Engine *>(h)->cancel.store(true);
}

JNIEXPORT void JNICALL
Java_com_craft_capturespace_ai_NativeLlama_nativeFree(JNIEnv *, jclass, jlong h) {
    free_engine(reinterpret_cast<Engine *>(h));
    LOGI("engine freed");
}

/**
 * Runs one prompt (already in the model's chat format, with mtmd's media marker where the
 * picture goes when rgb != null) and returns the reply as UTF-8 bytes.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_craft_capturespace_ai_NativeLlama_nativeGenerate(JNIEnv * env, jclass, jlong h, jbyteArray jprompt,
        jbyteArray jrgb, jint w, jint hgt, jint max_tokens,
        jfloat temp, jfloat top_p, jint top_k, jfloat presence_penalty, jbyteArray jgrammar) {
    auto * e = reinterpret_cast<Engine *>(h);
    if (!e) { throw_rt(env, "The model isn't loaded."); return nullptr; }
    const std::string grammar_str = jgrammar ? jbytes_utf8(env, jgrammar) : std::string();
    e->cancel.store(false);
    const std::string prompt = jbytes_utf8(env, jprompt);

    llama_memory_clear(llama_get_memory(e->ctx), true);

    mtmd::bitmaps bitmaps;
    if (jrgb) {
        if (!e->mctx) { throw_rt(env, "The vision projector isn't loaded."); return nullptr; }
        jsize n = env->GetArrayLength(jrgb);
        if (n != w * hgt * 3) { throw_rt(env, "Bad picture buffer."); return nullptr; }
        std::vector<unsigned char> rgb(static_cast<size_t>(n));
        env->GetByteArrayRegion(jrgb, 0, n, reinterpret_cast<jbyte *>(rgb.data()));
        mtmd::bitmap bmp(static_cast<uint32_t>(w), static_cast<uint32_t>(hgt), rgb.data());
        bitmaps.entries.push_back(std::move(bmp));
    }

    int64_t t0 = now_ms();
    llama_pos n_past = 0;
    if (e->mctx) {
        mtmd::input_chunks chunks(mtmd_input_chunks_init());
        mtmd_input_text text{prompt.c_str(), prompt.size(), true, true};
        auto ptrs = bitmaps.c_ptr();
        int32_t r = mtmd_tokenize(e->mctx, chunks.ptr.get(), &text, ptrs.data(), ptrs.size());
        if (r != 0) { throw_rt(env, "The picture couldn't be prepared (mtmd_tokenize returned " + std::to_string(r) + ")."); return nullptr; }
        size_t n_tok = mtmd_helper_get_n_tokens(chunks.ptr.get());
        if ((int)n_tok + max_tokens > e->n_ctx) {
            throw_rt(env, "This input is too long for the model's " + std::to_string(e->n_ctx) + "-token window (" + std::to_string(n_tok) + " tokens).");
            return nullptr;
        }
        LOGI("prompt: %zu tokens in %zu chunks, image=%dx%d", n_tok, chunks.size(), jrgb ? w : 0, jrgb ? hgt : 0);
        int32_t er = mtmd_helper_eval_chunks(e->mctx, e->ctx, chunks.ptr.get(), 0, 0, e->n_batch, true, &n_past);
        if (er != 0) {
            throw_rt(env, e->cancel.load() ? "Cancelled." : "The model couldn't read the input (eval returned " + std::to_string(er) + ").");
            return nullptr;
        }
    } else {
        const llama_vocab * vocab = llama_model_get_vocab(e->model);
        int n = -llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), nullptr, 0, true, true);
        std::vector<llama_token> toks(static_cast<size_t>(n));
        llama_tokenize(vocab, prompt.c_str(), (int32_t)prompt.size(), toks.data(), n, true, true);
        if (n + max_tokens > e->n_ctx) { throw_rt(env, "This input is too long for the model's window."); return nullptr; }
        for (int i = 0; i < n; i += e->n_batch) {
            int m = std::min(e->n_batch, n - i);
            if (llama_decode(e->ctx, llama_batch_get_one(toks.data() + i, m)) != 0) {
                throw_rt(env, e->cancel.load() ? "Cancelled." : "The model couldn't read the input.");
                return nullptr;
            }
        }
        n_past = n;
        LOGI("prompt: %d text tokens", n);
    }
    int64_t t1 = now_ms();
    LOGI("input processed in %lld ms%s", (long long)(t1 - t0), jrgb ? " (includes image encode)" : "");

    const llama_vocab * vocab = llama_model_get_vocab(e->model);
    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 256, 1.0f, 0.0f, presence_penalty));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Optional output grammar (GBNF). Checking the grammar against the whole vocabulary on every
    // token is slow, so the normal pick is tried first and only a rejected pick is re-sampled
    // under the grammar (the same approach as llama.cpp's common sampler).
    llama_sampler * gram = nullptr;
    if (!grammar_str.empty()) {
        gram = llama_sampler_init_grammar(vocab, grammar_str.c_str(), "root");
        if (!gram) { llama_sampler_free(smpl); throw_rt(env, "The output format rules couldn't be prepared."); return nullptr; }
    }
    const int n_vocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token_data> cand;
    int resampled = 0;

    std::string out;
    llama_batch batch = llama_batch_init(1, 0, 1);
    int generated = 0;
    bool failed = false;
    bool finished = false;
    for (; generated < max_tokens; ++generated) {
        if (e->cancel.load()) break;
        llama_token tok = llama_sampler_sample(smpl, e->ctx, -1);
        if (gram) {
            llama_token_data one_td = { tok, 1.0f, 0.0f };
            llama_token_data_array one = { &one_td, 1, -1, false };
            llama_sampler_apply(gram, &one);
            if (one.data[0].logit == -INFINITY) {
                const float * logits = llama_get_logits_ith(e->ctx, -1);
                cand.resize(static_cast<size_t>(n_vocab));
                for (int i = 0; i < n_vocab; ++i) cand[i] = { i, logits[i], 0.0f };
                llama_token_data_array arr = { cand.data(), cand.size(), -1, false };
                llama_sampler_apply(gram, &arr);
                llama_sampler_apply(smpl, &arr);
                if (arr.selected < 0 || arr.selected >= (int64_t)arr.size) { failed = true; break; }
                tok = arr.data[arr.selected].id;
                llama_sampler_accept(smpl, tok);
                ++resampled;
            }
            llama_sampler_accept(gram, tok);
        }
        if (llama_vocab_is_eog(vocab, tok)) { finished = true; break; }
        char buf[256];
        int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, false);
        if (n > 0) out.append(buf, static_cast<size_t>(n));
        batch.n_tokens = 1;
        batch.token[0] = tok;
        batch.pos[0] = n_past++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        if (llama_decode(e->ctx, batch) != 0) { failed = true; break; }
    }
    llama_batch_free(batch);
    llama_sampler_free(smpl);
    if (gram) llama_sampler_free(gram);
    int64_t t2 = now_ms();
    LOGI("output: %d tokens in %lld ms (%.2f tok/s)%s%s", generated, (long long)(t2 - t1),
         generated > 0 ? generated * 1000.0 / (double)std::max<int64_t>(1, t2 - t1) : 0.0,
         grammar_str.empty() ? "" : (" grammar, resampled " + std::to_string(resampled)).c_str(),
         finished ? "" : " (hit the token limit)");

    if (e->cancel.load()) { throw_rt(env, "Cancelled."); return nullptr; }
    if (failed) { throw_rt(env, "The model stopped with an error while writing."); return nullptr; }

    jbyteArray res = env->NewByteArray(static_cast<jsize>(out.size()));
    env->SetByteArrayRegion(res, 0, static_cast<jsize>(out.size()), reinterpret_cast<const jbyte *>(out.data()));
    return res;
}

} // extern "C"
