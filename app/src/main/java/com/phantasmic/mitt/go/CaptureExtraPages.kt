package com.phantasmic.mitt.go

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phantasmic.mitt.go.ui.GlassCard
import com.phantasmic.mitt.go.ui.GlassLevel
import com.phantasmic.mitt.go.ui.MittModal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 某个域名下的请求列表（抓包页的二级页）。 */
@Composable
fun DomainPage(
    host: String,
    topInset: Dp,
    bottomInset: Dp,
    onSelect: (CaptureSummary) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val records by CaptureStore.recordFlow.collectAsState()
    val items = remember(records, host) {
        records.filter { it.host == host }.sortedByDescending { it.startedAt }
    }

    if (items.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = topInset),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "这个域名下没有记录了", fontSize = 12.sp, color = colors.onSurfaceSecondary)
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = topInset + 4.dp, bottom = bottomInset + 24.dp),
    ) {
        items(items = items, key = { it.id }) { item ->
            val onClick = remember(item.id) { { onSelect(item) } }
            DomainRequestRow(item = item, onClick = onClick)
        }
    }
}

@Composable
private fun DomainRequestRow(item: CaptureSummary, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = pathOf(item.url),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.method.ifBlank { "?" }.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = methodColor(item.method),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (item.error.isNotBlank()) "失败" else statusText(item.statusCode),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = statusColor(item.statusCode),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (item.durationMs > 0) "${item.durationMs}ms" else "—",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceSecondary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = typeLabel(item.contentType),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceSecondary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatClock(item.startedAt),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceSecondary,
        )
    }
}

/** 历史记录：自动保存天数 + 已归档的会话列表。 */
@Composable
fun HistoryPage(
    settings: MittSettings,
    onSettingsChange: (MittSettings) -> Unit,
    topInset: Dp,
    bottomInset: Dp,
    onSelectRecord: (CaptureSummary) -> Unit,
    // null = 会话列表；非 null = 这一层就是某个历史会话的记录。
    // 用参数而不是全局状态，栈里同时存在两层历史时才不会互相串内容。
    session: CaptureSession? = null,
    onOpenSession: (CaptureSession) -> Unit = {},
) {
    val colors = MiuixTheme.colorScheme
    val sessions by CaptureStore.sessionFlow.collectAsState()
    var openedRecords by remember(session?.id) { mutableStateOf<List<CaptureSummary>>(emptyList()) }

    if (session != null) {
        LaunchedEffect(session.id) {
            openedRecords = withContext(Dispatchers.IO) {
                CaptureStore.openSession(session)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topInset + 4.dp, bottom = bottomInset + 24.dp),
        ) {
            if (openedRecords.isEmpty()) {
                item(key = "emptySession") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "这个会话没有记录",
                            fontSize = 12.sp,
                            color = colors.onSurfaceSecondary,
                        )
                    }
                }
            } else {
                items(items = openedRecords, key = { it.id }) { item ->
                    val accent = remember(item.url, settings.highlightEnabled, settings.highlightRules) {
                        highlightColorFor(item.url, settings.highlightRules, settings.highlightEnabled)
                    }
                    UrlRow(item = item, accent = accent, onClick = { onSelectRecord(item) })
                }
            }
        }
        return
    }
    val options = listOf("关闭", "1 天内", "3 天内", "7 天内", "30 天内")
    val daysToIndex = mapOf(0 to 0, 1 to 1, 3 to 2, 7 to 3, 30 to 4)
    val indexToDays = mapOf(0 to 0, 1 to 1, 2 to 3, 3 to 7, 4 to 30)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = topInset + 6.dp,
            bottom = bottomInset + 24.dp,
        ),
    ) {
        item(key = "auto") {
            Card(Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    items = options,
                    selectedIndex = daysToIndex[settings.autoSaveDays] ?: 0,
                    title = "自动保存",
                    summary = if (settings.autoSaveDays == 0) {
                        "关闭：停止抓包后不归档，记录只保留在当前会话"
                    } else {
                        "保留 ${settings.autoSaveDays} 天，到期自动清理"
                    },
                    onSelectedIndexChange = { index ->
                        val days = indexToDays[index] ?: 0
                        onSettingsChange(settings.copy(autoSaveDays = days))
                    },
                )
            }
        }

        if (sessions.isEmpty()) {
            item(key = "empty") {
                Spacer(Modifier.height(40.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "还没有历史记录\n开启自动保存后，停止抓包时会归档一次会话",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.onSurfaceSecondary,
                    )
                }
            }
        } else {
            items(items = sessions, key = { it.id }) { session ->
                Spacer(Modifier.height(10.dp))
                SessionRow(
                    title = formatSessionRange(session.startedAt, session.endedAt),
                    count = session.count,
                    bytes = session.bytes,
                    onClick = { onOpenSession(session) },
                    onDelete = { CaptureStore.deleteSession(session) },
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    title: String,
    count: Int,
    bytes: Long,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${count}条",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceSecondary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = formatSize(bytes.toInt().coerceAtLeast(0)),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.onSurfaceSecondary,
        )
        Spacer(Modifier.width(10.dp))
        TrashGlyph(color = Color(0xFFD64545), onClick = onDelete)
    }
}

