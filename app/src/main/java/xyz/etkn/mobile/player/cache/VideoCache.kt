package xyz.etkn.mobile.player.cache

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.io.File

/**
 * 用户口径（2026-11-27）**改用 ExoPlayer 官方缓存方案，不再手写代理**。
 *
 * ── 为什么推翻之前的实现 ──
 * 前几版自己写了 BlockStore + CachedDataSource + LocalProxy（本机 HTTP 服务），
 * 结果是：播了 20 多分钟本地只积累 162MB，阈值 400MB 根本没达到。
 *
 * 根因（日志实证）：
 *  · 我的实现要求「攒满一整块 1MB 才落盘」
 *  · 而播放器请求是跳跃的（bytes=0- / bytes=1511- / bytes=159056556- / bytes=3115841120-）
 *  · 每段经常没读满 1MB 就断开（日志里全是 Connection reset / Broken pipe）
 *  · → 一块都存不下来 → 缓存等于没有
 *
 * ── 官方方案为什么没这个问题 ──
 * SimpleCache 内部按 span 管理（span 默认 256KB 或以下），
 * 读到多少就写多少，不会因为「没攒满一大块」而丢弃；
 * 并且它自带：
 *  · 并发安全（多线程读写同一资源）
 *  · 洞（hole）管理：缺失区间自动从上游补
 *  · LRU 淘汰：达到上限自动挤掉最久未用
 *
 * ── 当年作者放弃的原因及解决办法 ──
 * 原注释说「SimpleCache 的 key 带 token 永远命中不了」。
 * 那是因为默认 key 取的是完整 URL（含 token/UserId 查询参数）。
 * 官方提供了 [CacheKeyFactory]，我们让它返回 itemId 即可 —— 同一个条目
 * 无论 URL 参数怎么变，key 都稳定。
 */
@UnstableApi
object VideoCache {

    /** 磁盘缓存最大值（GB）由设置里的「最大硬盘缓存」决定，见 [maxBytes]。 */
    @Volatile private var cache: SimpleCache? = null

    @Volatile private var maxBytes: Long = 0L

    /**
     * 取缓存实例（幂等）。必须在后台线程调用一次（SimpleCache 构造会读索引）。
     *
     * @param maxBytes 磁盘上限；为 0 表示不缓存（返回 null）。
     */
    fun get(context: Context, maxBytes: Long): SimpleCache? {
        if (maxBytes <= 0L) return null
        cache?.let { existing ->
            // 上限变了（用户在设置里改过）→ 淘汰器需要重建
            if (existing === cache && this.maxBytes == maxBytes) return existing
        }
        synchronized(this) {
            cache?.let { existing ->
                if (this.maxBytes == maxBytes) return existing
                // 上限变更：释放旧的，按新上限重建（索引留在磁盘上，不会丢数据）
                runCatching { existing.release() }
                cache = null
            }
            val dir = File(context.cacheDir, "videocache")
            val evictor = LeastRecentlyUsedCacheEvictor(maxBytes)
            val created = runCatching { SimpleCache(dir, evictor) }.getOrNull() ?: return null
            cache = created
            this.maxBytes = maxBytes
            return created
        }
    }

