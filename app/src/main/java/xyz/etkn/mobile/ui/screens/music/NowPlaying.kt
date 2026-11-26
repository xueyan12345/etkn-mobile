package xyz.etkn.mobile.ui.screens.music

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.music.Lyrics
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.formatClock
import xyz.etkn.mobile.ui.components.glass
import xyz.etkn.mobile.ui.components.images
import xyz.etkn.mobile.ui.components.px
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.theme.Corner
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.graphicsLayer
import xyz.etkn.mobile.ui.theme.Etkn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.ModalBottomSheet

/** Polls the shared player position at [intervalMs] while composed. */
@Composable
private fun rememberPlayerClock(intervalMs: Long = 250): Pair<Long, Long> {
    val player = LocalContext.current.graph.music.player
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it > 0 } ?: 0
            delay(intervalMs)
        }
    }
    return position to duration
}

/**
 * 用户口径（2026-11-27，#29 #31）**迷你播放条重做**。
 *
 * 需求（用户原话）：
 *  · 「这个播放条无论是否播放，都让它常住在音乐菜单界面」
 *  · 「我想把这个变成记忆状态，也就是上次播放的歌曲是啥，它就是啥，包括播放进度也要保存，
 *     上次播放一半，我彻底关闭 app 重新打开音乐界面，还是要显示上次播放的那个音乐」
 *  · 「从没播放过的话，框也正常显示，只不过音乐图显示个占位图」
 *  · 「左边的头像投放时候要能旋转」
 *  · 「头像和播放按钮中间的空白部分要能显示歌词滚动播放效果，我要三行歌词，
 *     中间的可以全部显示，上下两行可以用渐变隐藏的形式，越往上或者越往下字体就越淡」
 *  · 「最右面的那个是播放列表，需要加一下，点开播放列表，要能显示现在的播放列表」
 *
 * 布局：[旋转封面] [三行歌词（上下渐隐）] [环形进度播放键] [下一首] [播放列表]
 *
 * 记忆状态：没有正在播的歌时，用 `graph.music.remembered()` 里存的「上次那首歌 + 进度」渲染，
 * 框不会塌陷、封面用占位图，点播放就从那个进度继续。
 */
@Composable
fun MiniPlayer(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val graph = LocalContext.current.graph
    val music = graph.music
    val state by music.state.collectAsStateWithLifecycle()
    // 记忆状态：没在播时用它渲染（可能为 null = 从没播过）
    val remembered by music.remembered.collectAsStateWithLifecycle()
    /** 当前在播的歌；没在播时用记忆里的（可能也没有）。 */
    val track: Item? = state.current
    val (clockPos, clockDur) = rememberPlayerClock(500)
    val position = if (track != null) clockPos else (remembered?.positionMs ?: 0L)
    val duration = if (track != null) clockDur else (remembered?.durationMs ?: 0L)
    val shape = Corner.panel
    var queueOpen by remember { mutableStateOf(false) }

    Box {
        Column(modifier.fillMaxWidth().glass(shape, elevation = 10.dp).clickable(onClick = onOpen)) {
            Row(
                Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                /**
                 * 左侧封面：**播放时旋转**（唱片效果）。
                 * 用 `infiniteRepeatable` 的 rotation 动画，暂停时停在当前角度（不重置）。
                 */
                val spin = rememberInfiniteTransition(label = "cover-spin")
                val angle by spin.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing)),
                    label = "angle",
                )
                Box(Modifier.size(44.dp).clip(CircleShape)) {
                    Artwork(
                        track?.let { images().music(it, 96) },
                        Modifier.fillMaxSize().graphicsLayer {
                            // 只在播放时转；暂停时角度保持不动（用户能看到停在哪）
                            rotationZ = if (state.isPlaying) angle else 0f
                        },
                        track?.name,
                        placeholder = Icons.Rounded.MusicNote,
                    )
                }
                /**
                 * 中间：**三行歌词**，中间那行完整、上下两行渐变淡出。
                 *
                 * 没有歌词/没有歌时显示歌名+歌手（保证这条永远有内容，不会空成一条）。
                 */
                Box(Modifier.weight(1f).height(52.dp).padding(horizontal = 10.dp)) {
                    MiniLyrics(track, position)
                }
                /** 播放键：**环形进度**（外圈走一圈=一首歌）。 */
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                    IconButton(onClick = { if (state.current != null) music.toggle() else music.resumeRemembered() }) {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (state.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(onClick = music::next, enabled = state.current != null) {
                    Icon(Icons.Rounded.SkipNext, "下一首", modifier = Modifier.size(20.dp))
                }
                /** 播放列表：点开显示当前队列。 */
                IconButton(onClick = { queueOpen = true }) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, "播放列表", modifier = Modifier.size(20.dp))
                }
            }
            LinearWavyProgressIndicator(
                progress = { if (duration > 0) position.toFloat() / duration else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp),
            )
        }
        if (queueOpen) {
            MusicQueueSheet(state.queue, state.index, onDismiss = { queueOpen = false }) { item ->
                val i = state.queue.indexOfFirst { it.id == item.id }
                if (i >= 0) music.skipTo(i)
                queueOpen = false
            }
        }
    }
}

