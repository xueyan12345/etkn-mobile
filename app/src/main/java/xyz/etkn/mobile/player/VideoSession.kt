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
    private val settings: Settings
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
        settings = kotlinx.coroutines.runBlocking { graph.settings.flow.first() }
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

    /** Discs and HDR / Dolby Vision stay on ExoPlayer: it outputs HDR, mpv's GL path tone-maps. */
    private fun engineFor(chosen: MediaSource, disc: Boolean): Engine {
        forcedEngine?.let { return it }
        if (disc) return Engine.Exo
        return when (settings.engine) {
            PlayerEngine.Exo -> Engine.Exo
            PlayerEngine.Mpv -> Engine.Mpv
            PlayerEngine.Auto -> if (PlaybackAddress.isHdr(chosen)) Engine.Exo else Engine.Mpv
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
                ?: (Engine.Exo to PlayerFactory.video(context, graph.http.media, graph.identity.userAgent, settings.decoder))
        } else {
            Engine.Exo to PlayerFactory.video(context, graph.http.media, graph.identity.userAgent, settings.decoder)
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
        // 用户口径（2026-11-23）：原来是 startMs > 0 就标记「已跳过」，
        // 结果续播点落在片头内部时永远不跳。改成「续播点已越过片头结束点」才算跳过。
        // 注意此时 segments 还没算出来，先置 false，等 segments 生成后再判断。
        autoSkippedIntro = false
        autoSkippedCredits = false
        val url = PlaybackAddress.resolve(chosen, session.server, session.token, session.userId)
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
        val media = MediaItem.Builder()
            .setUri(url)
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
            if (item?.id == itemId) _ui.update { it.copy(next = next) }
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
