package xyz.etkn.mobile.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FirstPage
import androidx.compose.material.icons.rounded.LastPage
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.ui.actions.PopupChoice
import xyz.etkn.mobile.ui.actions.rememberItemPress
import xyz.etkn.mobile.ui.actions.rememberPopupAnchor
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.CardShape
import xyz.etkn.mobile.ui.components.InlineLoading
import xyz.etkn.mobile.ui.components.images
import xyz.etkn.mobile.ui.components.px
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.PillToggle

/** Episodes per range chip. */
private const val RANGE = 20

/**
 * One season as a horizontal row: season picker, jump to first / last, 全部 for the grid, range
 * chips (1-20, 21-40, …) that follow the scroll and jump to their range, and 16:9 cards. Opens
 * at [EpisodesState.anchorId]; long lists load on demand ([EpisodeList.loadThrough]).
 */
@Composable
internal fun EpisodeShelf(
    state: EpisodesState,
    seasons: List<Item>,
    currentId: String?,
    list: EpisodeList,
    onSeason: (String) -> Unit,
    onOpen: (Item) -> Unit,
    onAll: () -> Unit,
) {
    val row = rememberLazyListState()
    val chips = rememberLazyListState()
    /**
     * 用户口径（2026-11-23）：分集卡尺寸**和下面的章节卡保持一致**。
     * 用户口径（2026-11-24）：两者一起从 180dp 缩到 160dp。
     */
    val cardWidth = 160.dp
    // Position to scroll to once it is loaded (range chips, jump to last).
    var pending by remember { mutableIntStateOf(-1) }
    val ranges = if (state.total > RANGE + RANGE / 2) (state.total + RANGE - 1) / RANGE else 0
    val range by remember { derivedStateOf { row.firstVisibleItemIndex / RANGE } }

    fun jump(index: Int) {
        pending = index
        list.loadThrough(index)
    }

    LaunchedEffect(state.seasonId, state.anchorIndex >= 0) {
        if (state.anchorIndex >= 0) row.scrollToItem(state.anchorIndex)
    }
    /**
     * 用户口径（2026-11-23，照抄参考视频）**切集时选集要自动跟随**。
     *
     * 参考应用的行为（逐集从 1 点到 25 实测）：
     *  · 目标集在当前可见的一屏内 → 列表横向位置**不动**，只移高亮；
     *  · 目标集跨出可见范围 → 自动横向滚动，把目标集带到可点位置；
     *  · 而且保证**下一集也在手边**，可以连续点下去不停。
     *
     * 之前完全没有这个逻辑：切到后面的集，当前集早就滚出屏幕，用户得自己找。
     */
    LaunchedEffect(currentId, state.items.size) {
        val index = state.items.indexOfFirst { it.id == currentId }
        if (index < 0) return@LaunchedEffect
        val info = row.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull()?.index ?: return@LaunchedEffect
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        when {
            // 已经在视野里，而且下一集也露得出来 → 一律不动（参考应用就是这个手感）
            index in first..(last - 1) -> Unit
            // 在视野里但正好是最后一格 → 往后挪一格，让下一集露出来
            index == last -> row.animateScrollToItem(index)
            // 完全在视野外 → 滚到它刚好出现在起始位置
            else -> row.animateScrollToItem(index)
        }
    }
    LaunchedEffect(pending, state.items.size) {
        if (pending in state.items.indices) {
            row.animateScrollToItem(pending)
            pending = -1
        }
    }
    LaunchedEffect(range, ranges) { if (ranges > 0) chips.animateScrollToItem((range - 1).coerceAtLeast(0)) }
    val nearEnd by remember { derivedStateOf { (row.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= row.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, state.hasMore) { if (nearEnd && state.hasMore) list.more() }

    Column(Modifier.padding(top = Etkn.space.section - 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = Etkn.space.screen - 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SeasonTitle(seasons, state.seasonId, onSeason, Modifier.weight(1f))
            if (state.total > 1) {
                IconButton(onClick = { jump(0) }) { Icon(Icons.Rounded.FirstPage, "第一集") }
                IconButton(onClick = { jump(state.total - 1) }) { Icon(Icons.Rounded.LastPage, "最后一集") }
            }
            TextButton(onClick = onAll) { Text("全部") }
        }
        if (ranges > 1) {
            LazyRow(state = chips, contentPadding = PaddingValues(horizontal = Etkn.space.screen), horizontalArrangement = Arrangement.spacedBy(PillGap)) {
                items(ranges) { index ->
                    val start = index * RANGE + 1
                    PillToggle(checked = index == range, onClick = { jump(index * RANGE) }) {
                        Text("$start-${minOf(start + RANGE - 1, state.total)}")
                    }
                }
            }
        }
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = Etkn.space.screen, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(state.items, key = { _, it -> it.id }, contentType = { _, _ -> "episode" }) { _, episode ->
                EpisodeCard(
                    episode,
                    current = episode.id == currentId,
                    // The page's own episode is already open.
                    onOpen = { if (episode.id != currentId) onOpen(episode) },
                    modifier = Modifier.width(cardWidth),
                )
            }
            when {
                state.loading -> item(key = "loading") { Box(Modifier.width(cardWidth)) { InlineLoading() } }
                state.error != null -> item(key = "error") {
                    Box(Modifier.width(cardWidth).aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                        FilledTonalButton(onClick = list::retry) { Text("剧集加载失败，重试") }
                    }
                }
            }
        }
    }
}

/** 第 1 季 ▾ with a glass menu of the seasons when there are several; plain text otherwise. */
@Composable
private fun SeasonTitle(seasons: List<Item>, selected: String?, onSelect: (String) -> Unit, modifier: Modifier) {
    val name = seasons.firstOrNull { it.id == selected }?.name ?: "剧集"
    val anchor = rememberPopupAnchor()
    val choosable = seasons.size > 1
    Row(
        modifier.then(anchor.modifier).clip(Corner.card)
            .then(
                if (choosable) Modifier.clickable {
                    anchor.open(seasons.map { season -> PopupChoice(season.name, selected = season.id == selected) { onSelect(season.id) } })
                } else Modifier,
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (choosable) Icon(Icons.Rounded.ExpandMore, "选择季", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 16:9 still with progress and a watched mark, outlined when it is the page's own episode;
 * code, title and two lines of overview below. A tap anywhere opens the episode (playing is
 * that page's button); a long press on the still opens the item menu.
 */
@Composable
internal fun EpisodeCard(episode: Item, current: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val press = rememberItemPress(episode, resume = true)
    val url = images().primary(episode, 360.dp.px()) ?: images().landscape(episode, 360.dp.px())
    val scheme = MaterialTheme.colorScheme
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(press.modifier)
                .then(if (current) Modifier.border(2.dp, scheme.onSurface, CardShape) else Modifier)
                .padding(if (current) 3.dp else 0.dp)
                .clip(CardShape)
                .combinedClickable(onClick = onOpen, onLongClick = if (press.enabled) press::open else null),
        ) {
            Artwork(url, Modifier.fillMaxSize(), episode.name, placeholder = Icons.Rounded.Movie)
            if (episode.userData.played) {
                Icon(
                    Icons.Rounded.Check, "已观看",
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).background(scheme.primary, CircleShape).padding(3.dp),
                    tint = scheme.onPrimary,
                )
            } else if (episode.progress > 0f) {
                LinearProgressIndicator(
                    progress = { episode.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).height(3.dp),
                    trackColor = Etkn.tokens.onImageTrack,
                    drawStopIndicator = {},
                )
            }
        }
        Column(Modifier.fillMaxWidth().clip(Corner.card).clickable(onClick = onOpen).padding(top = 8.dp, start = 2.dp, end = 2.dp, bottom = 4.dp)) {
            Text(
                listOfNotNull(episode.indexNumber?.let { "E" + it.toString().padStart(2, '0') }, episode.name).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            episode.overview?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

