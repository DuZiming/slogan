/* Slogan 自有 mpv JNI 封装（LGPL 合规：动态链接 libmpv，自有代码）。
 * 替换 dev.jdtech.mpv AAR——暴露 mpv 底层句柄供渲染桥使用，
 * 并内置 EGL 渲染循环（ANativeWindow → EGL → mpv_render_context）。
 * 事件通过 JNI 回调 Kotlin（eventProperty* 系列）。 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>

#define TAG "SloganMpv"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* ---- 渲染器状态（每个 mpv 实例一份）---- */
typedef struct {
    mpv_handle *mpv;
    mpv_render_context *render_ctx;
    EGLDisplay egl_display;
    EGLContext egl_context;
    EGLSurface egl_surface;
    ANativeWindow *window;
    pthread_mutex_t lock;
    pthread_cond_t wake;
    pthread_t render_thread;
    volatile int render_running;
    volatile int frame_pending;
    volatile int surface_attached;
    JavaVM *jvm;
    jobject global_ref; /* Kotlin MpvCore 实例，事件回调用 */
} SloganMpv;

static void *gl_get_proc(void *ctx, const char *name) {
    (void)ctx;
    return (void *)eglGetProcAddress(name);
}

/* ---- 渲染线程 ---- */
static void on_mpv_redraw(void *ctx) {
    SloganMpv *s = (SloganMpv *)ctx;
    pthread_mutex_lock(&s->lock);
    s->frame_pending = 1;
    pthread_cond_signal(&s->wake);
    pthread_mutex_unlock(&s->lock);
}

static void *render_loop(void *arg) {
    SloganMpv *s = (SloganMpv *)arg;
    while (s->render_running) {
        pthread_mutex_lock(&s->lock);
        while (s->render_running && !s->frame_pending) {
            struct timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_nsec += 200000000; /* 200ms */
            if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
            pthread_cond_timedwait(&s->wake, &s->lock, &ts);
        }
        int pending = s->frame_pending;
        s->frame_pending = 0;
        pthread_mutex_unlock(&s->lock);
        if (!s->render_running) break;
        if (!pending) continue;

        if (s->egl_display != EGL_NO_DISPLAY &&
            eglMakeCurrent(s->egl_display, s->egl_surface, s->egl_surface, s->egl_context)) {
            int fbo = 0, flip = 1;
            mpv_render_param params[] = {
                {MPV_RENDER_PARAM_OPENGL_FBO, &fbo},
                {MPV_RENDER_PARAM_FLIP_Y, &flip},
                {MPV_RENDER_PARAM_INVALID, NULL},
            };
            mpv_render_context_render(s->render_ctx, params);
            eglSwapBuffers(s->egl_display, s->egl_surface);
        }
    }
    return NULL;
}

