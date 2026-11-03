package com.phantasmic.mitt.go

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

data class CaptureSession(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val count: Int,
    val bytes: Long,
)

/**
 * 抓包记录的权威存储。Go 只做 MITM 并通过 /events 增量回传，
 * 记录、内存上限、会话与历史全部由这里管理。
 */
object CaptureStore {
    private const val TAG = "MittGoStore"
    private const val CURRENT = "capture-current.jsonl"
    private const val SESSION_PREFIX = "session-"
    private const val HEADER_BYTES = 240L

    private val lock = Any()
    private val records = LinkedHashMap<String, CaptureDetail>()
    private val pending = LinkedHashMap<String, CaptureDetail>()
    private val sessionDetails = HashMap<String, CaptureDetail>()
    private var bytes = 0L
    private var lastSeq = 0L
    private var startedAt = 0L
    private var lastPublish = 0L

    @Volatile
    private var ctx: Context? = null

    @Volatile
    private var limitBytes: Long = 128L * 1024 * 1024

    @Volatile
    private var autoSaveDays: Int = 0

    private var pollJob: Job? = null

    /** 已经点进详情看过的记录 id：列表里会做“已读”变暗。 */
    private val _visited = MutableStateFlow<Set<String>>(emptySet())
    val visitedFlow = _visited.asStateFlow()
    private var visitedPrefs: android.content.SharedPreferences? = null


    private val _records = MutableStateFlow<List<CaptureSummary>>(emptyList())
    val recordFlow = _records.asStateFlow()

    private val _sessions = MutableStateFlow<List<CaptureSession>>(emptyList())
    val sessionFlow = _sessions.asStateFlow()

    /** 当前正在查看的历史会话（null = 会话列表）。 */
    private val _viewing = MutableStateFlow<CaptureSession?>(null)
    val viewingFlow = _viewing.asStateFlow()

    fun attach(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        // 当前内存里的记录不跨进程恢复：上一次异常退出留下的 current 文件
        // 按策略归档成历史会话，否则直接丢弃。
        runCatching {
            val current = file(CURRENT)
            if (current != null && current.exists()) {
                if (autoSaveDays > 0) {
                    archiveFile(current)
                } else {
                    current.delete()
                }
            }
        }
        runCatching { refreshSessions() }
        runCatching {
            val prefs = ctx?.getSharedPreferences("mitt_go_visited", Context.MODE_PRIVATE)
            visitedPrefs = prefs
            _visited.value = prefs?.getStringSet("ids", emptySet())?.toSet().orEmpty()
        }
    }

    fun setMemoryLimitMb(mb: Int) {
        limitBytes = mb.coerceIn(16, 2048).toLong() * 1024 * 1024
        synchronized(lock) { pruneLocked() }
        publish(force = true)
    }

    fun setAutoSaveDays(days: Int) {
        autoSaveDays = days.coerceIn(0, 30)
    }

    /**
     * 开始一次抓包会话。
     *
     * 这里刻意不清空已有记录：停止抓包后马上再开始，刚抓到的内容应该还在，
     * 只有用户点“清空记录”才真正删除。新内核的 seq 从 0 开始，所以 lastSeq 归零。
     */
    fun beginSession() {
        synchronized(lock) {
            lastSeq = 0
            pending.clear()
            startedAt = System.currentTimeMillis()
        }
        // 本次会话从空文件重新开始；上一次遗留的文件按策略归档或丢弃。
        file(CURRENT)?.let { current ->
            if (current.exists()) {
                if (autoSaveDays > 0) archiveFile(current) else current.delete()
            }
        }
        publish(force = true)
    }

    /**
     * 停止抓包：先落盘。开了自动保存就归档，并把内存清干净（内容已经在历史文件里）；
     * 没开自动保存则保留当前文件，下次启动/继续抓包还能看到。
     */
    fun endSession() {
        flushPending()
        if (autoSaveDays > 0) {
            archiveCurrent()
        } else {
            file(CURRENT)?.delete()
        }
        // 记录保留在内存里：停止抓包不该清空当前列表。
    }

    fun detail(id: String): CaptureDetail? =
        synchronized(lock) { records[id] } ?: synchronized(lock) { sessionDetails[id] }

