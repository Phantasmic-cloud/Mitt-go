package com.phantasmic.mitt.go.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全 App 唯一的玻璃质感实现。
 *
 * 开「高级效果」= 液态玻璃（模糊 + 折射 + 饱和度 + 边缘高光），关掉 = 普通半透明。
 * 页面里不要再手写 `drawBackdrop`，统一调 [glassSurface] / [GlassCard]。
 *
 * 取景规则（很重要，写错会闪退）：
 * - 页面自己创建一个取景图层（[rememberPageBackdrop] + [pageBackdrop]），玻璃模糊的就是它记录的内容；
 * - 浮层（菜单/弹窗）必须画在这个记录节点的**兄弟位置**，不能画在记录节点内部，
 *   否则 hwui 的 RenderNode 树会成环，`prepareTreeImpl` 递归到栈溢出（SIGSEGV）；
 * - 唯一例外是抓包页的搜索框/FAB：它们在页面内部的记录节点里，用的是页面自建的
 *   独立 backdrop（历史上验证过可用），不要再给它们换回页面级 backdrop。
 */

/** 高级效果开关。false 时所有玻璃退回半透明，不建图层、不编 shader。 */
val LocalGlassEnabled = staticCompositionLocalOf { true }

/** 当前页的取景图层。玻璃读它决定模糊什么；null 时退回半透明。 */
val LocalPageBackdrop = compositionLocalOf<Backdrop?> { null }

/** 玻璃的三档尺寸：小胶囊、菜单、弹窗。数值都在 [GlassDefaults] 里。 */
enum class GlassLevel { Pill, Menu, Dialog }

/**
 * 玻璃的观感参数。觉得太重/太淡就改这一处，调用点不用动。
 */
object GlassDefaults {

    /** 开高级效果时的容器不透明度。 */
    fun alpha(level: GlassLevel): Float = when (level) {
        GlassLevel.Pill -> 0.50f
        GlassLevel.Menu -> 0.62f
        GlassLevel.Dialog -> 0.68f
    }

    /** 关掉高级效果时的容器不透明度：不模糊就得挡住底下的内容。 */
    fun solidAlpha(level: GlassLevel): Float = when (level) {
        GlassLevel.Pill -> 0.92f
        GlassLevel.Menu -> 0.96f
        GlassLevel.Dialog -> 0.98f
    }

    fun blur(level: GlassLevel): Dp = when (level) {
        GlassLevel.Pill -> 8.dp
        GlassLevel.Menu -> 8.dp
        GlassLevel.Dialog -> 10.dp
    }

    /** 折射从边缘往里吃多深。 */
    fun lensHeight(level: GlassLevel): Dp = when (level) {
        GlassLevel.Pill -> 10.dp
        GlassLevel.Menu -> 12.dp
        GlassLevel.Dialog -> 16.dp
    }

    /** 边缘处采样偏移多少，越大越“鼓”。 */
    fun lensAmount(level: GlassLevel): Dp = when (level) {
        GlassLevel.Pill -> 8.dp
        GlassLevel.Menu -> 10.dp
        GlassLevel.Dialog -> 14.dp
    }

    /** 记录图层要往外多取一圈，覆盖模糊核与折射的采样范围。 */
    fun padding(level: GlassLevel): Dp = blur(level) * 2f + lensAmount(level)

    @Composable
    fun container(level: GlassLevel): Color {
        val base = MiuixTheme.colorScheme.surfaceContainer
        val alpha = if (LocalGlassEnabled.current) alpha(level) else solidAlpha(level)
        return base.copy(alpha = alpha)
    }

    @Composable
    fun highlight(level: GlassLevel): Highlight {
        val dark = isDark()
        val base = when (level) {
            GlassLevel.Pill -> if (dark) Highlight.GlassStrokeSmallDark else Highlight.GlassStrokeSmallLight
            else -> if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
        }
        return base.copy(alpha = if (level == GlassLevel.Pill) 0.70f else 0.85f)
    }

    /** Miuix 主题解析后的深浅色。不要用 `isSystemInDarkTheme()`：App 支持强制浅色/深色。 */
    @Composable
    fun isDark(): Boolean = MiuixTheme.colorScheme.surface.luminance() < 0.5f
}

/**
 * 建一个「这一页画了什么」的取景图层：先铺页面底色，再录内容。
 * 每个页面/每个二级页图层各自持有一个，别跨页共用。
 */
@Composable
fun rememberPageBackdrop(): LayerBackdrop {
    val surface = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop(graphicsLayer = rememberGraphicsLayer()) {
        drawRect(surface)
        drawContent()
    }
}

/**
 * 把 [backdrop] 的记录节点挂到这块内容上。关掉高级效果时什么都不做。
 *
 * 浮层要画的玻璃不要在 [enabled] 为 true 的这个节点里面，放到兄弟位置去。
 */
@Composable
fun Modifier.pageBackdrop(backdrop: LayerBackdrop, enabled: Boolean = true): Modifier =
    if (enabled && LocalGlassEnabled.current) this.layerBackdrop(backdrop) else this

/**
 * 一块玻璃。
 *
 * @param container 容器色；默认按 [level] 取主题色，需要染色（比如抓包 FAB）时自己传。
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape,
    level: GlassLevel = GlassLevel.Pill,
    container: Color = GlassDefaults.container(level),
): Modifier {
    val backdrop = LocalPageBackdrop.current
    if (!LocalGlassEnabled.current || backdrop == null) {
        return background(container, shape)
    }
    val blurRadius = GlassDefaults.blur(level)
    val lensHeight = GlassDefaults.lensHeight(level)
    val lensAmount = GlassDefaults.lensAmount(level)
    val pad = GlassDefaults.padding(level)
    val highlight = GlassDefaults.highlight(level)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            // 先把记录范围抬到位，再挂效果，避免 blur 之后再改 padding 导致整段重算一次。
            padding = maxOf(padding, pad.toPx())
            vibrancy()
            blur(blurRadius.toPx(), blurRadius.toPx())
            lens(
                refractionHeight = lensHeight.toPx(),
                refractionAmount = lensAmount.toPx(),
            )
        },
        highlight = { highlight },
        onDrawSurface = { drawRect(container) },
    )
}

/**
 * 玻璃卡片：弹窗和菜单的壳。自带描边、圆角裁剪，并且吞掉卡片内的点击
 * （否则点在文字上会穿透到后面的遮罩，把弹窗关掉）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    level: GlassLevel = GlassLevel.Dialog,
    padding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    container: Color = GlassDefaults.container(level),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Column(
        modifier
            .clip(shape)
            .glassSurface(shape = shape, level = level, container = container)
            .border(0.8.dp, colors.outline.copy(alpha = 0.28f), shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(padding),
        content = content,
    )
}

/**
 * 圆形玻璃按钮：抓包页右上角的三点、日志页的导出都用这个。
 * 按下会轻微缩小（玻璃上叠 ripple 不好看，所以关掉 indication 只做缩放）。
 */
@Composable
fun CircleGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    active: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(stiffness = 900f),
        label = "glassButtonPress",
    )
    val base = GlassDefaults.container(GlassLevel.Pill)
    val container = if (active) base.copy(alpha = (base.alpha + 0.12f).coerceAtMost(1f)) else base
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .glassSurface(shape = CircleShape, level = GlassLevel.Pill, container = container)
            .border(0.8.dp, colors.outline.copy(alpha = 0.28f), CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}
