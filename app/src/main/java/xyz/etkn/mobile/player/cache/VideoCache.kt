package xyz.etkn.mobile.player.cache

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 用户口径（2026-11-27）**缓存只用 Exo 官方机制，不再自己写下载循环**。
 *
 * ══════════════════════════════════════════════════════════════
 * 这一段是踩了很多坑之后定下来的架构，不要再加"主动下载循环"
 * ══════════════════════════════════════════════════════════════
 *
 * 之前我试过自己在后台跑 CacheWriter 循环主动下载 400MB，结果是：
 *  · 和播放器的 CacheDataSource 抢同一段缓存（SimpleCache 不允许双写）
 *  · 每 64MB 重建一次数据源，对象堆积，Java 堆 256MB 撑满 → OOM 闪退
 *  · 播放位置每秒变化，我按位置重算目标 → 循环高频空转
 *
 * 正确的做法是**只配好两个参数，让 Exo 自己干**：
 *
 *  ① `DefaultLoadControl.setTargetBufferBytes(用户设的字节阈值)`
 *     —— 这是关键。默认值是 C.LENGTH_UNSET，Exo 按轨道估算只有几十 MB，
 *        所以以前永远缓存不到 400MB（日志实证 aheadB 峰值 141MB 就不涨了）。
 *        显式设成 400MB 后，Exo 自己就会往下缓冲到 400MB。
 *
 *  ② `DefaultLoadControl.setBufferDurationsMs(max = 时长阈值 + 余量)`
 *     —— 时间上限，和字节上限谁先到谁停。
 *
 * 日志实证（1.8.4 配好这两个参数后）：
 * ```
 * 22:39:50  aheadB=0MB    disk=0MB
 * 22:39:52  aheadB=61MB   disk=64MB
 * 22:39:55  aheadB=119MB  disk=128MB
 * 22:39:57  aheadB=179MB  disk=192MB   ← Exo 自己在稳定缓存
 * ```
 *
 * ══════════════════════════════════════════════════════════════
 * 缓冲条怎么算
 * ══════════════════════════════════════════════════════════════
 * 用 [SimpleCache.getCachedBytes]（Exo 自己的 CacheWriter 统计进度也用这个）：
 * 「从播放位置往后，本地真实存了多少字节」= 能秒开的范围。
 *
 * ══════════════════════════════════════════════════════════════
 * 淘汰
 * ══════════════════════════════════════════════════════════════
 * · 播过的全部保留（用户要能往回拖）
 * · 超过「最大磁盘缓存」由 LeastRecentlyUsedCacheEvictor 自动挤掉最旧的
 */
@UnstableApi
object VideoCache {

    private const val TAG = "ETKN-Cache"

    @Volatile private var cache: SimpleCache? = null
    @Volatile private var maxBytes: Long = 0L

    /**
     * 取缓存实例（单例）。
     *
     * 注意：**同一个目录只能打开一个 SimpleCache**。之前我在多处调 get()，
     * 每次上限参数略有差异就 release 重建，导致索引被读空（日志 keys 2→0）。
     * 现在只要已有实例就直接复用，上限变化交给 LRU 淘汰器在下次写入时收敛。
     */
    fun get(context: android.content.Context, maxBytes: Long): SimpleCache? {
        if (maxBytes <= 0L) return null
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            /**
             * 缓存目录用 filesDir，不用 cacheDir。
             * Android 允许系统随时清空 cacheDir（OPPO ColorOS 尤其积极），
             * 放那里会出现「刚写完就被回收、disk 一直是 0」。
             */
            val dir = File(context.filesDir, "videocache")
            val created = runCatching {
                SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes))
            }.onFailure { Log.e(TAG, "SimpleCache open failed", it) }.getOrNull() ?: return null
            cache = created
            this.maxBytes = maxBytes
            Log.i(TAG, "cache ready cap=${maxBytes / 1048576}MB keys=${runCatching { created.keys.size }.getOrDefault(0)}")
            return created
        }
    }

    /**
     * 给播放器用的数据源。
     *
     * 这里**带写入**（不设 null）：既然不再有第二个写入者，就让 Exo 自己边播边存，
     * 这是官方的标准用法，也是唯一不会冲突的用法。
     *
     * key 固定用 itemId：解决「默认 key 是带 token 的完整 URL，token 一换就命中不了」。
     */
    fun dataSource(
        context: android.content.Context,
        upstream: DataSource.Factory,
        maxBytes: Long,
    ): DataSource.Factory {
        val store = get(context, maxBytes) ?: return upstream
        return CacheDataSource.Factory()
            .setCache(store)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(
                object : CacheKeyFactory {
                    override fun buildCacheKey(dataSpec: DataSpec): String {
                        val explicit = dataSpec.key
                        if (!explicit.isNullOrBlank()) return explicit
                        return dataSpec.uri.toString()
                    }
                },
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 供 mpv 代理用的同一个缓存实例 + 同样的 key 规则。 */
    fun cacheForProxy(context: android.content.Context, maxBytes: Long): SimpleCache? =
        get(context, maxBytes)

    // ---------------------------------------------------------------- 查询

    fun usedBytes(): Long = cache?.cacheSpace ?: 0L

    /**
     * 用户口径（2026-11-27）**缓冲条数据源：官方 getCachedBytes**。
     *
     * 语义 = 区间 [fromBytes, fromBytes+length) 内本地真实可读的字节数。
     * 这是 Exo 自己 CacheWriter 统计进度用的 API，最贴近「能不能秒开」。
     *
     * 之前试过但不对的：
     *  · isCached：只认完全连续的区域（正在写的永远 false → 恒 0）
     *  · getCachedSpans：不含正在写的数据
     *  · getCachedLength：拿到的范围和实际可播范围对不上
     */
    fun cachedBytesFrom(itemId: String, fromBytes: Long, length: Long): Long {
        val store = cache ?: return 0L
        if (length <= 0) return 0L
        return runCatching {
            store.getCachedBytes(itemId, fromBytes, length).coerceIn(0L, length)
        }.getOrDefault(0L)
    }

    fun isComplete(itemId: String, totalBytes: Long): Boolean {
        if (totalBytes <= 0) return false
        return cachedBytesFrom(itemId, 0L, totalBytes) >= totalBytes - 1024 * 1024
    }

    fun remove(itemId: String) {
        runCatching { cache?.removeResource(itemId) }
    }

    fun clearAll(context: android.content.Context) {
        synchronized(this) {
            runCatching { cache?.release() }
            cache = null
            runCatching { File(context.filesDir, "videocache").deleteRecursively() }
            Log.i(TAG, "cache cleared")
        }
    }

    fun release() {
        synchronized(this) {
            runCatching { cache?.release() }
            cache = null
            maxBytes = 0L
        }
    }
}

/** OkHttpClient → Media3 的 HttpDataSource.Factory。 */
fun httpFactory(client: okhttp3.OkHttpClient, userAgent: String): androidx.media3.datasource.HttpDataSource.Factory =
    androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client)
        .setUserAgent(userAgent)
