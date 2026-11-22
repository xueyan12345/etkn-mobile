package xyz.etkn.mobile.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import java.text.NumberFormat
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.model.LibraryCounts
import xyz.etkn.mobile.data.settings.HeroMode
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.common.WhileStarted
import xyz.etkn.mobile.ui.common.graphViewModel
import xyz.etkn.mobile.ui.common.windowSizeDp
import xyz.etkn.mobile.ui.components.ErrorState
import xyz.etkn.mobile.ui.components.HeroCarousel
import xyz.etkn.mobile.ui.components.LibraryCard
import xyz.etkn.mobile.ui.components.LoadingState
import xyz.etkn.mobile.ui.components.PosterCard
import xyz.etkn.mobile.ui.components.SquareCard
import xyz.etkn.mobile.ui.components.WideCard
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.components.shelf
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.nav.LocalStartInset
import xyz.etkn.mobile.ui.nav.TopLevel
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.LocalSettings

@Composable
fun HomeScreen() {
    val vm = graphViewModel { HomeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onResume() }
    WhileStarted(vm) { vm.followServer() }

    when (val s = state) {
        Load.Loading -> LoadingState()
        is Load.Failed -> ErrorState(s.message, vm::refresh)
        is Load.Ready -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            HomeContent(s.value, vm)
        }
    }
}

