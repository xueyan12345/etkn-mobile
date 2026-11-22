package xyz.etkn.mobile.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.api.SortKey
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.actions.LocalItemMenu
import xyz.etkn.mobile.ui.actions.PopupChoice
import xyz.etkn.mobile.ui.actions.RefreshDialog
import xyz.etkn.mobile.ui.actions.rememberPopupAnchor
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.common.Paged
import xyz.etkn.mobile.ui.common.graphViewModel
import xyz.etkn.mobile.ui.common.userMessage
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.ArtworkBackButton
import xyz.etkn.mobile.ui.components.CardShape
import xyz.etkn.mobile.ui.components.ErrorState
import xyz.etkn.mobile.ui.components.InlineLoading
import xyz.etkn.mobile.ui.components.LibraryTile
import xyz.etkn.mobile.ui.components.LoadingState
import xyz.etkn.mobile.ui.components.MessageState
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.PillToggle
import xyz.etkn.mobile.ui.components.PosterCard
import xyz.etkn.mobile.ui.components.glass
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.components.images
import xyz.etkn.mobile.ui.components.px
import xyz.etkn.mobile.ui.nav.FacetRoute
import xyz.etkn.mobile.ui.nav.LibraryRoute
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.Corner

@Composable
fun LibrariesScreen() {
    val vm = graphViewModel { LibrariesViewModel(it) }
    val graph = LocalContext.current.graph
    val session = graph.sessions.current
    val admin = session?.isAdmin == true
    val canRefresh = admin || session?.canRefreshMetadata == true
    val scope = rememberCoroutineScope()
    val menu = LocalItemMenu.current
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    var refreshing by remember { mutableStateOf<Item?>(null) }

    /** Runs one maintenance call and reports it in the glass notice pill. */
    fun submit(done: String, block: suspend () -> Unit) {
        scope.launch { menu?.notice = runCatching { block() }.fold({ done }, { it.userMessage() }) }
    }

    /** The Web library card menu: 生成封面 / 提取缩略图 / 扫描 for administrators, 刷新 for refresh accounts too. */
    fun choices(library: Item): List<PopupChoice> = buildList {
        if (!LIBRARY_ID.matches(library.id)) return@buildList
        if (admin) {
            add(PopupChoice("生成封面", Icons.Rounded.Image) { submit("「${library.name}」封面已生成") { graph.api.generateLibraryCover(library.id); vm.load() } })
            if (library.collectionType != "music") {
                add(PopupChoice("提取缩略图", Icons.Rounded.Slideshow) { submit("缩略图任务已提交，已有缩略图自动跳过") { graph.api.extractLibraryPreviews(library.id) } })
            }
            if (library.sourceType in SCANNABLE) {
                add(PopupChoice("扫描", Icons.Rounded.Sync) { submit("已开始扫描「${library.name}」") { graph.api.scanLibrary(library.id) } })
            }
        }
        if (canRefresh && library.sourceType != "personal_recommendation") add(PopupChoice("刷新", Icons.Rounded.Refresh) { refreshing = library })
    }

    when (val s = state) {
        Load.Loading -> LoadingState()
        is Load.Failed -> ErrorState(s.message, vm::load)
        is Load.Ready -> LazyVerticalGrid(
            columns = GridCells.Adaptive(if (LocalWindowLayout.current.wide) 220.dp else 156.dp),
            contentPadding = PaddingValues(start = Etkn.space.screen, end = Etkn.space.screen, bottom = LocalBottomInset.current + 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "title", span = { GridItemSpan(maxLineSpan) }, contentType = "title") {
                LargeTitle("媒体库", horizontal = 0.dp)
            }
            items(s.value, key = { it.id }, contentType = { "library" }) { library ->
                val anchor = rememberPopupAnchor()
                val actions = choices(library)
                LibraryTile(
                    library,
                    onClick = { nav.openLibrary(library) },
                    onLongClick = if (actions.isNotEmpty()) { { anchor.open(actions) } } else null,
                    modifier = anchor.modifier,
                )
            }
        }
    }

    refreshing?.let { library ->
        RefreshDialog(onDismiss = { refreshing = null }) { action ->
            refreshing = null
            scope.launch {
                menu?.notice = runCatching { graph.api.refreshLibrary(library.id, action) }
                    .fold({ if (it) "已提交刷新「${library.name}」" else "「${library.name}」没有需要刷新的条目" }, { it.userMessage() })
            }
        }
    }
}

