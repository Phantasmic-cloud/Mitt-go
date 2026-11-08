package com.phantasmic.mitt.go

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme



private enum class DetailTab(val label: String) {
    BASIC("基本"),
    REQUEST("请求"),
    RESPONSE("响应"),
}

@Composable
fun RequestDetailPage(
    detail: CaptureDetail?,
    summary: CaptureSummary?,
    topInset: Dp,
    bottomInset: Dp,
    loadFailed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 详情是异步拉取的：切到另一条记录时，上一条的 detail 还没清掉，
    // 这里用 id 对齐，避免先闪一下旧内容。
    val shown = detail?.takeIf { summary == null || it.id == summary.id }
    if (shown == null) {
        if (summary != null) {
            PendingDetail(summary = summary, topInset = topInset, failed = loadFailed)
        } else {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    var tab by remember(shown.id) { mutableStateOf(DetailTab.BASIC) }
    val tabs = DetailTab.values()
    val pagerState = rememberPagerState(
        initialPage = tab.ordinal,
        pageCount = { tabs.size },
    )
    val scope = rememberCoroutineScope()
    LaunchedEffect(pagerState.currentPage) {
        tab = tabs[pagerState.currentPage]
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(top = topInset)
            // 头部是纯文本，不加拦截的话点击会穿透到下层抓包列表，误开别的记录。
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        DetailSummary(shown)
        SegmentedTabs(
            selected = tab,
            onSelect = { next ->
                tab = next
                scope.launch { pagerState.animateScrollToPage(next.ordinal) }
            },
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp)
                    .padding(top = 4.dp, bottom = bottomInset + 28.dp),
            ) {
                when (tabs[page]) {
                    DetailTab.BASIC -> BasicTab(shown)
                    DetailTab.REQUEST -> BodyTab(
                        title = "请求体内容",
                        headers = shown.requestHeaders,
                        body = shown.requestBody,
                        contentType = headerOf(shown.requestHeaders, "Content-Type"),
                    )

                    DetailTab.RESPONSE -> BodyTab(
                        title = "响应体内容",
                        headers = shown.responseHeaders,
                        body = shown.responseBody,
                        contentType = headerOf(shown.responseHeaders, "Content-Type"),
                    )
                }
            }
        }
    }
}

/**
 * 详情还没拉回来时先按列表里的摘要渲染，避免整页空白转圈。
 * 记录已被清空/挤出 500 条时给出明确提示，而不是永远转圈。
 */
