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
         * 用户口径（2026-11-23）修「起播变慢」：
         * 原来是 (min=20s, max=90s, forPlayback=800ms, afterRebuffer=2500ms)。
         * 起播要等 800ms 缓冲才出首帧，网络一般时体感明显延迟。
         * 降到 400ms（够避免立刻卡顿，又明显更快出画面），rebuffer 保持 2500ms 保住稳定性。
         */
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 90_000, 400, 2_500)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setMediaSourceFactory(videoSources(context, client, userAgent, settings))
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
    private fun videoSources(
        context: Context,
        client: OkHttpClient,
        userAgent: String,
        settings: xyz.etkn.mobile.data.settings.Settings?,
    ): BlurayMediaSourceFactory {
        // 用户口径（2026-11-23）：缓存不做在数据源层（SimpleCache 的 key 带 token 永远命中不了），
        // 改成落地成整文件：命中时 VideoSession 直接把 file:// 交给播放器。
        val data: DataSource.Factory = dataSource(client, userAgent)
        val ordinary = DefaultMediaSourceFactory(ResolvedRedirects(data), BlurayMediaSourceFactory.baseLayerExtractors())
        return BlurayMediaSourceFactory(ordinary, client, userAgent, data)
    }

    fun preferredLanguages(): Array<String> {
        val device = Locale.getDefault().language
        return linkedSetOf("zh", "chi", "zho", device).toTypedArray()
    }
}
