package xyz.etkn.mobile.music

import androidx.compose.runtime.Immutable

@Immutable
data class LyricLine(val timeMs: Long, val text: String)

@Immutable
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {
    /** Index of the line active at [positionMs] (binary search; lines are sorted). */
    fun indexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }

    companion object {
        private val TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val META = Regex("""^\[(ar|ti|al|by|offset|re|ve|length|au):.*]$""", RegexOption.IGNORE_CASE)

        /**
         * 用户口径（2026-11-27，#55）**作词/作曲这类制作信息不当歌词显示**。
         *
         * 实测「天地离歌」开头：
         * ```
         * [00:00.0]作词: Vagary
         * [00:01.0]作曲: 又又
         * ```
         * 这两行带时间戳，所以 META 正则（只认 `[ar:...]` 这种）拦不住它们，
         * 会当成正常歌词显示在最前面，用户看到的就是"播了半天还在第一句"。
         */
        private val CREDIT = Regex(
            """^\s*(作词|作曲|编曲|制作人|混音|母带|和声|监制|出品|录音|吉他|贝斯|鼓|弦乐|填词|原唱|翻唱|OP|SP|词|曲|演唱|演唱者|专辑|发行|策划|统筹)\s*[:：].*$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 用户口径（2026-11-27，#57）**制作信息行首尾带歌名/歌手名也要过滤**。
         *
         * 实测「天外来物」：
         * ```
         * [00:00.00]天外来物 - 薛之谦      ← 歌名+歌手，重复显示
         * [00:06.03]词：薛之谦             ← 用「词：」不是「作词：」
         * [00:12.06]曲：罗小黑
         * [00:18.09]你降落的 太突然了       ← 真正的第一句
         * ```
         * 上一版 CREDIT 只认「作词：」，漏了「词：」「曲：」这种简写，
         * 于是这三行都被当歌词显示，用户看到开头 18 秒全是这些东西，
         * 主观感受就是"歌词延迟特别严重"。
         */
        private val TITLE_LINE = Regex("""^\s*(.+?)\s*[-–—]\s*(.+?)\s*$""")

        /**
         * Parses LRC (multiple time tags per line supported) or plain text. Blank lines and
         * lines with only time tags are dropped, as on TV.
         */
        fun parse(text: String): Lyrics {
            val out = ArrayList<LyricLine>()
            var sawTag = false
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || META.matches(line)) return@forEach
                val tags = TAG.findAll(line).toList()
                if (tags.isEmpty()) {
                    out += LyricLine(-1, line)
                    return@forEach
                }
                sawTag = true
                val content = line.substring(tags.last().range.last + 1).trim()
                /** 制作信息（作词/作曲…）带时间戳也跳过（#55/#57）。 */
                if (content.isNotEmpty() && CREDIT.matches(content)) return@forEach
                /**
                 * 「歌名 - 歌手」这种标题行也跳过（#57）。
                 *
                 * 实测「天外来物」第一行就是 `[00:00.00]天外来物 - 薛之谦`，
                 * 和界面顶部的歌名/歌手完全重复，不该出现在歌词里。
                 * 判定：包含「-」或「–」或「—」且前面部分长度 < 30（排除正常歌词里的破折号）。
                 */
                if (content.isNotEmpty()) {
                    val m = TITLE_LINE.matchEntire(content)
                    if (m != null && m.groupValues[1].length < 30) return@forEach
                }
                /**
                 * 用户口径（2026-11-27，#67）**空行直接删掉，不要占位**。
                 *
                 * 用户原话：「我的歌词文件里面有空行的，空行给我删掉」
                 *
                 * 演进过程（我绕了一圈）：
                 * ```
                 * 上游原版    if (content.isEmpty()) return@forEach   → 空行被丢弃
                 * 我改成保留  空行变成 text="" 的占位行               → 界面上出现空档
                 * 现在回到    return@forEach                          → 空行真的不出现
                 * ```
                 *
                 * 之前我担心「丢掉空行会让时间轴断裂、高亮卡住」，
                 * 那是误判 —— `Lyrics.indexAt()` 是二分查找「最后一个 timeMs <= 当前位置」，
                 * 丢掉中间的空行后它依然会正确落到下一句的起点，
                 * 不存在卡住的问题。（真正的"歌词延迟"根因是制作信息行被当歌词，
                 * 那个已经由下面的 CREDIT 过滤解决。）
                 */
                if (content.isEmpty()) return@forEach
                tags.forEach { m ->
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                    out += LyricLine(min * 60_000 + sec * 1_000 + ms, content)
                }
            }
            return if (sawTag) Lyrics(out.filter { it.timeMs >= 0 }.sortedBy { it.timeMs }, true) else Lyrics(out, false)
        }
    }
}