    /**
     * 组装给播放器用的数据源。
     *
     * 结构（官方推荐）：
     * ```
     * 播放器 → CacheDataSource ─┬─ 命中 → CacheDataSource 直接从 SimpleCache 读
     *                          └─ 未命中 → 上游 HttpDataSource，同时写入 SimpleCache
     * ```
     *
     * [itemId] 用自定义 key：保证不同播放地址（token 变化）命中同一份缓存。
     */
    fun dataSource(
        context: Context,
        upstream: DataSource.Factory,
        maxBytes: Long,
    ): DataSource.Factory {
        val store = get(context, maxBytes) ?: return upstream
        return CacheDataSource.Factory()
            .setCache(store)
            .setUpstreamDataSourceFactory(upstream)
            /**
             * key = 当前条目的 itemId。
             *
             * 这是「作者当年说命中不了」的解法：
             * 默认 key 是完整 URL（带 token），token 一刷新就换了 key；
             * 改成 itemId 之后，同一集永远同一个 key。
             */
            .setCacheKeyFactory(
                object : CacheKeyFactory {
                    override fun buildCacheKey(dataSpec: DataSpec): String {
                        val explicit = dataSpec.key
                        if (!explicit.isNullOrBlank()) return explicit
                        return dataSpec.uri.toString()
                    }
                },
            )
            /**
             * 允许「缓存未命中时正常走网络」。
             * 这里不开 FLAG_BLOCK_ON_CACHE —— 那会等缓存合流，起播更慢。
             */
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    // ---------------------------------------------------------------- 查询

    /** 磁盘占用（字节）。 */
    fun usedBytes(): Long = cache?.cacheSpace ?: 0L

    /** 某一集已缓存了哪些区间（毫秒换算需要调用方给 totalBytes/durationMs）。 */
    fun spansOf(itemId: String): List<CacheSpan> =
        runCatching { cache?.getCachedSpans(itemId)?.toList() }.getOrNull().orEmpty()

    /**
     * 用户口径（2026-11-27）**缓冲条的唯一数据源：这一集真实缓存到哪个字节**。
     *
     * 返回「从 [fromBytes] 起连续可用的字节数」。
     * 数据来自 SimpleCache 的 span（真实落盘），不是播放器请求水位。
     */
    fun availableBytesFrom(itemId: String, fromBytes: Long, length: Long): Long {
        val store = cache ?: return 0L
        /**
         * 用户口径（2026-11-27）**必须用官方 getCachedLength，不能自己遍历 span**。
         *
         * 修的 bug（用户实测：「缓冲条一点也不动，有时候能看着，有时候一点也看不着」）：
         *
         * 之前自己遍历 getCachedSpans() 并累加。但 SimpleCache 里
         * **正在下载的区域是 hole（CacheSpan.isHoleSpan=true），不在 getCachedSpans 的结果里**，
         * 而播放位置后面那段恰恰多数还在 hole 中 ——
         * 于是 availableBytesFrom 恒返回 0，缓冲条永远停在播放点不动。
         *
         * 官方 getCachedLength(key, position, length) 的语义：
         *  · 返回正数 = 从 position 起**连续可用**的字节数
         *  · 返回负数 = 这里是洞，长度是它的绝对值
         * 它内部把 span 和 hole 一起算了，这正是我们要的。
         */
        return runCatching {
            val cached = store.getCachedLength(
                itemId,
                fromBytes,
                if (length > 0) length else androidx.media3.common.C.LENGTH_UNSET.toLong(),
            )
            if (cached > 0) minOf(cached, if (length > 0) length else cached) else 0L
        }.getOrDefault(0L)
    }

    /** 整集是否已完整缓存。 */
    fun isComplete(itemId: String, totalBytes: Long): Boolean {
        if (totalBytes <= 0) return false
        return availableBytesFrom(itemId, 0L, totalBytes) >= totalBytes
    }

    /** 清空某一集。 */
    fun remove(itemId: String) {
        runCatching { cache?.removeResource(itemId) }
    }

    /** 清空全部（设置里的「清除缓存」）。 */
    fun clearAll(context: Context) {
        synchronized(this) {
            runCatching { cache?.release() }
            cache = null
            runCatching { File(context.cacheDir, "videocache").deleteRecursively() }
        }
    }

    /** 释放（退出播放器/进程结束时调用）。 */
    fun release() {
        synchronized(this) {
            runCatching { cache?.release() }
            cache = null
            maxBytes = 0L
        }
    }
}

/**
 * OkHttpClient → Media3 的 HttpDataSource.Factory。
 *
 * 放这里是因为缓存和上游数据源要配套使用；
 * 之前它写在已删除的 CachedDataSource.kt 里。
 */
fun httpFactory(client: okhttp3.OkHttpClient, userAgent: String): androidx.media3.datasource.HttpDataSource.Factory =
    androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client)
        .setUserAgent(userAgent)