@Composable
private fun PendingDetail(summary: CaptureSummary, topInset: Dp, failed: Boolean) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val appLabel = remember(summary.packageName) {
        AppIdentityResolver.labelFor(context, summary.packageName)
    }
    val time = remember(summary.startedAt) { TimeFmt.clockMillis(summary.startedAt) }
    Column(Modifier.fillMaxSize().padding(top = topInset)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MethodBadgePublic(summary.method)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (summary.error.isNotBlank()) "失败" else statusText(summary.statusCode),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = statusColor(summary.statusCode),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (summary.durationMs > 0) "${summary.durationMs} ms" else "进行中",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceSecondary,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = time,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceSecondary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = summary.url,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = colors.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconBadge(packageName = summary.packageName, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = appLabel,
                fontSize = 11.sp,
                color = colors.onSurfaceSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (failed) {
                Text(
                    text = "这条记录已经不在了（可能已被清空）",
                    fontSize = 12.sp,
                    color = colors.onSurfaceSecondary,
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun DetailSummary(detail: CaptureDetail) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    val appLabel = remember(detail.packageName) {
        AppIdentityResolver.labelFor(context, detail.packageName)
    }
    val time = remember(detail.startedAt) { TimeFmt.stamp(detail.startedAt) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconBadge(packageName = detail.packageName, size = 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = appLabel.ifBlank { "未知应用" },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail.packageName.ifBlank { "未知应用" },
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = time,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceSecondary,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SegmentedTabs(selected: DetailTab, onSelect: (DetailTab) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(colors.surfaceContainer)
            .padding(3.dp),
    ) {
        DetailTab.values().forEach { item ->
            val active = item == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) colors.surface else Color.Transparent)
                    .clickable { onSelect(item) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = item.label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                    color = if (active) colors.primary else colors.onSurfaceSecondary,
                )
            }
        }
    }
}

@Composable
private fun BasicTab(detail: CaptureDetail) {
    val (baseUrl, urlPath) = splitUrl(detail.url)
    Column {
        InfoCard(
            listOf(
                "方法" to detail.method,
                "协议" to detail.proto.ifBlank { "-" },
                "状态码" to if (detail.error.isNotBlank()) "失败" else statusText(detail.statusCode),
                "耗时" to if (detail.durationMs > 0) "${detail.durationMs} ms" else "-",
                "开始时间" to TimeFmt.stamp(detail.startedAt),
                "IP" to detail.remoteAddr.ifBlank { "-" },
                "基础 URL" to baseUrl.ifBlank { "-" },
                "路径" to urlPath.ifBlank { "-" },
                "请求头数量" to detail.requestHeaders.size.toString(),
                "响应头数量" to detail.responseHeaders.size.toString(),
                "请求大小" to formatSize(detail.requestBody.size),
                "响应大小" to formatSize(detail.responseBody.size),
                "App 包名" to detail.packageName.ifBlank { "-" },
                "UID" to detail.uid.toString(),
            ),
        )
        if (detail.error.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            PaddedCard {
                Text(
                    text = "错误",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFD64545),
                )
                Spacer(Modifier.height(4.dp))
                SelectionContainer {
                    Text(
                        text = detail.error,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(rows: List<Pair<String, String>>) {
    val colors = MiuixTheme.colorScheme
    PaddedCard {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.6.dp)
                        .background(colors.dividerLine),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    color = colors.onSurfaceSecondary,
                    modifier = Modifier.width(84.dp),
                )
                Spacer(Modifier.width(6.dp))
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        text = value.ifBlank { "-" },
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = colors.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun BodyTab(
    title: String,
    headers: List<HeaderItem>,
    body: BodyView,
    contentType: String,
) {
    val colors = MiuixTheme.colorScheme
    Column {
        if (headers.isNotEmpty()) {
            PaddedCard {
                Text(
                    text = "Headers",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceSecondary,
                )
                Spacer(Modifier.height(6.dp))
                headers.forEach { header ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(
                            text = header.name,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceSecondary,
                            modifier = Modifier.width(124.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(6.dp))
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(
                                text = header.value,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.onSurface,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        BodyCard(title = title, body = body, contentType = contentType)
    }
}

@Composable
private fun BodyCard(title: String, body: BodyView, contentType: String) {
    val colors = MiuixTheme.colorScheme
    val context = LocalContext.current
    // 类型和正文一次算好：之前 kind() 和 displayText() 会各 parse + 美化一遍正文，
    // 大 JSON 在切 tab 时是实打实的主线程开销。
    val render = remember(body.data, body.encoding, contentType) {
        BodyCodec.render(contentType, body)
    }
    val kind = render.kind
    val text = render.text
    val image = remember(body.data, contentType) {
        if (body.encoding == "base64" && contentType.contains("image", ignoreCase = true)) {
            decodeBase64Image(body.data)
        } else {
            null
        }
    }

    PaddedCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurfaceSecondary,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = kind.label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = typeColor(contentType),
            )
            Text(
                text = " · ${formatSize(body.size, compact = true)}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceSecondary,
            )
            Spacer(Modifier.weight(1f))
            if (text.isNotEmpty() || image != null) {
                Text(
                    text = "复制",
                    fontSize = 12.sp,
                    color = colors.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            copyToClipboard(context, text)
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        if (body.truncated) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "正文超过 128 KiB，仅保留前面部分",
                fontSize = 11.sp,
                color = Color(0xFFD08A22),
            )
        }
        Spacer(Modifier.height(8.dp))
        when {
            image != null -> Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )

            text.isEmpty() -> Text(
                text = "（空）",
                fontSize = 12.sp,
                color = colors.onSurfaceSecondary,
            )

            else -> SelectionContainer {
                Text(
                    text = displayBody(text),
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurface,
                )
            }
        }
    }
}

/** 单次布局超长文本会让整页掉帧，详情只渲染前 6 万字符。 */
private const val MAX_DISPLAY_CHARS = 60_000

private fun displayBody(text: String): String =
    if (text.length <= MAX_DISPLAY_CHARS) text
    else text.take(MAX_DISPLAY_CHARS) + "\n\n…（仅显示前 6 万字符，可点复制取全文）"

private fun decodeBase64Image(data: String) = runCatching {
    val bytes = Base64.decode(data, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    runCatching { manager?.setPrimaryClip(ClipData.newPlainText("Mitt-go", text)) }
}

@Composable
private fun MethodBadgePublic(method: String) {
    val color = methodColor(method)
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = method.ifBlank { "?" }.uppercase(),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}