#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>

#include "video_sink.h"
#include "audio_sink.h"

#if HAVE_RPIPLAY
extern "C" {
#include "raop.h"
#include "stream.h"
#include "dnssd.h"
#include "airplay_video.h"
}
#endif

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "airplay_native", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "airplay_native", __VA_ARGS__)

namespace {

jclass    g_nativeClass  = nullptr;
jmethodID g_onConnInit   = nullptr;
jmethodID g_onPlayUrl    = nullptr;
jmethodID g_onPlayStop   = nullptr;
jmethodID g_onPlayRate   = nullptr;
jmethodID g_onPlayInfo   = nullptr;

#if HAVE_RPIPLAY
raop_t*  g_raop  = nullptr;
dnssd_t* g_dnssd = nullptr;

void audio_process(void*, raop_ntp_t*, aac_decode_struct* data) {
    localair::dispatchAac(data->data, data->data_len, static_cast<int64_t>(data->pts));
}
void video_process(void*, raop_ntp_t*, h264_decode_struct* data) {
    localair::dispatchNal(data->data, data->data_len, static_cast<int64_t>(data->pts));
}
void conn_init(void*) {
    LOGI("client connected");
    if (!g_nativeClass || !g_onConnInit) return;
    JavaVM* vm = localair::jvm();
    if (!vm) return;
    JNIEnv* e = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) != JNI_OK)
        vm->AttachCurrentThread(&e, nullptr);
    if (!e) return;
    e->CallStaticVoidMethod(g_nativeClass, g_onConnInit);
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); }
}
void conn_destroy(void*) {
    LOGI("client disconnected");
    localair::dispatchSessionEnd();
}

void on_play_url(void*, const char* url, double start) {
    LOGI("play URL: %s start=%.2f", url, start);
    if (!g_nativeClass || !g_onPlayUrl) return;
    JavaVM* vm = localair::jvm(); if (!vm) return;
    JNIEnv* e = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) != JNI_OK)
        vm->AttachCurrentThread(&e, nullptr);
    if (!e) return;
    jstring jurl = e->NewStringUTF(url);
    e->CallStaticVoidMethod(g_nativeClass, g_onPlayUrl, jurl, (jdouble)start);
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); }
    e->DeleteLocalRef(jurl);
}

void on_play_stop(void*) {
    LOGI("play stop");
    if (!g_nativeClass || !g_onPlayStop) return;
    JavaVM* vm = localair::jvm(); if (!vm) return;
    JNIEnv* e = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) != JNI_OK)
        vm->AttachCurrentThread(&e, nullptr);
    if (!e) return;
    e->CallStaticVoidMethod(g_nativeClass, g_onPlayStop);
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); }
}

void on_play_rate(void*, float rate) {
    LOGI("play rate: %.1f", rate);
    if (!g_nativeClass || !g_onPlayRate) return;
    JavaVM* vm = localair::jvm(); if (!vm) return;
    JNIEnv* e = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) != JNI_OK)
        vm->AttachCurrentThread(&e, nullptr);
    if (!e) return;
    e->CallStaticVoidMethod(g_nativeClass, g_onPlayRate, (jfloat)rate);
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); }
}

