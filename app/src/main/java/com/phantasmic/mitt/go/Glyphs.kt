package com.phantasmic.mitt.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全 App 的图标都在这里，一律用 Canvas 手绘（不引图标库）。
 *
 * 加新图标就加在这个文件里，不要在各页面里再写一份；改现有图标的
 * 坐标/尺寸会影响界面观感，改之前先确认。
 */

/** 设置项左侧的圆形图标。 */
enum class SettingIcon {
    PROXY,
    SOCKS,
    UPSTREAM,
    FILTER,
    MEMORY,
    DEBUG,
    CERT,
    THEME,
    ABOUT,
    DOMAIN_FILTER,
    AUTO_START,
}

/** 三个点（抓包页右上角的菜单按钮）。 */
@Composable
fun MenuDots(color: Color = Color(0xFF7A7A7A)) {
    Canvas(Modifier.size(20.dp)) {
        val radius = 1.7.dp.toPx()
        listOf(0.28f, 0.5f, 0.72f).forEach { fraction ->
            drawCircle(
                color = color,
                radius = radius,
                center = Offset(size.width / 2f, size.height * fraction),
            )
        }
    }
}

@Composable
fun SearchGlyph(color: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val stroke = 1.7.dp.toPx()
        drawCircle(
            color,
            this.size.minDimension * 0.32f,
            Offset(this.size.width * 0.42f, this.size.height * 0.42f),
            style = Stroke(stroke),
        )
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
        drawLine(
            color,
            Offset(0.22f * this.size.width, 0.22f * this.size.height),
            Offset(0.78f * this.size.width, 0.78f * this.size.height),
            stroke,
        )
        drawLine(
            color,
            Offset(0.78f * this.size.width, 0.22f * this.size.height),
            Offset(0.22f * this.size.width, 0.78f * this.size.height),
            stroke,
        )
    }
}

/** 右上角的「新建」加号。 */
@Composable
fun PlusGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(color, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.84f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.16f, h * 0.5f), Offset(w * 0.84f, h * 0.5f), stroke, cap = StrokeCap.Round)
    }
}

/**
 * 导出：箭头朝上，底部一条横线（写出去）。
 *
 * 导入/导出是两件事，图标必须分开：导入用 [ImportGlyph]（箭头朝下），导出用这个。
 */
@Composable
fun ExportGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(color, Offset(w * 0.50f, h * 0.80f), Offset(w * 0.50f, h * 0.30f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.28f, h * 0.48f), Offset(w * 0.50f, h * 0.26f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.72f, h * 0.48f), Offset(w * 0.50f, h * 0.26f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.20f, h * 0.90f), Offset(w * 0.80f, h * 0.90f), stroke, cap = StrokeCap.Round)
    }
}

/**
 * 导入：箭头朝下，底部一条横线（取进来）。
 *
 * 和 [ExportGlyph] 是一对，两个动作不要共用一个图标。
 */
@Composable
fun ImportGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(color, Offset(w * 0.50f, h * 0.20f), Offset(w * 0.50f, h * 0.70f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.28f, h * 0.52f), Offset(w * 0.50f, h * 0.74f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.72f, h * 0.52f), Offset(w * 0.50f, h * 0.74f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.20f, h * 0.90f), Offset(w * 0.80f, h * 0.90f), stroke, cap = StrokeCap.Round)
    }
}

/**
 * 播放三角：抓包页的「开始抓包」圆钮。
 *
 * 用 fill + 同色圆角描边把三个角磨圆，纯 fillPath 的尖角在圆钮里显得很硬。
 */
@Composable
fun PlayGlyph(color: Color, size: Dp = 15.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.30f, h * 0.16f)
            lineTo(w * 0.85f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.84f)
            close()
        }
        drawPath(path, color)
        drawPath(path, color, style = Stroke(width = w * 0.16f, join = StrokeJoin.Round))
    }
}

/** 停止方块：抓包页的「停止抓包」圆钮。 */
@Composable
fun StopGlyph(color: Color, size: Dp = 13.dp) {
    Canvas(Modifier.size(size)) {
        val side = this.size.width
        val inset = side * 0.10f
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(side - inset * 2f, side - inset * 2f),
            cornerRadius = CornerRadius(side * 0.20f, side * 0.20f),
        )
    }
}

