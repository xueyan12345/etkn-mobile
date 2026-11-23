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
            Sheet.More -> MoreSheet(ui, session, onSheet, onDismiss, onRotate, portrait)
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

/** 定时关闭 and 用其他播放器打开. */
@Composable
private fun MoreSheet(
    ui: VideoUi,
    session: VideoSession,
    onSheet: (Sheet) -> Unit,
    onDismiss: () -> Unit,
    onRotate: () -> Unit,
    portrait: Boolean,
) {
    val context = LocalContext.current
    val graph = context.graph
    var showStats by remember { mutableStateOf(false) }
    /**
     * 用户口径（2026-11-23）：三点菜单照参考图重做 —— 右侧浮层，每行 = 图标 + 文字，
     * 不再用居中的普通列表。项目按参考图：旋转屏幕 / 跳过片头片尾 / 仅音频播放 /
     * 定时关闭 / 切换至 Mpv 播放器 / 统计信息，底部显示当前倍速。
     */
    Row(
        Modifier.fillMaxWidth().fillMaxHeight(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            Modifier.width(300.dp).fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.88f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // 顶部：倍速状态
            Row(
                Modifier.fillMaxWidth().clickable { onDismiss(); onSheet(Sheet.Speed) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Speed, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Text("倍速", color = Color.White, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    "%.1fx".format(ui.speed),
                    color = Color(0xFF8FD8C8),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            MenuDivider()
            MoreMenuItem(Icons.Rounded.ScreenRotation, "旋转屏幕") {
                onDismiss(); onRotate()
            }
            MoreMenuItem(
                Icons.Rounded.SkipNext,
                "跳过片头片尾",
                trailing = ui.skipMode.label,
            ) { onDismiss() }
            MoreMenuItem(
                Icons.Rounded.Timer,
                "定时关闭",
                trailing = ui.sleep?.label,
            ) {
                onDismiss(); onSheet(Sheet.Sleep)
            }
            MoreMenuItem(Icons.Rounded.Info, "统计信息") {
                onDismiss(); showStats = true
            }
            MenuDivider()
            MoreMenuItem(Icons.Rounded.OpenInNew, "用其他播放器打开") {
                onDismiss()
                session.portableStream()?.let { (url, title) ->
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
                        .putExtra(Intent.EXTRA_TITLE, title).putExtra("title", title)
                    if (runCatching { context.startActivity(Intent.createChooser(view, "用其他播放器打开")) }.isSuccess) session.player?.pause()
                }
            }
        }
    }
    if (showStats) {
        StatsDialog(ui, onDismiss = { showStats = false })
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

/** 统计信息：把播放器的关键数据摊开，方便排查卡顿/解码问题。 */
@Composable
private fun StatsDialog(ui: VideoUi, onDismiss: () -> Unit) {
    val source = ui.sources.getOrNull(ui.sourceIndex)
    val video = source?.video
    val lines = buildList {
        add("剧集：" + ui.title)
        add("容器：" + (source?.container?.uppercase() ?: "-"))
        video?.let {
            add("画面：%d×%d".format(it.width, it.height))
            add("编码：" + it.codec.uppercase())
        }
        add("总码率：%.1f Mbps".format((source?.bitrate ?: 0L) / 1_000_000.0))
        add("音轨：" + ((source?.audio?.size ?: 0)).toString() + " 条")
        add("字幕：" + ((source?.subtitles?.size ?: 0)).toString() + " 条")
        add("倍速：%.2fx".format(ui.speed))
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("统计信息") },
        text = {
            Column {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
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
    var episodes by remember(current.seasonId) { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(current.seasonId) {
        episodes = runCatching { graph.api.episodePage(seriesId, current.seasonId, start = 0, limit = 200).items }.getOrDefault(emptyList())
    }
    Text(current.seasonName ?: "选集", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    val list = episodes
    if (list == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
        return
    }
    val urls = images()
    val listState = rememberLazyListState(list.indexOfFirst { it.id == current.id }.coerceAtLeast(0))
    LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