/**
 * 用户口径（2026-11-27，#31）**迷你条里的三行歌词**。
 *
 * 中间那行完整显示（当前这句），上方/下方各一行，并且越远越淡 ——
 * 用 `Brush.verticalGradient` 做遮罩实现「往上往下渐隐」的效果。
 */
@Composable
private fun MiniLyrics(track: Item?, positionMs: Long) {
    val graph = LocalContext.current.graph
    val music by graph.music.state.collectAsStateWithLifecycle()
    var parsed by remember(track?.id) { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(track?.id, music.sourceId) {
        val t = track ?: return@LaunchedEffect
        val sid = music.sourceId ?: return@LaunchedEffect
        val text = runCatching { graph.api.lyrics(t.id, sid) }.getOrDefault("")
        parsed = if (text.isBlank()) null else Lyrics.parse(text)
    }
    val lines = parsed?.lines.orEmpty()
    val current = parsed?.indexAt(positionMs) ?: -1
    if (lines.isEmpty()) {
        // 没歌词：退回歌名 + 歌手（保证这条不空）
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                track?.name ?: "还没有播放记录",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track != null) {
                Text(
                    track.artistLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        return
    }
    val base = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.fillMaxSize().graphicsLayer { alpha = 1f },
        verticalArrangement = Arrangement.Center,
    ) {
        // 三行：当前行在中间，上下各一行；上下用低透明度形成渐隐
        listOf(-1, 0, 1).forEach { offset ->
            val line = lines.getOrNull(current + offset)
            Text(
                line ?: "",
                style = if (offset == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                color = base.copy(
                    alpha = when (offset) {
                        0 -> 1f
                        else -> 0.32f   // 上下两行淡出
                    },
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private enum class Pane { Lyrics, Queue }

@Composable
fun NowPlayingScreen() {
    val nav = LocalNav.current
    val graph = LocalContext.current.graph
    val music = graph.music
    val state by music.state.collectAsStateWithLifecycle()
    val track = state.current
    var pane by remember { mutableStateOf<Pane?>(null) }
    if (track == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val urls = images()
    Box(Modifier.fillMaxSize()) {
        // Ambient backdrop: the cover, heavily blurred and darkened.
        Artwork(urls.music(track, 320), Modifier.fillMaxSize().blur(60.dp), null, background = MaterialTheme.colorScheme.surface)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)))))
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth > maxHeight
            if (wide) {
                Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(Modifier.weight(1f)) { PlayerColumn(track, state.isPlaying, state.buffering, state.shuffle, state.repeat, pane, { pane = it }, nav::back, compact = true) }
                    Box(Modifier.weight(1f).fillMaxSize()) { SidePane(pane ?: Pane.Lyrics, track, state.sourceId, state.queue, state.index) }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                    AnimatedContent(pane, label = "pane", modifier = Modifier.weight(1f)) { current ->
                        if (current == null) {
                            PlayerColumn(track, state.isPlaying, state.buffering, state.shuffle, state.repeat, null, { pane = it }, nav::back, compact = false)
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                                    Artwork(urls.music(track, 96), Modifier.size(48.dp).clip(Corner.small), null, placeholder = Icons.Rounded.MusicNote)
                                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                        Text(track.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(track.artistLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    IconButton(onClick = { pane = null }) { Icon(Icons.Rounded.Close, "收起") }
                                }
                                Box(Modifier.weight(1f)) { SidePane(current, track, state.sourceId, state.queue, state.index) }
                                MiniTransport(state.isPlaying)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerColumn(
    track: Item,
    playing: Boolean,
    buffering: Boolean,
    shuffle: Boolean,
    repeat: Int,
    pane: Pane?,
    onPane: (Pane?) -> Unit,
    onClose: () -> Unit,
    compact: Boolean,
) {
    val music = LocalContext.current.graph.music
    val (position, duration) = rememberPlayerClock()
    var scrubbing by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "收起") }
            Spacer(Modifier.weight(1f))
            IconToggleButton(checked = pane == Pane.Lyrics, onCheckedChange = { onPane(if (it) Pane.Lyrics else null) }) { Icon(Icons.Rounded.Lyrics, "歌词") }
            IconToggleButton(checked = pane == Pane.Queue, onCheckedChange = { onPane(if (it) Pane.Queue else null) }) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "播放队列") }
        }
        Spacer(Modifier.weight(if (compact) 0.3f else 1f))
        val coverScale by animateDpAsState(if (playing) 0.dp else 18.dp, label = "cover")
        Box(Modifier.fillMaxWidth(if (compact) 0.62f else 1f).widthIn(max = 420.dp).aspectRatio(1f).padding(coverScale)) {
            Artwork(
                images().music(track, 900.dp.px().coerceAtMost(1080)),
                Modifier.fillMaxSize().clip(Corner.panel),
                track.name,
                placeholder = Icons.Rounded.MusicNote,
            )
            if (buffering) LoadingIndicator(Modifier.align(Alignment.Center))
        }
        Spacer(Modifier.weight(if (compact) 0.2f else 0.6f))
        Column(Modifier.fillMaxWidth()) {
            Text(track.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track.artistLine.ifEmpty { null }, track.album).joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        val seek = remember { SliderState() }
        seek.value = if (scrubbing) scrub else if (duration > 0) position.toFloat() / duration else 0f
        Slider(
            state = seek,
            onValueChange = { scrubbing = true; scrub = it },
            onValueChangeFinished = { music.seekTo((scrub * duration).toLong()); scrubbing = false },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(if (scrubbing) (scrub * duration).toLong() else position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatClock(duration), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val active = MaterialTheme.colorScheme.primary
            val idle = MaterialTheme.colorScheme.onSurfaceVariant
            val shuffleTint by animateColorAsState(if (shuffle) active else idle, label = "shuffle")
            val repeatTint by animateColorAsState(if (repeat != Player.REPEAT_MODE_OFF) active else idle, label = "repeat")
            IconButton(onClick = music::toggleShuffle) { Icon(Icons.Rounded.Shuffle, "随机播放", tint = shuffleTint) }
            IconButton(onClick = music::previous, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipPrevious, "上一首", Modifier.size(36.dp)) }
            FilledIconButton(
                onClick = music::toggle,
                modifier = Modifier.size(84.dp),
                shape = if (playing) Corner.panel else CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放", Modifier.size(44.dp))
            }
            IconButton(onClick = music::next, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.SkipNext, "下一首", Modifier.size(36.dp)) }
            IconButton(onClick = music::cycleRepeat) {
                Icon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "循环", tint = repeatTint)
            }
        }
        Spacer(Modifier.weight(if (compact) 0.3f else 0.8f))
    }
}

@Composable
private fun MiniTransport(playing: Boolean) {
    val music = LocalContext.current.graph.music
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = music::previous) { Icon(Icons.Rounded.SkipPrevious, "上一首") }
        FilledIconButton(onClick = music::toggle, modifier = Modifier.size(60.dp)) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放")
        }
        IconButton(onClick = music::next) { Icon(Icons.Rounded.SkipNext, "下一首") }
    }
}

