package com.phantasmic.mitt.go

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    onOpenUpstream: () -> Unit,
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
            SectionHeader("网络")
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
                    startAction = { SettingGlyph(SettingIcon.PROXY) },
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
                    startAction = { SettingGlyph(SettingIcon.SOCKS) },
                )
                ArrowPreference(
                    title = "上游代理",
                    summary = (if (settings.upstream.enabled) "开启" else "已关闭") +
                        " · " + settings.upstream.address,
                    startAction = { SettingGlyph(SettingIcon.UPSTREAM) },
                    onClick = onOpenUpstream,
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
            SectionHeader("代理过滤")
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
                    startAction = { SettingGlyph(SettingIcon.FILTER) },
                    onClick = { onOpen(SettingsSubPage.FILTER) },
                )
                ArrowPreference(
                    title = "域名过滤",
                    summary = when (settings.domainFilterMode) {
                        FilterMode.OFF -> "已关闭，抓取全部域名"
                        FilterMode.WHITELIST -> "白名单 ${settings.domainWhitelist.size} 个域名"
                        FilterMode.BLACKLIST -> "黑名单 ${settings.domainBlacklist.size} 个域名"
                    },
                    startAction = { SettingGlyph(SettingIcon.DOMAIN_FILTER) },
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
                    startAction = { SettingGlyph(SettingIcon.DEBUG) },
                )
            }
        }

        item(key = "pref_title") {
            SectionHeader("偏好")
        }
        item(key = "memory") {
            Card(Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = "内存上限",
                    summary = "记录总占用超过后丢弃最旧的 · 当前 ${settings.memoryLimitMb} MB",
                    startAction = { SettingGlyph(SettingIcon.MEMORY) },
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
                    startAction = { SettingGlyph(SettingIcon.THEME) },
                    onClick = { onOpen(SettingsSubPage.THEME) },
                )
                SwitchPreference(
                    checked = settings.autoStartOnLaunch,
                    onCheckedChange = { on ->
                        onSettingsChange(settings.copy(autoStartOnLaunch = on))
                    },
                    title = "自动开启抓包",
                    summary = "仅冷启动 App 时自动开始抓包",
                    startAction = { SettingGlyph(SettingIcon.AUTO_START) },
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
                    startAction = { SettingGlyph(SettingIcon.CERT) },
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
                    startAction = { SettingGlyph(SettingIcon.ABOUT) },
                    onClick = { onOpen(SettingsSubPage.ABOUT) },
                )
            }
        }
    }
}

/** 设置项左侧的圆形小图标：实心圆底 + 反白符号。 */
