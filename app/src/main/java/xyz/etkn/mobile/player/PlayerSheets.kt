package xyz.etkn.mobile.player

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.etkn.mobile.data.cast.CastDevice
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.CardShape
import xyz.etkn.mobile.ui.components.images
import xyz.etkn.mobile.ui.components.px
import xyz.etkn.mobile.ui.screens.me.DanmakuControls
import java.util.Locale
import xyz.etkn.mobile.ui.theme.Corner
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize

@Composable
internal fun PlayerSheet(
    sheet: Sheet,
    ui: VideoUi,
    session: VideoSession,
    onSheet: (Sheet) -> Unit,
    onDismiss: () -> Unit,
    /** 用户口径（2026-11-22）：横竖屏切换挪到「三点」菜单最上方。 */
    onRotate: () -> Unit = {},
    portrait: Boolean = false,
) {
    /**
     * 用户口径（2026-11-26）：不再需要 rotated 参数。
     * 面板由 PlayerScreen 直接渲染在「已旋转的画布」内部，
     * 画布坐标系等于用户看到的画面坐标系，方向自动正确。
     */
    PlayerSidePanel(onDismiss) {
        when (sheet) {
            Sheet.Audio -> OptionList("音轨", ui.audio.map { it.label to it.selected }) { i -> session.selectTrack(ui.audio[i]); onDismiss() }
            Sheet.Subtitle -> Column {
                ui.subtitleOffset?.let { SubtitleOffsetRow(it, session) }
                OptionList(
                    "字幕",
                    listOf("关闭" to !ui.textEnabled) + ui.text.map { it.label to (ui.textEnabled && it.selected) },
                ) { i -> if (i == 0) session.disableSubtitles() else session.selectTrack(ui.text[i - 1]); onDismiss() }
            }
            Sheet.Speed -> OptionList("播放速度", Speeds.map { "${it}×" to (it == ui.speed) }) { i -> session.setSpeed(Speeds[i]); onDismiss() }
            Sheet.Versions -> OptionList("版本", ui.sources.mapIndexed { i, s -> s.name.ifEmpty { s.slotLabel } to (i == ui.sourceIndex) }) { i ->
                session.selectSource(i); onDismiss()
            }
            Sheet.Sleep -> OptionList("定时关闭", listOf("关闭" to (ui.sleep == null)) + SleepTimer.entries.map { it.label to (it == ui.sleep) }) { i ->
                session.setSleepTimer(SleepTimer.entries.getOrNull(i - 1))
                onDismiss()
            }
            Sheet.Cast -> CastSheet(ui, session, onDismiss)
            Sheet.Episodes -> EpisodeSheet(ui, session, onDismiss)
            Sheet.Danmaku -> DanmakuSheet(session)
            Sheet.None -> Unit
        }
    }
}

@Composable
private fun DanmakuSheet(session: VideoSession) {
    val style by session.danmakuStyle.collectAsStateWithLifecycle()
    Text("弹幕", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    Column(Modifier.verticalScroll(rememberScrollState())) {
        DanmakuControls(style, session::setDanmakuStyle)
    }
}

/** Shift the shown subtitle in 0.1 s / 0.5 s steps; 保存 makes it this version's calibration everywhere. */
@Composable
private fun SubtitleOffsetRow(offset: SubtitleOffset, session: VideoSession) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字幕偏移", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                "%+.1f 秒".format(Locale.ROOT, offset.ms / 1000f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-500, -100, 100, 500).forEach { step ->
                FilledTonalButton(onClick = { session.shiftSubtitles(step) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                    Text("%+.1f".format(Locale.ROOT, step / 1000f), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (offset.ms != offset.saved) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { session.shiftSubtitles(offset.saved - offset.ms) }) { Text("还原") }
                TextButton(onClick = session::saveSubtitleOffset) { Text("保存") }
            }
        }
    }
}


/** 三点菜单的一行：图标 + 文字 +（可选）右侧状态值。 */
@Composable
private fun MoreMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    // 用户口径（2026-11-23）：照参考图实测比例
    //   行高 = 面板宽 24.5%（约 60dp）· 图标 = 面板宽 8.6%（约 24dp）
    //   左内边距 = 面板宽 3.9%（约 16dp）· 行尾值右内边距约 8dp · 无分隔线
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(18.dp))
        Text(
            label,
            color = Color.White,
            fontSize = 18.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (!trailing.isNullOrEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, color = Color.White.copy(alpha = 0.55f), fontSize = 15.sp, maxLines = 1)
        }
    }
}

@Composable
private fun MenuDivider() {
    // 用户口径（2026-11-23）：照参考图，菜单行之间不画分隔线，靠留白分隔。
}


