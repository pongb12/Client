#ifndef POJAVLAUNCHER_LOG_H
#define POJAVLAUNCHER_LOG_H

#include <android/log.h>

#ifndef TAG
#define TAG "jrelog"
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,    TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,    TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,    TAG, __VA_ARGS__)
/* Debug-level native logging is compiled out of release builds: it costs a
 * logcat write per call (which the Java-side logcat reader then also writes to
 * latestlog.txt), which is measurable on low-end devices when it sits on the
 * input/rendering paths. Build with LOCAL_CFLAGS += -DDEBUG to re-enable. */
#ifdef DEBUG
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,    TAG, __VA_ARGS__)
#else
#define LOGD(...) ((void)0)
#endif

#ifdef __cplusplus
}
#endif

#endif

