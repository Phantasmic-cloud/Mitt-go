package com.phantasmic.mitt.go

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.util.UUID

/** 一份导入的旁路上传配置；`raw` 是 YAML 原文，直接下发给 Go 内核。 */
data class AsyncConfig(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val matchPatterns: List<String>,
    val serverUrl: String,
    val raw: String,
) {
    val matchCount: Int get() = matchPatterns.size
    val firstPattern: String get() = matchPatterns.firstOrNull().orEmpty()
}

/**
 * 旁路上传配置存储。
 *
 * 每份配置就是 `files/async-upload/<uuid>.yaml`，保存原文；Kotlin 侧只做
 * 轻量解析（取 name / server_url / match_url）用来画列表，真正的语义解析
 * 和正则编译在 Go 里做，避免两边规则不一致。
 */
object AsyncUploadStore {
    private const val DIR = "async-upload"

    /** 配置增删改时自增，UI 用它触发重算（模块页的“N 份配置”）。 */
    val revision = androidx.compose.runtime.mutableIntStateOf(0)

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun list(context: Context): List<AsyncConfig> {
        val files = dir(context).listFiles { file -> file.isFile && file.name.endsWith(".yaml") }
            ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching { parse(file.name.removeSuffix(".yaml"), file.readText()) }.getOrNull()
        }.sortedBy { it.name.lowercase() }
    }

    fun raw(context: Context, id: String): String? =
        runCatching { File(dir(context), "$id.yaml").takeIf { it.exists() }?.readText() }.getOrNull()

    fun delete(context: Context, id: String): Boolean {
        val ok = runCatching { File(dir(context), "$id.yaml").delete() }.getOrDefault(false)
        if (ok) revision.intValue++
        return ok
    }

    /** 从 SAF 选中文件读出 YAML 文本（先给编辑器预览，用户点保存才落盘）。 */
    fun readText(context: Context, uri: Uri, displayName: String?): Result<String> = runCatching {
        val name = displayName.orEmpty()
        require(name.isEmpty() || name.endsWith(".yaml", true) || name.endsWith(".yml", true)) {
            "只接受 .yaml 配置"
        }
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("无法读取文件")
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "文件是空的" }
        require(looksLikeConfig(trimmed)) { "缺少 match_url 或 upload.server_url，这不是旁路上传配置" }
        text
    }

    /** 保存一份 YAML（导入、自行配置、二次编辑共用）；传了 id 就是覆盖原文件。 */
    fun save(context: Context, text: String, id: String = UUID.randomUUID().toString()): AsyncConfig {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "配置内容是空的" }
        require(looksLikeConfig(trimmed)) { "缺少 match_url 或 upload.server_url，这不是旁路上传配置" }
        File(dir(context), "$id.yaml").writeText(trimmed + "\n")
        revision.intValue++
        return parse(id, trimmed)
    }

    /** 导出成 `Mitt-<name>.yaml` 到 Download/Mitt-go，返回文件名。 */
    fun export(context: Context, config: AsyncConfig): String? = runCatching {
        val safeName = config.name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "async" }
        val fileName = "Mitt-$safeName.yaml"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Mitt-go")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { it.write(config.raw.toByteArray()) }
            ?: return@runCatching null
        fileName
    }.getOrNull()

    private fun looksLikeConfig(text: String): Boolean {
        val hasMatch = lineValue(text, "match_url") != null || matchPatterns(text).isNotEmpty()
        val hasUpload = lineValue(text, "server_url") != null
        return hasMatch && hasUpload
    }

    fun parse(id: String, raw: String): AsyncConfig {
        val name = (lineValue(raw, "name") ?: "").trim().ifBlank { "未命名配置" }
        val enabled = when ((lineValue(raw, "enable") ?: "true").lowercase()) {
            "false", "no", "off", "0" -> false
            else -> true
        }
        val serverUrl = (lineValue(raw, "server_url") ?: "").trim()
        return AsyncConfig(
            id = id,
            name = name,
            enabled = enabled,
            matchPatterns = matchPatterns(raw),
            serverUrl = serverUrl,
            raw = raw,
        )
    }

    /** 取某个键在第一处出现的值，去掉引号和行尾注释。 */
    private fun lineValue(raw: String, key: String): String? {
        val regex = Regex("""(?m)^\s*""" + Regex.escape(key) + """\s*:\s*(.+)$""")
        val match = regex.find(raw) ?: return null
        var value = match.groupValues[1].trim()
        val comment = value.indexOf(" #")
        if (comment > 0) value = value.take(comment).trim()
        if (value.length >= 2 &&
            ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'")))
        ) {
            value = value.substring(1, value.length - 1)
        }
        return value
    }

    /** match_url 下面每一条 `- "..."` 去掉列表符号和引号后的样子。 */
    private fun matchPatterns(raw: String): List<String> {
        val lines = raw.lines()
        var start = -1
        for ((index, line) in lines.withIndex()) {
            if (Regex("""^\s*match_url\s*:""").containsMatchIn(line)) {
                start = index
                break
            }
        }
        if (start < 0) return emptyList()
        val out = mutableListOf<String>()
        for (index in start + 1 until lines.size) {
            val line = lines[index]
            if (line.isBlank()) continue
            val indent = line.length - line.trimStart().length
            if (indent == 0) break
            val trimmed = line.trimStart()
            if (!trimmed.startsWith("-")) continue
            var value = trimmed.removePrefix("-").trim()
            val comment = value.indexOf(" #")
            if (comment > 0) value = value.take(comment).trim()
            if (value.length >= 2 &&
                ((value.startsWith("\"") && value.endsWith("\"")) ||
                    (value.startsWith("'") && value.endsWith("'")))
            ) {
                value = value.substring(1, value.length - 1)
            }
            if (value.isNotBlank()) out += value
        }
        return out
    }
}

