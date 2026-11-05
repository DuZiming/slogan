/* Slogan mpv EGL 渲染桥（自有代码）：
 * - ANativeWindow_fromSurface 绑定 SurfaceView
 * - EGL display/context/window-surface
 * - mpv_render_context_create(api=opengl)
 * - 渲染线程：update 回调驱动 mpv_render_context_render + eglSwapBuffers
 */
#include <jni.h>
#include <pthread.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <mpv/client.h>
#include <mpv/render.h>
#include <mpv/render_gl.h>

static mpv_handle *g_mpv = NULL;
static mpv_render_context *g_mpv_gl = NULL;
static EGLDisplay g_egl_display = EGL_NO_DISPLAY;
static EGLContext g_egl_context = EGL_NO_CONTEXT;
static EGLSurface g_egl_surface = EGL_NO_SURFACE;
static ANativeWindow *g_window = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_wake = PTHREAD_COND_INITIALIZER;
static pthread_t g_render_thread;
static volatile int g_render_running = 0;
static volatile int g_frame_pending = 0;

static void *get_proc_address(void *fn_ctx, const char *name) {
    (void) fn_ctx;
    return (void *) eglGetProcAddress(name);
}

static void on_mpv_redraw(void *ctx) {
    (void) ctx;
    pthread_mutex_lock(&g_lock);
    g_frame_pending = 1;
    pthread_cond_signal(&g_wake);
    pthread_mutex_unlock(&g_lock);
}

static void *render_loop(void *arg) {
    (void) arg;
    while (g_render_running) {
        pthread_mutex_lock(&g_lock);
        while (g_render_running && !g_frame_pending) {
            struct timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_nsec += 100 * 1000 * 1000; /* 100ms 兜底唤醒 */
            if (ts.tv_nsec >= 1000000000L) { ts.tv_sec += 1; ts.tv_nsec -= 1000000000L; }
            pthread_cond_timedwait(&g_wake, &g_lock, &ts);
        }
        int pending = g_frame_pending;
        g_frame_pending = 0;
        pthread_mutex_unlock(&g_lock);
        if (!g_render_running) break;
        if (!pending) continue;

        if (g_egl_display != EGL_NO_DISPLAY && eglMakeCurrent(g_egl_display, g_egl_surface, g_egl_surface, g_egl_context)) {
            mpv_render_param params[] = {
                {MPV_RENDER_PARAM_OPENGL_FBO, &(int){0}},
                {MPV_RENDER_PARAM_FLIP_Y, &(int){1}},
                {MPV_RENDER_PARAM_INVALID, NULL},
            };
            mpv_render_context_render(g_mpv_gl, params);
            eglSwapBuffers(g_egl_display, g_egl_surface);
        }
    }
    return NULL;
}

/* 由 Kotlin 在 MPVLib.init() 之后调用：传入底层 mpv 指针。 */
JNIEXPORT void JNICALL
Java_com_slogan_tv_player_render_MpvRenderBridge_nativeSetMpv(
        JNIEnv *env, jobject thiz, jlong mpv_ptr) {
    (void) env; (void) thiz;
    g_mpv = (mpv_handle *) (intptr_t) mpv_ptr;
}

JNIEXPORT jboolean JNICALL
Java_com_slogan_tv_player_render_MpvRenderBridge_nativeAttach(
        JNIEnv *env, jobject thiz, jobject surface) {
    (void) thiz;
    pthread_mutex_lock(&g_lock);
    /* 清理旧状态 */
    if (g_render_running) {
        g_render_running = 0;
        pthread_cond_signal(&g_wake);
        pthread_mutex_unlock(&g_lock);
        pthread_join(g_render_thread, NULL);
        pthread_mutex_lock(&g_lock);
    }
    if (g_mpv_gl) { mpv_render_context_free(g_mpv_gl); g_mpv_gl = NULL; }
    if (g_egl_surface != EGL_NO_SURFACE) { eglDestroySurface(g_egl_display, g_egl_surface); g_egl_surface = EGL_NO_SURFACE; }
    if (g_egl_context != EGL_NO_CONTEXT) { eglDestroyContext(g_egl_display, g_egl_context); g_egl_context = EGL_NO_CONTEXT; }
    if (g_window) { ANativeWindow_release(g_window); g_window = NULL; }

    g_window = ANativeWindow_fromSurface(env, surface);
    if (!g_window) goto fail;

    g_egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (g_egl_display == EGL_NO_DISPLAY) goto fail;
    if (!eglInitialize(g_egl_display, NULL, NULL)) goto fail;

    const EGLint cfg_attrs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 0,
        EGL_NONE,
    };
    EGLConfig config;
    EGLint num_configs = 0;
    if (!eglChooseConfig(g_egl_display, cfg_attrs, &config, 1, &num_configs) || num_configs < 1) goto fail;

    const EGLint ctx_attrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    g_egl_context = eglCreateContext(g_egl_display, config, EGL_NO_CONTEXT, ctx_attrs);
    if (g_egl_context == EGL_NO_CONTEXT) goto fail;

    g_egl_surface = eglCreateWindowSurface(g_egl_display, config, g_window, NULL);
    if (g_egl_surface == EGL_NO_SURFACE) goto fail;

    if (g_mpv != NULL) {
        mpv_render_param params[] = {
            {MPV_RENDER_PARAM_API_TYPE, MPV_RENDER_API_TYPE_OPENGL},
            {MPV_RENDER_PARAM_OPENGL_INIT_PARAM, &(mpv_opengl_init_params){get_proc_address, NULL}},
            {MPV_RENDER_PARAM_INVALID, NULL},
        };
        if (mpv_render_context_create(&g_mpv_gl, g_mpv, params) < 0) goto fail;
        mpv_render_context_set_update_callback(g_mpv_gl, on_mpv_redraw, NULL);
    }

    g_render_running = 1;
    g_frame_pending = 1;
    pthread_create(&g_render_thread, NULL, render_loop, NULL);
    pthread_mutex_unlock(&g_lock);
    return JNI_TRUE;

fail:
    if (g_mpv_gl) { mpv_render_context_free(g_mpv_gl); g_mpv_gl = NULL; }
    if (g_egl_surface != EGL_NO_SURFACE) { eglDestroySurface(g_egl_display, g_egl_surface); g_egl_surface = EGL_NO_SURFACE; }
    if (g_egl_context != EGL_NO_CONTEXT) { eglDestroyContext(g_egl_display, g_egl_context); g_egl_context = EGL_NO_CONTEXT; }
    if (g_window) { ANativeWindow_release(g_window); g_window = NULL; }
    pthread_mutex_unlock(&g_lock);
    return JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_slogan_tv_player_render_MpvRenderBridge_nativeDetach(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    pthread_mutex_lock(&g_lock);
    if (g_render_running) {
        g_render_running = 0;
        pthread_cond_signal(&g_wake);
        pthread_mutex_unlock(&g_lock);
        pthread_join(g_render_thread, NULL);
        pthread_mutex_lock(&g_lock);
    }
    if (g_mpv_gl) { mpv_render_context_free(g_mpv_gl); g_mpv_gl = NULL; }
    if (g_egl_surface != EGL_NO_SURFACE) { eglDestroySurface(g_egl_display, g_egl_surface); g_egl_surface = EGL_NO_SURFACE; }
    if (g_egl_context != EGL_NO_CONTEXT) { eglDestroyContext(g_egl_display, g_egl_context); g_egl_context = EGL_NO_CONTEXT; }
    if (g_window) { ANativeWindow_release(g_window); g_window = NULL; }
    pthread_mutex_unlock(&g_lock);
}
