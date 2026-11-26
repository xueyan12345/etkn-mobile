package xyz.etkn.mobile.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.etkn.mobile.AppGraph
import xyz.etkn.mobile.data.api.ImageUrls
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.TICKS_PER_MS
import xyz.etkn.mobile.data.session.ActiveSession
import xyz.etkn.mobile.data.session.SessionState
import xyz.etkn.mobile.player.PlaybackAddress
import xyz.etkn.mobile.player.PlayerFactory
import java.util.concurrent.ConcurrentHashMap
import xyz.etkn.mobile.data.model.UserData

@Immutable
data class MusicState(
    val current: Item? = null,
    val queue: List<Item> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
    val sourceId: String? = null,
)

/**
 * In-process music engine shared by the UI and [MusicService]. Tracks are queued as
 * `etkn-music://<itemId>`; the stream address is resolved lazily on ExoPlayer's loader thread
 * via PlaybackInfo, so a 200-track album costs one request per track actually played.
 */
class MusicConnection(private val context: Context, private val graph: AppGraph) {
    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()
    private val _library = MutableStateFlow<String?>(null)
    val libraryId: StateFlow<String?> = _library.asStateFlow()

    private data class Resolved(val url: String, val playSession: String, val sourceId: String, val runTimeTicks: Long)

    private val resolved = ConcurrentHashMap<String, Resolved>()
    private val items = ConcurrentHashMap<String, Item>()
    private var reportedId: String? = null
    private var startedId: String? = null
    private var progressJob: Job? = null
    @Volatile private var session: ActiveSession? = null

    private var _player: ExoPlayer? = null
    val player: ExoPlayer
        get() = _player ?: create().also { _player = it }

    init {
        /**
         * 用户口径（#39）启动时把「音乐缓存大小」从设置同步进 MusicCache。
         *
         * 播放器可能在用户进设置页之前就创建了，所以这里主动读一次，
         * 否则第一次播放会用默认 2048MB，跟用户设的不一致。
         */
        graph.scope.launch {
            runCatching {
                graph.settings.flow.collect { st ->
                    MusicCache.setLimit(st.musicCacheMb)
                }
            }
        }
        graph.scope.launch {
            graph.sessions.state.collect { s ->
                val next = (s as? SessionState.SignedIn)?.session
                if (next?.key != session?.key) {
                    stop()
                    resolved.clear()
                    _library.value = null
                }
                session = next
            }
        }
    }

    fun selectLibrary(id: String) {
        _library.value = id
    }

    /**
     * 用户口径（2026-11-27，#29）**记住上次播放的歌和进度**。
     *
     * 用户原话：
     *  「我想把这个变成记忆状态，也就是上次播放的歌曲是啥，它就是啥，包括播放进度也要保存，
     *    上次播放一半，我彻底关闭 app 重新打开音乐界面，还是要显示上次播放的那个音乐，
     *    进度啥的都在，点播放就能继续播放」
     *
     * 存储只放**够画界面 + 够续播**的字段（不序列化整个 Item，因为 Item 不是 @Serializable）：
     *  · id / name / artist / album —— 迷你条要显示的
     *  · imageTag —— 封面（没有就显示占位图）
     *  · positionMs / durationMs —— 进度
     *  · sourceId —— 歌词用
     *  · queueJson 不需要：点播放时重新按 id 取直链即可
     */
    private val prefs by lazy { context.getSharedPreferences("etkn_music_memory", Context.MODE_PRIVATE) }

    private val _remembered = MutableStateFlow<MusicMemory?>(null)
    val remembered: StateFlow<MusicMemory?> = _remembered.asStateFlow()

    /** 启动时读一次；没有记录就是 null（界面显示占位图）。 */
    init {
        _remembered.value = runCatching {
            val id = prefs.getString("m_id", null) ?: return@runCatching null
            MusicMemory(
                itemId = id,
                name = prefs.getString("m_name", "") ?: "",
                artist = prefs.getString("m_artist", "") ?: "",
                album = prefs.getString("m_album", "") ?: "",
                primaryTag = prefs.getString("m_tag", null),
                positionMs = prefs.getLong("m_pos", 0L),
                durationMs = prefs.getLong("m_dur", 0L),
                sourceId = prefs.getString("m_src", null),
            )
        }.getOrNull()
    }

