package com.phantasmic.mitt.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ModulesPage(
    topInset: Dp,
    bottomInset: Dp,
    asyncEnabled: Boolean,
    asyncCount: Int,
    onOpenAsync: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    LaunchedEffect(Unit) { MittLog.debug("Module") { "打开模块页" } }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp)
            .padding(top = topInset, bottom = bottomInset),
    ) {
        Card(Modifier.fillMaxWidth()) {
            ArrowPreference(
                title = "旁路上传",
                summary = if (asyncEnabled) {
                    "已开启 · ${asyncCount} 份配置"
                } else {
                    "已关闭 · 命中规则的请求/响应体异步 POST 到你的服务器"
                },
                startAction = { ModuleGlyph() },
                onClick = onOpenAsync,
            )
        }
    }
}

/** 模块列表用的拼图图标。 */
@Composable
private fun ModuleGlyph() {
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