@Composable
private fun HomeContent(data: HomeData, vm: HomeViewModel) {
    val nav = LocalNav.current
    val list = rememberLazyListState()
    val screenHeight = windowSizeDp().height
    val wide = LocalWindowLayout.current.wide
    // Tablets: a cinematic band instead of a near-full-screen poster.
    val heroHeight = if (wide) (screenHeight * 0.58f).coerceIn(380.dp, 560.dp) else (screenHeight * 0.64f).coerceIn(420.dp, 620.dp)
    val revealPx = with(LocalDensity.current) { 96.dp.toPx() }
    // 用户口径（2026-11-23）：轮播图按账号独立设置。
    val context = androidx.compose.ui.platform.LocalContext.current
    val accountKey = (context.graph.sessions.state.collectAsStateWithLifecycle().value as? xyz.etkn.mobile.data.session.SessionState.SignedIn)?.session?.key
    val settingsHero = LocalSettings.current.heroMode
    val heroMode = remember(accountKey, settingsHero) {
        xyz.etkn.mobile.data.settings.HeroModePerAccount.read(context, accountKey, settingsHero)
    }
    // 用户口径（2026-11-23）：媒体库长按菜单只给有管理权限的账号（与「媒体库」页同一套接口）。
    val session = (context.graph.sessions.state.collectAsStateWithLifecycle().value as? xyz.etkn.mobile.data.session.SessionState.SignedIn)?.session
    val canManage = session?.isAdmin == true
    val refreshAccount = session?.canRefreshMetadata == true
    val itemMenu = xyz.etkn.mobile.ui.actions.LocalItemMenu.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val graph = context.graph
    // 用户口径（2026-11-22）：轮播图要保留（上次误删，已恢复）。
    val hasHero = data.hero.isNotEmpty() && heroMode != HeroMode.Off
    // 0 over the untouched hero (bar invisible, poster unobstructed) → 1 after ~96 dp of scrolling.
    val reveal by remember(hasHero) {
        derivedStateOf {
            if (!hasHero || list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / revealPx).coerceIn(0f, 1f)
        }
    }
    val haze = rememberHazeState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().glassSource(haze)) {
            if (hasHero) {
                item(key = "hero", contentType = "hero") {
                    HeroCarousel(
                        items = data.hero,
                        height = heroHeight,
                        autoAdvance = heroMode == HeroMode.Auto,
                        scrollOffsetPx = { if (list.firstVisibleItemIndex == 0) list.firstVisibleItemScrollOffset else 0 },
                        onPlay = { nav.play(it) },
                        onOpen = { nav.open(it) },
                    )
                }
            } else {
                // 用户口径（2026-11-22）：没有轮播图时不要留 64dp 空档，直接贴状态栏起头。
                item(key = "top-space") { Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(12.dp)) }
            }
            // 用户口径（2026-11-23）：媒体库提到最上面（原来在继续观看下面），标题带 > 且可点。
            // 长按封面 → 弹服务端功能菜单（生成封面/提取缩略图/扫描/刷新），非管理员自动不出菜单项。
            shelf("libraries", "媒体库", data.libraries, onMore = { nav.top(TopLevel.Libraries) }, showChevron = true) { library ->
                val anchor = xyz.etkn.mobile.ui.actions.rememberPopupAnchor()
                val actions = buildList {
                    if (canManage) {
                        add(xyz.etkn.mobile.ui.actions.PopupChoice("生成封面", Icons.Rounded.Image) {
                            scope.launch { itemMenu?.notice = runCatching { graph.api.generateLibraryCover(library.id) }
                                .fold({ "「${library.name}」封面已生成" }, { it.message ?: "失败" }) }
                        })
                        if (library.collectionType != "music") {
                            add(xyz.etkn.mobile.ui.actions.PopupChoice("提取缩略图", Icons.Rounded.Slideshow) {
                                scope.launch { itemMenu?.notice = runCatching { graph.api.extractLibraryPreviews(library.id) }
                                    .fold({ "缩略图任务已提交" }, { it.message ?: "失败" }) }
                            })
                        }
                        add(xyz.etkn.mobile.ui.actions.PopupChoice("扫描", Icons.Rounded.Sync) {
                            scope.launch { itemMenu?.notice = runCatching { graph.api.scanLibrary(library.id) }
                                .fold({ "已开始扫描「${library.name}」" }, { it.message ?: "失败" }) }
                        })
                    }
                    if (refreshAccount) {
                        add(xyz.etkn.mobile.ui.actions.PopupChoice("刷新", Icons.Rounded.Refresh) {
                            scope.launch { itemMenu?.notice = runCatching { graph.api.refreshLibrary(library.id, "full") }
                                .fold({ "已刷新「${library.name}」" }, { it.message ?: "失败" }) }
                        })
                    }
                }
                LibraryCard(
                    library,
                    onClick = { nav.openLibrary(library) },
                    onLongClick = if (actions.isNotEmpty()) { { anchor.open(actions) } } else null,
                )
            }
            shelf("resume", "继续观看", data.resume) { item ->
                WideCard(item, onClick = { nav.openTitle(item) }, resume = true, onPlay = { nav.play(item) })
            }
            data.latest.forEach { shelfData ->
                val library = shelfData.library
                // 用户口径（2026-11-23）：去掉「最新 · 」前缀，直接显示库名。
                shelf("latest-${library.id}", library.name, shelfData.items,
                    onMore = { nav.openLibrary(library) }) { item ->
                    PosterCard(item, onClick = { nav.open(item) })
                }
            }
            data.musicLatest?.let { (library, albums) ->
                shelf("music-${library.id}", "新专辑", albums,
                    onMore = { nav.openLibrary(library) }, showChevron = false) { album ->
                    SquareCard(album, onClick = { nav.open(album) })
                }
            }
            data.counts?.let { counts -> item(key = "counts", contentType = "counts") { LibraryTotals(counts) } }
            item(key = "bottom") { Spacer(Modifier.height(LocalBottomInset.current + 16.dp)) }
        }
    }
}

/** What the libraries hold, closing the page: films, series, episodes. */
@Composable
private fun LibraryTotals(counts: LibraryCounts) {
    val number = remember { NumberFormat.getIntegerInstance() }
    Row(
        Modifier.fillMaxWidth().padding(start = LocalStartInset.current).padding(top = Etkn.space.section * 2, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
    ) {
        listOf("电影" to counts.movies, "剧集" to counts.series, "总集数" to counts.episodes).forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(number.format(value), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
