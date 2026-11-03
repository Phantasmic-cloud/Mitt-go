package com.phantasmic.mitt.go

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import kotlin.math.cos
import kotlin.math.sin
import top.yukonga.miuix.kmp.theme.MiuixTheme



enum class SettingsSubPage(val title: String) {
    CERTIFICATE("证书"),
    FILTER("代理过滤"),
    DOMAIN_FILTER("域名过滤"),
    THEME("主题"),
    ABOUT("关于"),
}

@Composable
fun SettingsPage(
    settings: MittSettings,
    onSettingsChange: (MittSettings) -> Unit,
    running: Boolean,
    topInset: Dp,
    bottomInset: Dp,
    onOpen: (SettingsSubPage) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var limitText by remember(settings.memoryLimitMb) {
        mutableStateOf(settings.memoryLimitMb.toString())
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = topInset + 6.dp,
            bottom = bottomInset + 110.dp,
        ),
    ) {
        item(key = "net_title") {
            SectionTitle("网络")
        }
        item(key = "engine") {
            Card(Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    items = ProxyMode.values().map { it.label },
                    selectedIndex = settings.proxyMode.ordinal,
                    title = "代理模式",
                    summary = when (settings.proxyMode) {
                        ProxyMode.VPN_TUN -> "建立 VPN + Tun，接管全部 App 的 IPv4 TCP"
                        ProxyMode.PROXY_ONLY -> "仅启用系统代理，需要自行 Hook 目标 App"
                    },
                    startAction = { SettingGlyph(0) },
                    onSelectedIndexChange = { index ->
                        val mode = ProxyMode.values().getOrElse(index) { ProxyMode.VPN_TUN }
                        onSettingsChange(settings.copy(proxyMode = mode))
                    },
                )
                SwitchPreference(
                    checked = settings.socksEnabled,
                    onCheckedChange = { on ->
                        onSettingsChange(settings.copy(socksEnabled = on))
                    },
                    title = "SOCKS5",
                    summary = if (running) {
                        "运行中修改会立即生效"
                    } else {
                        "在本地 ${settings.socksPort} 端口提供 SOCKS5"
                    },
                    startAction = { SettingGlyph(1) },
                )
            }
            if (running) {
                Text(
                    text = "运行中切换代理模式需要先停止再开始抓包。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }

        item(key = "filter_title") {
            SectionTitle("代理过滤")
        }
        item(key = "filter") {
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "应用过滤",
                    summary = when (settings.filterMode) {
                        FilterMode.OFF -> "已关闭，抓取全部 App"
                        FilterMode.WHITELIST -> "白名单 ${settings.whitelist.size} 个应用"
                        FilterMode.BLACKLIST -> "黑名单 ${settings.blacklist.size} 个应用"
                    },
                    startAction = { SettingGlyph(3) },
                    onClick = { onOpen(SettingsSubPage.FILTER) },
                )
                ArrowPreference(
                    title = "域名过滤",
                    summary = when (settings.domainFilterMode) {
                        FilterMode.OFF -> "已关闭，抓取全部域名"
                        FilterMode.WHITELIST -> "白名单 ${settings.domainWhitelist.size} 个域名"
                        FilterMode.BLACKLIST -> "黑名单 ${settings.domainBlacklist.size} 个域名"
                    },
                    startAction = { SettingGlyph(9) },
                    onClick = { onOpen(SettingsSubPage.DOMAIN_FILTER) },
                )
            }
        }

        item(key = "debug") {
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = settings.debugMode,
                    onCheckedChange = { on ->
                        onSettingsChange(settings.copy(debugMode = on))
                    },
                    title = "DEBUG 模式",
                    summary = "更详细的日志，用于排查非模块的问题",
                    startAction = { SettingGlyph(5) },
                )
            }
        }

        item(key = "pref_title") {
            SectionTitle("偏好")
        }
        item(key = "memory") {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = "内存上限",
                    summary = "记录总占用超过后丢弃最旧的 · 当前 ${settings.memoryLimitMb} MB",
                    startAction = { SettingGlyph(4) },
                    endActions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = limitText,
                                onValueChange = { input ->
                                    limitText = input.filter { it.isDigit() }.take(4)
                                },
                                modifier = Modifier.width(76.dp),
                                singleLine = true,
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                val value = limitText.toIntOrNull()?.coerceIn(16, 2048)
                                if (value != null) {
                                    onSettingsChange(settings.copy(memoryLimitMb = value))
                                }
                            }) {
                                Text("应用")
                            }
                        }
                    },
                )
                ArrowPreference(
                    title = "主题",
                    summary = buildString {
                        append(settings.themeMode.label)
                        if (settings.advancedEffects) append(" · 高级效果已开")
                    },
                    startAction = { SettingGlyph(7) },
                    onClick = { onOpen(SettingsSubPage.THEME) },
                )
                SwitchPreference(
                    checked = settings.autoStartOnLaunch,
                    onCheckedChange = { on ->
                        onSettingsChange(settings.copy(autoStartOnLaunch = on))
                    },
                    title = "自动开启抓包",
                    summary = "仅冷启动 App 时自动开始抓包",
                    startAction = { SettingGlyph(10) },
                )
            }
        }

        item(key = "cert") {
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "证书",
                    summary = when (settings.certMode) {
                        CertMode.INDIA -> "印度证书模式"
                        CertMode.BUILTIN -> "内置证书模式"
                    },
                    startAction = { SettingGlyph(6) },
                    onClick = { onOpen(SettingsSubPage.CERTIFICATE) },
                )
            }
        }

        item(key = "about") {
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(
                    title = "关于",
                    summary = "Mitt-go ${BuildConfig.VERSION_NAME}",
                    startAction = { SettingGlyph(8) },
                    onClick = { onOpen(SettingsSubPage.ABOUT) },
                )
            }
        }
    }
}

