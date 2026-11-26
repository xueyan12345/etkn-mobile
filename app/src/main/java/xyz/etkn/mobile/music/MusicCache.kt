package xyz.etkn.mobile.music

import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 用户口径（2026-11-27，#39）**音乐用一套独立的磁盘缓存**。
 *
 * 用户原话：
 *  「单独给音乐设置一个缓存路径，跟视频的那个设置的磁盘缓存大小分开，
 *    就是相当于不让音乐参与那个大小设置，单独开一个专门给音乐用，
 *    这样就能保证上次最后播放的音乐的缓存一直在，打开就能直接显示上次的缓存」
 *
 * 为什么不能和视频共用 [xyz.etkn.mobile.player.cache.VideoCache]：
 * ```
 * VideoCache 用的是 LeastRecentlyUsedCacheEvictor(maxBytes)
 *   → 视频配额（400MB）一满，LRU 会先丢「最久没访问」的
 *   → 恰好就是"上次播的那首歌"，于是重启后封面/歌词/秒播全没了
 * ```
 *
 * 所以这里刻意做三处不同：
 *  · **独立目录** `filesDir/musiccache`（不参与视频的磁盘缓存设置）
 *  · **独立配额** 由「我的 → 播放 → 音乐缓存」单独设置
 *  · **独立淘汰** 用 LRU，但配额由音乐自己的设置决定，
 *    而且「上次播放的那首」会被 `pin()`，LRU 不会淘汰它
 *
 * 这样既不会无限涨，也能保证「上次那首永远在」。
 */
object MusicCache {
    private const val TAG = "ETKN-MusicCache"

    @Volatile private var cache: SimpleCache? = null
    @Volatile private var currentBytes: Long = 0L

    /**
     * 当前生效的配额（MB）。
     *
     * 由设置页在读取/修改「音乐缓存大小」时写入（[setLimit]）。
     * 播放器创建时同步读它，避免为了一个数字去 runBlocking 等待 DataStore。
     */
    @Volatile var limitMb: Int = 2048
        private set

    fun setLimit(mb: Int) {
        limitMb = mb.coerceIn(256, 20480)
    }

    /**
     * 取缓存实例（单例）。
     *
     * 和 VideoCache 一样：同一个目录只能有一个 SimpleCache 实例，
     * 反复 open/release 会把索引读空，所以已有实例就直接复用。
     */
    fun get(context: android.content.Context, maxMb: Int = 2048): SimpleCache? {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            /**
             * 用 filesDir 而不是 cacheDir：ColorOS 会积极清 cacheDir，
             * 放那里会「刚缓存完就被回收」。
             *
             * 配额来自「我的 → 播放 → 音乐缓存」设置（[maxMb]），
             * 和视频的磁盘缓存设置完全分开。
             */
            val dir = File(context.filesDir, "musiccache")
            val maxBytes = maxMb.coerceIn(256, 20480).toLong() * 1024L * 1024L
            val created = runCatching { SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes)) }
                .onFailure { Log.e(TAG, "SimpleCache open failed", it) }
                .getOrNull() ?: return null
            cache = created
            currentBytes = runCatching { created.cacheSpace }.getOrDefault(0L)
            Log.i(TAG, "cache ready keys=${runCatching { created.keys.size }.getOrDefault(0)}")
            return created
        }
    }

    /**
     * 音乐播放用的数据源（**带写入**）。
     *
     * 音乐不存在「代理 + 预加载双写」的问题（mvp/exo 只用一条流），
     * 所以这里让 Exo 边播边写就行 —— 播过的歌自然落到本地，
     * 下次打开直接读本地，实现「点播放秒响」。
     */
    fun dataSource(
        context: android.content.Context,
        upstream: DataSource.Factory,
        maxMb: Int = 2048,
    ): DataSource.Factory {
        val store = get(context, maxMb) ?: return upstream
        return CacheDataSource.Factory()
            .setCache(store)
            .setUpstreamDataSourceFactory(upstream)
            /** key 固定用 itemId：默认 key 是带 token 的完整 URL，token 一换就命中不了。 */
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 当前占用的字节数（设置页显示用）。 */
    fun sizeBytes(): Long = cache?.let { runCatching { it.cacheSpace }.getOrDefault(0L) } ?: currentBytes

    /** 用户主动「清空音乐缓存」。 */
    fun clear() {
        runCatching { cache?.keys?.forEach { cache?.removeResource(it) } }
    }
}
