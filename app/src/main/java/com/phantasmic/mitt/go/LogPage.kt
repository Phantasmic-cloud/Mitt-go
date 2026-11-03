package com.phantasmic.mitt.go

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme



/**
 * 日志页：展示 [MittLog] 内存缓冲（关掉 App 就没了）。
 * 右上角的导出按钮在 MittGoRoot 的顶栏里，这里只管列表。
 */
@Composable
fun LogPage(topInset: Dp, bottomInset: Dp) {
    val colors = MiuixTheme.colorScheme
    val entries by MittLog.entries.collectAsState()
    val listState = rememberLazyListState()

    // 停在底部时跟随新日志；用户往上翻看时不动，避免被强行拽回来。
    LaunchedEffect(entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (last < 0 || last >= entries.size - 3) {
            listState.scrollToItem(entries.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = topInset + 6.dp,
            bottom = bottomInset + 110.dp,
        ),
    ) {
        if (entries.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "还没有日志。开始抓包或打开 DEBUG 模式后再来看。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.onSurfaceSecondary,
                    modifier = Modifier.padding(vertical = 18.dp),
                )
            }
        }
        itemsIndexed(items = entries) { _, entry ->
            val levelColor = when (entry.level) {
                LogLevel.DEBUG -> colors.onSurfaceSecondary
                LogLevel.INFO -> colors.primary
                LogLevel.WARNING -> Color(0xFFD98A22)
                LogLevel.ERROR -> Color(0xFFD24B4B)
            }
            val text = buildAnnotatedString {
                withStyle(SpanStyle(color = colors.onSurfaceSecondary)) {
                    append("[${MittLog.timeOf(entry.time)}] ")
                }
                withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Medium)) {
                    append("[${entry.level.label}] ")
                }
                withStyle(SpanStyle(color = colors.onSurfaceSecondary)) {
                    append("[${entry.tag}] ")
                }
                append(entry.message)
            }
            Text(
                text = text,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

/** 左上角悬浮短胶囊标题：日志、模块、设置共用同一个。 */
@Composable
fun TitleCapsule(
    title: String,
    topInset: Dp,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val pill = RoundedCornerShape(50)
    val container = colors.surfaceContainer.copy(alpha = if (backdrop == null) 0.92f else 0.55f)
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = topInset + 8.dp),
    ) {
        Box(
            Modifier
                .height(48.dp)
                .clip(pill)
                .glassSurface(backdrop = backdrop, shape = pill, container = container)
                .border(0.8.dp, colors.outline.copy(alpha = 0.35f), pill)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
        }
    }
}

/** 日志页顶部的悬浮条：左侧短胶囊写“日志”，右侧圆形导出按钮。 */
@Composable
fun LogHeader(
    topInset: Dp,
    backdrop: Backdrop?,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val pill = RoundedCornerShape(50)
    val container = colors.surfaceContainer.copy(alpha = if (backdrop == null) 0.92f else 0.55f)
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = topInset + 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .height(48.dp)
                .clip(pill)
                .glassSurface(backdrop = backdrop, shape = pill, container = container)
                .border(0.8.dp, colors.outline.copy(alpha = 0.35f), pill)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "日志",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
            )
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .glassSurface(backdrop = backdrop, shape = CircleShape, container = container)
                .border(0.8.dp, colors.outline.copy(alpha = 0.35f), CircleShape)
                .clickable(onClick = onExport),
            contentAlignment = Alignment.Center,
        ) {
            ExportGlyph(color = colors.onSurface)
        }
    }
}

@Composable
fun ExportGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(color, Offset(w * 0.50f, h * 0.10f), Offset(w * 0.50f, h * 0.60f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.28f, h * 0.42f), Offset(w * 0.50f, h * 0.64f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.72f, h * 0.42f), Offset(w * 0.50f, h * 0.64f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.18f, h * 0.88f), Offset(w * 0.82f, h * 0.88f), stroke, cap = StrokeCap.Round)
    }
}

/** 导出当前内存里的日志到 Download/，完成后 Toast 报文件名。 */
fun exportLogs(context: Context) {
    Thread {
        val name = MittLog.export(context)
        Handler(Looper.getMainLooper()).post {
            val text = if (name == null) {
                "导出失败，请检查存储权限"
            } else {
                "已导出到 Download/$name"
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }.start()
}
