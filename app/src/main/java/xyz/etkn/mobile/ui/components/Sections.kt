package xyz.etkn.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.ui.nav.LocalStartInset
import xyz.etkn.mobile.ui.theme.Etkn
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState

/**
 * 区块标题。
 *
 * 用户口径（2026-11-23）：
 * · 字号比上一版小一丢丢（titleLarge → titleMedium），上一版偏大
 * · **所有**媒体库区块（媒体库 / 国产剧集 / 华语电影 …）都带 > 且整行可点，
 *   点进去就是对应库；原来只有「媒体库」能点，其他点不了
 * · 上下留白保持紧凑
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    /** 有 onMore 就显示 > 并整行可点；没有就不显示箭头。 */
    showChevron: Boolean = true,
) {
    val arrow = showChevron && onMore != null
    val clickable = if (arrow) Modifier.clickable(onClick = onMore!!) else Modifier
    Row(
        modifier.fillMaxWidth().padding(start = LocalStartInset.current + Etkn.space.screen, end = 8.dp).then(clickable),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            // 用户口径（2026-11-23）：字号取中间值 19sp（原来 22sp → 上一版 16sp 降过头）
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (arrow) Modifier else Modifier.weight(1f),
        )
        if (arrow) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "查看全部",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/** Horizontally scrolling shelf with a header; items are keyed for stable scrolling. */
@Composable
fun Shelf(
    title: String,
    items: List<Item>,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    showChevron: Boolean = true,
    /**
     * 用户口径（2026-11-26）**「继续观看」刷新后要回到最左端**。
     *
     * 传一个「数据版本号」进来：值一变就把横条滚回位置 0，
     * 这样刚播完同步过来的那部一定出现在最左边、直接可见，
     * 不用再往右划才能看到。
     *
     * 其它 shelf 不传（null）→ 行为完全不变，用户手动滚到哪就停在哪。
     */
    resetKey: Any? = null,
    itemContent: @Composable (Item) -> Unit,
) {
    // 用户口径（2026-11-23）：模块之间的竖向间距 18dp，紧凑（参考图那密度）。
    Column(modifier.fillMaxWidth().padding(top = 18.dp)) {
        SectionHeader(title, onMore = onMore, showChevron = showChevron)
        val listState = rememberLazyListState()
        if (resetKey != null) {
            LaunchedEffect(resetKey) {
                // 回到最左端；没有动画，避免看到横条「划回去」的过程。
                listState.scrollToItem(0)
            }
        }
        LazyRow(
            state = listState,
            // Starts clear of the side dock but scrolls on under its glass.
            contentPadding = PaddingValues(start = LocalStartInset.current + Etkn.space.screen, end = Etkn.space.screen),
            horizontalArrangement = Arrangement.spacedBy(Etkn.space.item),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            items(items, key = { it.id.ifEmpty { it.key ?: it.name } }, contentType = { it.type }) { itemContent(it) }
        }
    }
}

fun LazyListScope.shelf(
    key: String,
    title: String,
    items: List<Item>,
    onMore: (() -> Unit)? = null,
    showChevron: Boolean = true,
    /** 见 [Shelf]：非空时数据一变就滚回最左端。 */
    resetKey: Any? = null,
    itemContent: @Composable (Item) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = key, contentType = "shelf") {
        Shelf(title, items, onMore = onMore, showChevron = showChevron, resetKey = resetKey, itemContent = itemContent)
    }
}
