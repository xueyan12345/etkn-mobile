package xyz.etkn.mobile.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import xyz.etkn.mobile.data.discover.DiscoverItem
import xyz.etkn.mobile.data.discover.DiscoverKind
import xyz.etkn.mobile.data.discover.DiscoverStatus
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.common.Paged
import xyz.etkn.mobile.ui.common.graphViewModel
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.CardShape
import xyz.etkn.mobile.ui.components.ErrorState
import xyz.etkn.mobile.ui.components.GlassPiece
import xyz.etkn.mobile.ui.components.InlineLoading
import xyz.etkn.mobile.ui.components.LoadingState
import xyz.etkn.mobile.ui.components.MessageState
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.PillToggle
import xyz.etkn.mobile.ui.components.formatRating
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.components.images
import xyz.etkn.mobile.ui.nav.DiscoverDetailRoute
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.Corner
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun DiscoverScreen() {
    val vm = graphViewModel { DiscoverViewModel(it) }
    val kind by vm.kind.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val paged by vm.paged.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val haze = rememberHazeState()
    /**
     * 用户口径（2026-11-24）**修「探索页点进详情再返回，滚动位置回到顶部」**。
     *
     * 原来用 rememberLazyGridState()：状态只活在当前组合里，
     * 导航去详情页再返回时页面被重新组合 → 状态重建 → 位置归零。
     *
     * 改成 rememberSaveable + LazyGridState.Saver：
     * 首项 index 和偏移量会被存进 SavedStateRegistry，
     * 返回时原样恢复，停在你刚才翻到的位置。
     */
    /**
     * LazyGridState 自带 Saver：用 rememberSaveable 直接构造，
     * 首项 index 和偏移量会进 SavedStateRegistry，返回时原样恢复。
     * 注意不能在里面调 rememberLazyGridState()（那是 @Composable，saver lambda 不是组合上下文）。
     */
    val grid = rememberSaveable(saver = androidx.compose.foundation.lazy.grid.LazyGridState.Saver) {
        androidx.compose.foundation.lazy.grid.LazyGridState()
    }
    val density = LocalDensity.current
    // Measured with the status bar and its padding: the grid starts right below the bar.
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var headerHeight by remember { mutableStateOf(status + 60.dp) }
    var searching by remember { mutableStateOf(filter.query.isNotEmpty()) }
    var filtering by remember { mutableStateOf(false) }
    val text = rememberTextFieldState(filter.query)

    LaunchedEffect(kind) { grid.scrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassSource(haze)) {
            when {
                paged.items.isEmpty() && paged.loading -> LoadingState()
                paged.items.isEmpty() && paged.error != null -> ErrorState(paged.error!!, vm::retry)
                paged.items.isEmpty() && paged.endReached -> MessageState("暂无匹配结果，可调整筛选或搜索词", icon = Icons.Rounded.Search)
                else -> DiscoverGrid(paged, grid, top = headerHeight, onLoadMore = vm::loadMore, onRetry = vm::retry) { item ->
                    nav.to(DiscoverDetailRoute(item.id, item.kind.wire, item.title))
                }
            }
        }
        DiscoverTopBar(
            kind = kind,
            onKind = vm::setKind,
            filters = filter.active,
            onFilter = { filtering = true },
            searching = searching,
            onToggleSearch = {
                searching = !searching
                if (!searching && filter.query.isNotEmpty()) {
                    text.setTextAndPlaceCursorAtEnd("")
                    vm.setFilter(filter.copy(query = ""))
                }
            },
            text = text,
            onSearch = { vm.setFilter(filter.copy(query = it.trim())) },
            haze = haze,
            modifier = Modifier.onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
        )
    }

    if (filtering) {
        FilterSheet(filter, options, vm::findPeople, onDismiss = { filtering = false }) { next ->
            filtering = false
            vm.setFilter(next.copy(query = filter.query))
        }
    }
}