/** 分组标题：小而灰，跟卡片错开一点。 */
@Composable
private fun SectionTitle(text: String) {
    val colors = MiuixTheme.colorScheme
    Text(
        text = text,
        fontSize = 12.sp,
        color = colors.onSurfaceSecondary,
        modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 6.dp),
    )
}

/** 设置项左侧的圆形小图标：实心圆底 + 反白符号。 */
@Composable
private fun SettingGlyph(kind: Int) {
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
            when (kind) {
                // 代理模式：闪电
                0 -> {
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
                1 -> {
                    drawLine(fg, Offset(w * 0.08f, h * 0.32f), Offset(w * 0.88f, h * 0.32f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.66f, h * 0.10f), Offset(w * 0.90f, h * 0.32f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.08f, h * 0.70f), Offset(w * 0.88f, h * 0.70f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.30f, h * 0.48f), Offset(w * 0.06f, h * 0.70f), stroke, cap = StrokeCap.Round)
                }
                // 本地端口：插头
                2 -> {
                    drawRoundRect(
                        color = fg,
                        topLeft = Offset(w * 0.10f, h * 0.34f),
                        size = Size(w * 0.80f, h * 0.50f),
                        cornerRadius = CornerRadius(1.5.dp.toPx()),
                    )
                    drawLine(fg, Offset(w * 0.34f, h * 0.06f), Offset(w * 0.34f, h * 0.30f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.66f, h * 0.06f), Offset(w * 0.66f, h * 0.30f), stroke, cap = StrokeCap.Round)
                }
                // 代理过滤：漏斗
                3 -> {
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
                4 -> {
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
                5 -> {
                    drawLine(fg, Offset(w * 0.34f, h * 0.18f), Offset(w * 0.10f, h * 0.50f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.10f, h * 0.50f), Offset(w * 0.34f, h * 0.82f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.66f, h * 0.18f), Offset(w * 0.90f, h * 0.50f), stroke, cap = StrokeCap.Round)
                    drawLine(fg, Offset(w * 0.90f, h * 0.50f), Offset(w * 0.66f, h * 0.82f), stroke, cap = StrokeCap.Round)
                }
                // 证书：盾牌
                6 -> {
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
                7 -> {
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
                9 -> {
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
                // 自动开启抓包：闪电箭头（播放三角 + 竖线）
                10 -> {
                    val tri = Path().apply {
                        moveTo(w * 0.24f, h * 0.16f)
                        lineTo(w * 0.86f, h * 0.50f)
                        lineTo(w * 0.24f, h * 0.84f)
                        close()
                    }
                    drawPath(tri, fg)
                }
                // 关于：i
                else -> {
                    drawCircle(fg, radius = w * 0.10f, center = Offset(w * 0.50f, h * 0.22f))
                    drawLine(fg, Offset(w * 0.50f, h * 0.42f), Offset(w * 0.50f, h * 0.82f), 2.dp.toPx(), cap = StrokeCap.Round)
                }
            }
        }
    }
}
