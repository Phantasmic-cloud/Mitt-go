package com.phantasmic.mitt.go

/**
 * 抓包记录的数据模型。
 *
 * 这里只放 Go 内核回传的结构和从详情推导摘要的纯函数；读写的入口在
 * [CaptureStore]，JSON 编解码在 [CaptureCodec]，引擎调用在 [ProxyCore]。
 * 新加字段时三处一起改，不要在各页面里自己拼一份。
 */

/** 请求/响应正文。`encoding` 为 `text` 或 `base64`。 */
data class BodyView(
    val size: Int,
    val truncated: Boolean,
    val encoding: String,
    val data: String,
)

data class HeaderItem(val name: String, val value: String)

/** 列表行用的摘要。 */
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

/** 详情页用的完整记录。 */
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

/** 取某个响应头（大小写不敏感），没有就返回空串。 */
fun headerOf(headers: List<HeaderItem>, name: String): String =
    headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value.orEmpty()

/** `scheme://host[:port]/path` 里的 host 部分；解析不出来就原样返回。 */
fun hostOf(url: String): String =
    url.substringAfter("://", url).substringBefore('/')

/** 详情 → 列表摘要。列表、会话、历史三处都走这一份，别各写一遍。 */
fun CaptureDetail.toSummary(): CaptureSummary = CaptureSummary(
    id = id,
    uid = uid,
    packageName = packageName,
    startedAt = startedAt,
    durationMs = durationMs,
    method = method,
    proto = proto,
    url = url,
    host = hostOf(url),
    statusCode = statusCode,
    contentType = headerOf(responseHeaders, "Content-Type"),
    size = responseBody.size,
    error = error,
)
