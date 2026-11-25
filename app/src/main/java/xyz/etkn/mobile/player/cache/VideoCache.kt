package xyz.etkn.mobile.player.cache

import android.util.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
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

    /**
     * cache key 规则：优先用 DataSpec 里显式指定的 key（= itemId），
     * 拿不到才退回完整 URL。这是「token 一换就命中不了」的解法。
     */
    private val keyFactory = object : CacheKeyFactory {
        override fun buildCacheKey(dataSpec: DataSpec): String {
            val explicit = dataSpec.key
            if (!explicit.isNullOrBlank()) return explicit
            return dataSpec.uri.toString()
        }
    }

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
            .setCacheKeyFactory(keyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** 供 mpv 代理用的同一个缓存实例 + 同样的 key 规则。 */
    fun cacheForProxy(context: android.content.Context, maxBytes: Long): SimpleCache? =
        get(context, maxBytes)

    // ---------------------------------------------------------------- 磁盘预缓存

    @Volatile private var preloadJob: kotlinx.coroutines.Job? = null
    @Volatile private var preloadItemId: String = ""
    @Volatile private var preloadTargetBytes: Long = 0L
    @Volatile private var preloadUrl: String = ""
    @Volatile private var preloadUpstream: DataSource.Factory? = null
    @Volatile private var preloadPosBytes: Long = 0L
    @Volatile private var preloadSpec: androidx.media3.datasource.DataSpec? = null

    /** 记录上游工厂，供预缓存用。 */
    fun rememberUpstream(factory: DataSource.Factory) {
        preloadUpstream = factory
    }

    /**
     * 用户口径（2026-11-27）**磁盘预缓存：主动把「播放位置往后」写到设定量，全程只占固定内存**。
     *
     * 为什么必须单独做这件事：
     * ExoPlayer 的内存缓冲已固定 32MB（够播几秒），不会把 400MB 塞进内存堆。
     * 但用户要的是「磁盘上往后有 400MB 可秒开」——这必须靠主动写盘。
     *
     * 内存安全的关键（对比我之前那版 OOM 的实现）：
     *  · `CacheWriter` 内部只用 128KB 的临时缓冲，写完就丢
     *  · 每轮只下一个分片（8MB），下完立刻释放数据源
     *  · 目标值放 volatile 字段，**tick 只改字段，绝不重启任务**
     *  · 从窗口起点量已缓存量，不从 0 量（从 0 量遇到洞会恒为 0 → 死循环 → OOM）
     *
     * @param itemId 当前集
     * @param url 上游地址
     * @param positionMs 当前播放位置
     * @param durationMs 总时长（0 表示未知）
     * @param totalBytes 总字节数
     * @param limitBytes 字节阈值（设置里的「最大缓存大小」）
     * @param limitMs 时长阈值（设置里的「最大缓存时长」）
     */
    fun preloadAhead(
        itemId: String,
        url: String,
        positionMs: Long,
        durationMs: Long,
        totalBytes: Long,
        limitBytes: Long,
        limitMs: Long,
    ) {
        val store = cache ?: return
        if (totalBytes <= 0 || limitBytes <= 0 || url.isBlank()) return
        val upstream = preloadUpstream ?: return

        // 播放位置对应的字节
        val posBytes = if (durationMs > 0) {
            (totalBytes.toDouble() * positionMs.toDouble() / durationMs.toDouble()).toLong()
        } else 0L
        val bytesByTime = if (durationMs > 0 && limitMs > 0) {
            (totalBytes.toDouble() * limitMs.toDouble() / durationMs.toDouble()).toLong()
        } else Long.MAX_VALUE
        val target = (posBytes + minOf(limitBytes, bytesByTime)).coerceAtMost(totalBytes)

        // 参数更新（不重启任务）
        preloadUrl = url
        preloadTargetBytes = target
        preloadPosBytes = posBytes

        if (preloadItemId != itemId) {
            // 换集才重启
            preloadJob?.cancel()
            preloadItemId = itemId
            preloadJob = scope.launch { preloadLoop(itemId, store, upstream) }
            Log.i(TAG, "preload start item=$itemId target=${target / 1048576}MB")
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    private suspend fun preloadLoop(
        itemId: String,
        store: SimpleCache,
        upstream: DataSource.Factory,
    ) {
        var lastLog = 0L
        var stuck = 0
        var lastFrom = -1L
        while (coroutineContext.isActive) {
            val target = preloadTargetBytes
            val posB = preloadPosBytes
            val url = preloadUrl
            if (target <= 0 || url.isBlank()) {
                kotlinx.coroutines.delay(500)
                continue
            }
            // 只统计「从播放位置往后」已缓存了多少（不从 0 量，避免遇到洞恒为 0）
            val span = (target - posB).coerceAtLeast(1L)
            val ahead = runCatching { store.getCachedBytes(itemId, posB, span) }.getOrDefault(0L)
            val cached = posB + ahead

            if (cached >= target - 2L * 1024 * 1024) {
                // 达到阈值 → 休眠（这就是「暂时不疯狂下载」）
                if (android.os.SystemClock.elapsedRealtime() - lastLog > 15_000L) {
                    lastLog = android.os.SystemClock.elapsedRealtime()
                    Log.i(TAG, "preload hold item=$itemId cached=${ahead / 1048576}MB target=${(target - posB) / 1048576}MB")
                }
                kotlinx.coroutines.delay(2_000)
                continue
            }
            // 无进展保护：连续多次起点不变就休眠，绝不死循环
            if (cached == lastFrom) {
                stuck++
                if (stuck >= 3) {
                    Log.w(TAG, "preload stuck item=$itemId at=${ahead / 1048576}MB，休眠")
                    kotlinx.coroutines.delay(5_000)
                    stuck = 0
                    lastFrom = -1L
                    continue
                }
            } else {
                stuck = 0
            }
            lastFrom = cached

            /**
             * 每轮只下 8MB：CacheWriter 内部 128KB 缓冲，
             * 下完立刻 close 数据源 → 内存回到基线，不会累积。
             */
            val CHUNK = 8L * 1024 * 1024
            val want = minOf(target - cached, CHUNK).coerceAtLeast(1L)
            val ds = runCatching {
                CacheDataSource.Factory()
                    .setCache(store)
                    .setUpstreamDataSourceFactory(upstream)
                    .setCacheKeyFactory(keyFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                    .createDataSource() as CacheDataSource
            }.getOrNull() ?: break

            val spec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(android.net.Uri.parse(url))
                .setKey(itemId)
                .setPosition(cached)
                .setLength(want)
                .build()
            try {
                androidx.media3.datasource.cache.CacheWriter(ds, spec, null, null).cache()
            } catch (e: java.io.InterruptedIOException) {
                runCatching { ds.close() }
                break
            } catch (t: Throwable) {
                Log.w(TAG, "preload chunk failed at=${cached / 1048576}MB: ${t.message}")
                runCatching { ds.close() }
                kotlinx.coroutines.delay(1_500)
                continue
            }
            runCatching { ds.close() }
        }
        Log.i(TAG, "preload exit item=$itemId")
    }

    fun stopPreload() {
        preloadJob?.cancel()
        preloadJob = null
        preloadItemId = ""
        preloadTargetBytes = 0L
        preloadUrl = ""
    }

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
