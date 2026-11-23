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
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints

internal val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
private val ResizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "适应",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁切填充",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "拉伸",
)

internal enum class Sheet { None, Audio, Subtitle, Speed, Episodes, Versions, Danmaku, Sleep, Cast }

@Composable
fun PlayerScreen(session: VideoSession, inPip: Boolean, onBack: () -> Unit, onPip: () -> Unit) {
    val ui by session.ui.collectAsStateWithLifecycle()
    val danmakuStyle by session.danmakuStyle.collectAsStateWithLifecycle()
    val danmakuCues by session.danmakuCues.collectAsStateWithLifecycle()
    val themeArgb = MaterialTheme.colorScheme.primary.toArgb()
    val player by session.players.collectAsStateWithLifecycle()
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    /** 用户口径（2026-11-23）：剧集简介浮层是否打开；打开时控制条不自动隐藏。 */
    var infoOpen by remember { mutableStateOf(false) }
    var resize by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(Sheet.None) }
    // 用户口径（2026-11-23）：去掉重力感应横竖屏，改成「按视频宽高比自动判断」。
    // 宽 > 高 → 横屏；高 > 宽 → 竖屏。切下一集时视频尺寸会变，这里监听 videoSize 重判，
    // 不用退出播放器。手动旋转按钮仍然可以临时覆盖，换集后会重新跟随视频比例。
    val context = androidx.compose.ui.platform.LocalContext.current
    /**
     * 用户口径（2026-11-23 修「所有视频都默认竖屏」）：
     * 原来写 `context as? android.app.Activity` —— Compose 里 LocalContext 常常是
     * ContextWrapper（主题包装 / 缩放包装），强转直接得 null，于是 applyOrientation()
     * 静默什么都不做，方向永远是 Manifest 的默认值 = 竖屏。
     * 改成沿 ContextWrapper 链一路往上找真正的 Activity。
     */
    val activity = remember(context) { context.findActivity() }
    /**
     * 用户口径（2026-11-23）：Compose 里读设备方向必须用 LocalConfiguration，
     * 直接读 context.resources.configuration 会拿到过期值（lint 也报了这条）。
     * 旋转判据依赖「当前真实方向」，读到旧值就永远判错。
     */
    val localConfig = androidx.compose.ui.platform.LocalConfiguration.current
    // true = 竖屏（portrait），false = 横屏（landscape）
    // 用户口径（2026-11-23 修「横屏视频点进去还是竖屏」）：
    // 这里原来是 false。而下面所有调用点都写成 `if (wantPortrait != portrait) applyOrientation(...)`，
    // 于是横屏视频 wantPortrait=false == portrait=false → 条件不成立 → 一次都没应用，
    // Activity 就一直停在 Manifest 的 unspecified（= 设备当前方向 = 竖屏）。
    // 改用 nullable 表示「还没应用过」，第一次一定应用。
    var portrait by remember { mutableStateOf<Boolean?>(null) }
    /**
     * 用户口径（2026-11-23）OPPO Pad Mini 专项：**进播放器立刻就是横屏**。
     *
     * 之前初值是 false，要先发系统方向请求、等 120ms 读回、确认系统不认才开兜底，
     * 结果用户看到的是「先竖着、一闪、再转」甚至根本不转。
     *
     * 现在初值直接给 true：一进播放器就按横屏画布绘制（不依赖系统方向）。
     * 如果系统其实认了横屏请求（手机上就是这样），读回校验后把兜底关掉，
     * 交还给系统方向 —— 两边都不冲突。
     */
    // 用户手动覆盖过方向就不再自动跟随，直到换集重置
    var manualOrientation by remember { mutableStateOf(false) }
    /**
     * 用户口径（2026-11-23）：系统不认方向 -> 整层 90° 旋转（配合 TextureView 才有效）。
     * 初值 false：先让系统试；确认系统没转过去才打开。
     */
    var forceRotate by remember { mutableStateOf(false) }
    /**
     * 用户口径（2026-11-23）OPPO Pad Mini 专项：这台平板上「旋转屏幕」按钮完全无效，
     * 只能跟随系统方向。SenPlayer / AfuseKit 同样如此 → 不是我们独有的 bug，
     * 是这台设备（Android 16 大屏 + ColorOS）的取向限制。
     *
     * 根因（Android 16 官方行为变更）：宽 ≥600dp 的大屏设备上，系统**默认忽略**
     * 应用通过 requestedOrientation 发的方向请求 —— 所以手机有效、平板无效。
     * 破解必须先关掉这个「忽略」开关，再发方向请求。
     *
     * 逐级兜底（每一步失败都继续往下试，绝不静默放弃）：
     *  ① setShouldDismissRequestedOrientation 相关 API（Android 16 / API 36）
     *  ② Activity.setRequestedOrientation（传统路径，配合 ① 才在大屏生效）
     *  ③ 都无效时，退化为「旋转画面」：给 PlayerScreen 一个 90° 旋转标记，
     *     不改系统方向、只把视频画面转过来（任何设备都能强制横看）
     */
    /**
     * 用户口径（2026-11-23）旋转按钮/自动横屏 —— **第三轮，这次找到真错了**。
     *
     * 前几轮一直不生效，因为判据用了 `activity.requestedOrientation` 读回值来判断
     * 「系统是否接受了我的方向请求」。但那个 getter **永远返回我自己刚设置的值**，
     * 跟系统到底有没有真的转过去完全无关 → `accepted` 恒为 true →
     * 画面旋转兜底永远关着 → 平板上系统不认方向时就彻底没反应。
     *
     * 正确判据：读 `resources.configuration.orientation`，那是**屏幕当前真实方向**。
     *   发请求 → 等 350ms（系统转屏是异步的）→ 看真实方向对不对
     *   对上了 → 系统认了，交还给系统方向
     *   没对上 → 系统忽略（大屏设备）→ 打开画面级 90° 旋转兜底
     */
    /**
     * 用户口径（2026-11-23）旋转 —— **最终方案：只走系统方向，不做画面旋转**。
     *
     * 为什么放弃画面旋转：实测给视频层套 graphicsLayer rotationZ=90° 之后，
     * SurfaceView 是独立硬件图层，Compose 转不动它，画面变成「竖排文字」（用户截图证实）。
     *
     * 系统方向在大屏设备上会被忽略（Android 16 行为变更），所以要做的是
     * **把请求发得更强硬**，而不是自己转画面：
     *  ① 用 USER_LANDSCAPE / USER_PORTRAIT（比 LANDSCAPE 更强制，部分 ROM 认这个）
     *  ② 一次性把 activity 的所有方向相关锁定都清掉再设
     *  ③ 300ms 后检查真实方向，没转过去就重发一次（有些设备首次会被丢弃）
     */
    val applyOrientation: (Boolean) -> Unit = { wantPortrait ->
        portrait = wantPortrait
        val act = activity
        val target = if (wantPortrait) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
        val apply = {
            runCatching { act?.requestedOrientation = target }
            // 部分 ROM 需要再碰一次 window 属性才会重新评估方向
            runCatching {
                act?.window?.attributes = act.window.attributes.apply {
                    screenOrientation = target
                }
            }
        }
        apply()
        val want = if (wantPortrait) android.content.res.Configuration.ORIENTATION_PORTRAIT
        else android.content.res.Configuration.ORIENTATION_LANDSCAPE
        // 300ms 后校验真实方向，没成再强推一次（最多三次）
        fun verify(round: Int) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val real = localConfig.orientation
                val ok = real == want
                // 用户口径（2026-11-23）：系统真转过去了 -> 不需要旋转画面；
                // 系统不认（大屏设备）-> 打开整层 90° 旋转，配合 TextureView 真正生效。
                forceRotate = !ok
                android.util.Log.i(
                    "ETKN-Rotate",
                    "round=$round want=${if (wantPortrait) "P" else "L"} " +
                        "real=${if (ok) "P" else "L"} ok=$ok canvasRotate=$forceRotate",
                )
                if (!ok && round < 3) {
                    apply()
                    verify(round + 1)
                }
            }, 300L)
        }
        verify(1)
    }

    /**
     * 用户口径（2026-11-23）：打开播放器瞬间默认就是横屏。
     * 绝大多数片源是横屏，先按横屏起，等服务端/播放器给出真实尺寸后
     * 如果判断是竖屏视频，再切成竖屏。这样避免「先竖屏站着，过一会儿才转过去」的闪动。
     * 这个 LaunchedEffect 必须声明在「服务端尺寸判断」之前，保证先执行。
     */
    LaunchedEffect(Unit) {
        /**
         * 用户口径（2026-11-23）诊断：v1.2.5 实机日志里 ETKN-Rotate 出现 0 次，
         * 说明这条自动方向逻辑要么没跑、要么跑到了却没进 verify() 打日志的分支。
         * 这里在入口无条件打一行，把「有没有被调用 / activity 是否为 null / 当前真实方向」
         * 一次性暴露出来，下一次日志就能定位到底断在哪一层。
         */
        val act = activity
        // 用 Compose 的 LocalConfiguration（配置感知），不读 context.resources（可能过期）
        val realNow = localConfig.orientation
        val realText = when (realNow) {
            android.content.res.Configuration.ORIENTATION_PORTRAIT -> "P"
            android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "L"
            else -> "?"
        }
        android.util.Log.i(
            "ETKN-Rotate",
            "enter auto: manual=$manualOrientation activity=${act != null} real=$realText " +
                "smallestDp=${localConfig.smallestScreenWidthDp}",
        )
        if (!manualOrientation) applyOrientation(false)
    }

    /**
     * 用户口径（2026-11-23，修「记忆上次亮度不生效」）：
     * 原来只在拖动手势里写 SharedPreferences（screen_brightness），
     * 进播放器时从来没人读回来应用 → 每次都是系统默认亮度。
     * 这里进播放器就恢复上次的亮度。
     */
    LaunchedEffect(Unit) {
        val act = activity ?: return@LaunchedEffect
        val saved = context
            .getSharedPreferences("etkn_player", android.content.Context.MODE_PRIVATE)
            .getFloat("screen_brightness", -1f)
        if (saved in 0.01f..1f) {
            act.window?.attributes = act.window.attributes.apply { screenBrightness = saved }
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

            /**
             * 用户口径（2026-11-23）：按视频宽高比自动决定横竖屏。
             * 这个回调在每次载入新视频时都会触发（包括不退出播放器直接切下一集），
             * 所以切集后方向会重新跟随新视频，不用手动干预。
             */
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (manualOrientation) return
                val w = videoSize.width
                val h = videoSize.height
                if (w <= 0 || h <= 0) return
                val wantPortrait = h > w
                if (portrait == null || wantPortrait != portrait) applyOrientation(wantPortrait)
            }
        }
        current?.addListener(listener)
        onDispose { current?.removeListener(listener) }
    }

    /**
     * 用户口径（2026-11-23，修「横屏视频点进去默认竖屏」）：
     * 不靠播放器回调猜方向 —— ETKN 的 MediaStreams 里就有 Width/Height
     * （实测全库 47855 条，横屏 33158 / 竖屏 14322 / 正方 375，覆盖 100%）。
     * 进播放器就用服务端已知尺寸直接定方向，首帧前就已经是对的，
     * 不再出现「先竖屏闪一下再转」。
     *
     * 优先级：服务端尺寸 > 播放器 onVideoSizeChanged（服务端没给时才回落）。
     */
    LaunchedEffect(ui.item?.id, ui.sourceIndex) {
        if (manualOrientation) return@LaunchedEffect
        // MediaSource.video 是现成的扩展（streams 里第一个 Type=Video）
        val v = ui.sources.getOrNull(ui.sourceIndex)?.video
        val w = v?.width ?: 0
        val h = v?.height ?: 0
        if (w > 0 && h > 0) {
            val wantPortrait = h > w
            if (portrait == null || wantPortrait != portrait) applyOrientation(wantPortrait)
        }
    }
    // 切集时重置手动覆盖，让新视频自己决定方向
    LaunchedEffect(ui.item?.id) { manualOrientation = false }
    LaunchedEffect(ui.finished) { if (ui.finished) onBack() }
    // Poll the clock only as fast as the UI needs it: 4 Hz while the controls show it, else 1 Hz
    // (enough for the skip-intro / credits segments).
    LaunchedEffect(player) {
        val current = player ?: return@LaunchedEffect
        var lastCacheTick = 0L
        while (true) {
            position = current.currentPosition.coerceAtLeast(0)
            duration = current.duration.takeIf { it > 0 } ?: 0
            buffered = current.bufferedPosition
            /**
             * 用户口径（2026-11-23）：缓存窗口跟着播放进度往前推。
             * 每 5 秒驱动一次（不必每 250ms 一次，避免频繁开 Range 请求），
             * 由 VideoSession 决定「当前集窗口是否到位 / 是否该启动下一集流水线」。
             */
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastCacheTick > 5_000L) {
                lastCacheTick = now
                session.tickCache(position, duration)
            }
            delay(if (controls || gesture != null) 250 else 1_000)
        }
    }
    // Auto-hide controls after 4 s of inactivity while playing.
    /**
     * 用户口径（2026-11-23）：自动隐藏要分清「进度条界面」和「弹层」。
     *
     * 之前这里只判断了 `sheet == Sheet.None`，但那个剧集简介浮层是
     * PlayerControls 内部的局部状态，不在 Sheet 枚举里 ——
     * 所以简介弹出来时，这条逻辑照旧 4 秒把整个控制条（连同简介）收掉，
     * 用户还没看完就消失了。
     *
     * 现在：控制条自动隐藏只在「没有打开任何弹层」时执行。
     * 简介浮层通过 [infoOpen] 上报自己的打开状态，打开期间不自动隐藏。
     */
    LaunchedEffect(controls, lastInteraction, playing, sheet, infoOpen) {
        if (controls && playing && sheet == Sheet.None && !infoOpen && !locked) {
            delay(4_000)
            controls = false
        }
    }
    val segment = remember(position, ui.segments, ui.skipMode) { session.segmentAt(position) }
    /**
     * 用户口径（2026-11-23）：自动跳过要加 5 秒倒计时。
     * · 选「自动跳过」时，跳过按钮照常显示，按钮内部用色块从左往右填充当进度
     * · 5 秒填满 → 才真正触发跳过
     * · 用户中途点了按钮 → 立即跳过（取消倒计时）
     * · 离开这个片段（倒计时没走完）→ 取消，不跳
     */
    var skipCountdown by remember { mutableFloatStateOf(0f) }   // 0f..1f
    /**
     * 用户口径（2026-11-23）修「起播时闪一下跳过片头」：
     * 之前只要 segment 有值就立刻起倒计时 —— 起播位置是 0 秒，正好落在片头区间，
     * 于是按钮和色块先闪一下才开始跳。
     *
     * 现在加前置条件：**必须已经在播且不在起播缓冲中**，才显示按钮和起倒计时。
     * 起播阶段（ui.loading / 首帧未出）完全不显示，避免闪。
     */
    /**
     * 用户口径（2026-11-23）**「取消」要真的取消掉整组按钮**。
     *
     * 之前的问题：点「取消」只是调 session.cancelAutoSkip(s) 设了个标志位，
     * 而 skipCountdown 这个 UI 状态没人清、segment 也没变 →
     *  ① 胶囊 + 「取消」两个字一直留在屏幕上不走
     *  ② 倒计时照样跑到 100%，数字停在「1s」不动
     * 用户看到的就是「固定在 1 秒，取消也不消失」。
     *
     * 现在用一个独立的 dismissed 状态：点取消 → 立刻把倒计时清零 + 标记已忽略这一段，
     * 整组按钮马上消失，并且这一段不会再弹出来（换段/换集才复位）。
     */
    var skipDismissed by remember { mutableStateOf(false) }
    // 换到别的片段 / 换集时复位「已忽略」
    LaunchedEffect(segment?.kind, ui.item?.id) { skipDismissed = false }

    val skipVisible = segment != null && !ui.loading && ui.error == null &&
        position > 500 && !skipDismissed
    LaunchedEffect(segment, skipVisible) {
        if (!skipVisible) {
            skipCountdown = 0f
            return@LaunchedEffect
        }
        if (ui.skipMode != SkipMode.Auto) {
            skipCountdown = 0f
            return@LaunchedEffect
        }
        skipCountdown = 0.01f
        // 5 秒色块填充：每 50ms 走 1%，5 秒正好 100%
        repeat(100) { step ->
            delay(50)
            // 中途点了取消 → 立刻停止填充，保持 0（按钮已由 skipDismissed 隐藏）
            if (skipDismissed) {
                skipCountdown = 0f
                return@LaunchedEffect
            }
            skipCountdown = (step + 1) / 100f
        }
        // 填满才真正跳
        if (!skipDismissed) session.autoSkip(segment)
    }
    // Double-tap seek feedback clears itself; drag feedback is cleared on release.
    LaunchedEffect(gesture) {
        val shown = gesture
        if (shown is GestureFeedback.Seek) {
            delay(800)
            if (gesture === shown) gesture = null
        }
    }
    val touch = { lastInteraction = System.currentTimeMillis() }

    /**
     * 用户口径（2026-11-23）OPPO Pad Mini 兜底（重做）：
     *
     * 上一版用 graphicsLayer rotationZ=90° 只转单个图层 —— 转完画面跑出可视区、
     * 也没交换宽高，所以用户看到的还是「竖窗口里塞横屏画面」。
     *
     * 正确做法：把**整个播放器 UI** 放进一个「逻辑尺寸 = 屏宽高互换」的容器，
     * 再把容器整体转 90° 铺满屏幕。布局按横屏算、画面也是横的，
     * 完全不依赖系统 orientation。
     */
    /**
     * 用户口径（2026-11-23）**平板旋转最终方案：TextureView + 整层 90° 旋转**。
     *
     * 前两次为什么失败：
     *  · 只转视频层 → PlayerView 用 SurfaceView 输出，SurfaceView 是独立硬件合成层，
     *    不在 View 树里绘制，Compose 转不动它 → 画面变「竖排文字」。
     *  · 改用系统 requestedOrientation → Android 16 大屏直接忽略这个请求。
     *
     * 这次两条一起做（缺一不可）：
     *  ① Exo 的 PlayerView 已切 setUseTextureView(true)
     *     → 画面变成普通纹理，绘制在 View 树内，可以被变换
     *  ② 整层（视频+控制条+弹幕）按「宽高互换画布」+ rotationZ=90° 绘制
     *     → 竖屏窗口里看到完整横屏画面，控件位置也正确
     *
     * 仅当系统确实不转（forceRotate=true）时启用；正常设备仍是普通全屏。
     */
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val rotated = forceRotate
        val canvasW = if (rotated) maxHeight else maxWidth
        val canvasH = if (rotated) maxWidth else maxHeight
        Box(
            Modifier.then(
                if (rotated) {
                    Modifier
                        .size(canvasW, canvasH)
                        .graphicsLayer {
                            rotationZ = 90f
                            translationX = (canvasH - canvasW).toPx() / 2f
                            translationY = (canvasW - canvasH).toPx() / 2f
                        }
                } else {
                    Modifier.fillMaxSize()
                },
            ),
        ) {
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
                            /**
                             * 用户口径（2026-11-23）**平板旋转的关键**。
                             *
                             * 之前旋转整层失败，画面变成「竖排文字」，根因是：
                             * PlayerView 默认用 SurfaceView 输出画面，SurfaceView 是独立的
                             * 硬件合成层（不在 View 层级树里绘制），Compose 的
                             * graphicsLayer/graphicsLayer 旋转只能转 View 树，转不动它。
                             *
                             * 换成 TextureView：画面作为普通纹理绘制在 View 树内，
                             * 父容器做 90° 旋转变换就能真正生效。代价是略多一点 GPU 合成开销，
                             * 但换来「系统不认方向时也能强制横屏」这个必须有的能力。
                             */
                            setUseTextureView(true)
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
                    onSeek = { session.resetAutoSkipIfBefore(it); core?.seekTo(it); touch() },
                    onSeekBy = { delta -> core?.let { p -> p.seekTo((p.currentPosition + delta).coerceAtLeast(0)) }; touch() },
                    onSheet = { sheet = it; touch() },
                    onResize = { resize = (resize + 1) % ResizeModes.size; touch() },
                    resizeLabel = ResizeModes[resize].second,
                    onRotate = { manualOrientation = true; applyOrientation(!(portrait ?: true)); touch() },
                    rotateLabel = if (portrait == true) "竖屏" else "横屏",
                    onLock = { locked = true; controls = false },
                    onNext = { session.playNext() },
                    onPrevious = { session.playPrevious() },
                    onSpeed = { session.setSpeed(it) },
                    onInfoOpenChange = { infoOpen = it },
                    onToggleDanmaku = {
                        // 弹幕开关：直接翻转 enabled（样式其它项保持不变）
                        session.setDanmakuStyle(session.danmakuStyle.value.copy(enabled = !ui.danmakuOn))
                        touch()
                    },
                )
            }
            if (locked) {
                FilledIconButton(
                    onClick = { locked = false; controls = true },
                    modifier = Modifier.align(Alignment.CenterStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White),
                ) { Icon(Icons.Rounded.Lock, "解锁") }
            }
            // 用户口径（2026-11-23）：自动跳过模式下按钮也要显示，按钮内部用色块填充当 5 秒倒计时。
            // 之前这里是 takeUnless { Auto && Intro } —— Auto 模式直接把片头按钮藏了，所以看不到倒计时。
            segment?.takeIf { skipVisible }?.let { s ->
                val label = when {
                    s.kind == SkipSegment.Kind.Intro -> "跳过片头"
                    ui.next != null -> "下一集"
                    else -> "跳过片尾"
                }
                val countingDown = ui.skipMode == SkipMode.Auto && skipCountdown > 0f
                // 用户口径（2026-11-23）：倒计时色块只在这颗胶囊按钮**内部**从左往右填，
                // 绝不能铺到整屏 —— 之前把填充层写成 fillMaxSize 的独立 Box，撑满的是整个播放界面，
                // 所以出现了「整个屏幕在倒计时」。现在直接在胶囊自己的 drawBehind 里画，
                // 尺寸天然就是按钮宽×按钮高。
                Row(
                    Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(end = 24.dp, bottom = if (controls) 120.dp else 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                Box(
                    Modifier
                        .clip(Corner.pill)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .drawBehind {
                            if (countingDown) {
                                val w = size.width * skipCountdown.coerceIn(0f, 1f)
                                if (w > 0f) {
                                    drawRoundRect(
                                        color = Color.White.copy(alpha = 0.28f),
                                        size = androidx.compose.ui.geometry.Size(w, size.height),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                                    )
                                }
                            }
                        }
                        .clickable { session.skip(s) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (s.kind == SkipSegment.Kind.Intro) Icons.Rounded.FastForward else Icons.Rounded.SkipNext,
                            null,
                            Modifier.size(18.dp),
                            tint = Color.White,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = Color.White, fontSize = 14.sp)
                        if (countingDown) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${(5 * (1f - skipCountdown)).toInt() + 1}s",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                    // 用户口径（2026-11-23）：照参考图，胶囊右边还有一个「取消」。
                    // 点它 = 这次不跳（关掉倒计时，也不要再自动跳这一段）。
                    if (countingDown) {
                        Text(
                            "取消",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 14.sp,
                            modifier = Modifier.clickable {
                                // 用户口径（2026-11-23）：点取消 → 这组按钮立刻消失，别再固定在那儿
                                skipDismissed = true
                                skipCountdown = 0f
                                session.cancelAutoSkip(s)
                            },
                        )
                    }
                }
            }
        }
        }
    }

    if (sheet != Sheet.None) {
        PlayerSheet(
            sheet, ui, session,
            onSheet = { sheet = it },
            onDismiss = { sheet = Sheet.None },
            onRotate = { manualOrientation = true; applyOrientation(!(portrait ?: true)); touch() },
            portrait = portrait ?: false,
        )
    }
}

/** 沿 ContextWrapper 链往上找到真正的 Activity（LocalContext 不一定是 Activity）。 */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return c as? android.app.Activity
}
