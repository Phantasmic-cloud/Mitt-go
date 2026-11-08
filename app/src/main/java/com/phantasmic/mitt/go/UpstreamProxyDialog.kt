package com.phantasmic.mitt.go

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phantasmic.mitt.go.ui.GlassCard
import com.phantasmic.mitt.go.ui.GlassLevel
import com.phantasmic.mitt.go.ui.MittModal
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 上游代理小窗：开关 + 地址 / 端口 + 可选的用户名密码。
 *
 * 浮层和关键词高亮、YAML 编辑器共用 `ui/MittOverlay.kt` 的 [MittModal]，
 * 遮罩、进出场、返回键都在里面——按返回关的是这个小窗，不会退掉设置页。
 *
 * 保存后由 [SettingsRuntime] 落盘并热下发给内核，抓包中改也立即生效。
 */
@Composable
fun UpstreamProxyDialog(
    visible: Boolean,
    settings: MittSettings,
    onSettingsChange: (MittSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val saved = settings.upstream
    // 每次打开都从已保存的配置重新填，取消就什么都不改。
    var enabled by remember(visible) { mutableStateOf(saved.enabled) }
    var host by remember(visible) { mutableStateOf(saved.host) }
    var portText by remember(visible) { mutableStateOf(saved.port.toString()) }
    var username by remember(visible) { mutableStateOf(saved.username) }
    var password by remember(visible) { mutableStateOf(saved.password) }

    val draft = UpstreamProxy(
        enabled = enabled,
        host = host.trim(),
        port = portText.toIntOrNull() ?: 0,
        username = username.trim(),
        password = password,
    )
    val canSave = draft.isValid

    MittModal(visible = visible, onDismiss = onDismiss, imePadding = true) {
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 26.dp),
            shape = RoundedCornerShape(22.dp),
            level = GlassLevel.Dialog,
        ) {
            Text(
                text = "上游代理",
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "启用",
                    fontSize = 14.sp,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                    colors = SwitchDefaults.switchColors(checkedTrackColor = colors.primary),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "抓到的请求在 MITM 之后先送到这个 HTTP 代理再出网，支持 Basic 认证",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = colors.onSurfaceSecondary,
            )
            Spacer(Modifier.height(12.dp))
            // 键盘弹出来时字段区自己滚，标题和按钮固定住。
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = host,
                        onValueChange = { input -> host = input.filterNot { it.isWhitespace() } },
                        modifier = Modifier.weight(1f),
                        label = "地址",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextField(
                        value = portText,
                        onValueChange = { input ->
                            portText = input.filter { it.isDigit() }.take(5)
                        },
                        modifier = Modifier.width(96.dp),
                        label = "端口",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "用户名（可选）",
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "密码（可选）",
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
            if (!canSave) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "地址不能为空，端口 1–65535",
                    fontSize = 11.sp,
                    color = Color(0xFFD64545),
                )
            }
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
                        if (!canSave) return@Button
                        onSettingsChange(settings.copy(upstream = draft))
                        onDismiss()
                    },
                    enabled = canSave,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("保存")
                }
            }
        }
    }
}
