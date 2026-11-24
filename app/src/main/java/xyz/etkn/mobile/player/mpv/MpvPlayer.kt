package xyz.etkn.mobile.player.mpv

import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode
import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import xyz.etkn.mobile.R
import xyz.etkn.mobile.data.settings.DecoderPreference
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

/**
 * libmpv as a Media3 [Player], so the player screen, gestures, danmaku, PiP and the ETKN
 * reports work the same on either core.
 *
 * Video is decoded by MediaCodec (`mediacodec-copy`, software only when the device has no
 * decoder for the stream) and drawn by `gpu-next`, which also renders ASS/PGS subtitles.
 * libmpv is a process-wide singleton: only one instance may exist at a time.
 *
 * mpv calls back on its own event thread; every state change is moved to the main thread.
 * The clock is read from throttled `time-pos` notifications and extrapolated in between, so
 * UI and danmaku never block on the mpv core lock.
 */
class MpvPlayer(
    context: Context,
    private val userAgent: String,
    private val decoder: DecoderPreference,
    private val languages: List<String>,
) : SimpleBasePlayer(Looper.getMainLooper()), MPVLib.EventObserver {

    private val app = context.applicationContext
    private val locks = PlaybackLocks(app)
    private val main = Handler(Looper.getMainLooper())
    val view: MpvView = LayoutInflater.from(context).inflate(R.layout.mpv_view, null, false) as MpvView

    private var item: MediaItem? = null
    private var startMs = 0L
    private var prepared = false
    private var loaded = false
    private var restarted = false
    private var ended = false
    private var cacheWait = false
    private var playWhenReady = false
    /** Last pause state we asked mpv for, until its echo arrives; null = mpv may pause on its own. */
    private var requestedPause: Boolean? = null
    private var error: PlaybackException? = null
    private var durationMs = C.TIME_UNSET
    private var positionMs = 0L
    private var positionAt = 0L
    private var cachedMs = 0L
    @Volatile private var speed = 1f
    private var tracks = Tracks.EMPTY
    /** Track group id → (mpv track type, mpv track id). */
    private var mpvTracks = emptyMap<String, Pair<String, Int>>()
    @Volatile private var externals = emptyList<MediaItem.SubtitleConfiguration>()
    private var videoSize = VideoSize.UNKNOWN
    private var selection = TrackSelectionParameters.getDefaults(app)
    @Volatile private var released = false
    private var surfaceReady = false
    private var pendingLoad = false

    // Event-thread throttle for time-pos.
    private var postedPos = 0L
    private var postedAt = 0L
    @Volatile private var playing = false

    init {
        // libmpv is one per process: a player still alive (activity not yet destroyed) goes first.
        live?.takeIf { it !== this && !it.released }?.release()
        live = this
        MpvAssets.prepare(app)
        view.options = ::configure
        view.observed = OBSERVED
        MPVLib.addObserver(this)
        view.initialize(app.filesDir.path, app.cacheDir.path)
        // Registered after the view's own callback, which attaches the surface to mpv first.
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                if (pendingLoad && !released) {
                    pendingLoad = false
                    load()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
            }
        })
    }

    // ---------- Options ----------

    private fun configure() {
        val lowRam = app.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        val set = MPVLib::setOptionString
        set("profile", "fast")
        set("hwdec", when (decoder) {
            DecoderPreference.Auto -> "mediacodec-copy,no"
            DecoderPreference.Hardware -> "mediacodec-copy"
            DecoderPreference.Software -> "no"
        })
        set("hwdec-codecs", "h264,hevc,mpeg2video,vc1,vp8,vp9,av1")
        set("vd-lavc-dr", "yes")
        set("vd-lavc-film-grain", "gpu")
        set("keep-open", "yes")
        set("input-default-bindings", "no")
        set("save-position-on-quit", "no")
        set("user-agent", userAgent)
        set("network-timeout", "20")
        set("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=10")
        set("tls-verify", "yes")
        set("tls-ca-file", File(app.filesDir, "cacert.pem").path)
        // Start as soon as the first frames are decoded; refill quietly afterwards.
        set("cache", "yes")
        set("cache-pause-initial", "no")
        set("cache-pause-wait", "1")
        set("cache-secs", if (lowRam) "20" else "60")
        set("demuxer-max-bytes", if (lowRam) "48MiB" else "160MiB")
        set("demuxer-max-back-bytes", if (lowRam) "16MiB" else "48MiB")
        set("audio-channels", "auto-safe")
        val langs = languages.joinToString(",")
        set("alang", langs)
        set("slang", langs)
        set("sub-auto", "no")
        MpvAssets.fontsDir?.let { set("sub-fonts-dir", it) }
        MpvAssets.fontFamily?.let { set("sub-font", it) }
        set("msg-level", "all=warn")
    }

    // ---------- Player state ----------

    override fun getState(): State {
        val current = item
        val state = when {
            current == null || error != null || !prepared -> Player.STATE_IDLE
            ended -> Player.STATE_ENDED
            !loaded || !restarted || cacheWait -> Player.STATE_BUFFERING
            else -> Player.STATE_READY
        }
        playing = playWhenReady && state == Player.STATE_READY
        locks.hold(playing)
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state)
            .setIsLoading(state == Player.STATE_BUFFERING)
            .setPlayerError(error)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setTrackSelectionParameters(selection)
            .setVideoSize(videoSize)
        if (current == null) return builder.build()
        val position = positionNow()
        builder
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(current.mediaId.ifEmpty { "mpv" })
                        .setMediaItem(current)
                        .setDurationUs(if (durationMs > 0) Util.msToUs(durationMs) else C.TIME_UNSET)
                        .setIsSeekable(true)
                        .setTracks(tracks)
                        .build(),
                ),
            )
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(if (playing) PositionSupplier.getExtrapolating(position, speed) else PositionSupplier.getConstant(position))
            .setContentBufferedPositionMs(PositionSupplier.getConstant(maxOf(position, cachedMs)))
        return builder.build()
    }

    private fun positionNow(): Long {
        if (!playing) return positionMs
        val moved = ((SystemClock.elapsedRealtime() - positionAt) * speed).toLong()
        val now = positionMs + moved
        return if (durationMs > 0) now.coerceAtMost(durationMs) else now
    }

    // ---------- Commands ----------

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        item = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
        startMs = startPositionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        positionMs = startMs
        positionAt = SystemClock.elapsedRealtime()
        externals = item?.localConfiguration?.subtitleConfigurations.orEmpty()
        tracks = Tracks.EMPTY
        mpvTracks = emptyMap()
        durationMs = C.TIME_UNSET
        if (prepared) load()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (item != null) {
            if (error != null) startMs = positionMs
            prepared = true
            load()
        }
        return Futures.immediateVoidFuture()
    }

    /** Opens the item once the surface exists (a video output without one would drop the video). */
    private fun load() {
        val uri = item?.localConfiguration?.uri ?: return
        error = null
        loaded = false
        restarted = false
        ended = false
        cacheWait = false
        if (!surfaceReady) {
            pendingLoad = true
            return
        }
        MPVLib.setPropertyString("start", if (startMs > 0) seconds(startMs) else "none")
        /**
         * 用户口径（2026-11-26）**HDR→SDR tone-map（修「平板播 HDR10 片源特别暗」）**。
         *
         * 背景（日志实证）：走马上任那一集是 HDR10 ——
         *   color=BT2020/Limited range/ST2084 PQ/10/10
         * 手机播正常，是因为手机侧的渲染路径会做 HDR→SDR 转换；
         * 平板为了支持画布旋转走 TextureView，HDR 元数据在那一层丢了，
         * PQ 的编码值被当成 SDR 直接显示 → 整体发暗发灰。
         *
         * 这里给 mpv 显式开启 tone-map（mpv 自带完整实现）：
         *  · target-trc=srgb / target-prim=bt.709 → 输出 SDR
         *  · tone-mapping=auto → 按片源自动选曲线
         *  · hdr-compute-peak=yes → 逐帧估峰值，暗部不会压死
         * 这几项对 SDR 片源无副作用（mpv 不做转换），所以可以无条件设。
         */
        /**
         * 用户口径（2026-11-26）**杜比视界 profile 5 必须让 mpv 按 DV 解析**。
         *
         * 谷雨 = dvhe.05.08，日志实证 color=NA/NA/NA ——
         * 文件头里没写色彩信息，颜色全靠 DV 元数据驱动（IPTPQc2 色彩空间）。
         *
         * mpv 默认可能把它当普通 HEVC（按 BT709 解释）→ 必然偏绿。
         * 这里显式告诉 mpv：这是杜比视界，按它的原生色彩空间处理再转 SDR。
         */
        /**
         * 用户口径（2026-11-26）**不要手工强设 video-params-***。
         *
         * 上一版我强行设了 `vf=format=fmt=gbrpf32le` 和
         * `video-params-primaries/gamma`，结果反而更糟 ——
         * 这些是「告诉 mpv 输入是什么格式」的参数，
         * 设错等于骗它，颜色必然错（谷雨用 mpv 也偏色就是这个原因）。
         *
         * 正确做法：让 mpv 自己从码流/容器里读色彩信息。
         * mpv 内置了 libplacebo，对杜比视界（含 profile 5 的 IPTPQc2）
         * 有完整支持，只要不干扰它，它自己会走对路径。
         *
         * 这里只保留一个「不猜、按码流来」的语义。
         */
        /**
         * 用户口径（2026-11-26）**不要给 mpv 设 video-params-***。
         *
         * 日志铁证（1.4.8，谷雨那次）：
         *   mpv_set_property(video-params-primaries) returned error property not found
         *   mpv_set_property(video-params-gamma)     returned error property not found
         * → 这两个属性 mpv 根本不存在，设了完全无效（上一版整个方案是空操作）。
         *
         * 正确做法：交给 mpv 自己从码流读色彩（它对 DV/IPTPQc2 有原生支持）。
         * 只在它确实需要提示时，用**真实存在**的属性。
         */
        if (isDolbyVisionSource) {
            runCatching {
                /**
                 * dolbyvision 相关的真实属性（Android/libplacebo 后端）：
                 *  · tone-mapping 已在下面统一设置
                 *  · 这里只需要确保 DV 元数据不被丢弃
                 */
                MPVLib.setPropertyBoolean("vd-lavc-dr", true)
                android.util.Log.i("ETKN-Mpv", "DV 源 → 交给 mpv 原生 DV 处理（不设不存在的属性）")
            }
        }
        runCatching {
            MPVLib.setPropertyString("target-trc", "srgb")
            MPVLib.setPropertyString("target-prim", "bt.709")
            MPVLib.setPropertyString("tone-mapping", "auto")
            MPVLib.setPropertyBoolean("hdr-compute-peak", true)
            /**
             * 用户口径（2026-11-26）**修「杜比视界片源偏绿」**。
             *
             * 谷雨是杜比视界 profile 5（dvhe.05.08），日志实证 color=NA/NA/NA ——
             * 这类片源的色彩信息在文件头里故意留空，颜色只能靠杜比元数据（RPU）驱动，
             * 解码输出的是杜比专用色彩空间 IPTPQc2。
             *
             * 如果解码器/渲染链没认出这是 DV，就会把 IPTPQc2 当普通 BT709 解释，
             * 典型症状就是整体偏绿（绿色通道被误读）。
             *
             * 这里显式告诉 mpv：
             *  · 动态度量元数据要读（ Dulby Vision RPU）
             *  · 遇到 DV 时按它的原生色彩空间解释，再转到 SDR
             *  · 关闭「按容器猜测色彩」的错误路径
             */
            /**
             * 只保留 Android/libplacebo 上真正生效的项：
             *  · target-colorspace-hint：让渲染器按输出设备的能力做色域映射
             *  · video-output-levels=auto：亮度范围自动（Limited/Full 不搞错）
             * （d3d11-* 是 Windows 专用，Android 上无效，不要写）
             */
            MPVLib.setPropertyBoolean("target-colorspace-hint", true)
            MPVLib.setPropertyString("video-output-levels", "auto")
        }
        requestPause(!playWhenReady)
        MPVLib.command("loadfile", uri.toString(), "replace")
        Log.i(TAG, "load host=${uri.host} start=${startMs / 1000}s hwdec=${decoder.name}")
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        positionMs = positionNow()
        positionAt = SystemClock.elapsedRealtime()
        if (prepared) requestPause(!playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val target = positionMs.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        this.positionMs = target
        positionAt = SystemClock.elapsedRealtime()
        if (!loaded) {
            startMs = target
        } else {
            ended = false
            restarted = false
            MPVLib.command("seek", seconds(target), "absolute")
        }
        return Futures.immediateVoidFuture()
    }

    private fun requestPause(pause: Boolean) {
        // No echo comes when the value does not change.
        requestedPause = if (MPVLib.getPropertyBoolean("pause") == pause) null else pause
        MPVLib.setPropertyBoolean("pause", pause)
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        positionMs = positionNow()
        positionAt = SystemClock.elapsedRealtime()
        speed = playbackParameters.speed
        MPVLib.setPropertyDouble("speed", playbackParameters.speed.toDouble())
        return Futures.immediateVoidFuture()
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        selection = trackSelectionParameters
        applySelection()
        return Futures.immediateVoidFuture()
    }

    private fun applySelection() {
        if (!loaded) return
        for ((group, override) in selection.overrides) {
            val (type, id) = mpvTracks[group.id] ?: continue
            if (override.type == C.TRACK_TYPE_AUDIO && type == "audio") MPVLib.setPropertyString("aid", id.toString())
            if (override.type == C.TRACK_TYPE_TEXT && type == "sub") MPVLib.setPropertyString("sid", id.toString())
        }
        if (C.TRACK_TYPE_TEXT in selection.disabledTrackTypes) MPVLib.setPropertyString("sid", "no")
    }

    override fun handleStop(): ListenableFuture<*> {
        prepared = false
        loaded = false
        MPVLib.command("stop")
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        locks.hold(false)
        if (live === this) live = null
        // Silence first, so nothing can keep playing even if the teardown below fails.
        runCatching {
            MPVLib.setPropertyBoolean("pause", true)
            MPVLib.command("stop")
        }.onFailure { Log.e(TAG, "mpv stop", it) }
        MPVLib.removeObserver(this)
        // The view must stop touching mpv before the core goes.
        runCatching { view.holder.removeCallback(view) }
        runCatching { MPVLib.destroy() }.onFailure { Log.e(TAG, "mpv destroy", it) }
        Log.i(TAG, "released")
        return Futures.immediateVoidFuture()
    }

    /** Index into fit / crop-fill / stretch, matching the ExoPlayer resize modes. */
    /**
     * 用户口径（2026-11-26）**让 mpv 自己把画面转 90°**。
     *
     * 为什么需要：这台平板的系统不接受横屏请求（日志实证 round=1/2/3 want=L real=P ok=false），
     * 唯一的横屏手段是外层画布 rotationZ=90。
     * 但 mpv 的画面走 SurfaceView（独立硬件图层），**外层旋转对它无效** ——
     * 于是「画面正但界面躺」或者「界面转了画面不转」。
     *
     * 解决办法：把旋转角交给 mpv 自己（video-rotate），
     * 它会在渲染时把画面转过来，不受 View 树变换限制。
     *
     * @param degrees 0 / 90 / 180 / 270
     */
    /**
     * 用户口径（2026-11-26）：当前片源是否杜比视界（决定 load 时的色彩解析方式）。
     */
    var isDolbyVisionSource: Boolean = false

    fun setVideoRotate(degrees: Int) {
        runCatching {
            MPVLib.setPropertyString("video-rotate", (degrees % 360).toString())
            android.util.Log.i("ETKN-Mpv", "video-rotate=$degrees")
        }
    }

    fun setResize(mode: Int) {
        if (released) return
        MPVLib.setPropertyString("keepaspect", if (mode == 2) "no" else "yes")
        MPVLib.setPropertyDouble("panscan", if (mode == 1) 1.0 else 0.0)
    }

    /** Shows subtitles [ms] later (negative = earlier). */
    fun setSubtitleDelay(ms: Int) {
        if (released) return
        MPVLib.setPropertyDouble("sub-delay", ms / 1000.0)
    }

    // ---------- mpv callbacks (event thread) ----------

    private inline fun onMain(crossinline block: () -> Unit) {
        main.post {
            if (released) return@post
            runCatching { block() }.onFailure { Log.e(TAG, "mpv event", it) }
            invalidateState()
        }
    }

    override fun eventProperty(property: String) {
        if (property == "track-list") readTracks()
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "demuxer-cache-time" -> onMain { cachedMs = value * 1000 }
            "dwidth", "dheight" -> {
                val w = MPVLib.getPropertyInt("dwidth") ?: 0
                val h = MPVLib.getPropertyInt("dheight") ?: 0
                if (w > 0 && h > 0) onMain { videoSize = VideoSize(w, h) }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> onMain {
                val requested = requestedPause
                when {
                    // Echo of our own request (older echoes may still be in flight).
                    requested != null -> if (requested == value) requestedPause = null
                    // mpv paused or resumed by itself (end of file with keep-open).
                    prepared && playWhenReady == value -> {
                        positionMs = positionNow()
                        positionAt = SystemClock.elapsedRealtime()
                        playWhenReady = !value
                    }
                }
            }
            "paused-for-cache" -> onMain { cacheWait = value }
            "eof-reached" -> onMain { if (loaded) ended = value }
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "aid" || property == "sid") readTracks()
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                val ms = (value * 1000).toLong()
                val now = SystemClock.elapsedRealtime()
                val expected = postedPos + if (playing) ((now - postedAt) * speed).toLong() else 0
                if (now - postedAt >= 1_000 || abs(ms - expected) > 1_200) {
                    postedPos = ms
                    postedAt = now
                    onMain {
                        positionMs = ms
                        positionAt = now
                    }
                }
            }
            "duration" -> onMain { durationMs = (value * 1000).toLong() }
            "speed" -> onMain { speed = value.toFloat() }
        }
    }

    override fun eventProperty(property: String, value: MPVNode) = Unit

    override fun event(eventId: Int, data: MPVNode) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                MPVLib.setPropertyString("start", "none")
                for (sub in externals) {
                    MPVLib.command("sub-add", sub.uri.toString(), "auto", sub.label ?: "", sub.language ?: "")
                }
                onMain {
                    loaded = true
                    applySelection()
                }
                readTracks()
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                val at = MPVLib.getPropertyDouble("time-pos")
                val now = SystemClock.elapsedRealtime()
                onMain {
                    restarted = true
                    if (at != null) {
                        positionMs = (at * 1000).toLong()
                        positionAt = now
                    }
                }
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                val reason = runCatching { data.asMap()?.get("reason")?.asString() }.getOrNull()
                val code = runCatching { data.asMap()?.get("error")?.asString() }.getOrNull()
                if (reason == "error" || reason == "4") {
                    Log.e(TAG, "end-file error ${code.orEmpty()}")
                    onMain {
                        error = PlaybackException(
                            "mpv: ${code ?: "error"}",
                            IOException("mpv 无法播放此视频（${code ?: "未知错误"}）"),
                            if (loaded) PlaybackException.ERROR_CODE_DECODING_FAILED else PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        )
                    }
                }
            }
        }
    }

    /** Rebuilds Media3 [Tracks] from `track-list` (event thread; mpv answers without the UI waiting). */
    private fun readTracks() {
        if (released) return
        val count = MPVLib.getPropertyInt("track-list/count") ?: 0
        val subs = externals
        val groups = ArrayList<Tracks.Group>(count)
        val ids = HashMap<String, Pair<String, Int>>(count)
        for (i in 0 until count) {
            val base = "track-list/$i/"
            val type = MPVLib.getPropertyString(base + "type") ?: continue
            val id = MPVLib.getPropertyInt(base + "id") ?: continue
            val codec = MPVLib.getPropertyString(base + "codec").orEmpty()
            val external = MPVLib.getPropertyBoolean(base + "external") == true
            val file = if (external) MPVLib.getPropertyString(base + "external-filename") else null
            val config = file?.let { f -> subs.firstOrNull { it.uri.toString() == f } }
            val formatId = config?.id ?: "mpv-$type-$id"
            val builder = Format.Builder()
                .setId(formatId)
                .setSampleMimeType(mime(type, codec))
                .setCodecs(codec.ifEmpty { null })
                .setLanguage(config?.language ?: MPVLib.getPropertyString(base + "lang"))
                .setLabel(config?.label ?: MPVLib.getPropertyString(base + "title"))
            when (type) {
                "audio" -> builder.setChannelCount(MPVLib.getPropertyInt(base + "demux-channel-count") ?: Format.NO_VALUE)
                "video" -> {
                    builder.setWidth(MPVLib.getPropertyInt(base + "demux-w") ?: Format.NO_VALUE)
                    builder.setHeight(MPVLib.getPropertyInt(base + "demux-h") ?: Format.NO_VALUE)
                }
            }
            val group = TrackGroup(formatId, builder.build())
            val selected = MPVLib.getPropertyBoolean(base + "selected") == true
            groups += Tracks.Group(group, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
            ids[formatId] = type to id
        }
        val built = Tracks(groups)
        onMain {
            tracks = built
            mpvTracks = ids
        }
    }

    private fun mime(type: String, codec: String): String = when (type) {
        "video" -> when (codec) {
            "hevc" -> MimeTypes.VIDEO_H265
            "h264" -> MimeTypes.VIDEO_H264
            "av1" -> MimeTypes.VIDEO_AV1
            "vp9" -> MimeTypes.VIDEO_VP9
            else -> "video/x-$codec"
        }
        "audio" -> when (codec) {
            "ac3" -> MimeTypes.AUDIO_AC3
            "eac3" -> MimeTypes.AUDIO_E_AC3
            "truehd", "mlp" -> MimeTypes.AUDIO_TRUEHD
            "dts" -> MimeTypes.AUDIO_DTS
            "aac" -> MimeTypes.AUDIO_AAC
            "flac" -> MimeTypes.AUDIO_FLAC
            "opus" -> MimeTypes.AUDIO_OPUS
            "mp3" -> MimeTypes.AUDIO_MPEG
            else -> "audio/x-$codec"
        }
        else -> when (codec) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "subrip" -> MimeTypes.APPLICATION_SUBRIP
            "webvtt" -> MimeTypes.TEXT_VTT
            "hdmv_pgs_subtitle" -> MimeTypes.APPLICATION_PGS
            else -> "text/x-$codec"
        }
    }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.3f", ms / 1000.0)

    companion object {
        private const val TAG = "ETKN-Mpv"

        /** Main thread only. */
        private var live: MpvPlayer? = null

        private val OBSERVED = listOf(
            "time-pos" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "speed" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "demuxer-cache-time" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "dwidth" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "dheight" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "aid" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "sid" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "track-list" to MPVLib.MpvFormat.MPV_FORMAT_NONE,
        )

        /**
         * Every command a `handle…` override serves. [SimpleBasePlayer] silently ignores a call
         * whose command is missing: without [Player.COMMAND_RELEASE], `release()` never reached
         * [handleRelease], libmpv was never destroyed, and the next player's `MPVLib.create`
         * ended the process ("mpv is already initialized").
         */
        private val COMMANDS = Player.Commands.Builder().addAll(
            Player.COMMAND_RELEASE,
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_BACK,
            Player.COMMAND_SEEK_FORWARD,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_TRACKS,
            Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS,
            Player.COMMAND_GET_METADATA,
        ).build()
    }
}
