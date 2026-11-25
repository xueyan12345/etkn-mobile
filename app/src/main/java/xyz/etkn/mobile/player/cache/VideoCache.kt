package xyz.etkn.mobile.player.cache

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 用户口径（2026-11-27）**缓存改用官方 CacheWriter（主动下载），不再依赖播放器拉多少**。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么前面十几版全错：用错了机制
 * ══════════════════════════════════════════════════════════════
 *
 * 官方有两套完全不同的东西：
 *
 *  · [CacheDataSource] —— **被动**。播放器读到哪就顺手存到哪。
 *    我前面一直在调 Exo 的 LoadControl（maxBufferMs / targetBufferBytes）
 *    想让它「多存一点」，但那些参数管的是「播放器自己拉多少」，
 *    播放器认为够了就停 —— 所以永远到不了用户设定的 400MB。
 *    日志实证：aheadB 峰值 141MB 就再也不涨。
 *
 *  · [CacheWriter] —— **主动**。它可以自己从任意位置往后一直下载，
 *    直到写完指定长度，完全不管播放器要什么。
 *    这才是「缓冲 400MB」该用的东西，HILLS 那类播放器就是这么实现的。
 *
 * ══════════════════════════════════════════════════════════════
 * 缓冲条怎么算（官方自己就是这么用的）
 * ══════════════════════════════════════════════════════════════
 *
 * CacheWriter.cache() 里第一行就是：
 * ```
 * bytesCached = cache.getCachedBytes(cacheKey, dataSpec.position, dataSpec.length)
 * ```
 * 所以缓冲条也用 [SimpleCache.getCachedBytes]：
 * 「从播放位置往后，本地真正存了多少字节」—— 这就是能秒开的范围。
 *
 * 之前用过的 getCachedLength / isCached / getCachedSpans 都不对：
 *  · isCached 只认完全连续的区域（正在写的永远 false）
 *  · getCachedSpans 不含正在写的数据
 *  · getCachedBytes 会把所有已落盘的段加起来，才是真实可读量
 *
 * ══════════════════════════════════════════════════════════════
 * 淘汰 & 保留
 * ══════════════════════════════════════════════════════════════
 * · 播过的全部保留（用户要能往回拖）
 * · 超过「最大磁盘缓存」由 LeastRecentlyUsedCacheEvictor 自动挤掉最旧的
 * · 手动清空：设置页「清空磁盘缓存」
 */
@UnstableApi
object VideoCache {

    private const val TAG = "ETKN-Cache"

    @Volatile private var cache: SimpleCache? = null
    @Volatile private var maxBytes: Long = 0L
    @Volatile private var upstreamFactory: DataSource.Factory? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前正在跑的缓存任务。 */
    @Volatile private var writerJob: Job? = null
    @Volatile private var writerItemId: String = ""
    @Volatile private var writerTargetBytes: Long = 0L
    @Volatile private var writerStartBytes: Long = 0L

    // ---------------------------------------------------------------- 生命周期

