/*
 * Vicu's local speech engine: a small C interface over whisper.cpp (Whisper and Parakeet models,
 * the Silero voice detector), the same in Vicu mobile (Android over JNI, iOS over cinterop) and
 * Vicu desktop (over koffi). docs/ai-assistant-v1.md, section 3.
 *
 * Audio is 16 kHz mono float samples in [-1, 1]. Every function may be called from any thread,
 * but one handle is used by one thread at a time.
 */
#ifndef VICU_SPEECH_H
#define VICU_SPEECH_H

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  define VS_API __declspec(dllexport)
#else
#  define VS_API __attribute__((visibility("default")))
#endif

#define VS_ENGINE_WHISPER 0
#define VS_ENGINE_PARAKEET 1

typedef struct vs_vad vs_vad;
typedef struct vs_model vs_model;

/** The whisper.cpp version this was built with. */
VS_API const char * vs_version(void);

/** Samples per voice-detector window (512 at 16 kHz): one probability per window. */
VS_API int vs_vad_window_samples(void);

/** Opens the Silero voice detector model. NULL on failure. */
VS_API vs_vad * vs_vad_open(const char * path, int n_threads);

/**
 * Feeds samples to the detector, which keeps its state between calls (streaming). Writes one
 * speech probability per whole window to [out_probs] (at most [max_probs]) and returns how many;
 * samples short of a whole window are kept for the next call. -1 on failure.
 */
VS_API int vs_vad_feed(vs_vad * vad, const float * samples, int n_samples, float * out_probs, int max_probs);

/** Forgets the detector's state and any kept samples (a new recording). */
VS_API void vs_vad_reset(vs_vad * vad);

VS_API void vs_vad_close(vs_vad * vad);

/** Opens a speech model ([engine] VS_ENGINE_WHISPER or VS_ENGINE_PARAKEET). NULL on failure. */
VS_API vs_model * vs_model_open(const char * path, int engine, int use_gpu);

/**
 * Transcribes one phrase with the decoding of section 3.3: temperature 0 without fallback, no
 * conditioning on earlier text, non-speech tokens suppressed. [language] is an ISO 639-1 code or
 * NULL / "auto"; [prompt] the vocabulary hint or NULL (both ignored by Parakeet). Writes the UTF-8
 * text, NUL-terminated and cut to fit, to [out] and returns its length in bytes; -1 on failure.
 */
VS_API int vs_transcribe(vs_model * model, const float * samples, int n_samples, const char * language,
                         const char * prompt, int n_threads, char * out, int max_out);

/** Asks a running vs_transcribe on this model to stop early (it then returns -1). */
VS_API void vs_abort(vs_model * model);

VS_API void vs_model_close(vs_model * model);

#ifdef __cplusplus
}
#endif

#endif
