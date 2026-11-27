package xyz.etkn.mobile.music

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 用户口径（2026-11-27，#128）**歌词要缓存，不要每次重拉**。
 *
 * 用户原话：
 * ```
 * 「那个歌词是不是也加缓存，要不每次都要拉数据」
 * ```
 *
 * ## 改之前的样子
 *
 * `EtknApi.lyrics()` 完全没有缓存，而且同一个 `itemId` 会被请求两次：
 * ```
 * MiniLyrics（迷你条那三行）  → graph.api.lyrics(...)
 * LyricsPane（歌词区整页）    → graph.api.lyrics(...)
 * ```
 * 再加上服务端 `GET` 拿不到词时会 `POST` 在线查找，
 * **一次切歌最多 4 个请求**，每次进播放页还会再来一遍。
 *
 * ## 现在
 *
 * ```
 * filesDir/lyricscache/<itemId>.lrc
 * ```
 * `read()` 命中就零请求直接返回；`write()` 只在拿到**非空**歌词时写。
 *
 * 放 `filesDir` 而不是 `cacheDir` —— ColorOS 会积极回收 cacheDir
 * （音乐缓存那边已经踩过这个坑）。
 *
 * ## 为什么不缓存空结果
 *
 * 服务端某次抽风返回空，如果一个空文件被永久缓存，
 * 这首歌就再也拿不到词了。宁可不缓存，下次再问一次。
 */
object LyricsCache {
    private const val TAG = "ETKN-LyricsCache"
    private const val DIR = "lyricscache"

    @Volatile private var app: Context? = null

    /** `EtknApp.onCreate()` 里调，`EtknApi` 是纯网络层拿不到 context。 */
    fun attach(context: Context) { app = context.applicationContext }

    private fun fileOf(itemId: String): File? {
        val ctx = app ?: return null
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        /** itemId 是 `item-25493` 这种，不会带路径分隔符，但仍兜一层。 */
        val safe = itemId.replace('/', '_').replace('\\', '_')
        return File(dir, "$safe.lrc")
    }

    /** 命中返回歌词原文，没有则 null（调用方去联网）。 */
    fun read(itemId: String): String? {
        val f = fileOf(itemId) ?: return null
        if (!f.isFile) return null
        return runCatching {
            val text = f.readText()
            if (text.isBlank()) {
                /** 空文件直接删掉，别让它一直挡着。 */
                f.delete()
                null
            } else {
                text
            }
        }.onFailure { Log.w(TAG, "读歌词缓存失败 $itemId", it) }.getOrNull()
    }

    fun write(itemId: String, text: String) {
        if (text.isBlank()) return
        val f = fileOf(itemId) ?: return
        runCatching { f.writeText(text) }
            .onFailure { Log.w(TAG, "写歌词缓存失败 $itemId", it) }
    }

    /** 「清空音乐缓存」时顺带清掉，让用户可以手动重拉。 */
    fun clear() {
        val ctx = app ?: return
        runCatching { File(ctx.filesDir, DIR).deleteRecursively() }
            .onFailure { Log.w(TAG, "清空歌词缓存失败", it) }
    }

    /** 当前缓存条数（设置页显示用）。 */
    fun count(): Int {
        val ctx = app ?: return 0
        return runCatching { File(ctx.filesDir, DIR).listFiles()?.size ?: 0 }.getOrDefault(0)
    }
}
