package com.phantasmic.mitt.go

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme











enum class CaptureDisplayMode(val label: String) {
    URL("完整 URL"),
    DOMAIN("按域名"),
}
/**
 * 抓包页的纯 UI 状态。搜索输入只会让抓包页自己重组，不会牵动根页面。
 */
class CaptureUiController {
    var searchOpen by mutableStateOf(false)
    var query by mutableStateOf("")
    var displayMode by mutableStateOf(CaptureDisplayMode.URL)
    var highlightEnabled by mutableStateOf(false)
    /** 列表是否在滚动/惯性滑动。 */
    var listScrolling by mutableStateOf(false)
    /** 菜单点“清空记录”时自增。 */
    var clearTick by mutableIntStateOf(0)
    private var consumedTick = 0
    fun consumeClear(): Boolean {
        if (clearTick == consumedTick) return false
        consumedTick = clearTick
        return true
    }
}
@Composable
fun CapturePage(
    runtime: ProxyRuntimeStatus,
    settings: MittSettings,
    backdrop: Backdrop?,
    topInset: Dp,
    bottomInset: Dp,
    controller: CaptureUiController,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelect: (CaptureSummary) -> Unit,
    onSelectHost: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    // 搜索框的玻璃必须用“只取列表”的独立 backdrop。
    // 直接用页面级 backdrop 的话，搜索框会在它自己取景的图层里绘制，
    // hwui 的 RenderNode 树会成环，prepareTreeImpl 递归到栈溢出（SIGSEGV）。
    val listBackdrop = rememberLayerBackdrop(graphicsLayer = rememberGraphicsLayer()) {
        drawRect(colors.surface)
        drawContent()
    }
    val records by CaptureStore.recordFlow.collectAsState()
    // 看过的记录：列表里整行压暗。
    val visitedIds by CaptureStore.visitedFlow.collectAsState()
    val query = controller.query
    val mode = controller.displayMode
    val running = runtime.running
    val listState = rememberLazyListState()
    // 下拉余量：列表已经在最顶端时继续往下拽，内容整体下移，最多到三点按钮下面。
    val density = LocalDensity.current
    val pullPx = remember { mutableFloatStateOf(0f) }
    val pullLimitPx = with(density) { 56.dp.toPx() }
    val pullConnection = remember(listState, pullLimitPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y < 0f && pullPx.floatValue > 0f) {
                    val delta = (-available.y).coerceAtMost(pullPx.floatValue)
                    pullPx.floatValue -= delta
                    return Offset(0f, -delta)
                }
                return Offset.Zero
            }
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.Drag && available.y > 0f && !listState.canScrollBackward) {
                    val before = pullPx.floatValue
                    pullPx.floatValue = (before + available.y).coerceAtMost(pullLimitPx)
                    // 全部吃掉：不让父层再做拉伸/微光，位移完全由这段留白承担。
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            // 松手后不回弹：拉到哪儿就停在哪儿，只有再往上滑才会缩回去。
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            controller.listScrolling = scrolling
        }
    }
    LaunchedEffect(controller.clearTick) {
        if (!controller.consumeClear()) return@LaunchedEffect
        CaptureStore.clear()
        listState.scrollToItem(0)
    }
    val filtered = remember(records, query) {
        val keyword = query.trim().lowercase()
        if (keyword.isEmpty()) {
            records
        } else {
            records.filter { item ->
                item.url.lowercase().contains(keyword) ||
                    item.host.lowercase().contains(keyword) ||
                    item.method.lowercase().contains(keyword) ||
                    item.packageName.lowercase().contains(keyword) ||
                    item.statusCode.toString() == keyword
            }
        }
    }
    val domains = remember(filtered, mode) {
        if (mode == CaptureDisplayMode.DOMAIN) groupByHost(filtered) else emptyList()
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .then(if (backdrop == null) Modifier else Modifier.layerBackdrop(listBackdrop)),
        ) {
            runtime.error?.let { message -> RuntimeErrorBanner(message, topInset) }
            val isEmpty = if (mode == CaptureDisplayMode.DOMAIN) domains.isEmpty() else filtered.isEmpty()
            // 搜索框只是盖在列表上的一层浮窗：它不改变列表的布局，也不裁剪列表。
            // 列表任何时候都能滑到状态栏（与其重合）和三点按钮下面。
            if (isEmpty) {
                EmptyHint(
                    running = running,
                    starting = runtime.starting,
                    filtered = records.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = topInset + 4.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(pullConnection),
                    contentPadding = PaddingValues(
                        top = topInset + 4.dp,
                        bottom = bottomInset + 110.dp,
                    ),
                ) {
                    item(key = "pull-space", contentType = "pull") {
                        Spacer(Modifier.height(with(density) { pullPx.floatValue.toDp() }))
                    }
                    if (mode == CaptureDisplayMode.DOMAIN) {
                        items(items = domains, key = { "d-" + it.host }) { group ->
                            val onClick = remember(group.host) { { onSelectHost(group.host) } }
                            DomainRow(group = group, onClick = onClick)
                        }
                    } else {
                        items(items = filtered, key = { it.id }, contentType = { "capture" }) { item ->
                            val onClick = remember(item.id) {
                                {
                                    CaptureStore.markVisited(item.id)
                                    onSelect(item)
                                }
                            }
                            val accent = remember(item.url, settings.highlightEnabled, settings.highlightRules) {
                                highlightColorFor(item.url, settings.highlightRules, settings.highlightEnabled)
                            }
                            UrlRow(
                                item = item,
                                accent = accent,
                                onClick = onClick,
                                visited = item.id in visitedIds,
                            )
                        }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = controller.searchOpen,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = topInset + 8.dp, start = 16.dp, end = 60.dp),
            enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.94f, animationSpec = tween(160)),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.96f, animationSpec = tween(120)),
        ) {
            SearchCapsule(
                query = query,
                onQueryChange = { controller.query = it },
                onClose = {
                    controller.searchOpen = false
                    controller.query = ""
                },
                backdrop = if (backdrop == null) null else listBackdrop,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        CaptureFab(
            running = running,
            starting = runtime.starting,
            backdrop = if (backdrop == null) null else listBackdrop,
            onClick = { if (running) onStop() else onStart() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 22.dp, bottom = 128.dp),
        )
    }
}
@Composable
private fun SearchCapsule(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .glassSurface(
                backdrop = backdrop,
                shape = shape,
                container = colors.surfaceContainer.copy(
                    alpha = if (backdrop == null) 0.92f else 0.55f,
                ),
            )
            .border(0.8.dp, colors.outline.copy(alpha = 0.35f), shape)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchGlyph(color = colors.onSurfaceSecondary)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = "搜索 URL / 域名",
                    fontSize = 13.sp,
                    color = colors.onSurfaceSecondary,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            Text(
                text = "✕",
                fontSize = 13.sp,
                color = colors.onSurfaceSecondary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onQueryChange("") }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        CloseGlyph(color = colors.onSurfaceSecondary, onClick = onClose)
    }
}
@Composable
private fun StatusLine(settings: MittSettings) {
    val colors = MiuixTheme.colorScheme
    val ports = buildString {
        append("HTTP ").append(settings.httpPort)
        if (settings.socksEnabled) append(" · SOCKS ").append(settings.socksPort)
        if (settings.proxyMode == ProxyMode.VPN_TUN) append(" · TUN ").append(settings.tunnelPort)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(Color(0xFF3AA86B), CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = "抓包中", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
        Spacer(Modifier.width(8.dp))
        Text(
            text = ports,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceSecondary,
        )
    }
}
@Composable
private fun RuntimeErrorBanner(message: String, topInset: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = topInset + 4.dp, start = 16.dp, end = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x1FD64545))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = message, fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFFD64545))
    }
}
@Composable
private fun EmptyHint(
    running: Boolean,
    starting: Boolean,
    filtered: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Canvas(Modifier.size(46.dp)) {
                val w = size.width
                val h = size.height
                val outline = Color(0x667A7A7A)
                val stroke = 2.dp.toPx()
                val plane = Path().apply {
                    moveTo(w * 0.08f, h * 0.50f)
                    lineTo(w * 0.92f, h * 0.12f)
                    lineTo(w * 0.62f, h * 0.90f)
                    lineTo(w * 0.44f, h * 0.58f)
                    close()
                }
                drawPath(plane, outline, style = Stroke(stroke, join = StrokeJoin.Round))
                drawLine(outline, Offset(w * 0.44f, h * 0.58f), Offset(w * 0.92f, h * 0.12f), stroke)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = when {
                    filtered -> "没有匹配的记录"
                    starting -> "正在启动抓包…"
                    running -> "正在等待请求"
                    else -> "还没有抓包记录"
                },
                fontSize = 14.sp,
                color = colors.onSurface,
            )
            val subtitle = when {
                filtered -> "换个关键词，或清空搜索"
                starting -> "Go 内核与代理端口准备中"
                running -> "打开任意 App 产生流量即可看到"
                else -> ""
            }
            if (subtitle.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = colors.onSurfaceSecondary,
                )
            }
        }
    }
}
@Composable
fun UrlRow(
    item: CaptureSummary,
    accent: Int?,
    onClick: () -> Unit,
    visited: Boolean = false,
) {
    val colors = MiuixTheme.colorScheme
    val divider = colors.dividerLine
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind {
                val y = size.height - 0.3.dp.toPx()
                drawLine(
                    color = divider,
                    start = Offset(58.dp.toPx(), y),
                    end = Offset(size.width - 16.dp.toPx(), y),
                    strokeWidth = 0.6.dp.toPx(),
                )
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 已读：App 图标和文字整块压暗（比整行 alpha 明显得多）。
        val contentAlpha = if (visited) 0.35f else 1f
        AppIconBadge(
            packageName = item.packageName,
            size = 34.dp,
            modifier = Modifier.alpha(contentAlpha),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).alpha(contentAlpha)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.method.ifBlank { "?" }.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = methodColor(item.method),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = item.proto.ifBlank { "—" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (item.error.isNotBlank()) "失败" else statusText(item.statusCode),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor(item.statusCode),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (item.durationMs > 0) "${item.durationMs}ms" else "—",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = typeLabel(item.contentType),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    color = typeColor(item.contentType),
                )
                Text(
                    text = " · ${compactSize(item.size)}",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    color = colors.onSurfaceSecondary,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = item.url,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = accent?.let { Color(it) } ?: colors.onSurface,
                fontWeight = if (accent != null) FontWeight.Medium else FontWeight.Normal,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatClock(item.startedAt),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceSecondary,
            modifier = Modifier.alpha(contentAlpha),
        )
    }
}
@Composable
private fun DomainRow(group: DomainGroup, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val divider = colors.dividerLine
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .drawBehind {
                val y = size.height - 0.3.dp.toPx()
                drawLine(
                    color = divider,
                    start = Offset(58.dp.toPx(), y),
                    end = Offset(size.width - 16.dp.toPx(), y),
                    strokeWidth = 0.6.dp.toPx(),
                )
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconBadge(packageName = group.latest.packageName, size = 34.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = group.host,
            fontSize = 14.sp,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${group.count} 次",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceSecondary,
        )
    }
}
@Composable
private fun CaptureFab(
    running: Boolean,
    starting: Boolean,
    backdrop: Backdrop?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 空闲绿、抓包红，颜色和“飞起来/停下来”用同一段节奏过渡。
    val tint by animateColorAsState(
        targetValue = if (running) Color(0xFFD64545) else Color(0xFF3FA75B),
        animationSpec = tween(durationMillis = 420),
        label = "fabTint",
    )
    val flight by animateFloatAsState(
        targetValue = if (running) 1f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "fabFlight",
    )
    val drift = rememberInfiniteTransition(label = "fab")
    val bob by drift.animateFloat(
        initialValue = -2.4f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bob",
    )
    val sway by drift.animateFloat(
        initialValue = -8f,
        targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sway",
    )
    Box(
        modifier = modifier
            .size(58.dp)
            // 质感：同色投影 + 球面高光 + 玻璃/半透明，
            // 玻璃和底栏、搜索框走的是同一个 glassSurface。
            .shadow(
                elevation = 14.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = tint.copy(alpha = 0.55f),
                spotColor = tint.copy(alpha = 0.55f),
            )
            .clip(CircleShape)
            .glassSurface(
                backdrop = backdrop,
                shape = CircleShape,
                container = tint.copy(alpha = if (backdrop == null) 0.92f else 0.62f),
            )
            .clickable(enabled = !starting, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 球面高光：顶部亮、底部略暗
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.10f),
                        ),
                    ),
                ),
        )
        if (starting) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Canvas(
                Modifier
                    .size(28.dp)
                    .graphicsLayer {
                        // 静止时幅度为 0，起飞后开始上下飘 + 轻微摇摆。
                        translationY = bob.dp.toPx() * flight
                        rotationZ = sway * flight
                    },
            ) {
                // 和悬浮底栏“抓包”用的是同一组坐标的纸飞机
                val w = size.width
                val h = size.height
                val stroke = 1.9.dp.toPx()
                val plane = Path().apply {
                    moveTo(w * 0.08f, h * 0.50f)
                    lineTo(w * 0.92f, h * 0.12f)
                    lineTo(w * 0.62f, h * 0.90f)
                    lineTo(w * 0.44f, h * 0.58f)
                    close()
                }
                drawPath(plane, Color.White, style = Stroke(stroke, join = StrokeJoin.Round))
                drawLine(Color.White, Offset(w * 0.44f, h * 0.58f), Offset(w * 0.92f, h * 0.12f), stroke)
            }
        }
    }
}
fun pathOf(url: String): String {
    val schemeEnd = url.indexOf("://")
    val rest = if (schemeEnd >= 0) url.substring(schemeEnd + 3) else url
    val slash = rest.indexOf('/')
    return if (slash >= 0) rest.substring(slash) else "/"
}
fun methodColor(method: String): Color = when (method.uppercase()) {
    "GET" -> Color(0xFF2E9E63)
    "POST" -> Color(0xFF3B7DD8)
    "PUT", "PATCH" -> Color(0xFFD08A22)
    "DELETE" -> Color(0xFFD64545)
    "HEAD", "OPTIONS" -> Color(0xFF8A6BC8)
    else -> Color(0xFF7A7A7A)
}
fun statusText(code: Int): String = if (code <= 0) "—" else code.toString()
fun statusColor(code: Int): Color = when {
    code <= 0 -> Color(0xFF8A8A8A)
    code < 300 -> Color(0xFF3AA86B)
    code < 400 -> Color(0xFF2F8FD8)
    code < 500 -> Color(0xFFD08A22)
    else -> Color(0xFFD64545)
}
fun formatBytes(size: Int): String = when {
    size <= 0 -> "0 B"
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.US, size / 1024.0)
    else -> "%.2f MB".format(java.util.Locale.US, size / 1048576.0)
}
@Composable
fun SearchGlyph(color: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val stroke = 1.7.dp.toPx()
        drawCircle(color, this.size.minDimension * 0.32f, Offset(this.size.width * 0.42f, this.size.height * 0.42f), style = Stroke(stroke))
        drawLine(
            color,
            Offset(this.size.width * 0.66f, this.size.height * 0.66f),
            Offset(this.size.width * 0.92f, this.size.height * 0.92f),
            stroke,
        )
    }
}
@Composable
fun CloseGlyph(color: Color, size: Dp = 16.dp, onClick: (() -> Unit)? = null) {
    val modifier = Modifier.size(size).let { base ->
        if (onClick != null) base.clip(CircleShape).clickable(onClick = onClick) else base
    }
    Canvas(modifier) {
        val stroke = 1.7.dp.toPx()
        drawLine(color, Offset(0.22f * this.size.width, 0.22f * this.size.height), Offset(0.78f * this.size.width, 0.78f * this.size.height), stroke)
        drawLine(color, Offset(0.78f * this.size.width, 0.22f * this.size.height), Offset(0.22f * this.size.width, 0.78f * this.size.height), stroke)
    }
}
