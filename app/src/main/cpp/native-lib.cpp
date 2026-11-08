// 윤슬 자막 — whisper.cpp JNI 브리지
// - Whisper: 음성 → 텍스트 (언어 자동 감지)
// - Silero VAD: 32ms 프레임 단위 말소리 확률 (스트리밍, LSTM 상태 유지)

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include "whisper.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "YunseulCaption", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) do { fprintf(stderr, __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

static void quiet_log(enum ggml_log_level level, const char * text, void *) {
    if (level >= GGML_LOG_LEVEL_WARN) LOGI("%s", text);
}

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// whisper.cpp 출력은 UTF-8. NewStringUTF는 "modified UTF-8"이라 4바이트 문자(이모지 등)에서
// 터질 수 있으므로 byte[] → Java String(UTF-8) 변환을 쓴다.
static jstring utf8(JNIEnv * env, const std::string & s) {
    jbyteArray bytes = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) s.size(), (const jbyte *) s.data());
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring enc = env->NewStringUTF("UTF-8");
    auto out = (jstring) env->NewObject(strCls, ctor, bytes, enc);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(strCls);
    return out;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_yunseul_caption_Whisper_nativeInit(JNIEnv * env, jclass, jstring modelPath) {
    whisper_log_set(quiet_log, nullptr);
    std::string path = jstr(env, modelPath);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    cp.flash_attn = true;
    whisper_context * ctx = whisper_init_from_file_with_params(path.c_str(), cp);
    if (!ctx) LOGI("whisper init failed: %s", path.c_str());
    return (jlong) ctx;
}

JNIEXPORT void JNICALL
Java_com_yunseul_caption_Whisper_nativeFree(JNIEnv *, jclass, jlong ctx) {
    if (ctx) whisper_free((whisper_context *) ctx);
}

// 반환: [감지언어, 텍스트, no_speech 확률(최대값)]
JNIEXPORT jobjectArray JNICALL
Java_com_yunseul_caption_Whisper_nativeTranscribe(JNIEnv * env, jclass, jlong handle,
                                                  jfloatArray samples, jstring lang, jint threads) {
    auto * ctx = (whisper_context *) handle;
    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(3, strCls, nullptr);
    if (!ctx) return out;

    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());
    std::string language = jstr(env, lang);
    if (language.empty()) language = "auto";

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads        = threads > 0 ? threads : 4;
    p.language         = language.c_str();
    p.detect_language  = false;      // true면 감지만 하고 끝남 — auto로 감지+인식 동시에
    p.translate        = false;
    p.no_context       = true;       // 이전 문장을 프롬프트로 안 씀 → 반복 환각 줄임
    p.no_timestamps    = true;
    p.single_segment   = false;
    p.print_progress   = false;
    p.print_realtime   = false;
    p.print_special    = false;
    p.print_timestamps = false;
    p.suppress_blank   = true;
    p.suppress_nst     = true;       // 음악기호·[박수] 같은 비음성 토큰 억제
    p.temperature      = 0.0f;
    p.temperature_inc  = 0.0f;       // 폴백 재디코딩 끔 — 실시간성 우선
    p.no_speech_thold  = 0.6f;

    if (whisper_full(ctx, p, pcm.data(), n) != 0) return out;

    std::string text;
    float maxNoSpeech = 0.0f;
    const int ns = whisper_full_n_segments(ctx);
    for (int i = 0; i < ns; ++i) {
        const char * t = whisper_full_get_segment_text(ctx, i);
        if (t) text += t;
        float nsp = whisper_full_get_segment_no_speech_prob(ctx, i);
        if (nsp > maxNoSpeech) maxNoSpeech = nsp;
    }
    int lid = whisper_full_lang_id(ctx);
    const char * ls = lid >= 0 ? whisper_lang_str(lid) : language.c_str();

    env->SetObjectArrayElement(out, 0, utf8(env, ls ? ls : ""));
    env->SetObjectArrayElement(out, 1, utf8(env, text));
    env->SetObjectArrayElement(out, 2, utf8(env, std::to_string(maxNoSpeech)));
    return out;
}

// ---------------- Silero VAD ----------------

JNIEXPORT jlong JNICALL
Java_com_yunseul_caption_Vad_nativeInit(JNIEnv * env, jclass, jstring modelPath) {
    whisper_log_set(quiet_log, nullptr);
    std::string path = jstr(env, modelPath);
    whisper_vad_context_params vp = whisper_vad_default_context_params();
    vp.n_threads = 1;
    vp.use_gpu = false;
    return (jlong) whisper_vad_init_from_file_with_params(path.c_str(), vp);
}

JNIEXPORT void JNICALL
Java_com_yunseul_caption_Vad_nativeFree(JNIEnv *, jclass, jlong h) {
    if (h) whisper_vad_free((whisper_vad_context *) h);
}

JNIEXPORT void JNICALL
Java_com_yunseul_caption_Vad_nativeReset(JNIEnv *, jclass, jlong h) {
    if (h) whisper_vad_reset_state((whisper_vad_context *) h);
}

// samples 길이는 512의 배수로 넘긴다. 프레임(512샘플=32ms)마다 말소리 확률 1개.
JNIEXPORT jfloatArray JNICALL
Java_com_yunseul_caption_Vad_nativeProbs(JNIEnv * env, jclass, jlong h, jfloatArray samples) {
    auto * v = (whisper_vad_context *) h;
    jsize n = env->GetArrayLength(samples);
    std::vector<float> pcm(n);
    env->GetFloatArrayRegion(samples, 0, n, pcm.data());
    if (!v || n == 0 || !whisper_vad_detect_speech_no_reset(v, pcm.data(), n)) {
        return env->NewFloatArray(0);
    }
    int np = whisper_vad_n_probs(v);
    const float * probs = whisper_vad_probs(v);
    jfloatArray out = env->NewFloatArray(np);
    env->SetFloatArrayRegion(out, 0, np, probs);
    return out;
}

} // extern "C"
