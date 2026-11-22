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

/**
 * 区块标题。
 *
 * 用户口径（2026-11-23）：
 * · 字号在上一版基础再放大一档（titleMedium → titleLarge），加粗有分量
 * · 只有「媒体库」带 > 并且整行可点（点进去看媒体库列表）；
 *   其他区块（国产剧集/华语电影…）不带箭头
 * · 上下留白收紧，跟参考图一样紧凑
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    /** 标题后是否显示 > 并使整行可点。只有媒体库用。 */
    showChevron: Boolean = false,
) {
    val clickable = if (showChevron && onMore != null) Modifier.clickable(onClick = onMore) else Modifier
    Row(
        modifier.fillMaxWidth().padding(start = LocalStartInset.current + Etkn.space.screen, end = 8.dp).then(clickable),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (showChevron) Modifier else Modifier.weight(1f),
        )
        if (showChevron) {
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
    showChevron: Boolean = false,
    itemContent: @Composable (Item) -> Unit,
) {
    // 用户口径（2026-11-23）：模块之间的竖向间距 18dp，紧凑（参考图那密度）。
    Column(modifier.fillMaxWidth().padding(top = 18.dp)) {
        SectionHeader(title, onMore = onMore, showChevron = showChevron)
        LazyRow(
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
    showChevron: Boolean = false,
    itemContent: @Composable (Item) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = key, contentType = "shelf") {
        Shelf(title, items, onMore = onMore, showChevron = showChevron, itemContent = itemContent)
    }
}