    /** 每次状态变化时写盘（`publish()` 里调用，见下方）。 */
    private fun saveMemory() {
        val s = _state.value
        val item = s.current ?: return
        val pos = _player?.currentPosition ?: 0L
        val dur = _player?.duration?.takeIf { it > 0 } ?: 0L
        prefs.edit()
            .putString("m_id", item.id)
            .putString("m_name", item.name)
            .putString("m_artist", item.artistLine)
            .putString("m_album", item.album ?: "")
            .putString("m_tag", item.imageTags["Primary"] ?: "")
            .putLong("m_pos", pos)
            .putLong("m_dur", dur)
            .putString("m_src", s.sourceId)
            .apply()
        _remembered.value = MusicMemory(
            itemId = item.id,
            name = item.name,
            artist = item.artistLine,
            album = item.album ?: "",
            primaryTag = item.imageTags["Primary"]?.takeIf { it.isNotBlank() },
            positionMs = pos,
            durationMs = dur,
            sourceId = s.sourceId,
        )
    }

    /**
     * 用户口径（2026-11-27，#35）**给系统 MediaSession 用的「续播 MediaItem」**。
     *
     * `onPlaybackResumption` 只能同步返回，拿不到网络，
     * 所以这里用记忆里存的名字/歌手/封面构造一个 MediaItem（不带直链 ——
     * 真正的地址在 `resolve()` 里按 itemId 现取）。
     *
     * 这样系统拉起播放时通知栏立刻有歌名封面，不会先空一格。
     */
    fun resumeMediaItem(memory: MusicMemory): MediaItem {
        val urls = graph.sessions.current?.let(graph::images)
        val artwork = memory.primaryTag?.takeIf { it.isNotBlank() }?.let { tag ->
            runCatching { urls?.image(memory.itemId, "Primary", 512, tag) }.getOrNull()
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(memory.name)
            .setArtist(memory.artist.ifEmpty { null })
            .setAlbumTitle(memory.album.ifEmpty { null })
            .setArtworkUri(artwork?.let(Uri::parse))
            .setDurationMs(memory.durationMs.takeIf { it > 0 })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setMediaId(memory.itemId)
            .setUri(Uri.Builder().scheme("etkn-music").authority(memory.itemId).build())
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * 用户口径（2026-11-27，#42）**Service 起来时把「上次那首」预装进播放器**。
     *
     * 为什么必须预装（对照系统音乐那张卡片：`00:00 / 04:00` + 播放键可点）：
     * 系统卡片要显示封面、总时长、可点的播放键，前提是
     * ```
     * player.mediaItemCount >= 1     // 有内容
     * player.duration > 0            // 知道时长（prepare 后才有）
     * isCommandAvailable(PLAY_PAUSE) // 命令可用
     * ```
     * 播放器空着的时候这三条全不成立，卡片整块就是灰的 —— 这正是用户报的问题。
     *
     * 注意：这里**只 prepare 不 play**，不会自己出声，用户点播放才播。
     */
    fun restoreForResumption() {
        val memory = _remembered.value ?: return
        val p = player
        if (p.mediaItemCount > 0) return   // 已经有内容，不用重装
        runCatching {
            p.setMediaItem(resumeMediaItem(memory))
            p.prepare()
            p.seekTo(memory.positionMs)
        }.onFailure { android.util.Log.w("ETKN-Music", "restoreForResumption failed", it) }
    }

    /**
     * 用户口径（2026-11-27，#41）**把记忆还原成一个可显示的最小 Item**。
     *
     * 播放页在重启后 `state.current` 是 null，需要用它来渲染封面/歌名/进度，
     * 否则页面会直接被 pop 掉（用户看到「抖一下」）。
     *
     * 只填显示必需字段，不填 mediaSources —— 播放时会走 `resumeRemembered()` 重新取详情。
     */
    fun itemFromMemory(memory: MusicMemory): Item = Item(
        id = memory.itemId,
        name = memory.name,
        type = "Audio",
        collectionType = null,
        sourceType = null,
        overview = null,
        taglines = emptyList(),
        productionYear = null,
        premiereDate = null,
        dateCreated = null,
        endDate = null,
        officialRating = null,
        communityRating = null,
        criticRating = null,
        tmdbRating = null,
        doubanRating = null,
        imdbRating = null,
        runTimeTicks = 0L,
        genres = emptyList(),
        studios = emptyList(),
        seriesId = null,
        seriesName = null,
        seasonId = null,
        seasonName = null,
        parentIndexNumber = null,
        indexNumber = null,
        indexNumberEnd = null,
        imageTags = memory.primaryTag?.takeIf { it.isNotBlank() }?.let { mapOf("Primary" to it) } ?: emptyMap(),
        backdropTags = emptyList(),
        parentBackdropItemId = null,
        parentBackdropTags = emptyList(),
        parentLogoItemId = null,
        parentLogoTag = null,
        parentThumbItemId = null,
        parentThumbTag = null,
        seriesPrimaryTag = null,
        primaryAspectRatio = null,
        userData = UserData(),
        people = emptyList(),
        mediaSources = emptyList(),
        childCount = null,
        recursiveItemCount = null,
        providerIds = emptyMap(),
        isFolder = false,
        locationType = null,
        album = memory.album.ifEmpty { null },
        albumKey = null,
        artists = if (memory.artist.isNotBlank()) listOf(memory.artist) else emptyList(),
    )

    /**
     * 用户口径（2026-11-27，#29）**点「记忆里的那首歌」→ 从上次进度续播**。
     *
     * 先按 id 重新取一次详情（拿到 MediaSources 才能解析直链），
     * 拿到后 setMediaItems + seekTo(上次进度) + play。
     */
    fun resumeRemembered() {
        val memory = _remembered.value ?: return
        graph.scope.launch {
            val item = runCatching { graph.api.item(memory.itemId) }.getOrNull() ?: return@launch
            play(listOf(item), 0, memory.sourceId ?: "remembered")
            // play() 里把位置置 0，这里再补一次续播位置
            _player?.seekTo(memory.positionMs)
        }
    }

    private fun create(): ExoPlayer {
        val upstream = PlayerFactory.dataSource(graph.http.media, graph.identity.userAgent)
        val resolving = ResolvingDataSource.Factory(upstream) { spec -> resolve(spec) }
        /**
         * 用户口径（2026-11-27，#39）**音乐走自己的独立缓存**。
         *
         * 接在 ResolvingDataSource 之后：直链由它解析出真实 URL，
         * MusicCache 用解析后的地址做 cache key（`spec.key ?: uri`），
         * 播过的歌落到 `filesDir/musiccache`，下次直接读本地 → 秒播。
         */
        /**
         * 配额取「我的 → 播放 → 音乐缓存」，和视频磁盘缓存完全分开（#39）。
         *
         * AppSettings 只暴露 `flow`（异步），这里在创建播放器时要同步拿值，
         * 所以直接读同一个 DataStore 生效后的 SharedPreferences 快捷方式：
         * 用 `runBlocking` 太重，改成 MusicCache 内部自己缓存配额，
         * 设置变更后由 `MusicCache.setLimit()` 更新。
         */
        val musicCacheMb = MusicCache.limitMb
        val factory = MusicCache.dataSource(context, resolving, musicCacheMb)
        val player = PlayerFactory.music(context, factory)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                finishReport(completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                // Gapless advance keeps STATE_READY, so start the next track's report here.
                if (player.playbackState == Player.STATE_READY) startReport()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) startReport()
                if (state == Player.STATE_ENDED) finishReport(completed = true)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (startedId != null) report("Progress", completed = false)
            }

            override fun onPlayerError(error: PlaybackException) {
                finishReport(completed = false)
                _state.value = _state.value.copy(error = "无法播放这首歌（${error.errorCodeName}）")
            }
        })
        return player
    }

    /** Runs on the loader thread; blocking here is expected by ResolvingDataSource. */
    private fun resolve(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != SCHEME) return spec
        val id = spec.uri.lastPathSegment ?: throw IllegalStateException("无效曲目")
        val active = session ?: throw IllegalStateException("未登录")
        val entry = resolved[id] ?: runBlocking {
            val info = graph.api.playbackInfo(id, audio = true)
            val source = info.sources.firstOrNull() ?: throw IllegalStateException("这首歌暂无可播放版本")
            val url = PlaybackAddress.music(source, active.server, active.token, active.userId, info.playSessionId)
            Resolved(url, info.playSessionId, source.id, source.runTimeTicks)
        }.also { resolved[id] = it }
        if (_state.value.current?.id == id) _state.value = _state.value.copy(sourceId = entry.sourceId)
        return spec.withUri(Uri.parse(entry.url))
    }

    fun play(tracks: List<Item>, index: Int, contextKey: String?) {
        if (tracks.isEmpty()) return
        tracks.forEach { items[it.id] = it }
        context.startService(Intent(context, MusicService::class.java))
        val urls = graph.sessions.current?.let(graph::images)
        val p = player
        finishReport(completed = false)
        p.setMediaItems(tracks.map { toMediaItem(it, urls) }, index.coerceIn(0, tracks.lastIndex), 0)
        p.prepare()
        p.play()
    }

    fun append(tracks: List<Item>) {
        val urls = graph.sessions.current?.let(graph::images)
        tracks.forEach { items[it.id] = it }
        _player?.addMediaItems(tracks.map { toMediaItem(it, urls) })
    }

    fun toggle() { _player?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } } }
    fun pause() { _player?.pause() }
    fun next() { _player?.seekToNextMediaItem() }
    fun previous() { _player?.let { if (it.currentPosition > 3_000) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun seekTo(ms: Long) { _player?.seekTo(ms) }
    fun skipTo(index: Int) { _player?.seekTo(index, 0) }
    fun toggleShuffle() { _player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        _player?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun removeAt(index: Int) { _player?.removeMediaItem(index) }

    fun stop() {
        finishReport(completed = false)
        _player?.let {
            it.stop()
            it.clearMediaItems()
        }
        publish()
    }

    private fun publish() {
        val p = _player ?: run { _state.value = MusicState(); return }
        val queue = (0 until p.mediaItemCount).mapNotNull { items[p.getMediaItemAt(it).mediaId] }
        val index = p.currentMediaItemIndex
        val current = p.currentMediaItem?.mediaId?.let(items::get)
        _state.value = _state.value.copy(
            current = current,
            queue = queue,
            index = if (current == null) -1 else index,
            isPlaying = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            shuffle = p.shuffleModeEnabled,
            repeat = p.repeatMode,
            error = if (p.playerError == null) null else _state.value.error,
            sourceId = current?.id?.let { resolved[it]?.sourceId },
        )
        // 用户口径（#29）：每次状态变化都把「上次播的歌 + 进度」落盘，供下次启动恢复。
        if (current != null) saveMemory()
    }

    // ---------- Reporting (TV contract: Playing → Progress → Stopped with PlayedToCompletion) ----------

    private fun startReport() {
        val id = _player?.currentMediaItem?.mediaId ?: return
        if (startedId == id) return
        startedId = id
        reportedId = id
        report("", completed = false)
        progressJob?.cancel()
        progressJob = graph.scope.launch {
            while (isActive) {
                delay(10_000)
                if (_player?.isPlaying == true) report("Progress", completed = false)
            }
        }
    }

    private fun finishReport(completed: Boolean) {
        if (reportedId == null) return
        report("Stopped", completed)
        progressJob?.cancel()
        reportedId = null
        startedId = null
    }

    private fun report(event: String, completed: Boolean) {
        val id = reportedId ?: return
        val active = session ?: return
        val entry = resolved[id] ?: return
        val p = _player ?: return
        val positionMs = if (p.currentMediaItem?.mediaId == id) p.currentPosition else entry.runTimeTicks / TICKS_PER_MS
        val payload = buildJsonObject {
            put("ItemId", id)
            put("UserId", active.userId)
            put("MediaSourceId", entry.sourceId)
            put("PlaySessionId", entry.playSession)
            put("PositionTicks", positionMs.coerceAtLeast(0) * TICKS_PER_MS)
            put("IsPaused", !p.isPlaying)
            put("PlayedToCompletion", completed)
        }
        graph.scope.launch { runCatching { graph.api.report(event, payload, active) } }
    }

    private fun toMediaItem(item: Item, urls: ImageUrls?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(item.name)
            .setArtist(item.artistLine.ifEmpty { null })
            .setAlbumTitle(item.album)
            .setArtworkUri(urls?.music(item, 512)?.let(Uri::parse))
            .setDurationMs(item.runtimeMs.takeIf { it > 0 })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply { putLong("runtime", item.runTimeTicks) })
            .build()
        return MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(Uri.Builder().scheme(SCHEME).authority("track").appendPath(item.id).build())
            .setMediaMetadata(metadata)
            .build()
    }

    companion object {
        const val SCHEME = "etkn-music"
    }
}

/**
 * 用户口径（2026-11-27，#29）**音乐记忆状态**：上次播的歌（够画界面的字段）+ 进度。
 *
 * 不存整个 `Item`：它不是 @Serializable，序列化会带来一大堆字段兼容问题。
 * 这里只留迷你条要显示的 + 续播要用的。
 */
data class MusicMemory(
    val itemId: String,
    val name: String,
    val artist: String,
    val album: String,
    val primaryTag: String?,
    val positionMs: Long,
    val durationMs: Long,
    val sourceId: String?,
)
