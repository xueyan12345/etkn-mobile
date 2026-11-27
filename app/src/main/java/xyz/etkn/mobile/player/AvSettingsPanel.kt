package xyz.etkn.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Subtitles
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.round

/**
 * 用户口径（2026-11-27，#98）**三行固定不动，点开时小菜单「浮」在第二行下面覆盖下层**。
 *
 * 用户原话（说了两遍，第一版我做错了）：
 * ```
 * 「我是说这三行位置就不要动，固定在这了，就算我点开选项，比如说第2个能点开，
 *   点开的话，这3个也不要动，在这个第二个点开的基础上弹出一个小对话框，
 *   也就是弹出一个小菜单，可以选」
 *
 * 「现在的样子是我点第二行，它整个三行的位置就跳起来了」
 *
 * 「相当于原来的三行位置不要动，点开之后它也固定住不动，信息单独弹出来一个小框」
 * ```
 *
 * ## 第一版错在哪
 *
 * 我把展开的音轨列表**插进了同一个 Column**：
 * ```
 * Column {
 *   视频行
 *   音频行
 *   音轨列表   ← 插在这里，把下面的内容全推下去
 * }
 * ```
 * 布局重新测量 → 整个卡片变高 → 三行跟着跳。用户看到的就是「三行跳起来」。
 *
 * ## 正确做法
 *
 * `Box` 做锚点容器：**三行按正常流式排版，高度恒定**，
 * 音轨菜单用 `offset` 绝对定位叠在音频行下方，**不参与 Column 测量**：
 * ```
 * Box {
 *   Column {                 ← 高度永远不变
 *     视频行
 *     音频行  ← 菜单从这里往下浮
 *     字幕行  ← 被菜单盖住，但位置不动
 *   }
 *   if (展开) OptionOverlay(offset(y = 音频行下沿))
 * }
 * ```
 */
