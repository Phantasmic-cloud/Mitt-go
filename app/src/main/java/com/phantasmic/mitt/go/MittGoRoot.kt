package com.phantasmic.mitt.go

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phantasmic.mitt.go.ui.GlassCard
import com.phantasmic.mitt.go.ui.GlassLevel
import com.phantasmic.mitt.go.ui.GlassMenuItem
import com.phantasmic.mitt.go.ui.GlassPopover
import com.phantasmic.mitt.go.ui.LocalGlassEnabled
import com.phantasmic.mitt.go.ui.LocalPageBackdrop
import com.phantasmic.mitt.go.ui.PageChrome
import com.phantasmic.mitt.go.ui.pageBackdrop
import com.phantasmic.mitt.go.ui.rememberPageBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
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
    /** 0 = 还在屏幕外，1 = 已就位。下层读它做“被压下去”的效果。 */
    val progress = Animatable(0f)
}

/** 上层压下来的程度：0 = 完全露着，1 = 被完全盖住。只在绘制阶段读，不触发重组。 */
private fun recedeDepth(above: List<NavLayer>): Float {
    var remain = 1f
    above.forEach { remain *= (1f - it.progress.value).coerceIn(0f, 1f) }
    return 1f - remain
}

@Composable
fun MittGoRoot(
    settings: MittSettings,
    onSettingsChange: (MittSettings) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onContentReady: () -> Unit = {},
) {
    val runtime by ProxyRuntime.status.collectAsState()
    // 一级页用 Pager：点底栏滑过去、手指也能左右滑。
    // 状态只保留 pagerState 一个来源：page 直接取 currentPage，
    // 底栏选中框因此始终跟着手指/动画走，也不会出现“点过去又弹回来”的竞态。
    val pagerState = rememberPagerState(initialPage = PAGE_CAPTURE) { 4 }
    val page = pagerState.currentPage
    val uiScope = rememberCoroutineScope()
    var highlightOpen by remember { mutableStateOf(false) }
    var upstreamDialogOpen by remember { mutableStateOf(false) }
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
    // 高级效果始终生效：之前为了帧率在动画/滚动期间关掉模糊，实测关掉也一样掉帧，
    // 说明瓶颈不在这里，那就不该再牺牲观感。
    val glassActive = settings.advancedEffects

    // 一级页（Pager）的取景图层：底栏、标题胶囊、三点按钮、抓包菜单都模糊它。
    val backdrop = rememberPageBackdrop()

    // 启动衔接：页面从第一帧就是完全不透明的，而且第一帧真的画出来之后才通知系统
    // 放走启动画面——icon 淡出的同时页面就在下面显出来，两段动画是并行的。
    // 之前这里让页面自己也从 0 淡入，两段动画串行，中间会露出一段空档。
    LaunchedEffect(Unit) {
        // 等一帧：composition → layout → draw 都过了，再告诉系统可以揭开。
        withTimeoutOrNull(1200) { withFrameNanos { } }
        onContentReady()
    }

    BackHandler(enabled = overlayVisible) { pop() }

    // 翻页时收起抓包菜单，否则菜单会跟着挂到别的页面上。
    LaunchedEffect(page) { menuOpen = false }
    // 菜单锚在三点按钮上，而按钮是跟着页面平移的；菜单是屏幕级浮层不跟着走，
    // 所以手指一开始搓就先收起来，免得看到菜单和按钮分家。
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) menuOpen = false
    }

    CompositionLocalProvider(
        LocalGlassEnabled provides glassActive,
        LocalPageBackdrop provides backdrop,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface),
        ) {
            Scaffold(
                // 一级页不再用不透明大标题栏：日志/模块/设置的标题都是悬浮胶囊。
                topBar = {},
            ) { inner ->
                val topInset = inner.calculateTopPadding()
                // 悬浮底栏抬高了，页面底部留白同步加 36dp，内容不会被盖住。
                val bottomInset = inner.calculateBottomPadding() + 36.dp
                // 抓包页没有顶栏，至少要给状态栏留出高度，列表才不会钻到状态栏后面
                val captureTop = if (topInset > statusBarTop) topInset else statusBarTop
                // 模块/设置页的内容从悬浮胶囊下方开始。
                // 这个值只由页面自己决定，不能按 currentPage 算：Pager 会提前组合相邻页，
                // 抓包→模块搓到一半时模块页还不是 currentPage，拿到的却是抓包页的留白，
                // 整页内容会往上飘 64dp 和标题胶囊叠在一起。
                val capsuleTop = statusBarTop + 64.dp

                Box(Modifier.fillMaxSize()) {
                    // 一级页内容录进取景图层；底栏、标题胶囊、三点按钮都在这个节点外面。
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pageBackdrop(backdrop),
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
                                    topInset = captureTop,
                                    bottomInset = bottomInset,
                                    controller = captureUi,
                                    onStart = onStart,
                                    onStop = onStop,
                                    onSelect = { summary -> push(NavEntry.Detail(summary)) },
                                    onSelectHost = { host -> push(NavEntry.Domain(host)) },
                                )

                                PAGE_MODULES -> ModulesPage(
                                    topInset = capsuleTop,
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
                                    topInset = capsuleTop,
                                    bottomInset = bottomInset,
                                    onOpen = { push(NavEntry.Settings(it)) },
                                    onOpenUpstream = { upstreamDialogOpen = true },
                                )
                            }
                        }
                    }
                    // 悬浮元素统一走 PageChrome：画在取景图层外面（玻璃安全），
                    // 又按 pager 的偏移跟着自己那一页一起平移。
                    // 带点击的按钮在二级页滑进来时直接卸掉——否则二级页空白处的点击
                    // 会穿过页面落到下面的按钮上；短胶囊不带点击，让二级页盖住就行，
                    // 提前卸掉反而会在进/退二级页时闪一下。
                    if (!overlayVisible) {
                        PageChrome(pagerState = pagerState, page = PAGE_CAPTURE) {
                            CircleMenuButton(
                                open = menuOpen,
                                onClick = { menuOpen = !menuOpen },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = captureTop + 8.dp, end = 8.dp),
                            )
                        }
                        PageChrome(pagerState = pagerState, page = PAGE_LOG) {
                            LogHeader(
                                topInset = statusBarTop,
                                onExport = { exportLogs(context) },
                                modifier = Modifier.align(Alignment.TopCenter),
                            )
                        }
                    }

                    PageChrome(pagerState = pagerState, page = PAGE_MODULES) {
                        TitleCapsule(
                            title = "模块",
                            topInset = statusBarTop,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                    PageChrome(pagerState = pagerState, page = PAGE_SETTINGS) {
                        TitleCapsule(
                            title = "设置",
                            topInset = statusBarTop,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }

                    MittBottomBar(
                        selectedIndex = page,
                        onSelected = { target ->
                            uiScope.launch { pagerState.animateScrollToPage(target) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, bottom = 50.dp),
                    )

                    GlassPopover(
                        visible = menuOpen && !overlayVisible,
                        onDismiss = { menuOpen = false },
                        origin = TransformOrigin(1f, 0f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = captureTop + 58.dp, end = 12.dp),
                    ) {
                        CaptureMenuContent(
                            searchOpen = captureUi.searchOpen,
                            domainMode = captureUi.displayMode == CaptureDisplayMode.DOMAIN,
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
                layers.forEachIndexed { index, layer ->
                    key(layer.key) {
                        NavLayerHost(
                            layer = layer,
                            above = layers.drop(index + 1),
                            widthPx = widthPx,
                            onExited = { layers.remove(layer) },
                        ) {
                            OverlayLayer(
                                entry = layer.entry,
                                settings = settings,
                                onSettingsChange = onSettingsChange,
                                running = runtime.running,
                                scrollBehavior = subBarScroll,
                                onBack = pop,
                                onSelectRecord = { summary -> push(NavEntry.Detail(summary)) },
                                onOpenSession = { session -> push(NavEntry.History(session)) },
                            )
                        }
                    }
                }
            }

            HighlightDialog(
                visible = highlightOpen,
                settings = settings,
                onSettingsChange = onSettingsChange,
                onDismiss = { highlightOpen = false },
            )

            UpstreamProxyDialog(
                visible = upstreamDialogOpen,
                settings = settings,
                onSettingsChange = onSettingsChange,
                onDismiss = { upstreamDialogOpen = false },
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
    // 这一层自己的取景图层：详情页保存菜单、YAML 编辑器模糊的是本层内容，
    // 不能借一级页的 backdrop（那样玻璃里透出来的是抓包列表）。
    // 记录节点只包页面本身，浮层放在它的兄弟位置，否则 hwui 渲染树会成环闪退。
    val pageBackdrop = rememberPageBackdrop()
    val glassOpen = saveMenuOpen ||
        (entry is NavEntry.AsyncUpload && (asyncUi.editorOpen || asyncUi.actionTarget != null))
    // 浮层退场动画还在跑的时候不能立刻拆掉取景图层，否则玻璃会闪一下变成实心卡片。
    var backdropActive by remember { mutableStateOf(false) }
    LaunchedEffect(glassOpen) {
        if (glassOpen) {
            backdropActive = true
        } else {
            delay(200)
            backdropActive = false
        }
    }
    val title = when (entry) {
        is NavEntry.Detail -> "详细"
        is NavEntry.Settings -> if (isAbout) "" else entry.page.title
        is NavEntry.Domain -> entry.host
        is NavEntry.History -> entry.session?.let { sessionLabel(it) } ?: "历史记录"
        is NavEntry.AsyncUpload -> "旁路上传"
    }

    CompositionLocalProvider(LocalPageBackdrop provides pageBackdrop) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.surface),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pageBackdrop(pageBackdrop, enabled = backdropActive),
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
                                    // 导入 = 取进来，箭头朝下；导出用的是 ExportGlyph。
                                    IconButton(onClick = { asyncUi.importTick++ }) {
                                        ImportGlyph(color = colors.onSurface)
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
            }

            SaveMenuOverlay(
                visible = saveMenuOpen && entry is NavEntry.Detail && detail != null,
                detail = detail,
                topPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp,
                onDismiss = { saveMenuOpen = false },
            )

            if (entry is NavEntry.AsyncUpload) {
                YamlEditorOverlay(ui = asyncUi, modifier = Modifier.fillMaxSize())
                AsyncConfigMenu(ui = asyncUi)
            }
        }
    }
}

/** 一层纸：进入时从右侧滑到原位，退出时滑回右侧，动画结束后把自己从栈里摘掉。 */
@Composable
private fun NavLayerHost(
    layer: NavLayer,
    above: List<NavLayer>,
    widthPx: Float,
    onExited: () -> Unit,
    content: @Composable () -> Unit,
) {
    val progress = layer.progress
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 320, easing = LinearOutSlowInEasing))
    }
    LaunchedEffect(layer.exiting) {
        if (layer.exiting) {
            progress.animateTo(0f, tween(durationMillis = 280, easing = FastOutLinearInEasing))
            onExited()
        }
    }
    val shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // 上层滑进来时，这一层跟着往左退一点、缩小一点，做出层次感。
                val depth = recedeDepth(above)
                translationX = (1f - progress.value) * widthPx - widthPx * 0.12f * depth
                scaleX = 1f - 0.04f * depth
                scaleY = 1f - 0.04f * depth
            }
            // 滑入的这张“纸”要有投影，否则和下面的页面糊在一起。
            .shadow(elevation = 16.dp, shape = shape, clip = false)
            .drawWithContent {
                drawContent()
                val depth = recedeDepth(above)
                if (depth > 0.01f) {
                    drawRect(Color.Black.copy(alpha = 0.16f * depth))
                }
            }
            .clip(shape),
    ) {
        content()
    }
}

