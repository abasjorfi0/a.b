// JNI glue: runs byedpi (renamed main -> byedpi_main) inside the app's :vpn process.
// byedpi keeps global state, so it is started once per process; the service
// kills its own process on stop (see ZapretVpnService.shutdown()).
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

extern int byedpi_main(int argc, char **argv);

JNIEXPORT jint JNICALL
Java_app_zapret_mobile_Native_runProxy(JNIEnv *env, jclass clazz, jobjectArray args)
{
    jsize n = (*env)->GetArrayLength(env, args);
    char **argv = calloc((size_t)n + 1, sizeof(char *));
    if (!argv)
        return -1;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring)(*env)->GetObjectArrayElement(env, args, i);
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        argv[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    __android_log_print(ANDROID_LOG_INFO, "zapret", "starting byedpi with %d args", (int)n);
    int rc = byedpi_main((int)n, argv);
    __android_log_print(ANDROID_LOG_INFO, "zapret", "byedpi exited rc=%d", rc);
    return rc;
}