@Composable
internal fun AvSettingsPanel(
    /** 视频行主文本，如「4K HDR10 HEVC」 */
    videoTitle: String,
    /** 视频行副文本，如「5.1 GB / 14.7 Mbps」 */
    videoSubtitle: String?,
    /** 音频当前值，如「国语 DDP Atmos 5.1 (默认)」 */
    audioTitle: String,
    /** 音频副文本，如「国语」 */
    audioSubtitle: String?,
    /** 可选画质/版本（label → 是否选中） */
    videoOptions: List<Pair<String, Boolean>> = emptyList(),
    onSelectVideo: (Int) -> Unit = {},
    /** 可选音轨（label → 是否选中） */
    audioOptions: List<Pair<String, Boolean>>,
    onSelectAudio: (Int) -> Unit,
    /** 字幕当前值 */
    subtitleTitle: String? = null,
    subtitleSubtitle: String? = null,
    subtitleOptions: List<Pair<String, Boolean>> = emptyList(),
    onSelectSubtitle: (Int) -> Unit = {},
) {
    /**
     * 当前展开哪一行：0=无，1=视频，2=音频，3=字幕。
     *
     * 用户口径（#99）：**三行都要能展开成浮层**，不是只有音轨。
     * 「不光音轨菜单改成浮层覆盖，这3个都要改成浮层覆盖，
     *   只不过正好我发你的那个图，它是只有音轨，是有选项的，其他的两个也可能有选项」
     *
     * 三行的位置与这个状态无关 —— 浮层不参与 Column 测量。
     */
    var openRow by remember { mutableStateOf(0) }

    /**
     * 三行区块在屏幕上的位置，用来把 Popup 精确锚到被点那一行的下沿。
     *
     * 用户口径（#102）**上一版还是没固定住**：
     * ```
     * 「三行媒体信息也没固定住，跟上个版本一样」
     * ```
     * 上一版用的是 `Modifier.offset` —— 我搞错了一个关键点：
     * **`offset` 只改变绘制位置，不改变测量尺寸**。
     * 浮层比三行本身高，`Box` 的高度取 `max(子项)`，
     * 于是 Box 被撑高 → 面板变高 → 三行往上跳。
     *
     * 真正不参与父布局测量的只有 **Popup / Dialog**（各自独立窗口）。
     * 所以改用 `Popup`：三行区块的高度跟 openRow 完全无关，一动不动。
     */
    var anchorInRoot by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var anchorSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "播放设置",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )

        Box(
            Modifier
                .fillMaxWidth()
                /**
                 * 记录区块左上角在窗口中的绝对位置。Popup 用窗口坐标，
                 * 必须把本区块的偏移和尺寸都算进去，否则菜单会飘到别处。
                 */
                .onGloballyPositioned { coords ->
                    anchorInRoot = coords.positionInRoot()
                    anchorSize = coords.size
                },
        ) {
            Column(Modifier.fillMaxWidth()) {
                /** ① 视频行：有可选画质时才可点开浮层。 */
                InfoRow(
                    icon = { Icon(Icons.Rounded.Movie, null, Modifier.size(20.dp)) },
                    title = videoTitle,
                    subtitle = videoSubtitle,
                    trailing = if (videoOptions.isEmpty()) null else {
                        {
                            Icon(
                                if (openRow == 1) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                if (openRow == 1) "收起" else "展开",
                                Modifier.size(22.dp),
                            )
                        }
                    },
                    onClick = if (videoOptions.isEmpty()) null else {
                        { openRow = if (openRow == 1) 0 else 1 }
                    },
                )

                /** ② 音频行：点击开合菜单。 */
                InfoRow(
                    icon = { Icon(Icons.Rounded.Audiotrack, null, Modifier.size(20.dp)) },
                    title = audioTitle,
                    subtitle = audioSubtitle,
                    trailing = {
                        Icon(
                            if (openRow == 2) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            if (openRow == 2) "收起" else "展开",
                            Modifier.size(22.dp),
                        )
                    },
                    onClick = { openRow = if (openRow == 2) 0 else 2 },
                )

                /** ③ 字幕行。 */
                InfoRow(
                    icon = { Icon(Icons.Rounded.Subtitles, null, Modifier.size(20.dp)) },
                    title = subtitleTitle ?: "字幕",
                    subtitle = subtitleSubtitle,
                    trailing = if (subtitleOptions.isEmpty()) null else {
                        {
                            Icon(
                                if (openRow == 3) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                if (openRow == 3) "收起" else "展开",
                                Modifier.size(22.dp),
                            )
                        }
                    },
                    onClick = if (subtitleOptions.isEmpty()) null else {
                        { openRow = if (openRow == 3) 0 else 3 }
                    },
                )
            }

            /**
             * 浮层菜单：用 **Popup** 渲染，独立窗口，**完全不参与父布局测量**。
             *
             * 被点那一行的下沿（相对区块顶部）：
             * ```
             * 第 1 行 → ROW_HEIGHT × 1
             * 第 2 行 → ROW_HEIGHT × 2
             * 第 3 行 → ROW_HEIGHT × 3
             * ```
             * Popup 的 offset 是相对窗口的，所以 = 区块在窗口里的 y + 行偏移。
             */
            if (openRow != 0) {
                val opts = when (openRow) {
                    1 -> videoOptions
                    2 -> audioOptions
                    else -> subtitleOptions
                }
                val pick: (Int) -> Unit = when (openRow) {
                    1 -> onSelectVideo
                    2 -> onSelectAudio
                    else -> onSelectSubtitle
                }
                androidx.compose.ui.window.Popup(
                    alignment = Alignment.TopStart,
                    offset = androidx.compose.ui.unit.IntOffset(
                        x = anchorInRoot.x.toInt(),
                        y = anchorInRoot.y.toInt() + (ROW_HEIGHT * openRow).roundToPx(),
                    ),
                    /** 点浮层外面就收起 —— 但不要关掉整个面板。 */
                    onDismissRequest = { openRow = 0 },
                    properties = androidx.compose.ui.window.PopupProperties(
                        focusable = true,
                        dismissOnClickOutside = true,
                    ),
                ) {
                    OptionOverlay(
                        options = opts,
                        onPick = pick,
                        /** Popup 有自己窗口，宽度用区块宽度算，跟卡片对齐。 */
                        width = with(androidx.compose.ui.platform.LocalDensity.current) {
                            anchorSize.width.toDp()
                        },
                    )
                }
            }
        }
    }
}

/**
 * 单行固定高度。
 *
 * 主文本约 22dp + 副文本约 18dp + 上下 padding 20dp ≈ 60dp，取 62 留呼吸。
 * 三行都用 `heightIn(min = ROW_HEIGHT)` 钉住最小高度，
 * 保证「有副文本」和「没副文本」的行高一致，浮层偏移才算得准。
 */
private val ROW_HEIGHT = 62.dp

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
            .heightIn(min = ROW_HEIGHT)
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

/**
 * 下拉选项浮层：**覆盖式**，本身不占三行区块的布局空间（外层是 Popup 独立窗口）。
 *
 * 参考图形态：浅色圆角浮层、宽度与卡片对齐、勾在左边、选中行整行高亮。
 * 必须有实底（`surfaceContainerHigh`），否则下层文字会透上来。
 */
@Composable
private fun OptionOverlay(
    options: List<Pair<String, Boolean>>,
    onPick: (Int) -> Unit,
    /** Popup 有独立窗口，拿不到父宽度，由调用方传入区块宽度。 */
    width: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .width(width)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (label, selected) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else Color.Transparent,
                        )
                        .clickable { onPick(i) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check, null,
                                Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
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
