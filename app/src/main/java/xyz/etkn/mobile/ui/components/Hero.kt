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
) {
    if (items.isEmpty()) return
    val pager = rememberPagerState { items.size }
    val dragged by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(items.size, dragged, autoAdvance) {
        if (!autoAdvance || dragged || items.size < 2) return@LaunchedEffect
        while (true) {
            delay(7_000)
            pager.animateScrollToPage((pager.currentPage + 1) % items.size)
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
            key = { items[it].id },
        ) { page ->
            HeroPage(items[page], widthPx, scrollOffsetPx, onPlay, onOpen)
        }
        if (items.size > 1) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(items.size) { index ->
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
            item.name,
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
                    item.name,
                    contentScale = ContentScale.Fit,
                    background = Color.Transparent,
                )
            } else {
                Text(
                    item.name,
                    style = HeroTitleStyle.copy(shadow = Shadow(Color.Black.copy(alpha = 0.35f), blurRadius = 16f)),
                    color = scheme.onSurface,
                    textAlign = textAlign,
                    maxLines = 2,
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
