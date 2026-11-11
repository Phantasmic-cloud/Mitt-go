package com.phantasmic.mitt.go

import android.util.LruCache
import androidx.compose.ui.graphics.Color
import java.util.Locale

/**
 * 抓包页/详情页共用的展示工具。
 *
 * 时间统一走 [TimeFmt]，内容类型统一走 [ContentKind]，体积统一走 [formatSize]。
 * 新加页面时优先用这里和 [CaptureModel] 的现成函数，不要各写一份。
 */

/** 按域名聚合出来的一组请求。 */
data class DomainGroup(
    val host: String,
    val count: Int,
    val latest: CaptureSummary,
)

private val regexCache = object : LruCache<String, Regex>(32) {}

fun formatClock(millis: Long): String = TimeFmt.clock(millis)

/** 会话标题里的时间段：`11-01 12:00-12:30`。 */
fun formatSessionRange(start: Long, end: Long): String =
    "${TimeFmt.dayClock(start)}-${TimeFmt.clockShort(end)}"

/** Content-Type → 列表右侧的短类型标签（空值显示为破折号）。 */
fun typeLabel(contentType: String): String =
    if (contentType.isEmpty()) "—" else ContentKind.of(contentType).label

fun typeColor(contentType: String): Color =
    if (contentType.isEmpty()) Color(0xFF7A7A7A) else ContentKind.of(contentType).color

/**
 * 体积显示，两种写法并存是有意的：
 * - `compact = true` 给列表行用：`534kb` / `1.2mb`；
 * - `compact = false` 给详情页用：`534.0 KB` / `1.20 MB`。
 */
/** 吞吐速率也用体积格式，只差一个 /s；这里只收 Long 防止调用方自己截断。 */
fun formatSize(size: Long): String =
    formatSize(size.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())

fun formatSize(size: Int, compact: Boolean = false): String = if (compact) {
    when {
        size <= 0 -> "0kb"
        size < 1024 -> "${size}b"
        size < 1024 * 1024 -> "${size / 1024}kb"
        else -> "%.1fmb".format(Locale.US, size / 1048576.0)
    }
} else {
    when {
        size <= 0 -> "0 B"
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "%.1f KB".format(Locale.US, size / 1024.0)
        else -> "%.2f MB".format(Locale.US, size / 1048576.0)
    }
}

/** `scheme://host/path?query` 里的 `/path?query`；没有路径就返回 `/`。 */
fun pathOf(url: String): String {
    val schemeEnd = url.indexOf("://")
    val rest = if (schemeEnd >= 0) url.substring(schemeEnd + 3) else url
    val slash = rest.indexOf('/')
    return if (slash >= 0) rest.substring(slash) else "/"
}

/** `scheme://host[:port]` 与 `/path?query` 拆开显示。 */
fun splitUrl(url: String): Pair<String, String> {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url to ""
    val rest = url.substring(schemeEnd + 3)
    val slash = rest.indexOf('/')
    if (slash < 0) return url to "/"
    val base = url.substring(0, schemeEnd + 3 + slash)
    return base to rest.substring(slash)
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

/** 会话标题：11-01 12:00-12:30，跨天时把结束日期也带上。 */
fun sessionLabel(session: CaptureSession): String {
    val startText = "${TimeFmt.day(session.startedAt)} ${TimeFmt.clockShort(session.startedAt)}"
    val sameDay = TimeFmt.day(session.startedAt) == TimeFmt.day(session.endedAt)
    val endText = if (sameDay) {
        TimeFmt.clockShort(session.endedAt)
    } else {
        "${TimeFmt.day(session.endedAt)} ${TimeFmt.clockShort(session.endedAt)}"
    }
    return "$startText-$endText"
}