/* ---- mpv 事件 → Kotlin 回调 ---- */
static void call_kotlin_event(SloganMpv *s, const char *method, const char *sig, ...) {
    JNIEnv *env;
    int attached = 0;
    if ((*s->jvm)->GetEnv(s->jvm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        (*s->jvm)->AttachCurrentThread(s->jvm, &env, NULL);
        attached = 1;
    }
    if (env && s->global_ref) {
        jclass cls = (*env)->GetObjectClass(env, s->global_ref);
        jmethodID mid = (*env)->GetMethodID(env, cls, method, sig);
        if (mid) {
            va_list args;
            va_start(args, sig);
            /* 调用方保证参数匹配——仅支持常见签名组合 */
            if (strcmp(sig, "(Ljava/lang/String;D)V") == 0) {
                jstring name = (*env)->NewStringUTF(env, va_arg(args, const char*));
                double val = va_arg(args, double);
                (*env)->CallVoidMethod(env, s->global_ref, mid, name, val);
                (*env)->DeleteLocalRef(env, name);
            } else if (strcmp(sig, "(Ljava/lang/String;Z)V") == 0) {
                jstring name = (*env)->NewStringUTF(env, va_arg(args, const char*));
                int val = va_arg(args, int);
                (*env)->CallVoidMethod(env, s->global_ref, mid, name, (jboolean)val);
                (*env)->DeleteLocalRef(env, name);
            } else if (strcmp(sig, "(Ljava/lang/String;J)V") == 0) {
                jstring name = (*env)->NewStringUTF(env, va_arg(args, const char*));
                long val = va_arg(args, long);
                (*env)->CallVoidMethod(env, s->global_ref, mid, name, (jlong)val);
                (*env)->DeleteLocalRef(env, name);
            } else if (strcmp(sig, "(Ljava/lang/String;)V") == 0) {
                jstring name = (*env)->NewStringUTF(env, va_arg(args, const char*));
                (*env)->CallVoidMethod(env, s->global_ref, mid, name);
                (*env)->DeleteLocalRef(env, name);
            } else if (strcmp(sig, "(I)V") == 0) {
                int val = va_arg(args, int);
                (*env)->CallVoidMethod(env, s->global_ref, mid, (jint)val);
            }
            va_end(args);
        }
    }
    if (attached) (*s->jvm)->DetachCurrentThread(s->jvm);
}

/* mpv wakeup 回调（mpv 内部线程调用）→ 唤醒事件线程 */
static void on_mpv_wakeup(void *ctx) {
    SloganMpv *s = (SloganMpv *)ctx;
    /* 标记有待处理事件，由事件线程处理 */
    call_kotlin_event(s, "onMpvEvent", "(I)V", (int)0 /*dummy*/);
}

/* ---- JNI 导出 ---- */

JNIEXPORT jlong JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeCreate(JNIEnv *env, jobject thiz) {
    SloganMpv *s = calloc(1, sizeof(SloganMpv));
    if (!s) return 0;

    (*env)->GetJavaVM(env, &s->jvm);
    s->global_ref = (*env)->NewGlobalRef(env, thiz);
    s->egl_display = EGL_NO_DISPLAY;
    s->egl_context = EGL_NO_CONTEXT;
    s->egl_surface = EGL_NO_SURFACE;
    pthread_mutex_init(&s->lock, NULL);
    pthread_cond_init(&s->wake, NULL);

    s->mpv = mpv_create();
    if (!s->mpv) { free(s); return 0; }

    /* 事件通知（wakeup → Kotlin 轮询 processEvent） */
    mpv_set_wakeup_callback(s->mpv, on_mpv_wakeup, s);

    LOGD("nativeCreate: mpv=%p", s->mpv);
    return (jlong)(intptr_t)s;
}

JNIEXPORT jboolean JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeInit(JNIEnv *env, jobject thiz, jlong ptr) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s || !s->mpv) return JNI_FALSE;
    int r = mpv_initialize(s->mpv);
    LOGD("nativeInit: r=%d", r);
    return r == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeDestroy(JNIEnv *env, jobject thiz, jlong ptr) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return;
    /* 停渲染线程 */
    pthread_mutex_lock(&s->lock);
    s->render_running = 0;
    pthread_cond_signal(&s->wake);
    pthread_mutex_unlock(&s->lock);
    if (s->render_thread) pthread_join(s->render_thread, NULL);
    if (s->render_ctx) mpv_render_context_free(s->render_ctx);
    /* 清 EGL */
    if (s->egl_surface != EGL_NO_SURFACE) eglDestroySurface(s->egl_display, s->egl_surface);
    if (s->egl_context != EGL_NO_CONTEXT) eglDestroyContext(s->egl_display, s->egl_context);
    if (s->egl_display != EGL_NO_DISPLAY) eglTerminate(s->egl_display);
    if (s->window) ANativeWindow_release(s->window);
    if (s->mpv) mpv_destroy(s->mpv);
    if (s->global_ref) (*env)->DeleteGlobalRef(env, s->global_ref);
    pthread_mutex_destroy(&s->lock);
    pthread_cond_destroy(&s->wake);
    free(s);
}