void on_play_info(void*, double* duration, double* position, float* rate) {
    if (!g_nativeClass || !g_onPlayInfo) return;
    JavaVM* vm = localair::jvm(); if (!vm) return;
    JNIEnv* e = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) != JNI_OK)
        vm->AttachCurrentThread(&e, nullptr);
    if (!e) return;
    jdoubleArray arr = e->NewDoubleArray(3);
    e->CallStaticVoidMethod(g_nativeClass, g_onPlayInfo, arr);
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); }
    else {
        jdouble vals[3];
        e->GetDoubleArrayRegion(arr, 0, 3, vals);
        *duration = vals[0]; *position = vals[1]; *rate = (float)vals[2];
    }
    e->DeleteLocalRef(arr);
}
void audio_flush(void*)  {}
void video_flush(void*)  {}
void audio_set_volume(void*, float)                       {}
void audio_set_metadata(void*, const void*, int)          {}
void audio_set_coverart(void*, const void*, int)          {}
void log_callback(void*, int level, const char* msg) {
    __android_log_print(ANDROID_LOG_DEBUG, "rpiplay", "[%d] %s", level, msg);
}
#endif

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_localair_airplay_nativebridge_AirPlayNative_nativeStart(JNIEnv*, jclass) {
#if HAVE_RPIPLAY
    raop_callbacks_t cbs{};
    cbs.audio_process      = audio_process;
    cbs.video_process      = video_process;
    cbs.conn_init          = conn_init;
    cbs.conn_destroy       = conn_destroy;
    cbs.audio_flush        = audio_flush;
    cbs.video_flush        = video_flush;
    cbs.audio_set_volume   = audio_set_volume;
    cbs.audio_set_metadata = audio_set_metadata;
    cbs.audio_set_coverart = audio_set_coverart;

    g_raop = raop_init(10, &cbs);
    if (!g_raop) { LOGE("raop_init failed"); return 0; }
    raop_set_log_callback(g_raop, log_callback, nullptr);
    raop_set_log_level(g_raop, RAOP_LOG_DEBUG);

    // dnssd_stub.c implements this API as a no-op store for name + hw_addr.
    // Kotlin (NsdManager) handles actual Bonjour advertising.
    static const char hw_addr[6] = {(char)0xAA,(char)0xBB,(char)0xCC,(char)0xDD,(char)0xEE,(char)0xFF};
    int err = 0;
    g_dnssd = dnssd_init("localair", 8, hw_addr, 6, &err);
    if (!g_dnssd) { LOGE("dnssd_init failed: %d", err); raop_destroy(g_raop); g_raop = nullptr; return 0; }
    raop_set_dnssd(g_raop, g_dnssd);

    unsigned short port = 0;
    if (raop_start(g_raop, &port) < 0) {
        LOGE("raop_start failed");
        dnssd_destroy(g_dnssd); g_dnssd = nullptr;
        raop_destroy(g_raop);   g_raop  = nullptr;
        return 0;
    }
    LOGI("raop listening on %d", port);
    airplay_video_set_callbacks(nullptr, on_play_url, on_play_stop, on_play_rate, on_play_info);
    return static_cast<jint>(port);
#else
    LOGI("stub start — RPiPlay not vendored");
    return 0;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localair_airplay_nativebridge_AirPlayNative_nativeIsRunning(JNIEnv*, jclass) {
#if HAVE_RPIPLAY
    return g_raop && raop_is_running(g_raop) ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_localair_airplay_nativebridge_AirPlayNative_nativeStop(JNIEnv*, jclass) {
#if HAVE_RPIPLAY
    if (g_raop)  { raop_stop(g_raop); raop_destroy(g_raop); g_raop = nullptr; }
    if (g_dnssd) { dnssd_destroy(g_dnssd); g_dnssd = nullptr; }
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_localair_airplay_nativebridge_AirPlayNative_nativeSetSink(JNIEnv* env, jclass, jobject sink) {
    localair::setSink(env, sink);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localair_airplay_nativebridge_AirPlayNative_nativeSetAudioSink(JNIEnv* env, jclass, jobject sink) {
    localair::setAudioSink(env, sink);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    localair::initJvm(vm);
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return -1;
    jclass cls = env->FindClass("com/localair/airplay/nativebridge/AirPlayNative");
    if (cls) {
        g_nativeClass = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
        g_onConnInit  = env->GetStaticMethodID(cls, "onConnectionInit", "()V");
        g_onPlayUrl   = env->GetStaticMethodID(cls, "onPlayUrl", "(Ljava/lang/String;D)V");
        g_onPlayStop  = env->GetStaticMethodID(cls, "onPlayStop", "()V");
        g_onPlayRate  = env->GetStaticMethodID(cls, "onPlayRate", "(F)V");
        g_onPlayInfo  = env->GetStaticMethodID(cls, "onPlayInfo", "([D)V");
        env->DeleteLocalRef(cls);
    }
    return JNI_VERSION_1_6;
}
