package com.phantasmic.mitt.go

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 高级效果开启时用 backdrop 做实时模糊，否则退回普通半透明。
 * 顶栏按钮、菜单、搜索胶囊都走这一套，和悬浮底栏保持同一种玻璃质感。
 */
fun Modifier.glassSurface(
    backdrop: Backdrop?,
    shape: Shape,
    container: Color,
): Modifier = if (backdrop == null) {
    background(container, shape)
} else {
    drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            blur(8.dp.toPx(), 8.dp.toPx())
        },
        onDrawSurface = { drawRect(container) },
    )
}

/** 抓包页右上角的圆形按钮。 */
@Composable
fun CircleMenuButton(
    open: Boolean,
    backdrop: Backdrop?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .glassSurface(
                backdrop = backdrop,
                shape = CircleShape,
                container = colors.surfaceContainer.copy(alpha = if (open) 0.72f else 0.55f),
            )
            .border(0.8.dp, colors.outline.copy(alpha = 0.35f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MenuDots()
    }
}

/** 抓包页菜单：搜索 / 清空 / 显示方式 / 关键词高亮 / 历史记录。 */
@Composable
fun CaptureMenu(
    searchOpen: Boolean,
    domainMode: Boolean,
    backdrop: Backdrop?,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onToggleMode: () -> Unit,
    onHighlight: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .width(184.dp)
            .clip(shape)
            .glassSurface(
                backdrop = backdrop,
                shape = shape,
                container = colors.surfaceContainer.copy(alpha = 0.68f),
            )
            .border(0.8.dp, colors.onSurface.copy(alpha = 0.10f), shape)
            .padding(vertical = 4.dp),
    ) {
        MenuItem(
            kind = 0,
            label = if (searchOpen) "关闭搜索" else "搜索",
            checked = searchOpen,
            onClick = onSearch,
        )
        MenuItem(kind = 1, label = "清空记录", checked = false, onClick = onClear)
        MenuSeparator()
        MenuItem(
            kind = 2,
            label = if (domainMode) "按完整 URL 显示" else "按域名显示",
            checked = domainMode,
            onClick = onToggleMode,
        )
        MenuItem(kind = 3, label = "关键词高亮", checked = false, onClick = onHighlight)
        MenuItem(kind = 4, label = "历史记录", checked = false, onClick = onHistory)
    }
}

@Composable
private fun MenuItem(kind: Int, label: String, checked: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val contentColor = if (checked) colors.primary else colors.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuGlyph(kind = kind, color = contentColor, size = 17.dp)
        Spacer(Modifier.width(9.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(colors.primary, CircleShape),
            )
        }
    }
}

@Composable
private fun MenuSeparator() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(0.6.dp)
            .background(MiuixTheme.colorScheme.dividerLine),
    )
}

@Composable
private fun MenuDots() {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val radius = 1.7.dp.toPx()
        val color = Color(0xFF7A7A7A)
        listOf(0.28f, 0.5f, 0.72f).forEach { fraction ->
            drawCircle(
                color = color,
                radius = radius,
                center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * fraction),
            )
        }
    }
}