JNIEXPORT jboolean JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeAttachSurface(
        JNIEnv *env, jobject thiz, jlong ptr, jobject surface) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s || !s->mpv) return JNI_FALSE;

    pthread_mutex_lock(&s->lock);

    /* 清旧 */
    if (s->render_running) {
        s->render_running = 0;
        pthread_cond_signal(&s->wake);
        pthread_mutex_unlock(&s->lock);
        pthread_join(s->render_thread, NULL);
        pthread_mutex_lock(&s->lock);
    }
    if (s->render_ctx) { mpv_render_context_free(s->render_ctx); s->render_ctx = NULL; }
    if (s->egl_surface != EGL_NO_SURFACE) { eglDestroySurface(s->egl_display, s->egl_surface); s->egl_surface = EGL_NO_SURFACE; }
    if (s->egl_context != EGL_NO_CONTEXT) { eglDestroyContext(s->egl_display, s->egl_context); s->egl_context = EGL_NO_CONTEXT; }
    if (s->window) { ANativeWindow_release(s->window); s->window = NULL; }

    /* 新 Surface */
    s->window = ANativeWindow_fromSurface(env, surface);
    if (!s->window) goto fail;

    s->egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (s->egl_display == EGL_NO_DISPLAY) goto fail;
    eglInitialize(s->egl_display, NULL, NULL);

    const EGLint cfg_attrs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config; EGLint num = 0;
    if (!eglChooseConfig(s->egl_display, cfg_attrs, &config, 1, &num) || num < 1) goto fail;

    const EGLint ctx_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    s->egl_context = eglCreateContext(s->egl_display, config, EGL_NO_CONTEXT, ctx_attrs);
    if (s->egl_context == EGL_NO_CONTEXT) goto fail;

    s->egl_surface = eglCreateWindowSurface(s->egl_display, config, s->window, NULL);
    if (s->egl_surface == EGL_NO_SURFACE) goto fail;

    /* 创建 mpv 渲染上下文（核心差异：自有构建暴露 mpv 句柄，这里直接用） */
    mpv_render_param params[] = {
        {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_OPENGL},
        {MPV_RENDER_PARAM_OPENGL_INIT_PARAM, &(mpv_opengl_init_params){gl_get_proc, NULL}},
        {MPV_RENDER_PARAM_INVALID, NULL},
    };
    if (mpv_render_context_create(&s->render_ctx, s->mpv, params) < 0) {
        LOGE("mpv_render_context_create failed");
        goto fail;
    }
    mpv_render_context_set_update_callback(s->render_ctx, on_mpv_redraw, s);

    /* 启动渲染线程 */
    s->render_running = 1;
    s->frame_pending = 1;
    s->surface_attached = 1;
    pthread_create(&s->render_thread, NULL, render_loop, s);

    pthread_mutex_unlock(&s->lock);
    LOGD("attachSurface: EGL+render_ctx OK");
    return JNI_TRUE;

fail:
    LOGE("attachSurface failed");
    if (s->render_ctx) { mpv_render_context_free(s->render_ctx); s->render_ctx = NULL; }
    if (s->egl_surface != EGL_NO_SURFACE) { eglDestroySurface(s->egl_display, s->egl_surface); s->egl_surface = EGL_NO_SURFACE; }
    if (s->egl_context != EGL_NO_CONTEXT) { eglDestroyContext(s->egl_display, s->egl_context); s->egl_context = EGL_NO_CONTEXT; }
    if (s->window) { ANativeWindow_release(s->window); s->window = NULL; }
    pthread_mutex_unlock(&s->lock);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeDetachSurface(JNIEnv *env, jobject thiz, jlong ptr) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return;
    pthread_mutex_lock(&s->lock);
    if (s->render_running) {
        s->render_running = 0;
        pthread_cond_signal(&s->wake);
        pthread_mutex_unlock(&s->lock);
        pthread_join(s->render_thread, NULL);
        pthread_mutex_lock(&s->lock);
    }
    if (s->render_ctx) { mpv_render_context_free(s->render_ctx); s->render_ctx = NULL; }
    if (s->egl_surface != EGL_NO_SURFACE) { eglDestroySurface(s->egl_display, s->egl_surface); s->egl_surface = EGL_NO_SURFACE; }
    if (s->egl_context != EGL_NO_CONTEXT) { eglDestroyContext(s->egl_display, s->egl_context); s->egl_context = EGL_NO_CONTEXT; }
    if (s->window) { ANativeWindow_release(s->window); s->window = NULL; }
    s->surface_attached = 0;
    pthread_mutex_unlock(&s->lock);
}

