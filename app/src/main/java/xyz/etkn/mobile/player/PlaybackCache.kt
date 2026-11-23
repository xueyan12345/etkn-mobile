package xyz.etkn.mobile.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
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
 * 用户口径（2026-11-23）：磁盘缓存 + 预加载。**两个播放内核共用同一份缓存**。
 *
 * 缓存本体是 Media3 的 [SimpleCache]（一个目录一份），key 就是视频 URL。
 *  · ExoPlayer：通过 [CacheDataSource] 读，命中就走本地，没命中边下边写
 *  · mpv：mpv 不认 Media3 的数据源，所以走 [localFile] —— 缓存里已有完整文件就直接
 *    把本地路径交给 mpv 播放（loadfile /path/to/cached）；没有则先下载完再交给它。
 *    这样两个内核看到的是同一份缓存，不会重复占空间。
 *
 * 设置里的六项都落在这里：
 *  · preloadNext         —— 是否把「下一集」提前缓存
 *  · cacheMemoryMb/秒    —— ExoPlayer 的前向缓冲上限（时长 + 字节双限）
 *  · cacheDiskGb         —— 缓存目录上限，超出按 LRU 淘汰
 *  · cacheClearOnFinish  —— 播完清掉该集缓存
 *  · 清空磁盘缓存        —— 手动清 + 显示占用
 */
object PlaybackCache {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cache: SimpleCache? = null
    private var cacheLimit: Long = 0L
    private var preloadJob: Job? = null

    /** 已经完整缓存过的集 id，用来给「下一集」图标显示闪电小圆。 */
    @Volatile private var warmedEpisodeId: String? = null

    fun isWarmed(episodeId: String?): Boolean = episodeId != null && episodeId == warmedEpisodeId
    fun clearWarmed() { warmedEpisodeId = null }

    private const val UA = "ETKN-Mobile"

    /** 磁盘上限（GB → 字节）。 */
    fun maxDiskBytes(settings: Settings): Long =
        settings.cacheDiskGb.coerceIn(1, 100).toLong() * 1024L * 1024L * 1024L

    @Synchronized
    fun cache(context: Context, settings: Settings): SimpleCache {
        cache?.let { return it }
        val dir = File(context.cacheDir, "media")
        dir.mkdirs()
        val limit = maxDiskBytes(settings)
        cacheLimit = limit
        val built = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(limit))
        cache = built
        return built
    }

    /** 当前磁盘缓存占用（字节）。 */
    fun diskUsage(): Long = runCatching { cache?.cacheSpace ?: 0L }.getOrDefault(0L)

    fun clearDisk() {
        runCatching {
            cache?.keys?.toList()?.forEach { key -> cache?.removeResource(key) }
        }
        warmedEpisodeId = null
    }

    /**
     * ExoPlayer 用：把网络层套进缓存。
     * 命中缓存 → 直接读本地；没命中 → 边下载边写缓存。
     */
    fun dataSourceFactory(
        context: Context,
        settings: Settings,
        upstream: DataSource.Factory,
    ): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context, settings))
            .setUpstreamDataSourceFactory(upstream)
            // 缓存坏了也只是退化成走网络，不让播放失败
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * mpv 用：判断这一集是否已经完整缓存在本地，有就返回本地文件路径。
     *
     * mpv 只认文件/URL，不认 Media3 的 CacheDataSource，所以这里给它真实文件。
     * SimpleCache 的 span 文件是按 key 分布的碎片，不能直接给 mpv 播；
     * 所以这里检查「有没有一个独占的整文件缓存」（见 [ensureLocalFile]）。
     */
    fun localFile(context: Context, settings: Settings, url: String): File? {
        val file = cachedFileFor(context, url) ?: return null
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    private fun cacheDir(context: Context) = File(context.cacheDir, "mediafiles").apply { mkdirs() }

    /** 每个 URL 一个文件名（URL 的 hash），整文件存放，mpv 可以直接播。 */
    private fun cachedFileFor(context: Context, url: String): File {
        val name = url.hashCode().toUInt().toString(16) + "_" + url.length
        return File(cacheDir(context), name)
    }

    /**
     * 确保这一集有一个完整的本地文件（供 mpv 播放，也供预加载）。
     * 已存在就直接返回；不存在则整段下载到临时文件后改名（避免半截文件被当成完整缓存）。
     */
    fun ensureLocalFile(
        context: Context,
        settings: Settings,
        url: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): File? {
        val target = cachedFileFor(context, url)
        if (target.isFile && target.length() > 0) return target
        val temp = File(target.parentFile, target.name + ".part")
        return runCatching {
            val factory = DefaultHttpDataSource.Factory().setUserAgent(UA)
            val source = factory.createDataSource()
            source.open(DataSpec(Uri.parse(url)))
            try {
                temp.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    // DefaultHttpDataSource 不是 DataSource 的 contentLength()；
                    // open() 之后可以从 DataSpec 的长度或响应头拿，取不到就用 -1 表示未知。
                    val length = -1L
                    while (true) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        total += read
                        onProgress?.invoke(total, if (length > 0) length else -1)
                    }
                }
            } finally {
                runCatching { source.close() }
            }
            if (temp.length() > 0 && temp.renameTo(target)) target else temp.delete().let { null }
        }.getOrNull()
    }

    /**
     * 预加载一集（供上一集看到下一集时提前缓存）。
     * 写进 [cacheDir] 的整文件区，mpv / ExoPlayer 都能用。
     */
    fun preload(context: Context, settings: Settings, episodeId: String, url: String) {
        if (!settings.preloadNext) return
        preloadJob?.cancel()
        preloadJob = scope.launch {
            val file = ensureLocalFile(context, settings, url) ?: return@launch
            // 按磁盘上限裁剪整文件区
            trimCacheDir(context, settings)
            if (file.isFile) warmedEpisodeId = episodeId
        }
    }

    /** 正在后台缓存的 URL，避免重复下载同一集。 */
    private val writing = java.util.concurrent.ConcurrentHashMap<String, Job>()

    /**
     * 用户口径（2026-11-23）边播边缓存：
     * 一边播一边把这一集整段落到本地缓存文件。下次进同一集时 [localFile] 直接命中，
     * 播放器拿到 file:// 就不会再请求服务器。
     *
     * 与预加载共用同一份文件，重复调用会被 [writing] 挡掉。
     */
    fun cacheWhilePlaying(context: Context, settings: Settings, episodeId: String, url: String) {
        if (writing.containsKey(url)) return
        if (localFile(context, settings, url) != null) return
        val job = scope.launch {
            try {
                ensureLocalFile(context, settings, url)
                trimCacheDir(context, settings)
            } finally {
                writing.remove(url)
            }
        }
        writing[url] = job
    }

    /** 超过设置的上限就按最后修改时间删最旧的。 */
    private fun trimCacheDir(context: Context, settings: Settings) {
        val limit = maxDiskBytes(settings)
        val dir = cacheDir(context)
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limit) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /** 播完一集后按设置清掉该集缓存。 */
    fun afterEpisodeFinished(context: Context, settings: Settings, url: String?) {
        if (!settings.cacheClearOnFinish) return
        if (url.isNullOrEmpty()) return
        scope.launch {
            runCatching { cachedFileFor(context, url).delete() }
            runCatching { cache(context, settings).removeResource(url) }
        }
    }
}
