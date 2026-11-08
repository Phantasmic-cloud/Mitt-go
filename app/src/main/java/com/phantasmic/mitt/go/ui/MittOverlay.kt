package com.phantasmic.mitt.go.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全 App 唯一的浮层入口。
 *
 * 以前关键词高亮小窗、YAML 编辑器、保存菜单各写了一套遮罩 + 进出场 + 返回处理，
 * 结果两处漏了返回键（按返回直接退页面/退 App）、一处玻璃取景取错页。
 * 现在统一走 [MittModal]（居中弹窗）和 [GlassPopover]（贴着按钮的菜单）。
 *
 * 两者都自带 `BackHandler`：回调在浮层内容组合时才注册，天然晚于根页面/子页的
 * 返回处理，所以返回键一定先关浮层，不会直接退掉页面。
 */

/** 浮层的动效参数。改手感只改这里。 */
object MittMotion {

    /** 弹窗背后压暗的强度。 */
    val scrim = Color.Black.copy(alpha = 0.20f)

    val dialogEnter: EnterTransition =
        fadeIn(tween(140)) +
            scaleIn(
                initialScale = 0.94f,
                animationSpec = tween(180, easing = LinearOutSlowInEasing),
            )

    val dialogExit: ExitTransition =
        fadeOut(tween(120)) +
            scaleOut(
                targetScale = 0.96f,
                animationSpec = tween(140, easing = FastOutLinearInEasing),
            )

    fun popoverEnter(origin: TransformOrigin): EnterTransition =
        fadeIn(tween(120)) +
            scaleIn(
                initialScale = 0.90f,
                animationSpec = tween(150, easing = LinearOutSlowInEasing),
                transformOrigin = origin,
            )

    fun popoverExit(origin: TransformOrigin): ExitTransition =
        fadeOut(tween(100)) +
            scaleOut(
                targetScale = 0.95f,
                animationSpec = tween(120, easing = FastOutLinearInEasing),
                transformOrigin = origin,
            )
}

/**
 * 居中弹窗：遮罩 + 进出场 + 返回键关闭。
 *
 * 内容自己套 [GlassCard]。注意内容要画在页面的 `pageBackdrop` 记录节点之外，
 * 否则玻璃会成环闪退。
 *
 * @param dim 是否压暗背景。菜单类的浮层用 [GlassPopover]，不要用这个。
 * @param imePadding 键盘弹出时整体上移（编辑器用）。
 */
@Composable
fun MittModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dim: Boolean = true,
    imePadding: Boolean = false,
    dismissOnOutside: Boolean = true,
    alignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = visible) { onDismiss() }
    AnimatedVisibility(
        visible = visible,
        enter = MittMotion.dialogEnter,
        exit = MittMotion.dialogExit,
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .then(if (imePadding) Modifier.imePadding() else Modifier),
        ) {
            // 遮罩在卡片下面一层：点空白关闭，点卡片由 GlassCard 自己吞掉。
            if (dim) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(MittMotion.scrim)
                        .then(dismissModifier(dismissOnOutside, onDismiss)),
                )
            } else {
                Box(Modifier.matchParentSize().then(dismissModifier(dismissOnOutside, onDismiss)))
            }
            Box(Modifier.fillMaxSize(), contentAlignment = alignment) {
                content()
            }
        }
    }
}

/**
 * 贴着按钮弹出的玻璃菜单：透明遮罩（不压暗）+ 从锚点缩放 + 返回键关闭。
 *
 * 必须直接放在一个 `Box` 里：它会同时吐出一个全屏遮罩和一个菜单，
 * 菜单的位置由 [modifier] 里的 `align` / `padding` 决定。
 *
 * @param origin 缩放原点。右上角弹出的菜单传 [TransformOrigin]`(1f, 0f)`。
 */
@Composable
fun GlassPopover(
    visible: Boolean,
    onDismiss: () -> Unit,
    origin: TransformOrigin,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BackHandler(enabled = visible) { onDismiss() }
    if (visible) {
        Box(Modifier.fillMaxSize().then(dismissModifier(true, onDismiss)))
    }
    AnimatedVisibility(
        visible = visible,
        enter = MittMotion.popoverEnter(origin),
        exit = MittMotion.popoverExit(origin),
        modifier = modifier,
        content = { content() },
    )
}

@Composable
private fun dismissModifier(enabled: Boolean, onDismiss: () -> Unit): Modifier =
    if (enabled) {
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismiss,
        )
    } else {
        Modifier
    }

/**
 * 玻璃菜单里的一行。抓包页三点菜单和详情页保存菜单共用。
 *
 * @param leading 左侧图标（没有就不留空）。
 * @param checked 选中状态：文字用主题色并加粗，右侧点一个小圆点。
 */
@Composable
fun GlassMenuItem(
    label: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    checked: Boolean = false,
    fontSize: TextUnit = 14.sp,
    contentColor: Color = MiuixTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) colors.onSurface.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = label,
            fontSize = fontSize,
            fontWeight = if (checked) FontWeight.Medium else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (checked) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(6.dp)
                    .background(colors.primary, CircleShape),
            )
        }
    }
}
