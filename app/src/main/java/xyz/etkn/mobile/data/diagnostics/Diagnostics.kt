package xyz.etkn.mobile.data.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import xyz.etkn.mobile.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Error-focused local log, exported by day and time range from 我的 → 导出日志.
 *
 * A `logcat -f` child keeps only this UID's errors (`*:E`, which includes crash backtraces) plus
 * the app's own `ETKN*` lines, stamped with the year so a day can be cut out. Files rotate at
 * 256 KB × 8 and anything older than [KEEP_DAYS] is deleted at start-up. Java crashes are also
 * appended synchronously in the same line format. Nothing leaves the device unless exported,
 * and the export strips tokens and URL query strings.
 */
object Diagnostics {
    private const val TAG = "ETKN"
    private const val LOG = "app.log"
    private const val CRASH = "crash.log"
    private const val FILES = 8
    private const val KEEP_DAYS = 7L

    /**
     * 用户口径（2026-11-26）**日志按 PID 抓，不再维护 tag 白名单**。
     *
     * 这个坑犯了两次：
     *  · 2026-11-23 漏了 ETKN-Rotate → 平板旋转的日志一条没进文件
     *  · 2026-11-26 漏了 ETKN-Cache → 缓存相关 5 条日志一条没进文件，
     *    排查「本地缓存不生效」时完全没有证据，只能靠猜
     *
     * 用户原话：「你以后加什么功能啥的，都要把这个功能相关日志放在这个往日志里写的
     * 功能里边，要不以后有啥问题，我导出日志你也看不见」
     *
     * 所以彻底改掉白名单机制：用 `--pid=<本进程>` 抓**本进程产生的所有日志**。
     * 好处：
     *  · 以后任何新模块、新 tag、任何级别（info/warn/error）都自动进文件，不可能再漏
     *  · 不再需要「加日志时记得同步改白名单」这种靠人记的动作
     *  · 本进程的崩溃堆栈、库报错也一并在里面，排查更完整
     * 代价：文件写入量比只抓 ETKN*:I 略大（但原来是 *:E 抓全系统错误，总量并不小），
     * 轮转仍是 256KB × 8，够用。
     */
    private val FILTER = listOf("--pid=${Process.myPid()}")

    /**
     * 兜底用的 tag 白名单：仅当 PID 过滤不可用时才用（例如某些定制 ROM 的 logcat
     * 不支持 --pid）。正常情况下走上面的 FILTER。
     *
     * 新增 ETKN 标签时可以往这里补，但**不补也不会漏**（主路径按 PID 抓）。
     */
    private val FALLBACK_FILTER = listOf(
        "ETKN", "ETKN-Cache", "ETKN-Detail", "ETKN-Disc", "ETKN-Mpv",
        "ETKN-Play", "ETKN-Player", "ETKN-Rotate",
    ).map { "$it:I" } + "*:E"

