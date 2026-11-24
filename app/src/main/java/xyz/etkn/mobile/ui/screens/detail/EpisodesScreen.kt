package xyz.etkn.mobile.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import xyz.etkn.mobile.ui.components.images
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.px
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip

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
    val grid = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= grid.layoutInfo.totalItemsCount - 8 } }
    LaunchedEffect(nearEnd, state.hasMore) { if (nearEnd && state.hasMore) vm.episodes.more() }
    LaunchedEffect(state.seasonId) { grid.scrollToItem(0) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        /**
         * 用户口径（2026-11-24）**季详情页照参考图重做**。
         *
         * 参考图结构：
         *  · 顶部：全宽头图（该季背景图）+ 剧名 + 季名 + 简介 + 播放按钮
         *  · 下面：竖向剧集列表，每集是「左 16:9 缩略图 + 右三行文字」
         *      第1行 集号+集名   第2行 时长（浅灰，格式「25min」）  第3行 简介（2-3行截断）
         *
         * 之前是个纯网格（只有海报 + 集号），没有头图、没有时长、没有简介。
         */
        LazyColumn(
            state = grid,
            contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize().glassSource(haze),
        ) {
            // ============ 顶部头图区 ============
            item(key = "head") {
                val urls = images()
                val season = seasons.firstOrNull { it.id == state.seasonId }
                val host = season ?: seasons.firstOrNull()
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val boxW = maxWidth
                    val h = (boxW * 0.56f).coerceIn(200.dp, 340.dp)
                    Box(Modifier.fillMaxWidth().height(h)) {
                        Artwork(
                            host?.let { urls.backdrop(it, boxW.px()) ?: urls.poster(it, boxW.px()) },
                            Modifier.fillMaxSize(),
                            route.title,
                            placeholder = Icons.Rounded.Tv,
                        )
                        Box(Modifier.fillMaxSize().background(Etkn.tokens.detailScrim))
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = Etkn.space.screen)
                                .padding(bottom = 12.dp),
                        ) {
                            Text(
                                route.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                host?.name ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
            // ============ 播放按钮 ============
            item(key = "play") {
                val first = state.items.firstOrNull()
                Box(Modifier.padding(horizontal = Etkn.space.screen).padding(top = 12.dp, bottom = 8.dp)) {
                    FilledTonalButton(
                        onClick = { first?.let { nav.play(it, resume = true) } },
                        enabled = first != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("播放${state.items.firstOrNull()?.episodeLabel ?: ""}")
                    }
                }
            }
            // ============ 季切换（多于 1 季时） ============
            if (seasons.size > 1) {
                item(key = "seasons") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Etkn.space.screen),
                        horizontalArrangement = Arrangement.spacedBy(PillGap),
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        items(seasons, key = { it.id }) { season ->
                            PillToggle(checked = season.id == state.seasonId, onClick = { vm.selectSeason(season.id) }) {
                                Text(season.name, maxLines = 1)
                            }
                        }
                    }
                }
            }
            // ============ 竖向剧集列表 ============
            items(state.items, key = { it.id }, contentType = { "episode" }) { episode ->
                SeasonEpisodeRow(episode, onOpen = { nav.open(episode) })
            }
            when {
                state.loading -> item(key = "loading") { InlineLoading() }
                state.error != null -> item(key = "error") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        FilledTonalButton(onClick = vm.episodes::retry) { Text("剧集加载失败，重试") }
                    }
                }
            }
        }
        GlassBackBar(route.title, collapsed = true, haze = haze, onBack = nav::back)
    }
}

/**
 * 用户口径（2026-11-24）**季详情页的一集（照参考图）**。
 *
 * 布局：左 16:9 缩略图 + 右三行文字
 *  · 第1行：集号 + 集名（如「1.致两千年后的你」）
 *  · 第2行：时长（浅灰小字，格式「25min」）
 *  · 第3行：简介，2-3 行截断
 */
@Composable
private fun SeasonEpisodeRow(episode: Item, onOpen: () -> Unit) {
    val urls = images()
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Etkn.space.screen, vertical = 8.dp),
    ) {
        Artwork(
            urls.landscape(episode, 360),
            Modifier.width(140.dp).aspectRatio(16f / 9f).clip(Corner.card),
            episode.name,
            placeholder = Icons.Rounded.Tv,
            contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // 集号 + 集名
            val title = listOfNotNull(
                episode.indexNumber?.let { "$it." },
                episode.name,
            ).joinToString(" ").ifBlank { episode.name }
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 时长
            formatRuntime(episode.runtimeMs)?.let {
                Text(
                    it.replace("小时", "h ").replace("分", "min ").replace("秒", "s"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // 简介
            episode.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
