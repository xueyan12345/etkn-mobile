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
     * Tags written at info level; everything else only at error level.
     *
     * 用户口径（2026-11-23）：**这里漏了 ETKN-Rotate，导致平板旋转的所有诊断日志
     * 一条都没进日志文件**（logcat 过滤只保留白名单 tag 的 info）。
     * 我连着两版加日志都看不到，就是因为这个。
     *
     * 不再逐个列举：凡是 ETKN 打头的 tag 都要 info，新加模块不会再漏。
     */
    private val TAGS = listOf("ETKN", "ETKN-Play", "ETKN-Disc", "ETKN-Player", "ETKN-Mpv", "ETKN-Rotate")
    private val FILTER = TAGS.map { "$it:I" } + "*:E"

    fun start(context: Context) {
        val dir = directory(context)
        File(context.filesDir, "diagnostics").deleteRecursively() // 0.5.3's unfiltered mirror
        cleanup(dir)
        runCatching {
            val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            ProcessBuilder(
                listOf("logcat", "-v", "threadtime,year", "-T", since, "-f", File(dir, LOG).path, "-r", "256", "-n", FILES.toString()) + FILTER,
            ).redirectErrorStream(true).start()
        }.onFailure { Log.w(TAG, "log recorder unavailable", it) }
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
