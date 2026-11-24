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
    /** 下一集的 (episodeId, url)，用于流水线预加载。 */
    private var nextUrl: Pair<String, String>? = null
    /**
     * 用户口径（2026-11-24）：下一集的片头结束位置与总时长。
     * 预加载量要用「片头结束 + 1 分钟」做下限（不受配额限制），
     * 没有片头数据时 introEndMs = -1，退回纯配额。
     */
    private var nextIntroEndMs: Long = -1L
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
            applyServerTracks(tracks)
            publishTracks(tracks)
        }

        override fun onEvents(player: Player, events: Player.Events) {
            danmakuFeed?.tick()
        }

        override fun onPlayerError(error: PlaybackException) {
            val current = source
            val position = player?.currentPosition ?: 0L
            // An ISO the server did not mark as one: give the disc reader one try.
            if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && !discTried && current != null) {
                playSource(current, position, disc = true)
                return
            }
            Log.e(TAG, "playback error on ${engine?.label} ${error.errorCodeName}", error)
            if (engine == Engine.Exo && recoverAudio(error)) return
            // One core gave up: the other plays the same source from the same place.
            val failed = engine
            if (current != null && forcedEngine == null && failed != null && !discTried) {
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
    private fun engineFor(chosen: MediaSource, disc: Boolean): Engine {
        forcedEngine?.let { return it }
        if (disc) return Engine.Exo
        return when (settings.engine) {
            PlayerEngine.Exo -> Engine.Exo
            PlayerEngine.Mpv -> Engine.Mpv
            // Auto：一律先用 ExoPlayer，失败再自动退 mpv
            PlayerEngine.Auto -> Engine.Exo
        }
    }

    /** Swaps cores only when the wanted one differs; an mpv that cannot start falls back to ExoPlayer. */
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
            runCatching { Engine.Mpv to MpvPlayer(context, graph.identity.userAgent, settings.decoder, PlayerFactory.preferredLanguages().toList()) }
                .onFailure { Log.e(TAG, "mpv unavailable", it) }
                .getOrNull()
                ?: (Engine.Exo to PlayerFactory.video(context, graph.http.media, graph.identity.userAgent, settings.decoder, settings))
        } else {
            Engine.Exo to PlayerFactory.video(context, graph.http.media, graph.identity.userAgent, settings.decoder, settings)
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
        if (base.isEpisode) loadNext(base.id)
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

    private fun playSource(chosen: MediaSource, startMs: Long, disc: Boolean = PlaybackAddress.isDisc(chosen)) {
        val current = item ?: return
        if (source !== chosen) {
            discTried = false
            forcedEngine = null
            failedFormats.clear()
            lastTracks = Tracks.EMPTY
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
        nextUrl = null
        PlaybackCache.clearReady()
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
        val cachedLocal = if (!disc) PlaybackCache.localFileById(context, current.id) else null
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
        val playUri: android.net.Uri = run {
            val local = cachedLocal
            if (local != null) {
                // file:// 必须是绝对路径且不能带查询串；用 fromFile 生成后再校验一次
                val u = android.net.Uri.fromFile(local)
                val bad = !u.scheme.equals("file", true) || u.path.isNullOrEmpty()
                if (bad) url.let { android.net.Uri.parse(it) } else u
            } else {
                android.net.Uri.parse(url)
            }
        }
        android.util.Log.i(TAG, "playUri=${playUri} (local=${cachedLocal?.absolutePath ?: "none"})")
        val media = MediaItem.Builder()
            .setUri(playUri)
            .setMediaId(current.id)
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
         * 用户口径（2026-11-24）**缓冲窗口是滑动窗口，必须紧跟播放位置**。
         *
         * 之前有两个前置条件（播放 >15s、内核前向缓冲 >30s）会直接 return，
         * 导致起播后一大段时间完全不缓冲、缓冲条不动 → 用户看到的「逻辑很乱」。
         * 现在去掉这两个门槛：只要开播就推进窗口。
         * 带宽冲突由「每次 tick 只拉一个 chunk」自然缓解（见 pullWindow）。
         */
        if (positionMs <= 0) return

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
            ).takeIf { it.itemId.isNotEmpty() && it.url.isNotEmpty() }

            val job = PlaybackCache.pipeline(
                client = graph.http.media,
                context = graph.app,
                settings = settings,
                currentEpisodeId = currentItem.id,
                currentUrl = cur,
                currentPositionMs = positionMs,
                currentDurationMs = durationMs,
                currentLength = length,
                next = nextInfo,
            )
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
            val cachedMs = PlaybackCache.cachedPositionMs(
                context = graph.app,
                itemId = currentItem.id,
                durationMs = durationMs,
                totalBytes = currentLength,
            )
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
        if (!settings.preloadNext) return
        val nextItem = _ui.value.next ?: return
        if (nextUrl?.first == nextItem.id) return
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
                PlaybackCache.pipeline(
                    client = graph.http.media,
                    context = graph.app,
                    settings = settings,
                    currentEpisodeId = current.id,
                    currentUrl = currentUrl ?: return@runCatching,
                    currentPositionMs = player?.currentPosition ?: 0L,
                    currentDurationMs = player?.duration?.takeIf { it > 0 } ?: 0L,
                    currentLength = currentLength,
                    next = PlaybackCache.NextEpisode(
                        itemId = nextItem.id,
                        url = url,
                        introEndMs = nextIntroEndMs,
                        durationMs = nextDurationMs,
                    ),
                )
            }
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
        if (source != null && !stopped && started) report("Stopped")
        stopped = true
    }

    private fun report(event: String) {
        if (offline) return
        val current = item ?: return
        val src = source ?: return
        val player = player ?: return
        val positionMs = player.currentPosition.coerceAtLeast(0)
        val durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
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
            if (event == "Stopped" && completedBySkip) put("PlayedToCompletion", true)
            if (event != "Stopped" && danmakuAvailable == true && _danmakuStyle.value.enabled) put("DanmakuActive", true)
        }
        val expected = playSession
        // Application scope: the Stopped report must outlive the player screen.
        graph.scope.launch {
            val reply = runCatching { api.report(event, payload, session) }.getOrNull() ?: return@launch
            val state = reply.obj("Danmaku") ?: return@launch
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
