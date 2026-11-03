package com.phantasmic.mitt.go

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
)

object InstalledAppsLoader {
    suspend fun load(context: Context, includeSystem: Boolean = false): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != context.packageName }
                .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { app ->
                    InstalledApp(
                        packageName = app.packageName,
                        label = runCatching { pm.getApplicationLabel(app).toString() }
                            .getOrDefault(app.packageName),
                        system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
                .toList()
        }
}

object AppIconCache {
    // 直接缓存 ImageBitmap：滚动时不再每行重新包装 Bitmap，减少对象分配与重绘。
    private val cache = object : LruCache<String, ImageBitmap>(96) {}

    fun get(packageName: String): ImageBitmap? = cache.get(packageName)

    fun put(packageName: String, bitmap: ImageBitmap) {
        cache.put(packageName, bitmap)
    }
}

@Composable
fun AppIconBadge(
    packageName: String,
    size: Dp = 28.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cached = remember(packageName) { AppIconCache.get(packageName) }
    var async by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }

    // 只有缓存未命中时才创建协程：滚动列表里绝大多数行都能直接命中缓存，
    // 不再为每一行往主线程消息队列里塞一个加载任务。
    if (cached == null) {
        LaunchedEffect(packageName) {
            if (packageName.isBlank() || packageName == "-") return@LaunchedEffect
            async = runCatching {
                withContext(Dispatchers.IO) {
                    val drawable = context.packageManager.getApplicationIcon(packageName)
                    drawable.toBitmap(64, 64).asImageBitmap()
                }
            }.getOrNull()?.also { AppIconCache.put(packageName, it) }
        }
    }

    val image = cached ?: async
    Box(
        modifier = modifier
            .size(size)
            .background(Color(0x1A7A7A7A), RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                text = packageName.takeIf { it.isNotBlank() && it != "-" }?.take(1)?.uppercase() ?: "?",
                fontSize = (size.value * 0.42f).sp,
                color = Color(0xFF8A8A8A),
            )
        }
    }
}
