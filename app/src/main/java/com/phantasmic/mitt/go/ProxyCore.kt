package com.phantasmic.mitt.go

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * In-process wrapper around the Go MITM core.
 *
 * The core is compiled as `libmittgocore.so` (Go `-buildmode=c-shared` with a
 * JNI bridge) and loaded through `System.loadLibrary`. There is no child
 * process, no control port and no token handshake: every call is a direct JNI
 * call that returns JSON.
 *
 * 这里只负责「引擎」：启动/停止、下发配置、拉取增量变更。记录本身由
 * [CaptureStore] 持有，数据模型在 [CaptureModel]，JSON 编解码在 [CaptureCodec]。
 * 不要在页面里直接调 JNI。
 */
/** 内核的累计统计（抓包通知用）：字节数 + MITM 解密成功/失败条数。 */
data class TrafficStats(
    val up: Long = 0,
    val down: Long = 0,
    val ok: Long = 0,
    val fail: Long = 0,
)

object ProxyCore {
    private const val TAG = "MittGoCore"

    @Volatile
    private var running = false

    @Synchronized
    fun start(
        context: Context,
        httpPort: Int,
        tunnelPort: Int,
        socksPort: Int,
        socksEnabled: Boolean,
        upstream: UpstreamProxy = UpstreamProxy(),
        domainMode: FilterMode = FilterMode.OFF,
        domainWhite: Set<String> = emptySet(),
        domainBlack: Set<String> = emptySet(),
        certMode: CertMode = CertMode.BUILTIN,
    ) {
        if (running) return
        check(GoCoreJni.load()) { "libmittgocore.so 加载失败：${GoCoreJni.failureMessage()}" }
        val caCert = readAsset(context, "ca/ca.crt")
        val caKey = readAsset(context, "ca/ca_key.pem")
        val caDir = java.io.File(context.filesDir, "mitm-ca").also { it.mkdirs() }
        val core = JSONObject()
            .put("httpPort", httpPort)
            .put("tunnelPort", tunnelPort)
            .put("socksPort", socksPort)
            .put("socksEnabled", socksEnabled)
            .withUpstream(upstream)
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

    /** 清空内核里的记录；Kotlin 侧的记录由 [CaptureStore.clear] 负责。 */
    fun clearEngineRecords() {
        if (running) {
            runCatching { GoCoreJni.clear() }
                .onFailure { Log.w(TAG, "clearing Go records failed", it) }
        }
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

    /** 上游代理配置 → 内核字段。启动和热更新共用，字段名只在这里写一次。 */
    private fun JSONObject.withUpstream(upstream: UpstreamProxy): JSONObject = apply {
        put("upstreamEnabled", upstream.enabled)
        put("upstreamHost", upstream.host.trim())
        put("upstreamPort", upstream.port)
        put("upstreamUser", upstream.username.trim())
        put("upstreamPassword", upstream.password)
    }

    /**
     * 热更新上游代理；抓包中改设置立即生效，关闭时也要下发一次把旧代理清掉。
     * 内核没起时什么都不用做——下次 start 会把配置带上。
     */
    fun setUpstream(upstream: UpstreamProxy) {
        if (!running) return
        val payload = JSONObject().withUpstream(upstream).toString()
        val error = runCatching { GoCoreJni.setUpstream(payload) }
            .getOrDefault("JNI call failed")
        if (error.isNotEmpty()) {
            Log.w(TAG, "setUpstream: $error")
            MittLog.warn("proxy", "上游代理同步失败：$error")
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
            MittLog.warn("async", "旁路上传配置下发失败：$error", MittLog.GROUP_MODULE)
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
                name = item.optString("name"),
                detail = item.optString("detail"),
                notify = item.optBoolean("notify", false),
            )
        }
        return out
    }

    /**
     * 内核累计统计：上下行字节数 + 进 MITM 的流量里解密成功 / 失败的条数。
     * 内核没起就全是 0；抓包通知每秒问一次，字节数自己取差值算速率。
     */
    fun traffic(): TrafficStats {
        if (!running || !GoCoreJni.load()) return TrafficStats()
        val body = runCatching { GoCoreJni.traffic() }.getOrNull() ?: return TrafficStats()
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return TrafficStats()
        return TrafficStats(
            up = json.optLong("up"),
            down = json.optLong("down"),
            ok = json.optLong("ok"),
            fail = json.optLong("fail"),
        )
    }

    fun setSocksEnabled(enabled: Boolean) {
        if (!running) return
        val error = runCatching { GoCoreJni.setSocks(enabled) }.getOrDefault("JNI call failed")
        if (error.isNotEmpty()) Log.w(TAG, "setSocksEnabled($enabled): $error")
    }

    /** 增量拉取：返回 (最新 seq, 变更过的记录)。 */
    fun events(since: Long, limit: Int = 30): Pair<Long, List<CaptureDetail>> {
        val body = GoCoreJni.events(since, limit)
        val json = JSONObject(body)
        val array = json.optJSONArray("records") ?: return since to emptyList()
        val out = ArrayList<CaptureDetail>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            runCatching { CaptureCodec.parseDetailJson(item) }.getOrNull()?.let { out += it }
        }
        return json.optLong("seq", since) to out
    }

    private fun readAsset(context: Context, name: String): String =
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
}
