#include <jni.h>
#include <string>
#include <android/log.h>
#include <stdlib.h>
#include <cmath>

#define LOG_TAG "LibMyPaintJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * C++ Native Studio Engine State Context for LibMyPaint Simulation
 */
struct StudioEngineContext {
    std::string currentBrushId = "watercolor";
    float brushSize = 10.0f;
    float opacity = 0.8f;
    float hardness = 0.5f;
    bool inStroke = false;
    float lastX = 0.0f;
    float lastY = 0.0f;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_authorss81_noteflow_services_LibMyPaintJni_nativeInitEngine(JNIEnv *env, jobject thiz) {
    auto *ctx = new StudioEngineContext();
    LOGI("Native LibMyPaint StudioEngineContext initialized at %p", ctx);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_authorss81_noteflow_services_LibMyPaintJni_nativeSetBrush(
        JNIEnv *env, jobject thiz, jlong engine_ptr, jstring brush_id, jfloat size, jfloat opacity, jfloat hardness) {
    auto *ctx = reinterpret_cast<StudioEngineContext *>(engine_ptr);
    if (!ctx) return JNI_FALSE;

    const char *brushStr = env->GetStringUTFChars(brush_id, nullptr);
    if (brushStr) {
        ctx->currentBrushId = std::string(brushStr);
        env->ReleaseStringUTFChars(brush_id, brushStr);
    }
    ctx->brushSize = size;
    ctx->opacity = opacity;
    ctx->hardness = hardness;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_authorss81_noteflow_services_LibMyPaintJni_nativeNewStroke(
        JNIEnv *env, jobject thiz, jlong engine_ptr, jfloat x, jfloat y, jfloat pressure) {
    auto *ctx = reinterpret_cast<StudioEngineContext *>(engine_ptr);
    if (!ctx) return;
    ctx->inStroke = true;
    ctx->lastX = x;
    ctx->lastY = y;
}

JNIEXPORT jboolean JNICALL
Java_com_authorss81_noteflow_services_LibMyPaintJni_nativeStrokeTo(
        JNIEnv *env, jobject thiz, jlong engine_ptr, jfloat x, jfloat y, jfloat pressure, jfloat dtime) {
    auto *ctx = reinterpret_cast<StudioEngineContext *>(engine_ptr);
    if (!ctx || !ctx->inStroke) return JNI_FALSE;
    ctx->lastX = x;
    ctx->lastY = y;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_authorss81_noteflow_services_LibMyPaintJni_nativeEndStroke(
        JNIEnv *env, jobject thiz, jlong engine_ptr) {
    auto *ctx = reinterpret_cast<StudioEngineContext *>(engine_ptr);
    if (!ctx) return;
    ctx->inStroke = false;
}

JNIEXPORT void JNICALL
Java_com_authorss81_noteflow_services_LibMyPaintJni_nativeFreeEngine(
        JNIEnv *env, jobject thiz, jlong engine_ptr) {
    auto *ctx = reinterpret_cast<StudioEngineContext *>(engine_ptr);
    if (ctx) {
        LOGI("Native LibMyPaint StudioEngineContext freed at %p", ctx);
        delete ctx;
    }
}

}
