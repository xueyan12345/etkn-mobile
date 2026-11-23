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
        super.onDestroy()
    }
}
