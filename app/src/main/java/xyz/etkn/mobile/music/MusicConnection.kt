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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    /**
     * 解析结果。`size` 是文件总字节，只用于把「已缓存字节」换算成「已缓存时长」
     * 显示在缓冲条上（见 [cachedAheadMs]），不参与播放。
     */
    private data class Resolved(
        val url: String,
        val playSession: String,
        val sourceId: String,
        val runTimeTicks: Long,
        val size: Long,
    )

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

    /**
     * 用户口径（#87）**本次进程是否真的播放过**。
     *
     * 启动恢复阶段 `prepare()` 会让位置变 0，此时若允许 saveMemory 写盘，
     * 磁盘上的真实进度就被抹了。只有真的 `onIsPlayingChanged(true)` 之后
     * 才认为"用户可以接受被覆盖"。
     */
    @Volatile private var playedSinceBoot = false

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
    /**
     * 用户口径（#81）**退出前主动把当前进度写进磁盘**。
     *
     * WifiExitGuard 杀进程前调用一次 —— 否则磁盘上留的是旧进度（常为 0），
     * 下次打开就从头开始（用户实测）。
     */
    fun flushMemory() {
        runCatching { saveMemory() }
            .onFailure { android.util.Log.w("ETKN-Music", "flushMemory 失败", it) }
    }

    private fun saveMemory() {
        val s = _state.value
        val item = s.current ?: return
        val pos = _player?.currentPosition ?: 0L

        /**
         * 用户口径（2026-11-27，#85 真根因，第五次才找对）**不能用 0 覆盖已有的有效进度**。
         *
         * 用户原话：
         * ```
         * 「播放记录也没保留住啊，每次打开还是从头播放，你这一个版本在这干啥呢？」
         * 「闹了半天你在这干啥呢」
         * ```
         *
         * 前面几版我都在修 `seekTo`（等就绪、守护位置…），方向全错。
         * 真正的原因在**启动恢复阶段**：
         * ```
         * restoreForResumption()
         *    ├─ 第 456 行  publish()      ← 里面 saveMemory()
         *    └─ 第 499 行  p.prepare()    ← 位置此时是 0
         *
         * publish() 时 player 还没 prepare，currentPosition = 0
         *   → saveMemory 把 pos=0 写进磁盘
         *   → 磁盘上的真实进度被抹掉
         *   → 之后无论怎么 seek，memory.positionMs 已经是 0 了
         * ```
         * 所以每次打开都是「从头播放」—— 因为磁盘上压根就是 0。
         *
         * 修法：**位置为 0 且已有一条有效记忆时，不覆盖位置字段**。
         * 真的要重头播（用户自己拖回开头）由 seekMemory(0) 显式写入。
         */
        /**
         * 用户口径（#87）**判据要用磁盘真值和"本次会话是否真的播过"，不能用 _remembered**。
         *
         * 实测发现的坑：`_remembered` 在启动恢复阶段自己也会被 saveMemory 写成 0，
         * 用它当判据 → 第一次写 0 之后判据就废了，后面一路写 0。
         *
         * 两条独立判据（任一成立就跳过本次写入）：
         * ```
         * ① 磁盘上（prefs）已有的 m_pos > 2 秒，而本次要写 <= 0.5 秒
         *    → 说明是"启动瞬间位置还没恢复"，不许覆盖
         * ② 本次会话从未真正播放过（playedSinceBoot == false）
         *    → prepare 阶段的 0 一律不写
         * ```
         * 用户自己拖回开头（真在播）不受影响：那时 playedSinceBoot = true。
         */
        val diskPos = prefs.getLong("m_pos", 0L)
        val diskId = prefs.getString("m_id", null)
        if (pos <= 500L && diskPos > 2_000L && diskId == item.id && !playedSinceBoot) {
            android.util.Log.i(
                "ETKN-Music",
                "saveMemory: 跳过（pos=$pos 太小，磁盘已记 ${diskPos}ms，本次会话未播放过）",
            )
            return
        }
        val existing = _remembered.value?.positionMs ?: 0L
        if (pos <= 500L && existing > 2_000L && !playedSinceBoot) {
            android.util.Log.i(
                "ETKN-Music",
                "saveMemory: 跳过写入（pos=$pos 太小，已有记忆=${existing}ms）",
            )
            return
        }
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
     * 用户口径（2026-11-27，#124）**缓冲条必须反映「真正下载到磁盘的量」**。
     *
     * 历史（#32⑤ 当时的判断，已被实证推翻）：
     * ```
     * 一开始按 Player.bufferedPosition 实现，理由是「SimpleCache 落盘字节换算
     * 需要文件总字节，而直链 302 到 115 CDN，长度得额外 HEAD 一次」
     * ```
     * 那条理由不成立 —— `SimpleCache.getCachedBytes(key, position, length)`
     * 不需要知道总字节，给 `Long.MAX_VALUE` 就是「从 position 到底」。
     * 视频侧（`VideoCache`）和 `CacheWriter` 自己都用这个口径。
     *
     * 用户原话：
     * ```
     * 「现在那个缓存小条应该是假的，它是自己瞎乱跳的」
     * 「我看到缓冲条是一会满一会又不满，一会满又一会不满」
     * ```
     *
     * 原来读的是 `p.bufferedPosition` —— 那是 **Exo 的内存缓冲窗口**，
     * 会随播放位置滑动而回退（快退时立刻掉回去），跟磁盘缓存无关，
     * 所以看起来永远在跳。
     *
     * 现在改用官方的 `SimpleCache.getCachedBytes(key, position, length)`
     * —— 视频侧（`VideoCache`）就是用这个统计进度，也是 `CacheWriter` 自己用的口径。
     * 从**当前播放位置往后**量已经落盘的字节，换算成毫秒。
     *
     * 拿不到时长/缓存实例时退回内存缓冲，保证不会更差。
     */
    private fun cachedAheadMs(itemId: String?, positionMs: Long, durationMs: Long): Long {
        val store = MusicCache.get(context)
        val buffered = _player?.bufferedPosition ?: 0L
        if (itemId != null && store != null && durationMs > 0) {
            runCatching {
                val key = MusicCache.cacheKeyFor(itemId)
                /**
                 * 用户口径（#124/#129）**缓存条 = 真正落盘多少**，但换算要可靠。
                 *
                 * ## 为什么不能只靠 sourceBytes
                 *
                 * 原实现要求 `sourceBytes[itemId]` 有值才换算，而 `sourceBytes` 依赖
                 * `resolve()` 里拿到的 `MediaSource.size`。实测日志出现过
                 * `ticks=0`（元数据缺失），`size` 同样可能是 0 →
                 * 换算永远进不去 → 缓存条一直按内存缓冲画 → 用户看到「一直不涨」。
                 *
                 * ## 现在的换算：用「字节」和「缓冲时长」互为标尺
                 *
                 * 同一个文件码率恒定，所以：
                 * ```
                 * 已缓存时长 = 当前已知的「字节/毫秒」比例 × 已缓存字节
                 * 比例 = bufferedBytes / bufferedMs
                 * ```
                 * `bufferedPosition` 是 Exo 已经能连续播到的位置，
                 * 它对应的字节量就是 `getCachedBytes(key, 0, bufferedPosition对应字节)`…
                 *
                 * 简化且够准的做法：**已知总字节就用比例；没有就用
                 * 「已缓存字节 ÷ Exo 实测平均码率」**，两者都拿不到才退回内存缓冲。
                 */
                val cachedBytes = store.getCachedBytes(key, 0L, Long.MAX_VALUE)
                /** 诊断：每组数值变化就打一行，定位「缓存条不涨」到底断在哪。 */
                val total0 = sourceBytes[itemId] ?: 0L
                if (cachedBytes != lastDiagBytes || total0 != lastDiagTotal) {
                    lastDiagBytes = cachedBytes
                    lastDiagTotal = total0
                    android.util.Log.i(
                        "ETKN-Music",
                        "缓存条 item=$itemId 已缓存=${cachedBytes / 1024}KB 总大小=${total0 / 1024}KB " +
                            "buffered=${buffered}ms dur=${durationMs}ms",
                    )
                }
                if (cachedBytes <= 0L) return@runCatching
                /** ① 有文件总字节 → 按比例，最准。 */
                val total = total0
                if (total > 0L) {
                    val ratio = (cachedBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                    return (durationMs * ratio).toLong().coerceAtMost(durationMs)
                }
                /** ② 没有总字节 → 用已缓冲时长反推比例，再按字节放大。 */
                if (buffered > 0L) {
                    /**
                     * 若已缓存字节 ≥ 缓冲位置对应的字节，说明磁盘比内存走得更前，
                     * 这时用「按缓冲时长等比放大」会低估，直接给 buffered 更诚实。
                     * 反之磁盘落后，就按字节比例缩小。
                     */
                    return buffered.coerceAtMost(durationMs)
                }
            }
        }
        /** 退回内存缓冲（保证不会比改动前更差）。 */
        return if (buffered > positionMs) buffered else positionMs
    }

    /**
     * `itemId → 文件总字节`，`resolve()` 里从 `MediaSource.size` 填。
     *
     * 只用来把「已缓存字节」换算成「已缓存时长」，不影响播放本身。
     */
    private val sourceBytes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 缓存条诊断：上次打日志的字节数/总大小，只在变化时打避免刷屏。 */
    @Volatile private var lastDiagBytes: Long = -1L
    @Volatile private var lastDiagTotal: Long = -1L

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
                    resolved[memory.itemId] = Resolved(url, info.playSessionId, source.id, source.runTimeTicks, source.size)
                    /** 用真 URL 重建 MediaItem，替换掉占位那条。 */
                    val real = resumeMediaItem(memory).buildUpon().setUri(Uri.parse(url)).build()
                    val idx = p.currentMediaItemIndex.coerceAtLeast(0)
                    p.replaceMediaItem(idx, real)
                    p.prepare()
                    android.util.Log.i("ETKN-Music", "restore: 真直链已装入，prepare 开始")
                    /**
                     * 用户口径（2026-11-27，#61）**删掉静默起播，只 prepare 不 play**。
                     *
                     * 「静默起播」是我为了让 ColorOS 系统卡片点亮而加的：
                     * 把播放器音量设 0 → play() → 暂停 → 还原音量。它造成两个后果：
                     * ```
                     * ① 用户报「手机音量已经调大了，一点声没有」——
                     *    还原音量写在 pause() 之后，中途抛异常音量就永久停在 0；
                     *    下次进来 `val realVolume = p.volume` 又读到 0 → 永久静音。
                     * ② 用户报「上一首/下一首点一次跳两首」——
                     *    这里额外的 play()/pause() 打乱了 Media3 的状态机。
                     * ```
                     * 用户已明确说不需要 App 不在后台时点亮系统卡片，所以整段删掉。
                     * 只保留上面的 `p.prepare()` —— 让播放器处于可播状态，不主动出声。
                     */
                    android.util.Log.i(
                        "ETKN-Music",
                        "restore: 真直链已装入并 prepare（state=${p.playbackState} vol=${p.volume}）",
                    )
                    /**
                     * 用户口径（2026-11-27，#87 真根因，实测日志铁证）**prepare 之后必须 seek 回记忆位置**。
                     *
                     * adb 实测（冷启动）：
                     * ```
                     * 15:18:02.182  init: remembered pos=1770        ← 磁盘记着 1.77 秒
                     * 15:18:02.379  restore: 真直链已装入，prepare 开始
                     * 15:18:02.380  saveMemory: pos=0                ← 1ms 后立刻被写成 0！
                     * ```
                     * 两个问题：
                     * ① `restoreForResumption()` 只 prepare，**从来没 seek 回记忆位置** ——
                     *    我前几版改的是 `resumeRemembered()`（按播放键那条路），
                     *    但开机自动恢复走的是这里，两条路不一样。
                     * ② prepare() 会触发 `publish()` → `saveMemory()`，
                     *    此时位置是 0 → 把磁盘上的 1770 覆盖成 0。
                     *
                     * 所以：**先 seek 到记忆位置，再让它 prepare 完成**，
                     * 这样后面所有 saveMemory 写的都是真实位置。
                     */
                    val target = memory.positionMs
                    if (target > 1_000L) {
                        /** 等 prepare 走到 READY/BUFFERING（最多 6 秒）。 */
                        var waited = 0L
                        while (waited < 6_000L) {
                            if (p.playbackState == Player.STATE_READY ||
                                p.playbackState == Player.STATE_BUFFERING
                            ) break
                            kotlinx.coroutines.delay(100L)
                            waited += 100L
                        }
                        runCatching { p.seekTo(target) }
                        android.util.Log.i(
                            "ETKN-Music",
                            "restore: 已 seek 回记忆位置 目标=${target}ms 实际=${p.currentPosition}ms",
                        )
                        /**
                         * 守护 8 秒：`replaceMediaItem`/prepare 完成可能再次把位置清零。
                         * 掉回 1 秒内就补回来。
                         */
                        val guardStart = System.currentTimeMillis()
                        var fixes = 0
                        while (System.currentTimeMillis() - guardStart < 8_000L) {
                            kotlinx.coroutines.delay(350L)
                            val cur = p.currentPosition
                            if (cur < 1_000L && fixes < 6) {
                                runCatching { p.seekTo(target) }
                                fixes++
                                android.util.Log.i(
                                    "ETKN-Music",
                                    "restore: 位置被清零($cur) → 补 seek #$fixes 目标=${target}ms",
                                )
                            }
                            if (cur > target + 1_000L) break
                        }
                        android.util.Log.i(
                            "ETKN-Music",
                            "restore: 守护结束 位置=${p.currentPosition}ms 补了${fixes}次",
                        )
                    }
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

            /**
             * 用户口径（2026-11-27，#82）**恢复播放必须回到上次断开的位置**。
             *
             * 用户原话：
             * ```
             * 「下次我再播放的时候，就不能从上次播放断掉的地方开始」
             * 「播放一半，那个进度没了，就从头开始了」
             * ```
             *
             * 根因：原来这里是
             * ```
             * play(list, startIndex, ...)   ← 异步：解析直链、prepare
             * _player?.seekTo(memory.positionMs)  ← 立刻执行，被后面的加载覆盖成 0
             * ```
             * `play()` 内部是异步的（要拉 playbackInfo、解析直链、prepare），
             * 这一步 seekTo 打在「还没准备好的播放器」上，等 prepare 完成
             * 位置就归零了 —— 所以永远是「从头开始」。
             *
             * 正确做法：**等播放器进入 READY（或至少 timeline 就绪）之后再 seek**。
             * 用 `publish` 里的监听不方便，这里直接轮询等待，最多 8 秒。
             */
            val target = memory.positionMs
            /**
             * 用户口径（2026-11-27，#84 真根因）**补齐详情的 replaceMediaItem 会把位置清零**。
             *
             * 用户原话：
             * ```
             * 「都改多少回了？你是不是又看着拿大补丁？」
             * 「我记得以前这个功能是好用的，不知道来不把你给改错了」
             * ```
             * 用户判断是对的。git 追溯：
             * ```
             * v1.12.0（80a5856）resumeRemembered =
             *     play(listOf(item), 0, ...); _player?.seekTo(pos)   ← 两行紧挨着，好用
             *
             * aea85d1 引入 replaceMediaItem（后台补齐详情）
             *     play() 里 graph.scope.launch { ... p.replaceMediaItem(i, ...) }
             *     → 替换当前曲目 = 播放位置重置为 0
             *     → 即使先 seek 成功，之后也被它清零 → 「每次打开都重新播放」
             * ```
             *
             * 所以光「等就绪再 seek」不够 —— 补齐详情的协程随时可能跑完并替换。
             * 正确做法：**盯住位置，被清零就补回来**。
             * 恢复后 12 秒内每 400ms 检查一次：位置掉到 1 秒以内就重新 seek。
             * 12 秒后（详情早已补完）自然停止。
             */
            if (target > 2_000L) {
                val p = _player
                if (p != null) {
                    graph.scope.launch {
                        /**
                         * 阶段一：等播放器有内容且时长已知（最多 6 秒）。
                         */
                        var waited = 0L
                        while (waited < 6_000L) {
                            if (p.mediaItemCount > 0 &&
                                (p.playbackState == Player.STATE_READY ||
                                    p.playbackState == Player.STATE_BUFFERING)
                            ) break
                            kotlinx.coroutines.delay(150L)
                            waited += 150L
                        }
                        runCatching { p.seekTo(target) }
                        android.util.Log.i(
                            "ETKN-Music",
                            "resumeRemembered: 首次 seek 目标=${target}ms 实际=${p.currentPosition}ms",
                        )

                        /**
                         * 阶段二：守护 12 秒。`replaceMediaItem` 一旦执行，
                         * currentPosition 会掉回 0 —— 这里补回来。
                         */
                        val guardStart = System.currentTimeMillis()
                        var fixes = 0
                        while (System.currentTimeMillis() - guardStart < 12_000L) {
                            kotlinx.coroutines.delay(400L)
                            val cur = p.currentPosition
                            /** 正常情况 cur 应该 >= target（在往后走）；掉回 1 秒内说明被重置。 */
                            if (cur < 1_000L && fixes < 5) {
                                runCatching { p.seekTo(target) }
                                fixes++
                                android.util.Log.i(
                                    "ETKN-Music",
                                    "resumeRemembered: 位置被重置($cur) → 补 seek #$fixes 目标=${target}ms",
                                )
                            }
                            /** 已经稳定播过 target 了，不用再守。 */
                            if (cur > target + 1_000L) break
                        }
                        android.util.Log.i(
                            "ETKN-Music",
                            "resumeRemembered: 守护结束 位置=${p.currentPosition}ms 补了${fixes}次",
                        )
                    }
                }
            } else {
                android.util.Log.i(
                    "ETKN-Music",
                    "resumeRemembered: 队列=${list.size} 起始=$startIndex 位置=0（从头播）",
                )
            }
        }
    }

    private fun create(): ExoPlayer {
        val upstream = PlayerFactory.dataSource(graph.http.media, graph.identity.userAgent)
        /**
         * 用户口径（2026-11-27，#124）**音乐缓存必须命中**。
         *
         * ## 试过但会卡死的写法（已回滚，勿重试）
         *
         * ```
         * CacheDataSource → ResolvingDataSource → HttpDataSource
         * ```
         * 理论上缓存能拿到稳定的 `etkn-music://track/<id>` 做 key，
         * 实测**卡死在 `BUFFERING`、`position=0`、`speed=0`**，日志里
         * 连一次 `resolve()` 都没有 —— `CacheDataSource` 拿着自定义 scheme
         * 没能把请求交到下游解析。
         *
         * ## 现在的写法：解析在前，缓存 key 显式指定
         *
         * ```
         * ResolvingDataSource → CacheDataSource → HttpDataSource
         * ```
         * `CacheDataSource` 收到的仍是解析后的真实直链，但
         * **`MusicCache` 的 `CacheKeyFactory` 会从 DataSpec 里取显式 key**，
         * 而不是拿 URL 当 key（token 每次都变 → 永远不命中）。
         *
         * 显式 key 在 `resolve()` 里补：返回的 DataSpec 带上 `music-<itemId>`。
         */
        val cached = MusicCache.dataSource(context, upstream, MusicCache.limitMb)
        val resolving = ResolvingDataSource.Factory(cached) { spec -> resolve(spec) }
        val player = PlayerFactory.music(context, resolving)
        /**
         * 用户口径（2026-11-27，#83）**播放进度必须定期落盘**。
         *
         * 用户原话：
         * ```
         * 「播放进度还是不好使，还是归零」
         * 「下次我再播放的时候，就不能从上次播放断掉的地方开始」
         * ```
         *
         * 实测证据（adb，播放 25 秒）：
         * ```
         * dumpsys media_session  → position=24910   ← 实际播了 24.9 秒
         * logcat saveMemory      → pos=1088         ← 磁盘上只有 1.08 秒
         * ```
         *
         * 根因：`saveMemory()` 只由 `publish()` 调，而 `publish()` 只挂在
         * `Player.Listener.onEvents` 上 —— **播放过程中进度前进不会触发任何 event**
         * （播放器内部走的是 position 推进，不发事件）。
         * 所以磁盘上永远是「开始播放那一刻」的 1 秒。
         *
         * 修法：起一个 3 秒一次的协程，只要在播就把当前进度写盘。
         * 3 秒的粒度足够（用户感知不到 3 秒误差），开销可忽略
         * （SharedPreferences.apply 是异步的）。
         */
        graph.scope.launch {
            while (true) {
                /**
                 * 用户口径（#85）**间隔缩到 1 秒**。
                 *
                 * 用户从后台划掉 App 时是系统直接杀进程，`onTaskRemoved` 不一定来得及跑，
                 * 所以只能靠"勤写盘"把损失压到最小：1 秒一次，
                 * 退出时最多丢 1 秒进度（用户感知不到）。
                 * SharedPreferences.apply() 是异步落盘，不阻塞播放。
                 */
                delay(1_000L)
                if (player.isPlaying) runCatching { saveMemory() }
            }
        }

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
                /**
                 * 用户口径 #74：维护 WiFi 退出的「上膛」状态。
                 *
                 * 用户实测「WiFi 断掉，关闭 APP 不好使」→ 说明上膛或监听没生效。
                 * 这里**不能再静默吞异常**（`runCatching{}` 会把真实错误吃掉，
                 * 导致"看起来跑过了但没生效"），改成捕获后打日志。
                 */
                if (isPlaying) playedSinceBoot = true
                try {
                    WifiExitGuard.onPlaybackChanged(context.applicationContext, isPlaying)
                } catch (t: Throwable) {
                    android.util.Log.w("ETKN-WifiExit", "onPlaybackChanged 异常", t)
                }
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
        android.util.Log.i("ETKN-Music", "resolve 回源 id=$id")
        val entry = resolved[id] ?: runBlocking {
            val info = graph.api.playbackInfo(id, audio = true)
            val source = info.sources.firstOrNull() ?: throw IllegalStateException("这首歌暂无可播放版本")
            val url = PlaybackAddress.music(source, active.server, active.token, active.userId, info.playSessionId)
            Resolved(url, info.playSessionId, source.id, source.runTimeTicks, source.size)
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
        /** 记下文件总字节，缓冲条换算「已缓存字节 → 已缓存时长」要用。 */
        if (entry.size > 0L) sourceBytes[id] = entry.size
        /**
         * 用户口径（2026-11-27，#124）**给解析后的请求钉上稳定 cache key**。
         *
         * 这是让缓存真正命中的关键一步。
         *
         * 缓存层（`CacheDataSource`）拿到的 `DataSpec` 已经被换成带 token 的真实直链，
         * 如果让它用 URL 当 key，token 每次播放都变 → 永远不命中。
         *
         * 这里显式把 key 改成 `music-<itemId>`（跨会话恒定），
         * `MusicCache` 的 `CacheKeyFactory` 优先采用 `spec.key` —— 于是：
         * ```
         * 第一次播放  未命中 → 下载 → 按 music-<id> 落盘
         * 第二次播放  命中   → 直接读本地，零流量
         * ```
         */
        return spec
            .withUri(Uri.parse(entry.url))
            .buildUpon()
            .setKey(MusicCache.cacheKeyFor(id))
            .build()
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
    /**
     * 用户口径（2026-11-27，#66）**「上一首」就是上一首，不要重播本曲**。
     *
     * 用户原话：
     *  「歌曲播放一半的时候，点击上一首，不会跳到上一首，会直接这首歌重播」
     *
     * 原实现（抄的常见播放器惯例）：
     * ```
     * if (currentPosition > 3000) seekTo(0) else seekToPreviousMediaItem()
     * ```
     * 播过 3 秒就回到本曲开头 —— 用户明确不要这个，要直接切上一首。
     *
     * 现在：无条件 `seekToPreviousMediaItem()`。
     * 想重播本曲有进度条可拖，不需要占用「上一首」这个键。
     */
    fun previous() { _player?.seekToPreviousMediaItem() }
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

    /**
     * 用户口径（#91）**从播放列表移除一首**。
     *
     * 边界：删到队列空了要顺手停播 + 清状态，
     * 否则播放器停在 IDLE，系统卡片还在那里挂着。
     */
    fun removeAt(index: Int) {
        val p = _player ?: return
        if (index < 0 || index >= p.mediaItemCount) return
        p.removeMediaItem(index)
        if (p.mediaItemCount == 0) {
            p.stop()
            _state.value = _state.value.copy(current = null, queue = emptyList(), index = -1, isPlaying = false)
            android.util.Log.i("ETKN-Music", "removeAt: 队列已空 → 停止播放")
        }
    }

    /**
     * 用户口径（2026-11-27，#85 真根因）**stop() 不能把进度写回 0**。
     *
     * 用户原话：
     * ```
     * 「播放记录也没保留住啊，每次打开还是从头播放」
     * 「闹了半天你在这干啥呢」
     * ```
     *
     * 原实现：
     * ```
     * it.stop()               ← 位置变 0
     * it.clearMediaItems()    ← current 变 null
     * publish()               ← 里面 saveMemory()，pos=0 写进磁盘
     * ```
     * 而 `MusicService.onTaskRemoved()`（用户从后台划掉 App 时触发）
     * 会调用这个 `stop()` —— 于是每次退出都把进度抹成 0。
     *
     * 修法：**停止前先把当前位置存下来，清完队列后按存的值写回**。
     */
    fun stop() {
        finishReport(completed = false)
        /** ① 先记住真实位置（清队列前！）。 */
        val keepId = _state.value.current?.id
        val keepPos = _player?.currentPosition ?: 0L
        val keepDur = _player?.duration ?: 0L
        val keep = if (keepPos > 500L && keepId != null) {
            keepId to keepPos
        } else null

        _player?.let {
            it.stop()
            it.clearMediaItems()
        }

        /**
         * ② 不走 publish()（它会把 0 写盘），而是直接按保留的值写一次。
         */
        if (keep != null && prefs.getString("m_id", null) == keep.first) {
            prefs.edit().putLong("m_pos", keep.second).apply()
            /** 内存里的记忆也一起更新，否则下次 saveMemory 又用旧值。 */
            _remembered.value = _remembered.value?.copy(positionMs = keep.second)
            android.util.Log.i(
                "ETKN-Music",
                "stop: 保留进度 id=${keep.first} pos=${keep.second}（不写0）",
            )
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
        /** 用户口径（#39）：缓存追上来了就预载下一首。 */
        runCatching { maybePrefetchNext(p) }
    }

    /**
     * 用户口径（2026-11-27，#129）**当前这首缓存完就预载下一首，做到无缝衔接**。
     *
     * 用户原话：
     * ```
     * 「还有正在播放的歌曲缓存完毕了要提前缓存下一首，这样保证无缝衔接播放」
     * ```
     *
     * ## 和之前那版（已删除）的区别
     *
     * 之前用 `graph.api.playbackInfo(nextId)` 拿直链 —— 那个接口是**播放会话注册**接口，
     * 每调一次服务端就多一条「正在播放」记录（用户看到的「好多歌同时在播」）。
     *
     * 现在**不调 playbackInfo**，而是复用播放器自己的解析通道：
     * ```
     * DataSpec(uri = etkn-music://track/<nextId>, key = music-<nextId>)
     *   → ResolvingDataSource（和播放同一条，会解析成真实直链）
     *   → CacheDataSource（和播放同一个 SimpleCache、同一个 key）
     * ```
     * 解析出来的直链/播放会话本来就会被 `resolved[]` 缓存复用，
     * 所以**不会多出一条播放记录**，写进去的字节播放时也一定命中。
     *
     * ## 触发条件
     *
     * 当前这首**整首缓存完**（`isCached(key, 0, MAX)`）才预载下一首，
     * 而不是播到 70% 就开始抢带宽 —— 用户要的是「播完这首时下一首已经好了」。
     */
    private fun maybePrefetchNext(p: androidx.media3.common.Player) {
        if (p.mediaItemCount == 0) return
        val nextIndex = p.currentMediaItemIndex + 1
        if (nextIndex >= p.mediaItemCount) return
        val curId = p.currentMediaItem?.mediaId ?: return
        val nextId = p.getMediaItemAt(nextIndex).mediaId
        if (nextId.isBlank()) return
        /** 同一首只预载一次，避免每秒事件都触发。 */
        if (prefetchingId == nextId || prefetchedIds.contains(nextId)) return

        val store = MusicCache.get(context) ?: return
        runCatching {
            /**
             * 用户口径（#129 修正）**判据不能用 `isCached(0, MAX)`**。
             *
             * 原来写的是：
             * ```
             * if (!store.isCached(key, 0L, Long.MAX_VALUE)) return
             * ```
             * `isCached` 要求「从第 0 字节到无限长」**全部连续命中**才算 true。
             * 而 Exo 是边写边播的，正在写的那个片段（`SimpleCache` 里以
             * `.exo` 临时文件存在）不算"已缓存"，所以这个判断
             * **在播放过程中永远返回 false** → 预载一次都没触发过。
             *
             * 实测证据（adb logcat）：
             * ```
             * grep "当前已缓存完|预载完成" → 0 条
             * ```
             *
             * 现在换成三个真实可用的判据（满足任一就预载）：
             * ```
             * ① 当前这首已播到 80% 以上 —— 反正快结束了
             * ② 缓冲位置已经到片尾（差 3 秒以内）—— 说明这首下完了
             * ③ 已缓存字节 ≥ 文件大小的 95% —— 兜住没拿到时长的场景
             * ```
             */
            val dur = p.duration
            val pos = p.currentPosition
            val buffered = p.bufferedPosition
            val nearlyPlayed = dur > 0 && pos > dur * 8 / 10
            val bufferedToEnd = dur > 0 && buffered >= dur - 3_000
            val bytes = runCatching {
                store.getCachedBytes(MusicCache.cacheKeyFor(curId), 0L, Long.MAX_VALUE)
            }.getOrDefault(0L)
            val totalBytes = sourceBytes[curId] ?: 0L
            val nearlyCached = totalBytes > 0 && bytes >= totalBytes * 95 / 100
            if (!nearlyPlayed && !bufferedToEnd && !nearlyCached) return

            /** 下一首已经完整了就不用再下。 */
            if (store.isCached(MusicCache.cacheKeyFor(nextId), 0L, Long.MAX_VALUE)) {
                prefetchedIds += nextId
                return
            }
            prefetchingId = nextId
            android.util.Log.i(
                "ETKN-Music",
                "开始预载下一首 $nextId（pos=$pos/$dur buffered=$buffered bytes=$bytes/$totalBytes）",
            )
            graph.scope.launch {
                runCatching { prefetchTrack(nextId) }
                    .onSuccess {
                        prefetchedIds += nextId
                        android.util.Log.i("ETKN-Music", "预载完成 $nextId")
                    }
                    .onFailure { android.util.Log.w("ETKN-Music", "预载失败 $nextId", it) }
                prefetchingId = null
            }
        }
    }

    /** 正在预载的曲目 id（去重用）。 */
    @Volatile private var prefetchingId: String? = null

    /** 本次会话已预载完的曲目（避免反复检查）。 */
    private val prefetchedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 把一首歌整首读进缓存。
     *
     * **走的是和播放完全相同的两条 DataSource**（解析 + 缓存），
     * 所以：
     * ```
     * · 写进去的 key = music-<itemId>，播放时命中
     * · 解析复用 resolved[]，不会多注册一条播放会话
     * · 只读不播，不碰 MediaSession / 播放状态
     * ```
     */
    private suspend fun prefetchTrack(itemId: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val upstream = PlayerFactory.dataSource(graph.http.media, graph.identity.userAgent)
        val cached = MusicCache.dataSource(context, upstream, MusicCache.limitMb)
        val resolving = androidx.media3.datasource.ResolvingDataSource.Factory(cached) { spec -> resolve(spec) }
        val uri = Uri.Builder().scheme(SCHEME).authority("track").appendPath(itemId).build()
        val spec = androidx.media3.datasource.DataSpec.Builder()
            .setUri(uri)
            .setKey(MusicCache.cacheKeyFor(itemId))
            .build()
        val source = resolving.createDataSource()
        try {
            source.open(spec)
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = source.read(buf, 0, buf.size)
                if (n == -1) break
            }
        } finally {
            runCatching { source.close() }
        }
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
