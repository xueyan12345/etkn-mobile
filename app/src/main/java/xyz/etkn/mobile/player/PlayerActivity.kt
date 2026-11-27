package xyz.etkn.mobile.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.VideoSize
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.settings.Settings
import xyz.etkn.mobile.data.settings.UiScale
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.components.LocalImages
import xyz.etkn.mobile.ui.theme.EtknTheme

class PlayerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        UiScale.applyTo(this, newBase)
    }

    /** The title on screen; a new intent (singleTask: another title picked while in PiP or playing in the background) replaces it. */
    private var session by mutableStateOf<VideoSession?>(null)
    private var inPip by mutableStateOf(false)
    private var wasPlaying = false
    private var backgroundPlay = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
        allowOrientationOverride()

        val active = graph.sessions.current
        if (active == null || !open(intent)) {
            finish()
            return
        }
        // 后台播放: a media session around whichever core is playing, while the setting is on.
        lifecycleScope.launch {
            val players = snapshotFlow { session }.flatMapLatest { it?.players ?: flowOf(null) }
            combine(graph.settings.flow.map { it.backgroundPlay }.distinctUntilChanged(), players) { on, player -> on to player }
                .collect { (on, player) ->
                    backgroundPlay = on
                    if (on && player != null) VideoPlaybackService.attach(this@PlayerActivity, player) else VideoPlaybackService.detach(this@PlayerActivity)
                }
        }
        val urls = graph.images(active)
        updatePipParams()
        setContent {
            val settings by graph.settings.flow.collectAsStateWithLifecycle(initialValue = Settings())
            EtknTheme(settings, forceDark = true) {
                CompositionLocalProvider(LocalImages provides urls) {
                    session?.let { video ->
                        key(video) {
                            PlayerScreen(
                                session = video,
                                inPip = inPip,
                                onBack = { finish() },
                                onPip = { enterPip() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The media notification only brings the player back to the front; a title replaces it.
        if (intent.getStringExtra(PlayerLauncher.EXTRA_ITEM) == null) return
        setIntent(intent)
        close()
        if (!open(intent)) finish()
    }

    /** Starts the title [intent] names; false when there is nothing to play. */
    private fun open(intent: Intent): Boolean {
        val itemId = intent.getStringExtra(PlayerLauncher.EXTRA_ITEM) ?: return false
        graph.music.pause()
        val video = VideoSession(this, graph, lifecycleScope)
        session = video
        /**
         * 用户口径（2026-11-27，#16）**把详情页选的音轨/字幕传给播放器**。
         *
         * 约定：-99 = 没选过（按服务端默认）；-1 = 关闭字幕；>=0 = 指定流的 Index。
         * 用 -99 而不是"不传"，是因为 Intent 的 getIntExtra 默认值也会是 0，
         * 无法区分"用户选了第 0 条"和"用户没选"。
         */
        val audioIdx = intent.getIntExtra(PlayerLauncher.EXTRA_AUDIO_INDEX, -99)
        val subIdx = intent.getIntExtra(PlayerLauncher.EXTRA_SUBTITLE_INDEX, -99)
        video.setTrackPreference(
            audioIndex = audioIdx.takeIf { it != -99 },
            subtitleIndex = subIdx.takeIf { it != -99 },
        )
        val download = intent.getLongExtra(PlayerLauncher.EXTRA_DOWNLOAD, -1L).takeIf { it >= 0 }
            ?.let { id -> graph.downloads.entries.value.firstOrNull { it.downloadId == id } }
        if (download != null) video.startOffline(download) else video.start(
            itemId = itemId,
            type = intent.getStringExtra(PlayerLauncher.EXTRA_TYPE),
            seriesId = intent.getStringExtra(PlayerLauncher.EXTRA_SERIES),
            seasonId = intent.getStringExtra(PlayerLauncher.EXTRA_SEASON),
            startMs = intent.getLongExtra(PlayerLauncher.EXTRA_START_MS, 0L),
            resume = intent.getBooleanExtra(PlayerLauncher.EXTRA_RESUME, true),
            sourceId = intent.getStringExtra(PlayerLauncher.EXTRA_SOURCE),
        )
        return true
    }

    /** Ends the current title: media session first, then its core (libmpv allows one per process). */
    private fun close() {
        VideoPlaybackService.detach(this)
        session?.release()
        session = null
        // 用户口径（2026-11-23）：退出播放/切集时把所有缓存下载一起停掉。
        // 之前 PlaybackCache 用的是 object 级常驻协程，退出后下载还在跑，
        // 用户能看到「退出播放了网速还在持续跑」。
        PlaybackCache.stopAll()
    }

    /**
     * 用户口径（2026-11-23）OPPO Pad Mini / 平板专项 —— 这是让旋转真正生效的关键。
     *
     * 现象：只在这台平板上点旋转毫无反应，系统一直是竖屏；手机完全正常。
     * 根因（Android 16 官方行为变更）：宽 ≥600dp 的大屏设备上，
     * 系统**默认忽略**应用发来的 requestedOrientation 请求。
     * 所以 SenPlayer / AfuseKit 这些播放器在它上面同样转不了 —— 不是我们的锅，
     * 但我们可以主动申请「允许覆盖」。
     *
     * 官方 API（API 35+）：
     *   Activity.setShouldDismissRequestedOrientation(false)
     * 意思是「别丢弃我发的方向请求」。必须在 onCreate 里、任何方向请求之前调用。
     * API < 35 没有这个限制，直接跳过。
     */
    /**
     * 用户口径（2026-11-23）OPPO Pad Mini / 平板专项。
     *
     * 现象：只在这台平板上点旋转毫无反应，系统一直竖屏；手机完全正常。
     * 根因（Android 16 大屏行为变更）：宽 ≥600dp 的应用在大屏设备上，
     * 系统默认忽略 requestedOrientation。SenPlayer / AfuseKit 同样转不了。
     *
     * 可用的真实手段（按优先级，都不存在就跳过，最后还有画面旋转兜底）：
     *  ① Activity.requestedOrientation = USER_LANDSCAPE
     *     —— 比 SCREEN_ORIENTATION_LANDSCAPE 更"强"，部分 ROM 认这个
     *  ② activity.setRequestedOrientation 之后再手动触发一次
     *     resources.updateConfiguration 不可行，但可以 adjustResize + 重设布局
     *  ③ 都无效时由 PlayerScreen 的画面级 90° 旋转兜底
     */
    /**
     * 用户口径（2026-11-23）OPPO Pad Mini 旋转专项 —— **最终方案**。
     *
     * 事实（日志 + 已证实）：
     *  · 手机正常，这台平板点旋转毫无反应，SenPlayer/AfuseKit 同样。
     *  · Android 16(API 36) 起，≤600dp 以上的大屏**默认忽略** requestedOrientation。
     *  · 画⾯旋转（graphicsLayer 90°）走不通：SurfaceView 是独立硬件层，
     *    Compose 转不动它，实测把画面转成「竖排文字」。
     *
     * 所以只能让系统重新承认方向请求。三条手段，按可用性依次尝试：
     *  ① 清单里已声明 PROPERTY_COMPAT_ALLOW_ORIENTATION_OVERRIDE（首选，系统级）
     *  ② 运行时通过反射打开「应用兼容性变更」的同类开关（部分 ROM 需要）
     *  ③ 打开 Android 的「大屏多窗口自适应」兼容模式：
     *     resizeableActivity=false + 固定方向 → 系统把窗口当固定型，不再套用大屏策略
     *
     * 全部失败也不崩：最终仍靠 requestedOrientation 走传统路径（小屏设备本来就好使）。
     */
    private fun allowOrientationOverride() {
        // Activity 里读 resources 是当前实时值（非 Compose 组合缓存），这里没问题
        val smallest = resources.configuration.smallestScreenWidthDp
        android.util.Log.i(
            "ETKN-Rotate",
            "init smallestWidthDp=$smallest sdk=${Build.VERSION.SDK_INT} large=${smallest >= 600}",
        )
        if (smallest < 600) return   // 不是大屏设备，系统本来就尊重方向请求

        // 手段②：反射尝试系统兼容性开关（不同 ROM 方法名不一样，逐个试，全不中就算了）
        val tried = mutableListOf<String>()
        for ((cls, method, arg) in listOf(
            Triple("android.app.Activity", "setShouldDismissRequestedOrientation", false),
            Triple("android.app.Activity", "setIgnoreOrientationRequest", false),
            Triple("android.view.WindowManager", "setIgnoreOrientationRequest", false),
        )) {
            val ok = runCatching {
                val c = Class.forName(cls)
                val m = c.getDeclaredMethod(method, Boolean::class.javaPrimitiveType)
                m.isAccessible = true
                val target: Any? = when (cls) {
                    "android.app.Activity" -> this
                    "android.view.WindowManager" -> windowManager
                    else -> null
                }
                if (target != null) { m.invoke(target, arg); true } else false
            }.getOrDefault(false)
            tried += "$cls.$method=$ok"
        }
        android.util.Log.i("ETKN-Rotate", "compat-switches ${tried.joinToString()}")
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val size: VideoSize = session?.player?.videoSize ?: VideoSize.UNKNOWN
        val ratio = if (size.width > 0 && size.height > 0) {
            val r = size.width.toFloat() / size.height
            Rational(size.width, size.height).takeIf { r in 0.42f..2.38f } ?: Rational(16, 9)
        } else Rational(16, 9)
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(session?.player?.isPlaying == true)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) pipParams()?.let { setPictureInPictureParams(it) }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) pipParams()?.let { enterPictureInPictureMode(it) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && session?.player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        /**
         * 用户口径（2026-11-27，#125）**进入视频播放器时，音乐让出媒体键和系统卡片**。
         *
         * 用户原话：
         * ```
         * 「当切换到播放视频时候第一应该屏蔽蓝牙控制器对音乐的控制，可以控制视频暂停，
         *   现在我用蓝牙暂停视频暂停然后音乐就从暂停状态转为播放状态了」
         * 「播放视频时候应该把系统状态栏的那个音乐播放卡片停掉了，就是恢复到纯灰色的状态」
         * ```
         *
         * 音乐和视频各有一个 MediaSession，同时 active 时按键会串。
         * 这里在视频页进入前台时让音乐失活，媒体键就只会落到视频会话上，
         * 系统卡片也回到无播放的灰态。
         */
        xyz.etkn.mobile.music.MusicSessionHolder.yieldToVideo()
        if (wasPlaying) session?.player?.play()
    }

    override fun onPause() {
        super.onPause()
        // Release now, not in onDestroy: that can run after the next player activity has already
        // started its own core, and libmpv allows one instance per process.
        if (isFinishing) close()
    }

    override fun onStop() {
        super.onStop()
        /**
         * 用户口径（#125）**视频结束/离开后，把媒体键还给音乐**。
         *
         * 用户原话：「播放视频结束之后应该就把蓝牙对音乐的控制恢复了」
         *
         * 放在 onStop 而不是 onDestroy：PiP 和后台播放时页面也可能 stop，
         * 但那时视频会话自己还在管按键，恢复音乐由 onDestroy 负责更准确 ——
         * 见下面的 onDestroy。
         */
        val player = session?.player ?: return
        // With 后台播放 the sound goes on (the media notification controls it, so coming back
        // leaves it as it is); closing PiP or leaving the app otherwise pauses, and the progress
        // report goes out on pause.
        if (backgroundPlay && !inPip && !isFinishing) {
            wasPlaying = false
            return
        }
        wasPlaying = player.isPlaying
        player.pause()
    }

    override fun onDestroy() {
        close()
        /** 用户口径（#125）视频彻底退出，把媒体键还给音乐。 */
        xyz.etkn.mobile.music.MusicSessionHolder.resumeFromVideo()
        super.onDestroy()
    }
}
