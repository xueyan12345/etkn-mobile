package xyz.etkn.mobile.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.settings.Settings
import java.io.File
import okhttp3.HttpUrl

/**
 * 用户口径（2026-11-23）磁盘缓存 / 预加载。**两个内核共用同一份整文件缓存。**
 *
 * 设计要点（都是踩过的坑）：
 *  1. 缓存 key 必须稳定：ETKN 播放地址里带 `X-Emby-Token`、`UserId`，每次可能不同，
 *     直接拿完整 URL 当 key 会导致「明明缓存过，下次还是没命中」（之前就是这个 bug）。
 *     所以 key 只取 scheme://host:port/path，查询串整段丢弃。
 *  2. mpv 不认 Media3 的数据源，所以缓存落成**整文件**，命中就给它 file:// 路径。
 *     ExoPlayer 也用同一份文件（命中就本地读），避免两套缓存重复占空间。
 *  3. 退出播放必须能停：下载循环里有 ensureActive()，配合 [stopAll] 立即中断，
 *     不会出现「退出播放了网速还在跑」。
 *  4. 超过设置的磁盘上限按最后修改时间淘汰。
 */
object PlaybackCache {

    private const val UA = "ETKN-Mobile"
    private const val DIR = "mediafiles"

    /** 常驻协程宿主。 */
    private val root = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 所有缓存下载都挂在这个子 Job 上；[stopAll] 一把取消。
     * 之前直接往 root 上 launch，退出播放器后下载还在后台跑。
     */
    private var work = SupervisorJob()
    private val workScope: CoroutineScope
        get() = CoroutineScope(root.coroutineContext + work)

    /** 正在下载的 URL → 任务，避免同一集重复下。 */
    private val writing = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private var preloadJob: Job? = null

    /** 已完整缓存好的集 id（驱动「下一集」按钮上的闪电小圆）。 */
    @Volatile private var warmedEpisodeId: String? = null

    fun isWarmed(episodeId: String?): Boolean = episodeId != null && episodeId == warmedEpisodeId
    fun clearWarmed() { warmedEpisodeId = null }

    // ---------------------------------------------------------------- 目录与占用

    private fun dir(context: Context): File =
        File(context.cacheDir, DIR).apply { mkdirs() }

    fun maxDiskBytes(settings: Settings): Long =
        settings.cacheDiskGb.coerceIn(1, 100).toLong() * 1024L * 1024L * 1024L

    fun diskUsage(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearDisk(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
        warmedEpisodeId = null
    }

    // ---------------------------------------------------------------- 缓存 key

    /**
     * 稳定 key：只保留 scheme://host:port/path。
     * ETKN 的 X-Emby-Token / UserId 都在查询串里，丢掉它们 key 才不变。
     */
    private fun stableKey(url: String): String {
        val parsed = runCatching { HttpUrl.get(url) }.getOrNull() ?: return url
        return parsed.newBuilder().query(null).fragment(null).build().toString()
    }

    private fun fileFor(context: Context, url: String): File {
        val key = stableKey(url)
        val name = key.hashCode().toUInt().toString(16) + "_" + key.length
        return File(dir(context), name)
    }

    /** 这一集是否已经有完整的本地文件；有就返回（交给播放器用 file://）。 */
    fun localFile(context: Context, url: String): File? =
        fileFor(context, url).takeIf { it.isFile && it.length() > 0 }

    // ---------------------------------------------------------------- 下载

    /**
     * 把整集写到本地缓存文件。
     * 已存在直接返回；否则先写 .part，成功后再改名（避免半截文件被当成完整缓存）。
     */
    suspend fun ensureLocalFile(
        context: Context,
        url: String,
        onProgress: ((Long) -> Unit)? = null,
    ): File? {
        val target = fileFor(context, url)
        if (target.isFile && target.length() > 0) return target
        val temp = File(target.parentFile, target.name + ".part")
        return runCatching {
            val source = DefaultHttpDataSource.Factory().setUserAgent(UA).createDataSource()
            source.open(DataSpec(Uri.parse(url)))
            try {
                temp.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var total = 0L
                    while (true) {
                        // 退出播放时 stopAll() 取消协程，这里立刻中断，不再继续拉流。
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer, 0, buffer.size)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        total += read
                        onProgress?.invoke(total)
                    }
                }
            } finally {
                runCatching { source.close() }
            }
            if (temp.length() > 0 && temp.renameTo(target)) target else temp.delete().let { null }
        }.getOrNull()
    }

    /** 播放时后台把这一集写进缓存（下次进来直接读本地）。重复调用会被拦掉。 */
    fun cacheWhilePlaying(context: Context, url: String) {
        if (writing.containsKey(url)) return
        if (localFile(context, url) != null) return
        writing[url] = workScope.launch {
            try {
                ensureLocalFile(context, url)
            } finally {
                writing.remove(url)
            }
        }
    }

    /** 预加载（当前集缓存完后，后台提前把下一集落盘）。 */
    fun preload(context: Context, settings: Settings, episodeId: String, url: String) {
        if (!settings.preloadNext) return
        if (localFile(context, url) != null) {
            warmedEpisodeId = episodeId
            return
        }
        preloadJob?.cancel()
        preloadJob = workScope.launch {
            val file = ensureLocalFile(context, url) ?: return@launch
            trim(context, settings)
            if (file.isFile) warmedEpisodeId = episodeId
        }
    }

    /** 超过磁盘上限就按最后修改时间删最旧的。 */
    private fun trim(context: Context, settings: Settings) {
        val limit = maxDiskBytes(settings)
        val files = dir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (file in files) {
            if (total <= limit) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /** 播完一集按设置清掉该集缓存。 */
    fun afterEpisodeFinished(context: Context, settings: Settings, url: String?) {
        if (!settings.cacheClearOnFinish) return
        if (url.isNullOrEmpty()) return
        workScope.launch { runCatching { fileFor(context, url).delete() } }
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 退出播放 / 切集：停掉所有缓存下载。
     * 已写好的文件保留（下次可以直接命中），只是不再继续占网速。
     */
    fun stopAll() {
        work.cancel()
        work = SupervisorJob()
        writing.clear()
        preloadJob = null
    }

    /** ExoPlayer 的数据源：直接走网络（缓存由 [cacheWhilePlaying] 落整文件，下次命中 file://）。 */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory = upstream
}