/** DLNA renderers on the Wi-Fi; picking one hands the title over at the current position. */
@Composable
private fun CastSheet(ui: VideoUi, session: VideoSession, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    var devices by remember { mutableStateOf<List<CastDevice>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        devices = null
        devices = runCatching { graph.cast.discover() }.getOrDefault(emptyList())
    }
    Text("投屏", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    ui.castingTo?.let { name ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("正在投屏到 $name", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            TextButton(onClick = { session.stopCast(); onDismiss() }) { Text("停止") }
        }
    }
    when (val found = devices) {
        null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
        else -> {
            if (found.isEmpty()) {
                Text("没有找到投屏设备", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
            OptionListBody(found.map { it.name to (it.name == ui.castingTo) }) { index ->
                session.castTo(found[index])
                onDismiss()
            }
            TextButton(onClick = { round++ }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("重新搜索") }
        }
    }
}

@Composable
private fun OptionList(title: String, options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    if (options.isEmpty()) {
        Text("没有可选项", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        return
    }
    OptionListBody(options, onSelect)
}

@Composable
private fun OptionListBody(options: List<Pair<String, Boolean>>, onSelect: (Int) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp), state = rememberLazyListState(options.indexOfFirst { it.second }.coerceAtLeast(0))) {
        items(options.size) { index ->
            val (label, selected) = options[index]
            Row(
                Modifier.fillMaxWidth().clip(CardShape)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(index) }.padding(horizontal = 12.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                    color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                )
                if (selected) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun EpisodeSheet(ui: VideoUi, session: VideoSession, onDismiss: () -> Unit) {
    val current = ui.item ?: return
    val seriesId = current.seriesId ?: return
    val graph = LocalContext.current.graph
    /**
     * 用户口径（2026-11-26）**修「选集菜单打开定位不到当前集」**。
     *
     * 根因：原来固定 `start = 0, limit = 200` —— 只拉前 200 集。
     * 《蜡笔小新》有 1200+ 集，当前播到 1265，列表里根本没有它，
     * 所以 `indexOfFirst { it.id == current.id }` 返回 -1 → 定位到第 0 条。
     * 用户在截图里看到停在 1075-1081，就是「只加载了前 200 条」的后果。
     *
     * 修法：用服务端的 `AnchorId` 以当前集为锚点请求，
     * 拿到的是「当前集前后各若干集」，当前集一定在结果里，定位就准了。
     */
    var episodes by remember(current.seasonId, current.id) { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(current.seasonId, current.id) {
        episodes = runCatching {
            graph.api.episodePage(
                seriesId, current.seasonId,
                anchorId = current.id,
                limit = 200,
            ).items
        }.getOrDefault(emptyList())
    }
    Text(current.seasonName ?: "选集", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    val list = episodes
    if (list == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
        return
    }
    val urls = images()
    /**
     * 用户口径（2026-11-26）：当前集不要顶在第 0 条，往上留几条，
     * 这样「当前集 + 它上面几集 + 下面几集」都能一眼看到。
     */
    val currentIndex = list.indexOfFirst { it.id == current.id }
    val listState = rememberLazyListState(
        (currentIndex - 3).coerceAtLeast(0),
    )
    /**
     * 展开后再校正一次：数据异步到达时上面的初值可能还算不出来，
     * 这里等 list 变化后主动滚一次，保证一定落在当前集附近。
     */
    LaunchedEffect(list.size, current.id) {
        if (currentIndex >= 0) listState.scrollToItem((currentIndex - 3).coerceAtLeast(0))
    }
    /**
     * 用户口径（2026-11-26）：`Modifier.weight(1f)` 让列表在面板高度上限内
     * 占满剩余空间。不加 weight 时 LazyColumn 在「按内容包裹」的父容器里
     * 会被量成刚好装下全部内容，滚动与惯性都会失效（OPPO 平板上表现为只能一格格扒）。
     */
    /**
     * 用户口径（2026-11-26）：这里不能写 `Modifier.weight(1f)`
     * —— EpisodeSheet 不在 ColumnScope 里，weight 不可用（编译报 Unresolved reference）。
     * 面板本身已经用 `heightIn(max = 画布高 88%)` 给了高度上限，
     * 列表用 fillMaxSize 就会填满这个上限，从而恢复滚动与惯性。
     */
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(list, key = { it.id }) { episode ->
            val selected = episode.id == current.id
            Row(
                Modifier.fillMaxWidth().clip(CardShape)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { session.playEpisode(episode); onDismiss() }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(
                    urls.primary(episode, 240.dp.px()),
                    Modifier.width(112.dp).aspectRatio(16f / 9f).clip(Corner.card),
                    episode.name, placeholder = Icons.Rounded.Movie,
                )
                Column(Modifier.weight(1f).padding(start = 12.dp).fillMaxHeight()) {
                    Text(
                        listOfNotNull(episode.indexNumber?.let { "$it." }, episode.name).joinToString(" "),
                        style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    episode.overview?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (episode.userData.played) Icon(Icons.Rounded.Check, "已观看", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
