package xyz.etkn.mobile.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import xyz.etkn.mobile.data.discover.DiscoverItem
import xyz.etkn.mobile.data.discover.DiscoverKind
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.common.graphViewModel
import xyz.etkn.mobile.ui.components.LoadingState
import xyz.etkn.mobile.ui.components.MessageState
import xyz.etkn.mobile.ui.components.SectionHeader
import xyz.etkn.mobile.ui.components.glass
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.nav.DiscoverDetailRoute
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.nav.LocalTabActive
import xyz.etkn.mobile.ui.screens.discover.DiscoverCard
import xyz.etkn.mobile.ui.screens.library.PosterGrid
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.nav.MusicListRoute
import xyz.etkn.mobile.ui.nav.AppNav
import xyz.etkn.mobile.ui.components.SquareCard
import xyz.etkn.mobile.ui.components.PillToggle
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.LibraryTile
import xyz.etkn.mobile.ui.components.GlassPiece
import xyz.etkn.mobile.data.model.Item
import androidx.compose.foundation.layout.Column
import xyz.etkn.mobile.ui.common.Paged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.width

@Composable
fun SearchScreen() {
    val vm = graphViewModel { SearchViewModel(it) }
    val paged by vm.paged.collectAsStateWithLifecycle()
    val discover by vm.discover.collectAsStateWithLifecycle()
    val scope by vm.scope.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val text = rememberTextFieldState()
    // Standalone field: SearchBar(state, …) hands focus to an expanded overlay we don't use,
    // which dropped the keyboard the moment the field was tapped.
    val barState = rememberSearchBarState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val grid = rememberLazyGridState()
    val haze = rememberHazeState()
    val shape = Corner.pill
    // Field (56) + gap + scope pills (48) + gap, below the status bar.
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 132.dp

    LaunchedEffect(text) {
        snapshotFlow { text.text.toString() }.collect(vm::onQuery)
    }
    // Open with the keyboard up, but only once this is the page shown: as a pre-composed pager
    // neighbour the focus request would scroll the pager here. Leaving the tab drops the keyboard.
    // 用户口径（2026-11-22）：切到搜索页不再自动弹输入法，点了搜索框才弹。
    // 离开标签仍然收起键盘，避免切走时留着焦点。
    val active = LocalTabActive.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active && focused) focusManager.clearFocus()
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassSource(haze)) {
            /**
             * 用户口径（2026-11-23）：库内结果和探索（在线）结果**永远同时展示**，
             * 不再「库里搜不到才出在线」。库内在上（能直接播），在线在下（要订阅/获取）。
             */
            val open: (Item) -> Unit = { keyboard?.hide(); openResult(nav, scope, it) }
            val libEmpty = paged.items.isEmpty() && paged.endReached
            val onlineEmpty = discover == null
            when {
                vm.current.isEmpty() && text.text.isEmpty() ->
                    MessageState(scope.hint, icon = Icons.Rounded.Search)
                paged.items.isEmpty() && paged.loading && onlineEmpty -> LoadingState()
                libEmpty && onlineEmpty -> MessageState(scope.none)
                scope == SearchScope.Video -> SearchResults(
                    books = paged, grid = grid, top = top, online = discover,
                    onOpenLib = open, onLoadMore = vm::loadMore, onRetry = vm::retry,
                    onRetryOnline = vm::retryDiscover,
                    onOpenOnline = { item ->
                        keyboard?.hide()
                        nav.to(DiscoverDetailRoute(item.id, item.kind.wire, item.title))
                    },
                )
                else -> {
                    // 音乐 / MV 仍按原来的网格展示（探索只对影视开）
                    when (scope) {
                        SearchScope.Music -> PosterGrid(
                            paged, grid, vm::loadMore, vm::retry, top = top, cell = 140.dp,
                            card = { SquareCard(it, onClick = { open(it) }, width = null) }, onOpen = open,
                        )
                        SearchScope.MusicVideo -> PosterGrid(
                            paged, grid, vm::loadMore, vm::retry, top = top, cell = 200.dp,
                            card = { LibraryTile(it, onClick = { open(it) }) }, onOpen = open,
                        )
                        else -> Unit
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Etkn.space.screen, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
        Box(Modifier.fillMaxWidth().glass(shape, elevation = 8.dp, state = haze)) {
            SearchBarDefaults.InputField(
                textFieldState = text,
                searchBarState = barState,
                onSearch = { keyboard?.hide() },
                placeholder = { Text(scope.hint) },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (text.text.isNotEmpty()) {
                        IconButton(onClick = { text.clearText(); focus.requestFocus() }) { Icon(Icons.Rounded.Close, "清除") }
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
            )
        }
        // Same glass pill of choices as 探索's type switch.
        GlassPiece(haze, Corner.pill) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(PillGap)) {
                SearchScope.entries.forEach { option ->
                    PillToggle(checked = option == scope, onClick = { vm.setScope(option) }) { Text(option.label) }
                }
            }
        }
        }
    }
}

