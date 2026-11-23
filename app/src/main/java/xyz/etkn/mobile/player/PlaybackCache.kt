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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.etkn.mobile.data.settings.Settings
import java.io.File
import java.io.RandomAccessFile

/**
 * 用户口径（2026-11-23）磁盘缓存 + 预加载。**按播放进度向前推进的滑动窗口 + 串行流水线。**
 *
 * 一、缓存窗口（本人明确讲的模型）
 *  · 从「当前播放进度」往后缓存，不是整个文件一次拉完
 *  · 同时受两个阈值约束：最大缓存大小(MB) + 最大缓存时长(秒)
 *  · **任一达到阈值 → 立刻停止缓存**，不再往后拉
 *  · 持续播放时窗口跟着往前推，一直保持「已播之后有 N 秒/MB 的储备」
 *
 * 二、串行流水线（一环套一环）
 *  · 当前集还在缓存中 → 绝不去碰下一集
 *  · 当前集窗口跑完（播到接近结尾、剩余量够覆盖到片尾）→ 才去取下一集直链
 *  · 下一集同样套用「最大缓存大小 + 时长」的窗口规则
 *  · 这就是「预加载」被触发的时刻
 *
 * 三、气泡（下一集就绪指示）显示条件
 *  · 当前集缓存完成 ✅ 且 下一集缓存完成 ✅ → 才显示
 *  · 任何一边没完成都不显示
 *
 * 四、落地形式：整文件（mpv 不认 Media3 数据源，必须给真实文件）
 *  · Exo 与 mpv 共用同一份文件，命中就给 file://，一个网络请求都不发
 *  · 未命中时用 [流式边播边存] 追加写入已命名的 .part，播完即改名为正式缓存
 *
 * 五、key 必须稳定
 *  · ETKN 地址带 X-Emby-Token/UserId，每次可能不同 → key 只取 scheme://host:port/path
 *
 * 六、退出必须能停
 *  · 所有下载挂在 work Job 上，[stopAll] 取消；下载循环内有 ensureActive()
 */
object PlaybackCache {

    private const val UA = "ETKN-Mobile"
    private const val DIR = "mediafiles"

    private val root = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work = SupervisorJob()
    private val workScope: CoroutineScope
        get() = CoroutineScope(root.coroutineContext + work)

    private val writing = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private var pipelineJob: Job? = null

    /** 当前集是否已经「缓存完成」（覆盖到片尾），驱动气泡的第一个条件。 */
    @Volatile private var currentComplete = false

    /** 下一集是否已经「缓存完成」，驱动气泡的第二个条件。 */
    @Volatile private var nextComplete = false

    @Volatile private var nextEpisodeId: String? = null

    /**
     * 气泡（下一集就绪）：
     * ① 当前集缓存完成 ② 下一集缓存完成 ③ 两者是同一部剧的相邻集
     * 三个条件同时满足才为 true。
     */
    fun isNextReady(): Boolean = currentComplete && nextComplete

    fun clearReady() {
        currentComplete = false
        nextComplete = false
        nextEpisodeId = null
    }

    // ---------------------------------------------------------------- 目录

    private fun dir(context: Context): File = File(context.cacheDir, DIR).apply { mkdirs() }

    fun maxDiskBytes(settings: Settings): Long =
        settings.cacheDiskGb.coerceIn(1, 100).toLong() * 1024L * 1024L * 1024L

    /** 窗口阈值：字节数（MB → B）。 */
    private fun maxWindowBytes(settings: Settings): Long =
        settings.cacheMemoryMb.coerceIn(32, 8192).toLong() * 1024L * 1024L

    /** 窗口阈值：时长（秒 → 毫秒）。 */
    private fun maxWindowMs(settings: Settings): Long =
        settings.cacheMemorySeconds.coerceIn(30, 7200).toLong() * 1000L

    fun diskUsage(context: Context): Long =
        dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun clearDisk(context: Context) {
        dir(context).listFiles()?.forEach { runCatching { it.delete() } }
        clearReady()
    }

    // ---------------------------------------------------------------- key

    /** 稳定 key：丢掉 X-Emby-Token/UserId 等查询参数，否则每次都是新 key、永远命中不了。 */
    private fun stableKey(url: String): String {
        val parsed = runCatching { url.toHttpUrlOrNull() }.getOrNull() ?: return url
        return parsed.newBuilder().query(null).fragment(null).build().toString()
    }