    /** 打开一个归档会话，读取其中的记录并缓存详情，供列表/详情页使用。 */
    fun openSession(session: CaptureSession): List<CaptureSummary> {
        _viewing.value = session
        val dir = ctx?.filesDir ?: return emptyList()
        val file = File(dir, session.id)
        if (!file.exists()) return emptyList()
        val loaded = LinkedHashMap<String, CaptureDetail>()
        runCatching {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val detail = runCatching {
                        ProxyCore.parseDetailJson(JSONObject(line))
                    }.getOrNull() ?: return@forEach
                    loaded[detail.id] = detail
                }
            }
        }
        synchronized(lock) {
            sessionDetails.clear()
            sessionDetails.putAll(loaded)
        }
        return loaded.values.sortedByDescending { it.startedAt }.map { it.toSummary() }
    }

    fun clearSessionView() {
        _viewing.value = null
        synchronized(lock) { sessionDetails.clear() }
    }

    /** 只切换“正在查看的会话”，真正的文件读取交给调用方的 IO 协程。 */
    fun selectSession(session: CaptureSession?) {
        _viewing.value = session
    }

    /** 点进详情时调用：记下来，列表里这一行就变暗。 */
    fun markVisited(id: String) {
        if (id.isEmpty() || _visited.value.contains(id)) return
        val next = _visited.value + id
        // 只留最近 1000 条，避免无限增长。
        val trimmed = if (next.size > 1000) next.toList().takeLast(1000).toSet() else next
        _visited.value = trimmed
        runCatching { visitedPrefs?.edit()?.putStringSet("ids", trimmed)?.apply() }
    }

    fun isVisited(id: String): Boolean = _visited.value.contains(id)

    fun clear() {
        synchronized(lock) {
            records.clear()
            pending.clear()
            bytes = 0
            lastSeq = 0
        }
        file(CURRENT)?.delete()
        publish(force = true)
        _visited.value = emptySet()
        runCatching { visitedPrefs?.edit()?.remove("ids")?.apply() }
        runCatching { ProxyCore.clearRecords() }
    }

    /** 增量轮询：运行中每 300ms 拉一次，落盘节流到 2s。 */
    fun startPolling(scope: CoroutineScope) {
        if (pollJob != null) return
        pollJob = scope.launch(Dispatchers.IO) {
            var lastFlush = 0L
            while (isActive) {
                if (ProxyCore.isRunning()) {
                    runCatching { ProxyCore.events(lastSeq, 30) }.onSuccess { (seq, list) ->
                        if (seq != lastSeq) lastSeq = seq
                        if (list.isNotEmpty()) ingest(list)
                    }
                    // 旁路上传的日志/通知事件：同样在轮询里取，避免另开一条协程。
                    runCatching { AsyncUploadRuntime.drain() }
                }
                val now = System.currentTimeMillis()
                if (now - lastFlush > 2000) {
                    lastFlush = now
                    runCatching { flushPending() }
                }
                delay(300)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** 停止内核之前把剩余变更全部拉回来。 */
    fun flushFromCore() {
        var guard = 0
        while (guard++ < 40) {
            val page = runCatching { ProxyCore.events(lastSeq, 50) }.getOrNull() ?: break
            val (seq, list) = page
            if (list.isEmpty() && seq == lastSeq) break
            lastSeq = seq
            if (list.isNotEmpty()) ingest(list)
        }
        flushPending()
    }

    // ---------- 内部 ----------

    private fun ingest(list: List<CaptureDetail>) {
        val logLines = if (MittLog.debugEnabled) ArrayList<String>(list.size) else null
        synchronized(lock) {
            for (item in list) {
                val old = records.put(item.id, item)
                if (old != null) bytes -= sizeOf(old)
                bytes += sizeOf(item)
                pending[item.id] = item
                if (logLines != null) {
                    val pkg = item.packageName.ifEmpty { "未知应用" }
                    logLines += if (item.error.isNotEmpty()) {
                        if (looksLikeTlsError(item.error)) {
                            "$pkg ${item.url} SSL握手失败，请检查证书是否已安装；" +
                                "或者该软件启用了证书固定，需要自行 SSL unpin"
                        } else {
                            "$pkg ${item.url} ${item.error}"
                        }
                    } else {
                        "$pkg ${item.url} 解密成功 耗时${item.durationMs}ms"
                    }
                }
            }
            pruneLocked()
        }
        if (logLines != null) MittLog.debugLines("mitm") { logLines }
        publish()
    }

    /** 内核把 TLS 细节塞在 error 里，统一转成用户能读懂的一句话。 */
    private fun looksLikeTlsError(error: String): Boolean {
        val text = error.lowercase()
        return listOf("tls", "handshake", "x509", "certificate", "ssl").any { text.contains(it) }
    }

    private fun pruneLocked() {
        if (records.isEmpty()) return
        val iterator = records.entries.iterator()
        while (bytes > limitBytes && iterator.hasNext()) {
            val entry = iterator.next()
            bytes -= sizeOf(entry.value)
            pending.remove(entry.key)
            iterator.remove()
        }
    }

    private fun publish(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < 400) return
        lastPublish = now
        val snapshot = synchronized(lock) {
            records.values.sortedByDescending { it.startedAt }.map { it.toSummary() }
        }
        _records.value = snapshot
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

    private fun sizeOf(detail: CaptureDetail): Long = HEADER_BYTES +
        detail.requestBody.data.length +
        detail.responseBody.data.length +
        detail.requestHeaders.sumOf { it.name.length + it.value.length } +
        detail.responseHeaders.sumOf { it.name.length + it.value.length }

    private fun file(name: String): File? = ctx?.let { File(it.filesDir, name) }

    private fun loadCurrent() {
        val current = file(CURRENT) ?: return
        if (!current.exists()) return
        synchronized(lock) {
            records.clear()
            pending.clear()
            bytes = 0
            current.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val detail = runCatching {
                        ProxyCore.parseDetailJson(JSONObject(line))
                    }.getOrNull() ?: return@forEach
                    val old = records.put(detail.id, detail)
                    if (old != null) bytes -= sizeOf(old)
                    bytes += sizeOf(detail)
                    if (startedAt == 0L || detail.startedAt < startedAt) startedAt = detail.startedAt
                }
            }
            pruneLocked()
        }
        publish(force = true)
        Log.i(TAG, "restored ${records.size} records from $CURRENT")
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val current = file(CURRENT) ?: return
        val batch = synchronized(lock) {
            val copy = pending.values.toList()
            pending.clear()
            copy
        }
        if (batch.isEmpty()) return
        runCatching {
            current.appendText(
                buildString {
                    batch.forEach { append(ProxyCore.detailToJson(it).toString()).append('\n') }
                },
                Charsets.UTF_8,
            )
        }
    }

    private fun archiveCurrent() {
        val current = file(CURRENT) ?: return
        if (!current.exists()) return
        flushPending()
        archiveFile(current)
    }

    /** 把 current 文件按“一次会话”归档，文件名里带开始/结束时间、条数和占用。 */
    private fun archiveFile(current: File) {
        val end = System.currentTimeMillis()
        val count = runCatching {
            current.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.count { it.isNotBlank() }
            }
        }.getOrDefault(0)
        val total = current.length()
        val start = if (startedAt > 0) startedAt else end
        val name = String.format(
            "%s%d-%d-%d-%d.jsonl",
            SESSION_PREFIX,
            start,
            end,
            count,
            total,
        )
        runCatching {
            val target = File(current.parentFile, name)
            if (!current.renameTo(target)) {
                current.copyTo(target, overwrite = true)
                current.delete()
            }
        }
        refreshSessions()
    }

    private fun refreshSessions() {
        val dir = ctx?.filesDir ?: return
        val list = dir.listFiles { f -> f.name.startsWith(SESSION_PREFIX) && f.name.endsWith(".jsonl") }
            ?.mapNotNull { f ->
                val parts = f.name.removePrefix(SESSION_PREFIX).removeSuffix(".jsonl").split('-')
                if (parts.size < 4) return@mapNotNull null
                CaptureSession(
                    id = f.name,
                    startedAt = parts[0].toLongOrNull() ?: return@mapNotNull null,
                    endedAt = parts[1].toLongOrNull() ?: return@mapNotNull null,
                    count = parts[2].toIntOrNull() ?: 0,
                    bytes = parts[3].toLongOrNull() ?: f.length(),
                )
            }
            .orEmpty()
            .sortedByDescending { it.startedAt }
        _sessions.value = list
        purgeExpiredSessions(dir, list)
    }

    private fun purgeExpiredSessions(dir: File, sessions: List<CaptureSession>) {
        if (autoSaveDays <= 0) return
        val deadline = System.currentTimeMillis() - autoSaveDays * 24L * 3600 * 1000
        val keep = sessions.filter { it.endedAt >= deadline }
        sessions.filter { it.endedAt < deadline }.forEach { expired ->
            runCatching { File(dir, expired.id).delete() }
        }
        if (keep.size != sessions.size) _sessions.value = keep
    }

    fun deleteSession(session: CaptureSession) {
        val dir = ctx?.filesDir ?: return
        runCatching { File(dir, session.id).delete() }
        _sessions.value = _sessions.value.filterNot { it.id == session.id }
    }
}