/**
 * Albums open in the music pages across every music library (they may come from any of them);
 * films, series and music videos open their detail page.
 */
private fun openResult(nav: AppNav, scope: SearchScope, item: Item) {
    if (scope == SearchScope.Music && item.isMusicGroup) {
        nav.to(MusicListRoute(parentId = "", tab = item.kind ?: "albums", key = item.key.orEmpty(), title = item.name))
    } else {
        nav.open(item)
    }
}

/** Library came back empty: TMDb movies and series side by side, each as its own shelf. */
@Composable
private fun DiscoverFallback(
    results: Map<DiscoverKind, Load<List<DiscoverItem>>>,
    top: Dp,
    onRetry: () -> Unit,
    onOpen: (DiscoverItem) -> Unit,
) {
    val allEmpty = results.values.all { it is Load.Ready && it.value.isEmpty() }
    if (allEmpty) {
        MessageState("没有找到相关影视")
        return
    }
    val width = if (LocalWindowLayout.current.wide) Etkn.space.posterWidthWide else Etkn.space.posterWidth
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = top, bottom = LocalBottomInset.current + 16.dp)) {
        DiscoverKind.entries.forEach { kind ->
            val load = results[kind] ?: return@forEach
            if (load is Load.Ready && load.value.isEmpty()) return@forEach
            item(key = "head-${kind.wire}", contentType = "head") { SectionHeader("探索 · ${kind.label}") }
            item(key = "row-${kind.wire}", contentType = "row") {
                when (load) {
                    Load.Loading -> Box(Modifier.fillMaxWidth().height(width * 1.6f), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    is Load.Failed -> Row(
                        Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(load.message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                    is Load.Ready -> LazyRow(
                        contentPadding = PaddingValues(horizontal = Etkn.space.screen),
                        horizontalArrangement = Arrangement.spacedBy(Etkn.space.item),
                        modifier = Modifier.padding(top = 4.dp, bottom = Etkn.space.section),
                    ) {
                        items(load.value, key = { it.id }) { item -> DiscoverCard(item, onClick = { onOpen(item) }, width = width) }
                    }
                }
            }
        }
    }
}

/**
 * 用户口径（2026-11-23）：影视搜索结果的统一展示 ——
 * 上面「媒体库」横向一排（能直接播），下面「在线资源」按电影/剧集分组横向一排。
 * 两侧互不遮挡，谁有结果谁显示。
 */
@Composable
private fun SearchResults(
    books: Paged,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    top: Dp,
    online: Map<DiscoverKind, Load<List<DiscoverItem>>>?,
    onOpenLib: (Item) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRetryOnline: () -> Unit,
    onOpenOnline: (DiscoverItem) -> Unit,
) {
    val width = if (LocalWindowLayout.current.wide) Etkn.space.posterWidthWide else Etkn.space.posterWidth
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = top, bottom = LocalBottomInset.current + 16.dp),
    ) {
        if (books.items.isNotEmpty()) {
            item(key = "lib-h", contentType = "h") { SectionHeader("媒体库") }
            item(key = "lib-row", contentType = "row") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Etkn.space.screen),
                    horizontalArrangement = Arrangement.spacedBy(Etkn.space.item),
                    modifier = Modifier.padding(top = 4.dp, bottom = Etkn.space.section),
                ) {
                    items(books.items, key = { it.id }) { entry ->
                        PosterCard(entry, onClick = { onOpenLib(entry) }, width = width)
                    }
                }
            }
        }
        online?.forEach { (kind, load) ->
            if (load is Load.Ready && load.value.isEmpty()) return@forEach
            item(key = "on-h-${kind.wire}", contentType = "h") { SectionHeader("在线 · ${kind.label}") }
            item(key = "on-r-${kind.wire}", contentType = "row") {
                when (load) {
                    Load.Loading -> Box(
                        Modifier.fillMaxWidth().height(width * 1.6f),
                        contentAlignment = Alignment.Center,
                    ) { LoadingIndicator() }
                    is Load.Failed -> Row(
                        Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(load.message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = onRetryOnline) { Text("重试") }
                    }
                    is Load.Ready -> LazyRow(
                        contentPadding = PaddingValues(horizontal = Etkn.space.screen),
                        horizontalArrangement = Arrangement.spacedBy(Etkn.space.item),
                        modifier = Modifier.padding(top = 4.dp, bottom = Etkn.space.section),
                    ) {
                        // 用户口径（2026-11-23）：在线资源不显示封面，只给文字行。
                        items(load.value, key = { it.id }) { item ->
                            OnlineTextRow(item) { onOpenOnline(item) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 用户口径（2026-11-23）：在线（探索）资源不显示封面图，只显示标题 + 年份 + 类型。
 * 点一下进探索详情页（那儿才有订阅 / 云资源按钮）。
 */
@Composable
private fun OnlineTextRow(item: DiscoverItem, onClick: () -> Unit) {
    Surface(
        shape = Corner.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.width(190.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                item.year?.takeIf { it > 0 }?.toString(),
                item.kind.label,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}
