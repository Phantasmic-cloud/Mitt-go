// JNI bridge for libmittgocore.so.
//
// Kotlin calls GoCoreJni.start/snapshot/events/... and this file forwards to
// the C entry points exported by the Go core (see main.go). Every string
// returned from Go is a C allocation freed here after copying it into a
// jstring, so Kotlin never owns native memory.

#include <jni.h>
#include <stddef.h>

#include "_cgo_export.h"

static jstring take_string(JNIEnv *env, char *value) {
    jstring out = (*env)->NewStringUTF(env, value == NULL ? "" : value);
    if (value != NULL) {
        MittGoFree(value);
    }
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_phantasmic_mitt_go_GoCoreJni_start(JNIEnv *env, jobject thiz, jstring config) {
    (void)thiz;
    if (config == NULL) {
        return (*env)->NewStringUTF(env, "config is required");
    }
    const char *chars = (*env)->GetStringUTFChars(env, config, NULL);
    if (chars == NULL) {
        return (*env)->NewStringUTF(env, "out of memory");
    }
    char *err = MittGoStart((char *)chars);
    (*env)->ReleaseStringUTFChars(env, config, chars);
    return take_string(env, err);
}

JNIEXPORT void JNICALL
Java_com_phantasmic_mitt_go_GoCoreJni_stop(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    MittGoStop();
}

JNIEXPORT jstring JNICALL
Java_com_phantasmic_mitt_go_GoCoreJni_snapshot(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return take_string(env, MittGoSnapshot());
}

JNIEXPORT jstring JNICALL
Java_com_phantasmic_mitt_go_GoCoreJni_record(JNIEnv *env, jobject thiz, jstring id) {
    (void)thiz;
    if (id == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    const char *chars = (*env)->GetStringUTFChars(env, id, NULL);
    if (chars == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    char *body = MittGoRecord((char *)chars);
    (*env)->ReleaseStringUTFChars(env, id, chars);
    return take_string(env, body);
}

JNIEXPORT jstring JNICALL
Java_com_phantasmic_mitt_go_GoCoreJni_events(JNIEnv *env, jobject thiz, jlong since, jint limit) {
    (void)thiz;
    return take_string(env, MittGoEvents((long long)since, (int)limit));
}

JNIEXPORT void JNICALL
Java_com_phantasmic_mitt_go_GoCoreJni_clear(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    MittGoClear();
}

JNIEXPORT jstring JNICALL
Java_com_phantasmic_mitt_go_GoCoreJni_dump(JNIEnv *env, jobject thiz, jint limit, jint maxBody) {
    (void)thiz;
    return take_string(env, MittGoDump((int)limit, (int)maxBody));
}

JNIEXPORT jstring JNICALL
Java_com_phantasmic_mitt_go_GoCoreJni_setSocks(JNIEnv *env, jobject thiz, jboolean enabled) {
    (void)thiz;
    return take_string(env, MittGoSetSocks(enabled == JNI_TRUE ? 1 : 0));
}

JNIEXPORT jstring JNICALL
Java_com_phantasmic_mitt_go_GoCoreJni_setDomainFilter(JNIEnv *env, jobject thiz, jstring config) {
    (void)thiz;
    if (config == NULL) {
        return (*env)->NewStringUTF(env, "config is required");
    }
    const char *chars = (*env)->GetStringUTFChars(env, config, NULL);
    if (chars == NULL) {
        return (*env)->NewStringUTF(env, "out of memory");
    }
    char *err = MittGoSetDomainFilter((char *)chars);
    (*env)->ReleaseStringUTFChars(env, config, chars);
    return take_string(env, err);
}

JNIEXPORT jstring JNICALL
Java_com_phantasmic_mitt_go_GoCoreJni_setAsyncUpload(JNIEnv *env, jobject thiz, jstring config) {
    (void)thiz;
    if (config == NULL) {
        return (*env)->NewStringUTF(env, "config is required");
    }
    const char *chars = (*env)->GetStringUTFChars(env, config, NULL);
    if (chars == NULL) {
        return (*env)->NewStringUTF(env, "out of memory");
    }
    char *err = MittGoSetAsyncUpload((char *)chars);
    (*env)->ReleaseStringUTFChars(env, config, chars);
    return take_string(env, err);
}

JNIEXPORT jstring JNICALL
Java_com_phantasmic_mitt_go_GoCoreJni_asyncEvents(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return take_string(env, MittGoAsyncEvents());
}
