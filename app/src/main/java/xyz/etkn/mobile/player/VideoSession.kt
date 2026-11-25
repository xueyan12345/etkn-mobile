package xyz.etkn.mobile.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.etkn.mobile.AppGraph
import xyz.etkn.mobile.data.api.EtknApi
import xyz.etkn.mobile.data.cast.CastDevice
import xyz.etkn.mobile.data.downloads.DownloadEntry
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.MediaSource
import xyz.etkn.mobile.data.model.MediaStream
import xyz.etkn.mobile.data.model.TICKS_PER_MS
import xyz.etkn.mobile.data.net.bool
import xyz.etkn.mobile.data.net.int
import xyz.etkn.mobile.data.net.obj
import xyz.etkn.mobile.data.session.ActiveSession
import xyz.etkn.mobile.data.settings.DanmakuStyle
import xyz.etkn.mobile.data.settings.PlayerEngine
import xyz.etkn.mobile.data.settings.Settings
import xyz.etkn.mobile.data.settings.SkipMode
import xyz.etkn.mobile.player.bluray.BlurayMediaSourceFactory
import xyz.etkn.mobile.player.bluray.BlurayTag
import xyz.etkn.mobile.player.bluray.DiscHint
import xyz.etkn.mobile.player.danmaku.DanmakuCue
import xyz.etkn.mobile.player.danmaku.DanmakuFeed
import xyz.etkn.mobile.player.mpv.MpvPlayer
import xyz.etkn.mobile.ui.common.userMessage
import java.util.Locale

@Immutable
data class TrackOption(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean, val stream: MediaStream? = null)

/** 定时关闭: after [minutes], or at the end of the current title (minutes = 0). */
enum class SleepTimer(val label: String, val minutes: Int) {
    EndOfItem("本集播完", 0), M15("15 分钟", 15), M30("30 分钟", 30), M45("45 分钟", 45), M60("60 分钟", 60), M90("90 分钟", 90),
}

/** Calibration of the shown subtitle: [ms] in effect, [saved] on the server; only the mpv core can shift. */
@Immutable
data class SubtitleOffset(val key: String, val ms: Int, val saved: Int)

@Immutable
data class SkipSegment(val kind: Kind, val startMs: Long, val endMs: Long) {
    enum class Kind { Intro, Credits }
}

@Immutable
data class VideoUi(
    val title: String = "",
    val subtitle: String = "",
    val item: Item? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val audio: List<TrackOption> = emptyList(),
    val text: List<TrackOption> = emptyList(),
    val textEnabled: Boolean = true,
    val sources: List<MediaSource> = emptyList(),
    val sourceIndex: Int = 0,
    val next: Item? = null,
    val segments: List<SkipSegment> = emptyList(),
    val speed: Float = 1f,
    val skipMode: SkipMode = SkipMode.Button,
    val finished: Boolean = false,
    /** Server advertised danmaku for this playback (button shown). */
    val danmakuSupported: Boolean = false,
    /** A feed is running and the overlay should draw. */
    val danmakuOn: Boolean = false,
    val notice: String? = null,
    /** Seek thumbnails of the playing version, when the server has extracted them. */
    val previews: SeekPreviews? = null,
    /** Offset controls for the selected subtitle; null when it cannot be shifted. */
    val subtitleOffset: SubtitleOffset? = null,
    val sleep: SleepTimer? = null,
    /** Name of the DLNA renderer this title was handed to. */
    val castingTo: String? = null,
    /** 用户口径（2026-11-23）：进度条要显示已缓冲区间。 */
    val bufferedPosition: Long = 0L,
    /** 用户口径（2026-11-23）：下一集已经预加载到本地（控制「下一集」按钮上的闪电小圆）。 */
    val nextPreloaded: Boolean = false,
    /** 用户口径（2026-11-23）：磁盘缓存已覆盖到的位置（毫秒），驱动进度条上的缓冲段。 */
    val cachedPosition: Long = 0L,
)

private const val TAG = "ETKN-Play"

/**
 * 用户口径（2026-11-27）**剩余多少时长就开始预加载下一集**。
 *
 * 5 分钟：足够铺好下一集「片头 + 1 分钟」，又不会太早去抢当前集带宽。
 * 这是「缓冲条到不了片尾」时的兜底 —— 保证临到片尾一定已经开始铺，
 * 不用用户把进度条拉到最末尾才触发。
 */
private const val PRELOAD_LEAD_MS = 5 * 60 * 1000L
private const val MAX_OFFSET_MS = 600_000

/** The two video cores. */
enum class Engine(val label: String) { Exo("ExoPlayer"), Mpv("mpv") }

/**
 * Owns the video core for the player screen and talks to ETKN:
 * PlaybackInfo → direct stream → Playing / Progress (every 10 s and on state changes) / Stopped.
 * Reports run on the application scope so the final Stopped survives the activity; everything
 * else runs in the session's own scope (a child of [parent]), which [release] cancels.
 *
 * The core is chosen per source ([engineFor]): discs and HDR / Dolby Vision play on ExoPlayer,
 * which hands HDR straight to the display; everything else on mpv. A core that fails switches
 * to the other once, at the same position. Both are Media3 [Player]s, so the rest is shared.
 */