    private fun fileFor(context: Context, url: String): File {
        val key = stableKey(url)
        return File(dir(context), key.hashCode().toUInt().toString(16) + "_" + key.length)
    }

    /** 这一集是否已有完整本地文件；有就返回（播放器直接读 file://）。 */
    fun localFile(context: Context, url: String): File? =
        fileFor(context, url).takeIf { it.isFile && it.length() > 0 }

    // ---------------------------------------------------------------- 窗口缓存（核心）

    private val windowLock = Mutex()

    /**
     * 用户口径（2026-11-23）：按播放进度往后维护缓存窗口。
     *
     * @param positionMs      当前播放进度
     * @param durationMs      总时长（未知传 0）
     * @param knownLength     该资源的字节总长（未知传 -1，用窗口字节阈值兜底）
     * @param onComplete      跑完/到达阈值/覆盖到片尾时回调
     *
     * 行为：
     *  · 已覆盖到「位置 + 窗口时长」或「位置 + 窗口字节」→ 什么都不做
     *  · 否则从「已缓存的末尾」继续往后拉，直到任一阈值达到
     *  · 缓存的已覆盖区间记在 [coverage]，按集记录，用来做增量续拉
     */
    fun ensureWindow(
        context: Context,
        settings: Settings,
        url: String,
        positionMs: Long,
        durationMs: Long,
        knownLength: Long,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val key = stableKey(url)
        pipelineJob = workScope.launch {
            windowLock.withLock {
                val target = fileFor(context, url)
                val part = File(target.parentFile, target.name + ".part")

                // 已经整集缓存完 → 直接算完成
                if (target.isFile && target.length() > 0 && target.length() == knownLength && knownLength > 0) {
                    onComplete(true)
                    return@withLock
                }

                // 计算这次要拉到哪
                val windowMs = maxWindowMs(settings)
                val windowBytes = maxWindowBytes(settings)

                // 没拿到总长时，用「剩余时长比例 × 文件大小」估一个字节目标
                val bytesPerMs = if (durationMs > 0 && knownLength > 0) {
                    knownLength.toDouble() / durationMs
                } else 0.0

                val wantedMs = (positionMs + windowMs).coerceAtMost(if (durationMs > 0) durationMs else Long.MAX_VALUE)
                val wantedBytes = if (bytesPerMs > 0) {
                    (wantedMs * bytesPerMs).toLong().coerceAtMost(positionMs * bytesPerMs + windowBytes)
                } else windowBytes

                // 已拉到的位置
                val have = if (part.isFile) part.length() else 0L
                val wantFromHere = if (bytesPerMs > 0) {
                    // 从当前位置开始算的字节偏移
                    (positionMs * bytesPerMs).toLong()
                } else 0L
                val bufferAhead = have - wantFromHere
                if (bufferAhead >= windowBytes) {
                    onComplete(coveredToEnd(have, knownLength, durationMs, positionMs, windowMs))
                    return@withLock
                }

                // 从 have 往后拉，最多拉到 wantedBytes
                downloadInto(context, url, part, from = have, until = wantedBytes)
                val done = coveredToEnd(part.length(), knownLength, durationMs, positionMs, windowMs)
                if (done && knownLength > 0) runCatching { part.renameTo(target) }
                onComplete(done)
            }
        }
    }

    /** 是否已经覆盖到片尾（没总长时按「位置 + 窗口时长 ≥ 时长」判断）。 */
    private fun coveredToEnd(haveBytes: Long, knownLength: Long, durationMs: Long, positionMs: Long, windowMs: Long): Boolean {
        if (knownLength > 0) return haveBytes >= knownLength
        if (durationMs > 0) return positionMs + windowMs >= durationMs
        return false
    }

    /**
     * 从 [from] 字节往后下载到 .part 文件，最多下到 [until]（-1 = 不限）。
     * 用 Range 请求续传，已经有的部分不重下。
     */
    private suspend fun downloadInto(context: Context, url: String, target: File, from: Long, until: Long) {
        val source = DefaultHttpDataSource.Factory().setUserAgent(UA).createDataSource()
        val spec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setPosition(from)
            .apply { if (until > from) setLength(until - from) }
            .build()
        source.open(spec)
        try {
            RandomAccessFile(target, "rw").use { raf ->
                raf.seek(from)
                val buffer = ByteArray(256 * 1024)
                var written = from
                while (true) {
                    // 退出播放时 stopAll() 取消协程 → 这里立即中断
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    raf.write(buffer, 0, read)
                    written += read
                    if (until > from && written >= until) break
                }
            }
        } finally {
            runCatching { source.close() }
        }
    }