/** Page title under the status bar (no bottom inset: callers add their own content padding). */
@Composable
fun LargeTitle(title: String, horizontal: Dp = Etkn.space.screen) {
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Text(
        title,
        style = MaterialTheme.typography.headlineLarge,
        modifier = Modifier.padding(start = horizontal, end = horizontal, top = status + 16.dp, bottom = 4.dp),
    )
}

@Composable
fun LibraryScreen(route: LibraryRoute) {
    val vm = graphViewModel(key = route.id) { LibraryViewModel(it, route.id, route.collectionType) }
    val paged by vm.paged.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val facets = view.facet != null
    PosterGridScaffold(
        title = route.name,
        paged = paged,
        onBack = nav::back,
        onLoadMore = vm::loadMore,
        onRetry = vm::retry,
        onOpen = { item ->
            if (item.isFacet) nav.to(FacetRoute(route.id, item.kind.orEmpty(), item.name)) else nav.open(item)
        },
        cell = if (facets) 156.dp else null,
        card = if (facets) { item -> FacetCard(route.id, item, vm) } else null,
        header = if (vm.isFolderView) null else {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.views.size > 1) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = HEADER_GAP),
                            horizontalArrangement = Arrangement.spacedBy(PillGap),
                        ) {
                            vm.views.forEach { option ->
                                PillToggle(checked = option == view, onClick = { vm.setView(option) }) {
                                    Text(option.label, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (view == LibraryView.All) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = HEADER_GAP),
                            horizontalArrangement = Arrangement.spacedBy(PillGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SortMenu(query.sort, query.descending, vm::setSort)
                            PillToggle(
                                checked = query.favoritesOnly,
                                onClick = vm::toggleFavorites,
                                icon = if (query.favoritesOnly) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            ) { Text("收藏") }
                            if (paged.total >= 0) {
                                Text("${paged.total} 项", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * One category card (genre, tag, network, company): its cover when the server has one, else the
 * name on a tonal tile. Long press offers 生成封面 to accounts allowed to make covers.
 */
@Composable
private fun FacetCard(libraryId: String, card: Item, vm: LibraryViewModel) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val menu = LocalItemMenu.current
    val anchor = rememberPopupAnchor()
    val url = images().facet(libraryId, card, 360.dp.px())
    Column(
        Modifier.fillMaxWidth().then(anchor.modifier).clip(CardShape).combinedClickable(
            onClick = { nav.to(FacetRoute(libraryId, card.kind.orEmpty(), card.name)) },
            onLongClick = if (vm.canMakeCovers) {
                {
                    anchor.open(
                        listOf(
                            PopupChoice(if (url == null) "生成封面" else "重新生成封面", Icons.Rounded.Image) {
                                scope.launch {
                                    menu?.notice = vm.makeCover(card).fold({ "「${card.name}」封面已更新" }, { it.userMessage() })
                                }
                            },
                        ),
                    )
                }
            } else null,
        ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(CardShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            if (url != null) {
                Artwork(url, Modifier.fillMaxSize(), card.name, contentScale = if (card.kind == "Networks" || card.kind == "Companies") ContentScale.Fit else ContentScale.Crop, background = Color.Transparent)
            } else {
                Text(card.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
            }
        }
        if (url != null) {
            Text(card.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp))
        }
    }
}

/** Titles of one category card. */
@Composable
fun FacetScreen(route: FacetRoute) {
    val vm = graphViewModel(key = route.libraryId + route.kind + route.value) { FacetViewModel(it, route.libraryId, route.kind, route.value) }
    val paged by vm.paged.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    PosterGridScaffold(
        title = route.value,
        paged = paged,
        onBack = nav::back,
        onLoadMore = vm::loadMore,
        onRetry = vm::retry,
        onOpen = nav::open,
        header = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SortMenu(sort.first, sort.second, vm::setSort)
                if (paged.total >= 0) {
                    Text("${paged.total} 项", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

@Composable
private fun SortMenu(sort: SortKey, descending: Boolean, onSelect: (SortKey) -> Unit) {
    val anchor = rememberPopupAnchor()
    val order = if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward
    Box {
        FilledTonalButton(
            onClick = {
                anchor.open(
                    SortKey.entries.map { key ->
                        PopupChoice(
                            key.label,
                            selected = key == sort,
                            trailing = order.takeIf { key == sort && key != SortKey.Random },
                            onClick = { onSelect(key) },
                        )
                    },
                )
            },
            modifier = anchor.modifier,
        ) {
            Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text(sort.label, style = MaterialTheme.typography.labelLarge)
            if (sort != SortKey.Random) {
                Icon(if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, null, Modifier.padding(start = 4.dp).size(16.dp))
            }
        }
    }
}

@Composable
fun FavoritesScreen() {
    val vm = graphViewModel { FavoritesViewModel(it) }
    val paged by vm.paged.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    PosterGridScaffold("我的收藏", paged, nav::back, vm::loadMore, vm::retry, nav::open)
}

/** Floating glass bar (title + optional filters) over an adaptive poster grid with infinite scroll. */
@Composable
fun PosterGridScaffold(
    title: String,
    paged: Paged<Item>,
    onBack: (() -> Unit)?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (Item) -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    grid: LazyGridState = rememberLazyGridState(),
    cell: Dp? = null,
    card: (@Composable (Item) -> Unit)? = null,
) {
    val haze = rememberHazeState()
    val density = LocalDensity.current
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var barHeight by remember { mutableStateOf(64.dp) }
    val top = status + barHeight + 12.dp
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().glassSource(haze)) {
            when {
                paged.items.isEmpty() && paged.loading -> LoadingState()
                paged.items.isEmpty() && paged.error != null -> ErrorState(paged.error, onRetry)
                paged.items.isEmpty() && paged.endReached -> MessageState("这里还没有内容")
                else -> PosterGrid(paged, grid, onLoadMore, onRetry, top = top, cell = cell, card = card, onOpen = onOpen)
            }
        }
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Etkn.space.screen, vertical = 6.dp)
                // A bare bar is a pill like every other; with filter rows it grows into a panel.
                .glass(if (header != null) Corner.panel else Corner.pill, elevation = 8.dp, state = haze)
                .onSizeChanged { barHeight = with(density) { it.height.toDp() } },
        ) {
            Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                // Same geometry as GlassBackBar so every secondary page's bar lines up.
                if (onBack != null) ArtworkBackButton(onBack, Modifier.padding(start = 2.dp), onGlass = true)
                else Spacer(Modifier.width(8.dp))
                Text(
                    title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
            }
            if (header != null) Box(Modifier.padding(bottom = HEADER_GAP)) { header() }
        }
    }
}

/** The filter rows' inset inside the bar: pills this far from the panel's edge are concentric with it. */
private val HEADER_GAP = 8.dp

@Composable
fun PosterGrid(
    paged: Paged<Item>,
    grid: LazyGridState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    top: Dp = 8.dp,
    cell: Dp? = null,
    card: (@Composable (Item) -> Unit)? = null,
    onOpen: (Item) -> Unit,
) {
    val shouldLoad by remember(paged) {
        derivedStateOf {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            paged.canLoadMore && last >= paged.items.size - 18
        }
    }
    LaunchedEffect(grid, paged) {
        snapshotFlow { shouldLoad }.collect { if (it) onLoadMore() }
    }
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Adaptive(cell ?: if (LocalWindowLayout.current.wide) 140.dp else 108.dp),
        contentPadding = PaddingValues(start = Etkn.space.screen, end = Etkn.space.screen, top = top, bottom = LocalBottomInset.current),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(paged.items, key = { it.id }, contentType = { "poster" }) { item ->
            // Removals and in-place updates (favourite, delete) slide neighbours into place.
            Box(Modifier.animateItem()) {
                if (card != null) card(item)
                else if (item.type == "CollectionFolder" || item.collectionType != null && item.type != "BoxSet") {
                    LibraryTile(item, onClick = { onOpen(item) })
                } else {
                    PosterCard(item, onClick = { onOpen(item) }, width = null)
                }
            }
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

/** Library sources the server can re-scan (TV shows 扫描 for the same ones). */
private val SCANNABLE = setOf("path", "list")
private val LIBRARY_ID = Regex("library-\\d+")
