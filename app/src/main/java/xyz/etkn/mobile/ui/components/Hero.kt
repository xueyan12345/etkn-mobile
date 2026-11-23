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
        // 用户口径（2026-11-23）：轮播图这里的横向 Pager 不能接受手势。
        // 它是嵌在 TabPager 里的内层 Pager——手指落在这一块横划时，内层到边界 /
        // 或正好赶上 7 秒自动轮播的 animateScrollToPage 在跑，位移会经嵌套滚动
        // 泄漏给外层 TabPager，导致整个首页被拖成两页并排、松手不回弹（偶发就是这个原因）。
        // 关掉手势后：自动轮播照常（那是程序化滚动，不受这个开关影响），只是不能手划。
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
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
