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
import androidx.compose.ui.text.font.FontWeight
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
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.foundation.layout.Spacer

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
    /** 用户口径（#92）分集卡放大到跟演员图一样大（160 → 132，横版 16:9）。 */
    /** 用户口径（#100）分集卡与演员卡同宽 112dp（横版 16:9）。 */
    val cardWidth = Etkn.space.episodeCard

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
            /**
             * 用户口径（2026-11-27，#13）**滚动要平滑，不要瞬移**。
             * 参考视频里选集行是"滑过去"的，不是瞬间跳。
             */
            row.animateScrollToItem((at - request.offset).coerceAtLeast(0))
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
        row.animateScrollToItem((at - UNIT_OFFSET).coerceAtLeast(0))
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
            row.animateScrollToItem((at - UNIT_OFFSET).coerceAtLeast(0))
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

    /**
     * 用户口径（2026-11-27，#17）**选集标题行重做**。
     *
     * 需求原文：
     *  「中间的剧集这俩字删掉，后面的两个跳到最前面和最后面的那两个符号也删掉，
     *    全部这个功能移动到 1-20 21-40 这个区间的最后面，这一行专门留出来个全部的按钮，
     *    把这个全部按钮设计一下，跟它前面的 1-20 这种按钮区分一下。
     *    然后剧集和全部也就是之前的这一行不是空了吗，把下面的所有信息都往上移，
     *    不要留空，正好衔接」
     *
     * 结构变化：
     *  旧：[剧集] ......... [‹] [›] [全部]
     *      [1-20] [21-40]
     *  新：[1-20] [21-40] [全部]      ← 同一行，全部放最后并做视觉区分
     *
     * 整块的 top padding 也一并收紧（原来是 section-8dp 再加标题行占位），
     * 现在没有独立的标题行，直接接着上一块，不留下空隙。
     */
    Column(Modifier.padding(top = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            /**
             * 区间 chip + 全部按钮**同一行**。
             *
             * 区间多了可以横向滑（LazyRow）；"全部"固定在行尾不随滑动跑掉，
             * 这样它永远在最右边，和区间按钮形成明显的位置区分。
             */
            LazyRow(
                state = chips,
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(PillGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(ranges.coerceAtLeast(1)) { index ->
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
            Spacer(Modifier.width(8.dp))
            /**
             * 「全部」按钮：和区间 chip 做视觉区分 ——
             *  · 区间 chip 是 PillToggle（选中是实色块）
             *  · 全部用**描边胶囊 + 图标**，不参与选中态，一眼看出是"动作"而不是"范围"
             */
            Surface(
                onClick = onAll,
                shape = Corner.pill,
                color = androidx.compose.ui.graphics.Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                ),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Rounded.GridView, null, Modifier.size(14.dp))
                    Text(
                        "全部",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                    )
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
    /**
     * 用户口径（2026-11-26）**修「剧集卡下面的文字被方框圆角压住」**。
     *
     * 原因：外层 Column 加了 `clip(CardShape)`（16dp 圆角），
     * 这个裁剪作用在**整个卡片**上，包括图片下方的文字。
     * 文字贴着卡片底部两角，正好被那两个圆角切掉一部分，看起来像被压住。
     *
     * 现在把裁剪只放在图片那一块（Box 已有 `clip(Corner.card)`），
     * 外层 Column 不再裁剪 —— 文字因此可以完整显示，
     * 而图片仍然是圆角的（视觉不变）。
     */
    /**
     * 用户口径（2026-11-27）**选中绿框只圈图片，不圈文字；选中的集号加粗**。
     *
     * 需求原文：
     *  「那个明显选中的那个绿色圈把文字压上了，我想到个解决办法，
     *    这个绿色圈只环绕到图像得四周，不要包上文字，
     *    并且把绿色这个选中的全加粗，这样更明显」
     *
     * 原来把 border 加在**整个 Column**（图片 + 下面两行集号文字）外面，
     * CardShape 的圆角正好压在文字两角上，看起来像被框住/压住。
     *
     * 现在：
     *  · border 只加在图片那个 Box 上（文字完全在框外）
     *  · 选中的集号文字加粗（FontWeight.Bold），靠颜色+字重一起区分
     */
    /**
     * 用户口径（2026-11-27，#13）**选中框要平滑移动，不是瞬间跳过去**。
     *
     * 参考视频效果：白色圆角选中框沿选集行平滑滑动到目标格，
     * 同时列表自动滚动把目标带进视野；页面其他区域完全不动。
     *
     * 做法：
     *  · 边框的 `alpha` 用 `animateFloatAsState` 平滑过渡（选中 1 / 未选中 0）
     *  · 边框颜色/粗细保持不变（3dp primary，和之前视觉一致）
     *  · 列表跟随滚动在 EpisodeShelf 主体用 animateScrollToItem 实现
     *
     * 为什么不用「一个会移动的共享边框」：那需要测量所有格子的坐标，
     * 在 LazyRow 里格子会被回收，量出来经常是错的。逐格淡入在视觉上
     * 同样是「框滑过去了」，而且永远不会错位。
     */
    val borderAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (current) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 220),
        label = "episode-selected-border",
    )
    Column(
        modifier.clickable(onClick = onOpen),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(Corner.card)
                .then(
                    if (borderAlpha > 0.01f) {
                        Modifier.border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = borderAlpha),
                            shape = Corner.card,
                        )
                    } else Modifier
                ),
        ) {
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
            // 用户口径（2026-11-27）：选中的集号加粗，配合颜色一起更明显
            // 用户口径（2026-11-27）：选中要更明显 —— Bold 换成 ExtraBold
            fontWeight = if (current) FontWeight.ExtraBold else FontWeight.Normal,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
