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
import xyz.etkn.mobile.player.cache.BlockStore
import xyz.etkn.mobile.player.cache.CachedDataSource
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
        val bufferedMaxMs = settings?.let {
            // 用户设的时长阈值 + 5 分钟余量；至少 5 分钟，避免设很小值时反而卡顿
            (it.cacheMemorySeconds.coerceIn(30, 7200).toLong() * 1000L + 5 * 60_000L)
                .coerceAtLeast(5 * 60_000L)
        } ?: (60 * 60_000L)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* min */ 8_000,
                /* max */ bufferedMaxMs,
                /* forPlayback */ 200,
                /* afterRebuffer */ 1_500,
            )
            // 按字节判断是否够播会让大码率片源起播更慢，改成只看时间
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        android.util.Log.i(
            "ETKN-Play",
            "loadControl min=8s max=${bufferedMaxMs / 1000}s " +
                "(缓存阈值 ${settings?.cacheMemoryMb ?: -1}MB / ${settings?.cacheMemorySeconds ?: -1}s)",
        )
        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setMediaSourceFactory(videoSources(context, client, userAgent, settings, cacheItemId, cacheTotalBytes, cacheDurationMs))
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
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 60_000, 800, 1_500).build())
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
    ): BlurayMediaSourceFactory {
        val plain: DataSource.Factory = dataSource(client, userAgent)
        /**
         * 只有「普通视频」走缓存数据源；file:// 仍然直读，
         * 由 CachedDataSource 内部判断（它只处理 http/https 上游）。
         */
        val data: DataSource.Factory = object : DataSource.Factory {
            private val cached = CachedDataSource.Factory(
                upstream = httpFactory(client, userAgent),
                store = BlockStore.get(context),
                itemId = cacheItemId,
                totalBytes = cacheTotalBytes,
                /**
                 * 用户口径（2026-11-27）**两个阈值谁先到谁停**。
                 *
                 * 这是「缓冲条长度对不对」的正解：
                 *  · 字节阈值（cacheMemoryMb）→ 直接是字节上限
                 *  · 时长阈值（cacheMemorySeconds）→ 按当前片子的码率换算成字节
                 *  · 取两者较小值
                 *
                 * 举例：片子 1000MB / 45 分钟，设 400MB + 1200 秒
                 *  · 400MB 对应 40%
                 *  · 1200 秒对应 1200/2700 ≈ 44%
                 *  · 取小 → 400MB → 缓冲条占 40%（与用户预期一致）
                 *
                 * totalBytes <= 0（还没探到）→ 不限，先让它正常缓冲。
                 */
                cacheLimitBytes = {
                    val total = cacheTotalBytes()
                    val cfg = settings
                    if (total <= 0 || cfg == null) {
                        -1L
                    } else {
                        val bySize = cfg.cacheMemoryMb.coerceIn(32, 8192).toLong() * 1024L * 1024L
                        val seconds = cfg.cacheMemorySeconds.coerceIn(30, 7200).toLong()
                        // 时长阈值换算成字节：需要 duration，由 cacheDurationMs 提供
                        val dur = cacheDurationMs()
                        val byTime = if (dur > 0) (total.toDouble() * seconds * 1000.0 / dur.toDouble()).toLong() else Long.MAX_VALUE
                        minOf(bySize, byTime).coerceAtMost(total)
                    }
                },
            )
            private val file = androidx.media3.datasource.FileDataSource.Factory()

            override fun createDataSource(): DataSource =
                object : DataSource {
                    private var active: DataSource? = null
                    private var isFile = false
                    /** 记住 Exo 传进来的监听器，每次新建数据源时立刻挂上（否则带宽统计丢失）。 */
                    private val listeners = mutableListOf<androidx.media3.datasource.TransferListener>()

                    override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long {
                        val wantFile = dataSpec.uri.scheme.equals("file", true)
                        val cur = active
                        if (cur == null || wantFile != isFile) {
                            runCatching { cur?.close() }
                            active = if (wantFile) file.createDataSource() else cached.createDataSource()
                            isFile = wantFile
                            listeners.forEach { runCatching { active?.addTransferListener(it) } }
                        }
                        return active!!.open(dataSpec)
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        active!!.read(buffer, offset, length)

                    override fun getUri(): android.net.Uri? = active?.uri
                    override fun getResponseHeaders(): Map<String, List<String>> =
                        active?.responseHeaders ?: emptyMap()
                    /**
                     * 用户口径（2026-11-27）：DataSource 接口要求实现这个。
                     * 转发给当前活跃的数据源，Exo 的带宽统计才能正常工作。
                     */
                    override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                        listeners.add(transferListener)
                        runCatching { active?.addTransferListener(transferListener) }
                    }
                    override fun close() {
                        runCatching { active?.close() }
                        active = null
                    }
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
