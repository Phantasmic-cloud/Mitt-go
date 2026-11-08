package com.phantasmic.mitt.go

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogLevel(val label: String) {
    DEBUG("DEBUG"),
    INFO("INFO"),
    WARNING("WARNING"),
    ERROR("ERROR"),
}

data class LogEntry(
    val time: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    fun format(): String = "[${MittLog.timeOf(time)}] [${level.label}] [$tag] $message"
}

/**
 * 进程内日志缓冲。
 *
 * 只保存在内存里，关掉 App 就没了；日志页可以把当前内容导出到
 * `Download/Mitt-go_log_<时间>.txt`。DEBUG 级别只在设置里的“DEBUG 模式”
 * 打开时才会被记录，普通模式下只留少量关键信息，避免刷屏。
 */
object MittLog {
    private const val CAPACITY = 4000

    @Volatile
    var debugEnabled: Boolean = false

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val lock = Any()

    fun timeOf(time: Long): String = TimeFmt.clock(time)

    fun debug(tag: String, message: () -> String) {
        if (!debugEnabled) return
        add(LogLevel.DEBUG, tag, message())
    }

    /** 批量写 DEBUG 日志：抓包时一次轮询可能带来几十条，合并成一次刷新。 */
    fun debugLines(tag: String, lines: () -> List<String>) {
        if (!debugEnabled) return
        val messages = lines()
        if (messages.isEmpty()) return
        val base = System.currentTimeMillis()
        val batch = messages.mapIndexed { index, message ->
            LogEntry(base + index, LogLevel.DEBUG, tag, message)
        }
        synchronized(lock) {
            val current = _entries.value
            val merged = current + batch
            _entries.value = if (merged.size > CAPACITY) {
                merged.subList(merged.size - CAPACITY, merged.size).toList()
            } else {
                merged
            }
        }
        batch.forEach { Log.d(tag, it.message) }
    }

    fun info(tag: String, message: String) = add(LogLevel.INFO, tag, message)

    fun warn(tag: String, message: String) = add(LogLevel.WARNING, tag, message)

    fun error(tag: String, message: String) = add(LogLevel.ERROR, tag, message)

    fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
    }

    /** 导出到系统下载目录，返回文件名；失败返回 null。 */
    fun export(context: Context): String? {
        val name = "Mitt-go_log_${TimeFmt.fileStamp()}.txt"
        val body = buildString {
            appendLine("# Mitt-go ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("# exported ${TimeFmt.clock(System.currentTimeMillis())}")
            appendLine()
            _entries.value.forEach { appendLine(it.format()) }
        }
        return DownloadStore.save(context, name, "text/plain", body.toByteArray(Charsets.UTF_8))
    }

    private fun add(level: LogLevel, tag: String, message: String) {
        if (level == LogLevel.DEBUG && !debugEnabled) return
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            val current = _entries.value
            _entries.value = if (current.size >= CAPACITY) {
                current.subList(current.size - CAPACITY + 1, current.size).toList() + entry
            } else {
                current + entry
            }
        }
        when (level) {
            LogLevel.ERROR -> Log.e(tag, message)
            LogLevel.WARNING -> Log.w(tag, message)
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
        }
    }
}