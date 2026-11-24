package xyz.etkn.mobile.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.ui.actions.PopupChoice
import xyz.etkn.mobile.ui.actions.rememberPopupAnchor
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.CardShape
import xyz.etkn.mobile.ui.components.InlineLoading
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.PillToggle
import xyz.etkn.mobile.ui.components.images
import xyz.etkn.mobile.ui.components.px
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.theme.Etkn
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow

/** Episodes per range chip. */
private const val RANGE = 20

/**
 * 用户口径（2026-11-26）**选集区整体重写（第 6 次，这次推倒重来）**。
 *
 * ── 为什么前面五六次都没修好 ──
 * 所有旧实现都建立在同一个错误前提上：列表是**分页加载**的，
 * 所以「第 n 集」和「列表第 n 格」不相等，必须不停做换算。
 * 换算依赖「已加载到第几页」这份状态，那份状态一旦和真实集号错位，定位就全错，
 * 表现出来就是「点区间没反应 / 抖一下回原处 / 跳到最开头」。
 *
 * ── 重写后的模型（一共只有三个概念，没有换算）──
 *  1. `state.items` **就是整季全部剧集**，顺序与服务器一致
 *     → 第 n 集的下标恒等于「按集号查表得到的值」，没有近似
 *  2. **唯一的滚动状态** `row`，只有一个地方写它：用户操作
 *  3. **高亮 = 横条第一格所在区间**，由 `row.firstVisibleItemIndex` 直接驱动，
 *     不绑定「当前播放第几集」——这两个是不同的东西，旧代码把它们混为一谈才互相打架
 *
 * 「跟随当前集」只在**用户切换播放集**时触发一次，且用一次性 token 触发，
 * 不会和用户点区间的操作抢位置（旧代码两个 LaunchedEffect 互相覆盖就是这个原因）。
 */
