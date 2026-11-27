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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
            /**
             * 用户口径（#96）**音轨不再是"只有列表"的面板**。
             *
             * 改成「固定行（视频/音频当前值）+ 点音频行展开下拉列表」，
             * 见 AvSettingsPanel 的类注释。
             * 视频行只展示当前画质（参考图里它没有下拉箭头）。
             */
            Sheet.Audio -> AvSettingsPanel(
                videoTitle = ui.sources.getOrNull(ui.sourceIndex)?.let { describeQuality(it) } ?: "未知",
                videoSubtitle = ui.sources.getOrNull(ui.sourceIndex)?.let { describeBitrate(it) },
                audioTitle = ui.audio.firstOrNull { it.selected }?.label
                    ?: ui.audio.firstOrNull()?.label ?: "无音轨",
                audioSubtitle = ui.audio.firstOrNull { it.selected }?.stream?.let { describeAudioSub(it) },
                audioOptions = ui.audio.map { it.label to it.selected },
            ) { i -> session.selectTrack(ui.audio[i]) }
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
    /**
     * 用户口径（2026-11-26）**修「播放器选集只能看到当前集和后面的集，前面的看不到」**。
     *
     * 上一版为了「能定位到当前集」，用了服务端的 `AnchorId` 以当前集为锚点请求。
     * 但那个参数返回的是「从当前集开始往后」的一段 ——
     * 当前集之前的集根本不在列表里，所以无论怎么往上滑都看不到前面的集。
     * 用户看到的正是：第 29 集打开，列表从 29 开始，28 及以前全没有。
     *
     * 现在改成**取整季**（和详情页同一套做法）：
     *  · 一次请求拿全这一季，前面后面的集都在
     *  · 当前集只是「滚到那个位置」，不再影响列表内容
     *  · 和详情页保持一致，两边行为也就不会互相矛盾
     */
    var episodes by remember(current.seasonId, current.id) { mutableStateOf<List<Item>?>(null) }
    LaunchedEffect(current.seasonId, current.id) {
        /**
         * 用户口径（2026-11-26）**服务端单页硬上限 80 条**（实测传 100/2000 都只返 80），
         * 所以这里必须按 StartIndex 翻完整季，否则长剧（蜡笔小新 2377 集）
         * 只能看到前 80 集，当前集之前的集全都看不到。
         */
        /**
         * 注意：`runCatching` 的 lambda 不是 suspend 作用域，
         * 里面不能直接用 coroutineScope / async（编译会报
         * "Cannot infer type" / "'async' can not be called without the corresponding coroutine scope"）。
         * 所以外层用 try/catch，并发放在 suspend 的 coroutineScope 里。
         */
        episodes = try {
            val first = graph.api.episodePage(seriesId, current.seasonId, start = 0, limit = EPISODE_PAGE)
            val total = maxOf(first.total, first.items.size)
            val restStarts = (first.items.size until total step EPISODE_PAGE).toList()
            val rest: List<List<Item>> = if (restStarts.isEmpty()) {
                emptyList()
            } else {
                coroutineScope {
                    restStarts.map { start ->
                        async {
                            runCatching {
                                graph.api.episodePage(seriesId, current.seasonId, start = start, limit = EPISODE_PAGE).items
                            }.getOrDefault(emptyList())
                        }
                    }.awaitAll()
                }
            }
            (listOf(first.items) + rest)
                .filter { page -> page.isNotEmpty() }
                .sortedBy { page -> page.firstOrNull()?.indexNumber ?: Int.MAX_VALUE }
                .flatten()
                .let { all ->
                    val seen = HashSet<String>(all.size)
                    all.filter { seen.add(it.id) }
                }
        } catch (error: Exception) {
            emptyList()
        }
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

/**
 * 用户口径（2026-11-26）：服务端 EpisodePage 单页硬上限是 80 条
 * （实测 Limit 传 100/200/500/2000 都只返 80），所以按 80 翻页。
 */
private const val EPISODE_PAGE = 80


/**
 * 用户口径（#96）**把 MediaSource 描述成参考图那种「4K HDR10 HEVC」**。
 *
 * 参考图第一行主文本就是这个格式：分辨率 + HDR + 编码。
 * 缺哪个就跳过哪个，不硬凑。
 */
internal fun describeQuality(source: MediaSource): String {
    val v = source.video
    val parts = mutableListOf<String>()
    /** 分辨率：优先用宽高判档，其次用服务端给的 height。 */
    val h = v?.height ?: 0
    val w = v?.width ?: 0
    parts += when {
        h >= 2000 || w >= 3500 -> "4K"
        h >= 1400 || w >= 2500 -> "2K"
        h >= 1000 || w >= 1800 -> "1080P"
        h >= 700 -> "720P"
        h > 0 -> "${h}P"
        else -> source.name.ifBlank { "视频" }
    }
    /** HDR：MediaStream 里有 videoRange / 颜色传输字段。 */
    val range = v?.videoRange.orEmpty()
    if (range.contains("HDR", true) || range.contains("PQ", true) || range.contains("HLG", true)) {
        parts += range.uppercase()
    }
    /** 编码：codec 里通常带 hevc / h264 / av1。 */
    val codec = v?.codec.orEmpty()
    parts += when {
        codec.contains("hevc", true) || codec.contains("h265", true) -> "HEVC"
        codec.contains("av1", true) -> "AV1"
        codec.contains("h264", true) || codec.contains("avc", true) -> "H.264"
        else -> codec.substringBefore('.').uppercase().takeIf { it.isNotBlank() } ?: ""
    }
    return parts.filter { it.isNotBlank() }.joinToString(" ")
}

/** 副标题：码率 / 文件大小，参考图是「3.6 GB / 13.1 Mbps」。 */
internal fun describeBitrate(source: MediaSource): String? {
    val out = mutableListOf<String>()
    if (source.size > 0) {
        val gb = source.size / 1024.0 / 1024 / 1024
        out += if (gb >= 1) String.format("%.1f GB", gb) else String.format("%.0f MB", source.size / 1024.0 / 1024)
    }
    if (source.bitrate > 0) out += String.format("%.1f Mbps", source.bitrate / 1_000_000.0)
    return out.joinToString(" / ").ifBlank { null }
}

/** 音轨副文本：编码 + 声道，参考图是「DDP5.1」。 */
internal fun describeAudioSub(track: MediaStream): String? {
    val codec = track.codec.orEmpty()
    val ch = track.channels ?: 0
    val c = when {
        codec.contains("eac3", true) || codec.contains("ddp", true) -> "DDP"
        codec.contains("ac3", true) -> "AC3"
        codec.contains("truehd", true) -> "TrueHD"
        codec.contains("dts", true) -> "DTS"
        codec.contains("aac", true) -> "AAC"
        else -> codec.substringBefore('.').uppercase().takeIf { it.isNotBlank() } ?: ""
    }
    val layout = when (ch) {
        8 -> "7.1"
        6 -> "5.1"
        2 -> "stereo"
        1 -> "mono"
        else -> ""
    }
    return (c + layout).ifBlank { null }
}
