package com.phantasmic.mitt.go

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

















private const val PAGE_LOG = 0
private const val PAGE_CAPTURE = 1
private const val PAGE_MODULES = 2
private const val PAGE_SETTINGS = 3

/** 子页栈里的一层。push 就是从右侧滑进来一张纸，pop 就是把它滑出去扔掉。 */
private sealed interface NavEntry {
    data class Settings(val page: SettingsSubPage) : NavEntry
    data class Domain(val host: String) : NavEntry
    data class History(val session: CaptureSession? = null) : NavEntry
    data class Detail(val summary: CaptureSummary) : NavEntry
    data object AsyncUpload : NavEntry
}

private class NavLayer(val key: Int, val entry: NavEntry) {
    var exiting by mutableStateOf(false)
}

@Composable
fun MittGoRoot(
    settings: MittSettings,
    onSettingsChange: (MittSettings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val runtime by ProxyRuntime.status.collectAsState()
    // 一级页用 Pager：点底栏滑过去、手指也能左右滑。
    // 状态只保留 pagerState 一个来源：page 直接取 currentPage，
    // 底栏选中框因此始终跟着手指/动画走，也不会出现“点过去又弹回来”的竞态。
    val pagerState = rememberPagerState(initialPage = PAGE_CAPTURE) { 4 }
    val page = pagerState.currentPage
    val uiScope = rememberCoroutineScope()
    var highlightOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val captureUi = remember { CaptureUiController() }
    val subBarScroll = MiuixScrollBehavior()

    // 所有子页（无论几级）都走这一个栈：进入 = push，退出 = pop。
    // 栈里的每层都一直留在组合里，所以上层滑进来时下层早就渲染好了。
    val layers = remember { mutableStateListOf<NavLayer>() }
    var nextLayerKey by remember { mutableIntStateOf(1) }
    val push: (NavEntry) -> Unit = remember {
        { entry ->
            layers.add(NavLayer(nextLayerKey, entry))
            nextLayerKey += 1
        }
    }
    val pop: () -> Unit = remember {
        {
            val top = layers.lastOrNull()
            if (top != null && !top.exiting) top.exiting = true
        }
    }

    val overlayVisible = layers.isNotEmpty()
    // 在 Scaffold 外面读，才不会拿到被 consume 掉的 0
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val context = LocalContext.current
    // 模块页显示“几份配置”，配置增删后跟着刷新。
    val asyncRevision = AsyncUploadStore.revision.intValue
    val asyncConfigCount = remember(asyncRevision, settings.asyncUploadEnabled) {
        AsyncUploadStore.list(context).count { it.enabled }
    }
    val useGlass = settings.advancedEffects

    // 高级效果始终生效：之前为了帧率在动画/滚动期间关掉模糊，实测关掉也一样掉帧，
    // 说明瓶颈不在这里，那就不该再牺牲观感。
    val glassActive = useGlass

    // 首帧先不组合底栏（FloatingBottomBar 首次组合不便宜），启动画面之后再加进来。
    var bottomBarMounted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(280)
        bottomBarMounted = true
    }
    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop(graphicsLayer = rememberGraphicsLayer()) {
        drawRect(surfaceColor)
        drawContent()
    }

    // 启动衔接：系统启动画面负责 icon + 底色，并在它淡出的同一段时间里
    // 把页面淡入，中途不铺第二层纯色，否则会多出一段“只有底色”的空档。
    var contentStarted by remember { mutableStateOf(false) }
    val contentAlpha = animateFloatAsState(
        targetValue = if (contentStarted) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "content",
    )
    var contentReleased by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        // 系统那条淡出动画是 300ms，这里稍晚一点起步，两者重叠。
        delay(60)
        contentStarted = true
        delay(360)
        contentReleased = true
    }

    BackHandler(enabled = overlayVisible) { pop() }
    BackHandler(enabled = menuOpen) { menuOpen = false }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = if (contentReleased) {
                Modifier
            } else {
                Modifier.graphicsLayer { alpha = contentAlpha.value }
            },
            // 一级页不再用不透明大标题栏：日志/模块/设置的标题都是悬浮胶囊。
            topBar = {},
        ) { inner ->
            val topInset = inner.calculateTopPadding()
            // 悬浮底栏抬高了，页面底部留白同步加 36dp，内容不会被盖住。
            val bottomInset = inner.calculateBottomPadding() + 36.dp
            // 抓包页没有顶栏，至少要给状态栏留出高度，列表才不会钻到状态栏后面
            val captureTop = if (topInset > statusBarTop) topInset else statusBarTop
            // 模块/设置页的内容从悬浮胶囊下方开始
            val capsuleTop = statusBarTop + 64.dp
            val listTop = if (page == PAGE_MODULES || page == PAGE_SETTINGS) capsuleTop else topInset

            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (glassActive) {
                                Modifier.layerBackdrop(backdrop)
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        key = { it },
                        beyondViewportPageCount = 1,
                    ) { current ->
                        when (current) {
                            PAGE_CAPTURE -> CapturePage(
                                runtime = runtime,
                                settings = settings,
                                backdrop = if (glassActive) backdrop else null,
                                topInset = captureTop,
                                bottomInset = bottomInset,
                                controller = captureUi,
                                onStart = onStart,
                                onStop = onStop,
                                onSelect = { summary -> push(NavEntry.Detail(summary)) },
                                onSelectHost = { host -> push(NavEntry.Domain(host)) },
                            )

                            PAGE_MODULES -> ModulesPage(
                                topInset = listTop,
                                bottomInset = bottomInset,
                                asyncEnabled = settings.asyncUploadEnabled,
                                asyncCount = asyncConfigCount,
                                onOpenAsync = { push(NavEntry.AsyncUpload) },
                            )

                            PAGE_LOG -> LogPage(
                                topInset = statusBarTop + 60.dp,
                                bottomInset = bottomInset,
                            )

                            else -> SettingsPage(
                                settings = settings,
                                onSettingsChange = onSettingsChange,
                                running = runtime.running,
                                topInset = listTop,
                                bottomInset = bottomInset,
                                onOpen = { push(NavEntry.Settings(it)) },
                            )
                        }
                    }
                }
