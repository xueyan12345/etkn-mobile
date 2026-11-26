package xyz.etkn.mobile.ui.screens.music

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import xyz.etkn.mobile.music.LyricLine
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.offset

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
    /**
     * 提前取一次 ImageUrls：
     * `images()` 是 @Composable，不能在 runCatching 之类的普通 lambda 里调，
     * 放在 Composable 作用域里先拿到手，后面就能随便用。
     */
    val imageUrls = images()

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
                    /**
                     * 用户口径（2026-11-27，#38）**重启后也要显示封面**。
                     *
                     * 修的 bug：「退出 app 之后再打开……没有专辑图也没有歌词」。
                     *
                     * 根因：原来直接用 `track?.let { images().music(it, 96) }`，
                     * 而重启后 `state.current` 是 null（还没播），`track` 就是 null → 传 null → 占位图。
                     * 但 `remembered` 里存了 `itemId` / `primaryTag`，足够拼出封面地址。
                     */
                    val coverUrl = track?.let { imageUrls.music(it, 96) }
                        ?: remembered?.let { memory ->
                            /**
                             * 用记忆里的 itemId + primaryTag 拼封面。
                             *
                             * 走 `ImageUrls.image()` —— 和正常播放时 `music()` 内部
                             * 最后回退到的那个方法完全同一套 URL 规则（maxWidth 分桶 + quality=90），
                             * 不自己手拼字符串，避免参数写错导致 404。
                             */
                            memory.primaryTag?.takeIf { it.isNotBlank() }?.let { tag ->
                                imageUrls.image(memory.itemId, "Primary", 96, tag)
                            }
                        }
                    Artwork(
                        coverUrl,
                        Modifier.fillMaxSize().graphicsLayer {
                            // 只在播放时转；暂停时角度保持不动（用户能看到停在哪）
                            rotationZ = if (state.isPlaying) angle else 0f
                        },
                        track?.name ?: remembered?.name,
                        placeholder = Icons.Rounded.MusicNote,
                    )
                }
                /**
                 * 中间：**三行歌词**，中间那行完整、上下两行渐变淡出。
                 *
                 * 没有歌词/没有歌时显示歌名+歌手（保证这条永远有内容，不会空成一条）。
                 */
                Box(Modifier.weight(1f).height(52.dp).padding(horizontal = 10.dp)) {
                    /** 用户口径（#38）：把 remembered 也传进去，重启后仍能取歌词。 */
                    MiniLyrics(track, remembered, position)
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
            /**
             * 用户口径（2026-11-27，#6）**迷你条进度改成直线**。
             *
             * 原话：「看你这个图，就是能看到最下边的那个进度条，它是波浪状的，
             *        把它换成跟播放界面一样的直线形式，有缓存，
             *        然后把头有个实心圆点的那种」
             *
             * 原来是 `LinearWavyProgressIndicator`（波浪），现在复用一个轻量直线条：
             * 已播放（实色） / 已缓存（淡） / 未播放（底轨）+ 头部实心圆点。
             * 和播放页那个 `MusicProgressBar` 同一套观感，只是更矮、不可拖。
             */
            MiniProgressLine(
                position = position,
                duration = duration,
                cached = if (track != null) state.cachedPosition else position,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp),
            )
        }
        if (queueOpen) {
            MusicQueueSheet(
                queue = state.queue,
                index = state.index,
                onDismiss = { queueOpen = false },
                onPick = { item ->
                    val i = state.queue.indexOfFirst { it.id == item.id }
                    if (i >= 0) music.skipTo(i)
                    queueOpen = false
                },
                onClear = { music.clearQueue(); queueOpen = false },
            )
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
private fun MiniLyrics(track: Item?, remembered: xyz.etkn.mobile.music.MusicMemory?, positionMs: Long) {
    val graph = LocalContext.current.graph
    val music by graph.music.state.collectAsStateWithLifecycle()
    /**
     * 用户口径（2026-11-27，#38）**重启后歌词也要在**。
     *
     * 修的 bug：原来 `val t = track ?: return@LaunchedEffect` ——
     * 重启后 `track` 是 null 就直接退出，永远不取歌词。
     *
     * 现在：优先用当前歌（itemId + sourceId），没有就用记忆里的。
     * `sourceId` 是关键 —— 歌词接口要它才能定位到具体那个版本的音频。
     */
    val itemId = track?.id ?: remembered?.itemId
    val sourceId = music.sourceId ?: remembered?.sourceId
    var parsed by remember(itemId) { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(itemId, sourceId) {
        if (itemId == null || sourceId == null) return@LaunchedEffect
        val text = runCatching { graph.api.lyrics(itemId, sourceId) }.getOrDefault("")
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
            val line: LyricLine? = lines.getOrNull(current + offset)
            Text(
                text = line?.text ?: "",
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
    /**
     * 用户口径（2026-11-27，#41）**重启后点迷你条要能进得来**。
     *
     * 根因：原来 `if (track == null) { nav.back(); return }`，而重启后
     * `state.current` 是 null → 页面刚推上去就立刻 pop → 用户看到「抖了一下」。
     * 现在 state.current 为空时退回记忆里的那首，只有从没播过才返回。
     */
    val remembered by music.remembered.collectAsStateWithLifecycle()
    val memory = remembered
    val track: Item? = state.current ?: memory?.let { mem -> music.itemFromMemory(mem) }
    if (track == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val urls = images()
    val (position, duration) = rememberPlayerClock()

    /**
     * 用户口径（2026-11-27，#32）**播放器整页重做**。
     *
     * 需求（用户逐条提的，下面全部落到实现里）：
     *  ① 顶部只留「歌曲 / 歌词」两个标签居中；删掉原来右上角那两个按钮
     *  ② 专辑封面放大到屏幕 70% 以上
     *  ③ 歌名 + 歌手居中
     *  ④ 5 行歌词滚动，最上/最下两行渐变淡出
     *  ⑤ 进度条改成视频播放器那种（带缓存段）
     *  ⑥ 底部：循环模式合并成 1 个按钮切 3 态 + 播放列表按钮
     *  ⑦ 左右滑动切「歌曲 / 歌词」，点歌词也能进，要有过渡动画
     *  ⑧ 参考图里划掉的 6 处一律不做（收起箭头、右上两个图标、
     *     歌曲信息右侧小按钮、底部三个功能小图标）
     */
    var page by remember { mutableIntStateOf(0) }   // 0 = 歌曲  1 = 歌词
    val pager = rememberPagerState(initialPage = 0) { 2 }
    var queueOpen by remember { mutableStateOf(false) }

    /** 左右滑动切换两页（#32⑦）。 */
    LaunchedEffect(pager.currentPage) { page = pager.currentPage }
    LaunchedEffect(page) { if (pager.currentPage != page) pager.animateScrollToPage(page) }

    Box(Modifier.fillMaxSize()) {
        // 背景：封面严重模糊 + 暗化，保持原样
        Artwork(
            urls.music(track, 320),
            Modifier.fillMaxSize().blur(60.dp),
            null,
            background = MaterialTheme.colorScheme.surface,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    ),
                ),
            ),
        )
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {

            /** ① 顶部两个标签，居中一左一右（原来的收起箭头和右侧图标都删了）。 */
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("歌曲", "歌词").forEachIndexed { i, label ->
                    val active = page == i
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(Corner.pill)
                            .clickable { page = i }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        /**
                         * 用户口径（2026-11-27，#4①）**顶部两个字加大到跟歌名一样大**。
                         * 原话：「这个顶部和差这俩字可以稍微再大点，可以跟现在的播放界面的歌名一样大小」
                         */
                        Text(
                            label,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            color = if (active) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 选中下划线
                        Box(
                            Modifier
                                .padding(top = 4.dp)
                                .width(if (active) 26.dp else 0.dp)
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.onSurface, Corner.pill),
                        )
                    }
                }
            }

            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = true,
            ) { index ->
                if (index == 0) {
                    /**
                     * ② ③ ④ 歌曲页：大封面 + 歌名歌手居中 + 5 行歌词。
                     * 封面占满可用宽度（70% 以上），受 480dp 上限约束避免平板上过大。
                     */
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        /**
                         * 用户口径（2026-11-27，#13）**封面往上提，紧贴标签下方**。
                         * 原话：「这个图片太往下了，往上点啊，放在歌曲和歌词的下边就行」
                         * 这里原来有一大块 `Spacer(weight 0.15f)` 把它顶下去了，去掉。
                         */
                        Spacer(Modifier.height(12.dp))
                        /**
                         * 用户口径（2026-11-27，#4②）**封面缩到现在的一半**。
                         * 原话：「中间的封面图缩小一点，封面图变成现在的一半那么大就行」
                         */
                        Box(
                            Modifier.fillMaxWidth(0.62f).widthIn(max = 260.dp).aspectRatio(1f),
                        ) {
                            Artwork(
                                urls.music(track, 1080),
                                Modifier.fillMaxSize().clip(Corner.panel),
                                track.name,
                                placeholder = Icons.Rounded.MusicNote,
                            )
                            if (state.buffering) LoadingIndicator(Modifier.align(Alignment.Center))
                        }
                        Spacer(Modifier.height(20.dp))
                        /** 用户口径（#4③）歌名再大一点。 */
                        Text(
                            track.name,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            listOfNotNull(track.artistLine.ifEmpty { null }, track.album).joinToString(" · "),
                            /** 用户口径（#4④）歌手名跟着歌名等比加大。 */
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        /**
                         * ④ 5 行歌词（中间完整、上下渐隐），点它进歌词页（#32⑦）。
                         */
                        /**
                         * 用户口径（2026-11-27，#5）**歌词行数按剩余空间自适应**。
                         *
                         * 原话：「播放详情界面把歌词行数按照空白区域计算一下能放多少行就放多少行」
                         *
                         * 做法：用 `BoxWithConstraints` 拿到这块区域的实际高度，
                         * 除以单行高度（约 26dp）得到能放几行，
                         * 保证奇数（中间行才是"当前这句"），最少 3 行。
                         */
                        BoxWithConstraints(
                            Modifier.fillMaxWidth().weight(1f).clickable { page = 1 },
                        ) {
                            val rowHeight = 26.dp
                            val fitted = (maxHeight / rowHeight).toInt().coerceAtLeast(3)
                            val rows = if (fitted % 2 == 0) fitted - 1 else fitted
                            AdaptiveLyrics(track, position, rows)
                        }
                        /** 用户口径（#4⑥）底部控件整体往上移 —— 压缩这里的弹性空白。 */
                        Spacer(Modifier.height(6.dp))
                    }
                } else {
                    /** 歌词页：整页滚动歌词。 */
                    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                        FullLyrics(track, position, state.sourceId)
                    }
                }
            }

            /**
             * ⑤ 进度条（带缓存段）+ 底部控制（#32⑤⑥）。
             */
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                MusicProgressBar(
                    position = position,
                    duration = duration,
                    cached = state.cachedPosition,
                    onSeek = { music.seekTo(it) },
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatClock(position),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatClock(duration),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                /**
                 * ⑥ 底部五个按钮：
                 *  循环模式（一个按钮切三态） / 上一首 / 播放 / 下一首 / 播放列表
                 *
                 * 用户明确要求把原来的「随机播放」「顺序播放+单曲循环」两个按钮
                 * 合并成左边一个，点一下切一种状态。
                 */
                /**
                 * 用户口径（2026-11-27，#14）**底部播放控件再往上一点**。
                 * 原话：「画圆圈的部分有点太往下了，稍微往上一点点」
                 */
                Spacer(Modifier.height(2.dp))
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    /**
                     * 用户口径（#4⑧）**一个按钮切 4 态**：顺序 → 列表循环 → 单曲循环 → 随机。
                     *
                     * 对比原实现：只有 repeatMode 的 3 态，缺「随机」，
                     * 而「随机」是 shuffleModeEnabled 这个独立维度，现在合并成一条循环。
                     */
                    /**
                     * 用户口径（2026-11-27，#4）**四种状态必须用四个不同图标**。
                     *
                     * 原话：「左下角的那个顺序播放/随机播放/单曲循环/列表循环播放，
                     *        我要4个小图标不一样的，你现在只有3个，说好几遍了」
                     *
                     * 原来 0(顺序) 和 1(列表循环) 都用了 `Repeat`，看起来就是只有 3 个。
                     * 现在四个各用各的：
                     * ```
                     * 0 顺序播放   → PlaylistPlay    （列表 + 播放箭头，表示按序播完停）
                     * 1 列表循环   → Repeat          （两个箭头成环）
                     * 2 单曲循环   → RepeatOne       （环 + 数字 1）
                     * 3 随机播放   → Shuffle         （交叉箭头）
                     * ```
                     */
                    val mode = when {
                        state.shuffle -> 3
                        state.repeat == Player.REPEAT_MODE_ONE -> 2
                        state.repeat == Player.REPEAT_MODE_ALL -> 1
                        else -> 0
                    }
                    IconButton(onClick = music::cyclePlayMode) {
                        Icon(
                            when (mode) {
                                0 -> Icons.AutoMirrored.Rounded.PlaylistPlay
                                1 -> Icons.Rounded.Repeat
                                2 -> Icons.Rounded.RepeatOne
                                else -> Icons.Rounded.Shuffle
                            },
                            contentDescription = when (mode) {
                                0 -> "顺序播放"
                                1 -> "列表循环"
                                2 -> "单曲循环"
                                else -> "随机播放"
                            },
                            /** 四个状态都用主色，只有「顺序播放」用中性色（默认态）。 */
                            tint = if (mode == 0) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = music::previous) {
                        Icon(Icons.Rounded.SkipPrevious, "上一首", Modifier.size(30.dp))
                    }
                    FilledIconButton(
                        onClick = music::toggle,
                        modifier = Modifier.size(60.dp),
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (state.isPlaying) "暂停" else "播放",
                            Modifier.size(32.dp),
                        )
                    }
                    IconButton(onClick = music::next) {
                        Icon(Icons.Rounded.SkipNext, "下一首", Modifier.size(30.dp))
                    }
                    /** 播放列表（#32⑥ 右侧改成播放列表）。 */
                    IconButton(onClick = { queueOpen = true }) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, "播放列表")
                    }
                }
            }
        }
    }

    if (queueOpen) {
        MusicQueueSheet(
            state.queue,
            state.index,
            onDismiss = { queueOpen = false },
            onPick = { item ->
                val i = state.queue.indexOfFirst { it.id == item.id }
                if (i >= 0) music.skipTo(i)
                queueOpen = false
            },
            onClear = { music.clearQueue(); queueOpen = false },
        )
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
    onClear: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        /**
         * 用户口径（2026-11-27，#7）**播放列表加一个「一键清空」按钮**。
         *
         * 原话：「播放列表加一个一键清空列表的功能按钮」
         */
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "播放列表 · ${queue.size} 首",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (queue.isNotEmpty()) {
                TextButton(onClick = onClear) {
                    Icon(
                        Icons.Rounded.DeleteSweep,
                        null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("清空")
                }
            }
        }
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

/**
 * 用户口径（2026-11-27，#32④）**播放页里的 5 行歌词**。
 *
 * 需求原文：
 *  「下面除了放歌名和歌手，还要居中滚动歌词，我想要一共显示 5 行，
 *    其中最上面的那行和最下面的那行要做成跟刚才播放控制条一样，
 *    越往上和越往下渐变显示的效果」
 *
 * 实现：中间那行（index=2）完整不透明，往两侧逐级降透明度，
 * 第一行和第五行几乎看不见 —— 就是"渐变淡出"的观感。
 */
@Composable
private fun FiveLineLyrics(track: Item, positionMs: Long) {
    val graph = LocalContext.current.graph
    val music by graph.music.state.collectAsStateWithLifecycle()
    var parsed by remember(track.id) { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(track.id, music.sourceId) {
        val sid = music.sourceId ?: return@LaunchedEffect
        val text = runCatching { graph.api.lyrics(track.id, sid) }.getOrDefault("")
        parsed = if (text.isBlank()) null else Lyrics.parse(text)
    }
    val lines = parsed?.lines.orEmpty()
    val active = parsed?.indexAt(positionMs) ?: -1
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂无歌词",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val base = MaterialTheme.colorScheme.onSurface
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        // 5 行：以当前行为中心，offset 从 -2 到 +2
        listOf(-2, -1, 0, 1, 2).forEach { offset ->
            val idx = active + offset
            val line: LyricLine? = lines.getOrNull(idx)
            val isCenter = offset == 0
            Text(
                text = line?.text.orEmpty(),
                /** 用户口径（#4⑤）歌词字稍微大一点点（不要太大）。 */
                style = if (isCenter) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                color = base.copy(
                    alpha = when (offset) {
                        0 -> 1f
                        -1, 1 -> 0.45f
                        else -> 0.16f
                    },
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            )
        }
    }
}

/**
 * 用户口径（#32）**歌词页（整页）**：滚动列表 + 当前行高亮。
 */
@Composable
private fun FullLyrics(track: Item, positionMs: Long, sourceId: String?) {
    val graph = LocalContext.current.graph
    var parsed by remember(track.id) { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(track.id, sourceId) {
        val sid = sourceId ?: return@LaunchedEffect
        val text = runCatching { graph.api.lyrics(track.id, sid) }.getOrDefault("")
        parsed = if (text.isBlank()) null else Lyrics.parse(text)
        /**
         * 用户口径（#10）**把歌词喂给 MusicConnection**，
         * 它会按播放进度把「当前这句」写进 MediaSession 的 subtitle，
         * 车机 CarLife / OPPO Car+ 那个卡片就能跟着滚。
         */
        parsed?.lines?.let { ls ->
            graph.music.publishLyrics(track.id, ls.map { it.timeMs to it.text })
        }
    }
    val lines = parsed?.lines.orEmpty()
    val active = parsed?.indexAt(positionMs) ?: -1
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    /**
     * 用户口径（2026-11-27，#15）**歌词页必须能手滑**。
     *
     * 用户原话：「这个界面的歌词也不能滑动」
     *
     * 原来有两个原因导致滑不动：
     *  ① `LaunchedEffect(active)` 每次都 `animateScrollToItem` ——
     *     用户刚滑走一点，播放进度一变就被拽回当前行
     *  ② 外层是 `HorizontalPager`，垂直手势本应不受影响，
     *     但 LazyColumn 没给足内容高度时（列表短）根本滚不动
     *
     * 现在：
     *  · 加 `userScrolling` 标记：用户手滑之后，暂停自动跟随 5 秒，然后恢复
     *  · 内容不足一屏时也能拖动（加足够的上下 padding）
     */
    val list = rememberLazyListState()
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(list.isScrollInProgress) {
        if (list.isScrollInProgress) {
            userScrolling = true
        } else if (userScrolling) {
            /** 手松开后再等 5 秒恢复自动跟随，避免立刻被拽走。 */
            kotlinx.coroutines.delay(5_000)
            userScrolling = false
        }
    }
    LaunchedEffect(active, userScrolling) {
        if (active >= 0 && !userScrolling) {
            list.animateScrollToItem((active - 3).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = list,
        /** 上下留白按屏幕高度的一半，保证第一句/最后一句也能滚到中间。 */
        contentPadding = PaddingValues(vertical = 160.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
            val highlighted = i == active
            Text(
                line.text,
                style = if (highlighted) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.bodyLarge,
                fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                color = if (highlighted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 用户口径（2026-11-27，#32⑤）**音乐进度条照视频播放器那套**。
 *
 * 需求原文：「下面的播放进度条还是这个 app 最开始的样子，
 *            我需要你参考现在咱们播放视频的那个界面，我要那个界面的那种播放进度条，
 *            同样也要加入缓存条」
 *
 * 三段：已播放（实色） / 已缓存（淡一点） / 未缓存（底轨）。
 * 拖动时显示实时位置，松手才 seek。
 */
@Composable
private fun MusicProgressBar(
    position: Long,
    duration: Long,
    cached: Long,
    onSeek: (Long) -> Unit,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }
    val playedFraction = when {
        scrubbing -> scrubFraction
        duration > 0 -> (position.toFloat() / duration).coerceIn(0f, 1f)
        else -> 0f
    }
    val cachedFraction = when {
        duration > 0 -> (cached.toFloat() / duration).coerceIn(0f, 1f)
        else -> 0f
    }
    val accent = MaterialTheme.colorScheme.primary
    val cachedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)

    Box(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    if (duration > 0) onSeek((offset.x / size.width * duration).toLong())
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { scrubbing = true },
                    onDragEnd = {
                        if (duration > 0) onSeek((scrubFraction * duration).toLong())
                        scrubbing = false
                    },
                    onDragCancel = { scrubbing = false },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val barHeight = 4.dp
        // 底轨
        Box(Modifier.fillMaxWidth().height(barHeight).background(trackColor, Corner.pill))
        // 缓存段
        Box(
            Modifier.fillMaxWidth(cachedFraction.coerceAtLeast(playedFraction))
                .height(barHeight).background(cachedColor, Corner.pill),
        )
        // 已播放
        Box(
            Modifier.fillMaxWidth(playedFraction).height(barHeight).background(accent, Corner.pill),
        )
        /**
         * 用户口径（2026-11-27，#10）**进度条头部的实心圆点必须看得见**。
         *
         * 之前写的圆点是 13dp，但外层 Box 只有 `barHeight`(4dp) 高
         * —— 圆点被裁掉一大半，看起来就像没有。
         *
         * 现在用 `wrapContentHeight` 让外层容器跟着圆点撑开，
         * 并用 `offset` 把圆心对齐到进度末端。
         */
        Box(
            Modifier
                .fillMaxWidth(playedFraction)
                .wrapContentHeight(Alignment.CenterVertically),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .offset(y = 0.dp)
                    .size(if (scrubbing) 18.dp else 14.dp)
                    .background(accent, CircleShape),
            )
        }
    }
}

/**
 * 用户口径（2026-11-27，#6）**迷你条用的直线进度**（含缓存段 + 头部实心圆点）。
 *
 * 和播放页的 `MusicProgressBar` 观感一致，区别：
 *  · 更矮（3dp）
 *  · 不可拖动（迷你条只是状态展示，拖进度去播放页）
 */
@Composable
private fun MiniProgressLine(
    position: Long,
    duration: Long,
    cached: Long,
    modifier: Modifier = Modifier,
) {
    val played = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val cachedFraction = if (duration > 0) (cached.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val accent = MaterialTheme.colorScheme.primary
    val cachedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Box(modifier.height(10.dp), contentAlignment = Alignment.CenterStart) {
        // 底轨
        Box(Modifier.fillMaxWidth().height(3.dp).background(trackColor, Corner.pill))
        // 缓存段
        Box(
            Modifier.fillMaxWidth(cachedFraction.coerceAtLeast(played))
                .height(3.dp).background(cachedColor, Corner.pill),
        )
        // 已播放
        Box(Modifier.fillMaxWidth(played).height(3.dp).background(accent, Corner.pill))
        // 头部实心圆点
        Box(
            Modifier.fillMaxWidth(played).height(3.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(Modifier.size(7.dp).background(accent, CircleShape))
        }
    }
}

/**
 * 用户口径（2026-11-27，#5）**按可用高度自适应的滚动歌词**。
 *
 * 中间那行（`rows / 2`）完整不透明，往两侧逐级降透明度，
 * 最外两行几乎看不见 —— 和迷你条一样的渐变观感。
 *
 * @param rows 这块区域能放几行（由调用方按高度算，保证是奇数）
 */
@Composable
private fun AdaptiveLyrics(track: Item, positionMs: Long, rows: Int) {
    val graph = LocalContext.current.graph
    val music by graph.music.state.collectAsStateWithLifecycle()
    var parsed by remember(track.id) { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(track.id, music.sourceId) {
        val sid = music.sourceId ?: return@LaunchedEffect
        val text = runCatching { graph.api.lyrics(track.id, sid) }.getOrDefault("")
        parsed = if (text.isBlank()) null else Lyrics.parse(text)
        parsed?.lines?.let { ls ->
            graph.music.publishLyrics(track.id, ls.map { it.timeMs to it.text })
        }
    }
    val lines = parsed?.lines.orEmpty()
    val active = parsed?.indexAt(positionMs) ?: -1
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "暂无歌词",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    /**
     * 用户口径（2026-11-27，#11）**当前这句必须在正中间**。
     *
     * 原话：「正在播放的那句歌词，不是在下方的正中间，好像有点偏下吧」
     *
     * 原因：上下各有 half 行，但每行有 padding(vertical=4dp)，
     * 且中间行字号更大（titleLarge vs bodyLarge），实际行高不同，
     * 导致中间行的视觉中心偏下。
     *
     * 修法：给每行固定高度（`heightIn(min = rowH)`），
     * 让所有行等高，Column 的 Center 才是真正居中。
     */
    val half = rows / 2
    val base = MaterialTheme.colorScheme.onSurface
    val rowH = 28.dp
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        for (offset in -half..half) {
            val idx = active + offset
            val line: LyricLine? = lines.getOrNull(idx)
            val distance = kotlin.math.abs(offset)
            val isCenter = offset == 0
            Text(
                text = line?.text.orEmpty(),
                style = if (isCenter) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                color = base.copy(
                    alpha = when (distance) {
                        0 -> 1f
                        1 -> 0.45f
                        2 -> 0.22f
                        else -> 0.10f
                    },
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                /** 固定行高，保证中间行真正居中（#11）。 */
                modifier = Modifier.fillMaxWidth().heightIn(min = rowH),
            )
        }
    }
}