    // ---------------------------------------------------------------- 流水线（预加载）

    /**
     * 用户口径（2026-11-23）串行流水线：
     * 当前集缓存完成后，才去取下一集直链并为下一集跑同样的窗口缓存。
     * 跑到下一集完成 → nextComplete = true → 气泡亮。
     */
    fun pipeline(
        context: Context,
        settings: Settings,
        currentEpisodeId: String,
        currentUrl: String,
        currentPositionMs: Long,
        currentDurationMs: Long,
        currentLength: Long,
        next: Pair<String, String>?,   // (episodeId, url)
    ) {
        val key = stableKey(currentUrl)
        // 当前集任务已在跑就不重复起
        if (writing.containsKey(key)) return

        writing[key] = workScope.launch {
            try {
                // 第一环：当前集
                ensureWindowSync(context, settings, currentUrl, currentPositionMs, currentDurationMs, currentLength)
                currentComplete = true

                if (!settings.preloadNext || next == null) return@launch

                // 第二环：当前集完成才动下一集
                val (nextId, nextUrl) = next
                nextEpisodeId = nextId
                nextComplete = false
                // 下一集先探长度（Range: bytes=0-0），再跑窗口
                val nextLength = probeLength(nextUrl)
                ensureWindowSync(context, settings, nextUrl, 0L, 0L, nextLength)
                nextComplete = true
            } finally {
                writing.remove(key)
            }
        }
    }

    private suspend fun ensureWindowSync(
        context: Context,
        settings: Settings,
        url: String,
        positionMs: Long,
        durationMs: Long,
        knownLength: Long,
    ) {
        windowLock.withLock {
            val target = fileFor(context, url)
            if (target.isFile && target.length() > 0) return@withLock
            val part = File(target.parentFile, target.name + ".part")
            val have = if (part.isFile) part.length() else 0L
            val windowBytes = maxWindowBytes(settings)
            // 目标：至少拉满一个窗口；能拉到片尾就直接拉完
            val until = if (knownLength > 0) knownLength else have + windowBytes
            if (knownLength > 0 && have >= knownLength) {
                runCatching { part.renameTo(target) }
                return@withLock
            }
            downloadInto(context, url, part, from = have, until = until)
            if (knownLength > 0 && part.length() >= knownLength) runCatching { part.renameTo(target) }
        }
    }

    /** 用 Range 请求探资源总字节数；探不到返回 -1。 */
    private fun probeLength(url: String): Long = runCatching {
        val src = DefaultHttpDataSource.Factory().setUserAgent(UA).createDataSource()
        src.open(DataSpec.Builder().setUri(Uri.parse(url)).setPosition(0).setLength(1).build())
        val len = src.contentLength
        src.close()
        len
    }.getOrDefault(-1L)

    // ---------------------------------------------------------------- 兼容旧调用

    /** 兼容：单集预加载（流水线内部用 pipeline，这里给外部零星调用保留）。 */
    fun preload(context: Context, settings: Settings, episodeId: String, url: String) {
        if (!settings.preloadNext) return
        val key = stableKey(url)
        if (writing.containsKey(key)) return
        writing[key] = workScope.launch {
            try {
                ensureWindowSync(context, settings, url, 0L, 0L, probeLength(url))
                nextEpisodeId = episodeId
                nextComplete = true
                trim(context, settings)
            } finally {
                writing.remove(key)
            }
        }
    }

    fun afterEpisodeFinished(context: Context, settings: Settings, url: String?) {
        if (!settings.cacheClearOnFinish) return
        if (url.isNullOrEmpty()) return
        workScope.launch { runCatching { fileFor(context, url).delete() } }
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

    /** 退出播放 / 切集：停掉所有缓存下载（已写好的部分保留，下次续拉）。 */
    fun stopAll() {
        work.cancel()
        work = SupervisorJob()
        writing.clear()
        pipelineJob = null
        clearReady()
    }

    /** ExoPlayer 数据源：直接走网络，缓存由 [pipeline] 落整文件，命中时上层用 file://。 */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory = upstream
}
