package xyz.etkn.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.etkn.mobile.music.Lyrics

class LyricsTest {
    /**
     * 用户口径（2026-11-27，#55）**空时间戳行必须保留为占位**。
     *
     * 原测试名是 `parsesSyncedLinesAndDropsEmptyTimestamps`，断言空行被丢弃。
     * 实测「天地离歌」暴露了这个行为是错的：
     * ```
     * [00:02.41]                            ← 间奏占位
     * [00:52.98]离了远了 别了忘了 不记了     ← 50 秒后才有第一句
     * ```
     * 丢掉空行后时间轴断裂，播到 2~53 秒界面还停在前一句，
     * 用户看到的就是「歌词延迟特别严重」。
     *
     * 现在的行为：空内容行保留为 `text = ""` 的占位行，时间轴连续。
     */
    @Test
    fun parsesSyncedLinesAndKeepsEmptyTimestamps() {
        val lyrics = Lyrics.parse(
            """
            [ti:Song]
            [00:01.50]第一句
            [00:03.00]
            [00:05.2][00:20.25]重复句
            """.trimIndent(),
        )
        assertTrue(lyrics.synced)
        /** 空时间戳行不再被丢弃：1.5s / 3.0s(空) / 5.2s / 20.25s。 */
        assertEquals(listOf(1_500L, 3_000L, 5_200L, 20_250L), lyrics.lines.map { it.timeMs })
        assertEquals("", lyrics.lines[1].text)
        assertEquals("重复句", lyrics.lines[2].text)
        assertEquals(-1, lyrics.indexAt(1_000))
        assertEquals(0, lyrics.indexAt(2_000))
        /** 3~5.2 秒落在空行上（间奏），不再是上一句。 */
        assertEquals(1, lyrics.indexAt(4_000))
        assertEquals(3, lyrics.indexAt(60_000))
    }

    /**
     * 用户口径（#57）**作词/作曲/歌名行不能当歌词显示**。
     *
     * 实测「天外来物」开头：
     * ```
     * [00:00.00]天外来物 - 薛之谦
     * [00:06.03]词：薛之谦
     * [00:12.06]曲：罗小黑
     * [00:18.09]你降落的 太突然了
     * ```
     * 这三行要是不过滤，前 18 秒界面上全是制作信息，
     * 看起来就像歌词"迟迟不出来"。
     */
    @Test
    fun dropsCreditsAndTitleLines() {
        val lyrics = Lyrics.parse(
            """
            [ti:天外来物]
            [00:00.00]天外来物 - 薛之谦
            [00:06.03]词：薛之谦
            [00:12.06]曲：罗小黑
            [00:18.09]你降落的 太突然了
            [00:25.19]我刚好呢 又路过了
            """.trimIndent(),
        )
        assertTrue(lyrics.synced)
        assertEquals(listOf("你降落的 太突然了", "我刚好呢 又路过了"), lyrics.lines.map { it.text })
        assertEquals(18_090L, lyrics.lines.first().timeMs)
    }

    @Test
    fun plainTextIsNotSynced() {
        val lyrics = Lyrics.parse("第一行\n\n第二行")
        assertFalse(lyrics.synced)
        assertEquals(2, lyrics.lines.size)
        assertEquals(-1, lyrics.indexAt(10_000))
    }
}