if (page == PAGE_CAPTURE && !overlayVisible) {
                    CircleMenuButton(
                        open = menuOpen,
                        backdrop = if (glassActive) backdrop else null,
                        onClick = { menuOpen = !menuOpen },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = captureTop + 8.dp, end = 8.dp),
                    )
                }

                // 和悬浮底栏一样常驻：二级页从右侧滑入时会直接盖住它，
                // 不要提前消失，否则进/退二级页会出现胶囊闪一下。
                if (page == PAGE_MODULES || page == PAGE_SETTINGS) {
                    TitleCapsule(
                        title = if (page == PAGE_MODULES) "模块" else "设置",
                        topInset = statusBarTop,
                        backdrop = if (glassActive) backdrop else null,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                if (page == PAGE_LOG && !overlayVisible) {
                    LogHeader(
                        topInset = statusBarTop,
                        backdrop = if (glassActive) backdrop else null,
                        onExport = { exportLogs(context) },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }

                if (bottomBarMounted) {
                    MittBottomBar(
                        selectedIndex = page,
                        onSelected = { target ->
                            uiScope.launch { pagerState.animateScrollToPage(target) }
                        },
                        backdrop = backdrop,
                        blurEnabled = glassActive,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, bottom = 50.dp),
                    )
                }

                if (menuOpen && !overlayVisible) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) { detectTapGestures { menuOpen = false } },
                    )
                }
                AnimatedVisibility(
                    visible = menuOpen && !overlayVisible,
                    enter = fadeIn(tween(120)) + scaleIn(
                        initialScale = 0.92f,
                        animationSpec = tween(140, easing = LinearOutSlowInEasing),
                    ),
                    exit = ExitTransition.None,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = captureTop + 58.dp, end = 12.dp),
                ) {
                    CaptureMenu(
                        searchOpen = captureUi.searchOpen,
                        domainMode = captureUi.displayMode == CaptureDisplayMode.DOMAIN,
                        backdrop = if (glassActive) backdrop else null,
                        onSearch = {
                            captureUi.searchOpen = !captureUi.searchOpen
                            if (!captureUi.searchOpen) captureUi.query = ""
                            menuOpen = false
                        },
                        onClear = {
                            menuOpen = false
                            captureUi.clearTick++
                        },
                        onToggleMode = {
                            captureUi.displayMode =
                                if (captureUi.displayMode == CaptureDisplayMode.DOMAIN) {
                                    CaptureDisplayMode.URL
                                } else {
                                    CaptureDisplayMode.DOMAIN
                                }
                            menuOpen = false
                        },
                        onHighlight = {
                            menuOpen = false
                            highlightOpen = true
                        },
                        onHistory = {
                            menuOpen = false
                            push(NavEntry.History())
                        },
                    )
                }
            }
        }

        // 子页栈：每层一个 Box，进入时从右侧滑到原位盖住下层，退出时滑回右侧被扔掉。
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = constraints.maxWidth.toFloat()
            layers.forEach { layer ->
                key(layer.key) {
                    NavLayerHost(
                        layer = layer,
                        widthPx = widthPx,
                        onExited = { layers.remove(layer) },
                    ) {
                        OverlayLayer(
                            entry = layer.entry,
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            running = runtime.running,
                            backdrop = if (glassActive) backdrop else null,
                            scrollBehavior = subBarScroll,
                            onBack = pop,
                            onSelectRecord = { summary -> push(NavEntry.Detail(summary)) },
                            onOpenSession = { session -> push(NavEntry.History(session)) },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = highlightOpen,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(140)) + scaleIn(initialScale = 0.92f, animationSpec = tween(160)),
            exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.94f, animationSpec = tween(140)),
        ) {
            HighlightDialog(
                settings = settings,
                onSettingsChange = onSettingsChange,
                backdrop = if (glassActive) backdrop else null,
                onDismiss = { highlightOpen = false },
            )
        }
    }
}