    fun get(context: android.content.Context, maxBytes: Long): SimpleCache? {
        if (maxBytes <= 0L) return null
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val dir = File(context.cacheDir, "videocache")
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
     * 给播放器用的数据源（Exo 播放时命中缓存读本地、未命中走上游）。
     * 同时把 upstreamFactory 记下来，供 [startCacheWindow] 的 CacheWriter 使用。
     */
    fun dataSource(
        context: android.content.Context,
        upstream: DataSource.Factory,
        maxBytes: Long,
    ): DataSource.Factory {
        upstreamFactory = upstream
        val store = get(context, maxBytes) ?: return upstream
        return CacheDataSource.Factory()
            .setCache(store)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheKeyFactory(keyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private val keyFactory = object : CacheKeyFactory {
        override fun buildCacheKey(dataSpec: DataSpec): String {
            val explicit = dataSpec.key
            if (!explicit.isNullOrBlank()) return explicit
            return dataSpec.uri.toString()
        }
    }

    // ---------------------------------------------------------------- 主动缓存窗口

    /**
     * 用户口径（2026-11-27）**从当前播放位置往后主动缓存到阈值**。
     *
     * 玩法（滑动窗口）：
     *  · 播放位置每前进一段，就重新算目标：位置 + min(字节阈值, 时长阈值换算字节)
     *  · 用一个 [CacheWriter] 从「已缓存到的位置」继续往后写
     *  · 一旦播放位置前移，窗口跟着前移（旧数据保留，可回拖）
     *  · 改设置（400→800MB）立刻按新阈值生效
     *
     * @param itemId   当前集
     * @param url      上游地址
     * @param positionMs 当前播放位置（毫秒）
     * @param durationMs 总时长（毫秒），未知传 0
     * @param totalBytes 总字节数（未知传 0）
     * @param limitBytes 字节阈值（来自设置「最大缓存大小」MB）
     * @param limitMs    时长阈值（来自设置「最大缓存时长」秒）
     */
    fun startCacheWindow(
        itemId: String,
        url: String,
        positionMs: Long,
        durationMs: Long,
        totalBytes: Long,
        limitBytes: Long,
        limitMs: Long,
    ) {
        val store = cache ?: return
        val upstream = upstreamFactory ?: return
        if (totalBytes <= 0 || limitBytes <= 0) return

        // 换算「应该缓存到哪个字节」
        val posBytes = if (durationMs > 0) {
            (totalBytes.toDouble() * positionMs.toDouble() / durationMs.toDouble()).toLong()
        } else 0L
        val bytesBySize = limitBytes
        val bytesByTime = if (durationMs > 0 && limitMs > 0) {
            (totalBytes.toDouble() * limitMs.toDouble() / durationMs.toDouble()).toLong()
        } else Long.MAX_VALUE
        // 两个阈值谁先到谁停
        val target = (posBytes + minOf(bytesBySize, bytesByTime)).coerceAtMost(totalBytes)

        // 已经缓存到哪了
        val alreadyCachedTo = runCatching {
            posBytes + store.getCachedBytes(itemId, posBytes, target - posBytes)
        }.getOrDefault(posBytes)

        /**
         * 已经够 → 什么都不做（这就是「达到阈值就暂时不疯狂下载」）。
         * 容差 2MB，避免每秒钟都重启任务。
         */
        if (alreadyCachedTo >= target - 2L * 1024 * 1024) return

        // 同一集且目标没变 → 正在跑的任务继续跑，别重复启动
        if (writerJob?.isActive == true && writerItemId == itemId && writerTargetBytes == target) return

        // 目标变了 → 换新任务（旧任务先取消）
        writerJob?.cancel()
        writerItemId = itemId
        writerTargetBytes = target
        writerStartBytes = alreadyCachedTo

        writerJob = scope.launch {
            val ds = CacheDataSource.Factory()
                .setCache(store)
                .setUpstreamDataSourceFactory(upstream)
                .setCacheKeyFactory(keyFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource() as CacheDataSource

            val spec = DataSpec.Builder()
                .setUri(android.net.Uri.parse(url))
                .setKey(itemId)                     // 与播放时同一个 key
                .setPosition(alreadyCachedTo)       // 从「还没缓存到的位置」开始
                .setLength(target - alreadyCachedTo)
                .build()

            var lastLogged = 0L
            val writer = CacheWriter(
                ds,
                spec,
                null,
                object : CacheWriter.ProgressListener {
                    override fun onProgress(requestLength: Long, bytesCached: Long, newBytesCached: Long) {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastLogged > 5_000L) {
                            lastLogged = now
                            Log.i(
                                TAG,
                                "cache-writer item=$itemId cached=${bytesCached / 1048576}MB " +
                                    "target=${(target - alreadyCachedTo) / 1048576}MB",
                            )
                        }
                    }
                },
            )
            try {
                writer.cache()
                Log.i(
                    TAG,
                    "cache-writer done item=$itemId upTo=${target / 1048576}MB",
                )
            } catch (e: java.io.InterruptedIOException) {
                Log.i(TAG, "cache-writer cancelled item=$itemId")
            } catch (t: Throwable) {
                Log.w(TAG, "cache-writer failed item=$itemId", t)
            } finally {
                runCatching { ds.close() }
            }
        }
    }

    /** 停止当前缓存任务（切集/退出播放器）。 */
    fun stopCacheWindow() {
        writerJob?.cancel()
        writerJob = null
        writerItemId = ""
        writerTargetBytes = 0L
        writerStartBytes = 0L
    }

    // ---------------------------------------------------------------- 查询

    fun usedBytes(): Long = cache?.cacheSpace ?: 0L

    /**
     * 用户口径（2026-11-27）**缓冲条数据源：官方 getCachedBytes**。
     *
     * 官方 CacheWriter 自己就用这个 API 统计「已经缓存了多少」，
     * 语义是「这段区间内，本地一共有多少字节可用」（含不连续的部分）。
     *
     * 注意：它的返回值是「可读总量」，不是「从起点连续可读」。
     * 但我们用 CacheWriter 顺序往后写，中间不会有洞，
     * 所以两者等价 —— 而且这正好是用户要的「往后能拖多远」。
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
            stopCacheWindow()
            runCatching { cache?.release() }
            cache = null
            runCatching { File(context.cacheDir, "videocache").deleteRecursively() }
            Log.i(TAG, "cache cleared")
        }
    }

    fun release() {
        synchronized(this) {
            stopCacheWindow()
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
