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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.rememberHazeState
import java.text.NumberFormat
import xyz.etkn.mobile.data.model.LibraryCounts
import xyz.etkn.mobile.data.session.SessionState
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.actions.PopupChoice
import xyz.etkn.mobile.ui.actions.rememberPopupAnchor
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.common.WhileStarted
import xyz.etkn.mobile.ui.common.graphViewModel
import xyz.etkn.mobile.ui.common.windowSizeDp
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.ErrorState
import xyz.etkn.mobile.ui.components.GlassPiece
import xyz.etkn.mobile.ui.components.LibraryCard
import xyz.etkn.mobile.ui.components.LoadingState
import xyz.etkn.mobile.ui.components.PosterCard
import xyz.etkn.mobile.ui.components.SquareCard
import xyz.etkn.mobile.ui.components.WideCard
import xyz.etkn.mobile.ui.components.glass
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.components.images
import xyz.etkn.mobile.ui.components.shelf
import xyz.etkn.mobile.ui.nav.FavoritesRoute
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.nav.LocalStartInset
import xyz.etkn.mobile.ui.nav.TopLevel
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.Corner

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
    val revealPx = with(LocalDensity.current) { 96.dp.toPx() }
    // 用户口径（2026-11-22）：首页顶部那块大图轮播整块不要了，改成普通标题起头。
    // 保留 reveal 机制：顶栏毛玻璃从 0 渐显到 1，只是不再有 hero 可滚。
    val hasHero = false
    val reveal by remember { derivedStateOf { 1f } }
    val haze = rememberHazeState()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().glassSource(haze)) {
            item(key = "top-space") { Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(64.dp)) }
            shelf("resume", "继续观看", data.resume) { item ->
                WideCard(item, onClick = { nav.openTitle(item) }, resume = true, onPlay = { nav.play(item) })
            }
            shelf("libraries", "媒体库", data.libraries, onMore = { nav.top(TopLevel.Libraries) }) { library ->
                LibraryCard(library, onClick = { nav.openLibrary(library) })
            }
            data.latest.forEach { shelfData ->
                val library = shelfData.library
                shelf("latest-${library.id}", "最新 · ${library.name}", shelfData.items, onMore = {
                    nav.openLibrary(library)
                }) { item -> PosterCard(item, onClick = { nav.open(item) }) }
            }
            data.musicLatest?.let { (library, albums) ->
                shelf("music-${library.id}", "新专辑", albums, onMore = { nav.openLibrary(library) }) { album ->
                    SquareCard(album, onClick = { nav.open(album) })
                }
            }
            data.counts?.let { counts -> item(key = "counts", contentType = "counts") { LibraryTotals(counts) } }
            item(key = "bottom") { Spacer(Modifier.height(LocalBottomInset.current + 16.dp)) }
        }
        HomeTopBar(reveal = { reveal }, haze = haze, onSearch = { nav.top(TopLevel.Search) }, onFavorites = { nav.to(FavoritesRoute) }, onMe = { nav.top(TopLevel.Me) })
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

/**
 * Three separate glass pieces — server name, search, avatar — instead of one full-width bar. Over
 * the untouched hero they float bare on the poster; their glass fades in as the page scrolls
 * ([reveal] 0 → 1) and stays once the hero is gone.
 */
@Composable
private fun HomeTopBar(reveal: () -> Float, haze: HazeState, onSearch: () -> Unit, onFavorites: () -> Unit, onMe: () -> Unit) {
    val graph = LocalContext.current.graph
    val session = (graph.sessions.state.collectAsStateWithLifecycle().value as? SessionState.SignedIn)?.session ?: return
    val urls = images()
    val fraction = reveal()
    val content = lerp(Color.White, MaterialTheme.colorScheme.onSurface, fraction)
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Etkn.space.screen, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The name takes the free width (ellipsized when long); search and avatar stay flush right.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            GlassPiece(haze, Corner.pill, fraction = fraction) {
                Text(
                    session.serverName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = content,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 18.dp).heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically),
                )
            }
        }
        GlassPiece(haze, CircleShape, fraction = fraction) {
            IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, "搜索", tint = content) }
        }
        val account = rememberPopupAnchor()
        GlassPiece(haze, CircleShape, account.modifier, fraction) {
            IconButton(
                onClick = {
                    account.open(
                        listOf(
                            PopupChoice("我的收藏", Icons.Rounded.Favorite, onClick = onFavorites),
                            PopupChoice("账号与设置", Icons.Rounded.Settings, onClick = onMe),
                        ),
                    )
                },
            ) {
                Artwork(
                    urls.user(tag = session.imageTag),
                    Modifier.size(32.dp).clip(CircleShape),
                    session.userName,
                    placeholder = Icons.Rounded.Person,
                )
            }
        }
    }
}