/** Split glass bar like 首页: 电影 / 剧集 on the left, search and filters on the right, the search field below. */
@Composable
private fun DiscoverTopBar(
    kind: DiscoverKind,
    onKind: (DiscoverKind) -> Unit,
    filters: Int,
    onFilter: () -> Unit,
    searching: Boolean,
    onToggleSearch: () -> Unit,
    text: TextFieldState,
    onSearch: (String) -> Unit,
    haze: HazeState,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Etkn.space.screen, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 用户口径（2026-11-23）：顶部这排按钮太大，按整体协调感缩小一档（40dp 高度、图标 20dp）。
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                GlassPiece(haze, Corner.pill) {
                    Row(Modifier.padding(horizontal = 3.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(PillGap)) {
                        DiscoverKind.entries.forEach { option ->
                            PillToggle(checked = option == kind, onClick = { onKind(option) }) {
                                Text(option.label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            GlassPiece(haze, CircleShape) {
                IconButton(onClick = onToggleSearch, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (searching) Icons.Rounded.Close else Icons.Rounded.Search,
                        if (searching) "关闭搜索" else "搜索",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            GlassPiece(haze, CircleShape) {
                IconButton(onClick = onFilter, modifier = Modifier.size(40.dp)) {
                    BadgedBox(badge = { if (filters > 0) Badge { Text(filters.toString()) } }) {
                        Icon(Icons.Rounded.Tune, "筛选", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        if (searching) {
            GlassPiece(haze, Corner.pill, Modifier.fillMaxWidth()) {
                SearchBarDefaults.InputField(
                    textFieldState = text,
                    searchBarState = rememberSearchBarState(),
                    onSearch = { keyboard?.hide(); onSearch(it) },
                    placeholder = { Text("搜索 TMDb 片名") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DiscoverGrid(
    paged: Paged<DiscoverItem>,
    grid: LazyGridState,
    top: Dp,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (DiscoverItem) -> Unit,
) {
    val shouldLoad by remember(paged) {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            paged.canLoadMore && last >= paged.items.size - 18
        }
    }
    LaunchedEffect(grid, paged) { snapshotFlow { shouldLoad }.collect { if (it) onLoadMore() } }
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Adaptive(if (LocalWindowLayout.current.wide) 140.dp else 108.dp),
        contentPadding = PaddingValues(start = Etkn.space.screen, end = Etkn.space.screen, top = top, bottom = LocalBottomInset.current),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(paged.items, key = { it.kind.wire + it.id }, contentType = { "discover" }) { item ->
            DiscoverCard(item, onClick = { onOpen(item) })
        }
        if (paged.loading) item(span = { GridItemSpan(maxLineSpan) }) { InlineLoading() }
        if (paged.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    FilledTonalButton(onClick = onRetry) { Text("加载失败，重试") }
                }
            }
        }
    }
}

/** 2:3 TMDb poster with status ribbon and rating, title and year below (mirrors PosterCard). */
@Composable
fun DiscoverCard(item: DiscoverItem, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = null) {
    val url = images().tmdb(item.posterPath, "w342")
    Column(
        modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).clip(CardShape).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(CardShape)) {
            Artwork(url, Modifier.fillMaxSize(), item.title, placeholder = Icons.Rounded.Movie)
            item.status?.let { StatusPill(it, Modifier.align(Alignment.TopStart).padding(6.dp)) }
            formatRating(item.vote)?.let { rating ->
                Row(
                    Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.55f), Corner.small)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Star, null, Modifier.size(12.dp), tint = Etkn.tokens.rating)
                    Spacer(Modifier.width(2.dp))
                    Text(rating, style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
        Text(
            item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
        if (item.year.isNotEmpty()) {
            Text(item.year, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

@Composable
fun StatusPill(status: DiscoverStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        DiscoverStatus.InLibrary -> scheme.primary to scheme.onPrimary
        DiscoverStatus.Subscribed, DiscoverStatus.Wanted -> scheme.tertiary to scheme.onTertiary
        else -> scheme.surfaceContainerHighest to scheme.onSurface
    }
    Surface(modifier, shape = Corner.small, color = container, contentColor = content) {
        Text(status.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
