package com.phantasmic.mitt.go

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phantasmic.mitt.go.ui.effect.BgEffectBackground
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme















// ==================== 证书与安装 ====================

private const val CA_HASH = "7dd0b8dd.0"

@Composable
fun CertificatePage(
    settings: MittSettings,
    onSettingsChange: (MittSettings) -> Unit,
    running: Boolean,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val fingerprint = remember { caFingerprint(context) }
    val builtinInstalled = remember { CertStatus.builtinInstalled() }
    val indiaAvailable = remember { CertStatus.indiaAvailable() }
    val india = settings.certMode == CertMode.INDIA
    // 按当前设备的 Android 版本决定推荐哪一种安装方式。
    val recommendScript = Build.VERSION.SDK_INT <= 33
    val recommendGreen = ButtonDefaults.buttonColors(
        color = Color(0xFF2FA45A),
        contentColor = Color.White,
    )
    val usingOk = if (india) indiaAvailable else builtinInstalled

    fun runExport(action: (Context) -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { action(context) } }
            busy = false
            Toast.makeText(
                context,
                result.fold(
                    onSuccess = { "已导出：$it" },
                    onFailure = { "导出失败：${it.message}" },
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = topInset + 6.dp,
            bottom = bottomInset + 28.dp,
        ),
    ) {
        item(key = "status") {
            CertStatusCard(
                ok = usingOk,
                title = when {
                    india && indiaAvailable -> "可用 [印度证书模式]"
                    india -> "不可用 [印度证书模式]"
                    builtinInstalled -> "已安装 [内置证书模式]"
                    else -> "未安装 [内置证书模式]"
                },
                detail = if (india) "emSign ECC Root CA - G3" else "7dd0b8dd.0",
                other = if (india) {
                    if (builtinInstalled) "内置证书 · 已安装" else "内置证书 · 未安装"
                } else {
                    if (indiaAvailable) "印度证书 · 可用" else "印度证书 · 不可用"
                },
            )
        }

        item(key = "mode") {
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    items = CertMode.values().map { it.label },
                    selectedIndex = settings.certMode.ordinal,
                    title = "证书模式",
                    summary = if (india) {
                        "用系统内置的 eMudhra 证书签发，免 root"
                    } else {
                        "用固定 Mitt CA 签发，需要装进系统证书库"
                    },
                    onSelectedIndexChange = { index ->
                        onSettingsChange(
                            settings.copy(
                                certMode = CertMode.values().getOrElse(index) { CertMode.BUILTIN },
                            ),
                        )
                    },
                )
            }
            if (running) {
                Text(
                    text = "切换证书模式后需要停止再开始抓包。",
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
        }

        if (india) {
            item(key = "india_tips") {
                Spacer(Modifier.height(12.dp))
                PaddedCard {
                    NoteHeading("温馨提醒")
                    Spacer(Modifier.height(6.dp))
                    NoteItem("该模式仅适于没 root 的设备使用")
                    NoteItem("启用该证书会导致当前设备不安全，请在抓包结束后及时关闭该证书", warn = true)
                    NoteItem("root 用户请直接使用内置证书模式")
                }
            }

            item(key = "india_ca") {
                Spacer(Modifier.height(12.dp))
                PaddedCard {
                    NoteHeading("证书")
                    Spacer(Modifier.height(6.dp))
                    NoteItem("emSign ECC Root CA - G3")
                }
            }

            item(key = "india_faq") {
                Spacer(Modifier.height(12.dp))
                PaddedCard {
                    NoteHeading("常见问题")
                    Spacer(Modifier.height(6.dp))
                    NoteItem(
                        "证书显示可用但全部 HTTPS SSL 握手失败",
                        "系统内未启用该证书，需自行启用该证书",
                    )
                    NoteItem(
                        "证书显示可用但部分 HTTPS SSL 握手失败",
                        "部分 App 已将该证书拉黑，无解",
                    )
                    NoteItem(
                        "证书显示不可用",
                        "该设备已将该证书去除，不适用该模式",
                    )
                }
            }
        } else {
            item(key = "android13") {
                Spacer(Modifier.height(12.dp))
                PaddedCard {
                    Text(
                        text = "安卓 13 及其以下",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "推荐执行 .sh 一键写入（赋予 su 执行）",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.onSurfaceSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { runExport { CertificateExporter.exportInstallScript(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (recommendScript) recommendGreen else ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            when {
                                busy -> "导出中…"
                                recommendScript -> "导出脚本（该设备推荐）"
                                else -> "导出脚本"
                            },
                        )
                    }
                }
            }

            item(key = "android14") {
                Spacer(Modifier.height(12.dp))
                PaddedCard {
                    Text(
                        text = "安卓 14 及其以上",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "如果你使用的是 KernelSU 系的 root 管理器，推荐使用脚本挂载",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.onSurfaceSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { runExport { CertificateExporter.exportKernelSuModule(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (recommendScript) {
                            ButtonDefaults.buttonColorsPrimary()
                        } else {
                            recommendGreen
                        },
                    ) {
                        Text(
                            when {
                                busy -> "导出中…"
                                recommendScript -> "导出 KernelSU 模块"
                                else -> "导出 KernelSU 模块（该设备推荐）"
                            },
                        )
                    }
                }
            }

            item(key = "manual") {
                Spacer(Modifier.height(12.dp))
                PaddedCard {
                    Text(
                        text = "root 管理器不属于 KernelSU 系、不想使用脚本",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "可导出证书后自行挂载",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.onSurfaceSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { runExport { CertificateExporter.exportSystemCert(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(if (busy) "导出中…" else "导出系统根证书 7dd0b8dd.0")
                    }
                }
            }

            item(key = "paths") {
                Spacer(Modifier.height(12.dp))
                PaddedCard {
                    Text(
                        text = "Android 13 及其以下：将证书挂载到 /system/etc/security/cacerts 目录",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.onSurfaceSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Android 14 及其以上：将证书挂载到 /apex/com.android.conscrypt/cacerts 目录",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.onSurfaceSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "挂载证书后均需要重启手机生效",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.onSurfaceSecondary,
                    )
                }
            }

            item(key = "info") {
                Spacer(Modifier.height(12.dp))
                PaddedCard {
                    InfoLine("文件名", "7dd0b8dd.0")
                    InfoLine("Subject", "CN=Mitt CA, O=Phantasmic, OU=Mitt")
                    InfoLine("SHA-256", fingerprint)
                }
            }
        }
    }
}

@Composable
private fun NoteHeading(text: String) {
    Text(
        text = text,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface,
    )
}

/** 一条带圆点的说明；sub 不为空时，在下一行缩进给出解释。 */
@Composable
private fun NoteItem(text: String, sub: String? = null, warn: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    val main = if (warn) Color(0xFFD64545) else colors.onSurface
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = "·",
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = if (warn) Color(0xFFD64545) else colors.onSurfaceSecondary,
            modifier = Modifier.padding(end = 6.dp),
        )
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = text,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = main,
            )
            if (sub != null) {
                Text(
                    text = sub,
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = colors.onSurfaceSecondary,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }
        }
    }
}

/** 顶部状态卡：大标题 + 证书名 + 另一模式状态，右下角一个大号 3/4 圆环对勾。 */
@Composable
private fun CertStatusCard(ok: Boolean, title: String, detail: String, other: String) {
    val accent = if (ok) Color(0xFF2FA45A) else Color(0xFF9A9A9A)
    val background = if (ok) Color(0xFFE3F6E7) else Color(0xFFF1F1F2)
    Box(
        Modifier
            .fillMaxWidth()
            .height(152.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(background),
    ) {
        // 右下角的大图标，稍微溢出卡片边缘，由卡片裁剪
        Canvas(
            Modifier
                .align(Alignment.BottomEnd)
                .size(138.dp)
                .offset(x = 30.dp, y = 34.dp),
        ) {
            // 画完整的圆环，右下部分溢出卡片由 clip 裁掉，于是看起来是开口朝右下的 3/4 圆。
            val stroke = 13.dp.toPx()
            val inset = stroke / 2f
            drawArc(
                color = accent,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - inset * 2, size.height - inset * 2),
                style = Stroke(stroke),
            )
            if (ok) {
                drawLine(
                    accent,
                    Offset(size.width * 0.26f, size.height * 0.52f),
                    Offset(size.width * 0.43f, size.height * 0.69f),
                    stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    accent,
                    Offset(size.width * 0.43f, size.height * 0.69f),
                    Offset(size.width * 0.76f, size.height * 0.30f),
                    stroke,
                    cap = StrokeCap.Round,
                )
            } else {
                drawLine(
                    accent,
                    Offset(size.width * 0.32f, size.height * 0.32f),
                    Offset(size.width * 0.68f, size.height * 0.68f),
                    stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    accent,
                    Offset(size.width * 0.68f, size.height * 0.32f),
                    Offset(size.width * 0.32f, size.height * 0.68f),
                    stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 20.dp, top = 18.dp, end = 108.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = title,
                    fontSize = 25.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1B1B1B),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = detail,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF5A5A5A),
                )
            }
            Text(
                text = other,
                fontSize = 13.sp,
                color = Color(0xFF5A5A5A),
            )
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = colors.onSurfaceSecondary,
            modifier = Modifier.width(76.dp),
        )
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                text = value,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurface,
            )
        }
    }
}

@Composable
private fun StepText(index: String, text: String) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(17.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(colors.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun caFingerprint(context: Context): String = runCatching {
    val pem = context.assets.open("ca/ca.crt")
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
    val cert = CertificateFactory.getInstance("X.509")
        .generateCertificate(pem.byteInputStream()) as X509Certificate
    MessageDigest.getInstance("SHA-256")
        .digest(cert.encoded)
        .joinToString(":") { "%02X".format(it) }
}.getOrDefault("-")

// ==================== 代理过滤 ====================

@Composable
fun ProxyFilterPage(
    settings: MittSettings,
    onSettingsChange: (MittSettings) -> Unit,
    running: Boolean,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var includeSystem by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(includeSystem) {
        loading = true
        apps = runCatching { InstalledAppsLoader.load(context, includeSystem) }.getOrDefault(emptyList())
        loading = false
    }

    val mode = settings.filterMode
    val selected = when (mode) {
        FilterMode.WHITELIST -> settings.whitelist
        FilterMode.BLACKLIST -> settings.blacklist
        FilterMode.OFF -> emptySet()
    }
    val visible = remember(apps, query, selected) {
        val keyword = query.trim().lowercase()
        val matched = if (keyword.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.lowercase().contains(keyword) || it.packageName.lowercase().contains(keyword)
            }
        }
        // 已勾选的排在前面，刷新列表或切换白/黑名单后都保持
        matched.sortedByDescending { it.packageName in selected }
    }

    fun toggle(packageName: String) {
        if (mode == FilterMode.OFF) return
        val next = if (packageName in selected) selected - packageName else selected + packageName
        onSettingsChange(
            when (mode) {
                FilterMode.WHITELIST -> settings.copy(whitelist = next)
                FilterMode.BLACKLIST -> settings.copy(blacklist = next)
                FilterMode.OFF -> settings
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = topInset + 6.dp,
            bottom = bottomInset + 28.dp,
        ),
    ) {
        item(key = "mode") {
            Card(Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    items = FilterMode.values().map { it.label },
                    selectedIndex = mode.ordinal,
                    title = "过滤模式",
                    summary = when (mode) {
                        FilterMode.OFF -> "抓取全部 App"
                        FilterMode.WHITELIST -> "只抓白名单里的 App"
                        FilterMode.BLACKLIST -> "跳过黑名单里的 App"
                    },
                    onSelectedIndexChange = { index ->
                        val next = FilterMode.values().getOrElse(index) { FilterMode.OFF }
                        onSettingsChange(settings.copy(filterMode = next))
                    },
                )
                SwitchPreference(
                    checked = includeSystem,
                    onCheckedChange = { includeSystem = it },
                    title = "显示系统应用",
                    summary = "列表默认只显示用户安装的应用",
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (running) {
                    "过滤在建立 TUN 时生效，修改后请停止再开始抓包。"
                } else {
                    "白名单非空时优先使用白名单，否则按黑名单排除。"
                },
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }

        if (mode == FilterMode.OFF) {
            item(key = "hint") {
                Spacer(Modifier.height(10.dp))
                PaddedCard {
                    Text(
                        text = "当前没有启用过滤。把上方过滤模式改成白名单或黑名单后，才会按应用分流。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.onSurfaceSecondary,
                    )
                }
            }
        } else {
            item(key = "search") {
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "已选 ${selected.size} 个 · ${visible.size} 个应用",
                    fontSize = 11.sp,
                    color = colors.onSurfaceSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (loading) {
                item(key = "loading") {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "正在读取应用列表…",
                            fontSize = 12.sp,
                            color = colors.onSurfaceSecondary,
                        )
                    }
                }
            } else {
                items(items = visible, key = { it.packageName }) { app ->
                    AppFilterRow(
                        app = app,
                        checked = app.packageName in selected,
                        onToggle = { toggle(app.packageName) },
                    )
                }
            }
        }
    }
}

/** 域名过滤：白/黑名单分开存，支持 example.com 与 *.example.com。 */
@Composable
fun DomainFilterPage(
    settings: MittSettings,
    onSettingsChange: (MittSettings) -> Unit,
    running: Boolean,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    var input by remember { mutableStateOf("") }
    val mode = settings.domainFilterMode
    val rules = when (mode) {
        FilterMode.WHITELIST -> settings.domainWhitelist
        FilterMode.BLACKLIST -> settings.domainBlacklist
        FilterMode.OFF -> emptySet()
    }

    fun update(next: Set<String>) {
        onSettingsChange(
            when (mode) {
                FilterMode.WHITELIST -> settings.copy(domainWhitelist = next)
                FilterMode.BLACKLIST -> settings.copy(domainBlacklist = next)
                FilterMode.OFF -> settings
            },
        )
    }

    fun addRule() {
        if (mode == FilterMode.OFF) return
        val value = input.trim()
            .lowercase()
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
        if (value.isEmpty()) return
        update(rules + value)
        input = ""
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = topInset + 6.dp,
            bottom = bottomInset + 28.dp,
        ),
    ) {
        item(key = "mode") {
            Card(Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    items = FilterMode.values().map { it.label },
                    selectedIndex = mode.ordinal,
                    title = "域名过滤",
                    summary = when (mode) {
                        FilterMode.OFF -> "关闭，所有域名都照常抓"
                        FilterMode.WHITELIST -> "只抓清单里的域名"
                        FilterMode.BLACKLIST -> "跳过清单里的域名"
                    },
                    onSelectedIndexChange = { index ->
                        val next = FilterMode.values().getOrElse(index) { FilterMode.OFF }
                        onSettingsChange(settings.copy(domainFilterMode = next))
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (running) {
                    "域名过滤不打断连接：命中的请求直接透传，不再解密和记录。修改即时生效。"
                } else {
                    "和应用过滤互相独立，可以同时启用。"
                },
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceSecondary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }

        if (mode == FilterMode.OFF) {
            item(key = "hint") {
                Spacer(Modifier.height(10.dp))
                PaddedCard {
                    Text(
                        text = "当前没有启用域名过滤。把上方模式改成白名单或黑名单后，才能添加域名规则。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.onSurfaceSecondary,
                    )
                }
            }
        } else {
            item(key = "input") {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { addRule() }) {
                        Text("添加")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "支持 *.example.com 匹配子域名 · 已添加 ${rules.size} 条",
                    fontSize = 11.sp,
                    color = colors.onSurfaceSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            if (rules.isEmpty()) {
                item(key = "empty") {
                    Spacer(Modifier.height(10.dp))
                    PaddedCard {
                        Text(
                            text = "还没有规则。在上面的输入框里填写域名后点“添加”。",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = colors.onSurfaceSecondary,
                        )
                    }
                }
            } else {
                items(items = rules.sorted(), key = { it }) { rule ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = rule,
                            fontSize = 13.sp,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "删除",
                            fontSize = 12.sp,
                            color = colors.primary,
                            modifier = Modifier
                                .clickable { update(rules - rule) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppFilterRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIconBadge(packageName = app.packageName, size = 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                fontSize = 13.sp,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = colors.primary),
        )
    }
}

// ==================== 主题与效果 ====================

@Composable
fun ThemePage(
    settings: MittSettings,
    onSettingsChange: (MittSettings) -> Unit,
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = topInset + 6.dp,
            bottom = bottomInset + 28.dp,
        ),
    ) {
        item(key = "theme") {
            Card(Modifier.fillMaxWidth()) {
                OverlayDropdownPreference(
                    items = ThemeMode.values().map { it.label },
                    selectedIndex = settings.themeMode.ordinal,
                    title = "主题模式",
                    summary = "默认跟随系统深浅色",
                    onSelectedIndexChange = { index ->
                        val next = ThemeMode.values().getOrElse(index) { ThemeMode.SYSTEM }
                        onSettingsChange(settings.copy(themeMode = next))
                    },
                )
                SwitchPreference(
                    checked = settings.advancedEffects,
                    onCheckedChange = { on ->
                        onSettingsChange(settings.copy(advancedEffects = on))
                    },
                    title = "高级效果",
                    summary = "启用液态玻璃，略微增加耗电",
                )
            }
        }

        item(key = "note") {
            Spacer(Modifier.height(10.dp))
            PaddedCard {
                Text(
                    text = "说明",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "高级效果使用 Miuix 的实时模糊渲染。若在某些机型上出现掉帧或渲染异常，关掉它即可回到半透明效果",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.onSurfaceSecondary,
                )
            }
        }
    }
}

// ==================== 关于 ====================

@Composable
fun AboutPage(
    topInset: Dp,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val ts = MiuixTheme.textStyles
    val pageSizeKb = runCatching {
        android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) / 1024
    }.getOrDefault(4)

    BgEffectBackground(dynamicBackground = true, modifier = modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = topInset + 12.dp,
            bottom = bottomInset + 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "logo") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 26.dp, bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_splash),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp),
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Mitt-go",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onBackground,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "v${BuildConfig.VERSION_NAME}  (${BuildConfig.VERSION_CODE})",
                    fontSize = 13.sp,
                    color = colors.onSurfaceSecondary,
                )
            }
        }

        item(key = "github") {
            OutlineCard {
                ArrowPreference(
                    title = "在 GitHub 查看源码",
                    summary = "Phantasmic-cloud/Mitt-go",
                    onClick = { openUrl(context, "https://github.com/Phantasmic-cloud/Mitt-go") },
                )
            }
        }

        item(key = "disclaimer") {
            var expanded by remember { mutableStateOf(false) }
            val chevron by animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                animationSpec = tween(durationMillis = 180),
                label = "disclaimerChevron",
            )
            OutlineCard {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded },
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "免责声明",
                            style = MiuixTheme.textStyles.body1,
                            color = colors.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "›",
                            fontSize = 20.sp,
                            lineHeight = 20.sp,
                            color = colors.onSurfaceSecondary,
                            modifier = Modifier.rotate(chevron),
                        )
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(animationSpec = tween(200)) + fadeIn(tween(160)),
                        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(120)),
                    ) {
                        Text(
                            text = "本软件为开源项目，仅供调试与学习使用，不进行任何形式的收费行为；" +
                                "也无后端服务器，不会收集、储存或者上传任何用户信息；" +
                                "所有数据仅在本地处理，除非你启用了流量转发等类似的远程功能；" +
                                "你的操作行为导致的后果，开发者不负任何责任。",
                            fontSize = 12.sp,
                            lineHeight = 19.sp,
                            color = colors.onSurfaceSecondary,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                        )
                    }
                }
            }
        }

        item(key = "info") {
            Text(
                text = "信息",
                style = ts.footnote1,
                color = colors.onSurfaceSecondary,
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
            OutlineCard {
                BasicComponent(
                    title = "版本",
                    onClick = {},
                    endActions = {
                        Text(
                            text = "Android ${android.os.Build.VERSION.RELEASE} " +
                                "(API ${android.os.Build.VERSION.SDK_INT})",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceSecondary,
                        )
                    },
                )
                BasicComponent(
                    title = "架构",
                    onClick = {},
                    endActions = {
                        Text(
                            text = "arm64-v8a (${pageSizeKb}K)",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceSecondary,
                        )
                    },
                )
                BasicComponent(
                    title = "内核",
                    onClick = {},
                    endActions = {
                        Text(
                            text = "GO、CGO",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceSecondary,
                        )
                    },
                )
            }
        }

        item(key = "refs") {
            Text(
                text = "参考项目",
                style = ts.footnote1,
                color = colors.onSurfaceSecondary,
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
            OutlineCard {
                ArrowPreference(
                    title = "Miuix",
                    summary = "compose-miuix-ui/miuix · UI 组件",
                    onClick = { openUrl(context, "https://github.com/compose-miuix-ui/miuix") },
                )
                ArrowPreference(
                    title = "go-mitmproxy",
                    summary = "lqqyt2423/go-mitmproxy · MITM 引擎",
                    onClick = { openUrl(context, "https://github.com/lqqyt2423/go-mitmproxy") },
                )
                ArrowPreference(
                    title = "AndroidLiquidGlass",
                    summary = "Kyant0/AndroidLiquidGlass · 液态玻璃效果",
                    onClick = { openUrl(context, "https://github.com/Kyant0/AndroidLiquidGlass") },
                )
            }
        }
    }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
    }
}