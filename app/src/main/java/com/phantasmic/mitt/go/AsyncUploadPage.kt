package com.phantasmic.mitt.go

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.Backdrop
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import androidx.compose.foundation.layout.Arrangement
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 右上角两个小按钮 → 页面内的动作（导入 / 新建）。 */
class AsyncUploadUi {
    var importTick by mutableIntStateOf(0)
    var createTick by mutableIntStateOf(0)
}

private const val YAML_TEMPLATE = """enable: true
name: 我的配置
match_url:
  - "^https://example\\.com/api/(?<id>\\d+)$"
upload:
  server_url: https://example.com/upload/{id}
  tls_skip_verify: false
  headers:
    - Content-Type: application/octet-stream
  file:
    type: Response Body
    rename: example-{id}-{time}.bin
  timeout: 10
  max_retries: 0
notify:
  enabled: true
  extra_info: {id}
"""

/**
 * 旁路上传的二级页：总开关 + 配置列表 + YAML 编辑器。
 *
 * 导入和新建都先打开编辑器，用户点“保存”才真正落盘；点“取消”什么也不做。
 */
@Composable
fun AsyncUploadPage(
    settings: MittSettings,
    onSettingsChange: (MittSettings) -> Unit,
    running: Boolean,
    topInset: Dp,
    bottomInset: Dp,
    ui: AsyncUploadUi,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val revision = AsyncUploadStore.revision.intValue
    val configs = remember(revision) { AsyncUploadStore.list(context) }
    var editorOpen by remember { mutableStateOf(false) }
    var editorId by remember { mutableStateOf<String?>(null) }
    var editorSeed by remember { mutableStateOf(YAML_TEMPLATE) }

    fun refresh() {
        if (running) AsyncUploadRuntime.sync()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            MittLog.info("async", "已获得通知权限")
        } else {
            MittLog.warn("async", "没有通知权限，上传结果只会写进日志页")
            Toast.makeText(context, "没有通知权限，结果只会写进日志页", Toast.LENGTH_LONG).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        AsyncUploadStore.readText(context, uri, queryDisplayName(context, uri))
            .onSuccess { raw ->
                MittLog.info("async", "已读入配置文件，等待确认保存")
                editorId = null
                editorSeed = raw
                editorOpen = true
            }
            .onFailure { error ->
                MittLog.warn("async", "导入失败：${error.message}")
                Toast.makeText(context, "导入失败：${error.message}", Toast.LENGTH_LONG).show()
            }
    }

    fun openImport() {
        importLauncher.launch(
            arrayOf(
                "application/x-yaml",
                "application/yaml",
                "text/yaml",
                "text/plain",
                "application/octet-stream",
            ),
        )
    }

    fun openCreate() {
        editorId = null
        editorSeed = YAML_TEMPLATE
        editorOpen = true
        MittLog.debug("async") { "打开新建配置编辑器" }
    }

    LaunchedEffect(ui.importTick) {
        if (ui.importTick > 0) openImport()
    }
    LaunchedEffect(ui.createTick) {
        if (ui.createTick > 0) openCreate()
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 14.dp,
            end = 14.dp,
            top = topInset + 6.dp,
            bottom = bottomInset + 28.dp,
        ),
    ) {
        item(key = "switch") {
            Card(Modifier.fillMaxWidth()) {
                SwitchPreference(
                    checked = settings.asyncUploadEnabled,
                    title = "旁路上传",
                    summary = "命中规则的请求/响应体会异步 POST 到服务器，不影响原来的流量",
                    onCheckedChange = { enabled ->
                        MittLog.info("async", "总开关 → $enabled")
                        onSettingsChange(settings.copy(asyncUploadEnabled = enabled))
                        if (running) AsyncUploadRuntime.sync()
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !AsyncNotify.canPost(context)
                        ) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                )
            }
        }

        if (configs.isEmpty()) {
            item(key = "empty") {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        text = "还没有配置。点右上角的两个按钮：箭头导入 .yaml，加号手写一份。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.onSurfaceSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
            }
        } else {
            items(configs, key = { it.id }) { config ->
                // animateItem：新增时下面的配置被顶下去，删除时下面的补上来。
                Column(Modifier.animateItem()) {
                    Spacer(Modifier.height(12.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editorId = config.id
                                    editorSeed = config.raw
                                    editorOpen = true
                                    MittLog.debug("async") { "编辑配置「${config.name}」" }
                                }
                                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = config.name,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.onSurface,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = config.firstPattern.ifBlank { "（没有 match_url）" },
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.onSurfaceSecondary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "导出",
                                fontSize = 13.sp,
                                color = colors.primary,
                                modifier = Modifier
                                    .clickable {
                                        val name = AsyncUploadStore.export(context, config)
                                        if (name == null) {
                                            MittLog.warn("async", "导出「${config.name}」失败")
                                            Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
                                        } else {
                                            MittLog.info("async", "已导出 $name")
                                            Toast.makeText(context, "已导出：$name", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Box(
                                Modifier
                                    .clickable {
                                        AsyncUploadStore.delete(context, config.id)
                                        MittLog.info("async", "已删除配置「${config.name}」")
                                        refresh()
                                    }
                                    .padding(8.dp),
                            ) {
                                TrashGlyph(color = Color(0xFF9A9A9A))
                            }
                        }
                    }
                }
            }
        }
    }

    AnimatedVisibility(
        visible = editorOpen,
        enter = fadeIn(tween(160)) + scaleIn(
            initialScale = 0.94f,
            animationSpec = tween(180, easing = LinearOutSlowInEasing),
        ),
        exit = fadeOut(tween(120)) + scaleOut(
            targetScale = 0.96f,
            animationSpec = tween(140, easing = FastOutLinearInEasing),
        ),
    ) {
        // imePadding：键盘弹出时把整层顶到键盘上方，面板和光标不会被遮住。
        Box(Modifier.fillMaxSize().imePadding()) {
        // 透明的点击遮罩：只负责“点空白关闭”，不压暗背景。
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) { editorOpen = false },
        )
        YamlEditorPanel(
            title = if (editorId == null) "新建配置" else "编辑配置",
            initial = editorSeed,
            backdrop = backdrop,
            shape = RoundedCornerShape(24.dp),
            container = if (backdrop == null) {
                colors.surface.copy(alpha = 0.94f)
            } else {
                colors.surface.copy(alpha = 0.70f)
            },
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f),
            onDismiss = { editorOpen = false },
            onSave = { text ->
                val id = editorId ?: java.util.UUID.randomUUID().toString()
                runCatching { AsyncUploadStore.save(context, text, id) }
                    .onSuccess { config ->
                        editorOpen = false
                        MittLog.info("async", "已保存配置「${config.name}」")
                        Toast.makeText(context, "已保存：${config.name}", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                    .onFailure { error ->
                        MittLog.warn("async", "保存失败：${error.message}")
                        Toast.makeText(context, "保存失败：${error.message}", Toast.LENGTH_LONG).show()
                    }
            },
        )
        }
    }
    }
}

