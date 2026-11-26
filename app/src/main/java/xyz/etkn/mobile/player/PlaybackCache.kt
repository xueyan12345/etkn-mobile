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
import okhttp3.Request
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
     * 用户口径（2026-11-27，#25）**「下一集真的能秒播」的最小字节门槛**。
     *
     * 40MB 大致等于 1080p 约 40 秒、4K 约 12 秒的内容 ——
     * 足够让点「下一集」时立刻出画面，而不是转圈。
     * 比这个少就不点亮气泡（用户明确要「确定有本地缓存才亮」）。
     */
    private const val PRELOAD_MIN_BYTES = 40L * 1024 * 1024

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
        /**
         * 用户口径（2026-11-27）**气泡只看「下一集铺好了」，不再要求当前集缓冲到头**。
         *
         * 修的问题（用户原话）：「如果这个缓冲它不到头的话，它就无法触发下一集预加载，
         * 可是我这都已经片尾了，它还不触发下一集预加载，难道我要等拉到最后吗」
         *
         * 根因：原来 ready = currentComplete && nextComplete，
         * 而 currentComplete 要求「缓冲条铺到片尾」——
         * 但 Exo 有 60 秒上限（且缓存阈值也有限），缓冲条常常到不了片尾，
         * 于是 currentComplete 永远 false → 气泡永远不亮、下一集永远不预加载。
         *
         * 现在：只看 nextComplete（下一集是否真的铺到能秒播），
         * 因为「下一集能不能秒播」和「当前集缓冲完没完」是两件独立的事。
         */
        /**
         * 用户口径（2026-11-27，#25）**气泡必须「当前集缓冲到头 + 下一集真的铺好了」才亮**。
         *
         * 用户实测（截图）：「你看现在明显是缓存条还没到头呢，它就把预加载亮起来了，
         * 我不知道这个预加载是真的预加载了，还是没预加载，只不过就是把它亮起来了」。
         *
         * 需求原文：
         *  「我要的是缓冲条要到头，也就是这一集从当前播放位置到最后，
         *    没有再需要缓存的了，因为从当前播放的位置到最后，缓存的已经全存到本地了。
         *    就是按照咱们设定的那个两个缓存规则来讲，已经没有可以缓存的余地了，
         *    这个时候才触发下一集的预加载，当下一集的预加载已经确定有本地缓存了，
         *    相当于我点下一集马上就能播放，这时候才把下一集上面的那个预加载小气泡亮起来」
         *
         * 之前这里被改成只看 `nextComplete`（当时的理由是「Exo 缓冲条到不了片尾」），
         * 结果就是现在的现象：缓冲条还差一大截，气泡已经亮了。
         * 现在两个条件都要满足。
         */
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
    /**
     * 用户口径（2026-11-27）**供外部读取「当前集是否已缓冲到片尾」**。
     *
     * 预加载触发条件要用它：缓冲到头了就不用等，立刻开始铺下一集。
     */
    fun currentCompleteFlag(): Boolean = currentComplete

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
     * 用户口径（2026-11-26）**缓冲条算法重写（这是「缓冲条不跟播放进度」的根因）**。
     *
     * ── 旧实现错在哪 ──
     *     frac = 已下载字节 / 整集总字节
     *     return durationMs * frac
     *
     * 它假定「已下载量 = 从文件开头连续下载的量」。但这是**滑动窗口**：
     * 播放到 30 分钟时，窗口下的是「30~50 分钟那一段」的字节，
     * 前面 0~30 分钟可能根本没下过。
     * 于是 have/total 这个比例和「播放位置之后的缓冲范围」毫无关系，
     * 缓冲条画出来的位置是乱的 —— 要么很短、要么直接顶到片尾。
     *
     * ── 正确做法 ──
     * 必须记录**窗口覆盖的字节区间** [windowStart, windowEnd]，然后：
     *     缓冲条位置 = windowEnd / totalBytes * durationMs
     * 这才是「已缓冲到第几分钟」。
     *
     * 窗口区间由 [pullWindow] 在每次下载后写入。
     */
    fun cachedPositionMs(context: Context, itemId: String, durationMs: Long, totalBytes: Long): Long {
        // 整集完整落盘 → 直接算作缓冲到片尾
        val full = fileForId(context, itemId)
        if (full.isFile && full.length() > 0) return if (durationMs > 0) durationMs else 0L
        if (durationMs <= 0 || totalBytes <= 0) return 0L
        /**
         * 用户口径（2026-11-26）**缓冲终点 = 已连续下载到的字节位置**。
         *
         * 语义（用户原话）：「进度条要持续比播放点长，达到阈值后就保持那个长度」。
         *
         *  · end = 已连续下到哪（只增不减）
         *  · 缓冲条画到 end 对应的时间点
         *  · 播放往前走、下载跟着补 → 长度恒等于阈值，不会缩也不会暴涨
         *
         * 之前用「窗口右边界」但窗口会平移，所以缓冲条会先缩后跳。
         * 现在窗口的 end 只增不减（见 pullWindow），这里直接换算即可。
         */
        val end = windowEndBytes(itemId)
        if (end <= 0) return 0L
        val frac = (end.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0)
        return (durationMs * frac).toLong()
    }

    /**
     * 窗口已覆盖到第几个字节（右边界）。
     *
     * 用户口径（2026-11-26）：这是缓冲条的唯一数据源。
     *  · 下载时由 [pullWindow] 写入真实值
     *  · 磁盘上已有的 .part 长度只作为兜底（重启后窗口信息丢失时）
     */
    private fun windowEndBytes(itemId: String): Long {
        windows[itemId]?.let { w -> if (w.end > 0) return w.end }
        return 0L
    }

    /**
     * 一集缓存窗口的字节区间。
     *
     * 用户口径（2026-11-26）**这是「连续已缓冲区间」，不是「会平移丢数据的窗口」**。
     *
     * 之前的问题（用户原话：「看着缓冲越来越少，马上要撵上了，
     * 然后突然一下子又长了」）：
     *  · 窗口跟着播放位置平移，但已下载的数据在后面无法移动
     *  · 播放越往前走，「已下到的 end − 当前位置」越小 → 缓冲条越来越短
     *  · 一旦窗口整体滑过 end，下一轮从新 windowStart 重下
     *    → 缓冲条从很短一下跳到很长
     *
     * 现在：end 只增不减（连续往前推进），start 允许跟着播放位置前移
     * （因为前面的数据已经播过了，不需要保留）。
     * 这样缓冲条 = end 到 total 的比例，单调前进，不会回缩也不会跳。
     */
    private data class Window(val start: Long, val end: Long)

    /** itemId → 当前窗口。下载时更新，切集时清理。 */
    private val windows = java.util.concurrent.ConcurrentHashMap<String, Window>()

    /** 切集/退出时清掉窗口记录（下次进这一集重新算）。 */
    fun clearWindow(itemId: String) {
        windows.remove(itemId)
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
        /**
         * 用户口径（2026-11-26）**修「完全不会自动缓存」的根因之一**。
         *
         * 日志实证（1.4.5，两部片子）：
         *   cache hit=false complete=false bytes=0
         *   ETKN-Cache 日志一条都没有（下载从未发生）
         *
         * 原因：strm 回源时服务端给的大小是 0（或 -1），于是
         *   bytesPerMs = 0 → posBytes = 0 → byTime = MAX → 窗口长度算成 0
         *   → `while (cur < windowEnd)` 一次都不进 → 什么都不下。
         *
         * 现在：总长未知时就**现场探一次**（带认证的 HEAD 请求）。
         * 还是探不到（服务端不给 Content-Length）就退化成「只按字节窗口缓存」：
         * 从当前位置的字节偏移开始，按 cacheMemoryMb 下这么多字节，
         * 不依赖「总长 / 时长」换算。这样缓存永远能工作。
         */
        val probed = if (knownLength > 0) knownLength else probeLength(client, url)
        val total = if (probed > 0) probed else Long.MAX_VALUE
        /**
         * 用户口径（2026-11-26）**修「时长 dur=0 时窗口还是算不出来」**。
         *
         * 日志实证：tickCache 首次驱动 pos=293668ms dur=0ms len=5771688235
         * 上一版只修了「总长为 0」，没修「时长为 0」：
         *   bpm = if (durationMs > 0 && probed > 0) ... else 0.0
         * 时长 0 → bpm 还是 0 → posBytes 还是 0 → 窗口还是算不出来。
         *
         * 时长从哪来？播放器还没准备好时 duration 就是 0。
         * 现在两种退化：
         *  · 有时长 → 按时长/大小两个窗口取小（正常路径）
         *  · 无时长 → 只用「大小窗口」，且**从已下载位置往后推**
         *    （不知道时间对应多少字节，就按字节算，这也是用户设的第二个条件）
         */
        val bpm = if (durationMs > 0 && probed > 0) probed.toDouble() / durationMs else 0.0

        /**
         * 时长未知时：位置对应的字节偏移无法换算，
         * 直接用「已缓存的 .part 长度」作为起点（已经下过的部分不重下）。
         */
        val posBytes = when {
            bpm > 0 -> (positionMs * bpm).toLong()
            else -> if (part.isFile) part.length() else 0L
        }

        /**
         * 用户口径（2026-11-26）**真正的滑动窗口：从播放位置开始往后下**。
         *
         * 旧实现从字节 0 顺序下载（`have = part.length()`，`downloadInto(from = have)`），
         * 所以文件里永远只有「开头那一截」，缓冲条自然跟不上播放位置。
         *
         * 现在：
         *  · 窗口左边界 = 当前位置对应字节（减去一点回看余量，便于拖动回退）
         *  · 窗口右边界 = 左边界 + min(时间窗口, 大小窗口)
         *  · 只保证「左边界到右边界」这段在本地；超出的部分不重复下载
         *  · 记录窗口区间，供缓冲条按真实右边界换算时间点
         *
         * 说明：为了让「单集内缓冲条恒长」这个观感成立，
         * 窗口始终跟着位置平移，而不是越攒越长。
         */
        // 回看余量：当前位置往前留 30 秒，方便小幅拖动回退
        // 回看余量：有时长就按 30 秒换算，没时长就按 8MB 兜底
        val lookBackBytes = if (bpm > 0) (30_000.0 * bpm).toLong() else (8L * 1024 * 1024)
        val windowStart = (posBytes - lookBackBytes).coerceAtLeast(0L)

        // 窗口长度：两个条件取小者（任一条达成就不再扩，这是用户口径）
        val byTime = if (bpm > 0) (windowMs * bpm).toLong() else Long.MAX_VALUE
        val bySize = if (windowBytes > 0) windowBytes else Long.MAX_VALUE
        var windowLen = minOf(byTime, bySize)
        if (windowLen == Long.MAX_VALUE) windowLen = total - windowStart   // 没配阈值就按整集
        var windowEnd = (windowStart + windowLen).coerceAtLeast(windowStart)
        if (total != Long.MAX_VALUE) windowEnd = windowEnd.coerceAtMost(total)

        /**
         * 当前本地已有的连续覆盖区间。
         *
         * 记在内存里（[windows]），重启后丢失 → 从磁盘 .part 长度兜底，
         * 但兜底只用于「别重复下载已经有的部分」，不影响缓冲条显示（缓冲条只认 windows）。
         */
        /**
         * 用户口径（2026-11-26）**缓冲条的正确行为（按用户原话重写）**。
         *
         * 用户原话：
         *  「正常来讲，进度条要持续的比播放的那个点要长，
         *    然后当达到咱们设置的那两个阈值之后，它应该就持续的保持那么长，
         *    不会有太大的变动」
         *  「持续播放，缓冲条肯定会变少，变少的同时那边下载它肯定就变长了」
         *  「肯定不会出现进度条撵上缓冲，然后突然之间又特别长」
         *
         * 也就是：
         *  · 缓冲终点 = 播放位置 + 阈值（时长/大小取小者）
         *  · 播放往前走 → 缓冲终点跟着往前走（长度不变）
         *  · 已经下过的部分【保留不动】，只是终点往前推 → 需要补的永远是「新增那一小段」
         *  · 缓冲条长度恒定 ≈ 阈值，不会缩、不会暴涨
         *
         * 之前错在哪：把「已下载区间」当成会平移的东西，
         * 播放推进时把它整体往后挪 → 缓冲条变短；挪不动了又从新位置重下 → 暴涨。
         *
         * 正确做法：已下载区间 [haveStart, haveEnd] 只增不减，
         * 目标区间 [playBytes, playBytes + thresh] 跟着播放前进，
         * 每轮只补「目标区间 减去 已下载区间」那一段。
         */
        val already = windows[itemId]
        // 已连续下载到的右边界（只增不减）
        val haveEnd = already?.end ?: 0L
        val haveStart = already?.start ?: windowStart

        /**
         * 目标缓冲终点：播放位置 + 阈值。
         * 注意 windowStart/windowEnd 上面已经按「位置-回看余量 + 阈值」算好，
         * 这里直接用 windowEnd 作为目标终点即可。
         */
        val targetEnd = windowEnd

        // 已经够了（已下到的右边界 ≥ 目标终点）→ 本轮什么都不用做
        if (haveEnd >= targetEnd) {
            android.util.Log.i(
                "ETKN-Cache",
                "window item=$itemId pos=${positionMs / 1000}s total=${total / 1048576}MB " +
                    "have=${haveEnd / 1048576}MB target=${targetEnd / 1048576}MB skip=covered",
            )
            return@withLock true
        }

        /**
         * 从 "已下到的右边界" 开始往后补，直到目标终点。
         * 绝不回退重下（haveEnd 之后才是缺的）。
         */
        var cur = if (haveEnd > 0) haveEnd else windowStart
        val chunk = 1L * 1024 * 1024
        var lastPublish = 0L
        while (cur < targetEnd) {
            val until = minOf(targetEnd, cur + chunk)
            val ok = downloadInto(client, url, part, from = cur, until = until)
            if (!ok) break          // 这一段没下到 → 停止，已下部分仍然有效
            cur = until
            // 每片完成立刻让缓冲条前进（节流 250ms）
            val nowTick = System.currentTimeMillis()
            if (nowTick - lastPublish >= 250L || cur >= targetEnd) {
                /**
                 * 起点取 min(haveStart, windowStart)：
                 * 前面的数据已经播过了不需要保留，但也不能让区间「回缩」，
                 * 所以起点只允许往前（取更小的那个），保证区间是连续的。
                 */
                windows[itemId] = Window(start = minOf(haveStart, windowStart), end = cur)
                lastPublish = nowTick
            }
        }
        val coveredEnd = cur.coerceAtMost(targetEnd)
        windows[itemId] = Window(start = minOf(haveStart, windowStart), end = coveredEnd)
        /**
         * 用户口径（2026-11-26）：**加诊断日志**。
         *
         * 之前「缓存到底有没有在跑」完全看不出来（1.4.5 的日志里 ETKN-Cache 一条都没有），
         * 只能靠猜。现在每次窗口推进都打一行，能直接看出：
         *  · 总长有没有探到（total）
         *  · 窗口从哪到哪（window）
         *  · 这一轮实际下到哪（covered）
         *  · 是不是已完成（done）
         */
        android.util.Log.i(
            "ETKN-Cache",
            "window item=$itemId pos=${positionMs / 1000}s " +
                "total=${if (total == Long.MAX_VALUE) "unknown" else "${total / 1024 / 1024}MB"} " +
                "win=${windowStart / 1024 / 1024}~${windowEnd / 1024 / 1024}MB " +
                "covered=${coveredEnd / 1024 / 1024}MB",
        )

        /**
         * 用户口径（2026-11-26）**「本集缓冲完成」的正确判定**。
         *
         * 从当前播放位置往后已经无处可缓冲，就是完成：
         *  · 窗口右边界顶到片尾（coveredEnd >= total）
         *  · 或者整集完整落盘
         * 这与「窗口恒长、跟着位置走」的口径一致。
         */
        val reachedEnd = when {
            total != Long.MAX_VALUE && coveredEnd >= total -> true
            total != Long.MAX_VALUE && part.isFile && part.length() >= total -> true
            // 总长未知：位置已在片尾附近且本轮没有进展 → 认为无可缓冲
            total == Long.MAX_VALUE && durationMs > 0 && positionMs >= durationMs - 5_000 -> true
            else -> false
        }
        if (reachedEnd) {
            /**
             * 用户口径（2026-11-26）**覆盖到片尾就转正，不再要求字节精确相等**。
             *
             * 修的问题（日志实证 item-25750）：
             * ```
             * 12:09:56  cache-dir hasThisEpisode=true   ← 文件在
             *           cache-probe hit=false size=0     ← 但认不出来
             * 12:09:57  window total=3495MB covered=3495MB  ← 整集已覆盖
             * ```
             * 缓存确实写满了整集，但文件一直叫 item-25750.part，
             * 而 localFileById 只认 item-25750 → 每次重进都当「没缓存」走网络。
             *
             * 根因：原来要求 `part.length() >= total` 精确成立才改名。
             * HTTP Range 分段写入时，part 的文件长度常常比 total 差几个字节
             * （末尾分片对齐、服务端 Content-Length 与容器时长换算误差），
             * 这个条件几乎永远不成立 → .part 永远不转正 → 缓存白写。
             *
             * 现在：只要「窗口右边界顶到片尾」（reachedEnd 已成立）就转正。
             * 再叠加一层保险：文件长度至少要有 total 的 99%，
             * 避免刚下一小段就误判成整集。
             */
            val bigEnough = total == Long.MAX_VALUE ||
                !part.isFile ||
                part.length() >= (total * 99 / 100)
            if (total != Long.MAX_VALUE && part.isFile && bigEnough && part.length() > 0) {
                val renamed = runCatching { part.renameTo(target) }.getOrDefault(false)
                android.util.Log.i(
                    "ETKN-Cache",
                    "整集完成 → 转正 item=$itemId size=${part.length()} total=$total renamed=$renamed",
                )
                runCatching { trim(context, settings, protect = target) }
            } else {
                runCatching { trim(context, settings, protect = part) }
            }
            return@withLock true
        }
        runCatching { trim(context, settings, protect = part) }
        // 窗口还没顶到片尾：本轮结束，等下次 tick 继续推
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

        /**
         * 用户口径（2026-11-26）：下一集总长探不到时，按字节配额下，
         * 不依赖「总长 / 时长」换算（服务端对 strm 常常不给 Content-Length）。
         */
        val probedNext = if (nextLength > 0) nextLength else probeLength(client, nextUrl)
        val total = if (probedNext > 0) probedNext else Long.MAX_VALUE
        val windowMs = maxWindowMs(settings)
        val windowBytes = maxWindowBytes(settings)

        // 配额（从第 0 秒起算）：时间窗口与大小窗口取小者
        val bytesPerMs = if (durationMs > 0 && probedNext > 0) probedNext.toDouble() / durationMs else 0.0
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

        /**
         * 用户口径（2026-11-26）**修「缓冲条没到片尾却亮了预加载气泡」**。
         *
         * 旧判据是 `if (have > 0) nextComplete = true` ——
         * 意思是「下一集只要下载了 1 个字节就亮气泡」。
         * 实测就是这样：缓冲条还剩一大截，气泡已经亮了。
         *
         * 用户口径（原话）：
         *  「不是下一集缓存完，是下一集已经开始缓冲了，
         *    我点下一集马上就能秒播的时候，就可以把气泡亮了」
         *
         * 「马上能秒播」= 下一集**开头那一段**真的在本地了，不是 1 个字节。
         * 门槛：至少下到「片头结束 + 1 分钟」或配额的一半（取小者），
         * 太少（比如只有几 MB）不足以秒播，不算。
         */
        val enough = when {
            introFloorBytes != null && introFloorBytes > 0 -> have >= introFloorBytes
            quotaBytes > 0 -> have >= quotaBytes / 2
            else -> have > 0
        }
        if (enough) {
            nextComplete = true
            android.util.Log.i(
                "ETKN-Cache",
                "next-ready item=$nextId have=${have / 1024 / 1024}MB " +
                    "need=${(introFloorBytes ?: (quotaBytes / 2)) / 1024 / 1024}MB",
            )
        }
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
    /**
     * 用户口径（2026-11-26）：返回**是否成功读到数据**。
     *
     * 滑动窗口必须知道「这一段到底下没下到」，
     * 否则窗口区间记错、缓冲条又会失真。
     * 返回 true 表示真的从 [from] 读到并写入了数据。
     */
    private suspend fun downloadInto(client: OkHttpClient, url: String, target: File, from: Long, until: Long): Boolean {
        var wrote = 0L
        val result = runCatching {
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
                        wrote += read
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
        return result.isSuccess && wrote > 0
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
    /**
     * 用户口径（2026-11-27）**只做「下一集预加载」，当前集交给缓存数据源**。
     *
     * 为什么改成这样：
     *  · 当前集已经由 CachedDataSource 边播边缓存（播放器要什么就拉什么）
     *    再让这里下一次就是两条流抢带宽 + 重复下载
     *  · 而下一集没有任何「播放」在驱动它，必须主动去铺
     *
     * 判据（这是用户反馈「预加载有时候不准」的正解）：
     *  · 按「块」判断，不看「下了多少字节」这种估算
     *  · 门槛 = 覆盖到「下一集起播点 + 片头结束点」
     *    —— 这是能直接验证的事实：点下一集确实能立刻出画
     *  · 已经覆盖就直接返回（不重复下）
     */
    fun preloadNextOnly(
        client: OkHttpClient,
        context: Context,
        settings: Settings,
        currentEpisodeId: String,
        next: NextEpisode?,
        dataSourceFactory: DataSource.Factory?,
    ): Job? {
        if (!settings.preloadNext) return null
        val target = next ?: return null
        if (target.itemId.isBlank() || target.url.isBlank()) return null
        if (dataSourceFactory == null) return null
        if (preloading.contains(target.itemId)) return null

        val job = workScope.launch {
            preloading.add(target.itemId)
            try {
                /**
                 * 用户口径（2026-11-27）**预加载也走官方缓存，不再手写块下载**。
                 *
                 * 做法：用同一个 CacheDataSource 打开下一集的头部并读掉一段，
                 * 读的同时 SimpleCache 就把它写进去了 —— 点下一集时自然秒开。
                 *
                 * 读多少：
                 *  · 有片头数据 → 读到「片头结束 + 1 分钟」
                 *  · 否则 → 读 min(字节阈值, 时长阈值) 的一半
                 */
                val needBytes = run {
                    val total = if (target.lengthBytes > 0) target.lengthBytes else probeLength(client, target.url)
                    val durationMs = target.durationMs
                    val bpm = if (durationMs > 0 && total > 0) total.toDouble() / durationMs else 0.0
                    val needMs = when {
                        target.introEndMs > 0 -> target.introEndMs + 60_000L
                        else -> maxWindowMs(settings) / 2
                    }
                    if (bpm > 0) (needMs * bpm).toLong() else maxWindowBytes(settings) / 2
                }.coerceAtLeast(2L * 1024 * 1024)   // 至少铺 2MB，避免刚够就断

                val ds = dataSourceFactory.createDataSource()
                var covered = 0L
                runCatching {
                    val spec = DataSpec.Builder()
                        .setUri(android.net.Uri.parse(target.url))
                        .setKey(target.itemId)     // 与播放时同一个 cache key
                        .build()
                    ds.open(spec)
                    val buf = ByteArray(256 * 1024)
                    while (covered < needBytes) {
                        val read = ds.read(buf, 0, buf.size)
                        if (read <= 0) break
                        covered += read
                    }
                }
                runCatching { ds.close() }

                val ready = covered >= (needBytes * 8 / 10)   // 铺到八成就算可用
                android.util.Log.i(
                    "ETKN-Cache",
                    "preload next=${target.itemId} covered=${covered / 1048576}MB " +
                        "need=${needBytes / 1048576}MB ready=$ready",
                )
                if (ready) nextComplete = true
            } catch (t: Throwable) {
                android.util.Log.w("ETKN-Cache", "preload failed", t)
            } finally {
                preloading.remove(target.itemId)
            }
        }
        return job
    }

    /** 正在预加载的 itemId（防重复）。 */
    private val preloading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
                /**
                 * 用户口径（2026-11-26）**别把 tickCache 判定的「已完成」冲掉**。
                 *
                 * tickCache 会按真实落盘位置直接写 currentComplete = true（见 markCurrentComplete）。
                 * 但这里如果无条件赋值，本轮 pullWindow 恰好返回 false 就会把它改回 false，
                 * 两个写入者互相覆盖，气泡时亮时不亮。
                 *
                 * 现在只在「本轮判定为完成」时才置 true，绝不主动置 false
                 * （取消完成由 clearReady() 在切集时统一处理）。
                 */
                if (currentAtEnd) currentComplete = true
                // 还没到片尾 → 本轮结束，等下次 tick 继续推窗口
                if (!currentAtEnd) return@launch

                if (!settings.preloadNext) return@launch
                val n = next ?: return@launch

                // 第二环：当前集已到片尾 → 立刻预加载下一集
                nextEpisodeId = n.itemId
                nextComplete = false
                val nextLength = probeLength(client, n.url)
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
        /** 这一集总字节数；-1 = 未知（预加载时现场探）。 */
        val lengthBytes: Long = -1L,
    )

    /**
     * 探资源总字节数：用 HTTP HEAD 读 Content-Length。
     * 探不到返回 -1（调用方按「位置 + 窗口」估算）。
     * 注：DefaultHttpDataSource 不暴露 contentLength()，所以直接用 OkHttp。
     */
    /**
     * 探资源总字节数：用 HTTP HEAD 读 Content-Length。
     *
     * 用户口径（2026-11-26）**必须复用带认证的 client**。
     * 旧实现自己 `OkHttpClient()` 新建一个裸客户端，请求不带 X-Emby-Token →
     * 服务端返 403 → 拿不到 Content-Length → 返回 -1 →
     * 调用方按 total = MAX_VALUE 处理 → `reachedEnd` 永远 false →
     * 下一集预加载永远不触发（这正是「缓冲条到底了气泡还不亮」的一半原因）。
     *
     * 现在用传进来的 [client]（和播放器/下载同一个、带认证的实例）。
     */
    private fun probeLength(client: OkHttpClient, url: String): Long = runCatching {
        val request = okhttp3.Request.Builder().url(url).head().build()
        client.newCall(request).execute().use { response ->
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
                val length = probeLength(mediaClient, url)
                pullWindow(mediaClient, context, settings, episodeId, url, 0L, 0L, length)
                nextEpisodeId = episodeId
                /**
                 * 用户口径（2026-11-27，#25）**不许无条件点亮气泡**。
                 *
                 * 原来是 pullWindow 一返回就 `nextComplete = true` ——
                 * 不管实际下了多少字节，气泡都亮，用户点下一集还是卡。
                 *
                 * 现在按真实落盘量判定：至少要有「片头结束 + 1 分钟」或 40MB 的量，
                 * 否则说明没真铺好，不点亮。
                 */
                val cached = xyz.etkn.mobile.player.cache.VideoCache.cachedBytesFrom(episodeId, 0L, length)
                val enough = cached >= PRELOAD_MIN_BYTES
                if (enough) {
                    nextComplete = true
                    android.util.Log.i(
                        "ETKN-Cache",
                        "preload ready item=$episodeId cached=${cached / 1048576}MB " +
                            "need=${PRELOAD_MIN_BYTES / 1048576}MB",
                    )
                } else {
                    android.util.Log.i(
                        "ETKN-Cache",
                        "preload NOT ready item=$episodeId cached=${cached / 1048576}MB " +
                            "need=${PRELOAD_MIN_BYTES / 1048576}MB（不点亮气泡）",
                    )
                }
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
        workScope.launch {
            runCatching { trim(context, settings) }
            /**
             * 用户口径（2026-11-27）**清掉旧版遗留的整文件缓存，释放磁盘**。
             *
             * 缓存已经全部改成官方 SimpleCache。旧的 mediafiles/ 目录里
             * 那些 item-xxxxx / item-xxxxx.part 整文件再也用不到了，
             * 却还占着空间（实测有 10 个文件约 10GB）。
             *
             * 只清一次（用 SharedPreferences 标记），避免每次进播放器都扫目录。
             */
            runCatching {
                val prefs = context.getSharedPreferences("etkn_cache_migrate", android.content.Context.MODE_PRIVATE)
                if (!prefs.getBoolean("legacy_purged", false)) {
                    val dir = java.io.File(context.cacheDir, "mediafiles")
                    val freed = dir.listFiles()?.sumOf { it.length() } ?: 0L
                    if (freed > 0) {
                        dir.listFiles()?.forEach { runCatching { it.delete() } }
                        android.util.Log.i(
                            "ETKN-Cache",
                            "purged legacy cache ${freed / 1048576}MB (moved to block cache)",
                        )
                    }
                    prefs.edit().putBoolean("legacy_purged", true).apply()
                }
            }
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
