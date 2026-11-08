package com.phantasmic.mitt.go

import org.json.JSONArray
import org.json.JSONObject

/**
 * 抓包记录的 JSON 编解码。
 *
 * 内核通过 JNI 回传的格式和落盘 `capture-current.jsonl` / `session-*.jsonl`
 * 用的是同一套字段，所以只在这里转换一次。字段名改动必须和 `core/model.go`
 * 的 `recordDetail` 同步。
 */
object CaptureCodec {

    fun parseDetailJson(json: JSONObject): CaptureDetail = CaptureDetail(
        id = json.optString("id"),
        uid = json.optInt("uid"),
        packageName = json.optString("packageName"),
        startedAt = json.optLong("startedAt"),
        durationMs = json.optLong("durationMs"),
        method = json.optString("method"),
        url = json.optString("url"),
        proto = json.optString("proto"),
        remoteAddr = json.optString("remoteAddr"),
        statusCode = json.optInt("statusCode"),
        requestHeaders = parseHeaders(json.optJSONObject("requestHeader")),
        responseHeaders = parseHeaders(json.optJSONObject("responseHeader")),
        requestBody = parseBody(json.optJSONObject("requestBody")),
        responseBody = parseBody(json.optJSONObject("responseBody")),
        error = json.optString("error"),
    )

    fun detailToJson(detail: CaptureDetail): JSONObject = JSONObject()
        .put("id", detail.id)
        .put("uid", detail.uid)
        .put("packageName", detail.packageName)
        .put("startedAt", detail.startedAt)
        .put("durationMs", detail.durationMs)
        .put("method", detail.method)
        .put("url", detail.url)
        .put("proto", detail.proto)
        .put("remoteAddr", detail.remoteAddr)
        .put("statusCode", detail.statusCode)
        .put("requestHeader", headersToJson(detail.requestHeaders))
        .put("responseHeader", headersToJson(detail.responseHeaders))
        .put("requestBody", bodyToJson(detail.requestBody))
        .put("responseBody", bodyToJson(detail.responseBody))
        .put("error", detail.error)

    private fun headersToJson(headers: List<HeaderItem>): JSONObject {
        val out = JSONObject()
        headers.groupBy { it.name }.forEach { (name, values) ->
            val array = JSONArray()
            values.forEach { array.put(it.value) }
            out.put(name, array)
        }
        return out
    }

    private fun bodyToJson(body: BodyView): JSONObject = JSONObject()
        .put("size", body.size)
        .put("truncated", body.truncated)
        .put("encoding", body.encoding)
        .put("data", body.data)

    private fun parseHeaders(obj: JSONObject?): List<HeaderItem> {
        if (obj == null) return emptyList()
        val out = mutableListOf<HeaderItem>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val values = obj.optJSONArray(name) ?: continue
            for (i in 0 until values.length()) {
                out += HeaderItem(name, values.optString(i))
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun parseBody(obj: JSONObject?): BodyView {
        if (obj == null) return BodyView(0, false, "text", "")
        return BodyView(
            size = obj.optInt("size"),
            truncated = obj.optBoolean("truncated"),
            encoding = obj.optString("encoding", "text"),
            data = obj.optString("data"),
        )
    }
}
