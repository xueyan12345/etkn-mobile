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

    fun dataSource(client: OkHttpClient, userAgent: String): DataSource.Factory =
        OkHttpDataSource.Factory(client).setUserAgent(userAgent)

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
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 90_000, 800, 2_500)
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
