package com.phantasmic.mitt.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phantasmic.mitt.go.ui.FloatingBottomBar
import com.phantasmic.mitt.go.ui.FloatingBottomBarItem
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.theme.LocalContentColor



val CAPTURE_BOTTOM_SPACE = 96.dp

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
    backdrop: Backdrop,
    blurEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    FloatingBottomBar(
        modifier = modifier,
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        backdrop = backdrop,
        tabsCount = tabs.size,
        isBlurEnabled = blurEnabled,
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

@Composable
private fun TabGlyph(kind: Int, color: Color) {
    Canvas(Modifier.size(21.dp).padding(1.dp)) {
        val stroke = 1.8.dp.toPx()
        val w = size.width
        val h = size.height
        when (kind) {
            0 -> {
                // 纸飞机
                val path = Path().apply {
                    moveTo(w * 0.08f, h * 0.50f)
                    lineTo(w * 0.92f, h * 0.12f)
                    lineTo(w * 0.62f, h * 0.90f)
                    lineTo(w * 0.44f, h * 0.58f)
                    close()
                }
                drawPath(path, color, style = Stroke(stroke, join = StrokeJoin.Round))
                drawLine(color, Offset(w * 0.44f, h * 0.58f), Offset(w * 0.92f, h * 0.12f), stroke)
            }

            1 -> {
                // 拼图：方块 + 顶部凸起 + 右缘凹口
                val piece = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRoundRect(
                        RoundRect(
                            rect = Rect(Offset(w * 0.16f, h * 0.20f), Size(w * 0.64f, h * 0.62f)),
                            cornerRadius = CornerRadius(w * 0.10f),
                        ),
                    )
                    // 圆心压在右边缘上，挖出一个半圆凹口
                    addOval(Rect(Offset(w * 0.80f, h * 0.51f), w * 0.13f))
                }
                drawPath(piece, color)
                // 顶部凸起
                drawCircle(color, w * 0.14f, Offset(w * 0.48f, h * 0.20f))
            }

            2 -> {
                // 日志：一页纸 + 三行文字
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.12f, h * 0.10f),
                    size = Size(w * 0.76f, h * 0.80f),
                    cornerRadius = CornerRadius(w * 0.12f),
                    style = Stroke(stroke),
                )
                listOf(0.34f, 0.50f, 0.66f).forEach { y ->
                    drawLine(
                        color,
                        Offset(w * 0.28f, h * y),
                        Offset(w * 0.72f, h * y),
                        stroke * 0.9f,
                    )
                }
            }

            3 -> {
                // 齿轮：实心星形轮廓 + 中间挖孔（EvenOdd）
                val cx = w / 2f
                val cy = h / 2f
                val gear = Path().apply {
                    fillType = PathFillType.EvenOdd
                    for (index in 0 until 16) {
                        val angle = Math.toRadians((index * 22.5).toDouble())
                        val radius = if (index % 2 == 0) 0.46f else 0.34f
                        val x = cx + cos(angle).toFloat() * w * radius
                        val y = cy + sin(angle).toFloat() * h * radius
                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                    addOval(Rect(Offset(cx, cy), w * 0.15f))
                }
                drawPath(gear, color)
            }
        }
    }
}
