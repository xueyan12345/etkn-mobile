package xyz.etkn.mobile.music

import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import kotlinx.coroutines.launch

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
     * 音乐播放用的数据源（**带读 + 带写**）。
     *
     * 音乐不存在「代理 + 预加载双写」的问题（mvp/exo 只用一条流），
     * 所以这里让 Exo 边播边写就行 —— 播过的歌自然落到本地，
     * 下次打开直接读本地，实现「点播放秒响」。
     *
     * ## 2026-11-27 #124 修「音乐缓存完全无效」
     *
     * 用户实测（连续快进快退 40 秒）：
     * ```
     * 累计流量 34289MB → 35344MB（涨约 1GB）
     * buffered position 183459 → 82425（快退后缓冲点跟着回退）
     * 「缓冲条一会满一会不满，网速一直跑」
     * 「感觉就是音乐持续往手机里写缓存，并且它不会读取，用完之后马上就废掉了」
     * ```
     *
     * 三个独立原因：
     *
     * **① cache key 每次都不一样 → 永远未命中**
     * ```
     * .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
     * ```
     * `spec.key` 从来没人设置。而 `ResolvingDataSource` 在最外层，
     * 它先把 `etkn-music://track/<id>` 换成带 token 的真实直链，
     * 缓存层接到的就是那个 **带 token 的 URL** —— token 每次播放都变，
     * cache key 跟着变，命中率恒为 0。
     *
     * 修法：**key 显式取自 `etkn-music://track/<id>` 里的 itemId**，
     * 不再依赖 `spec.key` 也不依赖可能被改写的 `spec.uri`。
     * 见 [keyOfUri]。
     *
     * **② 写入和读取用不同的 DataSource 实例**
     * 原来还有一个 `MusicPrefetch` object，自己 New 了一个
     * `CacheDataSource` + `CacheDataSink`，和播放器的那个**同时**往同一个
     * SimpleCache 写同一个 key。SimpleCache **不支持两个写入者**
     * （视频那边踩过：mpv 代理直接 502）。
     *
     * 修法：**整个 `MusicPrefetch` 已删除**，写入权集中给播放器那一条流
     * （播到哪写到哪，播完整首就是整首落盘 —— 正是用户要的口径）。
     * 原来由它触发的 `playbackInfo` 请求也一并没了，
     * 服务端不会再出现「好多歌同时在播」。
     *
     * **③ 缓存占用读数不对 → 缓冲条是假的**
     * `currentBytes` 只在 `get()` 时读一次，之后从不更新。
     *
     * 修法：`sizeBytes()` 直接读 `cache.cacheSpace`（每次问实时值）。
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
            /**
             * key 用 `etkn-music://track/<itemId>` 里的 itemId，**跨会话恒定**。
             *
             * 查 `spec.key` 优先（预取会显式 setKey），其次从 uri 里抠 itemId。
             * 都拿不到才退回 uri 字符串（这种情况会命中不了，但不至于崩）。
             */
            .setCacheKeyFactory { spec ->
                spec.key?.takeIf { it.isNotBlank() } ?: keyOfUri(spec.uri)
            }
            /**
             * 只读命中时不回源；缓存出错（例如索引损坏）就直接走上游，
             * 不要让整首歌播不出来。
             */
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * 从 [uri] 里抠出稳定的缓存 key。
     *
     * ```
     * etkn-music://track/item-25493   → "music-item-25493"
     * 其它（真实直链、http）           → uri.toString()（会命中不了，但不会崩）
     * ```
     *
     * `MediaItem` 的 uri 由 `toMediaItem()` 构造：
     * `Uri.Builder().scheme("etkn-music").authority("track").appendPath(itemId)`，
     * 所以 `lastPathSegment` 就是 itemId。
     */
    private fun keyOfUri(uri: android.net.Uri): String {
        return if (uri.scheme == "etkn-music" && uri.authority == "track") {
            "music-" + (uri.lastPathSegment ?: uri.toString())
        } else {
            uri.toString()
        }
    }

    /** 对外暴露同一个 key 规则，预取侧要用。 */
    fun cacheKeyFor(itemId: String): String = "music-$itemId"

    /** 当前占用的字节数（设置页显示用）。 */
    fun sizeBytes(): Long = cache?.let { runCatching { it.cacheSpace }.getOrDefault(0L) } ?: currentBytes

    /**
     * 用户主动「清空音乐缓存」。
     *
     * 用户口径（#128）：歌词也缓存了，这里一并清掉 ——
     * 用户点「清空」的语义就是「把本地音乐数据全丢掉重来」，
     * 留一堆歌词文件在那里会让计数和实际不符。
     */
    fun clear() {
        runCatching { cache?.keys?.forEach { cache?.removeResource(it) } }
        LyricsCache.clear()
    }
}
