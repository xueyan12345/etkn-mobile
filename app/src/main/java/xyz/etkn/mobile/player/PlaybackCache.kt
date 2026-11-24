package xyz.etkn.mobile.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
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
import okhttp3.OkHttpClient

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

    /**
     * 用户口径（2026-11-24）缓存下载专用 OkHttp client。
     *
     * 必须和播放器用同一套连接池/UA —— 裸 DefaultHttpDataSource 不带任何
     * 认证信息，下载直链时服务端返 403，当时直接把进程崩了。
     * `clients.media` 是 media 专用 client（stream URL 自带凭据，跟随重定向）。
     *
     * 这个实例在 PlayerCache.init() 里由 AppGraph 注入。
     */
    private var mediaClient: OkHttpClient = OkHttpClient()

    /** 由 AppGraph 在启动时注入 media client（带连接池 + UA）。 */
    fun init(client: OkHttpClient) {
        mediaClient = client
    }
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
    fun isNextReady(): Boolean {
        val ready = currentComplete && nextComplete
        /**
         * 用户口径（2026-11-24）**修「缓冲满了气泡还是不亮」**。
         *
         * 上一版 currentComplete 只由 pullWindow 的返回值决定，
         * 而 pullWindow 在「窗口已够、本轮没下载」时返回 false ——
         * 于是即使缓冲条已经铺到片尾，currentComplete 依然是 false，气泡永远不亮。
         *
         * 现在这里补一条日志，把两个标志的真实值打出来，方便一眼定位断在哪一环；
         * 同时 currentComplete 的判定改由 tickCache 直接写（见 VideoSession）。
         */
        android.util.Log.i(
            "ETKN-Cache",
            "isNextReady=$ready currentComplete=$currentComplete nextComplete=$nextComplete next=$nextEpisodeId",
        )
        return ready
    }

    /** 用户口径（2026-11-24）：由 tickCache 按真实缓冲位置直接写「当前集是否已到片尾」。 */
    fun markCurrentComplete(done: Boolean) {
        currentComplete = done
    }

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

    // ---------------------------------------------------------------- 缓存 key（itemId 为主键）

    /**
     * 用户口径（2026-11-23）**缓存 key 改成 itemId，不再依赖 URL**。
     *
     * 之前用 URL 做 key 的致命问题：URL 必须请求 PlaybackInfo 才有，
     * 于是每次起播都得先等一个网络请求，才知道「有没有缓存」——
     * 起播慢就慢在这一步，接口一慢缓存就等于白做。
     *
     * 改成 itemId 之后（用户原话：有缓存就直接先播缓存，播放的时候再继续请求网络）：
     *   ① 点开一集 → 立刻知道有没有缓存文件（纯本地 IO，零网络）
     *   ② 有缓存 → 马上 file:// 起播，同时后台照常请求接口（拿下一集、续传、进度上报）
     *   ③ 没缓存 → 走网络，边播边写
     *
     * itemId 是 ETKN 的稳定主键（item-25098 这种），永远不变。
     */
    private fun fileForId(context: Context, itemId: String): File {
        val safe = itemId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(dir(context), safe)
    }

    /** 这一集是否已有完整本地文件（只查本地，零网络）。 */
    fun localFileById(context: Context, itemId: String): File? =
        fileForId(context, itemId).takeIf { it.isFile && it.length() > 0 }

    /** 这一集的部分缓存文件（.part），只用于续传，不作为播放源。 */
    fun partFileById(context: Context, itemId: String): File =
        File(dir(context), fileForId(context, itemId).name + ".part")

    /** itemId → 播放直链（下载/续传用）。 */
    private val knownUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun rememberUrl(itemId: String, url: String) {
        if (itemId.isNotBlank() && url.isNotBlank()) knownUrls[itemId] = url
    }

    fun knownUrlFor(itemId: String): String? = knownUrls[itemId]

    /**
     * 用户口径（2026-11-23）：磁盘缓存已覆盖到第几毫秒（画进度条缓冲段）。
     *
     * 进度条原来画的是 player.bufferedPosition —— 那是播放器内核几秒的前向缓冲，
     * 和设置里的「最大缓存大小/时长」完全是两套东西，所以永远只有细细一小条。
     * 这里按 itemId 直接读缓存文件大小换算。
     */
    fun cachedPositionMs(context: Context, itemId: String, durationMs: Long, totalBytes: Long): Long {
        val full = fileForId(context, itemId)
        if (full.isFile && full.length() > 0) return if (durationMs > 0) durationMs else 0L
        val part = partFileById(context, itemId)
        val have = if (part.isFile) part.length() else 0L
        if (have <= 0 || totalBytes <= 0 || durationMs <= 0) return 0L
        val frac = (have.toDouble() / totalBytes).coerceIn(0.0, 1.0)
        return (durationMs * frac).toLong()
    }

    /** 稳定 key：丢掉 token/UserId 等查询参数。按 URL 查的场景（预加载）仍需要它。 */
    private fun stableKey(url: String): String {
        val parsed = runCatching { url.toHttpUrlOrNull() }.getOrNull() ?: return url
        return parsed.newBuilder().query(null).fragment(null).build().toString()
    }

    private fun fileFor(context: Context, url: String): File {
        val key = stableKey(url)
        return File(dir(context), "u_" + key.hashCode().toUInt().toString(16) + "_" + key.length)
    }

    /** 这一集是否已有完整本地文件（按 URL 查，供预加载用）。 */
    fun localFile(context: Context, url: String): File? =
        fileFor(context, url).takeIf { it.isFile && it.length() > 0 }

    /**
     * 部分缓存（.part）—— **不交给播放器当播放源**。
     * MKV/MP4 的索引在文件末尾，半截文件不是合法可播文件，
     * 之前拿它起播导致 ExoPlayer 报 Malformed URL 并自动切 mpv。
     * 保留这个查询只用于「续传进度」和缓冲条显示。
     */
    fun partialFile(context: Context, url: String): File? {
        val full = fileFor(context, url)
        if (full.isFile && full.length() > 0) return full
        val part = File(full.parentFile, full.name + ".part")
        return part.takeIf { it.isFile && it.length() > 512 * 1024 }
    }


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
    /**
     * 用户口径（2026-11-23）核心：把「从 0 到 播放位置+窗口」这一段落到本地。
     *
     * 之前这段写得太绕（算 bufferAhead、算 wantedBytes 一堆分支），
     * 实际结果是窗口不推进、缓冲条只有一小节。现在只用一句话说清：
     *
     *     目标字节 = min(总长, (播放位置 + 窗口时长) 对应的字节)
     *     已有字节 = .part 的长度
     *     目标字节 > 已有字节 → 从「已有字节」处 Range 续拉
     *     拉到的字节 ≥ 总长 → 改名成正式缓存文件（整集完成）
     *
     * 两个阈值（最大缓存大小 / 最大缓存时长）取小者：
     *     byTime  = (position + 时长阈值) 换算成字节
     *     bySize  = position 换算成字节 + 大小阈值
     *     target  = min(byTime, bySize, 总长)
     */
    /**
     * 用户口径（2026-11-24）**滑动窗口核心**。
     *
     * 语义（用户口述，逐条对齐）：
     *  ① 两个条件（时间 / 大小）达成任一个，就【这一轮】不再往后扩
     *  ② 但窗口跟着播放位置走 —— 播放前进，窗口就前进
     *     → 缓冲条 = 播放位置 + 窗口长度，在单集内恒长
     *     → 边播边缓冲、边缓冲边暂停、边暂停再缓冲，全程动态
     *  ③ 「当前集缓冲完成」的判据【只看往后】：
     *     从当前播放位置到片尾已经无可缓冲（have >= total）
     *     从中间开始播时前面没缓存，不影响判定
     *
     * @return true = 从当前位置到片尾已经全在本地（可触发下一集预加载）
     */
    private suspend fun pullWindow(
        client: OkHttpClient,
        context: Context,
        settings: Settings,
        itemId: String,
        url: String,
        positionMs: Long,
        durationMs: Long,
        knownLength: Long,
    ): Boolean = windowLock.withLock {
        val target = fileForId(context, itemId)
        // 整集已完整落盘
        if (target.isFile && target.length() > 0) return@withLock true
        val part = File(target.parentFile, target.name + ".part")

        val windowMs = maxWindowMs(settings)
        val windowBytes = maxWindowBytes(settings)
        // 总长为 0 时无法按时间换算，只能用大小窗口
        val bytesPerMs = if (durationMs > 0 && knownLength > 0) knownLength.toDouble() / durationMs else 0.0

        val total = if (knownLength > 0) knownLength else Long.MAX_VALUE
        val posBytes = if (bytesPerMs > 0) (positionMs * bytesPerMs).toLong() else 0L

        /**
         * 目标覆盖到哪：
         *   byTime = 当前位置 + 时间窗口
         *   bySize = 当前位置 + 大小窗口
         *   取小者（任一条达成就不扩）—— 这就是用户说的「两个达成一个就不再缓存」
         */
        val byTime = if (bytesPerMs > 0) posBytes + (windowMs * bytesPerMs).toLong() else Long.MAX_VALUE
        val bySize = if (windowBytes > 0) posBytes + windowBytes else Long.MAX_VALUE
        var wantBytes = minOf(byTime, bySize)
        // 两个阈值都没配（都为 0）时不做上限，直接拉满
        if (wantBytes == Long.MAX_VALUE) wantBytes = total
        if (total != Long.MAX_VALUE) wantBytes = wantBytes.coerceAtMost(total)

        var have = if (part.isFile) part.length() else 0L

        // 已经够（窗口到位），本轮不下载，但要返回「是否已到片尾」
        if (have < wantBytes) {
            val chunk = 12L * 1024 * 1024
            while (have < wantBytes) {
                val until = minOf(wantBytes, have + chunk)
                downloadInto(client, url, part, from = have, until = until)
                val after = if (part.isFile) part.length() else 0L
                if (after <= have) break   // 没进展，避免死循环
                have = after
            }
        }

        // 拉满整集 → 转正
        val reachedEnd = total != Long.MAX_VALUE && have >= total
        if (reachedEnd) {
            runCatching { part.renameTo(target) }
            runCatching { trim(context, settings, protect = target) }
            return@withLock true
        }
        runCatching { trim(context, settings, protect = part) }
        // 没到片尾：本次窗口到位就算「这一轮完成」，但不代表整集完成
        return@withLock false
    }

    /**
     * 用户口径（2026-11-24）**下一集预加载量**（含片头优先规则）。
     *
     * @param introEndMs 下一集片头结束位置（无片头数据传 -1）
     *
     * 规则：
     *  · 有片头：目标 = 片头结束 + 1 分钟
     *      配额能覆盖到那里 → 按配额继续
     *      配额覆盖不到 → 突破配额，硬缓冲到「片头后 1 分钟」
     *  · 无片头：按配额（时间 / 大小）
     */
    /**
     * 用户口径（2026-11-24）**下一集预加载量**（片头优先）。
     *
     * 规则（用户口述）：
     *  ① 下一集有片头数据 → 目标 = 片头结束 + 1 分钟
     *     · 配额（时间/大小）能覆盖到那个位置 → 按配额继续往下缓冲
     *     · 配额覆盖不到 → **突破配额**，硬缓冲到「片头结束 + 1 分钟」
     *  ② 下一集没有片头数据 → 按配额（时间/大小取小者）
     *
     * 为什么这样：片头是会被跳过的，只要「片头 + 后 1 分钟」在本地，
     * 点下一集就能秒播且跳过片头后不卡。
     *
     * @param introEndMs 片头结束位置；-1 表示无片头数据
     * @param durationMs 下一集总时长；0 表示未知
     */
    private suspend fun preloadNextEpisode(
        client: OkHttpClient,
        context: Context,
        settings: Settings,
        nextId: String,
        nextUrl: String,
        nextLength: Long,
        introEndMs: Long,
        durationMs: Long,
    ) {
        val target = fileForId(context, nextId)
        if (target.isFile && target.length() > 0) {
            nextComplete = true
            return
        }
        val part = File(target.parentFile, target.name + ".part")

        val total = if (nextLength > 0) nextLength else Long.MAX_VALUE
        val windowMs = maxWindowMs(settings)
        val windowBytes = maxWindowBytes(settings)

        // 配额（从第 0 秒起算）：时间窗口与大小窗口取小者
        val bytesPerMs = if (durationMs > 0 && nextLength > 0) nextLength.toDouble() / durationMs else 0.0
        val quotaByTime = if (windowMs > 0 && bytesPerMs > 0) (windowMs * bytesPerMs).toLong() else Long.MAX_VALUE
        val quotaBySize = if (windowBytes > 0) windowBytes else Long.MAX_VALUE
        var quotaBytes = minOf(quotaByTime, quotaBySize)
        if (quotaBytes == Long.MAX_VALUE) quotaBytes = total  // 两个阈值都没配 → 拉满

        /**
         * 片头优先：片头结束 + 1 分钟 对应的字节位置。
         * 需要码率才能换算；缺 duration 或总长时无法换算，只能退回配额。
         */
        val introFloorBytes: Long? =
            if (introEndMs > 0 && bytesPerMs > 0) {
                ((introEndMs + 60_000L) * bytesPerMs).toLong()
            } else null

        /**
         * 最终目标：
         *  · 有片头下限（introFloor）：
         *      配额 >= 下限 → 用配额（配额更大，继续往下缓冲）
         *      配额 <  下限 → 用下限（突破配额硬缓冲到片头后 1 分钟）
         *  · 无片头下限 → 只用配额
         */
        var wantBytes = if (introFloorBytes != null) maxOf(quotaBytes, introFloorBytes) else quotaBytes
        if (total != Long.MAX_VALUE) wantBytes = wantBytes.coerceAtMost(total)

        var have = if (part.isFile) part.length() else 0L
        if (have < wantBytes) {
            val chunk = 12L * 1024 * 1024
            while (have < wantBytes) {
                val until = minOf(wantBytes, have + chunk)
                downloadInto(client, nextUrl, part, from = have, until = until)
                val after = if (part.isFile) part.length() else 0L
                if (after <= have) break
                have = after
            }
        }
        runCatching { trim(context, settings, protect = part) }
        // 用户口径：下一集【已开始缓冲、点下去能秒播】就亮气泡，不要求全缓存完
        if (have > 0) nextComplete = true
    }

    /** 兼容旧入口：按窗口拉一次。 */
    fun ensureWindow(
        context: Context,
        settings: Settings,
        itemId: String,
        url: String,
        positionMs: Long,
        durationMs: Long,
        knownLength: Long,
        onComplete: (Boolean) -> Unit = {},
    ) {
        pipelineJob = workScope.launch {
            val done = pullWindow(mediaClient, context, settings, itemId, url, positionMs, durationMs, knownLength)
            onComplete(done)
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
    /**
     * 用户口径（2026-11-24）**闪退根因修复**。
     *
     * 日志铁证：
     *   FATAL EXCEPTION: DefaultDispatcher-worker-7
     *   yt1: Response code: 403
     *   Process: xyz.etkn.mobile
     *
     * 原因：这里原来用裸 `DefaultHttpDataSource.Factory().setUserAgent(UA)`，
     * 既没带 App 的 User-Agent 令牌，也没带任何认证头；
     * 而且 403 抛出的 IOException 在协程里没有被捕获 → 直接 FATAL 杀进程。
     *
     * 修法：
     *  ① 用 OkHttpDataSource + 和播放器同一套 client（带同源 X-Emby-Token 拦截器）
     *  ② 整段包 runCatching —— 403/超时/断网一律只记日志，绝不许崩进程
     */
    private suspend fun downloadInto(client: OkHttpClient, url: String, target: File, from: Long, until: Long) {
        runCatching {
            // 复用播放器的 OkHttp client（同源请求自动带 X-Emby-Token + UA）
            val factory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client).setUserAgent(UA)
            val source = factory.createDataSource()
            val spec = DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setPosition(from)
                .apply { if (until > from) setLength(until - from) }
                .build()
            try {
                source.open(spec)
                RandomAccessFile(target, "rw").use { raf ->
                    raf.seek(from)
                    val buffer = ByteArray(256 * 1024)
                    var written = from
                    while (true) {
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
        }.onFailure {
            android.util.Log.w(
                "ETKN-Cache",
                "download failed from=$from until=$until url=${url.take(90)} err=${it.message}",
            )
        }
    }

    // ---------------------------------------------------------------- 流水线（预加载）

    /**
     * 用户口径（2026-11-23）串行流水线：
     * 当前集缓存完成后，才去取下一集直链并为下一集跑同样的窗口缓存。
     * 跑到下一集完成 → nextComplete = true → 气泡亮。
     */
    /**
     * 用户口径（2026-11-24）**流水线重做**。
     *
     * 关键变化（对照用户口述）：
     *  ① 第一环：当前集窗口跟随播放位置推进（不是「拉满整集」才算完成）
     *  ② 当前集「从当前位置到片尾已无可缓冲」→ 立即触发下一集预加载
     *     （不等播完，片尾前就开始）
     *  ③ 下一集预加载量：有片头 → 片头结束+1分钟（配额够就按配额，
     *     配额不够则突破配额硬缓冲到片头后 1 分钟）；无片头 → 按配额
     *  ④ 下一集【已开始缓冲】即视为可秒播 → 气泡亮（不要求全缓存完）
     *
     * @param next 下一集 (itemId, url, introEndMs:-1 无片头, durationMs)
     */
    fun pipeline(
        client: OkHttpClient,
        context: Context,
        settings: Settings,
        currentEpisodeId: String,
        currentUrl: String,
        currentPositionMs: Long,
        currentDurationMs: Long,
        currentLength: Long,
        next: NextEpisode?,
    ): Job? {
        val key = stableKey(currentUrl)
        if (writing.containsKey(key)) return writing[key]

        val job = workScope.launch {
            /**
             * 用户口径（2026-11-24）：**任何异常都不许逃出这个协程**。
             * 上一版下载 403 抛到这里 → FATAL EXCEPTION 杀进程。
             * 现在兜一层 runCatching，最坏情况是这一轮缓存失败，播放不受影响。
             */
            try {
                // 第一环：当前集窗口（跟随播放位置）
                val currentAtEnd = pullWindow(
                    client, context, settings, currentEpisodeId, currentUrl,
                    currentPositionMs, currentDurationMs, currentLength,
                )
                currentComplete = currentAtEnd
                // 还没到片尾 → 本轮结束，等下次 tick 继续推窗口
                if (!currentAtEnd) return@launch

                if (!settings.preloadNext) return@launch
                val n = next ?: return@launch

                // 第二环：当前集已到片尾 → 立刻预加载下一集
                nextEpisodeId = n.itemId
                nextComplete = false
                val nextLength = probeLength(n.url)
                preloadNextEpisode(
                    client, context, settings, n.itemId, n.url,
                    nextLength, n.introEndMs, n.durationMs,
                )
            } catch (t: Throwable) {
                android.util.Log.w("ETKN-Cache", "pipeline failed: ${t.message}")
            } finally {
                writing.remove(key)
            }
        }
        writing[key] = job
        return job
    }

    /** 下一集预加载所需信息。 */
    data class NextEpisode(
        val itemId: String,
        val url: String,
        /** 片头结束位置（毫秒）；-1 = 这一集没有片头数据。 */
        val introEndMs: Long,
        /** 这一集总时长（毫秒）；0 = 未知。 */
        val durationMs: Long,
    )

    /**
     * 探资源总字节数：用 HTTP HEAD 读 Content-Length。
     * 探不到返回 -1（调用方按「位置 + 窗口」估算）。
     * 注：DefaultHttpDataSource 不暴露 contentLength()，所以直接用 OkHttp。
     */
    private fun probeLength(url: String): Long = runCatching {
        val request = okhttp3.Request.Builder().url(url).head().build()
        okhttp3.OkHttpClient().newCall(request).execute().use { response ->
            response.header("Content-Length")?.toLongOrNull() ?: -1L
        }
    }.getOrDefault(-1L)

    // ---------------------------------------------------------------- 兼容旧调用

    /** 兼容：单集预加载（流水线内部用 pipeline，这里给外部零星调用保留）。 */
    fun preload(context: Context, settings: Settings, episodeId: String, url: String) {
        if (!settings.preloadNext) return
        val key = stableKey(url)
        if (writing.containsKey(key)) return
        writing[key] = workScope.launch {
            try {
                pullWindow(mediaClient, context, settings, episodeId, url, 0L, 0L, probeLength(url))
                nextEpisodeId = episodeId
                nextComplete = true
                trim(context, settings)
            } finally {
                writing.remove(key)
            }
        }
    }

    /** 播完一集按设置清掉该集缓存（按 itemId 定位文件）。 */
    fun afterEpisodeFinished(context: Context, settings: Settings, itemId: String?) {
        if (!settings.cacheClearOnFinish) return
        if (itemId.isNullOrEmpty()) return
        workScope.launch {
            runCatching { fileForId(context, itemId).delete() }
            runCatching { partFileById(context, itemId).delete() }
        }
    }

    /**
     * 用户口径（2026-11-23）**磁盘上限之前根本没生效**，这里是根因修复。
     *
     * 实测症状：设置里写 5GB，实际占用 12.7GB。
     * 原因：trim() 只在 preload() 里被调用，而 preload() 是**死代码**
     * （全工程 grep 零调用）。主流程 pipeline() / pullWindow() 完全不检查上限，
     * 所以缓存只增不减，settings.cacheDiskGb 形同摆设。
     *
     * 现在的做法：
     *  · 每次 pullWindow 成功写盘之后都调 trim()
     *  · trim 内部先算总量，超上限就按 lastModified 从旧到新删
     *  · 保护正在写的文件（.part）和本次刚写的文件，不删自己
     */
    fun trim(context: Context, settings: Settings, protect: File? = null) {
        val limit = maxDiskBytes(settings)
        if (limit <= 0) return
        val files = dir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        for (file in files) {
            if (total <= limit) break
            // 不删正在写的、不删本次刚写的
            if (protect != null && file.absolutePath == protect.absolutePath) continue
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    /**
     * 用户口径（2026-11-23）：开机/进播放器时也主动清一次。
     * 老版本积累的超额缓存（12GB+）不会自己消失，得先收一次口。
     */
    fun trimNow(context: Context, settings: Settings) {
        workScope.launch { runCatching { trim(context, settings) } }
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