/** 会话列表的垃圾桶（可点击，20dp）。 */
@Composable
fun TrashGlyph(color: Color, onClick: () -> Unit) {
    Canvas(
        Modifier
            .size(20.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(1.dp),
    ) {
        val w = size.width
        val h = size.height
        val stroke = 1.6.dp.toPx()
        drawLine(color, Offset(w * 0.16f, h * 0.28f), Offset(w * 0.84f, h * 0.28f), stroke)
        drawLine(color, Offset(w * 0.38f, h * 0.28f), Offset(w * 0.38f, h * 0.15f), stroke)
        drawLine(color, Offset(w * 0.62f, h * 0.28f), Offset(w * 0.62f, h * 0.15f), stroke)
        drawLine(color, Offset(w * 0.38f, h * 0.15f), Offset(w * 0.62f, h * 0.15f), stroke)
        drawLine(color, Offset(w * 0.26f, h * 0.28f), Offset(w * 0.32f, h * 0.88f), stroke)
        drawLine(color, Offset(w * 0.74f, h * 0.28f), Offset(w * 0.68f, h * 0.88f), stroke)
        drawLine(color, Offset(w * 0.32f, h * 0.88f), Offset(w * 0.68f, h * 0.88f), stroke)
        drawLine(color, Offset(w * 0.45f, h * 0.40f), Offset(w * 0.46f, h * 0.76f), stroke)
        drawLine(color, Offset(w * 0.55f, h * 0.40f), Offset(w * 0.54f, h * 0.76f), stroke)
    }
}

/** 编辑（铅笔）。 */
@Composable
fun EditGlyph(color: Color, size: Dp = 16.dp) {
    Canvas(Modifier.size(size)) {
        val stroke = 1.6.dp.toPx()
        val w = this.size.width
        val h = this.size.height
        // 笔身：两条长边 + 笔尾
        drawLine(color, Offset(w * 0.28f, h * 0.68f), Offset(w * 0.78f, h * 0.18f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.40f, h * 0.80f), Offset(w * 0.90f, h * 0.30f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.78f, h * 0.18f), Offset(w * 0.90f, h * 0.30f), stroke, cap = StrokeCap.Round)
        // 笔尖
        drawLine(color, Offset(w * 0.28f, h * 0.68f), Offset(w * 0.21f, h * 0.87f), stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.40f, h * 0.80f), Offset(w * 0.21f, h * 0.87f), stroke, cap = StrokeCap.Round)
    }
}

/** 菜单里的小垃圾桶（18dp，无点击区域，点击由外层菜单行处理）。 */
@Composable
fun TrashGlyphSmall(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.6.dp.toPx()
        val left = size.width * 0.22f
        val right = size.width * 0.78f
        val top = size.height * 0.3f
        drawLine(color, Offset(left, top), Offset(right, top), stroke)
        drawLine(
            color,
            Offset(size.width * 0.4f, size.height * 0.2f),
            Offset(size.width * 0.6f, size.height * 0.2f),
            stroke,
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(left + stroke, top + stroke),
            size = Size(right - left - stroke * 2, size.height * 0.52f),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(stroke),
        )
    }
}

