package xyz.etkn.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 用户口径（2026-11-27，#96）**音视频设置改成「固定行 + 下拉展开」**。
 *
 * 用户原话：
 * ```
 * 「这块的弹出方式有问题，不要这样的，固定三大行不要动，
 *   点击的时候是额外弹出个下拉的菜单可以选，我给你个参考图，按照这样做」
 * ```
 *
 * 参考图（《无声的回响》那个播放器）的结构：
 * ```
 * ┌─────────────────────────────┐
 * │ 🎬 4K HDR10 HEVC            │  ← 固定行，显示当前值
 * │    13.1 Mbps                │
 * ├─────────────────────────────┤
 * │ 🎵 DDP 5.1 (默认)      ▲    │  ← 固定行，右侧箭头表示可展开
 * │    DDP5.1                   │
 * ├─────────────────────────────┤
 * │    关闭                     │  ← 展开的下拉列表
 * │ ✓  DDP 5.1 (默认)   [高亮]  │
 * │    DTS 5.1                  │
 * │    AAC stereo               │
 * └─────────────────────────────┘
 * ```
 *
 * ## 和旧实现的区别
 *
 * 旧：点「音轨」直接弹一个只有选项列表的面板（`OptionList`），
 * 没有"当前值"的固定行，也看不到画质信息 —— 所以用户说"弹出方式有问题"。
 *
 * 新：一个面板里同时有
 * ```
 * ① 视频行（固定，不可展开）：图标 + 当前画质主文本 + 码率副文本
 * ② 音频行（固定，可点击展开）：图标 + 当前音轨 + 右侧 ▲/▼
 * ③ 展开区：这个音轨的全部可选值，选中项打勾 + 整行高亮
 * ```
 * 点音频行才展开/收起，点选项才切换 —— 行本身不动。
 */
@Composable
internal fun AvSettingsPanel(
    /** 视频行主文本，如「4K HDR10 HEVC」 */
    videoTitle: String,
    /** 视频行副文本，如「13.1 Mbps」 */
    videoSubtitle: String?,
    /** 音频当前值，如「DDP 5.1 (默认)」 */
    audioTitle: String,
    /** 音频当前值副文本，如「DDP5.1」 */
    audioSubtitle: String?,
    /** 可选音轨（label → 是否选中） */
    audioOptions: List<Pair<String, Boolean>>,
    onSelectAudio: (Int) -> Unit,
) {
    /**
     * 展开状态：默认展开音频（用户点这个面板主要就是为了切音轨）。
     * 视频行固定不展开 —— 参考图里视频行也是没有箭头的。
     */
    var audioExpanded by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "播放设置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )

        /** ① 视频行：固定，不可展开（参考图里它没有箭头）。 */
        InfoRow(
            icon = { Icon(Icons.Rounded.Movie, null, Modifier.size(20.dp)) },
            title = videoTitle,
            subtitle = videoSubtitle,
            trailing = null,
        )

        Spacer(Modifier.size(8.dp))

        /** ② 音频行：固定，点击展开/收起。 */
        InfoRow(
            icon = { Icon(Icons.Rounded.Audiotrack, null, Modifier.size(20.dp)) },
            title = audioTitle,
            subtitle = audioSubtitle,
            trailing = {
                Icon(
                    if (audioExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    if (audioExpanded) "收起" else "展开",
                    Modifier.size(22.dp),
                )
            },
            onClick = { audioExpanded = !audioExpanded },
        )

        /** ③ 展开区：音轨列表。 */
        if (audioExpanded) {
            Spacer(Modifier.size(4.dp))
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(audioOptions.size) { i ->
                    val (label, selected) = audioOptions[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.secondaryContainer
                                else Color.Transparent,
                            )
                            .clickable { onSelectAudio(i) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        /** 选中打勾（参考图：勾在左边）。 */
                        Spacer(Modifier.width(22.dp))
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check, null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** 一行「图标 + 主文本 + 副文本 + 右侧箭头」。 */
@Composable
private fun InfoRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)?,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}
