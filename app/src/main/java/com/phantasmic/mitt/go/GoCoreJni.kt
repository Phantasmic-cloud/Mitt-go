package com.phantasmic.mitt.go

import android.util.Log

/**
 * Thin JNI facade over `libmittgocore.so`.
 *
 * The Go core is built with `-buildmode=c-shared` plus a small C bridge
 * (`core/cmd/mittgod/jni_bridge.c`), so it runs inside the app process. Loading
 * is done lazily and the failure reason is kept so the UI can surface it
 * instead of dying with an `UnsatisfiedLinkError`.
 */
internal object GoCoreJni {
    private const val TAG = "MittGoJni"

    @Volatile
    private var ready = false

    @Volatile
    private var failure: String? = null

    fun load(): Boolean {
        if (ready) return true
        if (failure != null) return false
        synchronized(this) {
            if (ready) return true
            if (failure != null) return false
            return try {
                System.loadLibrary("mittgocore")
                ready = true
                Log.i(TAG, "libmittgocore.so loaded")
                true
            } catch (t: Throwable) {
                failure = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "loadLibrary(mittgocore) failed", t)
                false
            }
        }
    }

    fun failureMessage(): String = failure ?: "unknown"

    /** Returns an empty string on success, otherwise the error message. */
    external fun start(coreJson: String): String

    external fun stop()

    external fun events(since: Long, limit: Int): String

    external fun clear()

    /** Returns an empty string on success, otherwise the error message. */
    external fun setSocks(enabled: Boolean): String

    /** 域名过滤规则（mode + white/black），返回空串表示成功。 */
    external fun setDomainFilter(config: String): String

    /** 上游代理配置（enabled + host/port/user/password），返回空串表示成功。 */
    external fun setUpstream(config: String): String

    /** 旁路上传配置（enabled + configs），返回空串表示成功。 */
    external fun setAsyncUpload(config: String): String

    /** 取走内核攒下的旁路上传日志/通知事件（JSON）。 */
    external fun asyncEvents(): String
}