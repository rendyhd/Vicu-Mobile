// JNI for com.rendyhd.vicu.speech.VicuSpeechJni (app/src/main/java/com/rendyhd/vicu/speech/), the
// Android side of vicu_speech.h. Handles travel as jlong (0 is none). Strings cross as UTF-16 both
// ways, converted here, because JNI's "UTF" functions use modified UTF-8, which encodes characters
// outside the Basic Multilingual Plane (emoji in a project name) differently from whisper.cpp.

#include <jni.h>

#include "vicu_speech.h"

#include <cstdint>
#include <string>
#include <vector>

namespace {

// Longer than any phrase's text (a phrase is at most 30 s of speech).
constexpr int kMaxText = 64 * 1024;

std::string to_utf8(JNIEnv * env, jstring value) {
    std::string out;
    if (value == nullptr) return out;
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return out;
    out.reserve(static_cast<size_t>(length));
    for (jsize i = 0; i < length; i++) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < length && chars[i + 1] >= 0xDC00 && chars[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (chars[i + 1] - 0xDC00);
            i++;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD;  // a lone surrogate
        }
        if (c < 0x80) {
            out += static_cast<char>(c);
        } else if (c < 0x800) {
            out += static_cast<char>(0xC0 | (c >> 6));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out += static_cast<char>(0xE0 | (c >> 12));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        } else {
            out += static_cast<char>(0xF0 | (c >> 18));
            out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
            out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (c & 0x3F));
        }
    }
    env->ReleaseStringChars(value, chars);
    return out;
}

/** UTF-8 to a Java string; an invalid sequence becomes U+FFFD. */
jstring to_jstring(JNIEnv * env, const char * text, size_t size) {
    std::vector<jchar> out;
    out.reserve(size);
    const auto * s = reinterpret_cast<const unsigned char *>(text);
    size_t i = 0;
    while (i < size) {
        const unsigned char b = s[i];
        uint32_t c;
        size_t extra;
        if (b < 0x80) { c = b; extra = 0; }
        else if ((b & 0xE0) == 0xC0) { c = b & 0x1F; extra = 1; }
        else if ((b & 0xF0) == 0xE0) { c = b & 0x0F; extra = 2; }
        else if ((b & 0xF8) == 0xF0) { c = b & 0x07; extra = 3; }
        else { out.push_back(0xFFFD); i++; continue; }
        bool valid = true;
        for (size_t k = 1; k <= extra; k++) {
            if (i + k >= size || (s[i + k] & 0xC0) != 0x80) { valid = false; break; }
            c = (c << 6) | (s[i + k] & 0x3F);
        }
        const bool overlong = (extra == 1 && c < 0x80) || (extra == 2 && c < 0x800) || (extra == 3 && c < 0x10000);
        if (!valid || overlong || c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF)) {
            out.push_back(0xFFFD);
            i++;
            continue;
        }
        if (c >= 0x10000) {
            c -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (c >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (c & 0x3FF)));
        } else {
            out.push_back(static_cast<jchar>(c));
        }
        i += extra + 1;
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

template <typename T>
T * from_handle(jlong handle) {
    return reinterpret_cast<T *>(static_cast<intptr_t>(handle));
}

jlong to_handle(const void * pointer) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(pointer));
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_rendyhd_vicu_speech_VicuSpeechJni_version(JNIEnv * env, jclass) {
    const char * version = vs_version();
    return version == nullptr ? nullptr : env->NewStringUTF(version);
}

JNIEXPORT jlong JNICALL
Java_com_rendyhd_vicu_speech_VicuSpeechJni_vadOpen(JNIEnv * env, jclass, jstring path, jint threads) {
    if (path == nullptr) return 0;
    const std::string file = to_utf8(env, path);
    return to_handle(vs_vad_open(file.c_str(), threads));
}

JNIEXPORT jint JNICALL
Java_com_rendyhd_vicu_speech_VicuSpeechJni_vadFeed(JNIEnv * env, jclass, jlong vad, jfloatArray samples, jint count,
                                                    jfloatArray outProbs) {
    if (vad == 0 || samples == nullptr || outProbs == nullptr || count < 0) return -1;
    if (count > env->GetArrayLength(samples)) return -1;
    const jsize maxProbs = env->GetArrayLength(outProbs);
    // The detector copies the samples into its own buffer at once, so a short critical section
    // would do, but detection runs inside the call: Get...Elements keeps the collector free.
    jfloat * input = env->GetFloatArrayElements(samples, nullptr);
    if (input == nullptr) return -1;
    std::vector<float> probs(static_cast<size_t>(maxProbs));
    const int written = vs_vad_feed(from_handle<vs_vad>(vad), input, count, probs.data(), maxProbs);
    env->ReleaseFloatArrayElements(samples, input, JNI_ABORT);
    if (written > 0) env->SetFloatArrayRegion(outProbs, 0, written, probs.data());
    return written;
}

JNIEXPORT void JNICALL
Java_com_rendyhd_vicu_speech_VicuSpeechJni_vadReset(JNIEnv *, jclass, jlong vad) {
    vs_vad_reset(from_handle<vs_vad>(vad));
}

JNIEXPORT void JNICALL
Java_com_rendyhd_vicu_speech_VicuSpeechJni_vadClose(JNIEnv *, jclass, jlong vad) {
    vs_vad_close(from_handle<vs_vad>(vad));
}

JNIEXPORT jlong JNICALL
Java_com_rendyhd_vicu_speech_VicuSpeechJni_modelOpen(JNIEnv * env, jclass, jstring path, jint engine, jboolean useGpu) {
    if (path == nullptr) return 0;
    const std::string file = to_utf8(env, path);
    return to_handle(vs_model_open(file.c_str(), engine, useGpu ? 1 : 0));
}

JNIEXPORT jstring JNICALL
Java_com_rendyhd_vicu_speech_VicuSpeechJni_transcribe(JNIEnv * env, jclass, jlong model, jfloatArray samples, jint count,
                                                       jstring language, jstring prompt, jint threads) {
    if (model == 0 || samples == nullptr || count <= 0 || count > env->GetArrayLength(samples)) return nullptr;
    const std::string lang = to_utf8(env, language);
    const std::string hint = to_utf8(env, prompt);
    std::vector<char> out(kMaxText);
    // A phrase is large enough to live in ART's large-object space, which does not move, so this
    // is usually the array itself rather than a copy.
    jfloat * input = env->GetFloatArrayElements(samples, nullptr);
    if (input == nullptr) return nullptr;
    const int length = vs_transcribe(from_handle<vs_model>(model), input, count,
                                     language == nullptr ? nullptr : lang.c_str(),
                                     prompt == nullptr ? nullptr : hint.c_str(),
                                     threads, out.data(), kMaxText);
    env->ReleaseFloatArrayElements(samples, input, JNI_ABORT);
    if (length < 0) return nullptr;
    return to_jstring(env, out.data(), static_cast<size_t>(length));
}

JNIEXPORT void JNICALL
Java_com_rendyhd_vicu_speech_VicuSpeechJni_abort(JNIEnv *, jclass, jlong model) {
    vs_abort(from_handle<vs_model>(model));
}

JNIEXPORT void JNICALL
Java_com_rendyhd_vicu_speech_VicuSpeechJni_modelClose(JNIEnv *, jclass, jlong model) {
    vs_model_close(from_handle<vs_model>(model));
}

}  // extern "C"