    fun start(context: Context) {
        val dir = directory(context)
        File(context.filesDir, "diagnostics").deleteRecursively() // 0.5.3's unfiltered mirror
        cleanup(dir)
        val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val base = listOf("logcat", "-v", "threadtime,year", "-T", since, "-f", File(dir, LOG).path, "-r", "256", "-n", FILES.toString())
        /**
         * 先按 PID 抓（能收全本进程所有日志）；起不来再退回 tag 白名单。
         * 两者的区别只在「漏不漏日志」，不影响功能。
         */
        val started = runCatching { ProcessBuilder(base + FILTER).redirectErrorStream(true).start() }
            .onFailure { Log.w(TAG, "log recorder (pid filter) unavailable", it) }
            .getOrNull()
        if (started == null) {
            runCatching { ProcessBuilder(base + FALLBACK_FILTER).redirectErrorStream(true).start() }
                .onFailure { Log.w(TAG, "log recorder unavailable", it) }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val head = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} ${Process.myPid()} ${Process.myTid()} F JavaCrash: "
                File(dir, CRASH).appendText(Log.getStackTraceString(error).lineSequence().filter { it.isNotBlank() }
                    .joinToString("\n", postfix = "\n") { head + "[${thread.name}] " + it })
            }
            previous?.uncaughtException(thread, error)
        }
        Log.i(TAG, "start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
        // Native crashes / ANRs of earlier runs (no Java handler sees them): from Android's exit records.
        Thread({ ExitRecords.record(context.applicationContext, File(dir, CRASH)) }, "etkn-exit-records").start()
    }

    /**
     * The last lines a dead process of this app left in the system log buffer, in the recorder's
     * format and filter: the recorder dies with the process and usually misses exactly these.
     */
    internal fun processLog(pid: Int): List<String> = runCatching {
        val process = ProcessBuilder(listOf("logcat", "-d", "-v", "threadtime,year", "--pid=$pid", "-t", "300") + FILTER)
            .redirectErrorStream(true).start()
        val lines = process.inputStream.bufferedReader().use { it.readLines() }
        process.waitFor(5, TimeUnit.SECONDS)
        lines.filter { DAY.matches(it.take(10)) }
    }.getOrDefault(emptyList())

    /** Days that have log lines, newest first. */
    fun days(context: Context): List<LocalDate> {
        val seen = HashSet<String>()
        for (file in files(directory(context))) file.forEachLine { line -> DAY.find(line.take(10))?.let { seen += it.value } }
        return seen.sortedDescending().mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    fun fileName(day: LocalDate): String = "etkn-mobile-log-$day.txt"

    /** Writes [day] between [fromHour] (inclusive) and [toHour] (exclusive), redacted; call off the main thread. */
    fun export(context: Context, target: Uri, day: LocalDate, fromHour: Int, toHour: Int) {
        val prefix = day.toString()
        val text = buildString {
            header(context, this)
            appendLine()
            appendLine("== $prefix ${"%02d:00".format(fromHour)}–${"%02d:00".format(toHour)} ==")
            val lines = files(directory(context)).flatMap { file -> file.readLines().filter { it.startsWith(prefix) } }
            lines.filter { (it.substring(11, minOf(13, it.length)).toIntOrNull() ?: -1) in fromHour until toHour }
                .distinct() // a dead process's last lines may be in both app.log and crash.log
                .sortedBy { it.take(23) }
                /**
                 * 用户口径（2026-11-26）**按 PID 全量抓之后，导出时要滤掉系统噪音**。
                 *
                 * 抓取改成 `--pid` 之后，本进程的日志全进来了（包括各库的 info/warn），
                 * 但同时也会混入大量与本应用无关的系统刷屏，例如：
                 *   · OplusDisplayModeManager / OplusBracketLog / OplusSfNotifier 等 OPPO 厂商日志
                 *   · AudioTrackExtImpl / ashmem / SchedAssist 这类平台噪音
                 *   · ExoPlayer / mpv 内部的逐帧 debug（warn 以下的都不进）
                 *
                 * 保留原则（宁可多留，不可漏掉排查线索）：
                 *  ① 所有 `I/W/E ETKN…` 标签 —— 我们自己的诊断主线，必留
                 *  ② 所有 `E`/`F` 级别的行 —— 任何报错都可能是线索，必留
                 *  ③ 播放器内核相关（mpv / ExoPlayer / MediaCodec / Codec2 / c2.）的 W 及以上
                 *  其余丢弃。
                 */
                .filter { line -> keepLine(line) }
                .forEach(::appendLine)
        }
        val output = context.contentResolver.openOutputStream(target, "wt") ?: error("无法写入所选位置")
        output.use { it.write(redact(text).toByteArray()) }
    }

    /** Deletes everything; the live file is truncated in place because the recorder keeps it open. */
    fun clear(context: Context) {
        val dir = directory(context)
        dir.listFiles()?.filter { it.name != LOG }?.forEach { it.delete() }
        runCatching { File(dir, LOG).writeText("") }
    }

    /** Device facts that explain playback failures: decoders, HDR, recent abnormal exits. */
    private fun header(context: Context, out: StringBuilder) = with(out) {
        appendLine("ETKN Mobile ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        append("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= 31) append(" · ${Build.SOC_MODEL}")
        appendLine(" · HDR ${hdr(context)}")
        decoders(this)
        if (Build.VERSION.SDK_INT >= 30) exits(context, this)
    }

    private fun hdr(context: Context): String = runCatching {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        @Suppress("DEPRECATION")
        val types = display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        types.joinToString("/") { type ->
            when (type) {
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "DV"
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                else -> type.toString()
            }
        }.ifEmpty { "none" }
    }.getOrDefault("?")

    /** Hardware decoders for disc/remux formats, one line per media type. */
    private fun decoders(out: StringBuilder) = runCatching {
        val byType = sortedMapOf<String, MutableList<String>>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder || (Build.VERSION.SDK_INT >= 29 && !info.isHardwareAccelerated && !info.isVendor)) continue
            for (type in info.supportedTypes) {
                if (type.lowercase(Locale.ROOT) !in WATCHED) continue
                val profiles = runCatching { info.getCapabilitiesForType(type).profileLevels.map { it.profile }.distinct().joinToString(",") }.getOrDefault("")
                byType.getOrPut(type) { mutableListOf() } += if (profiles.isEmpty()) info.name else "${info.name}[$profiles]"
            }
        }
        byType.forEach { (type, names) -> out.appendLine("$type: ${names.joinToString(" ")}") }
    }

    @RequiresApi(30)
    private fun exits(context: Context, out: StringBuilder) {
        runCatching {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .filter(ExitRecords::abnormal)
                .forEach { out.appendLine("exit ${stamp.format(Date(it.timestamp))} ${ExitRecords.label(it)} status=${it.status} ${it.description.orEmpty()}") }
        }
    }

    /** Removes credentials: whole URL query strings and any token-like key/value. */
    fun redact(text: String): String = text
        .replace(URL_QUERY, "$1?…")
        .replace(SECRET, "$1=…")

    /**
     * 用户口径（2026-11-26）：导出时决定哪些行值得留。
     *
     * 行格式：`2026-11-26 10:22:54.505 32647 32647 I Tag     : message`
     *
     * 为什么不是「只留 error」：改成按 PID 抓之后，本进程会带进大量
     * **平台/厂商层面的 E 级噪音**（实测 OPPO 机器上每几秒就刷一条）：
     * ```
     * E OplusDisplayModeManager: has been initialized.
     * E OplusBracketLog: ... updateHostViewRootIfNeeded, not support ...
     * E AudioTrackExtImpl: get fade type, but return null
     * E SchedAssist: open sharedFd failed with error=Permission denied
     * E ashmem: Pinning is deprecated since Android Q.
     * E OplusPredictiveBackController: NoSuchMethodException
     * E OplusSfNotifier / OplusHdrVideoUIDiming: get parcel data error
     * ```
     * 这些把真正的线索淹没了。所以反过来做：**先丢已知噪音，其余全留**。
     * 丢错了顶多多几行，漏了才是事故 —— 宁可多留。
     */
    private fun keepLine(line: String): Boolean = !NOISE.containsMatchIn(line)

    /**
     * 已知噪音标签（平台/厂商/系统组件），全部丢弃。
     * 只列**确认无用**的，不影响我们判断的；有疑问的一律不列。
     */
    private val NOISE = Regex(
        "\\s[VDIWEF]\\s+(" +
            "Oplus[A-Za-z]*|" +          // OPPO 厂商日志（刷屏主力）
            "SchedAssist|ashmem|" +
            "ActivityThread|OpenGLRenderer|EGL_emulation|" +
            "AudioTrackExtImpl|AudioFlinger|AudioPolicyManager|" +
            "Choreographer|ViewRootImpl|HWUI|libc|Zygote|" +
            "ziparchive|StrictMode|Binder|ProfileInstaller" +
            ")\\s*:",
    )

    private fun cleanup(dir: File) {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(KEEP_DAYS)
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /** Oldest first: logcat rotation names app.log.N (oldest) … app.log (newest); then crash lines. */
    private fun files(dir: File): List<File> =
        ((FILES downTo 1).map { File(dir, "$LOG.$it") } + File(dir, LOG) + File(dir, CRASH)).filter { it.isFile }

    private fun directory(context: Context) = File(context.filesDir, "logs").apply { mkdirs() }

    private val DAY = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val WATCHED = setOf(
        "video/hevc", "video/dolby-vision", "video/avc", "video/av01", "video/x-vnd.on2.vp9",
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/true-hd", "audio/eac3", "audio/eac3-joc", "audio/ac4",
    )
    private val URL_QUERY = Regex("""((?:https?://|/)[^\s"'<>?]+)\?[^\s"'<>]+""")
    private val SECRET = Regex("""(?i)\b(x-emby-token|x-mediabrowser-token|api_key|access_?token|token|password|pwd|authorization|cookie)\s*[=:]\s*"?[^\s",&;]+"?""")
}