/**
 * YAML 编辑器：行号 + 缩进辅助线 + 语法配色。
 *
 * 关掉软换行（正文放在横向滚动里），一个逻辑行永远只占一行，行号才能和
 * 正文严格对齐；长行（比如一长串 token）靠横向滚动看。
 */
@Composable
private fun YamlEditorPanel(
    title: String,
    initial: String,
    backdrop: Backdrop?,
    shape: Shape,
    container: Color,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var text by remember { mutableStateOf(initial) }
    val vertical = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val measurer = rememberTextMeasurer()

    val density = LocalDensity.current
    val textSize = 12.sp
    val lineHeight = 18.sp
    val padTop = 10.dp
    val logicalCount = text.count { it == '\n' } + 1
    val gutterWidth = with(density) {
        textSize.toDp() * 0.62f * logicalCount.toString().length.coerceAtLeast(2) + 16.dp
    }
    val charWidth = with(density) { textSize.toPx() * 0.6f }
    val lineStarts = remember(text) {
        val starts = ArrayList<Int>()
        starts.add(0)
        text.forEachIndexed { index, ch -> if (ch == '\n') starts.add(index + 1) }
        starts
    }
    val maxIndent = remember(text) {
        text.split('\n').maxOfOrNull { line -> (line.length - line.trimStart().length) / 2 } ?: 0
    }
    val gutterStyle = TextStyle(
        fontSize = textSize,
        lineHeight = lineHeight,
        fontFamily = FontFamily.Monospace,
        color = Color(0xFF9A9A9A),
    )
    val markerStyle = TextStyle(
        fontSize = 12.sp,
        lineHeight = lineHeight,
        fontFamily = FontFamily.Monospace,
        color = Color(0xFF9E9E9E),
    )

    Box(
        modifier
            .clip(shape)
            .then(
                if (backdrop != null) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = { blur(8.dp.toPx(), 8.dp.toPx()) },
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(container, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceVariant.copy(alpha = 0.55f))
                    .verticalScroll(vertical),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            val result = layout ?: return@drawBehind
                            val padTopPx = padTop.toPx()
                            val textLeft = gutterWidth.toPx()

                            // 行号列与正文之间的分隔线。
                            drawLine(
                                color = Color(0x332F6FED),
                                start = Offset(textLeft, 0f),
                                end = Offset(textLeft, this.size.height),
                                strokeWidth = 1f,
                            )

                            // 缩进辅助线：每 2 个空格一根。
                            val step = charWidth * 2
                            for (level in 1..maxIndent) {
                                val x = textLeft + step * level
                                drawLine(
                                    color = Color(0x222F6FED),
                                    start = Offset(x, 0f),
                                    end = Offset(x, this.size.height),
                                    strokeWidth = 1f,
                                )
                            }

                            // 行号：只在每个逻辑行的第一个视觉行上画。
                            for (visual in 0 until result.lineCount) {
                                val startOffset = result.getLineStart(visual)
                                val logicalIndex = lineStarts.indexOf(startOffset)
                                if (logicalIndex < 0) continue
                                val label = (logicalIndex + 1).toString()
                                val measured = measurer.measure(
                                    text = AnnotatedString(label),
                                    style = gutterStyle,
                                )
                                val baseline = result.getLineBaseline(visual)
                                val dy = padTopPx + baseline - measured.getLineBaseline(0)
                                drawText(
                                    textLayoutResult = measured,
                                    topLeft = Offset(
                                        textLeft - measured.size.width - 6.dp.toPx(),
                                        dy,
                                    ),
                                )
                            }

                            // ↲：这一逻辑行被软换行时，在最后一段末尾标一下。
                            for ((index, lineStart) in lineStarts.withIndex()) {
                                val lineEndExclusive =
                                    if (index + 1 < lineStarts.size) lineStarts[index + 1] - 1 else text.length
                                if (lineEndExclusive <= lineStart) continue
                                val lastChar = lineEndExclusive - 1
                                val firstVisual = result.getLineForOffset(lineStart)
                                val lastVisual = result.getLineForOffset(lastChar)
                                if (firstVisual == lastVisual) continue
                                val marker = measurer.measure(
                                    text = AnnotatedString("↲"),
                                    style = markerStyle,
                                )
                                val x = textLeft + result.getHorizontalPosition(
                                    lastChar,
                                    usePrimaryDirection = false,
                                ) + 4.dp.toPx()
                                drawText(
                                    textLayoutResult = marker,
                                    topLeft = Offset(
                                        x,
                                        padTopPx + result.getLineBaseline(lastVisual) -
                                            marker.getLineBaseline(0),
                                    ),
                                )
                            }
                        }
                        .padding(top = padTop, bottom = 10.dp),
                ) {
                    Spacer(Modifier.width(gutterWidth))
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        onTextLayout = { layout = it },
                        textStyle = TextStyle(
                            fontSize = textSize,
                            lineHeight = lineHeight,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurface,
                        ),
                        cursorBrush = SolidColor(colors.primary),
                        visualTransformation = YamlHighlight(
                            keyColor = colors.primary,
                            stringColor = Color(0xFFD08A22),
                            numberColor = Color(0xFF2A9D8F),
                            keywordColor = Color(0xFF9B59B6),
                            plainColor = Color(0xFF3E8E41),
                            commentColor = Color(0xFF9A9A9A),
                        ),
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = { onSave(text) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text("保存")
                }
            }
        }
    }
}

