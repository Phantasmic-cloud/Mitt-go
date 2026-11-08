package com.phantasmic.mitt.go

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phantasmic.mitt.go.ui.LocalPageBackdrop
import com.phantasmic.mitt.go.ui.glassSurface
import com.phantasmic.mitt.go.ui.pageBackdrop
import com.phantasmic.mitt.go.ui.rememberPageBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme











/** 抓包列表顶部给三点按钮让出的高度（按钮 8dp 上边距 + 44dp 高 + 4dp 间隔）。 */
private val CAPTURE_LIST_TOP_GAP = 56.dp

/** 抓包 FAB 呼吸环一圈的时长。 */
private const val PULSE_PERIOD_NANOS = 1_600_000_000L

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
    topInset: Dp,
    bottomInset: Dp,
    controller: CaptureUiController,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSelect: (CaptureSummary) -> Unit,
    onSelectHost: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    // 搜索框/FAB 的玻璃必须用“只取列表”的独立 backdrop。
    // 直接用页面级 backdrop 的话，搜索框会在它自己取景的图层里绘制，
    // hwui 的 RenderNode 树会成环，prepareTreeImpl 递归到栈溢出（SIGSEGV）。
    val listBackdrop = rememberPageBackdrop()
    val records by CaptureStore.recordFlow.collectAsState()
    // 看过的记录：列表里整行压暗。
    val visitedIds by CaptureStore.visitedFlow.collectAsState()
    val query = controller.query
    val mode = controller.displayMode
    val running = runtime.running
    val listState = rememberLazyListState()
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
    CompositionLocalProvider(LocalPageBackdrop provides listBackdrop) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .pageBackdrop(listBackdrop),
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
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            // 列表初始位置压在右上角三点按钮下面：
                            // 按钮 8dp 上边距 + 44dp 高 + 4dp 间隔，不然第一条记录会和按钮齐平。
                            top = topInset + CAPTURE_LIST_TOP_GAP,
                            bottom = bottomInset + 110.dp,
                        ),
                    ) {
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            CaptureFab(
                running = running,
                starting = runtime.starting,
                onClick = { if (running) onStop() else onStart() },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 22.dp, bottom = 128.dp),
            )
        }
    }
}
@Composable
private fun SearchCapsule(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .glassSurface(shape = shape)
            .border(0.8.dp, colors.outline.copy(alpha = 0.35f), shape)
            .padding(start = 14.dp, end = 4.dp),
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
        // 一个 X 管两件事：有内容先清空，内容空了再点才关搜索。
        // 触摸区域撑到整颗胶囊高度，16dp 的图形直接当按钮太小了。
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .clickable {
                    if (query.isNotEmpty()) onQueryChange("") else onClose()
                },
            contentAlignment = Alignment.Center,
        ) {
            CloseGlyph(color = colors.onSurfaceSecondary)
        }
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
                    text = " · ${formatSize(item.size, compact = true)}",
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
/**
 * 抓包开关：玻璃圆钮 + 状态色内芯。
 *
 * 不带文字：一级页本来就是纯图标语言（底栏、搜索框、三点按钮都没有文案），
 * 加一行说明反而显得笨重。状态靠颜色和图形表达就够了。
 *
 * 空闲 = 绿芯 + 播放三角；抓包中 = 红芯 + 停止方块 + 玻璃环里的呼吸圈；
 * 启动中 = 绿芯里转圈。呼吸圈只在抓包时按 60fps 手推（[withFrameNanos]），
 * 停止后一帧都不跑，别改回常驻无限动画。
 */
@Composable
private fun CaptureFab(
    running: Boolean,
    starting: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    // 空闲绿、抓包中红：内芯、呼吸圈、描边、投影都跟着这一份颜色走。
    val accent by animateColorAsState(
        targetValue = if (running) Color(0xFFD64545) else Color(0xFF3FA75B),
        animationSpec = tween(durationMillis = 420),
        label = "captureFabAccent",
    )
    val borderColor by animateColorAsState(
        targetValue = if (running) {
            accent.copy(alpha = 0.55f)
        } else {
            colors.outline.copy(alpha = 0.28f)
        },
        animationSpec = tween(durationMillis = 420),
        label = "captureFabBorder",
    )
    // 按下轻微缩小，和 ui/Glass.kt 的 CircleGlassButton 同一套手感（玻璃上叠 ripple 不好看）。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(stiffness = 900f),
        label = "captureFabPress",
    )
    // 呼吸圈进度 0→1 循环。抓包中才推，停止后停在 0、不再要帧。
    val pulse = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val origin = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            pulse.floatValue = ((now - origin) % PULSE_PERIOD_NANOS) / PULSE_PERIOD_NANOS.toFloat()
        }
    }
    Box(
        modifier = modifier
            .size(76.dp)
            .shadow(
                elevation = 15.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = accent.copy(alpha = 0.35f),
                spotColor = accent.copy(alpha = 0.35f),
            )
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            // 磨砂玻璃外环：内芯 52dp，外圈 76dp，中间 12dp 是呼吸圈的跑道。
            .glassSurface(shape = CircleShape)
            .border(1.dp, borderColor, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !starting,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            Canvas(Modifier.fillMaxSize()) {
                val progress = pulse.floatValue
                drawCircle(
                    color = accent.copy(alpha = 0.42f * (1f - progress)),
                    radius = 26.dp.toPx() + 8.5.dp.toPx() * progress,
                    style = Stroke(width = 2.4.dp.toPx()),
                )
            }
        }
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(accent),
            contentAlignment = Alignment.Center,
        ) {
            // 球面高光：上亮下暗，内芯才有点体积感。
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
            when {
                starting -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.6.dp,
                )

                running -> StopGlyph(color = Color.White, size = 17.dp)
                else -> PlayGlyph(color = Color.White, size = 21.dp)
            }
        }
    }
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
