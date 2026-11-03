package com.phantasmic.mitt.go

import android.util.LruCache
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale



/** 按域名聚合出来的一组请求。 */
data class DomainGroup(
    val host: String,
    val count: Int,
    val latest: CaptureSummary,
)

private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.US)
}

private val sessionFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("MM-dd HH:mm", Locale.US)
}

private val regexCache = object : LruCache<String, Regex>(32) {}

fun formatClock(millis: Long): String = timeFormat.get()!!.format(Date(millis))

fun formatSessionRange(start: Long, end: Long): String {
    val formatter = sessionFormat.get()!!
    return "${formatter.format(Date(start))}-${SimpleDateFormat("HH:mm", Locale.US).format(Date(end))}"
}

/** Content-Type → 列表右侧的短类型标签。 */
fun typeLabel(contentType: String): String {
    val value = contentType.lowercase()
    return when {
        value.contains("json") -> "JSON"
        value.contains("html") -> "HTML"
        value.contains("image") -> "IMAGE"
        value.contains("video") -> "VIDEO"
        value.contains("audio") -> "AUDIO"
        value.contains("javascript") -> "JS"
        value.contains("css") -> "CSS"
        value.contains("text") -> "TEXT"
        value.contains("xml") -> "XML"
        value.isEmpty() -> "—"
        else -> "BIN"
    }
}

fun typeColor(contentType: String): Color = when (typeLabel(contentType)) {
    "JSON" -> Color(0xFFD9922B)
    "TEXT" -> Color(0xFF4E9A5B)
    "HTML" -> Color(0xFF3E7BC4)
    "XML" -> Color(0xFF3E9AA8)
    "JS", "CSS" -> Color(0xFFB07A22)
    "IMAGE" -> Color(0xFFD267A5)
    "VIDEO" -> Color(0xFF8E6BD0)
    "AUDIO" -> Color(0xFF5E8FB8)
    "BIN" -> Color(0xFF8E6BD0)
    else -> Color(0xFF7A7A7A)
}

fun groupByHost(records: List<CaptureSummary>): List<DomainGroup> =
    records.groupBy { it.host.ifBlank { "-" } }
        .map { (host, items) ->
            DomainGroup(
                host = host,
                count = items.size,
                latest = items.maxByOrNull { it.startedAt }!!,
            )
        }
        .sortedByDescending { it.latest.startedAt }

/** 命中高亮规则时返回颜色，否则 null。 */
fun highlightColorFor(url: String, rules: List<HighlightRule>, enabled: Boolean): Int? {
    if (!enabled || rules.isEmpty() || url.isEmpty()) return null
    for (rule in rules) {
        val regex = regexCache.get(rule.pattern) ?: runCatching {
            Regex(rule.pattern, RegexOption.IGNORE_CASE)
        }.getOrNull()?.also { regexCache.put(rule.pattern, it) } ?: continue
        if (regex.containsMatchIn(url)) return rule.colorArgb
    }
    return null
}

/** 高亮小窗里可选的几个颜色。 */
val highlightPalette = intArrayOf(
    0xFFE0605E.toInt(),
    0xFFE0A33E.toInt(),
    0xFF4CA96A.toInt(),
    0xFF3D8FD8.toInt(),
    0xFF8E6BD0.toInt(),
    0xFFD267A5.toInt(),
)

/** 列表里用的紧凑体积：534kb / 1.2mb。 */
fun compactSize(size: Int): String = when {
    size <= 0 -> "0kb"
    size < 1024 -> "${size}b"
    size < 1024 * 1024 -> "${size / 1024}kb"
    else -> "%.1fmb".format(java.util.Locale.US, size / 1048576.0)
}

/** 会话标题：11-01 12:00-12:30，跨天时把结束日期也带上。 */
fun sessionLabel(session: CaptureSession): String {
    val day = java.text.SimpleDateFormat("MM-dd", java.util.Locale.US)
    val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    val start = java.util.Date(session.startedAt)
    val end = java.util.Date(session.endedAt)
    val startText = "${day.format(start)} ${clock.format(start)}"
    val sameDay = day.format(start) == day.format(end)
    val endText = if (sameDay) clock.format(end) else "${day.format(end)} ${clock.format(end)}"
    return "$startText-$endText"
}
