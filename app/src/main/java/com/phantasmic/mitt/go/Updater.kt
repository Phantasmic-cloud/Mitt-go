package com.phantasmic.mitt.go

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 检查更新、下载安装包。
 *
 * 关于页只订阅 [state] 并调用 [check] / [download] / [install]；版本比较、
 * 线路选择、超时、进度、安装都在这里，别在页面里另写一份。
 *
 * 线路：检测到系统代理就直连 GitHub，否则自动走 [MIRROR] 前缀。
 * 这个前缀只在代码里用，不显示在界面和提示里。
 */
object Updater {

    /** 检查更新的唯一状态源。 */
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val tag: String) : State {
            val version: String get() = tag.removePrefix("v")
        }
        data class Downloading(val received: Long, val total: Long, val speed: Long) : State
        data class Downloaded(val file: File) : State
        data object Failed : State
    }

    private const val REPO = "Phantasmic-cloud/Mitt-go"
    private const val RELEASE_PAGE = "https://github.com/$REPO/releases/latest"
    private const val TAG_PATTERN = "/releases/tag/(v?\\d+(?:\\.\\d+)*)"
    private const val TIMEOUT_MS = 15_000
    private const val MAX_HTML = 256 * 1024
    private const val MIRROR = "https://ghfast.top/"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    /** HTTP 状态码异常，和网络异常分开提示。 */
    private class HttpStatusException(val code: Int) : IOException()

    /** 下载到的内容不是完整 APK。 */
    private class IncompleteException : IOException()

    /** 点「检查更新」：查 GitHub 最新 release，比本地版本号。 */
    fun check(context: Context) {
        when (_state.value) {
            State.Checking, is State.Downloading, is State.Downloaded -> return
            else -> Unit
        }
        val app = context.applicationContext
        _state.value = State.Checking
        Thread {
            runCatching { fetchLatestTag(app) }
                .onSuccess { tag ->
                    _state.value = if (isNewer(tag, BuildConfig.VERSION_NAME)) {
                        State.Available(tag)
                    } else {
                        State.UpToDate
                    }
                }
                .onFailure { error ->
                    _state.value = State.Failed
                    toast(app, describe(error))
                }
        }.start()
    }

    /** 下载当前 [State.Available] 对应的安装包，完成后自动拉起安装界面。 */
    fun download(context: Context) {
        val available = _state.value as? State.Available ?: return
        val app = context.applicationContext
        val version = available.version
        val asset = "Mitt-go-arm64-v8a-release-$version.apk"
        val url = buildString {
            if (!hasSystemProxy(app)) append(MIRROR)
            append("https://github.com/$REPO/releases/download/${available.tag}/$asset")
        }
        _state.value = State.Downloading(0, -1, 0)
        Thread {
            runCatching { downloadTo(app, url, asset) }
                .onSuccess { file ->
                    _state.value = State.Downloaded(file)
                    Handler(Looper.getMainLooper()).post { install(app, file) }
                }
                .onFailure { error ->
                    _state.value = State.Available(available.tag)
                    toast(app, "下载失败：${describe(error)}")
                }
        }.start()
    }

    /** 用系统安装器打开已下载的 APK；失败时退回下载目录并提示手动安装。 */
    fun install(context: Context, file: File) {
        val app = context.applicationContext
        runCatching {
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
            app.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }.onFailure {
            // 没有可用的安装器时退回下载目录，至少能让用户手动装；8 MB 落盘放后台线程。
            Thread {
                val saved = runCatching {
                    DownloadStore.save(
                        app,
                        file.name,
                        "application/vnd.android.package-archive",
                        file.readBytes(),
                        pending = true,
                    )
                }.getOrNull()
                toast(
                    app,
                    if (saved != null) "安装包已保存到 Download/Mitt-go" else "无法启动安装，请检查「安装未知应用」权限",
                )
            }.start()
        }
    }

    // ---------- 网络 ----------

    /**
     * 读 `/releases/latest` 的 302 跳转地址拿到最新 tag。
     * 不跟随跳转，避免把整页 HTML 拉下来；个别镜像返回 200 时再退回解析正文。
     */
    private fun fetchLatestTag(context: Context): String {
        val target = if (hasSystemProxy(context)) RELEASE_PAGE else MIRROR + RELEASE_PAGE
        val connection = open(target, followRedirects = false)
        try {
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location") ?: throw HttpStatusException(code)
                return tagOf(location) ?: throw HttpStatusException(code)
            }
            if (code == 200) {
                val body = connection.inputStream.use { readLimited(it, MAX_HTML) }
                return tagOf(body) ?: throw HttpStatusException(code)
            }
            throw HttpStatusException(code)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadTo(context: Context, url: String, asset: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, asset)
        val connection = open(url, followRedirects = true)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            val total = connection.contentLengthLong
            var received = 0L
            var markTime = SystemClock.elapsedRealtime()
            var markBytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - markTime >= 300) {
                            val speed = (received - markBytes) * 1000 / (now - markTime)
                            _state.value = State.Downloading(received, total, speed)
                            markTime = now
                            markBytes = received
                        }
                    }
                }
            }
            if (received <= 0L) throw IncompleteException()
            if (total > 0L && received != total) throw IncompleteException()
            if (!looksLikeApk(target)) throw IncompleteException()
            _state.value = State.Downloading(received, received, 0)
            return target
        } catch (error: Exception) {
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, followRedirects: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = followRedirects
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Mitt-go/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
        }

    private fun readLimited(input: InputStream, limit: Int): String {
        val buffer = ByteArray(16 * 1024)
        val builder = StringBuilder()
        var total = 0
        while (total < limit) {
            val read = input.read(buffer)
            if (read < 0) break
            builder.append(String(buffer, 0, read, Charsets.UTF_8))
            total += read
        }
        return builder.toString()
    }

    private fun looksLikeApk(file: File): Boolean {
        if (file.length() < 1_000_000L) return false
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(2)
                input.read(head) == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            }
        }.getOrDefault(false)
    }

    /** 系统里配了 HTTP 代理（Wi-Fi 代理 / 代理 App 的系统代理）就认为能直连 GitHub。 */
    @Suppress("DEPRECATION")
    private fun hasSystemProxy(context: Context): Boolean {
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val proxy = manager?.defaultProxy
            if (proxy != null && (!proxy.host.isNullOrBlank() || proxy.pacFileUrl != null)) return true
        }
        if (!System.getProperty("http.proxyHost").isNullOrBlank()) return true
        if (!System.getProperty("https.proxyHost").isNullOrBlank()) return true
        return false
    }

    // ---------- 版本 ----------

    private val tagRegex = Regex(TAG_PATTERN)

    private fun tagOf(text: String): String? = tagRegex.find(text)?.groupValues?.getOrNull(1)

    /** 只比数字段，`v2.0.10` > `v2.0.9`，缺的位按 0 算。 */
    private fun isNewer(remote: String, local: String): Boolean {
        val a = versionNumbers(remote) ?: return false
        val b = versionNumbers(local) ?: return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun versionNumbers(version: String): List<Int>? =
        Regex("\\d+")
            .findAll(version.removePrefix("v"))
            .map { it.value.toIntOrNull() ?: 0 }
            .toList()
            .takeIf { it.isNotEmpty() }

    // ---------- 提示 ----------

    private fun describe(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "连接 GitHub 超时，请检查网络后重试"
        is UnknownHostException -> "无法连接 GitHub，请检查网络后重试"
        is HttpStatusException -> "服务器返回异常（${error.code}），请稍后重试"
        is IncompleteException -> "下载内容不完整，请重试"
        is IOException -> "网络异常，请稍后重试"
        else -> "检查更新失败，请稍后重试"
    }

    private fun toast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}
