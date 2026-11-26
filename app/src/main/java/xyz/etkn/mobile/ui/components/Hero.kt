package xyz.etkn.mobile.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.nav.LocalStartInset
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.HeroTitleStyle
import xyz.etkn.mobile.ui.theme.Corner
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection

/**
 * Home hero: one focal point per screen. Full-bleed backdrop that fades into the page, centred
 * logo (or title), one meta line, a short overview and two actions; page dots sit underneath.
 * Parallax is a pure graphicsLayer transform clipped to the hero, so scrolling never relayouts
 * and the artwork can never slide out below its own scrim.
 */
@Composable
fun HeroCarousel(
    items: List<Item>,
    height: Dp,
    scrollOffsetPx: () -> Int,
    onPlay: (Item) -> Unit,
    onOpen: (Item) -> Unit,
    modifier: Modifier = Modifier,
    autoAdvance: Boolean = true,
    /**
     * 用户口径（2026-11-27）**页数固定，先占位后填充**（用户原话「曲线救国」）。
     *
     * 需求：打开首页的瞬间就要看到 10 个圆点，不能先 6 个再跳成 10 个；
     * 但也绝不能为了凑数让首屏空着等 3 秒。
     *
     * 做法：pager 的总页数恒等于 [pageCount]（=10）。
     *  · 数据够的页正常画
     *  · 数据还没到的页画占位（同色深底，不出字）
     *  · 后续 [items] 变长时，对应页自动变成真内容 —— 圆点数量自始至终不变
     *
     * 用户自己说了「我也不能马上就去滑动那个轮播图」，所以前几页先有内容、
     * 后面几页稍后填上是完全可以接受的。
     */
    pageCount: Int = items.size,
) {
    if (items.isEmpty() && pageCount <= 0) return
    /**
     * 用户口径（2026-11-27，#26）**轮播逻辑回到 v1.9.6，只把数量 10 改成 8**。
     *
     * 历史：
     *  · v1.9.6 —— 页数写死 `pageCount`（10），数据不足时最后一页是空白（用户报的问题）
     *  · v1.10.0 —— 改成「按实际条数 + 先占位后填充」，结果变成「先几个、然后跳出来好多」
     *  · 现在 —— **回到 v1.9.6 的写法，只把 10 改成 8**
     *
     * 用户原话：「就是把轮播图的逻辑改成 1.9.6 那版的，1.9.6 那版的第 10 个不是空白吗，
     *            你直接在 1.9.6 那版的基础上，把轮播图 10 个改成 8 个，
     *            舍掉现在最新版本 1.10.0 的轮播图逻辑」
     *
     * 空白页的问题靠 HomeViewModel 保证「一定凑满 8 个才发布」来解决，
     * 而不是靠这里动态缩页数 —— 那会带来「数量跳变」的新问题。
     */
    val totalPages = maxOf(pageCount, items.size)
    val pager = rememberPagerState { totalPages }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(totalPages, dragged, autoAdvance) {
        if (!autoAdvance || dragged || totalPages < 2) return@LaunchedEffect
        while (true) {
            delay(7_000)
            pager.animateScrollToPage((pager.currentPage + 1) % totalPages)
        }
    }
    val widthPx = LocalWindowInfo.current.containerSize.width
    Box(modifier.fillMaxWidth().height(height).clipToBounds()) {
        // 用户口径（2026-11-23）：
        //  · 轮播图必须能左右滑（之前我为了堵手势泄漏把它关了，滑不动了 —— 这是改坏了）
        //  · 但滑到头之后，多余的水平位移不能漏给外层 TabPager（否则整个首页被拖成两页并排）
        // 解法：Pager 恢复手势，外面再套一层「只消费水平方向」的 pointerInput。
        // 内层 Pager 先拿到手势正常翻页；只有它走到边界不再消费的残余位移，
        // 才被这层拦下来吃掉，不漏到外层。
        HorizontalPager(
            state = pager,
            // 用户口径（2026-11-23）：恢复轮播图左右滑动（v1.1.9 我为了堵手势泄漏把它关了，滑不动）。
            //
            // 之前「整个首页被拖成两页并排」的真正原因不是这里，而是外层 TabPager 也在响应横滑；
            // 这里用 nestedScroll 声明「本区域的横向滚动由我处理完就不再往上抛」，
            // 既保留轮播可滑，又不会把残余位移漏给 TabPager。
            modifier = Modifier.fillMaxSize().nestedScroll(object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: androidx.compose.ui.geometry.Offset,
                    available: androidx.compose.ui.geometry.Offset,
                    source: NestedScrollSource,
                ): androidx.compose.ui.geometry.Offset {
                    // 把水平方向的剩余量全部吃掉，只让垂直方向继续传（首页竖向滚动不受影响）
                    return if (available.x != 0f) androidx.compose.ui.geometry.Offset(available.x, 0f)
                    else androidx.compose.ui.geometry.Offset.Zero
                }
            }),
            key = { page -> items.getOrNull(page)?.id ?: "hero-placeholder-$page" },
        ) { page ->
            val item = items.getOrNull(page)
            if (item != null) {
                HeroPage(item, widthPx, scrollOffsetPx, onPlay, onOpen)
            } else {
                /**
                 * 占位页：用页面底色铺满，不出图不出字。
                 * 用户滑到这里看到的是纯色块（很快就填上了），
                 * 但圆点数量从第一帧起就是 10 个，不会跳变。
                 */
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
        }
        if (totalPages > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(totalPages) { index ->
                    val selected = index == pager.currentPage
                    val w by animateDpAsState(if (selected) 18.dp else 6.dp, label = "dot")
                    Box(
                        Modifier.height(6.dp).width(w).background(
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                            CircleShape,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroPage(
    item: Item,
    widthPx: Int,
    scrollOffsetPx: () -> Int,
    onPlay: (Item) -> Unit,
    onOpen: (Item) -> Unit,
) {
    val urls = images()
    val tokens = Etkn.tokens
    val scheme = MaterialTheme.colorScheme
    // Phones centre the copy under the artwork; tablets anchor it bottom-left over a side scrim.
    val wide = LocalWindowLayout.current.wide
    val align = if (wide) Alignment.Start else Alignment.CenterHorizontally
    val textAlign = if (wide) TextAlign.Start else TextAlign.Center
    Box(Modifier.fillMaxSize().clickable(onClick = { onOpen(item) })) {
        Artwork(
            urls.backdrop(item, widthPx) ?: urls.poster(item, widthPx),
            Modifier.fillMaxSize().graphicsLayer {
                val offset = scrollOffsetPx().toFloat()
                translationY = offset * 0.45f
                val scale = 1f + (offset / size.height.coerceAtLeast(1f)) * 0.08f
                scaleX = scale
                scaleY = scale
            },
            heroTitle(item),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxWidth().height(160.dp).background(tokens.heroTopScrim))
        if (wide) Box(Modifier.fillMaxSize().background(tokens.heroSideScrim))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.62f).background(tokens.heroBottomScrim))
        Column(
            Modifier.align(if (wide) Alignment.BottomStart else Alignment.BottomCenter)
                .then(if (wide) Modifier.padding(start = LocalStartInset.current + 40.dp).widthIn(max = 560.dp) else Modifier)
                .fillMaxWidth()
                .padding(horizontal = Etkn.space.screen).padding(bottom = 30.dp),
            horizontalAlignment = align,
        ) {
            val logo = urls.logo(item, 720)
            if (logo != null) {
                Artwork(
                    logo,
                    Modifier.fillMaxWidth(if (wide) 0.8f else 0.72f).height(96.dp),
                    heroTitle(item),
                    contentScale = ContentScale.Fit,
                    background = Color.Transparent,
                )
            } else {
                /**
                 * 用户口径（2026-11-27）**轮播标题必须是剧名，不是集号**。
                 *
                 * 问题（用户截图）：继续播放位显示的是「S01E0645」，
                 * 用户原话「这个剧叫啥它不显示啊，正常它应该有一个剧集的名，
                 * 比如现在显示的这个应该是《乌拉精选》这个剧名你得放上啊」。
                 *
                 * 原因：分集 item.name 就是集号（S01E0645），
                 * 而剧名在 item.seriesName 里。
                 * 判断写法与 DetailScreen 保持一致（那里已经这么做了）。
                 */
                Text(
                    heroTitle(item),
                    style = HeroTitleStyle.copy(shadow = Shadow(Color.Black.copy(alpha = 0.35f), blurRadius = 16f)),
                    color = scheme.onSurface,
                    textAlign = textAlign,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            /**
             * 用户口径（2026-11-27）**分集要在剧名下面补一行集号**。
             *
             * 主标题改成剧名后，用户就不知道是第几集了，
             * 所以在标题下方用一行小字显示集号（如 S01E0645）。
             * 电影 / 剧集本身没有集号 → 不显示这一行。
             */
            episodeLabel(item)?.let { label ->
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface.copy(alpha = 0.86f),
                    textAlign = textAlign,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            HeroMeta(item)
            item.overview?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = scheme.onSurfaceVariant, textAlign = textAlign, modifier = Modifier.fillMaxWidth(0.9f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onPlay(item) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("播放")
                }
                FilledTonalButton(onClick = { onOpen(item) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Rounded.Info, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("详情")
                }
            }
        }
    }
}

/** ★ rating · year · genres · age rating — one centred line. */
@Composable
private fun HeroMeta(item: Item) {
    val scheme = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.labelLarge
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        formatRating(item.bestRating)?.let { rating ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Star, null, Modifier.size(16.dp), tint = Etkn.tokens.rating)
                Spacer(Modifier.width(2.dp))
                Text(rating, style = style, color = scheme.onSurface)
            }
        }
        item.productionYear?.let { Text(it.toString(), style = style, color = scheme.onSurface) }
        item.genres.take(3).joinToString("/").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = style, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        item.officialRating?.takeIf { it.isNotBlank() }?.let {
            Text(
                it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurface, maxLines = 1,
                modifier = Modifier.border(1.dp, scheme.onSurface.copy(alpha = 0.6f), Corner.small).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * 用户口径（2026-11-27）**轮播主标题：分集显示剧名，其余显示自己的名字**。
 *
 * 分集的 item.name 是集号（如 S01E0645），剧名在 seriesName。
 * 与 DetailScreen 的判断完全一致，避免两处口径不同。
 */
private fun heroTitle(item: Item): String =
    item.seriesName?.takeIf { (item.isEpisode || item.isSeason) && it.isNotBlank() } ?: item.name

/**
 * 分集的集号（S01E0645）；非分集返回 null。
 * 主标题已经换成剧名，这里补回「是第几集」的信息。
 */
private fun episodeLabel(item: Item): String? =
    item.name.takeIf { (item.isEpisode || item.isSeason) && it.isNotBlank() }
