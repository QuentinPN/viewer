// libplayer.so (mpv-android) asks libmpv for its log at "v" level and copies each
// line to logcat at VERBOSE priority, before the Kotlin observer can redact it.
// lavf prints the http headers it did not use at that level, the Emby token
// among them, when it opens an HLS transcode (ADR 0052).
#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdint.h>

typedef int32_t (*set_minimum_priority_fn)(int32_t);

/* Drops VERBOSE logcat lines of this process; false before Android 11 (API 30). */
JNIEXPORT jboolean JNICALL
Java_io_github_quentinpn_viewer_tv_player_NativeLog_quietVerbose(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    void *liblog = dlopen("liblog.so", RTLD_NOW);
    if (liblog == NULL) return JNI_FALSE;
    set_minimum_priority_fn set = (set_minimum_priority_fn)dlsym(liblog, "__android_log_set_minimum_priority");
    if (set == NULL) return JNI_FALSE;
    set(ANDROID_LOG_DEBUG);
    return JNI_TRUE;
}
