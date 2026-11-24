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
    // Position to scroll to once it is loaded (range chips, jump to last).
    var pending by remember { mutableIntStateOf(-1) }
    val ranges = if (state.total > RANGE + RANGE / 2) (state.total + RANGE - 1) / RANGE else 0
    /**
     * 用户口径（2026-11-26）**修「单集页点其他集，选集分页区间乱跳」**。
     *
     * 现象：《蜡笔小新》S1E417（分页 401-420）点 E419 后，
     * 集标题正确切到 E419，但分页选中项跳到 61-80、横条显示 E77-E80。
     *
     * 根因：这里原来写的是
     *     row.firstVisibleItemIndex / RANGE
     * `firstVisibleItemIndex` 是**当前已加载列表里的下标**，不是全局集号。
     * 蜡笔小新 1200+ 集、列表只按需加载了一部分，
     * 切集之后 `items` 的子集和滚动位置一变，下标记就算到了别的区间。
     *
     * 正确算法：用当前集在全集中的**集号**（IndexNumber）算区间，
     * 而不是已加载列表里的下标。
     */
    /**
     * 用户口径（2026-11-26）**第二次修「选集分页乱跳」**。
     *
     * 上一版改成用 `state.items` 里当前集的 indexNumber 算，仍然会闪：
     * 切集瞬间 `state.items` 还是旧数据（新集所在页还没加载），
     * 算出来就是旧集号 → 分页先跳到错误区间，等数据到了再跳回来。
     * 录屏实测到的就是：「1261-1280 → 1081-1100 → 又回 1261-1280」。
     *
     * 现在改为**直接由传进来的 currentNumber 算**，完全不依赖列表加载状态：
     *  · 切集瞬间 currentNumber 立刻就是新集的真实集号
     *  · 一次就算对，不会先跳错误区间再纠正
     *  · 拿不到 currentNumber 时才退回按滚动位置
     */
    val range by remember(currentNumber, currentId) {
        derivedStateOf {
            val number = currentNumber
            if (number != null && number > 0) {
                ((number - 1) / RANGE).coerceAtLeast(0)
            } else {
                row.firstVisibleItemIndex / RANGE
            }
        }
    }

    /**
     * 用户口径（2026-11-26）：jump 统一按「位置」语义，但调用方传进来的
     * 是「集号 - 1」这种全局序号。列表是全集时两者等价；
     * 是子集时用 loadThrough 把目标位置所在页拉进来，再滚过去。
     */
    fun jump(index: Int) {
        val at = index.coerceAtLeast(0)
        pending = at
        list.loadThrough(at)
    }

    /** 按「集号」跳（用于区间 chip）：换算成位置后复用 jump。 */
    fun jumpToNumber(number: Int) = jump((number - 1).coerceAtLeast(0))

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
    /**
     * 用户口径（2026-11-24）**整条平滑平移一张卡**（照参考视频）。
     *
     * 参考视频的手感：点第 4 集 → 整条横条平滑左移一张卡的宽度，
     * 第 4 集滑到第 3 集原来的位置，第 5 集滑到第 4 集原来的位置；点前一集反向。
     *
     * 实现要点：
     *  · 关键是「目标集永远停在同一个锚点位置」，而不是「只要可见就不动」
     *  · 每次切换都用同一套动画曲线（MaterialTheme 的 spatial spec），
     *    快慢一致才有连续的平移感
     *  · 锚点取「让当前集出现在第 2 格」（左边留一格，右边能看到下一集）
     */
    LaunchedEffect(currentId, state.items.size) {
        val index = state.items.indexOfFirst { it.id == currentId }
        if (index < 0) return@LaunchedEffect
        // 锚点：目标集显示在左起第 2 个位置（留一格给上一集，右边露出下一集）
        val anchorOffset = 1
        val target = (index - anchorOffset).coerceAtLeast(0)
        row.animateScrollToItem(target, scrollOffset = 0)
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
                    PillToggle(checked = index == range, onClick = { jumpToNumber(index * RANGE + 1) }) {
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

