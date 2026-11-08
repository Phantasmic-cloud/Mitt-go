package com.phantasmic.mitt.go.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.abs

/**
 * 一级页的悬浮元素：标题短胶囊、右上角的圆形按钮。
 *
 * 这些元素必须画在页面取景图层**外面**（放进页面里会和 `drawBackdrop` 的记录节点
 * 成环，hwui 直接栈溢出），但放在 pager 外面又不会跟着手指走——左右搓动时它们会
 * 钉在原地，看起来和页面脱节。所以这里按 pager 的页面偏移做等速平移：元素跟着
 * 它所属的那一页一起滑进滑出，和抓包页里跟着页面走的搜索框观感一致。
 *
 * 用法：放在一级页 Pager 的兄弟位置，[content] 里照常用 `Modifier.align(...)` 定位。
 * 翻到别的页时它会被平移到屏幕外，不需要手动隐藏。
 *
 * @param page 这块悬浮元素属于第几页。
 */
@Composable
fun PageChrome(
    pagerState: PagerState,
    page: Int,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    // 只组合“正在看”和“正在滑进来”的那一页的悬浮元素：静态页面上不为屏幕外的
    // 玻璃跑 blur。用 derivedStateOf 把逐帧的滚动偏移收敛成一个布尔值，
    // 翻页过程中不会每帧重组。
    val nearby by remember(pagerState, page) {
        derivedStateOf { abs(pagerState.getOffsetDistanceInPages(page)) < 1f }
    }
    if (!nearby) return

    BoxWithConstraints(modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 官方工具函数：正数 = 在当前页右边，单位是页。绘制阶段读滚动状态，
                    // 翻页只重绘这一层，不会触发重组。
                    translationX = pagerState.getOffsetDistanceInPages(page) * widthPx
                },
            content = content,
        )
    }
}
