package com.phantasmic.mitt.go

import android.content.Context
import android.util.Log
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class BodyView(
    val size: Int,
    val truncated: Boolean,
    val encoding: String,
    val data: String,
)

data class HeaderItem(val name: String, val value: String)

data class CaptureSummary(
    val id: String,
    val uid: Int,
    val packageName: String,
    val startedAt: Long,
    val durationMs: Long,
    val method: String,
    val proto: String = "",
    val url: String,
    val host: String,
    val statusCode: Int,
    val contentType: String,
    val size: Int,
    val error: String,
)

data class ProxySnapshot(
    val running: Boolean,
    val httpPort: Int,
    val tunnelPort: Int,
    val socksPort: Int,
    val socksEnabled: Boolean,
    val total: Int,
    val records: List<CaptureSummary>,
)

data class CaptureDetail(
    val id: String,
    val uid: Int,
    val packageName: String,
    val startedAt: Long,
    val durationMs: Long,
    val method: String,
    val url: String,
    val proto: String,
    val remoteAddr: String = "",
    val statusCode: Int,
    val requestHeaders: List<HeaderItem>,
    val responseHeaders: List<HeaderItem>,
    val requestBody: BodyView,
    val responseBody: BodyView,
    val error: String,
)

/**
 * In-process wrapper around the Go MITM core.
 *
 * The core is compiled as `libmittgocore.so` (Go `-buildmode=c-shared` with a
 * JNI bridge) and loaded through `System.loadLibrary`. There is no child
 * process, no control port and no token handshake any more: every call is a
 * direct JNI call that returns the same JSON the old HTTP control endpoint
 * produced, so the parsing and history logic below is unchanged.
 */
object ProxyCore {
    private const val TAG = "MittGoCore"

    @Volatile
    private var running = false

    @Volatile
    private var cachedSnapshot: ProxySnapshot? = null

    /** 停止内核前会把最近若干条完整记录导出到这里，停止后仍能查看详情。 */
    private val historyCache = object : LruCache<String, CaptureDetail>(40) {}

    private const val HISTORY_FILE = "capture-history.json"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var historyLoaded = false

    /** 只保存 context，供历史记录读写；不触发任何 IO。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    private fun historyFile(): File? = appContext?.let { File(it.filesDir, HISTORY_FILE) }

    /** 上会话的记录落到磁盘，重开 App 也能看到列表与详情。 */
    @Synchronized
    private fun lazyLoadHistory() {
        if (historyLoaded) return
        historyLoaded = true
        val body = runCatching { historyFile()?.takeIf { it.exists() }?.readText() }.getOrNull()
        if (!body.isNullOrBlank()) ingestHistory(body)
    }

