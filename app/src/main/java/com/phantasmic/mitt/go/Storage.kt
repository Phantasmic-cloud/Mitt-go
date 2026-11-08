package com.phantasmic.mitt.go

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast

/**
 * 全 App 唯一的「写文件到系统下载目录」实现。
 *
 * 所有导出都落到 `Download/Mitt-go/`。以前日志导出、详情保存、配置导出、
 * 证书导出各写了一遍 MediaStore 逻辑，参数还不一致（只有证书那份用了
 * `IS_PENDING`），现在统一走这里。需要「写完才可见」的调用方传 `pending = true`。
 *
 * @return 实际文件名；失败返回 null。
 */
object DownloadStore {
    fun save(
        context: Context,
        name: String,
        mime: String,
        bytes: ByteArray,
        pending: Boolean = false,
    ): String? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/Mitt-go",
            )
            if (pending) put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching null
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return@runCatching null
        if (pending) {
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            runCatching { resolver.update(uri, done, null, null) }
        }
        name
    }.getOrNull()
}

/**
 * 后台执行导出，完成后回主线程弹 Toast。
 *
 * [action] 返回 `Result<String>`（成功时是结果描述），[message] 负责把结果
 * 变成提示文案——三处导出入口的文案各不相同，所以由调用方决定。
 * [onFinished] 在主线程、弹 Toast 之前调用，给「导出中…」这类按钮状态用。
 */
fun exportAsync(
    context: Context,
    action: () -> Result<String>,
    message: (Result<String>) -> String,
    onFinished: () -> Unit = {},
) {
    Thread {
        val result = action()
        Handler(Looper.getMainLooper()).post {
            onFinished()
            Toast.makeText(context, message(result), Toast.LENGTH_LONG).show()
        }
    }.start()
}
