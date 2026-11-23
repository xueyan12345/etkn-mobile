package xyz.etkn.mobile.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.etkn.mobile.data.settings.Settings
import java.io.File

/**
 * 用户口径（2026-11-23）：缓存与预加载。
 *
 * 这是从零加的一套能力，设置页「我的 → 播放」里的六项全部落到这里：
 *  · 预加载开关        —— 控制是否把「下一集」提前down到磁盘
 *  · 最大缓存大小(MB)   —— 内存侧的前向缓冲上限（Media3 用时长 + 字节双上限）
 *  · 最大缓存时长(秒)   —— 同上，按时长
 *  · 最大磁盘缓存(GB)   —— SimpleCache 的 maxSizeBytes
 *  · 播完自动清空       —— 一集播完（或退出播放器）后清掉该集缓存
 *  · 清空磁盘缓存       —— 手动清，并给出当前占用
 *
 * 磁盘缓存用 Media3 的 SimpleCache（同一份文件可被 ExoPlayer 直接复用，
 * 预加载写进去的内容播放时直接命中，不会再走网络）。
 */
object PlaybackCache {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cache: SimpleCache? = null
    private var factory: CacheDataSource.Factory? = null
    private var preloadJob: Job? = null

    /** 预加载完成的集 id，用来给「下一集」图标显示闪电小提示。 */
    @Volatile private var warmedEpisodeId: String? = null

    fun isWarmed(episodeId: String?): Boolean = episodeId != null && episodeId == warmedEpisodeId

    fun clearWarmed() { warmedEpisodeId = null }

    @Synchronized
    fun cache(context: Context, settings: Settings): SimpleCache {
        cache?.let { return it }
        val dir = File(context.cacheDir, "media")
        dir.mkdirs()
        // 用 LRU 淘汰器并带上限：超过设置的 GB 数就自动清最久没用的。
        val limit = maxDiskBytes(settings)
        val built = SimpleCache(
            dir,
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(limit),
        )
        cache = built
        return built
    }

    /** 磁盘上限（GB → 字节）。设置里改了要重建缓存才会生效。 */
    fun maxDiskBytes(settings: Settings): Long =
        (settings.cacheDiskGb.coerceIn(1, 100).toLong() * 1024L * 1024L * 1024L)

    /** 当前磁盘缓存占用（字节）。 */
    fun diskUsage(): Long = runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)

    fun clearDisk() {
        runCatching { cache?.keys?.toList()?.forEach { cache?.removeResource(it) } }
        warmedEpisodeId = null
    }

    /**
     * 预加载一集：把它整段读进磁盘缓存。
     * 只在「预加载」开着、拿到直链、且缓存还没命中时做。
     */
    fun preload(context: Context, settings: Settings, episodeId: String, url: String) {
        if (!settings.preloadNext) return
        preloadJob?.cancel()
        preloadJob = scope.launch {
            runCatching {
                val simple = cache(context, settings)
                val dataSource = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent("ETKN-Mobile")
                    .createDataSource()
                val dataSpec = DataSpec(Uri.parse(url))
                // 已经全量缓存过就不再下
                val cached = simple.getCachedBytes(url, 0, -1)
                if (cached > 0) { warmedEpisodeId = episodeId; return@launch }
                CacheWriter(
                    CacheDataSource.Factory()
                        .setCache(simple)
                        .setUpstreamDataSourceFactory(androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent("ETKN-Mobile"))
                        .createDataSource(),
                    dataSpec,
                    null,
                    null,
                ).cache()
                warmedEpisodeId = episodeId
            }
        }
    }

    /** 播完一集后按设置清掉该集缓存，避免磁盘越堆越多。 */
    fun afterEpisodeFinished(context: Context, settings: Settings, url: String?) {
        if (!settings.cacheClearOnFinish) return
        if (url.isNullOrEmpty()) return
        scope.launch { runCatching { cache(context, settings).removeResource(url) } }
    }

    /** 供播放内核取用的可复用数据源（命中缓存就不走网络）。 */
    @Synchronized
    fun dataSourceFactory(context: Context, settings: Settings): CacheDataSource.Factory {
        factory?.let { return it }
        val built = CacheDataSource.Factory()
            .setCache(cache(context, settings))
            .setUpstreamDataSourceFactory(
                androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent("ETKN-Mobile"),
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        factory = built
        return built
    }
}
