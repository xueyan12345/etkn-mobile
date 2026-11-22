package xyz.etkn.mobile.player

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.delay
import xyz.etkn.mobile.data.settings.SkipMode
import xyz.etkn.mobile.player.danmaku.DanmakuView
import xyz.etkn.mobile.player.mpv.MpvPlayer
import xyz.etkn.mobile.ui.theme.Corner

internal val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
private val ResizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "适应",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁切填充",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "拉伸",
)

internal enum class Sheet { None, Audio, Subtitle, Speed, Episodes, Versions, Danmaku, Sleep, Cast, More }

@Composable
fun PlayerScreen(session: VideoSession, inPip: Boolean, onBack: () -> Unit, onPip: () -> Unit) {
    val ui by session.ui.collectAsStateWithLifecycle()
    val danmakuStyle by session.danmakuStyle.collectAsStateWithLifecycle()
    val danmakuCues by session.danmakuCues.collectAsStateWithLifecycle()
    val themeArgb = MaterialTheme.colorScheme.primary.toArgb()
    val player by session.players.collectAsStateWithLifecycle()
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var resize by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(Sheet.None) }
    // 用户口径（2026-11-22）：播放器原本被 manifest 钉死在 sensorLandscape，
    // 竖屏视频只能显示中间一条，也没有转屏按钮。这里加一个手动方向开关：
    // 默认跟随视频本身的比例（竖屏视频就竖屏显示），也可手动切到另一方向。
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity
    // true = 竖屏（portrait），false = 横屏（landscape）
    var portrait by remember {
        mutableStateOf(activity?.resources?.configuration?.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT)
    }
    val applyOrientation: (Boolean) -> Unit = { wantPortrait ->
        portrait = wantPortrait
        activity?.requestedOrientation = if (wantPortrait) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var gesture by remember { mutableStateOf<GestureFeedback?>(null) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(player) {
        val current = player
        playing = current?.isPlaying == true
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        current?.addListener(listener)
        onDispose { current?.removeListener(listener) }
    }
    LaunchedEffect(ui.finished) { if (ui.finished) onBack() }
    // Poll the clock only as fast as the UI needs it: 4 Hz while the controls show it, else 1 Hz
    // (enough for the skip-intro / credits segments).
    LaunchedEffect(player) {
        val current = player ?: return@LaunchedEffect
        while (true) {
            position = current.currentPosition.coerceAtLeast(0)
            duration = current.duration.takeIf { it > 0 } ?: 0
            buffered = current.bufferedPosition
            delay(if (controls || gesture != null) 250 else 1_000)
        }
    }
    // Auto-hide controls after 4 s of inactivity while playing.
    LaunchedEffect(controls, lastInteraction, playing, sheet) {
        if (controls && playing && sheet == Sheet.None) {
            delay(4_000)
            controls = false
        }
    }
    val segment = remember(position, ui.segments, ui.skipMode) { session.segmentAt(position) }
    LaunchedEffect(segment) { segment?.let(session::autoSkip) }
    // Double-tap seek feedback clears itself; drag feedback is cleared on release.
    LaunchedEffect(gesture) {
        val shown = gesture
        if (shown is GestureFeedback.Seek) {
            delay(800)
            if (gesture === shown) gesture = null
        }
    }
    val touch = { lastInteraction = System.currentTimeMillis() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val core = player) {
            null -> Unit
            // mpv draws video and subtitles into its own SurfaceView.
            is MpvPlayer -> key(core) {
                AndroidView(
                    factory = { (core.view.parent as? ViewGroup)?.removeView(core.view); core.view },
                    update = { core.setResize(resize) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> key(core) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            setKeepContentOnPlayerReset(true)
                            // Plain white text with a dark outline instead of the system caption box
                            // (black background); ASS/SSA keep their own embedded styling.
                            subtitleView?.apply {
                                setStyle(
                                    CaptionStyleCompat(
                                        android.graphics.Color.WHITE,
                                        android.graphics.Color.TRANSPARENT,
                                        android.graphics.Color.TRANSPARENT,
                                        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                        android.graphics.Color.BLACK,
                                        null,
                                    ),
                                )
                                setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.08f)
                                setBottomPaddingFraction(0.06f)
                            }
                            this.player = core
                        }
                    },
                    update = { view -> view.resizeMode = ResizeModes[resize].first },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val core = player
        if (ui.danmakuOn && !inPip && core != null) {
            key(core) {
                AndroidView(
                    factory = { ctx -> DanmakuView(ctx, core) },
                    update = { view ->
                        view.apply(danmakuStyle, themeArgb)
                        view.setCues(danmakuCues)
                    },
                    onRelease = { it.close() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (!inPip && core != null) {
            GestureLayer(
                enabled = !locked,
                session = session,
                player = core,
                onToggle = { controls = !controls; touch() },
                onFeedback = { gesture = it },
            )
        }

        if (ui.loading && ui.error == null) {
            LoadingIndicator(Modifier.align(Alignment.Center).size(72.dp), color = Color.White)
        }
        ui.error?.let { message ->
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = onBack) { Text("返回") }
            }
        }
        gesture?.let { feedback ->
            // Notices sit high, clear of faces and subtitles; seek and level readouts stay central.
            // 长按调速（Speed）也算 notice：放顶部，且 5 秒后自动消失，不长期挡画面。
            val isNotice = feedback is GestureFeedback.Text || feedback is GestureFeedback.Speed
            val place = if (isNotice) {
                Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 48.dp)
            } else Modifier.align(Alignment.Center)
            GestureBadge(feedback, place)
        }
        // 用户口径（2026-11-22）：倍速徽标持续 5 秒后自动隐藏。
        // 用 key(gesture) 让每次倍速变化重新计时；松手本身已清空 gesture，这里兜住「一直按住不放」的情况。
        if (gesture is GestureFeedback.Speed) {
            LaunchedEffect(gesture) {
                delay(5000)
                if (gesture is GestureFeedback.Speed) gesture = null
            }
        }
        ui.notice?.let { message ->
            LaunchedEffect(message) {
                delay(3_500)
                session.clearNotice(message)
            }
            Text(
                message,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 64.dp).background(Color.Black.copy(alpha = 0.55f), Corner.pill)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (!inPip) {
            AnimatedVisibility(controls && !locked, enter = fadeIn(), exit = fadeOut()) {
                Controls(
                    ui = ui, playing = playing, position = position, duration = duration, buffered = buffered,
                    onBack = onBack, onPip = onPip,
                    onPlayPause = { core?.let { if (it.isPlaying) it.pause() else it.play() }; touch() },
                    onSeek = { core?.seekTo(it); touch() },
                    onSeekBy = { delta -> core?.let { p -> p.seekTo((p.currentPosition + delta).coerceAtLeast(0)) }; touch() },
                    onSheet = { sheet = it; touch() },
                    onResize = { resize = (resize + 1) % ResizeModes.size; touch() },
                    resizeLabel = ResizeModes[resize].second,
                    onRotate = { applyOrientation(!portrait); touch() },
                    rotateLabel = if (portrait) "竖屏" else "横屏",
                    onLock = { locked = true; controls = false },
                    onNext = { session.playNext() },
                )
            }
            if (locked) {
                FilledIconButton(
                    onClick = { locked = false; controls = true },
                    modifier = Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White),
                ) { Icon(Icons.Rounded.Lock, "解锁") }
            }
            segment?.takeUnless { ui.skipMode == SkipMode.Auto && it.kind == SkipSegment.Kind.Intro }?.let { s ->
                FilledTonalButton(
                    onClick = { session.skip(s) },
                    modifier = Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(end = 24.dp, bottom = if (controls) 120.dp else 32.dp),
                ) {
                    Icon(if (s.kind == SkipSegment.Kind.Intro) Icons.Rounded.FastForward else Icons.Rounded.SkipNext, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (s.kind == SkipSegment.Kind.Intro) "跳过片头" else if (ui.next != null) "下一集" else "跳过片尾")
                }
            }
        }
    }

    if (sheet != Sheet.None) {
        PlayerSheet(sheet, ui, session, onSheet = { sheet = it }, onDismiss = { sheet = Sheet.None })
    }
}