@Composable
private fun OverlayLayer(
    entry: NavEntry,
    settings: MittSettings,
    onSettingsChange: (MittSettings) -> Unit,
    running: Boolean,
    backdrop: Backdrop?,
    scrollBehavior: ScrollBehavior,
    onBack: () -> Unit,
    onSelectRecord: (CaptureSummary) -> Unit,
    onOpenSession: (CaptureSession) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val isAbout = entry is NavEntry.Settings && entry.page == SettingsSubPage.ABOUT
    var saveMenuOpen by remember { mutableStateOf(false) }
    // 旁路上传页右上角的两个小按钮，通过 tick 通知页面内的动作。
    val asyncUi = remember { AsyncUploadUi() }
    // 详情数据由本层自己加载：每层各管各的，退出动画里不会串成别的记录。
    val detailEntry = entry as? NavEntry.Detail
    val entryId = detailEntry?.summary?.id
    var detail by remember(entryId) { mutableStateOf<CaptureDetail?>(null) }
    var detailFailed by remember(entryId) { mutableStateOf(false) }
    LaunchedEffect(entryId) {
        detailEntry ?: return@LaunchedEffect
        detail = null
        detailFailed = false
        val loaded = CaptureStore.detail(detailEntry.summary.id)
        detail = loaded
        if (loaded == null) detailFailed = true
    }
    // 保存菜单开着时，返回键先关菜单，不要直接退掉整层。
    BackHandler(enabled = saveMenuOpen) { saveMenuOpen = false }
    val title = when (entry) {
        is NavEntry.Detail -> "详细"
        is NavEntry.Settings -> if (isAbout) "" else entry.page.title
        is NavEntry.Domain -> entry.host
        is NavEntry.History -> entry.session?.let { sessionLabel(it) } ?: "历史记录"
        is NavEntry.AsyncUpload -> "旁路上传"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.surface),
    ) {
        Scaffold(
            topBar = {
                SmallTopAppBar(
                    title = title,
                    scrollBehavior = scrollBehavior,
                    // 关于页照 KernelSU：顶栏透明、不显示标题，只有返回箭头
                    color = if (isAbout) Color.Transparent else colors.surface,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Text("‹", fontSize = 28.sp)
                        }
                    },
                    actions = {
                        if (entry is NavEntry.Detail && detail != null) {
                            IconButton(onClick = { saveMenuOpen = true }) {
                                ExportGlyph(color = colors.onSurface)
                            }
                        }
                        if (entry is NavEntry.AsyncUpload) {
                            IconButton(onClick = { asyncUi.importTick++ }) {
                                ExportGlyph(color = colors.onSurface)
                            }
                            IconButton(onClick = { asyncUi.createTick++ }) {
                                PlusGlyph(color = colors.onSurface)
                            }
                        }
                    },
                )
            },
        ) { inner ->
            val topInset = inner.calculateTopPadding()
            val bottomInset = inner.calculateBottomPadding()
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(
                        RoundedCornerShape(
                            topStart = 20.dp,
                            bottomStart = 20.dp,
                        ),
                    ),
            ) {
                when (entry) {
                    is NavEntry.Detail -> RequestDetailPage(
                        detail = detail,
                        summary = entry.summary,
                        topInset = topInset,
                        bottomInset = bottomInset,
                        loadFailed = detailFailed,
                        modifier = Modifier.fillMaxSize(),
                    )

                    is NavEntry.Settings -> when (entry.page) {
                        SettingsSubPage.CERTIFICATE -> CertificatePage(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            running = running,
                            topInset = topInset,
                            bottomInset = bottomInset,
                            modifier = Modifier.fillMaxSize(),
                        )

                        SettingsSubPage.FILTER -> ProxyFilterPage(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            running = running,
                            topInset = topInset,
                            bottomInset = bottomInset,
                            modifier = Modifier.fillMaxSize(),
                        )

                        SettingsSubPage.THEME -> ThemePage(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            topInset = topInset,
                            bottomInset = bottomInset,
                            modifier = Modifier.fillMaxSize(),
                        )

                        SettingsSubPage.DOMAIN_FILTER -> DomainFilterPage(
                            settings = settings,
                            onSettingsChange = onSettingsChange,
                            running = running,
                            topInset = topInset,
                            bottomInset = bottomInset,
                            modifier = Modifier.fillMaxSize(),
                        )

                        SettingsSubPage.ABOUT -> AboutPage(
                            topInset = topInset,
                            bottomInset = bottomInset,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    is NavEntry.Domain -> DomainPage(
                        host = entry.host,
                        topInset = topInset,
                        bottomInset = bottomInset,
                        onSelect = onSelectRecord,
                    )

                    is NavEntry.AsyncUpload -> AsyncUploadPage(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        running = running,
                        topInset = topInset,
                        bottomInset = bottomInset,
                        ui = asyncUi,
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is NavEntry.History -> HistoryPage(
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        topInset = topInset,
                        bottomInset = bottomInset,
                        onSelectRecord = onSelectRecord,
                        session = entry.session,
                        onOpenSession = onOpenSession,
                    )
                }
            }
        }

        SaveMenuOverlay(
            visible = saveMenuOpen && entry is NavEntry.Detail,
            detail = detail,
            backdrop = backdrop,
            topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp,
            onDismiss = { saveMenuOpen = false },
        )
    }
}

