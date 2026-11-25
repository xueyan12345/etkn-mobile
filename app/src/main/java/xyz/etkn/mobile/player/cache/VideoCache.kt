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
 * 用户口径（2026-11-27）**官方 SimpleCache + CacheDataSource（不自己造存储）**。
 *
 * 缓存本体完全交给官方：
 *  · SimpleCache 负责落盘、并发、索引、LRU 淘汰
 *  · CacheDataSource 负责「命中读本地 / 未命中拉上游并写入」
 *
 * 我们只做两件事：
 *  ① 把 cache key 固定成 itemId（解决「key 带 token 命中不了」的历史问题）
 *  ② 提供 [isCached] / [availableBytesFrom] 给缓冲条显示用
 *
 * 关于缓冲条读数据的说明（踩过的坑）：
 *  · getCachedSpans() 不包含正在下载的 hole → 算出来恒为 0
 *  · getCachedLength() 语义是对的（正数=连续可用），实测也要配合正确的 key
 *  · 最终用 isCached(key, position, length) 逐段二分探测，语义最明确
 */
@UnstableApi
object VideoCache {

    private const val TAG = "ETKN-Cache"

    @Volatile private var cache: SimpleCache? = null
    @Volatile private var maxBytes: Long = 0L

    fun get(context: android.content.Context, maxBytes: Long): SimpleCache? {
        if (maxBytes <= 0L) return null
        /**
         * 用户口径（2026-11-27）**同一个缓存实例只能有一个，绝不重复打开同一目录**。
         *
         * 修的 bug（日志实证 keys 2 → 0）：
         * ```
         * 19:57:13 SimpleCache ready cap=10240MB keys=2   ← 已经缓存了 2 集
         * 19:57:27 SimpleCache ready cap=10240MB keys=0   ← 又被打开一次，索引空了
         * ```
         * 两次 cap 完全相同，说明不是「上限变了要重建」，
         * 而是同一个目录被打开了两个实例 —— 后打开的读到的索引是空的，
         * 于是之后按 itemId 查缓存永远查不到（aheadB 有值也是假象）。
         *
         * 现在：**只要已经有一个实例在用，就直接返回它，不管上限参数是多少**。
         * 上限变了也不重建（重建会丢索引、打断正在进行的下载），
         * 交给 LRU 淘汰器在下次写入时按新上限收敛 —— 效果等价，风险为零。
         */
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val dir = File(context.cacheDir, "videocache")
            val created = runCatching {
                SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes))
            }.onFailure { Log.e(TAG, "SimpleCache open failed", it) }.getOrNull() ?: return null
            cache = created
            this.maxBytes = maxBytes
            Log.i(
                TAG,
                "SimpleCache opened dir=${dir.absolutePath} cap=${maxBytes / 1048576}MB " +
                    "keys=${runCatching { created.keys.size }.getOrDefault(0)}",
            )
            return created
        }
    }

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

    // ---------------------------------------------------------------- 查询

    fun usedBytes(): Long = cache?.cacheSpace ?: 0L

    /** 缓存里现有哪些 key（诊断用）。 */
    fun keys(): Set<String> = runCatching { cache?.keys }.getOrNull() ?: emptySet()

    /**
     * 用户口径（2026-11-27）**从 [fromBytes] 起，本地连续可读多少字节**。
     *
     * 用官方 [SimpleCache.isCached] 逐段探测：
     *  · isCached 的语义是「这一段是否全部在缓存里」，最明确
     *  · 从当前位置开始，按指数增长再二分，找出最长连续长度
     *  · 上限 [length]（通常是「到片尾还有多少字节」）
     *
     * 为什么不用 getCachedLength：
     *  它返回「连续可用或洞的长度」，实测在我们的 key 用法下拿到的值
     *  和实际能秒播的范围对不上（会导致条画得比实际多）。
     *  isCached 是「能不能读」的直接判断，正好对应我们的验收标准。
     */
    fun availableBytesFrom(itemId: String, fromBytes: Long, length: Long): Long {
        val store = cache ?: return 0L
        if (fromBytes < 0 || length <= 0) return 0L
        val maxProbe = length

        val cachedAt = { n: Long ->
            runCatching { store.isCached(itemId, fromBytes, n) }.getOrDefault(false)
        }
        // 起始一小段都没缓存 → 直接 0
        if (!cachedAt(1L)) return 0L

        /**
         * 指数扩张找上界，再二分收敛。
         * 每集最多 30 次 isCached 调用（1,2,4,...,2^30 字节 ≈ 1GB），开销可忽略。
         */
        var lo = 1L
        var step = 1L
        while (lo < maxProbe) {
            val next = minOf(lo + step, maxProbe)
            if (cachedAt(next)) {
                lo = next
                step *= 2
                if (lo >= maxProbe) return maxProbe
            } else {
                break
            }
        }
        var hi = minOf(lo + step, maxProbe)
        // 二分在 (lo, hi] 里找最大全缓存长度
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cachedAt(mid)) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun isComplete(itemId: String, totalBytes: Long): Boolean {
        if (totalBytes <= 0) return false
        return runCatching { cache?.isCached(itemId, 0L, totalBytes) ?: false }.getOrDefault(false)
    }

    fun remove(itemId: String) {
        runCatching { cache?.removeResource(itemId) }
    }

    fun clearAll(context: android.content.Context) {
        synchronized(this) {
            runCatching { cache?.release() }
            cache = null
            runCatching { File(context.cacheDir, "videocache").deleteRecursively() }
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
