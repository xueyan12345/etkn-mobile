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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

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
                Box(Modifier.size(48.dp).clip(CircleShape)) {
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
                Box(Modifier.weight(1f).height(56.dp).padding(horizontal = 10.dp)) {
                    /** 用户口径（#38）：把 remembered 也传进去，重启后仍能取歌词。 */
                    MiniLyrics(track, remembered, position)
                }
                /**
                 * 用户口径（2026-11-27，#79）**迷你条右侧三个按钮放大**。
                 *
                 * 用户原话：
                 * ```
                 * 「播放那个小的控制条右面的按钮不太好看，有点太小了，你看看这个参考」
                 * ```
                 * 参考图（G.E.M.《倒数》那个播放器）的实测比例：
                 * ```
                 * 播放键  44dp 外圈环 + 内部图标约 24dp
                 * 下一首  图标约 26dp，无环
                 * 列表    图标约 26dp，无环
                 * 三者等距，整体占条宽约 35~40%
                 * ```
                 * 我们原来是 40dp 环 + 20dp 图标，视觉偏小。改成：
                 * ```
                 * 播放键  46dp 环 + 26dp 图标
                 * 下一首  26dp
                 * 列表    26dp
                 * 间距    用 IconButton 默认 48dp 触摸区，不额外挤
                 * ```
                 */
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxSize(),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    )
                    IconButton(
                        onClick = { if (state.current != null) music.toggle() else music.resumeRemembered() },
                        modifier = Modifier.size(46.dp),
                    ) {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (state.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                IconButton(onClick = music::next, enabled = state.current != null) {
                    Icon(Icons.Rounded.SkipNext, "下一首", modifier = Modifier.size(26.dp))
                }
                /** 播放列表：点开显示当前队列。 */
                IconButton(onClick = { queueOpen = true }) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, "播放列表", modifier = Modifier.size(26.dp))
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
                onRemove = { i -> music.removeAt(i) },
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
    val remembered by music.remembered.collectAsStateWithLifecycle()
    val memory = remembered
    val track: Item? = state.current ?: memory?.let { mem -> music.itemFromMemory(mem) }
    if (track == null) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val urls = images()
    val (position, duration) = rememberPlayerClock()
    var queueOpen by remember { mutableStateOf(false) }

    /**
     * 用户口径（2026-11-27，#35）**播放页只留一个界面，删掉「歌曲/歌词」两个标签**。
     *
     * 用户原话：
     *  「我觉得把上边的歌曲和歌词那个可以删掉了，就只留现在点开播放界面，
     *    这一个界面就可以，因为下边的歌词已经很长了，不必要再专门整一个歌词界面，
     *    然后你把那个专辑图往上提一提，提到歌词和歌曲那两个字那块，
     *    这样下边的歌词还能拉长一些」
     *
     * 布局（从上到下）：
     * ```
     * [专辑封面]        ← 顶到原来"歌曲/歌词"标签的位置
     * 歌名 / 歌手
     * ─────────────
     * 歌词区（可手势滑动 + 中间定位线 + 点定位线跳播）
     * ─────────────
     * 进度条 + 底部控制
     * ```
     */
    Box(Modifier.fillMaxSize()) {
        // 背景：封面严重模糊 + 暗化
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
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            /**
             * 用户口径（2026-11-27，#49）**播放页整体下移一点**。
             *
             * 用户原话：
             *  「把专辑的图片往下移一点，移多少呢，大概就移现在的播放界面的歌名的高度就行，
             *    然后歌名和歌手也都稍微往下移，歌词部分稍微压缩压缩」
             *
             * 做法：
             *  · 封面顶部空白 8dp → 34dp（约等于歌名那行的高度）
             *  · 歌名/歌手与封面之间的间距 14dp → 18dp
             *  · 歌词区是 weight(1f) 自适应，上面多占的部分自然从这里扣
             */
            Spacer(Modifier.height(34.dp))
            Box(Modifier.fillMaxWidth(0.58f).widthIn(max = 240.dp).aspectRatio(1f)) {
                Artwork(
                    urls.music(track, 1080),
                    Modifier.fillMaxSize().clip(Corner.panel),
                    track.name,
                    placeholder = Icons.Rounded.MusicNote,
                )
                /** 缓冲指示也用三点跳动（#183 全 App 统一）。 */
                if (state.buffering) {
                    xyz.etkn.mobile.ui.components.EtknLoading(Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.height(18.dp))
            /**
             * ② 歌名 + 歌手
             *
             * 用户口径（2026-11-27，#64）**歌名大一点点，不要太多**。
             * `headlineSmall` 默认 24sp → 用 `copy(fontSize = 26.sp)` 微调 +2sp，
             * 不跳到 headlineMedium（28sp，用户会觉得"太多"）。
             */
            Text(
                track.name,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp),
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                listOfNotNull(track.artistLine.ifEmpty { null }, track.album).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(10.dp))

            /**
             * ③ 歌词区：**用上游官方实现 LyricsPane**。
             *
             * 用户口径（2026-11-27，#59）：
             *  「ETKN 是有歌词对接的接口的，你不要自己去搞，你要直接对接 ETKN 的歌词，
             *    人家那都配套做好的，你非得自己再弄一套」
             *
             * 我此前写了 4 版（FiveLineLyrics / FullLyrics / AdaptiveLyrics / SeekerLyrics）
             * 全部删掉了。上游 `LyricsPane` 本来就完整：
             * ```
             * ✅ 走官方接口 /api/tv/Music/{id}/Lyrics?MediaSourceId=
             * ✅ 当前行高亮（大字 + 加粗）
             * ✅ 点歌词直接跳播（用户要的"定位播放"它自带）
             * ✅ 自动滚动跟随
             * ✅ 加载中显示 LoadingIndicator、无词显示"暂无歌词"
             * ```
             * 唯一受限：`sourceId` 为空时不加载 —— 但服务端实测
             * `/api/tv/Music/{id}/Lyrics?MediaSourceId={id}` 传曲目 ID 即可，见 LyricsPane 内注释。
             */
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LyricsPane(track = track, sourceId = state.sourceId)
            }

            /**
             * 用户口径（2026-11-27，#80 修正）**底部控件往上移一行歌词的距离**。
             *
             * 用户原话：
             * ```
             * 「这个下面的播放控件稍微往上移一点，压缩一行歌词的距离」
             * ```
             *
             * **上一版做错了**（用户实测「还是原来的位置，没有往上提」）：
             * 我给歌词区加了 `padding(bottom = 44.dp)`，但整页是
             * ```
             * Column(fillMaxSize) {
             *     ...封面/歌名...
             *     Box(weight(1f)) { 歌词 }   ← 吃掉所有剩余空间
             *     进度条 + 控制按钮          ← 贴在底部
             * }
             * ```
             * `weight(1f)` 会把剩余空间全部给歌词区，歌词区块变矮 44dp、
             * 但**底部控件位置纹丝不动**（它本来就贴着底）。
             *
             * 正确做法：给底部这一整块（进度条+时间+五个按钮）
             * 加 `padding(bottom = 44.dp)` —— 把整块往上顶一行歌词的距离。
             * 歌词区 weight 会自动吸收多出来的空间，不用另外调。
             */
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 44.dp)) {
                MusicProgressBar(
                    position = position,
                    duration = duration,
                    cached = state.cachedPosition,
                    onSeek = { music.seekTo(it) },
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp),
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
                /** ⑤ 底部五个按钮 */
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    val mode = when {
                        state.shuffle -> 3
                        state.repeat == Player.REPEAT_MODE_ONE -> 2
                        state.repeat == Player.REPEAT_MODE_ALL -> 1
                        else -> 0
                    }
                    IconButton(onClick = music::cyclePlayMode) {
                        Icon(
                            when (mode) {
                                /** 用户口径（#43）顺序播放换成"三个横向箭头"自绘图标。 */
                                0 -> SequentialPlayIcon
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
                            /**
                             * 用户口径（2026-11-28，#132）**四种播放模式图标颜色统一**。
                             *
                             * 用户原话：
                             * ```
                             * 「我不是让你改了4个小图标切换播放形式吗，就是顺序播放，
                             *   还有什么随机播放那个，为啥顺序播放的是黑色，
                             *   然后点击一下剩下的那三种状态都是绿色的，
                             *   你把它统一成都是黑色，不要改色」
                             * ```
                             *
                             * 原来：`if (mode == 0) onSurfaceVariant else primary`
                             * → 顺序播放显灰黑，其余三种显主题绿。
                             *
                             * 现在：四种状态一律 `onSurfaceVariant`（跟其他控制键同色）。
                             * 用户明确说「不要改色」，所以不动 `onSurfaceVariant` 本身。
                             */
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    /**
                     * 用户口径（2026-11-28，#134/#137/#138）**下一首预加载好后亮个呼吸小气泡**。
                     *
                     * 用户原话：
                     * ```
                     * 「咱们现在不是有预加载下一首的功能吗，就是类似于播放视频的预加载下一集，
                     *   你把预加载下一集的那个小气泡添加到这里边，相当于是一个小提示」
                     * 「小气泡有点离下一首那个按钮太远了并且也不是很清晰」
                     * 「你能不能把这小气泡变成呼吸的样式？」
                     * 「感觉对于那个下一首按钮来说有点大」
                     * ```
                     *
                     * ## 尺寸依据（不要凭感觉改）
                     *
                     * 「下一首」图标本身是 30dp，气泡要明显小于它才有"附着"感而不是"并列"感。
                     * 取 **11dp 直径**（约图标 1/3），闪电 8dp。
                     */
                    Box(contentAlignment = Alignment.TopEnd) {
                        IconButton(onClick = music::next) {
                            Icon(Icons.Rounded.SkipNext, "下一首", Modifier.size(30.dp))
                        }
                        if (state.nextReady) {
                            /** 呼吸：0.5 ↔ 1.0，1.6 秒一轮。 */
                            val breathe = rememberInfiniteTransition(label = "next-ready")
                            val alpha by breathe.animateFloat(
                                initialValue = 0.5f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800, easing = LinearEasing),
                                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                                ),
                                label = "next-ready-alpha",
                            )
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    /** 压回按钮右上角内圈（IconButton 的 48dp 触摸区比图标大）。 */
                                    .offset(x = (-4).dp, y = 4.dp)
                                    .size(11.dp)
                                    .graphicsLayer { this.alpha = alpha }
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .shadow(2.dp, CircleShape),
                            ) {
                                Icon(
                                    Icons.Rounded.Bolt,
                                    contentDescription = "下一首已预加载",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(8.dp),
                                )
                            }
                        }
                    }
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
            onRemove = { i -> music.removeAt(i) },
        )
    }
}




