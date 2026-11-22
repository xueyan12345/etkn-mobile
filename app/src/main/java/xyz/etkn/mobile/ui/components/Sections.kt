package xyz.etkn.mobile.ui.components

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
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, onMore: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = LocalStartInset.current + Etkn.space.screen, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            // 用户口径（2026-11-22）：区块标题参考图那种更黑、更硬气的样式 —— 加粗 + 稍小字号，看着匀称不抢戏。
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        if (onMore != null) {
            IconButton(onClick = onMore) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "查看全部") }
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
    itemContent: @Composable (Item) -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = Etkn.space.section)) {
        SectionHeader(title, onMore = onMore)
        LazyRow(
            // Starts clear of the side dock but scrolls on under its glass.
            contentPadding = PaddingValues(start = LocalStartInset.current + Etkn.space.screen, end = Etkn.space.screen),
            // 用户口径（2026-11-22）：卡片间距加大一点（原来 12dp 显得稀稀拉拉，现在 14dp 更紧凑整齐）。
            horizontalArrangement = Arrangement.spacedBy(Etkn.space.item),
            modifier = Modifier.padding(top = 6.dp),
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
    itemContent: @Composable (Item) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = key, contentType = "shelf") { Shelf(title, items, onMore = onMore, itemContent = itemContent) }
}