/* ---- mpv 核心 API 直通 ---- */

JNIEXPORT jint JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeCommand(
        JNIEnv *env, jobject thiz, jlong ptr, jobjectArray args) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s || !s->mpv) return -1;
    int argc = (*env)->GetArrayLength(env, args);
    const char **c_args = calloc(argc + 1, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring str = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        c_args[i] = (*env)->GetStringUTFChars(env, str, NULL);
    }
    c_args[argc] = NULL;
    int r = mpv_command(s->mpv, c_args);
    for (int i = 0; i < argc; i++) {
        jstring str = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        (*env)->ReleaseStringUTFChars(env, str, c_args[i]);
    }
    free(c_args);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeSetOptionString(
        JNIEnv *env, jobject thiz, jlong ptr, jstring name, jstring value) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return -1;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    const char *c_val = (*env)->GetStringUTFChars(env, value, NULL);
    int r = mpv_set_option_string(s->mpv, c_name, c_val);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    (*env)->ReleaseStringUTFChars(env, value, c_val);
    return r;
}

/* ---- 属性读写 ---- */

JNIEXPORT jint JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeGetPropertyInt(
        JNIEnv *env, jobject thiz, jlong ptr, jstring name) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return 0;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    double val;
    int r = mpv_get_property(s->mpv, c_name, MPV_FORMAT_DOUBLE, &val);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    return r == 0 ? (jint)(long)val : 0;
}

JNIEXPORT jdouble JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeGetPropertyDouble(
        JNIEnv *env, jobject thiz, jlong ptr, jstring name) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return 0;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    double val = 0;
    int r = mpv_get_property(s->mpv, c_name, MPV_FORMAT_DOUBLE, &val);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    return r == 0 ? val : 0;
}

JNIEXPORT jstring JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeGetPropertyString(
        JNIEnv *env, jobject thiz, jlong ptr, jstring name) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return NULL;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    char *val = mpv_get_property_string(s->mpv, c_name);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    if (!val) return NULL;
    jstring result = (*env)->NewStringUTF(env, val);
    mpv_free(val);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeGetPropertyBoolean(
        JNIEnv *env, jobject thiz, jlong ptr, jstring name) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return JNI_FALSE;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    int val = 0;
    int r = mpv_get_property(s->mpv, c_name, MPV_FORMAT_FLAG, &val);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    return r == 0 ? (val != 0) : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeSetPropertyInt(
        JNIEnv *env, jobject thiz, jlong ptr, jstring name, jint value) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return -1;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    double val = (double)value;
    int r = mpv_set_property(s->mpv, c_name, MPV_FORMAT_DOUBLE, &val);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeSetPropertyDouble(
        JNIEnv *env, jobject thiz, jlong ptr, jstring name, jdouble value) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return -1;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    int r = mpv_set_property(s->mpv, c_name, MPV_FORMAT_DOUBLE, &value);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeSetPropertyString(
        JNIEnv *env, jobject thiz, jlong ptr, jstring name, jstring value) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return -1;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    const char *c_val = (*env)->GetStringUTFChars(env, value, NULL);
    int r = mpv_set_property_string(s->mpv, c_name, c_val);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    (*env)->ReleaseStringUTFChars(env, value, c_val);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeSetPropertyBoolean(
        JNIEnv *env, jobject thiz, jlong ptr, jstring name, jboolean value) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return -1;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    int val = value ? 1 : 0;
    int r = mpv_set_property(s->mpv, c_name, MPV_FORMAT_FLAG, &val);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    return r;
}

