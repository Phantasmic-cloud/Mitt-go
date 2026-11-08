package com.phantasmic.mitt.go

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phantasmic.mitt.go.ui.CircleGlassButton
import com.phantasmic.mitt.go.ui.GlassCard
import com.phantasmic.mitt.go.ui.GlassLevel
import com.phantasmic.mitt.go.ui.GlassMenuItem
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 抓包页右上角的圆形按钮。玻璃质感和底栏、搜索框统一走 `ui/Glass.kt`。
 */
@Composable
fun CircleMenuButton(
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CircleGlassButton(
        onClick = onClick,
        modifier = modifier,
        active = open,
    ) {
        MenuDots()
    }
}

/** 抓包页菜单：搜索 / 清空 / 显示方式 / 关键词高亮 / 历史记录。 */
@Composable
fun CaptureMenuContent(
    searchOpen: Boolean,
    domainMode: Boolean,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onToggleMode: () -> Unit,
    onHighlight: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    GlassCard(
        modifier = modifier.width(184.dp),
        shape = RoundedCornerShape(16.dp),
        level = GlassLevel.Menu,
        padding = PaddingValues(vertical = 4.dp),
    ) {
        MenuRow(
            kind = 0,
            label = if (searchOpen) "关闭搜索" else "搜索",
            checked = searchOpen,
            onClick = onSearch,
        )
        MenuRow(kind = 1, label = "清空记录", checked = false, onClick = onClear)
        MenuSeparator()
        MenuRow(
            kind = 2,
            label = if (domainMode) "按完整 URL 显示" else "按域名显示",
            checked = domainMode,
            onClick = onToggleMode,
        )
        MenuRow(kind = 3, label = "关键词高亮", checked = false, onClick = onHighlight)
        MenuRow(kind = 4, label = "历史记录", checked = false, onClick = onHistory)
    }
}

@Composable
private fun MenuRow(kind: Int, label: String, checked: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val contentColor = if (checked) colors.primary else colors.onSurface
    GlassMenuItem(
        label = label,
        onClick = onClick,
        leading = { MenuGlyph(kind = kind, color = contentColor, size = 17.dp) },
        checked = checked,
        fontSize = 13.sp,
        contentColor = contentColor,
    )
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
