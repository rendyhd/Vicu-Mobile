// Vicu's local speech engine over whisper.cpp; see vicu_speech.h.

#include "vicu_speech.h"

#include "parakeet.h"
#include "whisper.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <string>
#include <vector>

namespace {

// Silero's window at 16 kHz (whisper.cpp reads it from the model; v6 uses 512).
constexpr int kVadWindow = 512;

void quiet_log(enum ggml_log_level, const char *, void *) {}

void silence_logs() {
    static std::atomic<bool> done{false};
    if (!done.exchange(true)) {
        whisper_log_set(quiet_log, nullptr);
        parakeet_log_set(quiet_log, nullptr);
    }
}

int write_out(const std::string & text, char * out, int max_out) {
    if (out == nullptr || max_out <= 0) return -1;
    size_t n = std::min(text.size(), static_cast<size_t>(max_out - 1));
    // Never cut inside a UTF-8 sequence.
    while (n > 0 && n < text.size() && (static_cast<unsigned char>(text[n]) & 0xC0) == 0x80) n--;
    std::memcpy(out, text.data(), n);
    out[n] = '\0';
    return static_cast<int>(n);
}

bool aborted(void * user_data) {
    return static_cast<std::atomic<bool> *>(user_data)->load();
}

}  // namespace

struct vs_vad {
    whisper_vad_context * ctx = nullptr;
    std::vector<float> pending;
};

struct vs_model {
    int engine = VS_ENGINE_WHISPER;
    whisper_context * whisper = nullptr;
    parakeet_context * parakeet = nullptr;
    std::atomic<bool> abort{false};
};

extern "C" {

const char * vs_version(void) { return whisper_version(); }

int vs_vad_window_samples(void) { return kVadWindow; }

vs_vad * vs_vad_open(const char * path, int n_threads) {
    silence_logs();
    whisper_vad_context_params params = whisper_vad_default_context_params();
    params.n_threads = std::max(1, n_threads);
    params.use_gpu = false;
    whisper_vad_context * ctx = whisper_vad_init_from_file_with_params(path, params);
    if (ctx == nullptr) return nullptr;
    // The detector's LSTM state is allocated but not cleared (whisper_vad_detect_speech clears it,
    // the streaming _no_reset call does not): memory reused from the heap gives a state that holds
    // every answer near one value (seen on Android in the app's process, not in a fresh one).
    whisper_vad_reset_state(ctx);
    auto * vad = new vs_vad();
    vad->ctx = ctx;
    return vad;
}

int vs_vad_feed(vs_vad * vad, const float * samples, int n_samples, float * out_probs, int max_probs) {
    if (vad == nullptr || (samples == nullptr && n_samples > 0) || n_samples < 0) return -1;
    vad->pending.insert(vad->pending.end(), samples, samples + n_samples);
    const int windows = static_cast<int>(vad->pending.size()) / kVadWindow;
    if (windows == 0) return 0;
    const int whole = windows * kVadWindow;
    if (!whisper_vad_detect_speech_no_reset(vad->ctx, vad->pending.data(), whole)) return -1;
    const int n_probs = std::min(whisper_vad_n_probs(vad->ctx), windows);
    const float * probs = whisper_vad_probs(vad->ctx);
    const int written = std::min(n_probs, max_probs);
    if (out_probs != nullptr && written > 0) std::memcpy(out_probs, probs, sizeof(float) * written);
    vad->pending.erase(vad->pending.begin(), vad->pending.begin() + whole);
    return written;
}

void vs_vad_reset(vs_vad * vad) {
    if (vad == nullptr) return;
    whisper_vad_reset_state(vad->ctx);
    vad->pending.clear();
}

void vs_vad_close(vs_vad * vad) {
    if (vad == nullptr) return;
    whisper_vad_free(vad->ctx);
    delete vad;
}

vs_model * vs_model_open(const char * path, int engine, int use_gpu) {
    silence_logs();
    auto * model = new vs_model();
    model->engine = engine;
    if (engine == VS_ENGINE_PARAKEET) {
        parakeet_context_params params = parakeet_context_default_params();
        params.use_gpu = use_gpu != 0;
        model->parakeet = parakeet_init_from_file_with_params(path, params);
        if (model->parakeet == nullptr) { delete model; return nullptr; }
    } else {
        whisper_context_params params = whisper_context_default_params();
        params.use_gpu = use_gpu != 0;
        params.flash_attn = use_gpu != 0;
        model->whisper = whisper_init_from_file_with_params(path, params);
        if (model->whisper == nullptr) { delete model; return nullptr; }
    }
    return model;
}

int vs_transcribe(vs_model * model, const float * samples, int n_samples, const char * language,
                  const char * prompt, int n_threads, char * out, int max_out) {
    if (model == nullptr || samples == nullptr || n_samples <= 0) return -1;
    model->abort.store(false);
    const int threads = std::max(1, std::min(n_threads, 4));
    std::string text;

    if (model->engine == VS_ENGINE_PARAKEET) {
        parakeet_full_params params = parakeet_full_default_params(PARAKEET_SAMPLING_GREEDY);
        params.n_threads = threads;
        params.no_context = true;
        params.abort_callback = aborted;
        params.abort_callback_user_data = &model->abort;
        if (parakeet_full(model->parakeet, params, samples, n_samples) != 0) return -1;
        const int n = parakeet_full_n_segments(model->parakeet);
        for (int i = 0; i < n; i++) text += parakeet_full_get_segment_text(model->parakeet, i);
    } else {
        whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = threads;
        params.translate = false;
        params.no_context = true;
        params.no_timestamps = true;
        params.single_segment = false;
        params.print_special = false;
        params.print_progress = false;
        params.print_realtime = false;
        params.print_timestamps = false;
        params.temperature = 0.0f;
        params.temperature_inc = 0.0f;
        params.suppress_blank = true;
        params.suppress_nst = true;
        const bool automatic = language == nullptr || language[0] == '\0' || std::strcmp(language, "auto") == 0;
        params.language = automatic ? "auto" : language;
        params.detect_language = false;
        params.initial_prompt = (prompt != nullptr && prompt[0] != '\0') ? prompt : nullptr;
        params.carry_initial_prompt = false;
        params.abort_callback = aborted;
        params.abort_callback_user_data = &model->abort;
        if (whisper_full(model->whisper, params, samples, n_samples) != 0) return -1;
        const int n = whisper_full_n_segments(model->whisper);
        for (int i = 0; i < n; i++) text += whisper_full_get_segment_text(model->whisper, i);
    }
    if (model->abort.load()) return -1;
    return write_out(text, out, max_out);
}

void vs_abort(vs_model * model) {
    if (model != nullptr) model->abort.store(true);
}

void vs_model_close(vs_model * model) {
    if (model == nullptr) return;
    if (model->whisper != nullptr) whisper_free(model->whisper);
    if (model->parakeet != nullptr) parakeet_free(model->parakeet);
    delete model;
}

}  // extern "C"