/** 设置项左侧的圆形小图标：实心圆底 + 反白符号。 */
@Composable
fun SettingGlyph(icon: SettingIcon) {
    val colors = MiuixTheme.colorScheme
    Box(
        Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(colors.onSurface),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val fg = colors.surface
            val w = size.width
            val h = size.height
            val stroke = 1.4.dp.toPx()
            when (icon) {
                // 代理模式：闪电
                SettingIcon.PROXY -> {
                    val bolt = Path().apply {
                        moveTo(w * 0.58f, 0f)
                        lineTo(w * 0.16f, h * 0.56f)
                        lineTo(w * 0.46f, h * 0.56f)
                        lineTo(w * 0.40f, h)
                        lineTo(w * 0.84f, h * 0.42f)
                        lineTo(w * 0.54f, h * 0.42f)
                        close()
                    }
                    drawPath(bolt, fg)
                }
                // SOCKS5：双向箭头
                SettingIcon.SOCKS -> {
                    drawLine(fg, Offset(w * 0.08f, h * 0.32f), Offset(w * 0.88f, h * 0.32f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.66f, h * 0.10f), Offset(w * 0.90f, h * 0.32f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.08f, h * 0.70f), Offset(w * 0.88f, h * 0.70f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.30f, h * 0.48f), Offset(w * 0.06f, h * 0.70f), stroke, cap = StrokeCap.Round)
                }
                // 上游代理：请求先进小盒子，再从箭头送到上游
                SettingIcon.UPSTREAM -> {
                    drawRoundRect(
                        color = fg,
                        topLeft = Offset(w * 0.04f, h * 0.26f),
                        size = Size(w * 0.34f, h * 0.48f),
                        cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
                        style = Stroke(stroke),
                    )
                    drawLine(fg, Offset(w * 0.46f, h * 0.50f), Offset(w * 0.94f, h * 0.50f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.74f, h * 0.28f), Offset(w * 0.94f, h * 0.50f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.74f, h * 0.72f), Offset(w * 0.94f, h * 0.50f), stroke, cap = StrokeCap.Round)
                }
                // 代理过滤：漏斗
                SettingIcon.FILTER -> {
                    val funnel = Path().apply {
                        moveTo(0f, h * 0.14f)
                        lineTo(w, h * 0.14f)
                        lineTo(w * 0.60f, h * 0.58f)
                        lineTo(w * 0.60f, h * 0.92f)
                        lineTo(w * 0.40f, h * 0.92f)
                        lineTo(w * 0.40f, h * 0.58f)
                        close()
                    }
                    drawPath(funnel, fg)
                }
                // 内存上限：内存条
                SettingIcon.MEMORY -> {
                    drawRoundRect(
                        color = fg,
                        topLeft = Offset(w * 0.06f, h * 0.22f),
                        size = Size(w * 0.88f, h * 0.56f),
                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                        style = Stroke(stroke),
                    )
                    listOf(0.32f, 0.50f, 0.68f).forEach { x ->
                        drawLine(fg, Offset(w * x, h * 0.36f), Offset(w * x, h * 0.64f), stroke * 0.9f)
                    }
                }
                // DEBUG：</> 代码括号
                SettingIcon.DEBUG -> {
                    drawLine(fg, Offset(w * 0.34f, h * 0.18f), Offset(w * 0.10f, h * 0.50f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.10f, h * 0.50f), Offset(w * 0.34f, h * 0.82f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.66f, h * 0.18f), Offset(w * 0.90f, h * 0.50f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.90f, h * 0.50f), Offset(w * 0.66f, h * 0.82f), stroke, cap = StrokeCap.Round)
                }
                // 证书：盾牌
                SettingIcon.CERT -> {
                    val shield = Path().apply {
                        moveTo(w * 0.50f, 0f)
                        lineTo(w, h * 0.20f)
                        lineTo(w * 0.90f, h * 0.66f)
                        lineTo(w * 0.50f, h)
                        lineTo(w * 0.10f, h * 0.66f)
                        lineTo(0f, h * 0.20f)
                        close()
                    }
                    drawPath(shield, fg)
                }
                // 主题：太阳
                SettingIcon.THEME -> {
                    val cx = w / 2f
                    val cy = h / 2f
                    drawCircle(fg, radius = w * 0.26f, center = Offset(cx, cy))
                    listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f).forEach { deg ->
                        val rad = Math.toRadians(deg.toDouble())
                        val dx = cos(rad).toFloat()
                        val dy = sin(rad).toFloat()
                        drawLine(
                            fg,
                            Offset(cx + dx * w * 0.36f, cy + dy * h * 0.36f),
                            Offset(cx + dx * w * 0.48f, cy + dy * h * 0.48f),
                            stroke * 0.9f,
                            cap = StrokeCap.Round,
                        )
                    }
                }
                // 域名过滤：地球
                SettingIcon.DOMAIN_FILTER -> {
                    val cx = w / 2f
                    val cy = h / 2f
                    drawCircle(fg, radius = w * 0.44f, center = Offset(cx, cy), style = Stroke(stroke))
                    drawLine(fg, Offset(0f, cy), Offset(w, cy), stroke * 0.9f)
                    drawOval(
                        color = fg,
                        topLeft = Offset(w * 0.30f, 0f),
                        size = Size(w * 0.40f, h),
                        style = Stroke(stroke * 0.9f),
                    )
                }
                // 自动开启抓包：播放三角
                SettingIcon.AUTO_START -> {
                    val tri = Path().apply {
                        moveTo(w * 0.24f, h * 0.16f)
                        lineTo(w * 0.86f, h * 0.50f)
                        lineTo(w * 0.24f, h * 0.84f)
                        close()
                    }
                    drawPath(tri, fg)
                }
                // 关于：i
                SettingIcon.ABOUT -> {
                    drawCircle(fg, radius = w * 0.10f, center = Offset(w * 0.50f, h * 0.22f))
                    drawLine(fg, Offset(w * 0.50f, h * 0.42f), Offset(w * 0.50f, h * 0.82f), 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
    }
}

/** 抓包页菜单的小图标。 */
@Composable
fun MenuGlyph(kind: Int, color: Color, size: Dp = 18.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.6.dp.toPx()
        when (kind) {
            0 -> {
                drawCircle(color, w * 0.30f, Offset(w * 0.42f, h * 0.42f), style = Stroke(stroke))
                drawLine(color, Offset(w * 0.64f, h * 0.64f), Offset(w * 0.92f, h * 0.92f), stroke)
            }

            1 -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(w * 0.22f, h * 0.30f),
                    size = Size(w * 0.56f, h * 0.56f),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = Stroke(stroke),
                )
                drawLine(color, Offset(w * 0.36f, h * 0.12f), Offset(w * 0.36f, h * 0.30f), stroke)
                drawLine(color, Offset(w * 0.64f, h * 0.86f), Offset(w * 0.64f, h * 0.96f), stroke)
            }

            2 -> {
                drawLine(color, Offset(w * 0.10f, h * 0.30f), Offset(w * 0.90f, h * 0.30f), stroke)
                drawLine(color, Offset(w * 0.10f, h * 0.70f), Offset(w * 0.90f, h * 0.70f), stroke)
                drawCircle(color, w * 0.10f, Offset(w * 0.36f, h * 0.30f))
                drawCircle(color, w * 0.10f, Offset(w * 0.64f, h * 0.70f))
            }

            3 -> {
                drawCircle(color, w * 0.26f, Offset(w * 0.5f, h * 0.40f), style = Stroke(stroke))
                drawLine(color, Offset(w * 0.44f, h * 0.62f), Offset(w * 0.38f, h * 0.92f), stroke)
                drawLine(color, Offset(w * 0.56f, h * 0.62f), Offset(w * 0.62f, h * 0.92f), stroke)
                drawLine(color, Offset(w * 0.30f, h * 0.30f), Offset(w * 0.70f, h * 0.30f), stroke)
            }

            else -> {
                drawCircle(color, w * 0.34f, Offset(w * 0.5f, h * 0.5f), style = Stroke(stroke))
                drawLine(color, Offset(w * 0.5f, h * 0.16f), Offset(w * 0.5f, h * 0.30f), stroke)
                drawLine(color, Offset(w * 0.5f, h * 0.70f), Offset(w * 0.5f, h * 0.84f), stroke)
            }
        }
    }
}

/** 底栏四个 tab 的图标。 */
@Composable
fun TabGlyph(kind: Int, color: Color) {
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

/** 模块页空状态的四宫格。 */
@Composable
fun ModuleGlyph() {
    val color = MiuixTheme.colorScheme.onSurfaceSecondary
    Canvas(Modifier.size(22.dp)) {
        val gap = 2.5.dp.toPx()
        val cell = (size.width - gap) / 2f
        val radius = CornerRadius(3.5.dp.toPx())
        drawRoundRect(color, Offset.Zero, Size(cell, cell), radius)
        drawRoundRect(color, Offset(cell + gap, 0f), Size(cell, cell), radius)
        drawRoundRect(color, Offset(0f, cell + gap), Size(cell, cell), radius)
        drawRoundRect(color, Offset(cell + gap, cell + gap), Size(cell, cell), radius)
    }
}
