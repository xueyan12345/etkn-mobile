package xyz.etkn.mobile.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import okhttp3.OkHttpClient
import xyz.etkn.mobile.data.settings.DecoderPreference
import xyz.etkn.mobile.player.bluray.BlurayMediaSourceFactory
import xyz.etkn.mobile.player.cache.VideoCache
import xyz.etkn.mobile.player.cache.httpFactory
import java.util.Locale

/**
 * Single place that assembles ExoPlayer for video and music.
 *
 * - Hardware MediaCodec first; NextLib's bundled FFmpeg covers formats the device cannot decode
 *   (TrueHD, DTS, …) unless the user pins hardware-only or software-first.
 * - Media is fetched through the shared OkHttp pool (HTTP/2, keep-alive) with the app UA.
 * - Buffering is tuned for LAN/remote direct streams: fast start, generous forward buffer, but
 *   capped by size as well as time (a 90 s buffer of an 80 Mbit/s remux would be ~900 MB of heap).
 * - CPU and Wi-Fi stay awake only while playing, so 后台播放 with the screen off does not stall.
 */
object PlayerFactory {

    /**
     * 用户口径（2026-11-23）**这是 Malformed URL 的真根因**。
     *
     * 之前数据源一律是 OkHttpDataSource —— 它只会处理 http/https，
     * 内部拿 OkHttp 的 HttpUrl 去解析 URI。而命中磁盘缓存时我们给的是
     * file:///data/.../mediafiles/xxx，HttpUrl 解析必然抛 Malformed URL
     * → ERROR_CODE_FAILED_RUNTIME_CHECK → 自动换核到 mpv（起播更慢）。
     *
     * 日志铁证：
     *   version-35806  cache hit=true  → Malformed URL → 切 mpv
     *   version-35138  cache hit=false → ExoPlayer 721ms 正常起播
     *
     * 修法：按 scheme 分发。file → FileDataSource（本地直读），
     * 其余 → OkHttpDataSource（走共享连接池）。Media3 的 DefaultDataSource
     * 也能做这事，但它还要处理 asset/content 等我们用不到的分支，这里显式更清楚。
     */
    fun dataSource(client: OkHttpClient, userAgent: String): DataSource.Factory =
        object : DataSource.Factory {
            private val http = OkHttpDataSource.Factory(client).setUserAgent(userAgent)
            private val file = androidx.media3.datasource.FileDataSource.Factory()

            override fun createDataSource(): DataSource =
                object : DataSource {
                    private var active: DataSource? = null

                    private fun pick(uri: android.net.Uri): DataSource {
                        active?.let { return it }
                        val created = if (uri.scheme.equals("file", true)) file.createDataSource() else http.createDataSource()
                        active = created
                        return created
                    }

                    /** 同一个 Source 实例可能被复用于不同 scheme，open 时按 URI 重选。 */
                    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                        val current = active
                        val wantFile = dataSpec.uri.scheme.equals("file", true)
                        val haveFile = current is androidx.media3.datasource.FileDataSource
                        if (current == null || wantFile != haveFile) {
                            runCatching { current?.close() }
                            active = if (wantFile) file.createDataSource() else http.createDataSource()
                        }
                        return active!!.open(dataSpec)
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        active!!.read(buffer, offset, length)

                    override fun getUri(): android.net.Uri? = active?.uri

                    override fun getResponseHeaders(): Map<String, List<String>> =
                        active?.responseHeaders ?: emptyMap()

                    override fun close() {
                        runCatching { active?.close() }
                        active = null
                    }

                    override fun addTransferListener(
                        transferListener: androidx.media3.datasource.TransferListener,
                    ) {
                        // 两个工厂各自创建实例，这里把监听交给「当前」与「后续」两条；
                        // 用包一层的方式没法提前绑定，简单起见让上层按 DataSpec 自行统计。
                    }
                }
        }