/**
 * 语法高亮。只加样式、绝不增删字符，因此可以配合 OffsetMapping.Identity。
 *
 * 注意不能拿 Kotlin 的 `lines()` 切：它会把 `\r` 吃掉，字符数变了就会让
 * Compose 报 “OffsetMapping.originalToTransformed returned invalid mapping”。
 */
private class YamlHighlight(
    private val keyColor: Color,
    private val stringColor: Color,
    private val numberColor: Color,
    private val keywordColor: Color,
    private val plainColor: Color,
    private val commentColor: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val builder = AnnotatedString.Builder()
        var start = 0
        while (true) {
            val newline = source.indexOf('\n', start)
            val end = if (newline < 0) source.length else newline
            appendLine(source.substring(start, end), builder)
            if (newline < 0) break
            builder.append('\n')
            start = newline + 1
        }
        // 双保险：万一段落拼装出了偏差，宁可不高亮也不能让 Compose 崩。
        if (builder.length != source.length) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }

    /** line 不含换行符；所有字符原样输出，只决定颜色。 */
    private fun appendLine(line: String, builder: AnnotatedString.Builder) {
        if (line.isEmpty()) return
        val trimmed = line.trimStart()
        when {
            trimmed.isEmpty() -> builder.append(line)
            trimmed.startsWith("#") -> builder.withStyle(SpanStyle(color = commentColor)) {
                builder.append(line)
            }
            else -> {
                val colon = line.indexOf(':')
                val keyCandidate = if (colon > 0) line.substring(0, colon) else ""
                val looksLikeKey = keyCandidate.isNotBlank() &&
                    !keyCandidate.trimStart().startsWith("#") &&
                    keyCandidate.none { it == '"' || it == '\'' }
                if (looksLikeKey) {
                    builder.withStyle(SpanStyle(color = keyColor)) {
                        builder.append(line.substring(0, colon + 1))
                    }
                    appendValue(line.substring(colon + 1), builder)
                } else {
                    val dashIndex = line.indexOf('-')
                    if (dashIndex >= 0 && line.substring(0, dashIndex).isBlank()) {
                        builder.withStyle(SpanStyle(color = keywordColor)) {
                            builder.append(line.substring(0, dashIndex + 1))
                        }
                        appendValue(line.substring(dashIndex + 1), builder)
                    } else {
                        builder.append(line)
                    }
                }
            }
        }
    }

    /** rest 原样输出：值（含首尾空格）一个颜色，行尾注释另一个颜色。 */
    private fun appendValue(rest: String, builder: AnnotatedString.Builder) {
        if (rest.isEmpty()) return
        val hash = rest.indexOf('#')
        val code = if (hash >= 0) rest.substring(0, hash) else rest
        val comment = if (hash >= 0) rest.substring(hash) else ""
        if (code.isNotEmpty()) {
            val value = code.trim()
            val color = when {
                value.isEmpty() -> plainColor
                value.startsWith("\"") || value.startsWith("'") -> stringColor
                value == "true" || value == "false" || value == "null" || value == "~" -> keywordColor
                value.toBigDecimalOrNull() != null -> numberColor
                else -> plainColor
            }
            builder.withStyle(SpanStyle(color = color)) { builder.append(code) }
        }
        if (comment.isNotEmpty()) {
            builder.withStyle(SpanStyle(color = commentColor)) { builder.append(comment) }
        }
    }
}

@Composable
private fun TrashGlyph(color: Color) {
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
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            style = Stroke(stroke),
        )
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}.getOrNull()