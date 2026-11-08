package com.phantasmic.mitt.go

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phantasmic.mitt.go.ui.FloatingBottomBar
import com.phantasmic.mitt.go.ui.FloatingBottomBarItem
import com.phantasmic.mitt.go.ui.LocalGlassEnabled
import com.phantasmic.mitt.go.ui.LocalPageBackdrop
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor



/** 单个 tab 的固定宽度：KSU 悬浮胶囊按内容定宽，不用 weight 撑满。 */
private val TAB_WIDTH = 66.dp

private data class TabSpec(val label: String, val kind: Int)

private val tabs = listOf(
    TabSpec("日志", 2),
    TabSpec("抓包", 0),
    TabSpec("模块", 1),
    TabSpec("设置", 3),
)

/**
 * 悬浮底栏：始终使用 FloatingBottomBar，
 * 关掉高级效果时只是把模糊关掉。之前的自绘版本在部分机型上帧率反而更差。
 */
@Composable
fun MittBottomBar(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 底栏模糊的是一级页内容，取景图层由 MittGoRoot 下发。
    val backdrop = LocalPageBackdrop.current ?: return
    FloatingBottomBar(
        modifier = modifier,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        backdrop = backdrop,
        tabsCount = tabs.size,
        isBlurEnabled = LocalGlassEnabled.current,
    ) { activateTab ->
        tabs.forEachIndexed { index, spec ->
            GlassTab(spec, selectedIndex == index) { activateTab(index) }
        }
    }
}

@Composable
private fun RowScope.GlassTab(spec: TabSpec, selected: Boolean, onClick: () -> Unit) {
    FloatingBottomBarItem(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minWidth = TAB_WIDTH),
    ) {
        TabGlyph(spec.kind, LocalContentColor.current)
        Text(
            text = spec.label,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

