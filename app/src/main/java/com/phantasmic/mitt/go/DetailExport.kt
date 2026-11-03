package com.phantasmic.mitt.go

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 所有导出都落到 `Download/Mitt-go/`。 */
object DownloadStore {
    fun save(context: Context, name: String, mime: String, bytes: ByteArray): String? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Mitt-go",
            )
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching null
        name
    }.getOrNull()
}

/** 正文在详情页/导出时的类型判定，会做一次内容嗅探，不只看 Content-Type。 */
enum class BodyKind(val label: String, val extension: String, val mime: String) {
    JSON("JSON", "json", "application/json"),
    HTML("HTML", "html", "text/html"),
    XML("XML", "xml", "application/xml"),
    JS("JS", "js", "application/javascript"),
    CSS("CSS", "css", "text/css"),
    TEXT("TEXT", "txt", "text/plain"),
    BIN("BIN", "bin", "application/octet-stream"),
}

object BodyCodec {
    /** 原始字节：base64 的正文解回二进制，文本正文按 UTF-8 编码。 */
    fun rawBytes(body: BodyView): ByteArray = runCatching {
        if (body.encoding == "base64") {
            Base64.decode(body.data, Base64.DEFAULT)
        } else {
            body.data.toByteArray(Charsets.UTF_8)
        }
    }.getOrDefault(ByteArray(0))

    /** 只有能严格按 UTF-8 解码的正文才当成文本，否则留给 BIN。 */
    fun textOrNull(body: BodyView): String? {
        if (body.data.isEmpty()) return ""
        if (body.encoding != "base64") return body.data
        return runCatching {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(rawBytes(body))).toString()
        }.getOrNull()
    }

    fun prettyJson(text: String): String? = runCatching {
        val trimmed = text.trim()
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
            else -> null
        }
    }.getOrNull()

    fun kind(contentType: String, body: BodyView): BodyKind {
        val text = textOrNull(body) ?: return BodyKind.BIN
        prettyJson(text)?.let { return BodyKind.JSON }
        val type = contentType.lowercase()
        return when {
            type.contains("json") -> BodyKind.JSON
            type.contains("html") -> BodyKind.HTML
            type.contains("javascript") -> BodyKind.JS
            type.contains("css") -> BodyKind.CSS
            type.contains("xml") -> BodyKind.XML
            else -> if (looksPrintable(text)) BodyKind.TEXT else BodyKind.BIN
        }
    }

    /** BIN 也照原样展示：可打印字符保留，其它用 � 占位，不丢内容。 */
    fun binaryPreview(body: BodyView, limit: Int = 4000): String {
        val bytes = rawBytes(body)
        val size = minOf(bytes.size, limit)
        val out = StringBuilder(size)
        for (index in 0 until size) {
            val value = bytes[index].toInt() and 0xFF
            out.append(
                when {
                    value == 9 || value == 10 || value == 13 -> value.toChar()
                    value in 32..126 -> value.toChar()
                    else -> '\uFFFD'
                },
            )
        }
        return out.toString()
    }

    fun displayText(contentType: String, body: BodyView): String {
        val kind = kind(contentType, body)
        if (kind == BodyKind.BIN) return binaryPreview(body)
        val text = textOrNull(body).orEmpty()
        if (kind == BodyKind.JSON) return prettyJson(text) ?: text
        return text
    }

    private fun looksPrintable(text: String): Boolean {
        if (text.isEmpty()) return true
        val sample = text.take(2048)
        val printable = sample.count { it == '\n' || it == '\r' || it == '\t' || it >= ' ' }
        return printable >= sample.length * 0.9f
    }
}

/** 详情页顶栏保存菜单的三项动作。 */
object DetailExport {
    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun saveRequestAndResponse(context: Context, detail: CaptureDetail): String? {
        val body = buildString {
            appendLine("Mitt-go 请求详情")
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(detail.startedAt))}")
            appendLine("方法: ${detail.method} ${detail.proto}")
            appendLine("URL: ${detail.url}")
            appendLine("IP: ${detail.remoteAddr.ifBlank { "-" }}")
            appendLine("状态: ${if (detail.error.isNotBlank()) "失败 ${detail.error}" else detail.statusCode.toString()}")
            appendLine("耗时: ${detail.durationMs} ms")
            appendLine()
            appendLine("===== 请求 =====")
            appendLine("${detail.method} ${detail.url}")
            detail.requestHeaders.forEach { appendLine("${it.name}: ${it.value}") }
            appendLine()
            appendLine("===== 响应 =====")
            appendLine("${detail.statusCode}")
            detail.responseHeaders.forEach { appendLine("${it.name}: ${it.value}") }
        }
        return DownloadStore.save(
            context = context,
            name = "Mitt-go_${detail.method.ifBlank { "http" }}_${stamp()}.txt",
            mime = "text/plain",
            bytes = body.toByteArray(Charsets.UTF_8),
        )
    }

    fun saveBody(context: Context, detail: CaptureDetail, response: Boolean): String? {
        val headers = if (response) detail.responseHeaders else detail.requestHeaders
        val body = if (response) detail.responseBody else detail.requestBody
        val kind = BodyCodec.kind(headerOf(headers), body)
        val suffix = if (response) "resp" else "req"
        return DownloadStore.save(
            context = context,
            name = "Mitt-go_${detail.method.ifBlank { "http" }}_${stamp()}_$suffix.${kind.extension}",
            mime = kind.mime,
            bytes = BodyCodec.rawBytes(body),
        )
    }

    private fun headerOf(headers: List<HeaderItem>): String =
        headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value.orEmpty()
}