package com.phantasmic.mitt.go

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phantasmic.mitt.go.ui.CircleGlassButton
import com.phantasmic.mitt.go.ui.glassSurface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 日志页：展示 [MittLog] 内存缓冲（关掉 App 就没了）。
 * 右上角的导出按钮在 [LogHeader] 里，这里只管列表。
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

/**
 * 左上角悬浮短胶囊标题：日志、模块、设置共用同一个。
 *
 * @param endAction 右侧追加的动作（日志页的导出按钮）；不需要就不传。
 */
@Composable
fun TitleCapsule(
    title: String,
    topInset: Dp,
    modifier: Modifier = Modifier,
    endAction: (@Composable () -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    val pill = RoundedCornerShape(50)
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
                .glassSurface(shape = pill)
                .border(0.8.dp, colors.outline.copy(alpha = 0.28f), pill)
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
        if (endAction != null) {
            Spacer(Modifier.weight(1f))
            endAction()
        }
    }
}

/** 日志页顶部的悬浮条：左侧短胶囊写“日志”，右侧圆形导出按钮。 */
@Composable
fun LogHeader(
    topInset: Dp,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    TitleCapsule(title = "日志", topInset = topInset, modifier = modifier) {
        CircleGlassButton(onClick = onExport) {
            ExportGlyph(color = colors.onSurface)
        }
    }
}

/** 导出当前内存里的日志到 Download/，完成后 Toast 报文件名。 */
fun exportLogs(context: Context) {
    exportAsync(
        context = context,
        action = { runCatching { MittLog.export(context) ?: error("export failed") } },
        message = { result ->
            result.fold(
                onSuccess = { "已导出到 Download/$it" },
                onFailure = { "导出失败，请检查存储权限" },
            )
        },
    )
}