    fun video(
        context: Context,
        client: OkHttpClient,
        userAgent: String,
        decoder: DecoderPreference,
        /** 用户口径（2026-11-23）：带上设置，播放走磁盘缓存（预加载的内容直接命中）。 */
        settings: xyz.etkn.mobile.data.settings.Settings? = null,
        /**
         * 用户口径（2026-11-27）：缓存数据源需要知道「当前播的是哪一集、总长多少」。
         * 用回调而不是值：换集时 VideoSession 更新这两个字段，Exo 不用重建。
         */
        cacheItemId: () -> String = { "" },
        cacheTotalBytes: () -> Long = { -1L },
        cacheDurationMs: () -> Long = { 0L },
        cachePositionMs: () -> Long = { 0L },
    ): ExoPlayer {
        DolbyVision.init(context)
        val renderers = NextRenderersFactory(context)
            .setExtensionRendererMode(
                when (decoder) {
                    DecoderPreference.Auto -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    DecoderPreference.Hardware -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    DecoderPreference.Software -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                },
            )
            .setEnableDecoderFallback(true)
        val selector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguages(*preferredLanguages())
                    .setPreferredTextLanguages(*preferredLanguages())
                    .build(),
            )
        }
        /**
         * 用户口径（2026-11-23）起播速度继续压 —— 目标是「点开就播」。
         * 参照对象是 Hills：播本地已缓存的片子，点开约 0.5 秒出画面。
         *
         * 分两档（本地缓存 vs 网络），本地可以直接更激进：
         *  · 本地文件（file://）：缓冲几乎无延迟，最小值/起播阈值都给到很低
         *  · 网络：保留一点缓冲余量，避免起播就卡
         *
         * 参数含义 setBufferDurationsMs(min, max, forPlayback, afterRebuffer)：
         *  · min         最小缓冲时长
         *  · max         最大缓冲时长
         *  · forPlayback 起播要攒够多少才出首帧  ← 这条直接决定「点开多久能看到画面」
         *  · afterRebuffer 卡顿后要攒够多少继续播
         */
        /**
         * 用户口径（2026-11-27）**缓冲上限交给缓存策略决定，不再由播放器砍到 60 秒**。
         *
         * 用户实证：「缓冲条感觉偏短」。
         *
         * 根因：这里 max = 60_000 —— 播放器最多只缓冲 60 秒就停止请求数据。
         * 用户设的「400MB / 1200 秒」根本轮不到生效，缓冲条永远只有 60 秒长
         * （一部 45 分钟的片子只占 2%），所以看着特别短。
         *
         * 现在：把播放器的 max 放到足够大（1 小时），让它不再是刹车；
         * 真正的刹车由 CachedDataSource + 缓存策略（字节/时长双阈值，谁先到谁停）负责。
         *
         * 保留 min / forPlayback 不动：起播速度仍然很快。
         * afterRebuffer 也不动：卡顿恢复行为不变。
         */
        /**
         * 用户口径（2026-11-27）**播放器缓冲上限必须 ≥ 缓存阈值，否则缓冲条长度不对**。
         *
         * 两个阈值谁先到谁停的逻辑在缓存层（CachedDataSource / LocalProxy），
         * 但播放器自己也有个「最多缓冲多久」的刹车（max）。
         * 如果 max 比用户阈值小 → 缓冲条被 max 砍短（上一版 60 秒就是这个毛病）。
         * 如果 max 比用户阈值大太多 → 播放器会一直请求数据，
         *   缓存层按阈值停了写盘，但 bufferedPosition 还会往前涨，
         *   缓冲条就又变长了（显示与缓存不一致）。
         *
         * 所以这里让 max 略大于「字节/时长两个阈值里起作用的那个」：
         *  · 时长阈值换算成时间（就是 cacheMemorySeconds 本身）
         *  · 字节阈值换算成时间需要码率，播放器创建时还拿不到
         *    → 取时长阈值 + 1 分钟余量，实际长度由缓存层的字节阈值再收一次
         * 这样缓冲条 = 真实写盘范围，不会再出现「显示 62% 但只缓存了 12%」。
         */
        val bufferedMaxMs = settings?.let {
            (it.cacheMemorySeconds.coerceIn(30, 7200).toLong() * 1000L + 60_000L)
                .coerceAtLeast(2 * 60_000L)
        } ?: (60 * 60_000L)
        /**
         * 用户口径（2026-11-27）**必须显式设 targetBufferBytes，否则字节上限会抢在时间上限之前把加载停掉**。
         *
         * 这是「缓存条一丢丢、达不到设置量」的最终根因（日志 + 官方源码双重确认）。
         *
         * 官方 DefaultLoadControl.shouldContinueLoading 的停止条件是「或」关系：
         * ```
         * bufferedDurationUs >= maxBufferUs   ||   targetBufferSizeReached
         * ```
         *  · 前者看时间（我们设成 1260 秒，够大）
         *  · 后者看字节，而 DEFAULT_TARGET_BUFFER_BYTES = C.LENGTH_UNSET(-1) 时
         *    由 calculateTargetBufferBytes() 按轨道估算，**通常只有几十 MB**
         *  · 任一满足就 stopLoading —— 所以 400MB 的设置永远轮不到生效
         *
         * 日志实证：aheadB 峰值只有 136MB 就不再增长，
         * 之后随着播放一路下降到 0（没有再往后补），正是这个字节天花板。
         *
         * 现在按用户设置的两个阈值直接给字节上限：
         *  targetBufferBytes = 缓存阈值（MB）转字节
         * 时间上限仍是 cacheMemorySeconds + 1 分钟余量
         * → 两者谁先到谁停，且都等于用户在设置里填的值。
         */
        /**
         * 用户口径（2026-11-27）**内存缓冲 ≠ 磁盘缓存，两者必须分开**。
         *
         * 这是前面十几版一直没找到的真正错因（用户质疑「Hills 也设 400MB 为啥不崩」才想明白）：
         *
         * `setTargetBufferBytes` 设的是**播放器内存里的缓冲区大小**，
         * 不是「磁盘上缓存多少」。
         *
         * 我之前把设置里的「最大缓存大小 400MB」直接填进去，等于
         * 让 ExoPlayer 在内存里攒 400MB 数据 —— 而 Java 堆上限只有 256MB，
         * 所以播放器一就绪就 OOM：
         * ```
         * 04:43:35.144  Dalvik used=234827   ← 0.14 秒内涨 37MB
         * 04:43:35.339  Throwing OutOfMemoryError
         * ```
         *
         * 正确分工（Hills 这类播放器就是这么做的）：
         *  · 内存缓冲：只留够播放的几秒（32MB），不随设置变化
         *  · 磁盘缓存：由 VideoCache(SimpleCache) 负责，400MB 存硬盘
         *  · 往后预缓存：后台 CacheWriter 写盘，完全不占内存堆
         *
         * 所以这里**固定 32MB**，不再跟着 cacheMemoryMb 走。
         */
        val MEMORY_BUFFER_BYTES = 32 * 1024 * 1024
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* min */ 8_000,
                /* max */ bufferedMaxMs.toInt(),
                /* forPlayback */ 200,
                /* afterRebuffer */ 1_500,
            )
            /**
             * 固定 32MB 内存缓冲：够播放器提前解码几秒，又不会把 256MB 的 Java 堆挤爆。
             * 「缓存 400MB」这件事交给磁盘层（VideoCache + CacheWriter），不在这里。
             */
            .setTargetBufferBytes(MEMORY_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        android.util.Log.i(
            "ETKN-Play",
            "loadControl min=8s max=${bufferedMaxMs / 1000}s memBuffer=${MEMORY_BUFFER_BYTES / 1048576}MB " +
                "(磁盘缓存阈值 ${settings?.cacheMemoryMb ?: -1}MB / ${settings?.cacheMemorySeconds ?: -1}s)",
        )
        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setMediaSourceFactory(videoSources(context, client, userAgent, settings, cacheItemId, cacheTotalBytes, cacheDurationMs, cachePositionMs))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            // Decoders, formats and failures land in the exportable log.
            .apply { addAnalyticsListener(PlaybackLog()) }
    }

    fun music(context: Context, dataSource: DataSource.Factory): ExoPlayer {
        val renderers = NextRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            /**
             * 用户口径（2026-11-27，#129）**音乐把整首缓冲进缓存，而不是只缓 60 秒**。
             *
             * 原来 `setBufferDurationsMs(10_000, 60_000, 800, 1_500)`。
             * 参数含义 = (最小缓冲, **最大缓冲**, 起播缓冲, 重缓冲后起播)。
             *
             * 实测证据（adb `dumpsys media_session`，同一首歌播了两遍）：
             * ```
             * buffered position  78071 → 131289 → 183459
             * 78071  = 78 秒
             * 131289 = 131 秒   ← 一次跨 53 秒，正好撞 60 秒上限
             * 183459 = 183 秒（全长）
             * ```
             * 也就是说：播到 78 秒时 Exo 停止往下读，等播放位置推进到约 71 秒
             * 才继续读下一段 —— 整首歌是「边播边下」，播完才整首落盘。
             *
             * 用户要的是「这首播完，整首就在本地；下一首也提前备好」，
             * 所以把最大缓冲提到 **10 分钟**（覆盖任何单曲，超长无损也不会卡住），
             * 让 Exo 一次性把整首歌读完写进 SimpleCache。
             *
             * 不设成 `C.TIME_UNSET`（无限）：那样播超长音频时会把内存堆吃满
             * （视频侧踩过 400MB 直接 OOM 的坑）。
             */
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(10_000, 600_000, 800, 1_500)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /**
     * `.iso` sources go through libbluray (full-disc Blu-ray / UHD Blu-ray); everything else through
     * the default extractors. Both play Dolby Vision profile 7 as 8.1 where the device can, else as
     * its HEVC HDR10 base layer.
     */
    /**
     * 用户口径（2026-11-27）**缓存插到数据源层（这是本次重构的核心）**。
     *
     * 原来（作者的注释也写了）：SimpleCache 的 key 带 token 永远命中不了，
     * 所以缓存改成「另起一条流写整文件」，播放器完全不知道有缓存。
     * 结果：拖到已缓存区间也不秒播、缓冲条还忽长忽短。
     *
     * 现在：不再用 SimpleCache（它的 key 问题确实存在），
     * 改成自建的 [CachedDataSource]（按块存，key = itemId，不带 token）。
     * 播放器要数据时先问本地块，没有再拉上游 —— 所以两个内核都能「下到哪播到哪」。
     *
     * [cacheItemId] / [cacheTotalBytes] 是「当前正在播哪一集、总长多少」的回调，
     * 因为 Exo 的 MediaItem 里不方便带这两个值（换集时由 VideoSession 更新）。
     */
    private fun videoSources(
        context: Context,
        client: OkHttpClient,
        userAgent: String,
        settings: xyz.etkn.mobile.data.settings.Settings?,
        cacheItemId: () -> String = { "" },
        cacheTotalBytes: () -> Long = { -1L },
        /** 当前集总时长（毫秒），用于把「时长阈值」换算成字节。 */
        cacheDurationMs: () -> Long = { 0L },
        /** 当前播放位置（毫秒），缓存阈值的起点。 */
        cachePositionMs: () -> Long = { 0L },
    ): BlurayMediaSourceFactory {
        /**
         * 用户口径（2026-11-27）**换成 ExoPlayer 官方缓存（SimpleCache + CacheDataSource）**。
         *
         * 之前自己写的 BlockStore + CachedDataSource + LocalProxy 已全部停用。
         * 失败原因（日志实证）：我的实现要求「攒满一整块 1MB 才落盘」，
         * 而播放器请求是跳跃的、经常没读满就断开（Connection reset / Broken pipe），
         * 结果一块都存不下来 —— 播了 20 多分钟本地只有 162MB，阈值 400MB 根本没到。
         *
         * 官方 SimpleCache 按 span 管理，读到多少写多少，不存在这个问题。
         * 另外它自带并发安全、洞补齐、LRU 淘汰，都是我们手写容易出错的部分。
         *
         * 磁盘上限用设置里的「最大硬盘缓存」（cacheDiskGb）。
         * 命中判定的 key 用 itemId（见 VideoCache 里的 CacheKeyFactory 说明）。
         */
        val cacheMaxBytes = (settings?.cacheDiskGb ?: 0).coerceIn(0, 200).toLong() * 1024L * 1024L * 1024L
        val plainHttp = httpFactory(client, userAgent)
        /**
         * 用户口径（2026-11-27）**本机代理地址必须透传，不能再套一层缓存**。
         *
         * mpv 分支会播 http://127.0.0.1:<port>/item/<id>，那一路已经由代理内部的
         * CacheDataSource 做过缓存了。如果 Exo 这边再包一层 CacheDataSource，
         * 就会「缓存里再存缓存」—— key 不同、空间翻倍、还可能互相干扰。
         *
         * 所以：CacheDataSource 只在目标是上游地址时启用；
         * 目标是本机回环时直接走原始 HttpDataSource 透传。
         */
        val cachedOrPlain: DataSource.Factory = if (cacheMaxBytes > 0) {
            /**
             * 把上游工厂登记给 VideoCache：磁盘预缓存（preloadAhead）要用它
             * 自己起一条写入流，不依赖播放器。
             */
            VideoCache.rememberUpstream(plainHttp)
            /**
             * 用户口径（2026-11-27）**播放器只读，写入只归 preloadAhead**。
             *
             * 原来这里用带写入的 `dataSource`，于是播放器和后台预缓存是**两个写入者**
             * 写同一个 cache key（mpv 那次直接 502）。SimpleCache 不支持两个写入者，
             * 现在统一：播放侧只读，写盘只走那一条后台流。
             *
             * 好处：命中缓存就本地给（拖回去秒开），没命中就走上游，
             * 但不再落盘 —— 落盘由预缓存按「播放位置往后 min(字节,时长)」负责。
             */
            VideoCache.readOnlyDataSource(
                context = context,
                upstream = plainHttp,
                maxBytes = cacheMaxBytes,
            )
        } else {
            plainHttp
        }
        val data: DataSource.Factory = object : DataSource.Factory {
            private val file = androidx.media3.datasource.FileDataSource.Factory()

            override fun createDataSource(): DataSource = object : DataSource {
                private var active: DataSource? = null
                private var isFile = false
                private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()

                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                    val isFileUri = dataSpec.uri.scheme.equals("file", true)
                    val isLoopback = dataSpec.uri.host?.let { it == "127.0.0.1" || it == "localhost" } == true
                    val cur = active
                    val wantKind = when {
                        isFileUri -> 1
                        isLoopback -> 2   // 透传，不套缓存
                        else -> 3          // 上游 → 带缓存
                    }
                    if (cur == null || kind != wantKind) {
                        runCatching { cur?.close() }
                        active = when (wantKind) {
                            1 -> file.createDataSource()
                            2 -> plainHttp.createDataSource()
                            else -> cachedOrPlain.createDataSource()
                        }
                        kind = wantKind
                        isFile = isFileUri
                        listeners.forEach { runCatching { active?.addTransferListener(it) } }
                    }
                    return active!!.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    active!!.read(buffer, offset, length)

                override fun getUri(): android.net.Uri? = active?.uri
                override fun getResponseHeaders(): Map<String, List<String>> =
                    active?.responseHeaders ?: emptyMap()
                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                    listeners.add(transferListener)
                    runCatching { active?.addTransferListener(transferListener) }
                }
                override fun close() {
                    runCatching { active?.close() }
                    active = null
                }

                private var kind = 0
            }
        }
        val ordinary = DefaultMediaSourceFactory(ResolvedRedirects(data), BlurayMediaSourceFactory.baseLayerExtractors())
        return BlurayMediaSourceFactory(ordinary, client, userAgent, data)
    }

    fun preferredLanguages(): Array<String> {
        val device = Locale.getDefault().language
        return linkedSetOf("zh", "chi", "zho", device).toTypedArray()
    }
}