@Composable
private fun SidePane(pane: Pane, track: Item, sourceId: String?, queue: List<Item>, index: Int) {
    when (pane) {
        Pane.Lyrics -> LyricsPane(track, sourceId)
        Pane.Queue -> QueuePane(queue, index)
    }
}

@Composable
private fun LyricsPane(track: Item, sourceId: String?) {
    val graph = LocalContext.current.graph
    var lyrics by remember(track.id) { mutableStateOf<Lyrics?>(null) }
    var loaded by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id, sourceId) {
        if (sourceId == null) return@LaunchedEffect
        val text = runCatching { graph.api.lyrics(track.id, sourceId) }.getOrDefault("")
        lyrics = if (text.isBlank()) null else Lyrics.parse(text)
        loaded = true
    }
    val current = lyrics
    if (current == null || current.lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!loaded) LoadingIndicator() else Text("暂无歌词", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val (position, _) = rememberPlayerClock(200)
    val active = current.indexAt(position)
    val list = rememberLazyListState()
    LaunchedEffect(active) { if (active >= 0) list.animateScrollToItem((active - 3).coerceAtLeast(0)) }
    LazyColumn(state = list, contentPadding = PaddingValues(vertical = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(current.lines, key = { i, _ -> i }) { i, line ->
            val highlighted = i == active
            val color by animateColorAsState(
                if (highlighted || !current.synced) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                label = "lyric",
            )
            Text(
                line.text,
                style = if (highlighted) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                color = color,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().clip(Corner.card)
                    .clickable(enabled = current.synced) { graph.music.seekTo(line.timeMs) }.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun QueuePane(queue: List<Item>, index: Int) {
    val music = LocalContext.current.graph.music
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (index - 2).coerceAtLeast(0))
    LazyColumn(state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(queue, key = { i, item -> "$i-${item.id}" }) { i, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { TrackRow(item, onClick = { music.skipTo(i) }, horizontalPadding = 0.dp) }
                if (i != index) IconButton(onClick = { music.removeAt(i) }) { Icon(Icons.Rounded.Close, "移出队列") }
            }
        }
    }
}

/**
 * 用户口径（2026-11-27，#31）**迷你条上的「播放列表」弹窗**。
 *
 * 用户原话：「最右面的那个是播放列表，需要加一下，点开播放列表，要能显示现在的播放列表」。
 *
 * 用 ModalBottomSheet 而不是跳到播放页的列表面板 —— 迷你条本身就是「不想离开当前页」的场景，
 * 弹一下能看到队列、点一首直接切，比整页跳转轻。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MusicQueueSheet(
    queue: List<Item>,
    index: Int,
    onDismiss: () -> Unit,
    onPick: (Item) -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "播放列表 · ${queue.size} 首",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = Etkn.space.screen, vertical = 10.dp),
        )
        if (queue.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("队列里还没有歌", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val list = rememberLazyListState(initialFirstVisibleItemIndex = (index - 2).coerceAtLeast(0))
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                itemsIndexed(queue, key = { i, item -> "$i-${item.id}" }) { i, item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.background(
                            if (i == index) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                            else androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    ) {
                        Box(Modifier.weight(1f)) {
                            TrackRow(item, onClick = { onPick(item) }, horizontalPadding = Etkn.space.screen)
                        }
                        if (i == index) {
                            Text(
                                "播放中",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