/**
 * 关键词高亮小窗：总开关 + 颜色/正则规则。
 *
 * 浮层（遮罩、进出场、返回键关闭）统一交给 [MittModal]，玻璃统一走 [GlassCard]。
 */
@Composable
fun HighlightDialog(
    visible: Boolean,
    settings: MittSettings,
    onSettingsChange: (MittSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var pattern by remember { mutableStateOf("") }
    var colorIndex by remember { mutableIntStateOf(0) }

    MittModal(visible = visible, onDismiss = onDismiss) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp),
            shape = RoundedCornerShape(22.dp),
            level = GlassLevel.Dialog,
        ) {
            Text(
                text = "关键词高亮",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "总开关",
                    fontSize = 14.sp,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.highlightEnabled,
                    onCheckedChange = { enabled ->
                        onSettingsChange(settings.copy(highlightEnabled = enabled))
                    },
                    colors = SwitchDefaults.switchColors(checkedTrackColor = colors.primary),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "用正则匹配 URL，命中后整条 URL 用对应颜色显示",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceSecondary,
            )

            if (settings.highlightRules.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                settings.highlightRules.forEach { rule ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(Color(rule.colorArgb), CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = rule.pattern,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "删除",
                            fontSize = 11.sp,
                            color = Color(0xFFD64545),
                            modifier = Modifier
                                .clickable {
                                    onSettingsChange(
                                        settings.copy(
                                            highlightRules = settings.highlightRules - rule,
                                        ),
                                    )
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                highlightPalette.forEachIndexed { index, argb ->
                    Box(
                        Modifier
                            .padding(end = 8.dp)
                            .size(18.dp)
                            .background(Color(argb), CircleShape)
                            .border(
                                width = if (index == colorIndex) 2.dp else 1.dp,
                                color = if (index == colorIndex) colors.onSurface else Color.Transparent,
                                shape = CircleShape,
                            )
                            .clickable { colorIndex = index },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            TextField(
                value = pattern,
                onValueChange = { pattern = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton("取消", onClick = onDismiss)
                Button(
                    onClick = {
                        val text = pattern.trim()
                        if (text.isEmpty()) return@Button
                        val regex = runCatching { Regex(text) }.getOrNull() ?: return@Button
                        val rules = settings.highlightRules + HighlightRule(
                            colorArgb = highlightPalette[colorIndex],
                            pattern = regex.pattern,
                        )
                        onSettingsChange(settings.copy(highlightRules = rules))
                        pattern = ""
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("添加")
                }
            }
        }
    }
}

/** 抓包页菜单的小图标。 */
