package xyz.etkn.mobile.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.etkn.mobile.AppGraph
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.common.graphViewModel
import xyz.etkn.mobile.ui.components.GlassBackBar
import xyz.etkn.mobile.ui.components.InlineLoading
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.PillToggle
import xyz.etkn.mobile.ui.components.glassBarClearance
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.nav.EpisodesRoute
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.theme.Etkn

class EpisodesViewModel(graph: AppGraph, private val seriesId: String, seasonId: String?) : ViewModel() {
    private val api = graph.api
    val episodes = EpisodeList(api, viewModelScope)
    private val _seasons = MutableStateFlow<List<Item>>(emptyList())
    val seasons: StateFlow<List<Item>> = _seasons.asStateFlow()

    init {
        episodes.select(seriesId, seasonId)
        viewModelScope.launch { _seasons.value = runCatching { api.seasons(seriesId) }.getOrDefault(emptyList()) }
        viewModelScope.launch { graph.itemChanges.collect { if (episodes.contains(it.id)) episodes.refresh() } }
    }

    fun selectSeason(seasonId: String) = episodes.select(seriesId, seasonId)
}

/** Every episode of a season as a grid (the detail page's 全部) in the series' artwork colours, seasons switchable on top. */
@Composable
/**
 * 用户口径（2026-11-24）：剧集全列表页同样不做动态取色（与详情页一致，方案A）。
 * 固定 App 主题色，避免整页重组。
 */
fun EpisodesScreen(route: EpisodesRoute) = EpisodesContent(route)

@Composable
private fun EpisodesContent(route: EpisodesRoute) {
    val vm = graphViewModel(key = route.seriesId) { EpisodesViewModel(it, route.seriesId, route.seasonId) }
    val state by vm.episodes.state.collectAsStateWithLifecycle()
    val seasons by vm.seasons.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val haze = rememberHazeState()
    val grid = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 8 } }
    LaunchedEffect(nearEnd, state.hasMore) { if (nearEnd && state.hasMore) vm.episodes.more() }
    LaunchedEffect(state.seasonId) { grid.scrollToItem(0) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(if (LocalWindowLayout.current.wide) 240.dp else 150.dp),
            contentPadding = PaddingValues(
                start = Etkn.space.screen, end = Etkn.space.screen,
                top = glassBarClearance(), bottom = LocalBottomInset.current + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().glassSource(haze),
        ) {
            if (seasons.size > 1) {
                item(key = "seasons", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(PillGap), modifier = Modifier.padding(bottom = 8.dp)) {
                        items(seasons, key = { it.id }) { season ->
                            PillToggle(checked = season.id == state.seasonId, onClick = { vm.selectSeason(season.id) }) {
                                Text(season.name, maxLines = 1)
                            }
                        }
                    }
                }
            }
            items(state.items, key = { it.id }, contentType = { "episode" }) { episode ->
                EpisodeCard(episode, current = false, onOpen = { nav.open(episode) })
            }
            when {
                state.loading -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { InlineLoading() }
                state.error != null -> item(key = "error", span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        FilledTonalButton(onClick = vm.episodes::retry) { Text("剧集加载失败，重试") }
                    }
                }
            }
        }
        GlassBackBar(route.title, collapsed = true, haze = haze, onBack = nav::back)
    }
}
