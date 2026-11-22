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
 * 用户口径（2026-11-22）：
 * · 去掉右侧的 → 箭头（参考图没有箭头，点了也多余）
 * · 标题字号比上一版稍大一点（titleMedium → 17sp 左右），加粗，看着有分量但不抢戏
 * · 上下留白收紧，跟参考图一样紧凑
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    showArrow: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().padding(start = LocalStartInset.current + Etkn.space.screen, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        )
    }
}

/** Horizontally scrolling shelf with a header; items are keyed for stable scrolling. */
@Composable
fun Shelf(
    title: String,
    items: List<Item>,
    modifier: Modifier = Modifier,
    onMore: (() -> Unit)? = null,
    showArrow: Boolean = true,
    itemContent: @Composable (Item) -> Unit,
) {
    // 用户口径（2026-11-22）：模块之间的竖向间距从 28dp 收到 18dp，整页更紧凑（参考图那密度）。
    Column(modifier.fillMaxWidth().padding(top = 18.dp)) {
        SectionHeader(title, onMore = onMore, showArrow = showArrow)
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
    showArrow: Boolean = true,
    itemContent: @Composable (Item) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = key, contentType = "shelf") {
        Shelf(title, items, onMore = onMore, showArrow = showArrow, itemContent = itemContent)
    }
}