/** 保存动作放到工作线程，完成后回主线程 Toast。 */
private fun runExport(context: Context, action: () -> String?) {
    exportAsync(
        context = context,
        action = { runCatching { action() ?: error("save failed") } },
        message = { result ->
            result.fold(
                onSuccess = { "已保存到 Download/Mitt-go/$it" },
                onFailure = { "保存失败" },
            )
        },
    )
}

/** 详情页保存菜单：贴着右上角弹出的玻璃菜单。 */
@Composable
private fun SaveMenuOverlay(
    visible: Boolean,
    detail: CaptureDetail?,
    topPadding: Dp,
    onDismiss: () -> Unit,
) {
    if (detail == null) return
    Box(Modifier.fillMaxSize()) {
        GlassPopover(
            visible = visible,
            onDismiss = onDismiss,
            origin = TransformOrigin(1f, 0f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = topPadding, end = 12.dp),
        ) {
            SaveMenu(detail = detail, onDismiss = onDismiss)
        }
    }
}

/** 详情页保存菜单：玻璃卡片 + 三项。 */
@Composable
private fun SaveMenu(detail: CaptureDetail, onDismiss: () -> Unit) {
    val context = LocalContext.current
    GlassCard(
        modifier = Modifier.width(172.dp),
        shape = RoundedCornerShape(18.dp),
        level = GlassLevel.Menu,
        padding = PaddingValues(vertical = 6.dp),
    ) {
        GlassMenuItem("保存请求和响应") {
            onDismiss()
            runExport(context) { DetailExport.saveRequestAndResponse(context, detail) }
        }
        GlassMenuItem("保存请求体") {
            onDismiss()
            runExport(context) { DetailExport.saveBody(context, detail, response = false) }
        }
        GlassMenuItem("保存响应体") {
            onDismiss()
            runExport(context) { DetailExport.saveBody(context, detail, response = true) }
        }
    }
}