/** 旁路上传的系统通知。没有通知权限时只写日志。 */
object AsyncNotify {
    private const val CHANNEL_ID = "mitt_go_async_upload"
    private const val CHANNEL_NAME = "旁路上传"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "旁路上传完成或失败时提醒"
            },
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun post(context: Context, title: String, text: String, timestamp: Long = 0L) {
        ensureChannel(context)
        if (!canPost(context)) {
            MittLog.warn("async", "没有通知权限，跳过通知：$text")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title.ifBlank { "Mitt-go" })
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            // 显式给时间，系统才会在右上角显示“现在 / 2 分钟前”。
            .setWhen(if (timestamp > 0L) timestamp else System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        runCatching { manager.notify(text.hashCode(), notification) }
            .onFailure { MittLog.error("async", "通知发送失败：${it.message}") }
    }
}

/** 一条旁路上传事件（Go 侧推上来）。 */
data class AsyncEvent(
    val kind: String,
    val level: String,
    val title: String,
    val text: String,
    val time: Long,
)

/**
 * 旁路上传运行时：负责把配置下发给 Go 内核，以及把内核攒下的事件取回来
 * （通知 + 日志）。
 */
object AsyncUploadRuntime {
    @Volatile
    private var context: Context? = null

    fun attach(context: Context) {
        this.context = context.applicationContext
        AsyncNotify.ensureChannel(context.applicationContext)
    }

    /** 开关或配置变化时调用；内核没起也不报错，下次 start 会再同步一次。 */
    fun sync() {
        val context = this.context ?: return
        val settings = AppSettings.load(context)
        val enabled = settings.asyncUploadEnabled
        val configs = if (enabled) {
            AsyncUploadStore.list(context).filter { it.enabled }.map { it.raw }
        } else {
            emptyList()
        }
        if (!ProxyCore.isRunning()) {
            MittLog.debug("async") { "内核未运行，配置等下次 start 再下发" }
            return
        }
        ProxyCore.setAsyncUpload(enabled, configs)
        MittLog.info(
            "async",
            "下发配置：总开关=$enabled，${if (enabled) configs.size else 0} 份生效",
        )
    }

    /** 轮询取走内核攒下的日志 / 通知事件。 */
    fun drain() {
        val context = this.context ?: return
        val events = ProxyCore.asyncEvents()
        if (events.isEmpty()) return
        events.forEach { event ->
            when {
                event.kind == "notify" -> {
                    MittLog.info("async", event.text.replace("\n", " "))
                    AsyncNotify.post(context, event.title, event.text, event.time)
                }
                event.level == "warn" -> MittLog.warn("async", event.text)
                else -> MittLog.info("async", event.text)
            }
        }
    }
}