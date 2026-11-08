package com.phantasmic.mitt.go

import androidx.compose.ui.graphics.Color

/**
 * 内容类型分类，全 App 唯一一份。
 *
 * 两个入口，别混用：
 * - [of] 只看 `Content-Type`，给列表徽标用（拿不到正文）；
 * - [BodyCodec.render] / [BodyCodec.export] 会嗅探正文，给详情页和导出用。
 *   详情页和落盘必须走同一个函数，否则会出现“预览是缩进 JSON、保存下来是一行”。
 *
 * `label` / `extension` / `mime` / `color` 都是对外可见的，改之前先确认
 * 列表和详情页的观感（详情页目前只会出现 JSON/HTML/XML/JS/CSS/TEXT/BIN）。
 */
enum class ContentKind(
    val label: String,
    val extension: String,
    val mime: String,
    val color: Color,
) {
    JSON("JSON", "json", "application/json", Color(0xFFD9922B)),
    HTML("HTML", "html", "text/html", Color(0xFF3E7BC4)),
    XML("XML", "xml", "application/xml", Color(0xFF3E9AA8)),
    JS("JS", "js", "application/javascript", Color(0xFFB07A22)),
    CSS("CSS", "css", "text/css", Color(0xFFB07A22)),
    TEXT("TEXT", "txt", "text/plain", Color(0xFF4E9A5B)),
    IMAGE("IMAGE", "img", "image/*", Color(0xFFD267A5)),
    VIDEO("VIDEO", "mp4", "video/*", Color(0xFF8E6BD0)),
    AUDIO("AUDIO", "mp3", "audio/*", Color(0xFF5E8FB8)),
    BIN("BIN", "bin", "application/octet-stream", Color(0xFF8E6BD0)),
    ;

    companion object {
        /** 按 `Content-Type` 分类。判断顺序会影响结果（如 text/xml 算 TEXT）。 */
        fun of(contentType: String): ContentKind {
            val value = contentType.lowercase()
            return when {
                value.contains("json") -> JSON
                value.contains("html") -> HTML
                value.contains("image") -> IMAGE
                value.contains("video") -> VIDEO
                value.contains("audio") -> AUDIO
                value.contains("javascript") -> JS
                value.contains("css") -> CSS
                value.contains("text") -> TEXT
                value.contains("xml") -> XML
                else -> BIN
            }
        }
    }
}