@Composable
private fun LyricsPane(track: Item, sourceId: String?) {
    val graph = LocalContext.current.graph
    var lyrics by remember(track.id) { mutableStateOf<Lyrics?>(null) }
    var loaded by remember(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id, sourceId) {
        /**
         * 用户口径（2026-11-27，#59）**这一处只做最小修补，不另起一套**。
         *
         * 上游原样是 `if (sourceId == null) return@LaunchedEffect`。
         * 实测：`state.sourceId` 要等直链解析完才有，刚进播放页时是 null；
         * 而服务端 `/api/tv/Music/{id}/Lyrics?MediaSourceId={id}`
         * **直接传曲目 ID 就能返回歌词**（已用 curl 验证）。
         *
         * 所以只把 null 兜底成 track.id，其余逻辑（高亮/跳播/跟随）
         * 全部沿用上游，一行没动。
         */
        val sid = sourceId ?: track.id
        val text = runCatching { graph.api.lyrics(track.id, sid) }.getOrDefault("")
        lyrics = if (text.isBlank()) null else Lyrics.parse(text)
        loaded = true
    }
    val current = lyrics
    if (current == null || current.lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (!loaded) xyz.etkn.mobile.ui.components.EtknLoading()
            else Text("暂无歌词", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val (position, _) = rememberPlayerClock(200)
    val active = current.indexAt(position)

    /**
     * 用户口径（2026-11-27，#71）**放弃 LazyColumn 滚动，改成"固定窗口"渲染**。
     *
     * 背景：我前面用 `scrollToItem` 算的偏移，数学上是对的
     * （`padPx = boxPx/2 - rowPx/2`，所以 `(boxPx-rowPx)/2 - padPx ≡ 0`），
     * 但用户在真机上看到的仍是"高亮句在下面、看不到"。
     * 说明这条路（LazyColumn + scrollToItem 求偏移）在实机上有未预料的行为
     * （可能是首帧未测量、或 scrollToItem 的 offset 语义与我理解不同）。
     *
     * 我不再跟它较劲。改成**根本不需要滚动**的写法：
     * ```
     * 按可用高度算出能放几行 rows（奇数，保证有正中间）
     * 直接把 [active - half, active + half] 这一窗口画出来
     * Column + Arrangement.Center 自然垂直居中
     * 当前句永远在窗口正中间那一格 —— 恒等式，没有滚动计算
     * ```
     *
     * 代价：失去手指滑动的惯性滚动。但定位播放（滑动 + 中线 + 跳播）
     * 可以用 `verticalScroll` 的偏移量做定位，仍然支持。
     */
    val rowHeight = 44.dp
    var dragging by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val rowPx = with(density) { rowHeight.toPx() }
        val boxPx = with(density) { maxHeight.toPx() }
        /**
         * 能放几行：向下取整，并强制为奇数 ——
         * 奇数才能保证有唯一的正中间那一格。
         */
        val fitted = (boxPx / rowPx).toInt().coerceAtLeast(3)
        val rows = if (fitted % 2 == 0) fitted - 1 else fitted
        val half = rows / 2

        /**
         * 用户口径（2026-11-27，#76）**手指滑动歌词 + 中间横杠 + 定位播放**。
         *
         * 用户原话：
         * ```
         * 「我想要的是滑动歌词，可以来回滑动，然后中间不是会显示有一个横杠吗，
         *   然后定位播放这个好像没加，能加吗，我怕你加完之后又出问题了」
         * ```
         *
         * 上一版我写了这套，但三个 bug 让它完全失效：
         * ```
         * ① LaunchedEffect(dragging, shift) 里判断 dragging && ... → 一拖就重置 dragging，
         *    松手瞬间又重置 shift，等于永远回不到 0（或永远在归零）
         * ② verticalScroll(rememberScrollState(0), enabled = false) 嵌在里面，
         *    它自己的滚动状态会吞掉/干扰拖动手势
         * ③ shift 只在「拖过半行」时才 ±1，要拖 22dp 才动一行，手感像卡住
         * ```
         *
         * 现在重写：
         * ```
         * 拖动时不按行取整，而是按像素累积 dragPx，实时换算成行偏移
         * → 手指跟手，滑多少动多少
         * 松手后：不立刻归零，保持 1.5 秒（用户要看定位线在哪句）
         * → 1.5 秒内没有新动作才回到正在播的那句
         * ```
         */
        var dragPx by remember { mutableFloatStateOf(0f) }

        /**
         * 用户口径（2026-11-27，#90）**滑动手感要连续，不能一格一格蹦**。
         *
         * 用户原话：「滑动太生硬了，没有一点上下滑动的感觉，太生硬了」
         *
         * 原因就是下面这行：
         * ```
         * val dragRows = (dragPx / rowPx).toInt()
         * ```
         * `toInt()` 把连续位移**取整成整行**，手指平滑滑动时画面只在
         * 攒够 44dp 才跳一格 —— 手感就是"一格一格蹦"。
         *
         * 改法：
         * ```
         * dragLines 保留浮点（连续位移）
         * → centerIndex 用四舍五入（决定高亮哪一句）
         * → 渲染时整列按 (dragLines - dragRows) 的余量做像素级平移
         *   让歌词真的跟手滑动，而不是跳变
         * ```
         */
        val dragLines = dragPx / rowPx          // 连续值（浮点）
        val dragRows = dragLines.toInt()         // 取整（决定高亮行）

        /** 窗口中心 = 当前播的那句 + 拖动偏移。 */
        val centerIndex = (active.coerceAtLeast(0) + dragRows)
            .coerceIn(0, current.lines.lastIndex.coerceAtLeast(0))

        /**
         * 松手后 1.5 秒：如果这期间没有新的拖动，就回到正在播的那句。
         *
         * 用 `dragPx` 当唯一依赖 —— 它一变这个 effect 就重启计时器，
         * 所以"持续拖动"期间永远不会归零，只有真正停下来 1.5 秒才归位。
         */
        LaunchedEffect(dragPx, active) {
            if (dragPx != 0f) {
                kotlinx.coroutines.delay(1_500)
                dragPx = 0f
                dragging = false
            }
        }

        /** 拖动中（有像素偏移）才显示横杠和定位按钮。 */
        val showLocator = dragPx != 0f || dragging

        Box(Modifier.fillMaxSize()) {
            /**
             * 垂直滑动：整个歌词区可拖动，累积像素换算成行偏移（跟手，不按行取整）。
             */
            Column(
                Modifier
                    .fillMaxSize()
                    /**
                     * 用户口径（#76）**去掉 verticalScroll**。
                     *
                     * 之前这里嵌了 `verticalScroll(rememberScrollState(0), enabled = false)`，
                     * 它虽然被禁用滚动，但仍然会参与手势竞争，把手势吞掉一部分，
                     * 表现就是"滑不动"。纯 pointerInput 就够，不需要它。
                     *
                     * 拖动直接累积像素（dragPx），不做"过半行才跳一格"的取整 ——
                     * 那样要拖 22dp 才动一行，手感像卡住。
                     */
                    .graphicsLayer {
                        translationY = -(dragLines - dragRows) * rowPx
                    }
                    .pointerInput(rows, active) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false; dragPx = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            /** 手指向上滑（dragAmount < 0）→ 看后面的歌词 → dragPx 增大。 */
                            dragPx = (dragPx - dragAmount)
                                .coerceIn(
                                    -active.coerceAtLeast(0) * rowPx,
                                    (current.lines.lastIndex - active.coerceAtLeast(0)) * rowPx,
                                )
                        }
                    },
                /**
                 * 用户口径（#90）**像素级平滑偏移**：把「不足一行的余量」变成实际位移。
                 *
                 * `dragLines` 是连续值（如 1.37 行），`dragRows` 是取整（1 行）。
                 * 已经按 dragRows 移动了 1 行，剩下的 0.37 行 = 0.37*rowPx 像素
                 * 用 graphicsLayer 平移补上 —— 画面就跟着手指连续走了，不跳变。
                 * 方向：手指上滑（dragPx 增大）→ 内容向上移（translationY 为负）。
                 */
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                /** 画 rows 行：从 centerIndex - half 到 centerIndex + half。 */
                for (offset in -half..half) {
                    val idx = centerIndex + offset
                    val line = current.lines.getOrNull(idx)
                    val isCenter = offset == 0
                    val highlighted = isCenter
                    val color by animateColorAsState(
                        if (highlighted || !current.synced) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        label = "lyric",
                    )
                    Box(
                        Modifier.fillMaxWidth().height(rowHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            line?.text.orEmpty(),
                            style = if (highlighted) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.titleMedium,
                            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
                            color = color,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                        )
                    }
                }
            }

            /**
             * 定位线：只在手动滑动时显示（用户口径 #76）。
             *
             * 位置：**中间那行文字的下沿再往下 2dp**，不压在字上 ——
             * 直接 `align(Center)` 会正好穿过文字中间，看着像划掉。
             * 用 offset 往下挪半个行高的一半，落在行与行之间的空隙里。
             */
            if (showLocator) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(y = rowHeight / 2 + 2.dp)
                        .fillMaxWidth(0.72f)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), Corner.pill),
                )
            }

            /** 定位线右侧的小播放按钮：点它跳到中间那行。 */
            if (showLocator) {
                current.lines.getOrNull(centerIndex)?.let { target ->
                    FilledIconButton(
                        onClick = {
                            graph.music.seekTo(target.timeMs)
                            /** 跳完立刻回到跟随状态（用户口径 #76）。 */
                            dragPx = 0f
                            dragging = false
                        },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).size(40.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, "跳到这句播放", Modifier.size(22.dp))
                    }
                    Box(Modifier.align(Alignment.CenterStart).padding(start = 16.dp)) {
                        Text(
                            formatClock(target.timeMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** 占位：固定窗口方案不需要滚动状态，保留签名方便以后切换。 */
private fun listScrollInProgressIgnore(): Boolean = false

@Composable
private fun QueuePane(queue: List<Item>, index: Int) {
    val music = LocalContext.current.graph.music
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (index - 2).coerceAtLeast(0))
    LazyColumn(state = list, contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(queue, key = { i, item -> "$i-${item.id}" }) { i, item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    /** 用户口径（#104）这一处也统一成「只有垃圾桶」（原来三点 + 叉号并存）。 */
                    TrackRow(
                        item,
                        onClick = { music.skipTo(i) },
                        horizontalPadding = 0.dp,
                        trailing = {
                            IconButton(onClick = { music.removeAt(i) }, modifier = Modifier.size(40.dp)) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    "移出队列",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }
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
    /** 用户口径（#91）单曲从队列移除。 */
    onRemove: (Int) -> Unit,
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
                            /**
                             * 用户口径（#104）**三点必须消失，只剩垃圾桶**。
                             *
                             * 用户原话：
                             * ```
                             * 「你现在是三个点和垃圾桶都留着了，这3个点啥用没有？留它干啥呀」
                             * ```
                             * 上一版：TrackRow 没传 trailing（三点照画），
                             * 外面又加了一个 IconButton（垃圾桶）→ 两个并排。
                             *
                             * 现在：垃圾桶作为 trailing 传进去，TrackRow 内部不画三点。
                             */
                            TrackRow(
                                item,
                                onClick = { onPick(item) },
                                horizontalPadding = Etkn.space.screen,
                                trailing = {
                                    IconButton(
                                        onClick = { onRemove(i) },
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            "从播放列表移除",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                            )
                        }
                        if (i == index) {
                            Text(
                                "播放中",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 4.dp),
                            )
                        }
                    }
                }
            }
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
    Box(modifier.height(14.dp), contentAlignment = Alignment.CenterStart) {
        // 底轨
        Box(Modifier.fillMaxWidth().height(3.dp).background(trackColor, Corner.pill))
        // 缓存段
        Box(
            Modifier.fillMaxWidth(cachedFraction.coerceAtLeast(played))
                .height(3.dp).background(cachedColor, Corner.pill),
        )
        // 已播放
        Box(Modifier.fillMaxWidth(played).height(3.dp).background(accent, Corner.pill))
        /**
         * 用户口径（2026-11-27，#37）**迷你条也要有那个实心圆点**。
         *
         * 用户原话：「小的播放控件里边是没有那个圆点的，把那个圆点加上」
         *
         * 原来这里只有 7dp，而且外层容器只有 10dp 高，圆点被压得几乎看不见。
         * 现在加大到 10dp，容器给到 14dp。
         */
        Box(
            Modifier.fillMaxWidth(played).height(14.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(accent, CircleShape),
            )
        }
    }
}