/** 一层纸：进入时从右侧滑到原位，退出时滑回右侧，动画结束后把自己从栈里摘掉。 */
@Composable
private fun NavLayerHost(
    layer: NavLayer,
    widthPx: Float,
    onExited: () -> Unit,
    content: @Composable () -> Unit,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 320, easing = LinearOutSlowInEasing))
    }
    LaunchedEffect(layer.exiting) {
        if (layer.exiting) {
            progress.animateTo(0f, tween(durationMillis = 280, easing = FastOutLinearInEasing))
            onExited()
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationX = (1f - progress.value) * widthPx }
            .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)),
    ) {
        content()
    }
}

@Composable
private fun MenuDots() {
    Canvas(Modifier.size(20.dp)) {
        val radius = 1.7.dp.toPx()
        val color = Color(0xFF7A7A7A)
        listOf(0.28f, 0.5f, 0.72f).forEach { fraction ->
            drawCircle(color, radius, Offset(size.width / 2f, size.height * fraction))
        }
    }
}

/** 保存动作放到工作线程，完成后回主线程 Toast。 */
private fun runExport(context: android.content.Context, action: () -> String?) {
    Thread {
        val name = runCatching(action).getOrNull()
        Handler(Looper.getMainLooper()).post {
            val text = if (name == null) {
                "保存失败"
            } else {
                "已保存到 Download/Mitt-go/$name"
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }.start()
}

/** 详情页保存菜单的覆盖层：带进出动画，点空白处关闭。 */
@Composable
private fun SaveMenuOverlay(
    visible: Boolean,
    detail: CaptureDetail?,
    backdrop: Backdrop?,
    topPadding: Dp,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(100)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
        }
        AnimatedVisibility(
            visible = visible && detail != null,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = topPadding, end = 12.dp),
            enter = scaleIn(initialScale = 0.88f, animationSpec = tween(150)) +
                fadeIn(tween(150)),
            exit = scaleOut(targetScale = 0.9f, animationSpec = tween(120)) +
                fadeOut(tween(120)),
        ) {
            if (detail != null) {
                SaveMenu(detail = detail, backdrop = backdrop, onDismiss = onDismiss)
            }
        }
    }
}

/** 详情页保存菜单：玻璃卡片 + 三项。 */
@Composable
private fun SaveMenu(detail: CaptureDetail, backdrop: Backdrop?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .width(172.dp)
            .clip(shape)
            .glassSurface(
                backdrop = backdrop,
                shape = shape,
                container = colors.surfaceContainer.copy(
                    alpha = if (backdrop == null) 0.96f else 0.62f,
                ),
            )
            .border(0.8.dp, colors.outline.copy(alpha = 0.3f), shape)
            .padding(vertical = 6.dp),
    ) {
        SaveMenuItem("保存请求和响应") {
            onDismiss()
            runExport(context) { DetailExport.saveRequestAndResponse(context, detail) }
        }
        SaveMenuItem("保存请求体") {
            onDismiss()
            runExport(context) { DetailExport.saveBody(context, detail, response = false) }
        }
        SaveMenuItem("保存响应体") {
            onDismiss()
            runExport(context) { DetailExport.saveBody(context, detail, response = true) }
        }
    }
}

@Composable
private fun SaveMenuItem(label: String, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Text(
        text = label,
        fontSize = 14.sp,
        color = colors.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** 右上角的「新建」加号图标。 */
@Composable
private fun PlusGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.8.dp.toPx()
        val w = size.width
        val h = size.height
        drawLine(
            color,
            Offset(w * 0.5f, h * 0.16f),
            Offset(w * 0.5f, h * 0.84f),
            stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(w * 0.16f, h * 0.5f),
            Offset(w * 0.84f, h * 0.5f),
            stroke,
            cap = StrokeCap.Round,
        )
    }
}