JNIEXPORT jint JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeObserveProperty(
        JNIEnv *env, jobject thiz, jlong ptr, jstring name, jint id) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return -1;
    const char *c_name = (*env)->GetStringUTFChars(env, name, NULL);
    int r = mpv_observe_property(s->mpv, id, c_name, MPV_FORMAT_NODE);
    (*env)->ReleaseStringUTFChars(env, name, c_name);
    return r;
}

/* 处理事件（由 Kotlin 在 wake 回调中调用）→ 分发属性变更 */
JNIEXPORT void JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeProcessEvents(JNIEnv *env, jobject thiz, jlong ptr) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    if (!s) return;
    while (1) {
        mpv_event *event = mpv_wait_event(s->mpv, 0);
        if (event->event_id == MPV_EVENT_NONE) break;
        switch (event->event_id) {
        case MPV_EVENT_PROPERTY_CHANGE: {
            mpv_event_property *prop = event->data;
            if (!prop) break;
            if (prop->format == MPV_FORMAT_DOUBLE) {
                call_kotlin_event(s, "eventProperty", "(Ljava/lang/String;D)V",
                                   prop->name, *(double *)prop->data);
            } else if (prop->format == MPV_FORMAT_FLAG) {
                call_kotlin_event(s, "eventProperty", "(Ljava/lang/String;Z)V",
                                   prop->name, (int)(*(int *)prop->data));
            } else if (prop->format == MPV_FORMAT_STRING) {
                jstring jname = (*env)->NewStringUTF(env, prop->name);
                jstring jval = (*env)->NewStringUTF(env, *(char **)prop->data);
                jclass cls = (*env)->GetObjectClass(env, s->global_ref);
                jmethodID mid = (*env)->GetMethodID(env, cls, "eventProperty", "(Ljava/lang/String;Ljava/lang/String;)V");
                if (mid) (*env)->CallVoidMethod(env, s->global_ref, mid, jname, jval);
            } else if (prop->format == MPV_FORMAT_INT64) {
                call_kotlin_event(s, "eventProperty", "(Ljava/lang/String;J)V",
                                   prop->name, (long)(*(int64_t *)prop->data));
            } else if (prop->format == MPV_FORMAT_NONE) {
                /* 属性不可用 */
                jstring jname = (*env)->NewStringUTF(env, prop->name);
                jclass cls = (*env)->GetObjectClass(env, s->global_ref);
                jmethodID mid = (*env)->GetMethodID(env, cls, "eventProperty", "(Ljava/lang/String;)V");
                if (mid) (*env)->CallVoidMethod(env, s->global_ref, mid, jname);
            }
            break;
        }
        case MPV_EVENT_LOG_MESSAGE: {
            mpv_event_log_message *msg = event->data;
            jstring jprefix = (*env)->NewStringUTF(env, msg->prefix);
            jstring jtext = (*env)->NewStringUTF(env, msg->text);
            jclass cls = (*env)->GetObjectClass(env, s->global_ref);
            jmethodID mid = (*env)->GetMethodID(env, cls, "logMessage", "(Ljava/lang/String;ILjava/lang/String;)V");
            if (mid) (*env)->CallVoidMethod(env, s->global_ref, mid, jprefix, (jint)msg->log_level, jtext);
            break;
        }
        case MPV_EVENT_FILE_LOADED:
        case MPV_EVENT_START_FILE:
        case MPV_EVENT_END_FILE:
        case MPV_EVENT_IDLE:
        case MPV_EVENT_SEEK:
        case MPV_EVENT_PLAYBACK_RESTART: {
            jclass cls = (*env)->GetObjectClass(env, s->global_ref);
            jmethodID mid = (*env)->GetMethodID(env, cls, "mpvEvent", "(I)V");
            if (mid) (*env)->CallVoidMethod(env, s->global_ref, mid, (jint)event->event_id);
            break;
        }
        default: break;
        }
    }
}

JNIEXPORT jlong JNICALL
Java_com_slogan_tv_player_core_MpvCore_nativeGetMpvHandle(JNIEnv *env, jobject thiz, jlong ptr) {
    SloganMpv *s = (SloganMpv *)(intptr_t)ptr;
    return s ? (jlong)(intptr_t)s->mpv : 0;
}