class VideoSession(
    private val context: Context,
    private val graph: AppGraph,
    parent: CoroutineScope,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val api = graph.api
    private val _player = MutableStateFlow<Player?>(null)
    /** Current core; null until the first source is known. */
    val players: StateFlow<Player?> = _player.asStateFlow()
    val player: Player? get() = _player.value
    private var engine: Engine? = null
    /**
     * 用户口径（2026-11-23）修「设置里的播放内核/解码不生效」：
     *
     * 之前这里是 `private val settings: Settings`，在 init 里
     * `runBlocking { graph.settings.flow.first() }` 读一次就定死 ——
     * 相当于播放器一创建就把当时的设置拍了个快照。
     * 于是「在设置里换内核/换解码」对正在播的这一集完全无效，
     * 甚至换集也不会重读，用户看到的永远是进播放器那一刻的旧值。
     *
     * 现在改成每次用时现读（DataStore 的 flow 里取当前值），
     * 设置一改立刻生效，不用退出播放器。
     */
    private val settings: Settings
        get() = kotlinx.coroutines.runBlocking {
            runCatching { graph.settings.flow.first() }.getOrDefault(Settings())
        }
    private val session: ActiveSession = graph.sessions.require()

    private val _ui = MutableStateFlow(VideoUi())
    val ui: StateFlow<VideoUi> = _ui.asStateFlow()

    private var item: Item? = null
    private var source: MediaSource? = null
    private var discTried = false
    /** Set after a core failed on the current source; the other core plays it. */
    private var forcedEngine: Engine? = null

    /**
     * 用户口径（2026-11-26）**「本次播放用户手动选过内核」标记**。
     *
     * 修的问题：「手动切 Exo 后 2 秒被 HDR 自动逻辑拉回 mpv」
     * 日志实证（走马上任）：12:16:53 手动→Exo，12:16:55 自动→mpv，
     * 12:16:57 手动→Exo……用户看到的就是「点了没反应」。
     *
     * 一旦用户点过按钮，本次播放内所有自动切换全部停止，
     * 直到换集（playSource 新 item）或退出播放器才重置。
     */
    private var userPickedEngineThisPlayback = false

    /**
     * 用户口径（2026-11-26）**每次起播只自动决策一次内核**。
     *
     * 防止 onTracksChanged 被多次回调时反复切核（切一次就够，
     * 反复切会让画面一直重新起播）。
     */
    private var autoPickDone = false

    /**
     * 用户口径（2026-11-26）：下次创建的 mpv 是否是杜比视界源。
     * mpv 必须在 load 之前知道这件事，才能按 IPTPQc2 解析颜色。
     */
    private var pendingDvSource = false

    /**
     * 用户口径（2026-11-26）**设置里的内核选择优先级最高**。
     *
     * 实测问题：设置改成 Exo / mpv 后「完全没感觉」——因为
     *  ① engineFor 只在起播/换集时读设置，正在播的这一集不会重选
     *  ② forcedEngine（自动换核）一旦被设上会一直覆盖设置
     *  ③ 新加的「DV5 自动切 mpv」「无解码器自动切 mpv」会把手动选的 Exo 盖掉
     *
     * 现在的规则：
     *  · 设置 = Exo  → 永远 Exo，禁止任何自动换核
     *  · 设置 = Mpv  → 永远 mpv
     *  · 设置 = 自动 → 才允许「不支持的解码 / DV profile5」时自动切 mpv
     */
    private val autoSwitchAllowed: Boolean
        get() = settings.engine == PlayerEngine.Auto

    /**
     * 用户口径（2026-11-26）：把「播放内核」设置暴露成 Flow，供播放页监听。
     * 设置一变就能重选内核，不用退出播放器。
     */
    fun settingsEngineFlow(): kotlinx.coroutines.flow.Flow<PlayerEngine> =
        runCatching {
            graph.settings.flow.map { it.engine }
        }.getOrElse { kotlinx.coroutines.flow.flowOf(PlayerEngine.Auto) }

    private var loadStartedAt = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** Tracks of the current source as last reported (the player clears them on an error). */
    private var lastTracks: Tracks = Tracks.EMPTY
    /** Formats whose decoder failed on this source; never offered again. */
    private val failedFormats = HashSet<String>()
    /** Track types whose server default is still to be applied to the current media. */
    private var pendingDefaults = emptySet<Int>()
    private var playSession = ""
    private var started = false
    private var stopped = true
    private var completedBySkip = false
    private var released = false
    /** Playing a downloaded file: nothing is asked of or reported to the server. */
    private var offline = false
    private var progressJob: Job? = null
    private var loadJob: Job? = null
    private var previewJob: Job? = null
    private var previewKey = ""
    /** 当前集的实际播放地址（含 token，仅用于缓存下载）。 */
    private var currentUrl: String? = null
    /** 当前集资源总字节数（-1 = 未知）。 */
    private var currentLength: Long = -1L
    /**
     * 用户口径（2026-11-27）**服务端给的时长（毫秒），Exo 给不出 duration 时用它兜底**。
     *
     * 日志实证：strm 源播放时 Exo 一直返回 duration=0，
     * 导致缓存阈值、缓冲条刻度、预加载触发全部失效。
     * 而服务端的 MediaSource.runTimeTicks 里一直有这个值。
     */
    private var serverDurationMs: Long = 0L
    /** 下一集资源总字节数（-1 = 未知，预加载时现场探）。 */
    private var nextLength: Long = -1L
    /** 下一集的 (episodeId, url)，用于流水线预加载。 */
    private var nextUrl: Pair<String, String>? = null
    /**
     * 用户口径（2026-11-24）：下一集的片头结束位置与总时长。
     * 预加载量要用「片头结束 + 1 分钟」做下限（不受配额限制），
     * 没有片头数据时 introEndMs = -1，退回纯配额。
     */
    private var nextIntroEndMs: Long = -1L
    /** 正在取下一集直链（避免 tick 每 5 秒重复发起）。 */
    private var nextFetching = false

    /** 缓存占用是否已记录（避免每 tick 刷屏）。 */
    private var cacheUsageLogged = false

    /** tickCache 是否已经打过首轮日志（避免刷屏）。 */
    private var tickLogged = false

    /**
     * 用户口径（2026-11-26）**当前源是否 Dolby Vision（DV profile 5 等）**。
     *
     * 平板日志实证：
     *  · 正常集：mimeType=video/hevc, codecs=hvc1.2.4.L150.90 → TextureView 正常出画
     *  · 黑屏集：mimeType=video/dolby-vision, codecs=dvhe.05.08, color=NA/NA/NA
     *            解码器 c2.qti.dv.decoder 初始化成功、进度照走，但画面出不来
     *
     * 结论：TextureView（画布旋转的必要条件）与 DV profile 5 在这台高通车机上
     * 渲染路径不兼容。遇到 DV 必须换回 SurfaceView ——
     * 代价是不能再画布旋转，但至少画面能出来（这类片源本身就是宽画幅）。
     */
    /**
     * 用户口径（2026-11-26）**DV profile 5 单独标记**。
     *
     * 谷雨 = dvhe.05.08 = profile 5（单层、无 HDR10 基础层、纯 DV）。
     * 这类片源的 SPS/VUI 里**故意不写色彩信息**（规范如此），
     * 日志实证 color=NA/NA/NA/8/8。
     * 颜色完全由 DV RPU 驱动，解码器输出 IPTPQc2 色彩空间。
     *
     * 关键：IPTPQc2 一旦被当成普通 BT709 解释 → 画面偏绿。
     * 平板上为了画布旋转走 TextureView（GPU 纹理上传），
     * 元数据在这一层丢失 → 偏绿。
     *
     * 所以 profile 5 必须走「Surface 直通」，不能用 TextureView 中间层。
     */
    private val _isDvProfile5 = MutableStateFlow(false)
    val isDvProfile5: StateFlow<Boolean> = _isDvProfile5.asStateFlow()

    private val _isDolbyVision = MutableStateFlow(false)

    /** 供 UI 读取：当前源是否为 DV（决定用 SurfaceView 还是 TextureView）。 */
    val isDolbyVision: StateFlow<Boolean> = _isDolbyVision.asStateFlow()

    /**
     * 用户口径（2026-11-26）**当前源是否为 HDR（HDR10 / HLG / PQ）**。
     *
     * 日志实证：走马上任那一集
     *   color=BT2020/Limited range/ST2084 PQ/10/10
     * 这是 HDR10（PQ 曲线，0-10000 nit）。
     * 在平板上走 TextureView 时 HDR 元数据丢失 → 按 SDR 解释 → 整体发暗发灰。
     * 手机正常是因为渲染路径不同，系统做了转换。
     *
     * 处理：HDR 片源切到 mpv 引擎（mpv 自带 tone-map，见 MpvPlayer.load）。
     */
    private val _isHdr = MutableStateFlow(false)
    val isHdr: StateFlow<Boolean> = _isHdr.asStateFlow()
    private var nextDurationMs: Long = 0L
    private var autoSkippedIntro = false
    private var autoSkippedCredits = false

    private val _danmakuStyle = MutableStateFlow(DanmakuStyle())
    val danmakuStyle: StateFlow<DanmakuStyle> = _danmakuStyle.asStateFlow()
    private val _danmakuCues = MutableStateFlow<List<DanmakuCue>>(emptyList())
    val danmakuCues: StateFlow<List<DanmakuCue>> = _danmakuCues.asStateFlow()
    /** Last server answer: null = not advertised, false = service off, true = available. */
    private var danmakuAvailable: Boolean? = null
    private var danmakuFeed: DanmakuFeed? = null

    init {
        _danmakuStyle.value = settings.danmaku
        _ui.update { it.copy(speed = settings.defaultSpeed, skipMode = settings.skipMode) }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    _ui.update { it.copy(loading = false, error = null) }
                    if (!started) {
                        started = true
                        Log.i(TAG, "ready on ${engine?.label} after ${SystemClock.elapsedRealtime() - loadStartedAt} ms")
                        report("")
                    }
                }
                Player.STATE_BUFFERING -> _ui.update { it.copy(loading = true) }
                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (started) report("Progress")
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (!tracks.isEmpty) lastTracks = tracks
            /**
             * 用户口径（2026-11-26）：识别 Dolby Vision 片源。
             * 判据（两者任一成立即算 DV）：
             *  · MIME 为 video/dolby-vision
             *  · codec 字符串以 dvhe / dvh1 开头（DV profile 5/8 都在此列）
             */
            runCatching {
                /**
                 * 用户口径（2026-11-26）**按片源类型自动选内核（这是「自动」模式的完整规则）**。
                 *
                 * 需求原文：
                 *  · 「谷雨好像只有Exo不偏色，MPV偏色」
                 *  · 「《走马上任》，这两个内核，有一个暗，有一个不暗，
                 *     我觉得这个应该也自动模式就能自动判定」
                 *  · 「我设置里选的是自动啊，它不应该自动给我判定这个剧用哪个好用吗」
                 *
                 * 三条规则（按优先级，只判断一次就定下来）：
                 *  ① 杜比视界 profile 5（dvhe.05 / dvh1.05）→ **Exo**
                 *     mpv 对这类片源色彩转换不正确（实测：Exo 颜色对、mpv 偏绿）
                 *  ② HDR10 / HLG / PQ（非 DV）→ **mpv**
                 *     走 Exo 时平板纹理视图丢 HDR 元数据 → 亮度被当 SDR → 发暗
                 *     mpv 自带 tone-map → 亮度正常
                 *  ③ 其余（SDR）→ **Exo**（性能最好，且没有色彩问题）
                 *
                 * 生效条件（严格遵守）：
                 *  · 设置必须是「自动」——手动选了 Exo 或 mpv 就完全不动
                 *  · 本次播放没有手动切换过内核 —— 用户点过按钮就尊重他的选择
                 *  · 每次起播只决策一次（recordedAutoPick 防重入）
                 */
                /**
                 * 用户口径（2026-11-27）**取视频格式时不要按 mime 过滤**。
                 *
                 * 修的 bug：原来写的是
                 *   firstOrNull { g.getTrackFormat(it).sampleMimeType != null }
                 * 也就是「只认 mime 非空的轨道」。而 msmpeg4v2 这类老编码，
                 * Exo 的提取器有时给不出 mime（日志实证：第一次 codec=msmpeg4v2，
                 * 第二次同一集变成 codec= 空）→ videoFormat=null → 判据全失效。
                 *
                 * 现在：只要这个轨道组有内容，就拿第 0 个格式，
                 * 不管 mime 有没有值。空 mime 本身就是一个「解不了」的信号。
                 */
                val videoFormat = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
                    ?.let { g -> runCatching { g.getTrackFormat(0) }.getOrNull() }
                val codec = videoFormat?.codecs.orEmpty().lowercase()
                val mime = videoFormat?.sampleMimeType.orEmpty().lowercase()
                val dv5 = codec.startsWith("dvhe.05") || codec.startsWith("dvh1.05")
                val dv = dv5 || mime == "video/dolby-vision" ||
                    codec.startsWith("dvhe") || codec.startsWith("dvh1")
                val hdr = run {
                    val cs = videoFormat?.colorInfo?.colorSpace
                    val tr = videoFormat?.colorInfo?.colorTransfer
                    cs == C.COLOR_SPACE_BT2020 ||
                        tr == C.COLOR_TRANSFER_ST2084 ||
                        tr == C.COLOR_TRANSFER_HLG
                }
                if (dv5 != _isDvProfile5.value) {
                    android.util.Log.i(TAG, "DV profile5=$dv5（Exo 颜色正确 / mpv 偏绿）")
                    _isDvProfile5.value = dv5
                }
                if (dv != _isDolbyVision.value) {
                    android.util.Log.i(TAG, "dolby-vision=$dv → ${if (dv) "SurfaceView（优先出画）" else "TextureView"}")
                    _isDolbyVision.value = dv
                }
                if (hdr != _isHdr.value) {
                    android.util.Log.i(TAG, "hdr=$hdr（BT2020/PQ/HLG）")
                    _isHdr.value = hdr
                }
                /**
                 * 只有「设置=自动」且「本次没手动切过」才允许自动决策。
                 */
                /**
                 * 用户口径（2026-11-26）**「视频轨道解析不出来」必须交给 mpv**。
                 *
                 * 修的问题（日志实证 item-24861 热穴教师的合格贺礼）：
                 * ```
                 * 自动选核 → ExoPlayer（dv5=false hdr=false dv=false codec=）  ← codec 空
                 * ETKN-Player: audio id=1, mimeType=audio/mpeg              ← 只有音频！
                 * ready on ExoPlayer after 214 ms
                 * ```
                 * 这部是 msmpeg4v2 老编码，Exo 连视频轨道都没解析出来，
                 * 只认出了 mp3 音轨 → 画面全黑、进度条正常走。
                 * 之前的判断逻辑（dv5/hdr/dv）在 codec 为空时全部 false，
                 * 落到 else → 选了 Exo → 必然黑屏。
                 *
                 * 现在把「没有视频轨道」放在第一条判断：
                 *  · 有视频轨道但 codec 为空 → 同样是提取器不认识，交给 mpv
                 *  · mpv 内置 FFmpeg，msmpeg4v2 / wmv 这类老编码都能解
                 */
                val hasVideoTrack = tracks.groups.any { g ->
                    g.type == C.TRACK_TYPE_VIDEO && g.length > 0
                }
                if (!hasVideoTrack || (videoFormat != null && codec.isEmpty())) {
                    android.util.Log.i(TAG, "无视频轨道/编码未知（hasVideo=$hasVideoTrack codec=$codec）→ 需要 mpv")
                }
                if (autoSwitchAllowed && !userPickedEngineThisPlayback && !autoPickDone) {
                    /**
                     * 用户口径（2026-11-27）**Exo 能不能解这个编码，由设备能力决定，不是猜的**。
                     *
                     * 日志实证（这部 msmpeg4v2 的片子）：
                     * ```
                     * 15:55:54.292 自动选核 → ExoPlayer（codec=msmpeg4v2 hasVideo=true）
                     *              → 黑屏
                     * 15:55:58.992 手动切换内核 → mpv  → 出画
                     * ```
                     * 判据只看了「有没有视频轨道」，没看「这个编码 Exo 能不能解」，
                     * 所以 msmpeg4v2（视频轨道是有的）被判成 Exo，结果黑屏。
                     *
                     * 现在用 MediaCodecList 真实查询：设备解不了这个 mime → 直接 mpv。
                     * 这比任何白名单都准确（不同机器支持的编码不一样）。
                     */
                    val exoCanDecode = canDecode(mime)
                    val want = when {
                        // ⓪ 视频轨道缺失 / 编码未知 → mpv（Exo 解不了）
                        !hasVideoTrack || (videoFormat != null && codec.isEmpty()) -> Engine.Mpv
                        // ⓪' 设备没有这个编码的解码器 → mpv（内置 FFmpeg 能解）
                        !exoCanDecode -> Engine.Mpv
                        dv5 -> Engine.Exo          // ① DV profile5 → Exo（mpv 偏色）
                        hdr -> Engine.Mpv          // ② HDR10/HLG → mpv（Exo 发暗）
                        dv -> Engine.Exo           // DV 非 profile5 → Exo
                        else -> Engine.Exo         // ③ SDR → Exo
                    }
                    autoPickDone = true
                    android.util.Log.i(
                        TAG,
                        "自动选核 → ${want.label}（dv5=$dv5 hdr=$hdr dv=$dv codec=$codec hasVideo=$hasVideoTrack）",
                    )
                    /**
                     * 记住结论：下次进这一集直接用对的内核起播，不再起播两次。
                     * （这是「走马上任起播十几秒」的根治办法）
                     */
                    item?.id?.let { EngineMemory.remember(context, it, want) }
                    if (engine != want) {
                        forcedEngine = want
                        val cur = source
                        val pos = player?.currentPosition ?: 0L
                        /**
                         * 用户口径（2026-11-27）**自动选核也要真的切过去**。
                         *
                         * 用户实证：「黑屏那个视频还是打不开，我手动切换了一下内核
                         * 才显示，自动的那个没生效」——
                         * 说明自动决策算对了（日志有「自动选核 → mpv」），
                         * 但那次切换没执行。
                         *
                         * 根因和之前手动切换那个 bug 完全一样：
                         * 切换动作被包在 main.post 里，投递没有真正跑（或跑之前
                         * source 已变），于是 forcedEngine 设了但播放器没重建。
                         *
                         * 现在：直接在回调线程调 playSource。
                         * onTracksChanged 本身就在主线程（Exo 的内部播放线程投递到
                         * 应用线程），不需要再 post 一次。
                         */
                        if (cur != null && !released) {
                            runCatching { playSource(cur, pos) }
                                .onFailure { android.util.Log.e(TAG, "自动选核重建失败", it) }
                        } else {
                            android.util.Log.w(TAG, "自动选核跳过重建：source=${cur != null} released=$released")
                        }
                    }
                }
            }
            applyServerTracks(tracks)
            publishTracks(tracks)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            danmakuFeed?.tick()
        }

        override fun onPlayerError(error: PlaybackException) {
            val current = source
            val position = player?.currentPosition ?: 0L
            // 用户口径（2026-11-27）：ISO 支持已移除，不再有「当成盘再试一次」的分支。
            Log.e(TAG, "playback error on ${engine?.label} ${error.errorCodeName}", error)
            if (engine == Engine.Exo && recoverAudio(error)) return
            // One core gave up: the other plays the same source from the same place.
            val failed = engine
            /**
             * 用户口径（2026-11-26）：手动选了内核就不许自动换。
             * 之前这里无条件换，会把用户选的 Exo/mpv 覆盖掉。
             */
            /**
             * 用户口径（2026-11-27）**这里保留 main.post，但修掉一个会吞掉切换的条件**。
             *
             * 为什么必须 post：回调返回后失败的内核才被 release，
             * 同步重建会和正在释放的实例打架。这一条是有意为之，不改。
             *
             * 但原来条件是 `forcedEngine == null`：
             *  · 自动选核已经把 forcedEngine 设成 mpv 了
             *  · 这时 Exo 报错想切 mpv → 条件不成立 → 不再动作（正确）
             *  · 但反过来说明：只要 forcedEngine 有值就完全不补救，
             *    万一 forcedEngine 指向的内核也失败，就永远不换
             *
             * 现在改成判断「failed 是否已经等于我想要的内核」：
             * 失败的就是目标内核 → 没得换（避免来回切）；
             * 失败的还不是目标 → 换过去。
             */
            val target = forcedEngine
            if (current != null && failed != null && failed != target && !discTried && autoSwitchAllowed) {
                val other = if (failed == Engine.Exo) Engine.Mpv else Engine.Exo
                forcedEngine = other
                showNotice("已切换到 ${other.label} 内核")
                // After this callback returns: the failing core is released by the switch.
                main.post { if (!released && source === current) playSource(current, position) }
                return
            }
            _ui.update { it.copy(loading = false, error = describe(error)) }
        }
    }

    /**
     * 用户口径（2026-11-23）：**默认全走 ExoPlayer，播放失败才切 mpv。**
     *
     * 之前 Auto 是「HDR → Exo，其余 → mpv」，会在普通片源上直接给到 mpv。
     * 现在改成：Auto = 一律 ExoPlayer；Exo 起播失败/解码报错时，由
     * onPlayerError 里的自动换核逻辑切到 mpv（那段本来就是 Exo→Mpv，无需改）。
     * 手动在设置里选了内核仍然优先。
     */
    /**
     * 用户口径（2026-11-26）**识别到 HDR 后切到 mpv 做 tone-map**。
     *
     * 为什么不在 engineFor 里判断：那里还没解封装，拿不到 HDR 信息。
     * 所以在这里（轨道信息到位后）补一次切换。
     *
     * 安全约束：
     *  · 只在 Auto / Exo 时切（用户明确选了 mpv 就不用切，已经是了）
     *  · 防重入：切过一次就不再切，避免反复重建内核
     *  · 保留当前进度，切完从原位置继续
     */
    /**
     * 用户口径（2026-11-26）：**已停用**。
     *
     * v1.4.5 用它「HDR → 切 mpv 做 tone-map」，实测导致黑屏和缓存全失效，已撤回。
     * HDR 发暗问题改在渲染层解决，不再切内核。
     * 保留这个方法签名（空实现）是为了让历史调用点不至于编译不过。
     */
    private fun switchToHdrEngine() {
        android.util.Log.i(TAG, "switchToHdrEngine: 已停用（v1.4.6 起不再切内核）")
    }

    /**
     * 用户口径（2026-11-26）**在设置里换内核后立刻生效**。
     *
     * 之前 engineFor() 只在起播/换集时调用，设置改了当前这一集不会重选内核，
     * 用户改完回来看还是老样子 → 判定「设置压根不生效」。
     *
     * 这个方法供设置页在改完 engine 后调用：重选内核、从当前位置续播。
     */
    fun reloadEngineFromSettings() {
        if (released) return
        val cur = source ?: return
        val pos = player?.currentPosition ?: 0L
        forcedEngine = null          // 清掉自动切换留下的锁定，回到设置优先
        val wanted = engineFor(cur, disc = false)
        android.util.Log.i(TAG, "设置变更 → 重选内核=${wanted.label}（从 ${pos / 1000}s 续播）")
        main.post {
            if (!released && source === cur) playSource(cur, pos)
        }
    }

    /**
     * 用户口径（2026-11-26）**播放界面右上角「切换内核」按钮的执行入口**。
     *
     * 行为：在 ExoPlayer / mpv 之间切换，写回设置，立刻从当前位置续播。
     *
     * 为什么用「当前实际在跑的内核」判断方向，而不是读 settings.engine：
     * 设置是「自动」时，engine 字段是 Auto，按它判断会永远切到同一侧，
     * 按钮就变成只能切一次。按实际运行的内核翻转才符合直觉。
     *
     * @return 切换后要用的内核名（给 UI 立即显示，不等 Flow 回流）
     */
    fun toggleEngine(): String {
        val runningMpv = engine == Engine.Mpv
        val target = if (runningMpv) PlayerEngine.Exo else PlayerEngine.Mpv
        /**
         * 用户口径（2026-11-26）**按钮只影响当前这一集，绝不改全局设置**。
         *
         * 修的问题：用户按按钮切内核时，原实现会
         *   graph.settings.setEngine(target)
         * 直接写进 DataStore —— 于是「设置里的自动」被改成「mpv」，
         * 之后所有剧都按 mpv 走，用户再进去发现不是自动判定的了。
         *
         * 现在：
         *  · 不碰 settings（设置里仍是「自动」）
         *  · 只把本次播放锁定到目标内核
         *  · 标记 userPickedEngineThisPlayback，本次播放不再自动切
         *  · 换集/退出后自动失效，下次仍按片源类型自动判定
         */
        userPickedEngineThisPlayback = true
        autoPickDone = true
        forcedEngine = target.let { if (it == PlayerEngine.Exo) Engine.Exo else Engine.Mpv }
        val cur = source
        val pos = player?.currentPosition ?: 0L
        android.util.Log.i(
            TAG,
            "手动切换内核 → ${target.label}（仅本次播放，从 ${pos / 1000}s 续播）" +
                " src=${cur != null} released=$released forced=$forcedEngine",
        )
        /**
         * 用户口径（2026-11-27）**切换必须真的生效（日志实证 1.5.0 点了 12 次都没切）**。
         *
         * 日志：
         * ```
         * 14:20:29.593 点击内核按钮（当前=Exo）
         * 14:20:29.593 手动切换内核 → mpv（仅本次播放，从 839s 续播）
         * 14:20:30.013 点击内核按钮（当前=Exo）   ← 还是 Exo，根本没切
         * ```
         * 打了日志但 engine 没变成 mpv，且全程没有 `ETKN-Mpv: load`
         * → 说明 main.post 里那次 playSource 没跑（被条件挡住或 post 被吞）。
         *
         * 现在改成：**主线程同步重建内核，不再包 post**。
         * 这里本来就在主线程（点击回调），不需要再 post 一次。
         */
        if (cur != null && !released) {
            runCatching { playSource(cur, pos) }
                .onFailure { android.util.Log.e(TAG, "切换内核重建失败", it) }
        } else {
            android.util.Log.w(TAG, "切换内核被跳过：source=${cur != null} released=$released")
        }
        return if (target == PlayerEngine.Exo) "Exo" else "mpv"
    }

    /**
     * 用户口径（2026-11-27）**缓存上限：当前位置往后 min(字节阈值, 时长阈值)**。
     *
     * 这是「两个条件相辅相成、谁先到谁停」的唯一实现点。
     *
     * 参数都在实时变化（播放位置前进、时长要等解析），所以做成函数，
     * 每次算都用当下的真实值 —— 改设置里的 400/600/800MB 立刻生效。
     *
     * @return 允许缓存到的绝对字节位置；-1 = 目前算不出来（时长/总长未知）。
     */
    private fun computeCacheLimit(posMs: Long, durMs: Long, totalBytes: Long): Long {
        if (totalBytes <= 0) return -1L
        val bySize = settings.cacheMemoryMb.coerceIn(32, 8192).toLong() * 1024L * 1024L
        val seconds = settings.cacheMemorySeconds.coerceIn(30, 7200).toLong()

        /**
         * 用户口径（2026-11-27）**拿不到时长时也要能按字节阈值工作**。
         *
         * 日志实证：这一集 `tickCache 首次驱动 pos=562019ms dur=0ms` ——
         * Exo 对这个 strm 源一直给不出 duration。
         * 而旧实现 `if (durMs <= 0) return -1L` 直接放弃 → 阈值完全失效
         * → 缓存一路下到片尾（用户看到「根本不按 400MB 停」）。
         *
         * 现在三条路：
         *  · 有 duration → 两个阈值都参与，谁小谁生效（正常路径）
         *  · 无 duration → 只用**字节阈值**，并且按「已落盘量」估算位置偏移
         *    （字节阈值本身就是绝对量，不需要时长也能执行）
         */
        if (durMs <= 0) {
            /**
             * 无时长：位置换成字节只能用「已落盘到哪」来估。
             * 拿不到位置偏移时，就从 0 起算 —— 至少保证「整集最多缓存 bySize」，
             * 而不是完全不管。
             */
            val anchorBytes = currentCachedEndBytes()
            return (anchorBytes + bySize).coerceAtMost(totalBytes)
        }

        // 时长阈值换算成字节（按这一集的真实码率）
        val byTime = (totalBytes.toDouble() * seconds * 1000.0 / durMs.toDouble()).toLong()
        val span = minOf(bySize, byTime)            // 谁小谁生效
        // 起点 = 当前播放位置（换算成字节）
        val anchor = (totalBytes.toDouble() * posMs.coerceAtLeast(0L).toDouble() / durMs.toDouble()).toLong()
        return (anchor + span).coerceAtMost(totalBytes)
    }

    /**
     * 用户口径（2026-11-27）**无时长时的兜底：已落盘到哪个字节位置**。
     * 用 BlockStore 里真实连续的块长度反推，不依赖 duration。
     */
    private fun currentCachedEndBytes(): Long {
        val id = item?.id ?: return 0L
        if (currentLength <= 0) return 0L
        return runCatching {
            // 用官方缓存里真实落盘的连续字节数
            xyz.etkn.mobile.player.cache.VideoCache.availableBytesFrom(id, 0L, currentLength)
        }.getOrDefault(0L)
    }

    private fun engineFor(chosen: MediaSource, disc: Boolean): Engine {
        forcedEngine?.let { return it }
        // 用户口径（2026-11-27）：disc 恒为 false（ISO 已移除），这行留着只为兼容旧调用点。
        if (disc) return Engine.Exo
        return when (settings.engine) {
            PlayerEngine.Exo -> Engine.Exo
            PlayerEngine.Mpv -> Engine.Mpv
            /**
             * 用户口径（2026-11-26）**自动模式先查「这部片上次用的内核」**。
             *
             * 修的问题：「走马上任起播要十几二十秒」。
             *
             * 根因：起播时还没解封装，不知道片源是 HDR，
             * 所以只能先按默认给 Exo → 起播 → 识别到 HDR → 切 mpv → 再起播一次。
             * HDR 片源每次都要起播两次，两次加起来就是这个十几秒。
             *
             * 现在：上次已经识别出「这部片该用 mpv」，这次直接用 mpv 起播，
             * 一次到位，不用再切。第一次仍然会切（无法避免），之后都很快。
             */
            PlayerEngine.Auto -> {
                val remembered = item?.id?.let { EngineMemory.lookup(context, it) }
                remembered ?: Engine.Exo
            }
        }
    }

    /** Swaps cores only when the wanted one differs; an mpv that cannot start falls back to ExoPlayer. */
    /**
     * 用户口径（2026-11-27）**统一创建 Exo 播放器，并把「当前这一集」的缓存上下文传下去**。
     *
     * 缓存数据源需要知道 itemId（决定读写哪个缓存目录）和总字节数
     * （决定最后一块多长）。这两个值会随换集变化，所以传回调而不是值。
     */
    private fun videoPlayer(): Player =
        PlayerFactory.video(
            context = context,
            client = graph.http.media,
            userAgent = graph.identity.userAgent,
            decoder = settings.decoder,
            settings = settings,
            cacheItemId = { item?.id ?: "" },
            cacheTotalBytes = { currentLength },
            cacheDurationMs = { player?.duration?.coerceAtLeast(0L) ?: 0L },
            cachePositionMs = { player?.currentPosition?.coerceAtLeast(0L) ?: 0L },
        )

    private fun ensurePlayer(wanted: Engine): Player {
        val existing = _player.value
        if (existing != null && engine == wanted) return existing
        val speed = existing?.playbackParameters?.speed ?: settings.defaultSpeed
        if (existing != null) {
            closeDanmaku()
            existing.removeListener(listener)
            existing.release()
        }
        val created: Pair<Engine, Player> = if (wanted == Engine.Mpv) {
            runCatching {
                /**
                 * 用户口径（2026-11-26）：**DV 标记必须走构造参数**。
                 *
                 * mpv 的 init 里会立刻 configure() 设置 hwdec，
                 * 而 DV 源必须软解（硬解拿不到杜比 RPU 元数据 → 偏色）。
                 * 事后赋值的话 configure 早就跑完，软解设置无效。
                 */
                Engine.Mpv to (
                    MpvPlayer(
                        context = context,
                        userAgent = graph.identity.userAgent,
                        decoder = settings.decoder,
                        languages = PlayerFactory.preferredLanguages().toList(),
                        isDolbyVisionSource = pendingDvSource,
                    ) as Player
                    )
            }
                .onFailure { Log.e(TAG, "mpv unavailable", it) }
                .getOrNull()
                ?: (Engine.Exo to videoPlayer())
        } else {
            Engine.Exo to videoPlayer()
        }
        engine = created.first
        val next = created.second
        next.setPlaybackSpeed(speed)
        next.addListener(listener)
        _player.value = next
        return next
    }

    init {
        scope.launch {
            graph.settings.flow.map { it.danmaku }.distinctUntilChanged().collect { style ->
                _danmakuStyle.value = style
                applyDanmaku()
            }
        }
    }

    // ---------- Loading ----------

    /** Plays a finished download from disk with its saved tracks and chapters. */
    fun startOffline(entry: DownloadEntry, startMs: Long = 0L) {
        offline = true
        val source = entry.offlineSource()
        showItem(entry.offlineItem())
        _ui.update { it.copy(sources = listOf(source), sourceIndex = 0, loading = true, error = null) }
        playSource(source, startMs)
    }

    fun start(itemId: String, type: String?, seriesId: String?, seasonId: String?, startMs: Long, resume: Boolean, sourceId: String?) {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                var targetId = itemId
                var position = startMs
                var known: Item? = null
                if ((type == "Series" || type == "Season") && seriesId != null) {
                    val target = api.playbackTarget(seriesId, if (type == "Season") seasonId ?: itemId else null)
                        ?: api.episodePage(seriesId, if (type == "Season") seasonId ?: itemId else null, start = 0, limit = 1).items.firstOrNull()
                        ?: throw IllegalStateException("没有可播放的剧集")
                    targetId = target.id
                    position = if (resume && !target.userData.played) target.positionMs else 0
                    known = target
                }
                load(targetId, position, sourceId, known)
            } catch (error: Exception) {
                _ui.update { it.copy(loading = false, error = error.message ?: "无法开始播放") }
            }
        }
    }

    /**
     * Playback starts as soon as PlaybackInfo is in (usually already requested when Play was
     * tapped); the full item only refines the title and chapters, so it never holds the stream.
     */
    private suspend fun load(itemId: String, startMs: Long, sourceId: String?, known: Item? = null) = coroutineScope {
        stopReporting()
        loadStartedAt = SystemClock.elapsedRealtime()
        _ui.update { it.copy(loading = true, error = null, next = null, segments = emptyList()) }
        val handoff = PlaybackPrefetch.take(itemId)
        // Never fails the load: a missing detail only leaves the tapped item's fields on screen.
        val detail = async { runCatching { api.item(itemId, EtknApi.DETAIL_FIELDS) }.getOrNull() ?: runCatching { api.item(itemId) }.getOrNull() }

        /**
         * 用户口径（2026-11-23）起播提速：**有缓存就先播缓存，播放的同时再请求网络**。
         *
         * 缓存 key 已改成 itemId（不再依赖直链 URL），所以这里能纯本地判断：
         *  · 命中完整缓存 → 立刻用 file:// 起播，同时后台继续请求 PlaybackInfo
         *    （用于拿下一集、进度上报、断点续传；播完这段缓存还能无缝接网络）
         *  · 没命中 → 照原路径等接口
         */
        val localFile = PlaybackCache.localFileById(graph.app, itemId)
        if (localFile != null && !offline) {
            val baseItem = (handoff?.item ?: known ?: detail.await())?.takeIf { it.id == itemId }
            if (baseItem != null) {
                android.util.Log.i(
                    TAG,
                    "cache-first after ${SystemClock.elapsedRealtime() - loadStartedAt} ms " +
                        "size=${localFile.length()}",
                )
                showItem(baseItem)
                // 本地起播：不需要 PlaybackInfo
                playLocalFile(baseItem, localFile, startMs)
                applyDanmaku()
                // 后台补请求：拿下一集 + 记住直链，供续传与预加载
                scope.launch {
                    runCatching {
                        val late = handoff?.info?.let { it.await() } ?: api.playbackInfo(itemId)
                        if (late.sources.isNotEmpty()) {
                            playSession = late.playSessionId
                            danmakuAvailable = late.danmakuAvailable
                            val url = runCatching {
                                PlaybackAddress.resolve(late.sources.first(), session.server, session.token, session.userId)
                            }.getOrNull()
                            if (url != null) PlaybackCache.rememberUrl(itemId, url)
                            _ui.update { it.copy(sources = late.sources, sourceIndex = 0) }
                        }
                        runCatching { loadNext(baseItem.id) }
                    }
                }
                return@coroutineScope
            }
        }

        val info = handoff?.info?.let { runCatching { it.await() }.getOrNull() } ?: api.playbackInfo(itemId)
        if (info.sources.isEmpty()) throw IllegalStateException("暂无可播放版本")
        val index = sourceId?.let { id -> info.sources.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: 0
        val base = (handoff?.item ?: known)?.takeIf { it.id == itemId } ?: detail.await() ?: throw IllegalStateException("无法读取条目")
        Log.i(TAG, "playback info after ${SystemClock.elapsedRealtime() - loadStartedAt} ms (prefetched=${handoff != null})")
        playSession = info.playSessionId
        closeDanmaku()
        danmakuAvailable = info.danmakuAvailable
        showItem(base)
        _ui.update { it.copy(sources = info.sources, sourceIndex = index) }
        playSource(info.sources[index], startMs)
        applyDanmaku()
        /**
         * 用户口径（2026-11-27）**不管什么类型都试着取下一集**。
         *
         * 修的 bug（日志实证：103 条 isNextReady 全是 next=null，预加载从没启动）：
         * 原来只在 `base.isEpisode` 时才调 loadNext。
         * 但通过 strm / version 形式进入播放时，base 可能是 Series 或 Movie，
         * 于是 loadNext 从不调用 → next 永远 null → startNextIfIdle 第一行就 return
         * → 预加载和小气泡永远不会出现。
         *
         * 现在：无条件尝试。nextEpisode 接口本身就是「有下一集就返回，没有就 null」，
         * 所以对电影调用也无害（返回 null，什么都不做）。
         */
        loadNext(base.id)
        val full = detail.await()
        if (full != null && item?.id == full.id && full !== base) {
            showItem(full)
            source?.let { src ->
                val segs = segments(src.chapters.ifEmpty { full.chapters }, src.runTimeTicks / TICKS_PER_MS)
                // 用户口径（2026-11-23）：续播点已经越过片头结束点才标记「已跳过」，
                // 落在片头内部时仍然要跳。
                val introEnd = segs.firstOrNull { it.kind == SkipSegment.Kind.Intro }?.endMs
                if (introEnd != null && startMs >= introEnd) autoSkippedIntro = true
                _ui.update { it.copy(segments = segs) }
            }
        }
    }

    private fun showItem(value: Item) {
        item = value
        _ui.update {
            it.copy(
                item = value,
                title = if (value.isEpisode) value.seriesName ?: value.name else value.name,
                subtitle = if (value.isEpisode) listOfNotNull(value.episodeCode, value.name).joinToString("  ") else value.productionYear?.toString().orEmpty(),
            )
        }
    }

    /**
     * 用户口径（2026-11-27）**不再支持 ISO 蓝光盘播放**。
     *
     * 用户明确表示没有 ISO 片源，且盘播放牵扯 libbluray、盘内标题/章节、
     * DV p7→p8.1 蓝光路径，会让缓存代理多一条完全不同的分支。
     * 去掉之后所有片源只剩「普通视频流」一种形态，缓存数据源只处理 Range 请求。
     *
     * [disc] 参数保留但恒为 false（外部还有调用点），蓝光相关类文件不删，
     * 万一以后要用重新接回来即可。
     */
    private fun playSource(chosen: MediaSource, startMs: Long, disc: Boolean = false) {
        val current = item ?: return
        if (source !== chosen) {
            discTried = false
            failedFormats.clear()
            lastTracks = Tracks.EMPTY
            /**
             * 用户口径（2026-11-26）**这里不能无条件清 forcedEngine**。
             *
             * 修的问题：「点切换内核没反应」（老片黑屏时尤其明显）。
             *
             * 根因：toggleEngine() 先设 forcedEngine=目标内核，再调 playSource()；
             * 而 playSource 进来第一件事就是把 forcedEngine 清成 null
             * → 目标内核设置当场被抹掉 → ensurePlayer 又按 engineFor 选回原来那个
             * → 用户看到的就是「点了没反应」。
             *
             * 现在：只有在「不是用户手动切换」的情况下才清
             * （用户手动切换时 userPickedEngineThisPlayback=true，保留 forcedEngine）。
             */
            if (!userPickedEngineThisPlayback) {
                // 非手动切换（自动决策 / 换集）→ 清掉上一轮的锁定
                forcedEngine = null
            }
            /**
             * 用户口径（2026-11-26）**换片源 = 重新按片源类型自动判定内核**。
             *
             * 这里重置两个标记，语义是「新的一集/新的一部，重新决策」：
             *  · autoPickDone：允许 onTracksChanged 再决策一次
             *  · userPickedEngineThisPlayback：
             *    上一集手动切过 mpv，不代表这一集也要 mpv。
             *    用户要的是「自动模式下每部剧都自动选最合适的」，
             *    所以手动选择的作用域就是「当前这一集」。
             */
            autoPickDone = false
            userPickedEngineThisPlayback = false
        }
        // New media in the player (new source or the other core): its tracks start from the server's defaults again.
        pendingDefaults = setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)
        source = chosen
        discTried = discTried || disc
        val player = ensurePlayer(engineFor(chosen, disc))
        started = false
        stopped = false
        completedBySkip = false
        trimmedThisItem = false
        // 用户口径（2026-11-23）：原来是 startMs > 0 就标记「已跳过」，
        // 结果续播点落在片头内部时永远不跳。改成「续播点已越过片头结束点」才算跳过。
        // 注意此时 segments 还没算出来，先置 false，等 segments 生成后再判断。
        autoSkippedIntro = false
        autoSkippedCredits = false
        val url = PlaybackAddress.resolve(chosen, session.server, session.token, session.userId)
        /**
         * 用户口径（2026-11-23）：mpv 走本地文件缓存。
         *
         * mpv 不认 Media3 的 CacheDataSource，所以给它一个真实的本地文件：
         *  · 这一集已经完整缓存过 → 直接把本地路径交给 mpv，完全不发网络请求
         *  · 没缓存过 → 保持走网络（边播边缓存由 Exo 那边负责；mpv 只能靠预加载写文件）
         *
         * 只有普通视频走这条路；蓝光 ISO（disc）必须走 libbluray，不能替换成本地文件。
         */
        // 用户口径（2026-11-23）：记下当前集地址与总长，供缓存流水线使用。
        // 换集时把「下一集直链」清掉，避免沿用上一集的。
        currentUrl = url
        currentLength = chosen.size ?: -1L
        // 服务端时长：Exo 拿不到 duration 时用它兜底（见 serverDurationMs 说明）
        serverDurationMs = chosen.runTimeTicks.takeIf { it > 0 }?.div(TICKS_PER_MS) ?: 0L
        nextUrl = null
        PlaybackCache.clearReady()
        /**
         * 用户口径（2026-11-26）：换集时清掉上一集的窗口记录。
         * 窗口是「滑动区间」，换集后必须重算，否则缓冲条会沿用上一集的区间。
         */
        PlaybackCache.clearWindow(current.id)
        /**
         * 用户口径（2026-11-23）：**部分缓存也要立刻用上，别再请求服务器**。
         *
         * ⚠️ 用户口径（2026-11-23）修正 —— 上一版这里是个严重 bug：
         *  我把 .part（半截文件）也交给播放器当 file:// 播，结果 ExoPlayer 报
         *  「Caused by: Malformed URL / ERROR_CODE_FAILED_RUNTIME_CHECK」每次都崩，
         *  然后被自动换核到 mpv（日志里 mpv 的 host= 还是空的），用户看到的现象就是
         *  「谷雨每集都切 mpv」「起播极慢」。
         *
         *  根因：MKV/MP4 的索引在文件末尾，半截文件根本不是一个合法可播文件，
         *  拿它当播放源必然解析失败。
         *
         *  正确规则（现在这样）：
         *   · 整集已缓存完成 → 用 file:// 播，零网络（这才是真秒播）
         *   · 只是部分缓存     → **不用它当播放源**，照常走网络 URL；
         *                        .part 继续作为续传底子，攒满后自动转正，
         *                        下次进来才是真命中
         */
        // 用户口径（2026-11-23）：缓存文件按 itemId 命名，这里也按 id 查
        val cachedLocal = PlaybackCache.localFileById(context, current.id)
        val cacheComplete = cachedLocal != null
        val subtitles = chosen.subtitles.filter { it.isExternal && it.deliveryUrl != null && !offline }.mapNotNull { stream ->
            val address = PlaybackAddress.subtitle(stream.deliveryUrl!!, session.server, session.token) ?: return@mapNotNull null
            val mime = subtitleMime(stream.codec) ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(address))
                .setMimeType(mime)
                .setLanguage(stream.language.ifEmpty { null })
                .setLabel(stream.label)
                .setId("ext-${stream.index}")
                .setSelectionFlags(if (stream.isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        /**
         * 用户口径（2026-11-23）日志已确认的真相：
         *   version-35806 cache hit=true 完成=true → Malformed URL → 切 mpv
         *   version-35138 cache hit=false          → ExoPlayer 721ms 正常起播
         * 凡是「命中缓存用 file://」就崩，「走网络」就正常 —— 所以坏在本地 URI 这条路上。
         *
         * 这里改成两步：① 先算出一个合法的播放 URI，② 打日志把真实值记下来。
         */
        /**
         * 用户口径（2026-11-27）**两个内核统一走本机缓存代理**。
         *
         * 之前：Exo 有数据源缓存，mpv 只能等「整集下完的旧文件」——
         * 两套缓存互不相通，mpv 下永远半截不能用。
         *
         * 现在：
         *  · 启动本机代理（127.0.0.1 随机端口，只服务 /item/<id>）
         *  · 把这一集的上游地址登记进去
         *  · Exo 和 mpv 都播 http://127.0.0.1:<port>/item/<id>
         *    → 谁都能「本地有就读本地、没有就边拉边存」
         *
         * 代理起不来（极罕见）→ 退回直连上游，功能不受影响，只是没缓存加速。
         */
        /**
         * 用户口径（2026-11-27）**播放地址直连上游，缓存由官方 CacheDataSource 在数据源层处理**。
         *
         * 这里不再需要本机代理、不再手工选 file:// 还是 http://：
         * 播放器始终请求上游 URL，CacheDataSource 在它下面自动
         * 命中就读本地、未命中就边下边写。
         *
         * 唯一的例外是「旧版遗留的整集文件」——那种情况仍可直读（省一次解封装），
         * 但新缓存不走这条路。
         */
        /**
         * 用户口径（2026-11-27）**mpv 走本机代理（底层是同一个 SimpleCache）**。
         *
         * Exo 直接用 CacheDataSource；mpv 没有数据源概念，只能给 HTTP 地址，
         * 所以起一个只绑 127.0.0.1 的极薄代理，它内部用官方 CacheDataSource 读。
         * 两个内核共用同一份缓存文件，谁缓存的另一方都能秒开。
         */
        /**
         * 用户口径（2026-11-27）**Exo 直连上游，只有 mpv 走本机代理**。
         *
         * 修的 bug（日志实证）：
         * 上一版不管什么内核都用代理地址（playUri=http://127.0.0.1:39741/...），
         * 而 Exo 的数据源遇到回环地址是「透传不缓存」——
         * 于是 Exo 播放全程不往 SimpleCache 写任何东西，
         * 缓冲条自然一点不动。
         *
         * 现在按内核分流：
         *  · Exo → 直连上游 URL，CacheDataSource 自动边播边写缓存（正常路径）
         *  · mpv → 走代理，代理内部用同一个 CacheDataSource 读写缓存
         *
         * 两边共用同一个 SimpleCache 和同一个 cache key（itemId），
         * 所以谁缓存的另一方都能命中。
         */
        val engineNow = engine
        val cacheMaxBytes = settings.cacheDiskGb.coerceIn(0, 200).toLong() * 1024L * 1024L * 1024L
        val playUri: android.net.Uri = if (cacheMaxBytes > 0 && engineNow == Engine.Mpv) {
            val port = xyz.etkn.mobile.player.cache.LocalProxy.start(
                xyz.etkn.mobile.player.cache.VideoCache.dataSource(
                    context = context,
                    upstream = xyz.etkn.mobile.player.cache.httpFactory(
                        graph.http.media, graph.identity.userAgent,
                    ),
                    maxBytes = cacheMaxBytes,
                ),
            )
            if (port > 0) {
                xyz.etkn.mobile.player.cache.LocalProxy.register(current.id, url)
                val proxied = xyz.etkn.mobile.player.cache.LocalProxy.urlFor(current.id)
                android.util.Log.i(TAG, "engine=mpv proxy uri=${proxied ?: url}")
                if (proxied != null) android.net.Uri.parse(proxied) else android.net.Uri.parse(url)
            } else {
                android.net.Uri.parse(url)
            }
        } else {
            android.util.Log.i(TAG, "engine=${engineNow?.label} direct url (CacheDataSource 负责缓存)")
            android.net.Uri.parse(url)
        }

        /**
         * 用户口径（2026-11-26）**这一行是「退出再进有没有命中缓存」的唯一判据**。
         *
         * 用户报的现象：看完退出、再点同一集，明显还在走网络（网速跑起来）才出画面，
         * 应该立刻读本地缓存秒播。
         *
         * 这里把三件事一次打全，便于一眼判断卡在哪一环：
         *  · 缓存目录里到底有没有这一集的文件（有没有写过）
         *  · 文件多大（写了多少）
         *  · 最终播放 URI 是 file:// 还是 http(s)://（有没有真的用上）
         */
        /**
         * 用户口径（2026-11-26）**同时打出缓存目录总览**。
         *
         * 因为缓存写在 `cacheDir`（系统可随时清理），如果整个目录是空的，
         * 说明不是「查不到这一集」而是「根本没写进去 / 被系统清了」——两者的修法完全不同。
         */
        run {
            val dir = PlaybackCache.partFileById(context, current.id).parentFile
            val all = dir?.listFiles()
            android.util.Log.i(
                TAG,
                "cache-dir path=${dir?.absolutePath ?: "none"} " +
                    "exists=${dir?.exists() ?: false} " +
                    "files=${all?.size ?: 0} " +
                    "bytes=${all?.sumOf { it.length() } ?: 0} " +
                    "hasThisEpisode=${all?.any { it.name.startsWith(current.id.replace(Regex("[^A-Za-z0-9_-]"), "_")) } ?: false}",
            )
        }

        android.util.Log.i(
            TAG,
            "cache-probe item=${current.id} " +
                "file=${PlaybackCache.partFileById(context, current.id).parentFile?.path}/" +
                "${current.id.replace(Regex("[^A-Za-z0-9_-]"), "_")} " +
                "hit=${cachedLocal != null} " +
                "size=${cachedLocal?.length() ?: 0} " +
                "scheme=${playUri.scheme} " +
                "localPath=${cachedLocal?.absolutePath ?: "none"}",
        )

        android.util.Log.i(TAG, "playUri=${playUri} (local=${cachedLocal?.absolutePath ?: "none"})")
        val media = MediaItem.Builder()
            .setUri(playUri)
            .setMediaId(current.id)
            /**
             * 用户口径（2026-11-27）**缓存 key 必须固定为 itemId**。
             *
             * 这是「SimpleCache 的 key 带 token 命中不了」这个历史问题的解法：
             * 默认 key 取完整 URL（含 token/UserId 查询参数），token 一刷新就换 key
             * → 上次缓存的永远命中不了。
             * 这里显式指定 customCacheKey = itemId，同一集永远同一个 key。
             */
            .setCustomCacheKey(current.id)
            .setSubtitleConfigurations(subtitles)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(_ui.value.title).setSubtitle(_ui.value.subtitle).build())
            // Full-disc ISO: libbluray opens the server's probed main playlist when it sent one.
            .setTag(BlurayTag(disc, chosen.raw.int("BlurayPlaylist", -1), discHints(chosen)))
            .build()
        Log.i(TAG, "play ${chosen.id} on ${engine?.label} container=${chosen.container} disc=$disc playlist=${chosen.raw.int("BlurayPlaylist", -1)} " +
            "host=${Uri.parse(url).host} streams=" + chosen.streams.filterNot { it.isExternal }.joinToString(" ") {
                "${it.type}:${it.codec}${if (it.transportStreamId > 0) "@" + it.transportStreamId else ""}:${it.language}"
            })
        player.setMediaItem(media, startMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
        android.util.Log.i(
            TAG,
            "cache hit=${cachedLocal != null} complete=$cacheComplete " +
                "bytes=${cachedLocal?.length() ?: 0} url=${Uri.parse(url).host}",
        )
        /**
         * 用户口径（2026-11-23）：边播边缓存。
         *
         * 用户要的是「看一半退出，下次进来直接读本地，不再请求网络」。
         * 所以只要是在线播放（不是本地文件、不是蓝光 ISO），就在后台把这一集整段写进
         * 本地缓存文件；下次 playSource 时 PlaybackCache.localFile 命中，
         * 直接把 file:// 交给内核 → 一个网络请求都不发。
         */
        // 缓存写入不在这里启动：由 PlayerScreen 每 5 秒调 tickCache 驱动滑动窗口，
        // 只有当前集窗口完成后才会去预加载下一集（串行流水线）。
        // A new player starts unshifted: the selected subtitle's calibration is applied again.
        _ui.update { it.copy(segments = segments(chosen.chapters.ifEmpty { current.chapters }, chosen.runTimeTicks / TICKS_PER_MS), subtitleOffset = null) }
        startProgressLoop()
        // A core switch closed the feed bound to the previous player.
        applyDanmaku()
        if (!offline) loadPreviews(current.id, chosen.id)
    }

    /** Seek thumbnails for this version; none (HTTP 204) until the server has extracted them. */
    private fun loadPreviews(itemId: String, sourceId: String) {
        if (_ui.value.previews != null && previewKey == "$itemId/$sourceId") return
        previewKey = "$itemId/$sourceId"
        previewJob?.cancel()
        _ui.update { it.copy(previews = null) }
        previewJob = scope.launch {
            val manifest = runCatching { api.previewThumbnails(itemId, sourceId) }.getOrNull() ?: return@launch
            _ui.update { it.copy(previews = SeekPreviews.from(session.server, itemId, manifest)) }
        }
    }

    fun selectSource(index: Int) {
        val sources = _ui.value.sources
        val chosen = sources.getOrNull(index) ?: return
        val position = player?.currentPosition ?: 0L
        stopReporting()
        _ui.update { it.copy(sourceIndex = index, loading = true) }
        playSource(chosen, position)
    }

    private fun loadNext(itemId: String) {
        scope.launch {
            val next = runCatching { api.nextEpisode(itemId) }.getOrNull()
            if (item?.id != itemId) return@launch
            _ui.update { it.copy(next = next) }
            // 预加载不在这里触发：由 tickCache 在当前集缓存完成后串行推进（一环套一环）。
            }
    }

    /**
     * 用户口径（2026-11-23）缓存流水线：一环套一环，由播放进度驱动。
     *
     * 每 5 秒被 PlayerScreen 调一次，传当前进度：
     *  ① 当前集：从进度往后开窗口缓存（受最大缓存大小/时长双阈值约束，达阈值即停）
     *  ② 当前集窗口跑完 → 才去取下一集直链
     *  ③ 下一集同样跑窗口缓存
     *  ④ 两边都完成 → 气泡亮（下一集可秒播）
     *
     * 只在「预加载」开着时做；关着就什么也不做。
     */
    /** 只在本集第一次 tick 时做一次磁盘收敛，避免每 5 秒扫全目录。 */
    private var trimmedThisItem = false

    fun tickCache(positionMs: Long, durationMs: Long) {
        /**
         * 用户口径（2026-11-23）：进播放器第一次 tick 时先收敛一次磁盘
         * （老版本堆的超额缓存不会自己变小）。
         */
        if (!trimmedThisItem) {
            trimmedThisItem = true
            PlaybackCache.trimNow(graph.app, settings)
        }
        // 两个阈值都是 0 = 不做缓存
        val cacheWindowOn = settings.cacheMemoryMb > 0 || settings.cacheMemorySeconds > 0
        if (!cacheWindowOn) return
        val cur = currentUrl ?: return
        val currentItem = item ?: return

        /**
         * 用户口径（2026-11-27）**持续播放时也要持续淘汰**，不能只在进播放器时查一次。
         *
         * 用户原话：
         *  「持续播放，你那边持续缓存，当达到阈值之后，
         *    就自动把最开始的那个数据一点点往下挤」
         *
         * 每次 tick 都按「最大磁盘缓存」检查一次。
         * trim 内部先算总量、没超就直接返回，开销很小（只 stat 目录）。
         * 传入当前 itemId 作为保护，绝不删正在播的这一集。
         */
        /**
         * 用户口径（2026-11-27）**淘汰交给官方 LRU 淘汰器**。
         *
         * 之前手写的 trim 需要每次 tick 扫描目录，官方
         * LeastRecentlyUsedCacheEvictor 在写入时就按上限自动挤掉最旧的，
         * 不需要我们再管。这里只记一下占用，方便日志排查。
         */
        if (!cacheUsageLogged) {
            cacheUsageLogged = true
            android.util.Log.i(
                "ETKN-Cache",
                "disk usage=${xyz.etkn.mobile.player.cache.VideoCache.usedBytes() / 1048576}MB " +
                    "cap=${settings.cacheDiskGb}GB",
            )
        }

        /**
         * 用户口径（2026-11-24）**缓冲窗口是滑动窗口，必须紧跟播放位置**。
         *
         * 之前有两个前置条件（播放 >15s、内核前向缓冲 >30s）会直接 return，
         * 导致起播后一大段时间完全不缓冲、缓冲条不动 → 用户看到的「逻辑很乱」。
         * 现在去掉这两个门槛：只要开播就推进窗口。
         * 带宽冲突由「每次 tick 只拉一个 chunk」自然缓解（见 pullWindow）。
         */
        if (positionMs <= 0) return
        /**
         * 用户口径（2026-11-26）：确认 tickCache 真的被驱动。
         * 之前日志里 ETKN-Cache 一条都没有，无法判断是「没被调用」还是「调了但没下载」。
         */
        if (!tickLogged) {
            tickLogged = true
            android.util.Log.i(TAG, "tickCache 首次驱动 pos=${positionMs}ms dur=${durationMs}ms len=$currentLength")
        }

        /**
         * 用户口径（2026-11-26）**修「下一集预加载永远不发生」的另一半原因**。
         *
         * `startNextIfIdle` 这个函数在整个工程里**零调用**（死代码），
         * 而下一集的直链 `nextUrl` 只在它里面被设置。
         * 于是 nextUrl 永远是 null → 传给 pipeline 的 next 恒为 null →
         * pipeline 里 `val n = next ?: return@launch` 直接返回 →
         * 预加载根本不会发生。
         *
         * 这里在每次 tick 时确保 nextUrl 已经取到（取过一次就不重复取）。
         */
        if (settings.preloadNext && nextUrl == null) {
            startNextIfIdle(currentItem)
        }

        /**
         * 用户口径（2026-11-27）**预加载的触发条件：当前集快播完了才开始铺下一集**。
         *
         * 修的问题（用户原话）：「我这都已经片尾了，它还不触发下一集预加载，
         * 难道我要等拉到最后吗」
         *
         * 原来没有任何位置条件，靠 currentComplete（缓冲条铺到片尾）来卡，
         * 但缓冲条常常到不了片尾（Exo 上限 + 缓存阈值），于是永远不触发。
         *
         * 现在用两个条件里任意一个成立就开始：
         *  ① 当前集已缓冲到片尾（最快路径，缓冲够大时成立）
         *  ② 剩余时长不足 [PRELOAD_LEAD_MS]（兜底，保证临到片尾一定开始）
         * 并且加防重入：本轮已经在铺就不再发起。
         */
        /**
         * 用户口径（2026-11-27）**每 5 秒按当前位置重算缓存上限并重新登记**。
         *
         * 这是「窗口跟着播放前移」和「改设置立刻生效」的实现点：
         *  · 播放位置前进 → 上限右移 → 继续往后下
         *  · 设置里 400 改 600 → 下一次 tick 就用新值
         *  · 起播时 duration 还是 0 算不出来 → 等这里第一次拿到就补上
         *
         * 不做这一步的话，起播那一次登记的值（可能是 -1 或不含位置偏移）
         * 会一直用下去 —— 这正是之前「阈值完全不生效」的原因。
         */
        // Exo 给不出时长时用服务端的（日志实证 strm 源 dur 恒为 0）
        val effDurMs = if (durationMs > 0) durationMs else serverDurationMs

        /**
         * 用户口径（2026-11-27）**duration=0 时也要能触发预加载**。
         *
         * 日志实证这一集 `dur=0ms` 全程如此（strm 源给不出时长），
         * 而 old 判据是 `durationMs > 0 && 剩余 < 5分钟` —— 永远不成立
         * → 预加载永远不触发。
         *
         * 现在：duration 未知时，用「已播放时长」兜底 ——
         * 播到 20 分钟以上就认为「够久了，该铺下一集了」。
         */
        val nearEnd = when {
            effDurMs > 0 -> (effDurMs - positionMs) <= PRELOAD_LEAD_MS
            // 时长未知：播够 20 分钟就当接近尾声（短剧也不至于太早）
            else -> positionMs >= 20 * 60 * 1000L
        }
        val shouldPreload = settings.preloadNext && (PlaybackCache.currentCompleteFlag() || nearEnd)
        if (settings.preloadNext && !shouldPreload) {
            android.util.Log.i(
                TAG,
                "preload wait: pos=${positionMs / 1000}s dur=${effDurMs / 1000}s " +
                    "nearEnd=$nearEnd complete=${PlaybackCache.currentCompleteFlag()}",
            )
        }

        val length = currentLength
        scope.launch {
            /**
             * 下一集信息（含片头结束位置和总时长）——
             * 供「片头优先」的预加载量计算使用。
             */
            val nextInfo = PlaybackCache.NextEpisode(
                itemId = nextUrl?.first ?: "",
                url = nextUrl?.second ?: "",
                introEndMs = nextIntroEndMs,
                durationMs = nextDurationMs,
                lengthBytes = nextLength,
            ).takeIf { it.itemId.isNotEmpty() && it.url.isNotEmpty() }

            /**
             * 用户口径（2026-11-27）**旧的「另起一条流写整文件」已停用**。
             *
             * 停用原因：现在缓存由 CachedDataSource 负责（播放器要什么就拉什么、顺手写块），
             * 如果再让旧流水线同时下当前集，就是两条流抢带宽、重复下载，
             * 还会把 .part 文件写乱。
             *
             * 所以这里只保留「下一集预加载」这一个能力（它仍然有用：
             * 提前把下一集头部铺好，点下一集能秒开）。
             */
            /**
             * 用户口径（2026-11-27）**必须是 `if (shouldPreload)` 才铺**。
             *
             * 修的 bug（用户截图实证）：「刚播放就把下一集给预加载出来了？」
             * 1:52 / 33:04 才刚开始播，下一集就已经在下。
             *
             * 根因：这里漏了判断条件 —— 无条件调用 preloadNextOnly，
             * 而 tickCache 每秒跑一次，所以起播第一秒就开始铺下一集。
             * shouldPreload 明明算出来了却没用上。
             *
             * 现在：缓冲到片尾 或 剩余不足 5 分钟 才铺。
             */
            val job = if (shouldPreload) {
                PlaybackCache.preloadNextOnly(
                    client = graph.http.media,
                    context = graph.app,
                    settings = settings,
                    currentEpisodeId = currentItem.id,
                    next = nextInfo,
                    // 预加载也走同一个官方缓存数据源 → 写进同一份 SimpleCache
                    dataSourceFactory = xyz.etkn.mobile.player.cache.VideoCache.dataSource(
                        context = graph.app,
                        upstream = xyz.etkn.mobile.player.cache.httpFactory(
                            graph.http.media, graph.identity.userAgent,
                        ),
                        maxBytes = settings.cacheDiskGb.coerceIn(0, 200).toLong() * 1024L * 1024L * 1024L,
                    ),
                )
            } else null
            job?.join()
            /**
             * 用户口径（2026-11-24）**缓冲条数据源**。
             *
             * ui.cachedPosition 这个字段之前只声明、从未计算 —— 永远是 0，
             * 所以进度条上的缓冲段只能退回 player.bufferedPosition（内核几秒前向缓冲），
             * 用户看到的就是「缓冲条逻辑很乱」。
             *
             * 现在按真实落盘文件换算：当前集缓存到第几毫秒。
             * 这是滑动窗口的结果，所以会随播放位置一起前进 —— 单集内缓冲条恒长。
             */
            /**
             * 用户口径（2026-11-27）**缓冲条改用播放器自己的 bufferedPosition**。
             *
             * 这是本次重构最关键的一处改动。
             *
             * 之前：`PlaybackCache.cachedPositionMs` 是我们自己按字节区间算的，
             * 跟播放器实际能播到哪毫无关系 → 用户看到「越来越少，然后突然变长」。
             * 改过 4 次（12MB 分片 → 1MB → 窗口平移 → 连续区间）都是在这个数上打转。
             *
             * 现在：缓存已经是播放器的数据源（CachedDataSource），
             * 所以 bufferedPosition 就是「真实能播到哪」——
             * 本地有块就是满的，缺的会边拉边补，天然不会出现
             * 「进度追上缓冲」这种矛盾。
             */
            /**
             * 用户口径（2026-11-27）**缓冲条 = 真实落盘量，不是播放器请求水位**。
             *
             * 这是第 N 次修正，也是唯一正确的数据源。
             *
             * 用户原话：
             *  「这个缓冲条是因为我只能通过缓冲条来判断它是不是真正的已经缓存到本地了……
             *    你现在光给我瞎画这个小动画，那你不掩耳盗铃吗」
             *
             * 之前用的 bufferedPosition 是「已经请求进内存的水位」，
             * 而真实落盘可能只到一半 —— 两者差值就是虚假区间：
             * 条上看着有、拖过去却卡。
             *
             * 现在：
             *  · 从播放位置换算成字节偏移
             *  · 查 BlockStore 里「从这个偏移往后连续有多少完整块」
             *  · 换算回毫秒 → 这才是能秒开的真实范围
             *  · 一段都没有（0）就返回当前位置，条不画
             */
            /**
             * 用户口径（2026-11-27）**缓冲条 = SimpleCache 里真实落盘的 span 范围**。
             *
             * 数据源是官方缓存的 getCachedSpans（真实写进磁盘的区间），
             * 不是播放器请求水位，也不是我们估算的量。
             * 所以：条画到哪，那里就一定有数据，拖过去必然秒开。
             */
            val cachedMs = run {
                val posMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
                val durMs = if (durationMs > 0) durationMs
                    else if (serverDurationMs > 0) serverDurationMs
                    else (player?.duration ?: 0L)
                val totalB = currentLength
                if (durMs <= 0 || totalB <= 0) {
                    posMs
                } else {
                    val posBytes = (totalB.toDouble() * posMs.toDouble() / durMs.toDouble()).toLong()
                    val ahead = xyz.etkn.mobile.player.cache.VideoCache
                        .availableBytesFrom(currentItem.id, posBytes, totalB - posBytes)
                    if (ahead <= 0) {
                        posMs
                    } else {
                        val aheadMs = (durMs.toDouble() * ahead.toDouble() / totalB.toDouble()).toLong()
                        posMs + aheadMs
                    }
                }
            }
            /**
             * 用户口径（2026-11-24）**气泡不亮的根因就在这里**。
             *
             * currentComplete 原来只由 pullWindow 的返回值决定，
             * 而 pullWindow 在「窗口已够、本轮没下载」时返回 false ——
             * 缓冲条都铺到片尾了，currentComplete 还是 false，气泡永远不亮。
             *
             * 现在按真实缓冲位置直接判定：
             * 当前集已缓冲到的毫秒数，是否已经到（或非常接近）片尾。
             * 阈值 2 秒：留一点余量，避免因字节换算误差永远差几百毫秒。
             */
            if (durationMs > 0 && cachedMs >= durationMs - 2_000) {
                PlaybackCache.markCurrentComplete(true)
            }
            _ui.update {
                it.copy(
                    cachedPosition = cachedMs,
                    nextPreloaded = PlaybackCache.isNextReady(),
                )
            }
        }
    }


    /**
     * 用户口径（2026-11-23）：直接用本地缓存文件起播（零网络）。
     *
     * 不经过 PlaybackInfo —— 命中完整缓存时直链根本用不上。
     * 播放器内核仍然按设置选（默认 Exo），数据源已按 scheme 分发，
     * file:// 会走 FileDataSource。
     */
    private fun playLocalFile(base: Item, file: java.io.File, startMs: Long) {
        val uri = Uri.fromFile(file)
        val engineWant = engineForLocal()
        val p = runCatching { ensurePlayer(engineWant) }.getOrNull() ?: return
        val media = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(base.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(_ui.value.title)
                    .setSubtitle(_ui.value.subtitle)
                    .build(),
            )
            .build()
        android.util.Log.i(TAG, "playLocal uri=$uri on ${engine?.label}")
        p.setMediaItem(media, startMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
        startProgressLoop()
    }

    /** 本地文件起播时内核选择：尊重设置，Auto 一律 Exo（与 engineFor 保持一致）。 */
    private fun engineForLocal(): Engine = when (settings.engine) {
        PlayerEngine.Exo -> Engine.Exo
        PlayerEngine.Mpv -> Engine.Mpv
        PlayerEngine.Auto -> Engine.Exo
    }

    /** 当前集缓存完成后，取下一集直链并交给流水线。 */
    private fun startNextIfIdle(current: Item) {
        // 「预加载下一集」开关：只管下一集，和当前集的缓存窗口无关
        if (!settings.preloadNext) {
            android.util.Log.i(TAG, "preload skip: 开关关闭")
            return
        }
        /**
         * 用户口径（2026-11-27）**每一步都要有日志**，否则「气泡不亮」无法定位。
         * 之前日志里只有 next=null，看不出是「没取到下一集」还是「取了但没预加载」。
         */
        val nextItem = _ui.value.next
        if (nextItem == null) {
            android.util.Log.i(TAG, "preload skip: next 为空（nextEpisode 没返回，可能本片没有下一集）")
            return
        }
        // 已经取过这一集的直链就不再重复取
        if (nextUrl?.first == nextItem.id) return
        if (nextFetching) return
        nextFetching = true
        scope.launch {
            runCatching {
                val info = api.playbackInfo(nextItem.id)
                val source = info.sources.firstOrNull() ?: return@runCatching
                val url = PlaybackAddress.resolve(source, session.server, session.token, session.userId)
                nextUrl = nextItem.id to url
                /**
                 * 用户口径（2026-11-24）：把下一集的片头结束位置和总时长记下来，
                 * 供「片头优先」的预加载量计算（片头后 1 分钟不受配额限制）。
                 */
                // chapters / runTimeTicks 都在 MediaSource 上，不在 PlaybackInfo
                nextIntroEndMs = source.chapters
                    .firstOrNull { it.markerType == "IntroEnd" }
                    ?.startTicks?.div(TICKS_PER_MS) ?: -1L
                nextDurationMs = source.runTimeTicks.takeIf { it > 0 }?.div(TICKS_PER_MS) ?: 0L
                /**
                 * 用户口径（2026-11-27）**这里只负责「取下一集直链」，绝不在这里开始预加载**。
                 *
                 * 修的 bug（用户截图实证）：「刚播放就把下一集给预加载出来了？」
                 * —— 1:52 / 33:04 刚开始播，下一集就已经在下了。
                 *
                 * 根因：这里原来直接调 PlaybackCache.pipeline(...)，
                 * 而 tickCache 是每秒调一次、只要 nextUrl==null 就会进来，
                 * 于是起播那一秒就绕过了 shouldPreload 判断，立刻开始铺下一集。
                 *
                 * 现在：本函数只取直链 + 片头/时长信息，写进 nextUrl / nextIntroEndMs。
                 * 真正什么时候铺，由 tickCache 里的 shouldPreload 统一决定
                 * （缓冲到片尾 或 剩余不足 5 分钟）。
                 */
            }
            nextFetching = false
        }
    }

    /**
     * 用户口径（2026-11-23）：播放器底部要有「上一集」。
     * ETKN 没有 /PreviousEpisode（404），所以用 EpisodePage 拉整季列表，
     * 找到当前集在列表里的位置，取前一个。
     */
    fun playPrevious() {
        val current = item ?: return
        if (!current.isEpisode) return
        val seriesId = current.seriesId ?: return
        stopReporting()
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                val page = api.episodePage(seriesId, current.seasonId, limit = 200)
                val list = page.items
                val index = list.indexOfFirst { it.id == current.id }
                val prev = if (index > 0) list[index - 1] else null
                if (prev == null) {
                    _ui.update { it.copy(error = "已经是第一集") }
                    return@launch
                }
                load(prev.id, if (prev.userData.played) 0 else prev.positionMs, null, prev)
            } catch (error: Exception) {
                _ui.update { it.copy(loading = false, error = error.message ?: "无法播放上一集") }
            }
        }
    }

    fun playNext(completed: Boolean = false) {
        val next = _ui.value.next ?: return
        /**
         * 用户口径（2026-11-26）：顺序很重要 ——
         * 先把「是否播完」写进去，再 stopReporting() 让 report("Stopped") 立刻读到；
         * report 内部已经把值快照成局部变量，所以后面 playSource 重置这个标志也不会影响已发出的请求。
         */
        completedBySkip = completed
        stopReporting()
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                load(next.id, if (next.userData.played) 0 else next.positionMs, null, next)
            } catch (error: Exception) {
                _ui.update { it.copy(loading = false, error = error.message ?: "无法播放下一集") }
            }
        }
    }

    fun playEpisode(episode: Item) {
        if (episode.id == item?.id) return
        stopReporting()
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                load(episode.id, if (episode.userData.played) 0 else episode.positionMs, null, episode)
            } catch (error: Exception) {
                _ui.update { it.copy(loading = false, error = error.message ?: "无法播放") }
            }
        }
    }

    private fun onEnded() {
        completedBySkip = true
        /**
         * 用户口径（2026-11-23）：**「播完清掉该集缓存」之前是摆设**。
         *
         * PlaybackCache.afterEpisodeFinished() 全工程零调用，
         * 所以设置里的开关打开也不会删任何文件。
         * 现在在看完整集（或自动跳下一集）时真正调一次。
         */
        runCatching {
            // PlaybackCache 是 object（单例），这里直接走静态调用
            PlaybackCache.afterEpisodeFinished(context, settings, _ui.value.item?.id)
        }
        val sleeping = _ui.value.sleep == SleepTimer.EndOfItem
        if (sleeping) setSleepTimer(null)
        if (!sleeping && settings.autoPlayNext && _ui.value.next != null) playNext(completed = true)
        else {
            stopReporting()
            _ui.update { it.copy(finished = true) }
        }
    }

    // ---------- Skip segments ----------

    private fun segments(chapters: List<xyz.etkn.mobile.data.model.Chapter>, durationMs: Long): List<SkipSegment> {
        val out = ArrayList<SkipSegment>(2)
        val introStart = chapters.firstOrNull { it.markerType == "IntroStart" }?.startTicks?.div(TICKS_PER_MS)
        val introEnd = chapters.firstOrNull { it.markerType == "IntroEnd" }?.startTicks?.div(TICKS_PER_MS)
        if (introEnd != null && introEnd > (introStart ?: 0)) out += SkipSegment(SkipSegment.Kind.Intro, introStart ?: 0, introEnd)
        val credits = chapters.firstOrNull { it.markerType == "CreditsStart" }?.startTicks?.div(TICKS_PER_MS)
        if (credits != null && credits > 0) out += SkipSegment(SkipSegment.Kind.Credits, credits, if (durationMs > 0) durationMs else Long.MAX_VALUE)
        return out
    }

    /** Segment active at [positionMs] (pure; safe to call during composition). */
    fun segmentAt(positionMs: Long): SkipSegment? {
        val ui = _ui.value
        if (ui.skipMode == SkipMode.Off) return null
        // 用户口径（2026-11-23）：原来要求「距结束至少 1 秒」才算在段内，
        // 结果续播点恰好落在最后 1 秒时不显示按钮、也不跳。容差收到 300ms，
        // 并且允许当前位置正好等于结束点之前一点点就算命中。
        return ui.segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 300 }
    }

    /**
     * Auto mode：片头、片尾都自动跳（用户口径 2026-11-23）。
     * 原来只处理 Intro，所以片尾永远只显按钮不跳。
     * Credits 自动跳同样只触发一次，避免播完又回跳。
     */
    fun autoSkip(segment: SkipSegment): Boolean {
        if (_ui.value.skipMode != SkipMode.Auto) return false
        return when (segment.kind) {
            SkipSegment.Kind.Intro -> {
                if (autoSkippedIntro) return false
                autoSkippedIntro = true
                player?.seekTo(segment.endMs)
                true
            }
            SkipSegment.Kind.Credits -> {
                if (autoSkippedCredits) return false
                autoSkippedCredits = true
                skip(segment)
                true
            }
        }
    }

    /**
     * 用户口径（2026-11-23）：用户在 5 秒倒计时期间点了「取消」。
     * 表示这一段这次不要自动跳 —— 把已跳标记置上，倒计时就不会再触发。
     * 拖动进度回到片头之前会由 resetAutoSkipIfBefore 复位，之后仍然能自动跳。
     */
    fun cancelAutoSkip(segment: SkipSegment) {
        when (segment.kind) {
            SkipSegment.Kind.Intro -> autoSkippedIntro = true
            SkipSegment.Kind.Credits -> autoSkippedCredits = true
        }
    }

    /**
     * 用户口径（2026-11-23）：手动拖回片头前（或重播）时，重置「已自动跳过」标记，
     * 这样再进片头还会自动跳一次。原来该标记一旦置 true 本视频内永不复位。
     */
    fun resetAutoSkipIfBefore(positionMs: Long) {
        val introStart = _ui.value.segments.firstOrNull { it.kind == SkipSegment.Kind.Intro }?.startMs ?: return
        if (positionMs <= introStart + 1_000) autoSkippedIntro = false
        val creditsStart = _ui.value.segments.firstOrNull { it.kind == SkipSegment.Kind.Credits }?.startMs ?: return
        if (positionMs <= creditsStart + 1_000) autoSkippedCredits = false
    }

    fun skip(segment: SkipSegment) {
        val player = player ?: return
        when (segment.kind) {
            SkipSegment.Kind.Intro -> player.seekTo(segment.endMs)
            SkipSegment.Kind.Credits -> if (_ui.value.next != null) playNext(completed = true) else player.seekTo(player.duration.coerceAtLeast(0))
        }
    }

    // ---------- Tracks ----------

    private fun publishTracks(tracks: Tracks) {
        val src = source
        val audioStreams = src?.audio.orEmpty()
        val textStreams = src?.subtitles?.filterNot { it.isExternal }.orEmpty()
        val audio = ArrayList<TrackOption>()
        val text = ArrayList<TrackOption>()
        var audioOrdinal = 0
        var textOrdinal = 0
        tracks.groups.forEachIndexed { g, group ->
            for (t in 0 until group.length) {
                if (!group.isTrackSupported(t, true)) continue
                val format = group.getTrackFormat(t)
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> {
                        if (format.id in failedFormats) continue
                        val stream = discStream(format, audioStreams) ?: audioStreams.getOrNull(audioOrdinal++)
                        audio += TrackOption(g, t, audioLabel(format, stream), group.isTrackSelected(t))
                    }
                    C.TRACK_TYPE_TEXT -> {
                        val external = format.id?.contains("ext-") == true
                        val stream = if (external) null else discStream(format, textStreams) ?: textStreams.getOrNull(textOrdinal++)
                        val server = stream ?: src?.subtitles?.firstOrNull { external && format.id?.endsWith("ext-${it.index}") == true }
                        text += TrackOption(g, t, textLabel(format, stream), group.isTrackSelected(t), server)
                    }
                }
            }
        }
        val textDisabled = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        _ui.update { it.copy(audio = audio, text = text, textEnabled = !textDisabled && text.any { o -> o.selected }) }
        followSubtitleOffset(text.firstOrNull { it.selected }?.stream?.takeUnless { textDisabled })
    }

    /**
     * Disc tracks carry their PID (`etkn-ts-<pid>-<mime>`); the server's disc probe reports the same
     * PID as `TransportStreamId`, so labels never depend on track order. TrueHD and its AC3 core
     * share a PID, so the codec family must agree too.
     */
    private fun discStream(format: Format, streams: List<MediaStream>): MediaStream? {
        val pid = BlurayMediaSourceFactory.discPid(format.id).takeIf { it > 0 } ?: return null
        val matches = streams.filter { it.transportStreamId == pid }
        if (matches.size <= 1) return matches.firstOrNull()
        return matches.firstOrNull { sameCodec(it.codec, format.sampleMimeType) }
    }

    /**
     * 用户口径（2026-11-27）**设备到底能不能解这个视频编码**（真实查询，不猜）。
     *
     * 为什么不能靠编码白名单：
     *  · 不同机器支持的编码不一样（这台能解 msmpeg4v2，那台可能不能）
     *  · 系统还分硬解/软解/FFmpeg 扩展，硬编码名单必然漏
     *
     * 用 Android 官方的 MediaCodecList 查：有任何一个解码器支持这个 mime 就算能解。
     * 查不到 → 交给 mpv（它自带完整的 FFmpeg 解码器集合）。
     */
    private fun canDecode(mimeType: String): Boolean {
        if (mimeType.isBlank()) return false
        return runCatching {
            val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        }.getOrDefault(true)   // 查询本身失败时假定能解（不因此误切内核）
    }

    private fun sameCodec(codec: String, mime: String?): Boolean = when (codec.lowercase(Locale.ROOT)) {
        "truehd", "mlp" -> mime == MimeTypes.AUDIO_TRUEHD
        "ac3" -> mime == MimeTypes.AUDIO_AC3
        "eac3" -> mime == MimeTypes.AUDIO_E_AC3
        else -> codec.lowercase(Locale.ROOT).startsWith("dts") && mime == MimeTypes.AUDIO_DTS_HD
    }

    /** Per-PID facts from the server's disc probe (size, channels) for gaps the demuxer leaves. */
    private fun discHints(chosen: MediaSource): Map<Int, DiscHint> =
        chosen.streams.filter { it.transportStreamId > 0 }
            .associate { it.transportStreamId to DiscHint(it.width, it.height, it.channels) }

    /**
     * Starts on the server's default audio and subtitle, which follow the account's language
     * priority (默认音轨 / 默认字幕); without one, the subtitle the source marks as default. Disc
     * tracks are matched by PID, external subtitles by their sideloaded id, the rest by their
     * order among the file's own streams; `-1` turns subtitles off.
     *
     * Each type is settled once its track exists: mpv adds external subtitles only after the file
     * has loaded, so the first track list may not have them yet. A pick by the user settles it too.
     */
    private fun applyServerTracks(tracks: Tracks) {
        if (pendingDefaults.isEmpty() || tracks.isEmpty) return
        val src = source ?: return
        val player = player ?: return
        val params = player.trackSelectionParameters.buildUpon()
        var changed = false
        fun settle(type: Int, wanted: MediaStream?, streams: List<MediaStream>) {
            if (type !in pendingDefaults) return
            if (wanted == null) {
                pendingDefaults -= type
                return
            }
            val (group, index) = findTrack(tracks, type, streams, wanted) ?: return // not in the player yet
            pendingDefaults -= type
            if (!group.isTrackSupported(index) || group.isTrackSelected(index)) return
            params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)).setTrackTypeDisabled(type, false)
            changed = true
        }
        settle(C.TRACK_TYPE_AUDIO, src.audio.firstOrNull { it.index == src.defaultAudioIndex }, src.audio)
        val subtitle = src.defaultSubtitleIndex ?: src.subtitles.firstOrNull { it.isDefault }?.index
        if (subtitle == -1 && C.TRACK_TYPE_TEXT in pendingDefaults) {
            pendingDefaults -= C.TRACK_TYPE_TEXT
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            changed = true
        }
        settle(C.TRACK_TYPE_TEXT, src.subtitles.firstOrNull { it.index == subtitle }, src.subtitles)
        if (changed) player.trackSelectionParameters = params.build()
    }

    /** The player's track for server stream [wanted] (one of [streams], the source's streams of that type). */
    private fun findTrack(tracks: Tracks, type: Int, streams: List<MediaStream>, wanted: MediaStream): Pair<Tracks.Group, Int>? {
        val ordinal = streams.filterNot { it.isExternal }.indexOf(wanted)
        var seen = 0
        for (group in tracks.groups) {
            if (group.type != type) continue
            for (t in 0 until group.length) {
                val format = group.getTrackFormat(t)
                val external = format.id?.contains("ext-") == true
                when {
                    wanted.isExternal -> if (format.id?.endsWith("ext-${wanted.index}") == true) return group to t
                    external -> Unit
                    BlurayMediaSourceFactory.discPid(format.id) > 0 -> if (discStream(format, listOf(wanted)) != null) return group to t
                    seen++ == ordinal -> return group to t
                }
            }
        }
        return null
    }

    /**
     * An audio decoder that cannot start (or decode) should not cost the whole film: move to the
     * next playable audio track — same language first — and resume where playback stopped.
     */
    private fun recoverAudio(error: PlaybackException): Boolean {
        val failure = error as? ExoPlaybackException ?: return false
        if (failure.type != ExoPlaybackException.TYPE_RENDERER) return false
        val format = failure.rendererFormat ?: return false
        if (MimeTypes.getTrackType(format.sampleMimeType) != C.TRACK_TYPE_AUDIO) return false
        failedFormats += format.id ?: return false
        val candidates = lastTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) && group.getTrackFormat(it).id !in failedFormats }.map { group to it }
        }
        val (group, index) = candidates.firstOrNull { (g, t) -> g.getTrackFormat(t).language == format.language }
            ?: candidates.firstOrNull() ?: return false
        val player = player ?: return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
        player.prepare()
        showNotice("${codecName(format.sampleMimeType) ?: "该"}音轨无法解码，已切换音轨")
        return true
    }

    fun selectTrack(option: TrackOption) {
        val player = player ?: return
        val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        pendingDefaults -= group.type
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .setTrackTypeDisabled(group.type, false)
            .build()
    }

    fun disableSubtitles() {
        val player = player ?: return
        pendingDefaults -= C.TRACK_TYPE_TEXT
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    // ---------- Hand-off ----------

    /** The playing version as a self-contained URL with its title, for another player or device. */
    fun portableStream(): Pair<String, String>? {
        val chosen = source ?: return null
        val url = runCatching { PlaybackAddress.portable(chosen, session.server, session.token, session.userId) }.getOrNull() ?: return null
        return url to listOf(_ui.value.title, _ui.value.subtitle).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    // ---------- Cast ----------

    private var castDevice: CastDevice? = null

    /** Hands the playing version to a renderer at the current position and pauses here. */
    fun castTo(device: CastDevice) {
        val (url, title) = portableStream() ?: return
        val position = player?.currentPosition ?: 0L
        scope.launch {
            runCatching { graph.cast.play(device, url, title, position) }
                .onSuccess {
                    castDevice = device
                    player?.pause()
                    _ui.update { it.copy(castingTo = device.name) }
                    showNotice("已投屏到 ${device.name}")
                }
                .onFailure { showNotice(it.message ?: "投屏失败") }
        }
    }

    fun stopCast() {
        val device = castDevice ?: return
        castDevice = null
        _ui.update { it.copy(castingTo = null) }
        graph.scope.launch { graph.cast.stop(device) }
    }

    // ---------- Sleep timer ----------

    private var sleepJob: Job? = null

    /** Pauses after the chosen time, or lets the current title end without moving on; null cancels. */
    fun setSleepTimer(timer: SleepTimer?) {
        sleepJob?.cancel()
        _ui.update { it.copy(sleep = timer) }
        if (timer == null || timer.minutes == 0) return
        sleepJob = scope.launch {
            delay(timer.minutes * 60_000L)
            player?.pause()
            _ui.update { it.copy(sleep = null) }
            showNotice("定时已到，已暂停")
        }
    }

    // ---------- Subtitle offset ----------

    /** Offsets chosen this session, by key: re-selecting a subtitle keeps its calibration. */
    private val offsets = HashMap<String, Int>()

    /** Applies the selected subtitle's calibration (session pick, else the server's saved value). */
    private fun followSubtitleOffset(stream: MediaStream?) {
        val mpv = player as? MpvPlayer
        if (mpv == null || stream == null || stream.offsetKey.isEmpty() || stream.burnedIn) {
            _ui.update { it.copy(subtitleOffset = null) }
            return
        }
        val shown = _ui.value.subtitleOffset
        if (shown?.key == stream.offsetKey) return
        val ms = offsets[stream.offsetKey] ?: stream.offsetMs
        mpv.setSubtitleDelay(ms)
        _ui.update { it.copy(subtitleOffset = SubtitleOffset(stream.offsetKey, ms, stream.offsetMs)) }
    }

    fun shiftSubtitles(deltaMs: Int) {
        val current = _ui.value.subtitleOffset ?: return
        val ms = (current.ms + deltaMs).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
        offsets[current.key] = ms
        (player as? MpvPlayer)?.setSubtitleDelay(ms)
        _ui.update { it.copy(subtitleOffset = current.copy(ms = ms)) }
    }

    /** Stores the current calibration on the server for this version (only on the user's word). */
    fun saveSubtitleOffset() {
        val current = _ui.value.subtitleOffset ?: return
        val itemId = item?.id ?: return
        val sourceId = source?.id ?: return
        scope.launch {
            runCatching { api.saveSubtitleOffset(itemId, sourceId, current.key, current.ms) }
                .onSuccess {
                    _ui.update { ui -> ui.copy(subtitleOffset = ui.subtitleOffset?.takeIf { it.key == current.key }?.copy(saved = current.ms)) }
                    showNotice("字幕偏移已保存")
                }
                .onFailure { showNotice(it.userMessage()) }
        }
    }

    fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        _ui.update { it.copy(speed = speed) }
    }

    // ---------- Danmaku ----------

    /** Starts or stops the feed to match server availability and the local switch. */
    private fun applyDanmaku() {
        val available = danmakuAvailable
        val wanted = available == true && _danmakuStyle.value.enabled && playSession.isNotEmpty() && item != null
        if (!wanted) closeDanmaku()
        val player = player
        if (wanted && danmakuFeed == null && player != null) {
            val current = item!!
            danmakuFeed = DanmakuFeed(
                api = api, scope = scope, player = player, itemId = current.id, playSession = playSession,
                onCues = { _danmakuCues.value = it },
                onNotice = ::showNotice,
                onServiceOff = { danmakuAvailable = false; applyDanmaku() },
            )
        }
        _ui.update { it.copy(danmakuSupported = available != null, danmakuOn = danmakuFeed != null) }
    }

    private fun closeDanmaku() {
        danmakuFeed?.close()
        danmakuFeed = null
        _danmakuCues.value = emptyList()
    }

    /** Persists the style; switching on re-checks the server when it last said the service was off. */
    fun setDanmakuStyle(style: DanmakuStyle) {
        val turningOn = style.enabled && !_danmakuStyle.value.enabled
        _danmakuStyle.value = style
        graph.scope.launch { graph.settings.setDanmaku(style) }
        if (turningOn && danmakuAvailable != true) {
            val expected = playSession
            scope.launch {
                val available = runCatching { api.danmakuAvailable() }.getOrDefault(false)
                if (expected != playSession) return@launch
                danmakuAvailable = available
                if (!available) showNotice("请在服务端增强功能中启用弹幕服务")
                applyDanmaku()
            }
        } else {
            applyDanmaku()
        }
    }

    private fun showNotice(message: String) = _ui.update { it.copy(notice = message) }

    fun clearNotice(message: String) = _ui.update { if (it.notice == message) it.copy(notice = null) else it }

    // ---------- Reporting ----------

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(10_000)
                if (started && player?.isPlaying == true) report("Progress")
            }
        }
    }

    private fun stopReporting() {
        progressJob?.cancel()
        /**
         * 用户口径（2026-11-26）：只要已经起播过（started），退出/切集就要发一次 Stopped，
         * 把当前进度落到服务端。这样「播了几集后退出」每一集都有各自的记录。
         */
        if (source != null && !stopped && started) report("Stopped")
        stopped = true
    }

    /**
     * 用户口径（2026-11-26）**修「退出后进度不同步 / 播了好几集还停在旧集」**。
     *
     * 问题在这两处：
     *  ① `report()` 内部异步（graph.scope）执行，但 payload 是在调用时同步构造的，
     *     这一层没问题；真正的问题是下面第 ② 点会让「播完」标记丢失。
     *  ② `positionMs` / `durationMs` 在**切集瞬间**读 —— 此时 player 可能已经绑到新集，
     *     读出来是新集的 0 秒，于是上一集被上报成「播到 0 秒」，
     *     记录看起来就像「停在很早的位置」。
     *
     * 现在：把这一刻的位置/时长/播完标记全部**先快照**成局部变量，
     * 后续异步协程只用快照，不再回头读可能已经变了的 player / 标志位。
     */
    private fun report(event: String) {
        if (offline) return
        val current = item ?: return
        val src = source ?: return
        val player = player ?: return
        // 快照：位置、时长、播完标记（切集时这些都会被改掉）
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
        val playedToEnd = event == "Stopped" && completedBySkip
        val payload = buildJsonObject {
            put("ItemId", current.id)
            put("UserId", session.userId)
            put("MediaSourceId", src.id)
            put("PlaySessionId", playSession)
            put("PositionTicks", positionMs * TICKS_PER_MS)
            put("RunTimeTicks", durationMs?.times(TICKS_PER_MS) ?: src.runTimeTicks)
            put("IsPaused", !player.isPlaying)
            put("CanSeek", true)
            put("PlayMethod", "DirectStream")
            put("Client", AppGraph.CLIENT_NAME)
            put("TranscodeSessionId", "")
            if (playedToEnd) put("PlayedToCompletion", true)
            if (event != "Stopped" && danmakuAvailable == true && _danmakuStyle.value.enabled) put("DanmakuActive", true)
        }
        val expected = playSession
        // Application scope: the Stopped report must outlive the player screen.
        graph.scope.launch {
            /**
             * 用户口径（2026-11-26）**上报成功后立刻通知首页刷新续播**。
             *
             * 首页原来只在 onResume 里刷续播，还有 60 秒节流 ——
             * 播完一集马上回来时窗口经常没过，于是「继续观看」还是旧的，
             * 必须手动下拉整页才变。
             *
             * 这里在 Stopped 真正发出去之后发一个事件，
             * 首页收到就无条件刷续播（不再看节流窗口）。
             */
            val replied = runCatching { api.report(event, payload, session) }.getOrNull()
            if (event == "Stopped" && replied != null) {
                runCatching { graph.playbackChanged.tryEmit(Unit) }
            }
            val state = replied?.obj("Danmaku") ?: return@launch
            if (event != "Stopped" && expected == playSession && !released) {
                danmakuAvailable = state.bool("Available")
                applyDanmaku()
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        scope.cancel()
        closeDanmaku()
        stopReporting()
        val core = player
        _player.value = null
        core?.removeListener(listener)
        // Stop before release: playback must end with the screen even if a core's release fails.
        runCatching { core?.stop() }.onFailure { Log.e(TAG, "stop on release", it) }
        runCatching { core?.release() }.onFailure { Log.e(TAG, "release", it) }
    }

    /**
     * Media3's code alone hides the reason; the disc reader and transports throw IOExceptions
     * with a user-facing message (Range unsupported, encrypted disc, no main title …).
     */
    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error.cause
        var detail: String? = null
        while (cause != null && detail == null) {
            detail = cause.message?.takeIf { message -> message.any { it.code > 0x2E7F } }
            cause = cause.cause
        }
        if (detail != null) return "播放失败：$detail"
        // Renderer failures name the stream and the decoder, so a report says what the device lacks.
        val renderer = error as? ExoPlaybackException
        val format = renderer?.rendererFormat
        if (renderer?.type == ExoPlaybackException.TYPE_RENDERER && format != null) {
            val decoder = generateSequence(error.cause) { it.cause }
                .filterIsInstance<MediaCodecRenderer.DecoderInitializationException>().firstOrNull()?.codecInfo?.name
                ?: renderer.rendererName
            val stream = listOfNotNull(
                format.sampleMimeType,
                format.codecs,
                "${format.width}×${format.height}".takeIf { format.width > 0 },
                "${format.channelCount}ch".takeIf { format.channelCount > 0 },
            ).joinToString(" ")
            return "播放失败：$stream 无法解码（${listOfNotNull(decoder, error.errorCodeName).joinToString(" · ")}）"
        }
        return "播放失败（${error.errorCodeName}）"
    }

    // ---------- Labels ----------

    private fun audioLabel(format: Format, stream: MediaStream?): String {
        stream?.previewLabel?.takeIf { it.isNotBlank() }?.let { return it }
        val parts = listOfNotNull(
            format.label ?: format.language?.let { Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } },
            codecName(format.sampleMimeType),
            format.channelCount.takeIf { it > 0 }?.let { if (it > 2) "${it - 1}.1" else if (it == 2) "立体声" else "单声道" },
        )
        return parts.joinToString(" · ").ifEmpty { "音轨" }
    }

    private fun textLabel(format: Format, stream: MediaStream?): String {
        stream?.let { s -> s.title.ifEmpty { s.displayTitle }.takeIf { it.isNotBlank() }?.let { return it } }
        return format.label ?: format.language?.let { Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } } ?: "字幕"
    }

    private fun codecName(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AC3 -> "DD"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "DD+"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        else -> null
    }

    private fun subtitleMime(codec: String): String? = when (codec.lowercase()) {
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }
}
