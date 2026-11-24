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
    /**
     * 用户口径（2026-11-26）：**当前集在整季里的真实集号**（IndexNumber）。
     *
     * 分页区间必须用它来算，不能用「已加载列表里的下标」：
     * 长剧（1000+ 集）是按需加载的，列表下标和集号不是一回事。
     * 用下标算会出现「417 集点了 419 集、分页跳到 61-80」这种乱跳。
     */
    currentNumber: Int?,
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
    /**
     * 用户口径（2026-11-26）**选集滚动逻辑整体重写（第三次，这次动的是架构不是参数）**。
     *
     * 前两次失败的共同原因：一直在「怎么算区间 / 怎么算下标」上打补丁，
     * 但没解决「列表里到底有没有这一集」和「什么时候滚才有效」这两件事。
     *
     * 录屏实测到的两个现象，对应两个不同的坏点：
     *  ① 点 421-440 chip：绿色阴影没落到 421-440
     *     → chip 的选中判定和滚过去的目标不是同一个值
     *  ② 点 462 集：横条跳到最开头 E80 那块
     *     → 目标集根本不在已加载列表里（positionOf 找不到 → 退回 0）
     *
     * 现在统一规则：
     *  · **所有定位都以「集号」为准**（1-based），不再用列表下标
     *  · 滚之前先 `list.ensureNumber(n)` 把那一集加载进来
     *  · 滚的触发条件绑定「数据里已经有这一集」，数据没到就不滚（避免滚到 0）
     *  · chip 高亮 = 当前集所在区间（setSelection），与滚动解耦
     */
    var pendingNumber by remember { mutableIntStateOf(-1) }
    val ranges = if (state.total > RANGE + RANGE / 2) (state.total + RANGE - 1) / RANGE else 0

    /**
     * 用户口径（2026-11-26）**修「点了区间没反应还会跳回来」**。
     *
     * 上一版把高亮绑在「当前播放的集」上（currentRange = currentNumber / 20）。
     * 点区间按钮时只是把横条滚过去了，高亮判据没变 → 阴影留在原地，
     * 看起来就是「点了没反应」，而且横条一回到当前集附近又「跳回来」。
     *
     * 现在高亮 **跟随横条的第一格**：
     *  · 点区间 → 横条滚过去 → 第一格变了 → 高亮自然落到你点的区间
     *  · 手动滑动横条 → 高亮跟着走（这才是区间按钮该有的行为）
     *  · 切集 → 横条滚到该集 → 高亮跟着到它所在区间
     */
    val currentRange = (currentNumber ?: 1).let { ((it - 1).coerceAtLeast(0)) / RANGE }
    val visibleRange by remember(state.items, currentRange) {
        derivedStateOf {
            val idx = row.firstVisibleItemIndex
            val items = state.items
            if (idx in items.indices) {
                val n = items[idx].indexNumber ?: (idx + 1)
                ((n - 1).coerceAtLeast(0)) / RANGE
            } else {
                currentRange
            }
        }
    }

    /**
     * 用户点 chip / 跳首尾时记录的「想去的集号」。
     * 只有当这一集真的进了列表、且能算出下标时，才执行滚动。
     */
    fun jumpToNumber(number: Int) {
        pendingNumber = number.coerceAtLeast(1)
        list.ensureNumber(number)
    }

    /**
     * ① 数据到位后滚动到目标集。
     * 以 `state.items` 和 `pendingNumber` 为 key：任何一边变了都重新判断，
     * 但只要那一集还没进列表就什么都不做（关键：不会像以前那样滚到 0）。
     */
    LaunchedEffect(pendingNumber, state.items, currentId) {
        val number = if (pendingNumber > 0) pendingNumber else currentNumber ?: return@LaunchedEffect
        val at = list.positionOfNumber(number)
        if (at >= 0) {
            /**
             * 用户口径（2026-11-26）**修「先跳到最前面再跳到目标集」**。
             * `animateScrollToItem` 跨几百集会有一段很长的动画，经过中间所有集，
             * 视觉上就是「先跑到最前面再回来」。这里改成瞬时就位。
             * 目标集放在左起第 2 格（左边留一集，右边露出下一集，方便连续点）。
             */
            row.scrollToItem((at - 1).coerceAtLeast(0))
            pendingNumber = -1
        } else {
            // 还没加载到 → 继续补（幂等，加载完会再次进入本 effect）
            list.ensureNumber(number)
        }
    }

    /**
     * ② 切集（单集页原地换集）时让选集跟随当前集。
     *
     * 只在没有待处理的跳转时执行，避免和 ① 抢滚动位置。
     */
    LaunchedEffect(currentId, currentNumber, state.items) {
        if (pendingNumber > 0) return@LaunchedEffect
        val number = currentNumber ?: return@LaunchedEffect
        val at = list.positionOfNumber(number)
        if (at < 0) {
            list.ensureNumber(number)
            return@LaunchedEffect
        }
        /**
         * 用户口径（2026-11-26）：切集时也瞬时就位，不做长距离动画
         * （动画穿过几百集同样会出现「先跳到最前面」的观感）。
         */
        row.scrollToItem((at - 1).coerceAtLeast(0))
    }

    /** ③ 区间 chip 栏跟着当前区间走。 */
    LaunchedEffect(visibleRange, ranges) {
        if (ranges > 0) chips.animateScrollToItem((visibleRange - 1).coerceAtLeast(0))
    }

    val nearEnd by remember { derivedStateOf { (row.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= row.layoutInfo.totalItemsCount - 6 } }
    LaunchedEffect(nearEnd, state.hasMore) { if (nearEnd && state.hasMore) list.more() }

    Column(Modifier.padding(top = Etkn.space.section - 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = Etkn.space.screen - 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SeasonTitle(seasons, state.seasonId, onSeason, Modifier.weight(1f))
            if (state.total > 1) {
                // 用户口径（2026-11-26）：首尾跳转同样按集号（1-based），不走列表下标
                IconButton(onClick = { jumpToNumber(1) }) { Icon(Icons.Rounded.FirstPage, "第一集") }
                IconButton(onClick = { jumpToNumber(state.total.coerceAtLeast(1)) }) { Icon(Icons.Rounded.LastPage, "最后一集") }
            }
            TextButton(onClick = onAll) { Text("全部") }
        }
        if (ranges > 1) {
            LazyRow(state = chips, contentPadding = PaddingValues(horizontal = Etkn.space.screen), horizontalArrangement = Arrangement.spacedBy(PillGap)) {
                items(ranges) { index ->
                    val rangeStart = index * RANGE + 1
                    /**
                     * 用户口径（2026-11-26）：高亮用「当前集所在区间」，
                     * 点击也传「该区间的第一集集号」，两者同源，不会再出现
                     * 「点了 421-440 但阴影没落上去」。
                     */
                    PillToggle(
                        checked = index == visibleRange,
                        onClick = { jumpToNumber(rangeStart) },
                    ) {
                        Text("$rangeStart-${minOf(rangeStart + RANGE - 1, state.total)}")
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

