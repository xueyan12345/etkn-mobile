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
            """^\s*(作词|作曲|编曲|制作人|混音|母带|和声|监制|出品|录音|吉他|贝斯|鼓|弦乐|填词|原唱|翻唱|OP|SP)\s*[:：].*$""",
            RegexOption.IGNORE_CASE,
        )

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
                /** 制作信息（作词/作曲…）带时间戳也跳过（#55）。 */
                if (content.isNotEmpty() && CREDIT.matches(content)) return@forEach
                /**
                 * 用户口径（2026-11-27，#55）**空内容的时间戳行不能直接丢掉**。
                 *
                 * 实测「天地离歌」的原始歌词：
                 * ```
                 * [00:02.41]        ← 只有时间戳，没文字（表示这段时间是间奏）
                 * [00:52.98]离了远了 别了忘了 不记了
                 * [01:29.77]        ← 同上
                 * [01:30.55]若再把笛音吹落
                 * ```
                 * 旧代码 `if (content.isEmpty()) return@forEach` 把这些行删了，
                 * 于是 `[00:02.41]` 之后直接跳到 `[00:52.98]` ——
                 * 播放到 2~53 秒时界面还停在前一句，用户看到的就是「延迟特别严重」。
                 *
                 * 修法：空内容行**保留为空白行**（占位），
                 * 这样时间轴连续，高亮句能正确推进。
                 */
                if (content.isEmpty()) {
                    tags.forEach { m ->
                        out += LyricLine(
                            m.groupValues[1].toLong() * 60_000 +
                                m.groupValues[2].toLong() * 1_000 +
                                run {
                                    val f = m.groupValues[3]
                                    when (f.length) { 0 -> 0L; 1 -> f.toLong() * 100; 2 -> f.toLong() * 10; else -> f.take(3).toLong() }
                                },
                            "",
                        )
                    }
                    return@forEach
                }
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
