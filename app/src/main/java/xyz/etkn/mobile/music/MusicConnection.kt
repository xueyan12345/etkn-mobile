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
import xyz.etkn.mobile.player.PlaybackAddress
import xyz.etkn.mobile.player.PlayerFactory
import java.util.concurrent.ConcurrentHashMap
import xyz.etkn.mobile.data.model.UserData
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import xyz.etkn.mobile.data.session.SessionState

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
    /**
     * 用户口径（2026-11-27，#32⑤）**音乐进度条的缓存段要用它**。
     *
     * 语义和视频那边一致：从播放位置往后，本地已缓存的毫秒数
     * （由 `MusicCache` 真实落盘量换算）。0 表示还没有缓存数据，
     * 此时进度条只画"已播放 + 未播放"两段。
     */
    val cachedPosition: Long = 0L,
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
                /** 用户口径（#43）队列一起读回来，重启后播放列表才有内容。 */
                queueIds = prefs.getString("m_q_ids", "").orEmpty().splitToSeq(),
                queueNames = prefs.getString("m_q_names", "").orEmpty().splitToSeq(),
                queueArtists = prefs.getString("m_q_artists", "").orEmpty().splitToSeq(),
                queueTags = prefs.getString("m_q_tags", "").orEmpty().splitToSeq(),
            )
        }.getOrNull()
        /**
         * 用户口径（2026-11-27，#2/#9）**重启后立刻把队列装回去**。
         *
         * 修的 bug（用户原话）：
         *  「播一首，退出重开，点播放列表图标，图标里边没有队列，啥也没有，是空的，
         *    我明明之前添加10多个歌曲，有队列里边」
         *
         * 根因：`restoreForResumption()` 原来只在 `MusicService.onCreate()` 里调用，
         * 而 Service 只在 `play()` 时才 `startService` ——
         * 重启 App 后光打开音乐页，Service 根本没起，恢复逻辑一次都没执行。
         *
         * 现在放到 init 里（App 启动就执行），不等 Service。
         * 注意用 `runCatching` 兜住：恢复失败不能影响正常使用。
         */
        android.util.Log.i(
            "ETKN-Music",
            "init: remembered=${_remembered.value?.let { "id=${it.itemId} queue=${it.queueIds.size} pos=${it.positionMs} src=${it.sourceId != null}" } ?: "null"}",
        )
        /**
         * 用户口径（2026-11-27，#24）**必须等登录态恢复后再恢复队列**。
         *
         * 实测日志（这次是真正的根因）：
         * ```
         * init: remembered=id=item-25293 queue=601 pos=41145 src=false
         * restore: mediaItemCount=0 q=601
         * restore 解析直链失败
         *   va1: 登录已过期，请重新登录      ← 就是这句
         * ```
         *
         * 原因：`SessionManager.restore()` 是 **异步** 的（`scope.launch`），
         * 而这里 `init` 里同步调 `restoreForResumption()` —— token 还没解出来，
         * 所有 API 调用都返回「登录已过期」：
         *  · 直链解析失败 → 队列装不进播放器 → 播放列表空
         *  · `api.item()` 失败 → 点播放没反应
         *  · sourceId 永远拿不到 → src=false → 迷你条没歌词
         *
         * 三个问题同一个根因。
         *
         * 现在：等 `sessions.state` 变成 SignedIn（有 token）之后再恢复。
         */
        graph.scope.launch {
            runCatching {
                /** 等登录态就绪（最多 15 秒，避免卡死）。 */
                withTimeoutOrNull(15_000L) {
                    graph.sessions.state.first { it is SessionState.SignedIn }
                }
                android.util.Log.i(
                    "ETKN-Music",
                    "登录态就绪（${graph.sessions.state.value.javaClass.simpleName}），开始恢复队列",
                )
                restoreForResumption()
            }.onFailure {
                android.util.Log.w("ETKN-Music", "restore failed", it)
            }
        }
        android.util.Log.i(
            "ETKN-Music",
            "init done: mediaItemCount=${_player?.mediaItemCount ?: -1} queue=${_state.value.queue.size}",
        )
    }

    /** 空串返回空列表（不是 [""]）。 */
    private fun String.splitToSeq(): List<String> =
        if (isEmpty()) emptyList() else split(SEP)

    /** 每次状态变化时写盘（`publish()` 里调用，见下方）。 */
    private fun saveMemory() {
        val s = _state.value
        val item = s.current ?: return
        val pos = _player?.currentPosition ?: 0L
        /**
         * 用户口径（#1 终极根因）**duration 不能只信 player.duration**。
         *
         * 实测：恢复后 `dur=-9223372036854775807`（= `C.TIME_UNSET`），
         * 系统据此判定"时长未知"→ 卡片显示 00:00 → 播放键不可用 → 变灰。
         *
         * 原因：`player.duration` 在「未真正解析直链」时是 TIME_UNSET，
         * 而 `saveMemory()` 往往在这种时刻被调用（publish 很频繁）。
         *
         * 现在的取值优先级：
         *  ① player.duration（真正 prepare 完才有）
         *  ② item.runTimeTicks（服务端元数据，一直有）
         *  ③ 上次记忆里的 duration（保底）
         */
        val dur = _player?.duration?.takeIf { it > 0 && it != androidx.media3.common.C.TIME_UNSET }
            ?: item.runTimeTicks.takeIf { it > 0 }?.div(10_000L)
            ?: _remembered.value?.durationMs?.takeIf { it > 0 }
            ?: 0L
        prefs.edit()
            .putString("m_id", item.id)
            .putString("m_name", item.name)
            .putString("m_artist", item.artistLine)
            .putString("m_album", item.album ?: "")
            .putString("m_tag", item.imageTags["Primary"] ?: "")
            .putLong("m_pos", pos)
            .putLong("m_dur", dur)
            .putString("m_src", s.sourceId)
            /**
             * 用户口径（2026-11-27，#2）**队列必须落盘**。
             *
             * 之前的 bug：这里只存了「当前这一首」，`m_q_ids` 等字段压根没写
             * → 重启后 `queueIds` 为空 → 播放列表是空的。
             *
             * 现在把整条队列（id / 名字 / 歌手 / 封面 tag）都存下来，
             * 用 `\u001F` 拼接（歌曲名里不会出现这个字符）。
             */
            .putString("m_q_ids", s.queue.joinToString(SEP) { it.id })
            .putString("m_q_names", s.queue.joinToString(SEP) { it.name })
            .putString("m_q_artists", s.queue.joinToString(SEP) { it.artistLine })
            .putString("m_q_tags", s.queue.joinToString(SEP) { it.imageTags["Primary"].orEmpty() })
            .apply()
        android.util.Log.i(
            "ETKN-Music",
            "saveMemory: id=${item.id} pos=$pos dur=$dur q=${s.queue.size} rawDur=${_player?.duration} ticks=${item.runTimeTicks}",
        )
        _remembered.value = MusicMemory(
            itemId = item.id,
            name = item.name,
            artist = item.artistLine,
            album = item.album ?: "",
            primaryTag = item.imageTags["Primary"]?.takeIf { it.isNotBlank() },
            positionMs = pos,
            durationMs = dur,
            sourceId = s.sourceId,
            queueIds = s.queue.map { it.id },
            queueNames = s.queue.map { it.name },
            queueArtists = s.queue.map { it.artistLine },
            queueTags = s.queue.map { it.imageTags["Primary"].orEmpty() },
        )
    }

    /**
     * 用户口径（#32⑤）**进度条的缓存段用播放器真实 bufferedPosition**。
     *
     * 一开始我想自己按 SimpleCache 的落盘字节换算，但那需要「文件总字节」，
     * 而我们没有可靠来源（直链是 302 到 115 CDN，长度得额外 HEAD 一次）。
     *
     * 直接用 `Player.bufferedPosition` 更准也简单：它反映「当前已能连续播放到的位置」，
     * 在有 SimpleCache 的情况下就等于「已缓存的末尾」。
     * 视频播放器那条缓冲条最终也是这个语义。
     */
    private fun cachedAheadMs(itemId: String?, positionMs: Long, durationMs: Long): Long {
        val p = _player ?: return 0L
        val buffered = p.bufferedPosition
        return if (buffered > positionMs) buffered else positionMs
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
            /**
             * 用户口径（#1）**duration 必须有值**。
             * 系统控制中心/车机卡片就是靠这个显示总时长（如 00:00 / 04:00），
             * 没有它 → 卡片判定"未在播放" → 播放键变灰不可点。
             */
            .setDurationMs(
                memory.durationMs.takeIf { it > 0 }
                    ?: itemFromMemory(memory).runtimeMs.takeIf { it > 0 },
            )
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            .setMediaId(memory.itemId)
            /**
             * 用户口径（2026-11-27，#2）**URI 必须和 `toMediaItem` 完全一致**。
             *
             * 修的 bug：这里原来写 `authority(itemId)` —— itemId 在 authority 位、path 为空。
             * 而 `resolve()` 取的是 `spec.uri.lastPathSegment`：
             * ```
             * val id = spec.uri.lastPathSegment ?: throw IllegalStateException("无效曲目")
             * ```
             * path 为空 → lastPathSegment == null → 直接抛「无效曲目」
             * → 恢复出来的队列一 prepare 就崩，queue 归零。
             *
             * 正确写法（和 `toMediaItem` 一致）：scheme + authority("track") + appendPath(itemId)。
             */
            .setUri(
                Uri.Builder()
                    .scheme(SCHEME)
                    .authority("track")
                    .appendPath(memory.itemId)
                    .build(),
            )
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
        android.util.Log.i(
            "ETKN-Music",
            "restore: remembered=${_remembered.value?.let { "id=${it.itemId} q=${it.queueIds.size} pos=${it.positionMs}" } ?: "NULL"}",
        )
        val memory = _remembered.value ?: run {
            android.util.Log.w("ETKN-Music", "restore: 没有记忆，跳过")
            return
        }
        /**
         * 用户口径（#1 终极修法）**恢复必须用真实直链，不能用占位 scheme**。
         *
         * Media3 的机制（实测确认）：
         *  · `setMediaItems` 只是往 player 里塞数据，**不会**让 MediaSession
         *    把 metadata / queue 同步给系统 —— 系统拿到的是 `state=NONE(0), size=0`
         *  · 只有当 player 真正进入 BUFFERING / READY / PAUSED，
         *    Session 才会有完整状态（metadata size=11, queueTitle size=1）
         *  · 而 `etkn-music://track/<id>` 是占位 scheme，prepare 必然失败落到 IDLE
         *
         * 所以这里改成：**异步 resolve 出真实直链，再 setMediaItems + prepare**。
         * 这样即使不播放，player 也会到 PAUSED + metadata 完整，
         * 系统控制中心/车机就能看到「一万次悲伤 / 逃跑计划」并点亮播放键。
         */
        if (memory.itemId.isBlank()) return
        val p = player
        android.util.Log.i("ETKN-Music", "restore: mediaItemCount=${p.mediaItemCount} q=${memory.queueIds.size}")
        /**
         * 用户口径（#1）**即使已有占位 item，也必须解析真直链并 prepare**。
         *
         * 上一版这里 `if (p.mediaItemCount > 0) return` ——
         * 而 init 阶段 player 里已经塞了占位 item，于是这段永远早退，
         * 真直链解析一次都没执行过，Session 一直停在 NONE。
         *
         * 现在：只有在「已经处于 BUFFERING/READY/PAUSED」时才跳过
         * （说明之前真的 prepare 成功过），否则一律走一次真直链解析。
         */
        /**
         * 用户口径（2026-11-27，#14）**已有内容时不能整块跳过，队列还得恢复**。
         *
         * 用户原话：「大退重开，播放列表不在了，进度条还在，
         *            下边的小播放条里边个词也不显示（正常播放时候歌词显示）」
         *
         * 根因：上面这个早退在「player 已有 item 且不是 IDLE」时直接 return，
         * 连 `items` map 和 `_state.queue` 都没恢复 ——
         * 结果播放列表是空的、迷你条拿不到歌词（歌词依赖 sourceId）。
         *
         * 现在早退时也要保证：items map 填好、publish() 一次、
         * 让 state.queue 有内容、sourceId 有值。
         */
        if (p.mediaItemCount > 0 && p.playbackState != Player.STATE_IDLE) {
            android.util.Log.i("ETKN-Music", "restore: 已有内容且状态=${p.playbackState}，只补队列状态")
            /** 把队列重新映射回 items（publish 依赖它）。 */
            memory.queueIds.forEachIndexed { i, id ->
                if (items[id] == null) {
                    items[id] = itemFromMemory(
                        MusicMemory(
                            itemId = id,
                            name = memory.queueNames.getOrNull(i).orEmpty().ifEmpty { id },
                            artist = memory.queueArtists.getOrNull(i).orEmpty(),
                            album = "",
                            primaryTag = memory.queueTags.getOrNull(i)?.takeIf { it.isNotBlank() },
                            positionMs = 0L,
                            durationMs = 0L,
                            sourceId = null,
                        ),
                    )
                }
            }
            /** sourceId 也要补回，否则迷你条歌词取不到（#14）。 */
            if (_state.value.sourceId == null && memory.sourceId != null) {
                _state.value = _state.value.copy(sourceId = memory.sourceId)
            }
            publish()
            return
        }
        runCatching {
            /**
             * 用户口径（2026-11-27，#43）**恢复整个队列，不只恢复一首**。
             *
             * 修的 bug：重启后点播放列表是空的。
             *
             * 根因：原来只 `setMediaItem(一首)`，播放器里只有 1 条 MediaItem，
             * 而 `publish()` 的队列是从播放器的 MediaItem 列表算出来的：
             * ```
             * val queue = (0 until p.mediaItemCount).mapNotNull { items[...] }
             * ```
             * 1 条（甚至 0 条，如果 items map 里没这个 id）→ 列表空。
             *
             * 现在：按记忆里的队列 id 逐个还原成 MediaItem，一起装进播放器，
             * 并**同时填回 `items` map** —— 否则 `mapNotNull` 依然会把它们过滤掉。
             */
            /**
             * 先解析当前这首的真实直链（网络请求，放在协程里做）。
             * 拿到后 setMediaItems 用的就是真 URL，prepare 能走到 BUFFERING。
             */
            graph.scope.launch {
                runCatching {
                    /**
                     * 用户口径（#25）**Service 直接调进来时也要等登录态**。
                     * （init 那条路径已经等了，但 MusicService.onCreate 是另一条入口。）
                     */
                    withTimeoutOrNull(10_000L) {
                        graph.sessions.state.first { it is SessionState.SignedIn }
                    }
                    val info = graph.api.playbackInfo(memory.itemId, audio = true)
                    val source = info.sources.firstOrNull() ?: return@runCatching
                    val active = session ?: return@runCatching
                    val url = PlaybackAddress.music(
                        source, active.server, active.token, active.userId, info.playSessionId,
                    )
                    resolved[memory.itemId] = Resolved(url, info.playSessionId, source.id, source.runTimeTicks)
                    /** 用真 URL 重建 MediaItem，替换掉占位那条。 */
                    val real = resumeMediaItem(memory).buildUpon().setUri(Uri.parse(url)).build()
                    val idx = p.currentMediaItemIndex.coerceAtLeast(0)
                    p.replaceMediaItem(idx, real)
                    p.prepare()
                    android.util.Log.i("ETKN-Music", "restore: 真直链已装入，prepare 开始")
                    /**
                     * 用户口径（#1 最终手段）**静默播 1 帧再暂停，强制走完状态机**。
                     *
                     * 实测规律（反复验证）：
                     * ```
                     * 冷启动后 restore（只 prepare）→ Session state=NONE(0), metadata size=2
                     * 点一次播放                      → state=PLAYING, metadata size=11
                     * 再暂停                          → state=PAUSED, metadata size=11 ✅ 一直保持
                     * ```
                     * Media3 只在 player **真正开始播放过**之后，才把 metadata/queue
                     * 同步给 MediaSession。仅 prepare 到 PAUSED 不够。
                     *
                     * 所以这里：音量置 0（用户听不见）→ play() → 等状态到 READY
                     * → 立即 pause() 并 seek 回原位置。整个过程约 200~600ms，
                     * 用户感知不到声音，但 Session 从此有了完整状态，
                     * 状态栏/车机卡片的播放键就亮了。
                     */
                    val realVolume = p.volume
                    p.volume = 0f
                    p.play()
                    /** 等到真的进入 READY 或超时（最多 2 秒）。 */
                    val deadline = System.currentTimeMillis() + 2_000
                    while (p.playbackState != Player.STATE_READY &&
                        System.currentTimeMillis() < deadline
                    ) {
                        kotlinx.coroutines.delay(50)
                    }
                    p.pause()
                    p.volume = realVolume
                    p.seekTo(memory.positionMs)
                    android.util.Log.i(
                        "ETKN-Music",
                        "restore: 静默起播完成 state=${p.playbackState} pos=${p.currentPosition}",
                    )
                }.onFailure {
                    android.util.Log.w("ETKN-Music", "restore 解析直链失败", it)
                }
            }
            val ids = memory.queueIds
            if (ids.isEmpty()) {
                /**
                 * 用户口径（#1）**队列为空时也要能恢复"当前这一首"**。
                 *
                 * 实测日志：`remembered=id=item-25699 queue=0` ——
                 * 老版本（v1.16 之前）的 prefs 里没有 `m_q_ids` 字段，队列是空的。
                 * 如果这时只 setMediaItem 而不填 `items` map，
                 * `publish()` 里的 `mapNotNull { items[...] }` 会把它过滤掉 →
                 * `state.queue` 依然是空 → 界面/系统卡片都显示"未在播放"。
                 *
                 * 所以这里必须同时把这一首填进 `items`。
                 */
                items[memory.itemId] = itemFromMemory(memory)
                p.setMediaItem(resumeMediaItem(memory))
            } else {
                val list = ids.mapIndexed { i, id ->
                    val mini = MusicMemory(
                        itemId = id,
                        name = memory.queueNames.getOrNull(i).orEmpty().ifEmpty { id },
                        artist = memory.queueArtists.getOrNull(i).orEmpty(),
                        album = "",
                        primaryTag = memory.queueTags.getOrNull(i)?.takeIf { it.isNotBlank() },
                        positionMs = if (id == memory.itemId) memory.positionMs else 0L,
                        durationMs = if (id == memory.itemId) memory.durationMs else 0L,
                        sourceId = if (id == memory.itemId) memory.sourceId else null,
                    )
                    val mediaItem = resumeMediaItem(mini)
                    // 关键：填回 items map，publish() 才认得出这些 id
                    items[id] = itemFromMemory(mini)
                    mediaItem
                }
                val startIndex = ids.indexOf(memory.itemId).coerceAtLeast(0)
                p.setMediaItems(list, startIndex, memory.positionMs)
            }
            /**
             * 用户口径（#1）**不要对占位 URL 调 prepare**。
             *
             * 实测：`etkn-music://track/item-xxx` 是占位 scheme，真正的直链在
             * `resolve()` 里按需换取。对占位 URL prepare 会解析失败，
             * 播放器状态变成 `STATE_IDLE`，Session 随之 `PlaybackState=NONE`，
             * 系统控制中心看到 NONE 就判定「未在播放」→ 播放键变灰。
             *
             * 系统音乐的做法：它暂停时是 `PAUSED(2)` 且 metadata 完整，
             * 所以卡片一直亮着。
             *
             * 现在改成：
             *  · 只 setMediaItems（Session 立刻有 metadata + duration）
             *  · 不 prepare（避免 IDLE）
             *  · 真正播放时（用户点播放键 / music.play）才 prepare ——
             *    那时 resolve() 会把占位 URL 换成真直链
             */
            android.util.Log.i(
                "ETKN-Music",
                "restore done: count=${p.mediaItemCount} idx=${p.currentMediaItemIndex}",
            )
            /**
             * 用户口径（#1）**这里必须 prepare，否则 Session 状态停在 NONE**。
             *
             * 实测：只 setMediaItems 不 prepare，`dumpsys media_session` 是
             * ```
             * PlaybackState {state=NONE(0), ...}
             * metadata: size=2, description=null
             * queueTitle=null, size=0
             * ```
             * ColorOS 的 `createActionsFromState: curState = 0` 直接据此判定"没有可播内容"，
             * 播放键不给点亮。
             *
             * 而系统音乐暂停时是 `PAUSED(2)` + metadata 完整 → 按钮一直亮。
             *
             * 所以：prepare() 让它至少进入 BUFFERING/READY。
             * 占位 URL `etkn-music://track/<id>` 会由 `resolve()` 换成真直链 ——
             * 这一步是异步网络请求，失败也不影响 Session 已有 metadata。
             */
            runCatching { p.prepare() }.onFailure {
                android.util.Log.w("ETKN-Music", "restore prepare failed", it)
            }
            /**
             * 用户口径（#2）**装完必须 publish 一次**。
             *
             * `state.queue` 是从 player 的 MediaItem 列表算出来的（在 `publish()` 里），
             * 只 setMediaItems 不 publish，界面读到的队列依然是空的。
             */
            publish()
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
        /** 用户口径（#1）时长必须带上，否则恢复后系统卡片显示 00:00、播放键不可用。 */
        runTimeTicks = memory.durationMs.takeIf { it > 0 }?.let { it * 10_000L } ?: 0L,
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
        isMissing = false,
        status = null,
        kind = null,
        key = null,
        artworkHash = null,
        artistImage = null,
        count = null,
        chapters = emptyList(),
    )

    /**
     * 用户口径（2026-11-27，#29）**点「记忆里的那首歌」→ 从上次进度续播**。
     *
     * 先按 id 重新取一次详情（拿到 MediaSources 才能解析直链），
     * 拿到后 setMediaItems + seekTo(上次进度) + play。
     */
    /**
     * 用户口径（2026-11-27，#18）**续播必须恢复整个队列，不能只剩一首**。
     *
     * 修的两个 bug（用户原话）：
     *  「大退APP之后，重新进入APP，里边播放列表就是没了，清空了」
     *  「重新进入APP之后，直接点击那个小的播放控件进入播放界面，
     *    我点击播放按钮，没有反应，不好使」
     *
     * 根因链（三个问题同一个根因）：
     * ```
     * resumeRemembered() 里调 play(listOf(item), 0, ...)  ← 只传了 1 首！
     *   → player 的队列被覆盖成 1 首
     *   → publish() → saveMemory() 把「队列=1首」写回磁盘
     *   → 原队列彻底丢了（下次启动只剩 1 首，或者 0 首）
     * ```
     *
     * 而且如果 `graph.api.item()` 拿不到详情（网络失败/登出），
     * 直接 `return@launch` —— 用户点播放就完全没反应。
     *
     * 现在：
     *  · 按记忆里的 `queueIds` 还原**整个队列**
     *  · 当前这首的详情拿不到时，用记忆里的字段先播（不 return）
     *  · 位置用 `memory.positionMs` 续上
     */
    fun resumeRemembered() {
        val memory = _remembered.value ?: return
        graph.scope.launch {
            /**
             * 用户口径（#25）**等登录态就绪再解析**，否则 token 没恢复
             * 所有 API 都返回「登录已过期」，点播放永远没反应。
             */
            withTimeoutOrNull(10_000L) {
                graph.sessions.state.first { it is SessionState.SignedIn }
            }
            /**
             * 先按队列 id 重建整个列表。
             * 当前这首优先用线上详情（有完整 MediaSource），
             * 拿不到就用记忆里的最小 Item（至少能播、能显示）。
             */
            val ids = memory.queueIds.ifEmpty { listOf(memory.itemId) }
            val list = ids.mapIndexed { i, id ->
                if (id == memory.itemId) {
                    runCatching { graph.api.item(id) }.getOrNull()
                        ?: itemFromMemory(memory)
                } else {
                    items[id] ?: itemFromMemory(
                        MusicMemory(
                            itemId = id,
                            name = memory.queueNames.getOrNull(i).orEmpty().ifEmpty { id },
                            artist = memory.queueArtists.getOrNull(i).orEmpty(),
                            album = "",
                            primaryTag = memory.queueTags.getOrNull(i)?.takeIf { it.isNotBlank() },
                            positionMs = 0L,
                            durationMs = 0L,
                            sourceId = null,
                        ),
                    )
                }
            }
            val startIndex = ids.indexOf(memory.itemId).coerceAtLeast(0)
            /** 一次性把整个队列装回播放器，再定位到上次的位置。 */
            play(list, startIndex, memory.sourceId ?: "remembered")
            _player?.seekTo(memory.positionMs)
            android.util.Log.i(
                "ETKN-Music",
                "resumeRemembered: 队列=${list.size} 起始=$startIndex 位置=${memory.positionMs}",
            )
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
        /**
         * 用户口径（2026-11-27，#2）**sourceId 一解析出来就立刻补存一次记忆**。
         *
         * 修的 bug：「重启后迷你条的三行歌词没了」。
         *
         * 根因：歌词接口必须要 `sourceId` 才能取到词。而 `sourceId` 是在这里
         * （真正要播放时才 resolve 直链）才拿到的，`saveMemory()` 由 `publish()` 调用，
         * 第一次 publish 时 resolve 往往还没跑完 → 存进磁盘的 `m_src` 是 null
         * → 重启后 `remembered.sourceId == null` → 歌词接口直接 return，永远没词。
         *
         * 现在：resolve 成功的那一刻马上把最新的 sourceId 落盘。
         */
        if (_state.value.current?.id == id) {
            _state.value = _state.value.copy(sourceId = entry.sourceId)
            runCatching { saveMemory() }
        }
        return spec.withUri(Uri.parse(entry.url))
    }

    /**
     * 用户口径（2026-11-27，#20）**播放时若 Item 缺字段就按 id 补详情**。
     *
     * 场景：歌曲列表现在优先显示本地缓存，缓存里的 Item 只存了展示字段
     * （id/歌名/歌手/专辑/封面tag/时长/添加时间/播放次数），
     * 没有 `mediaSources`、`runTimeTicks` 可能也是旧的。
     *
     * 直接拿去播会解析失败（`playbackInfo` 拿不到源）。
     * 这里在起播前把「当前这首 + 队列里缺字段的」补一次详情，
     * 但**只补必要的那几首**（当前 + 前后各 2 首），避免 601 首全拉一遍。
     */
    fun play(tracks: List<Item>, index: Int, contextKey: String?) {
        if (tracks.isEmpty()) return
        tracks.forEach { items[it.id] = it }
        context.startService(Intent(context, MusicService::class.java))
        val p = player
        finishReport(completed = false)
        /** 先立刻用现有数据起播（可能缺字段，但能显示歌名封面）。 */
        val start = index.coerceIn(0, tracks.lastIndex)
        val urls = graph.sessions.current?.let(graph::images)
        p.setMediaItems(tracks.map { toMediaItem(it, urls) }, start, 0)
        p.prepare()
        p.play()
        /**
         * 后台补齐「当前这首 + 邻近 2 首」的完整详情。
         * 补齐后替换对应的 MediaItem，不影响正在播放的进度。
         */
        graph.scope.launch {
            val around = (start - 2..start + 2).filter { it in tracks.indices }
            around.forEach { i ->
                val t = tracks[i]
                if (t.mediaSources.isNotEmpty()) return@forEach
                runCatching {
                    val full = graph.api.item(t.id)
                    items[full.id] = full
                    if (i == p.currentMediaItemIndex) {
                        runCatching { p.replaceMediaItem(i, toMediaItem(full, urls)) }
                    }
                }
            }
        }
    }

    fun append(tracks: List<Item>) {
        val urls = graph.sessions.current?.let(graph::images)
        tracks.forEach { items[it.id] = it }
        _player?.addMediaItems(tracks.map { toMediaItem(it, urls) })
    }

    /**
     * 用户口径（2026-11-27，#12）**大退后点播放必须能出声**。
     *
     * 用户原话：
     *  「大退后进入APP，直接点击下方的播放小条，进入播放界面点击播放按钮，不能播放没反应」
     *
     * 根因：恢复出来的 item 用的是**占位 URL**（`etkn-music://track/<id>`），
     * 真直链要在 `resolve()` 里才换。而 `resolve()` 只在 ExoPlayer
     * 真正去读数据源时才被调用 —— `pause()` 后 `play()` 只是恢复播放，
     * 不会重新走 prepare，所以永远是「点了没反应」。
     *
     * 修法：播放前检查当前 item 的 URL 是不是占位 scheme，
     * 是的话先用记忆里的 itemId 重新走一次完整播放（`resumeRemembered()`）。
     */
    /**
     * 用户口径（2026-11-27，#25）**点播放一定要有反应 —— 会话没就绪就先等它**。
     *
     * 用户原话：「重开之后点小播放条，进入播放页，点播放，没有任何反应，
     *            一点也不好使还是有问题，没整明白」
     *
     * 实测日志：`va1: 登录已过期，请重新登录`
     * App 刚启动时 token 还没恢复，`api.item()` / 直链解析全失败，
     * 而这里原来直接调 `p.play()` —— 播放器里没有可播的源，就静默失败。
     *
     * 现在：
     *  · 正在播 → 暂停
     *  · 当前 item 是占位 URL（恢复出来的）→ 走 `resumeRemembered()`
     *    （它会等登录态就绪再解析）
     *  · 其他情况 → prepare + play
     */
    fun toggle() {
        val p = _player ?: return
        if (p.isPlaying) {
            p.pause()
            return
        }
        val uri = p.currentMediaItem?.localConfiguration?.uri?.toString().orEmpty()
        /** 占位 URL 或根本没有 item → 都要走完整解析。 */
        if ((uri.startsWith("$SCHEME://") || p.currentMediaItem == null) && _remembered.value != null) {
            resumeRemembered()
            return
        }
        if (p.playbackState == Player.STATE_IDLE) {
            runCatching { p.prepare() }
        }
        p.play()
    }
    fun pause() { _player?.pause() }
    fun next() { _player?.seekToNextMediaItem() }
    fun previous() { _player?.let { if (it.currentPosition > 3_000) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun seekTo(ms: Long) { _player?.seekTo(ms) }
    fun skipTo(index: Int) { _player?.seekTo(index, 0) }
    fun toggleShuffle() { _player?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    /**
     * 用户口径（2026-11-27，#10）**车机卡片要显示歌词**。
     *
     * 场景：OPPO Car+ 通过 CarLife 连车机，车机那个音乐卡片能显示歌名但不显示歌词；
     * 而系统自带音乐播放器在同一张卡片上能滚动显示「当前这句歌词」。
     *
     * 系统音乐是怎么做到的：它把**当前歌词行**写进 MediaSession 的
     * `MediaMetadata.subtitle` 字段，车机读的就是这个字段。
     *
     * 所以这里也照做：歌词加载完 + 播放进度变化时，
     * 把「当前这句」塞进 metadata 的 subtitle，车机卡片就会跟着滚。
     *
     * 限流：只在「换歌」或「换行」时才 `replaceMediaItem`，
     * 不然每秒都改一次 metadata 会把 Session 刷爆。
     */
    private var lyricLines: List<Pair<Long, String>> = emptyList()
    private var lastLyricIndex: Int = -1
    private var lyricForItemId: String? = null

    /** 歌词加载后调它（UI 侧取到歌词时喂进来）。 */
    fun publishLyrics(itemId: String, lines: List<Pair<Long, String>>) {
        if (lyricForItemId == itemId && lyricLines == lines) return
        lyricForItemId = itemId
        lyricLines = lines
        lastLyricIndex = -1
        refreshLyricMetadata()
    }

    /** 播放进度变化时调它：换到新的一句才更新 metadata。 */
    fun refreshLyricMetadata() {
        val p = _player ?: return
        val idx = p.currentMediaItemIndex
        if (idx < 0) return
        val itemId = p.currentMediaItem?.mediaId ?: return
        if (itemId != lyricForItemId) return
        val pos = p.currentPosition
        val lineIdx = lyricLines.indexOfLast { it.first <= pos }.takeIf { it >= 0 } ?: -1
        if (lineIdx == lastLyricIndex) return
        lastLyricIndex = lineIdx
        val text = lyricLines.getOrNull(lineIdx)?.second.orEmpty()
        runCatching {
            val old = p.getMediaItemAt(idx)
            val newMeta = old.mediaMetadata.buildUpon()
                .setSubtitle(text.ifEmpty { null })
                .build()
            p.replaceMediaItem(idx, old.buildUpon().setMediaMetadata(newMeta).build())
        }
    }

    /**
     * 用户口径（2026-11-27，#7）**一键清空播放列表**。
     *
     * 清空播放器里的 MediaItem、`items` map、当前状态，
     * 并**把磁盘上的队列记忆也一起清掉** ——
     * 否则下次启动 `restoreForResumption()` 又会把队列装回来。
     */
    fun clearQueue() {
        _player?.let {
            it.stop()
            it.clearMediaItems()
        }
        items.clear()
        _state.value = MusicState()
        runCatching { prefs.edit().clear().apply() }
        _remembered.value = null
    }
    /**
     * 用户口径（2026-11-27，#4⑧）**左下角按钮要能切 4 种状态**：
     *
     * ```
     * 顺序播放 → 列表循环 → 单曲循环 → 随机播放 → 顺序播放 ...
     * ```
     *
     * 原来只有 `repeatMode` 的 3 态（OFF/ALL/ONE），漏了「随机」——
     * 而「随机」其实是 `shuffleModeEnabled` 的开关，是另一个维度。
     * 这里把两个维度合并成一条循环，用户点一下走一格。
     *
     * `playMode` 取值：0=顺序 1=列表循环 2=单曲循环 3=随机
     */
    fun cyclePlayMode() {
        val p = _player ?: return
        val next = (currentPlayMode() + 1) % 4
        when (next) {
            0 -> { p.shuffleModeEnabled = false; p.repeatMode = Player.REPEAT_MODE_OFF }
            1 -> { p.shuffleModeEnabled = false; p.repeatMode = Player.REPEAT_MODE_ALL }
            2 -> { p.shuffleModeEnabled = false; p.repeatMode = Player.REPEAT_MODE_ONE }
            3 -> { p.shuffleModeEnabled = true; p.repeatMode = Player.REPEAT_MODE_ALL }
        }
        publish()
    }

    /** 当前处于哪一种（0=顺序 1=列表循环 2=单曲循环 3=随机）。 */
    fun currentPlayMode(): Int {
        val p = _player ?: return 0
        return when {
            p.shuffleModeEnabled -> 3
            p.repeatMode == Player.REPEAT_MODE_ONE -> 2
            p.repeatMode == Player.REPEAT_MODE_ALL -> 1
            else -> 0
        }
    }

    /** 兼容旧调用：切到下一个循环模式。 */
    fun cycleRepeat() = cyclePlayMode()

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
        /**
         * 用户口径（2026-11-27，#21）**队列不能因为 items map 缺项就被清空**。
         *
         * 原来 `mapNotNull { items[mediaId] }` —— playbackInfo 一失败、items 里
         * 没有这个 id，该首就被丢掉，队列变短甚至空。
         *
         * 现在：找不到就用地记忆里的信息兜底，保证「队列长度 = player 的 mediaItemCount」。
         */
        val queue = (0 until p.mediaItemCount).mapNotNull { i ->
            val mediaId = p.getMediaItemAt(i).mediaId
            items[mediaId] ?: run {
                /** 兜底：从记忆的队列里按 id 找回来。 */
                val mem = _remembered.value
                val pos = mem?.queueIds?.indexOf(mediaId) ?: -1
                if (mem != null && pos >= 0) {
                    itemFromMemory(
                        MusicMemory(
                            itemId = mediaId,
                            name = mem.queueNames.getOrNull(pos).orEmpty().ifEmpty { mediaId },
                            artist = mem.queueArtists.getOrNull(pos).orEmpty(),
                            album = "",
                            primaryTag = mem.queueTags.getOrNull(pos)?.takeIf { it.isNotBlank() },
                            positionMs = 0L,
                            durationMs = 0L,
                            sourceId = null,
                        ),
                    ).also { items[mediaId] = it }
                } else null
            }
        }
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
            /** #32⑤ 缓存进度：从当前播放位置往后，本地已落盘的毫秒数。 */
            cachedPosition = cachedAheadMs(current?.id, p.currentPosition, p.duration),
        )
        /** 用户口径（#10）进度变化 → 同步车机卡片上的歌词行。 */
        runCatching { refreshLyricMetadata() }
        /**
         * 用户口径（#1）**publish 时把队列状态显式推给 Session**。
         *
         * 实测问题：player 里 mediaItemCount=1、state=PAUSED，
         * 但 `dumpsys media_session` 报 `state=NONE(0), size=0`。
         * ColorOS 的 `createActionsFromState: curState = 0` 据此判定无内容 → 播放键灰。
         *
         * 这里在每次 publish 时用一个无害的状态变更触发 Media3 重算 PlaybackState：
         * 只在「播放器有内容但状态是 IDLE」时补一次 prepare。
         */
        if (p.mediaItemCount > 0 && p.playbackState == Player.STATE_IDLE) {
            runCatching { p.prepare() }
        }
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

        /** 用户口径（#43）队列序列化用的单元分隔符（歌曲名里不会出现）。 */
        private const val SEP = "\u001F"
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
    /**
     * 用户口径（2026-11-27，#43）**整个播放队列也要记住**。
     *
     * 修的 bug（用户原话）：
     *  「APP 彻底退出之后，重新打开后，这个右边的那个播放列表，打开是空的，
     *    没有记忆功能，正常必须要记忆，有播放列表的记忆」
     *
     * 原先只存了「当前那一首」，重启后播放器里只有 1 条（或 0 条）MediaItem，
     * 队列自然是空的。现在把整条队列的 id 和名字都存下来。
     */
    val queueIds: List<String> = emptyList(),
    val queueNames: List<String> = emptyList(),
    val queueArtists: List<String> = emptyList(),
    val queueTags: List<String> = emptyList(),
)