    /** 解析 dump 的响应，填充详情缓存并把摘要合并进当前快照。 */
    private fun ingestHistory(body: String) {
        val array = runCatching { JSONObject(body).optJSONArray("records") }.getOrNull() ?: return
        val restored = ArrayList<CaptureSummary>(array.length())
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            val detail = runCatching { parseDetail(json) }.getOrNull() ?: continue
            if (detail.id.isEmpty()) continue
            historyCache.put(detail.id, detail)
            restored += detail.toSummary()
        }
        if (restored.isEmpty()) return
        val existing = cachedSnapshot?.records.orEmpty()
        val ids = existing.mapTo(HashSet()) { it.id }
        val merged = (existing + restored.filter { it.id !in ids })
            .sortedByDescending { it.startedAt }
            .take(500)
        val base = cachedSnapshot
        cachedSnapshot = ProxySnapshot(
            running = base?.running ?: false,
            httpPort = base?.httpPort ?: 9099,
            tunnelPort = base?.tunnelPort ?: 9098,
            socksPort = base?.socksPort ?: 1080,
            socksEnabled = base?.socksEnabled ?: true,
            total = merged.size,
            records = merged,
        )
    }

    private fun CaptureDetail.toSummary(): CaptureSummary {
        val host = url.substringAfter("://", url).substringBefore('/')
        val contentType = responseHeaders
            .firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }
            ?.value
            .orEmpty()
        return CaptureSummary(
            id = id,
            uid = uid,
            packageName = packageName,
            startedAt = startedAt,
            durationMs = durationMs,
            method = method,
            proto = proto,
            url = url,
            host = host,
            statusCode = statusCode,
            contentType = contentType,
            size = responseBody.size,
            error = error,
        )
    }

    @Synchronized
    fun start(
        context: Context,
        httpPort: Int,
        tunnelPort: Int,
        socksPort: Int,
        socksEnabled: Boolean,
        domainMode: FilterMode = FilterMode.OFF,
        domainWhite: Set<String> = emptySet(),
        domainBlack: Set<String> = emptySet(),
        certMode: CertMode = CertMode.BUILTIN,
    ) {
        if (running) return
        check(GoCoreJni.load()) { "libmittgocore.so 加载失败：${GoCoreJni.failureMessage()}" }
        val caCert = readAsset(context, "ca/ca.crt")
        val caKey = readAsset(context, "ca/ca_key.pem")
        val caDir = File(context.filesDir, "mitm-ca").also { it.mkdirs() }
        val core = JSONObject()
            .put("httpPort", httpPort)
            .put("tunnelPort", tunnelPort)
            .put("socksPort", socksPort)
            .put("socksEnabled", socksEnabled)
            .put("caDir", caDir.absolutePath)
            .put("caCertPem", caCert)
            .put("caKeyPem", caKey)
            .put("domainMode", domainMode.name)
            .put("domainWhite", JSONArray(domainWhite.toList()))
            .put("domainBlack", JSONArray(domainBlack.toList()))
            .put("certMode", certMode.name)
        MittLog.debug("mitm") {
            "libmittgocore.so 已加载，启动内核 http=$httpPort tunnel=$tunnelPort"
        }
        val error = GoCoreJni.start(core.toString())
        check(error.isEmpty()) { "Go 内核启动失败：$error" }
        running = true
        MittLog.debug("mitm") { "Go 内核就绪" }
        Log.i(TAG, "Go MITM core running in-process: http=$httpPort tunnel=$tunnelPort socks=$socksPort")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        MittLog.debug("mitm") { "Go 内核已停止" }
        runCatching { GoCoreJni.stop() }
            .onFailure { Log.w(TAG, "stopping the Go core failed", it) }
    }

    fun isRunning(): Boolean = running

    fun clearRecords() {
        historyCache.evictAll()
        runCatching { historyFile()?.delete() }
        if (running) {
            runCatching { GoCoreJni.clear() }
                .onFailure { Log.w(TAG, "clearing Go records failed", it) }
        }
        cachedSnapshot = cachedSnapshot?.copy(records = emptyList(), total = 0)
    }

    /** 同步域名过滤到 Go 内核；关闭状态下也要调用一次，清掉旧规则。 */
    fun setDomainFilter(mode: FilterMode, white: Set<String>, black: Set<String>) {
        val payload = JSONObject()
            .put("mode", mode.name)
            .put("white", JSONArray(white.toList()))
            .put("black", JSONArray(black.toList()))
            .toString()
        val error = runCatching { GoCoreJni.setDomainFilter(payload) }
            .getOrDefault("JNI call failed")
        if (error.isNotEmpty()) {
            Log.w(TAG, "setDomainFilter: $error")
            MittLog.info("filter", "域名过滤同步失败：$error")
        }
    }

    /** 下发旁路上传配置；关闭时也要调一次，让内核清掉旧规则。 */
    fun setAsyncUpload(enabled: Boolean, configs: List<String>) {
        val payload = JSONObject()
            .put("enabled", enabled)
            .put("configs", JSONArray(configs))
            .toString()
        val error = runCatching { GoCoreJni.setAsyncUpload(payload) }
            .getOrDefault("JNI call failed")
        if (error.isNotEmpty()) {
            Log.w(TAG, "setAsyncUpload: $error")
            MittLog.warn("async", "旁路上传配置下发失败：$error")
        }
    }

    /** 取走内核攒下的旁路上传事件；内核没起就返回空。 */
    fun asyncEvents(): List<AsyncEvent> {
        if (!running) return emptyList()
        if (!GoCoreJni.load()) return emptyList()
        val body = runCatching { GoCoreJni.asyncEvents() }.getOrNull() ?: return emptyList()
        if (body.isBlank()) return emptyList()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val array = json.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<AsyncEvent>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val text = item.optString("text")
            if (text.isBlank()) continue
            out += AsyncEvent(
                kind = item.optString("kind", "log"),
                level = item.optString("level", "info"),
                title = item.optString("title"),
                text = text,
                time = item.optLong("time"),
            )
        }
        return out
    }

    fun setSocksEnabled(enabled: Boolean) {
        if (!running) return
        val error = runCatching { GoCoreJni.setSocks(enabled) }.getOrDefault("JNI call failed")
        if (error.isNotEmpty()) Log.w(TAG, "setSocksEnabled($enabled): $error")
    }

    /** 直接返回上次缓存的快照，不发网络请求；页面重建时可立即出内容。 */
    fun peekSnapshot(): ProxySnapshot? = cachedSnapshot

    fun snapshot(): ProxySnapshot {
        if (!running) {
            lazyLoadHistory()
            return cachedSnapshot?.copy(running = false)
                ?: ProxySnapshot(false, 9099, 9098, 1080, true, 0, emptyList())
        }
        val json = JSONObject(GoCoreJni.snapshot())
        val records = mutableListOf<CaptureSummary>()
        val array = json.optJSONArray("records")
        if (array != null) {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                records += CaptureSummary(
                    id = item.optString("id"),
                    uid = item.optInt("uid"),
                    packageName = item.optString("packageName"),
                    startedAt = item.optLong("startedAt"),
                    durationMs = item.optLong("durationMs"),
                    method = item.optString("method"),
                    proto = item.optString("proto"),
                    url = item.optString("url"),
                    host = item.optString("host"),
                    statusCode = item.optInt("statusCode"),
                    contentType = item.optString("contentType"),
                    size = item.optInt("size"),
                    error = item.optString("error"),
                )
            }
        }
        // 新会话的记录在前，上会话保留下来的记录接在后面（按 id 去重）。
        val previous = cachedSnapshot?.records.orEmpty()
        val seen = records.mapTo(HashSet()) { it.id }
        val merged = (records + previous.filter { it.id !in seen }).take(500)
        return ProxySnapshot(
            running = json.optBoolean("running"),
            httpPort = json.optInt("httpPort", 9099),
            tunnelPort = json.optInt("tunnelPort", 9098),
            socksPort = json.optInt("socksPort", 1080),
            socksEnabled = json.optBoolean("socksEnabled", true),
            total = merged.size,
            records = merged,
        ).also { cachedSnapshot = it }
    }

    fun detail(id: String): CaptureDetail {
        if (running) {
            val body = runCatching { GoCoreJni.record(id) }.getOrNull()
            if (body != null && body.length > 4) {
                val json = runCatching { JSONObject(body) }.getOrNull()
                if (json != null && json.optString("id").isNotEmpty()) {
                    return parseDetail(json)
                }
            }
        }
        historyCache.get(id)?.let { return it }
        error("这条记录已经不在了（内核已停止，且不在最近 40 条缓存里）")
    }

    /** 供记录仓库复用同一套解析逻辑。 */
    internal fun parseDetailJson(json: JSONObject): CaptureDetail = parseDetail(json)

    /** 增量拉取：返回 (最新 seq, 变更过的记录)。 */
    fun events(since: Long, limit: Int = 30): Pair<Long, List<CaptureDetail>> {
        val body = GoCoreJni.events(since, limit)
        val json = JSONObject(body)
        val array = json.optJSONArray("records") ?: return since to emptyList()
        val out = ArrayList<CaptureDetail>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            runCatching { parseDetail(item) }.getOrNull()?.let { out += it }
        }
        return json.optLong("seq", since) to out
    }

    /** 把记录写成与事件流相同的 JSON 结构，供 Kotlin 侧持久化。 */
    fun detailToJson(detail: CaptureDetail): JSONObject = JSONObject()
        .put("id", detail.id)
        .put("uid", detail.uid)
        .put("packageName", detail.packageName)
        .put("startedAt", detail.startedAt)
        .put("durationMs", detail.durationMs)
        .put("method", detail.method)
        .put("url", detail.url)
        .put("proto", detail.proto)
        .put("remoteAddr", detail.remoteAddr)
        .put("statusCode", detail.statusCode)
        .put("requestHeader", headersToJson(detail.requestHeaders))
        .put("responseHeader", headersToJson(detail.responseHeaders))
        .put("requestBody", bodyToJson(detail.requestBody))
        .put("responseBody", bodyToJson(detail.responseBody))
        .put("error", detail.error)

    private fun headersToJson(headers: List<HeaderItem>): JSONObject {
        val out = JSONObject()
        headers.groupBy { it.name }.forEach { (name, values) ->
            val array = org.json.JSONArray()
            values.forEach { array.put(it.value) }
            out.put(name, array)
        }
        return out
    }

    private fun bodyToJson(body: BodyView): JSONObject = JSONObject()
        .put("size", body.size)
        .put("truncated", body.truncated)
        .put("encoding", body.encoding)
        .put("data", body.data)

    fun dumpHistoryForExit() {
        if (!running) return
        val body = runCatching { GoCoreJni.dump(40, 65536) }.getOrNull() ?: return
        historyFile()?.let { file -> runCatching { file.writeText(body) } }
        ingestHistory(body)
        Log.i(TAG, "history dumped and persisted before shutdown")
    }

    private fun parseDetail(json: JSONObject) = CaptureDetail(
        id = json.optString("id"),
        uid = json.optInt("uid"),
        packageName = json.optString("packageName"),
        startedAt = json.optLong("startedAt"),
        durationMs = json.optLong("durationMs"),
        method = json.optString("method"),
        url = json.optString("url"),
        proto = json.optString("proto"),
        remoteAddr = json.optString("remoteAddr"),
        statusCode = json.optInt("statusCode"),
        requestHeaders = parseHeaders(json.optJSONObject("requestHeader")),
        responseHeaders = parseHeaders(json.optJSONObject("responseHeader")),
        requestBody = parseBody(json.optJSONObject("requestBody")),
        responseBody = parseBody(json.optJSONObject("responseBody")),
        error = json.optString("error"),
    )

    private fun parseHeaders(obj: JSONObject?): List<HeaderItem> {
        if (obj == null) return emptyList()
        val out = mutableListOf<HeaderItem>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val values = obj.optJSONArray(name) ?: continue
            for (i in 0 until values.length()) {
                out += HeaderItem(name, values.optString(i))
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun parseBody(obj: JSONObject?): BodyView {
        if (obj == null) return BodyView(0, false, "text", "")
        return BodyView(
            size = obj.optInt("size"),
            truncated = obj.optBoolean("truncated"),
            encoding = obj.optString("encoding", "text"),
            data = obj.optString("data"),
        )
    }

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}