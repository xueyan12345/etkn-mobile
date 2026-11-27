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
         * 用户口径（2026-11-27，#72）**歌词文件原样显示，只丢弃空行**。
         *
         * 用户原话（连续纠正三次）：
         * ```
         * 「不要过滤」
         * 「人家歌词里面的信息你过滤干啥」
         * 「过滤了之后不就空白了吗」
         * 「这种信息留着」
         * 「我只要过滤歌词里面空行的」
         * ```
         *
         * 我之前的错误：把 LRC 自带的 `[00:00.00]逃 - 侯旭`、
         * `[00:03.95]词：小白`、`[00:11.85]编曲：孙培喜` 这类行
         * 当成"脏数据"过滤，穷举职位名越补越多，还写了「开头 45 秒 +
         * 冒号」的结构兜底去猜 —— 全错。
         *
         * 真相：这些就是歌词文件的一部分，QQ 音乐/网易云都原样显示。
         * 我过滤掉之后开头几十秒整块空白，用户看到的就是"没歌词"。
         *
         * 现在**只做一件事：空行丢弃**。其余一律保留。
         */
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
                /**
                 * 用户口径（2026-11-27，#72）**只丢空行，其余一律原样显示**。
                 *
                 * 用户原话（连纠正五次）：
                 * ```
                 * 「不要过滤」
                 * 「人家歌词里面的信息你过滤干啥」
                 * 「过滤了之后不就空白了吗」
                 * 「这种信息留着」
                 * 「我只要过滤歌词里面空行的」
                 * ```
                 *
                 * 我之前的错误：把 LRC 自带的 `[00:00.00]逃 - 侯旭`、
                 * `[00:03.95]词：小白`、`[00:11.85]编曲：孙培喜` 当成"脏数据"过滤，
                 * 穷举职位名越补越多，还写「开头 45 秒 + 冒号」的结构兜底去猜 —— 全错。
                 * 过滤掉之后开头几十秒整块空白，用户看到的就是「没歌词」。
                 *
                 * 真相：这些就是歌词文件的一部分，QQ 音乐/网易云都原样显示。
                 * 所以现在**只做一件事：空行（`[00:19.43]` 后面什么都没有）丢弃**。
                 * 丢弃不影响高亮 —— `indexAt()` 是二分查找「最后一个 timeMs <= 当前位置」，
                 * 空行没了它照样落到下一句起点。
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
            val parsed = if (sawTag) {
                Lyrics(out.filter { it.timeMs >= 0 }.sortedBy { it.timeMs }, true)
            } else {
                Lyrics(out, false)
            }
            /**
             * 用户口径（2026-11-27，#75）**歌词解析结果要打进日志**。
             *
             * 用户反复说「50 多秒才显示歌词」「歌词不匹配」，但我之前只能靠截图猜，
             * 截图又常抓到别的窗口（Telegram/桌面），验证不了。
             * 有了这行日志，直接 `adb logcat -s ETKN-Lyrics` 就能看到
             * 解析出几行、第一句是什么、时间戳多少 —— 不用再看截图。
             */
            android.util.Log.i(
                "ETKN-Lyrics",
                "解析完成: synced=$sawTag 共${parsed.lines.size}行 " +
                    "首句=${parsed.lines.firstOrNull()?.let { "${it.timeMs}ms/「${it.text.take(24)}」" }} " +
                    "末句=${parsed.lines.lastOrNull()?.let { "${it.timeMs}ms" }}",
            )
            return parsed
        }
    }
}
