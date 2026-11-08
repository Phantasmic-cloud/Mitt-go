package com.phantasmic.mitt.go

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** 正文在详情页/导出时的类型判定，会做一次内容嗅探，不只看 Content-Type。 */
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

    /** 详情页一次算好类型和正文：正文只 parse + 美化一遍，别让 kind 和 displayText 各算一次。 */
    data class Render(val kind: ContentKind, val text: String)

    fun render(contentType: String, body: BodyView): Render {
        val text = textOrNull(body) ?: return Render(ContentKind.BIN, binaryPreview(body))
        prettyJson(text)?.let { return Render(ContentKind.JSON, it) }
        val kind = kindOfText(contentType, text)
        return Render(kind, if (kind == ContentKind.BIN) binaryPreview(body) else text)
    }

    /**
     * 导出用的文件内容。和详情页看到的一致：JSON 就是已经格式化好的缩进版，
     * 其它文本按 UTF-8 原样写出，二进制仍然是原始字节。
     *
     * 保存正文一律走这里——以前直接写 [rawBytes]，导出的 .json 是一行压缩版，
     * 和详情页预览对不上。
     */
    data class Export(val bytes: ByteArray, val kind: ContentKind)

    fun export(contentType: String, body: BodyView): Export {
        val render = render(contentType, body)
        val bytes = if (render.kind == ContentKind.BIN) {
            rawBytes(body)
        } else {
            render.text.toByteArray(Charsets.UTF_8)
        }
        return Export(bytes, render.kind)
    }

    /** 正文不是 JSON 时的类型判定，不碰 prettyJson。 */
    private fun kindOfText(contentType: String, text: String): ContentKind {
        val type = contentType.lowercase()
        return when {
            type.contains("json") -> ContentKind.JSON
            type.contains("html") -> ContentKind.HTML
            type.contains("javascript") -> ContentKind.JS
            type.contains("css") -> ContentKind.CSS
            type.contains("xml") -> ContentKind.XML
            else -> if (looksPrintable(text)) ContentKind.TEXT else ContentKind.BIN
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

    private fun looksPrintable(text: String): Boolean {
        if (text.isEmpty()) return true
        val sample = text.take(2048)
        val printable = sample.count { it == '\n' || it == '\r' || it == '\t' || it >= ' ' }
        return printable >= sample.length * 0.9f
    }
}

/** 详情页顶栏保存菜单的三项动作。 */
object DetailExport {

    fun saveRequestAndResponse(context: Context, detail: CaptureDetail): String? {
        val body = buildString {
            appendLine("Mitt-go 请求详情")
            appendLine("时间: ${TimeFmt.stampMillis(detail.startedAt)}")
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
            name = "Mitt-go_${detail.method.ifBlank { "http" }}_${TimeFmt.fileStamp()}.txt",
            mime = "text/plain",
            bytes = body.toByteArray(Charsets.UTF_8),
        )
    }

    fun saveBody(context: Context, detail: CaptureDetail, response: Boolean): String? {
        val headers = if (response) detail.responseHeaders else detail.requestHeaders
        val body = if (response) detail.responseBody else detail.requestBody
        // 落盘的内容和详情页预览同源：JSON 是格式化后的缩进版。
        val export = BodyCodec.export(headerOf(headers, "Content-Type"), body)
        val suffix = if (response) "resp" else "req"
        return DownloadStore.save(
            context = context,
            name = "Mitt-go_${detail.method.ifBlank { "http" }}_${TimeFmt.fileStamp()}_$suffix.${export.kind.extension}",
            mime = export.kind.mime,
            bytes = export.bytes,
        )
    }
}