@Composable
internal fun EpisodeShelf(
    state: EpisodesState,
    seasons: List<Item>,
    /** 当前播放的集（单集页传入；剧集页为 null）。 */
    currentId: String?,
    /** 当前播放集的真实集号（IndexNumber）。 */
    currentNumber: Int?,
    list: EpisodeList,
    onSeason: (String) -> Unit,
    onOpen: (Item) -> Unit,
    onAll: () -> Unit,
) {
    val row = rememberLazyListState()
    val chips = rememberLazyListState()

    /**
     * 用户口径（2026-11-26）**修「进入详情页不定位到当前集，停在最开头」**。
     *
     * 原因：`scrollToItem` 在列表**还没被测量/上屏**时调用会被静默忽略
     * （LazyList 在 layoutInfo 为空时直接吞掉滚动请求，不抛错）。
     * 数据是异步到达的，LaunchedEffect 触发那一刻列表往往还没上屏，
     * 于是「定位到 417 集」被吞掉，画面停在最开头。
     *
     * 这里等 `layoutInfo.totalItemsCount > 0` 之后再滚，保证请求一定被执行。
     */
    suspend fun awaitLaidOut() {
        snapshotFlow { row.layoutInfo.totalItemsCount }.first { it > 0 }
    }

    /** 分集卡尺寸：和章节卡一致（历史口径 160dp）。 */
    val cardWidth = 160.dp

    val ranges = if (state.items.size > RANGE + RANGE / 2) (state.items.size + RANGE - 1) / RANGE else 0

    /**
     * 用户的滚动意图。只有一个写入者（用户的点击），
     * 消费完就清空；清空用 [Unit] 无关的 key，不会自己触发自己重启。
     */
    var pendingScroll by remember { mutableStateOf<PendingScroll?>(null) }

    /** 已消费到哪一次「切集跟随」，防止切集跟随被重复执行。 */
    var followedEpisode by remember { mutableStateOf<String?>(null) }

    /**
     * 区间高亮：**只看横条第一格**。
     * 这是唯一数据源 —— 用户点区间、手动滑动、切集跟随，
     * 三种操作最终都体现为「横条第一格变了」，高亮自然就对。
     */
    val activeRange by remember(state.items) {
        derivedStateOf {
            val at = row.firstVisibleItemIndex
            val items = state.items
            if (at in items.indices) {
                val number = items[at].indexNumber ?: (at + 1)
                ((number - 1).coerceAtLeast(0)) / RANGE
            } else 0
        }
    }

    /**
     * 唯一的滚动执行者。
     *
     * key 用 `pendingScroll`：为 null 时直接退出，
     * **不会**退回去滚「当前集」——旧代码正是这一步导致「点区间被切集跟随覆盖」。
     */
    LaunchedEffect(pendingScroll) {
        val request = pendingScroll ?: return@LaunchedEffect
        val at = state.indexOfNumber(request.number)
        if (at >= 0) {
            /**
             * 用户口径（2026-11-26）**修「点区间后高亮亮在左边那一格」**。
             *
             * 旧实现固定把目标集放在左起第 2 格（`at - 1`），
             * 于是「横条第一格」变成了目标集的**上一集**：
             * 点 41-60 → 第一格是第 40 集 → 按集号算出来属于 21-40 → 高亮亮左边那个。
             * 现在点区间时 offset = 0（目标贴左起第 1 格），第一格就是区间首集，高亮必然对。
             */
            awaitLaidOut()
            row.scrollToItem((at - request.offset).coerceAtLeast(0))
            pendingScroll = null
        }
    }

    /**
     * 用户切换播放集（单集页原地换集）→ 让横条跟过去。
     *
     * 只在 `currentId` 真的变化时触发一次；用户刚点了区间（有 pendingScroll）时不抢。
     */
    LaunchedEffect(currentId, currentNumber, state.items.size) {
        if (pendingScroll != null) return@LaunchedEffect
        if (currentId == null) return@LaunchedEffect
        if (followedEpisode == currentId) return@LaunchedEffect
        // 只有这一集真的在列表里才跟随（避免数据未到就滚到 0）
        val at = state.indexOf(currentId)
        if (at < 0) return@LaunchedEffect
        followedEpisode = currentId
        awaitLaidOut()
        row.scrollToItem((at - UNIT_OFFSET).coerceAtLeast(0))
    }

    /**
     * 首次进入 / 换季时，定位到当前集（或锚点）。
     * 用 seasonId 做 key，只在换季时对齐一次，之后的滚动完全交给用户。
     */
    LaunchedEffect(state.seasonId, state.items.size) {
        if (state.items.isEmpty()) return@LaunchedEffect
        val target = currentId ?: state.anchorId ?: return@LaunchedEffect
        val at = state.indexOf(target)
        if (at >= 0) {
            // 等列表真正上屏再滚，否则这次定位会被静默丢弃（进页面停在最开头）
            awaitLaidOut()
            row.scrollToItem((at - UNIT_OFFSET).coerceAtLeast(0))
        }
    }

    /** 区间 chip 栏跟着高亮走。 */
    LaunchedEffect(activeRange) {
        if (ranges > 0) chips.animateScrollToItem(activeRange.coerceIn(0, (ranges - 1).coerceAtLeast(0)))
    }

    /**
     * 手动滑动横条时，让高亮逻辑正常工作：
     * `activeRange` 是 derivedStateOf，本来就会跟；这里不需要额外做任何事。
     * （旧代码在这一步反复写 LaunchedEffect 反而制造了竞态。）
     */

    Column(Modifier.padding(top = Etkn.space.section - 8.dp)) {
        /** 标题行：季选择 / 跳首尾 / 全部 */
        Row(
            Modifier.fillMaxWidth().padding(start = Etkn.space.screen - 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SeasonTitle(seasons, state.seasonId, onSeason, Modifier.weight(1f))
            if (state.items.size > 1) {
                val lastNumber = state.items.lastOrNull()?.indexNumber ?: state.items.size
                val firstNumber = state.items.firstOrNull()?.indexNumber ?: 1
                IconButton(onClick = { pendingScroll = PendingScroll(firstNumber, offset = 0) }) {
                    Icon(Icons.Rounded.FirstPage, "第一集")
                }
                IconButton(onClick = { pendingScroll = PendingScroll(lastNumber, offset = 0) }) {
                    Icon(Icons.Rounded.LastPage, "最后一集")
                }
            }
            TextButton(onClick = onAll) { Text("全部") }
        }

        /** 区间 chip：点一下 = 请求滚到该区间第一集 */
        if (ranges > 1) {
            LazyRow(
                state = chips,
                contentPadding = PaddingValues(horizontal = Etkn.space.screen),
                horizontalArrangement = Arrangement.spacedBy(PillGap),
            ) {
                items(ranges) { index ->
                    val rangeStart = index * RANGE + 1
                    val rangeEnd = minOf(rangeStart + RANGE - 1, state.items.size)
                    PillToggle(
                        checked = index == activeRange,
                        onClick = {
                            // 直接请求「滚到该区间第一集」；高亮由滚动结果自然驱动
                            pendingScroll = PendingScroll(rangeStart, offset = 0)
                        },
                    ) {
                        Text("$rangeStart-$rangeEnd")
                    }
                }
            }
        }

        /** 分集横条 */
        LazyRow(
            state = row,
            contentPadding = PaddingValues(horizontal = Etkn.space.screen, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(state.items, key = { _, it -> it.id }, contentType = { _, _ -> "episode" }) { _, episode ->
                EpisodeCard(
                    episode,
                    current = episode.id == currentId,
                    onOpen = { if (episode.id != currentId) onOpen(episode) },
                    modifier = Modifier.width(cardWidth),
                )
            }
            if (state.loading) {
                item(key = "loading") {
                    Box(Modifier.width(120.dp).height(cardWidth * 9 / 16).padding(8.dp), contentAlignment = Alignment.Center) {
                        InlineLoading()
                    }
                }
            }
        }
    }
}

/**
 * 一次「滚到第 n 集」的请求。
 *
 * 用户口径（2026-11-26）：用独立对象而不是 `Int?`，
 * 因为要让「请求第 20 集」和「请求第 20 集」是同一个 key（不会因为重复点击重复滚），
 * 同时「清空请求」不会触发同一个 LaunchedEffect 重启。
 */
private data class PendingScroll(
    val number: Int,
    /**
     * 目标集相对横条左边的格数。
     *  · 0 —— 目标集贴在左起第 1 格（点区间 chip 用这个，高亮才能精确落在该区间）
     *  · 1 —— 目标集显示在左起第 2 格（切集跟随时用，左边留一集方便回看）
     */
    val offset: Int = 0,
)

/**
 * 目标集相对横条左边的格数。
 * 1 = 目标集显示在第 2 格（左起第 2 个），左边留一集、右边露出下一集。
 */
private const val UNIT_OFFSET = 1

@Composable
private fun SeasonTitle(
    seasons: List<Item>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anchor = rememberPopupAnchor()
    val current = seasons.firstOrNull { it.id == selected } ?: seasons.firstOrNull()
    val label = current?.seasonName?.takeIf { it.isNotBlank() } ?: "剧集"
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (seasons.size > 1) {
            Icon(
                Icons.Rounded.ExpandMore,
                null,
                Modifier.size(20.dp).then(anchor.modifier).clickable {
                    anchor.open(
                        seasons.map { season ->
                            PopupChoice(
                                label = season.seasonName ?: season.name,
                                selected = season.id == selected,
                            ) { onSelect(season.id) }
                        },
                    )
                },
            )
        }
    }
}

/**
 * 分集卡：16:9 缩略图 + 集号与集名 + 进度条。
 */
@Composable
internal fun EpisodeCard(
    episode: Item,
    current: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val urls = images()
    val progress = episode.progress
    Column(
        modifier
            .clip(CardShape)
            .then(if (current) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CardShape) else Modifier)
            .clickable(onClick = onOpen),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Corner.card)) {
            Artwork(
                urls.primary(episode, 320.dp.px()),
                Modifier.fillMaxSize(),
                episode.name,
                placeholder = Icons.Rounded.Movie,
            )
            if (episode.userData.played) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(4.dp)
                        .size(18.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, "已观看", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            if (progress > 0f && progress < 1f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                )
            }
        }
        Text(
            listOfNotNull(episode.indexNumber?.let { "$it." }, episode.name).joinToString(" "),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
