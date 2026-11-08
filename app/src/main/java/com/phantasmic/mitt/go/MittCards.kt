package com.phantasmic.mitt.go

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 带内边距的卡片。Miuix 的 Card 默认不会给自绘内容留白，文字贴到圆角上会被裁掉，
 * 所以凡是直接塞 Text 的地方都用这个包装。
 */
@Composable
fun PaddedCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            content = content,
        )
    }
}

/** 关于页用的半透明描边卡片，观感对齐 KernelSU。 */
@Composable
fun OutlineCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                0.8.dp,
                colors.onSurface.copy(alpha = 0.10f),
                RoundedCornerShape(16.dp),
            ),
        colors = CardDefaults.defaultColors(colors.surfaceContainer.copy(alpha = 0.52f)),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** 分组标题：小而灰，跟卡片错开一点。设置页和关于页共用同一个。 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceSecondary,
        modifier = modifier.padding(start = 6.dp, top = 12.dp, bottom = 6.dp),
    )
}
