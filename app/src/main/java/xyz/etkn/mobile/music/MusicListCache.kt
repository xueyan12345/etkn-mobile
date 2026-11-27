package xyz.etkn.mobile.music

import android.content.Context
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.UserData
import java.io.File

/**
 * 用户口径（2026-11-27，#19/#23/#88）**歌曲列表本地缓存**。
 *
 * 用户原话（需求来源）：
 * ```
 * 「我发现每次好像进入APP打开，都是要重新请求服务器重新拉一下，
 *   这个能不能写进本地缓存呀，就像是视频的那页一样，
 *   刚进去先看到的是本地缓存，要不进去之后看到的是空白」
 * 「音乐推荐页面每次打开都转圈」
 * 「正常来讲，这个都有缓存而在，打开直接就能进去，不应该转圈啊，
 *   就算转，也应该是秒一下子就出来」
 * ```
 *
 * 目标行为：
 * ```
 * 进「歌曲」页
 *   → 立刻显示上次缓存的完整列表（不空白、不转圈）
 *   → 后台静默拉服务端最新数据
 *   → 拿到后替换并回写缓存
 * ```
 *
 * ## #88 改了什么（上一版还在转圈的原因）
 *
 * 原来用 `SharedPreferences` 存整库（601 首）字符串，三重问题：
 * ```
 * ① SharedPreferences 首次加载会把**整个 XML 文件**读进内存并解析，
 *    几百 KB 的缓存直接卡主线程
 * ② ViewModel 的 init 里**同步**调用 read()：
 *       raw.split(分隔符)      → 601 段
 *       .mapNotNull(::decode)  → 每段再 split 10 字段 + 构造 Item
 *    ≈ 6000 次字符串切分 + 601 个对象构造，全在主线程
 * ③ write() 用 runCatching 吞掉所有异常，写失败没人知道
 * ```
 *
 * 现在：
 * ```
 * · 存普通文件 filesDir/musiclist/<lib>_<tab>.txt（不进 SharedPreferences）
 * · 调用方负责放 Dispatchers.IO（ViewModel 已改）
 * · 写入走「临时文件 + 原子改名」，写失败打日志
 * ```
 *
 * ## 为什么**不**按时间过期
 *
 * 用户质疑（完全正确）：
 * 「14天过期，那么第14天的时候，打开，你给我清理了，
 *   那会不会又出现这种问题？就是没有数据了，还得重新加载」
 *
 * 接口缓存过期 = 数据可能错了，该删；
 * 列表缓存只是「先把列表画出来」的占位，进页面后很快被服务端覆盖，删它只有坏处。
 *
 * 真正会被清掉的时机只有三个：
 *  · 用户点「清空缓存」
 *  · 退出登录 / 换账号
 *  · 服务端返回列表为空
 */
object MusicListCache {
    private const val ROW = '\u001E'   // 记录分隔（一首歌一行）
    private const val COL = '\u001F'   // 字段分隔
    private const val DIR = "musiclist"

    private fun fileOf(context: Context, libraryId: String, tab: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val safe = "${libraryId}_${tab}".replace('/', '_').replace('\\', '_')
        return File(dir, "$safe.txt")
    }

    /** 缓存一首歌需要的字段（够渲染列表、够按各种方式排序）。 */
    private fun encode(item: Item): String = listOf(
        item.id,
        item.name,
        item.type,
        item.artistLine,
        item.album.orEmpty(),
        item.imageTags["Primary"].orEmpty(),
        item.runTimeTicks.toString(),
        item.dateCreated.orEmpty(),
        item.userData.playCount.toString(),
        item.indexNumber?.toString().orEmpty(),
    ).joinToString(COL.toString()) { it.replace(COL, ' ').replace(ROW, ' ') }

    private fun decode(row: String): Item? {
        val p = row.split(COL)
        if (p.size < 10) return null
        val id = p[0].ifBlank { return null }
        val ticks = p[6].toLongOrNull() ?: 0L
        return Item(
            id = id,
            name = p[1],
            type = p[2].ifBlank { "Audio" },
            collectionType = null,
            sourceType = null,
            overview = null,
            taglines = emptyList(),
            productionYear = null,
            premiereDate = null,
            dateCreated = p[7].ifBlank { null },
            endDate = null,
            officialRating = null,
            communityRating = null,
            criticRating = null,
            tmdbRating = null,
            doubanRating = null,
            imdbRating = null,
            runTimeTicks = ticks,
            genres = emptyList(),
            studios = emptyList(),
            seriesId = null,
            seriesName = null,
            seasonId = null,
            seasonName = null,
            parentIndexNumber = null,
            indexNumber = p[9].toIntOrNull(),
            indexNumberEnd = null,
            imageTags = p[5].ifBlank { null }?.let { mapOf("Primary" to it) } ?: emptyMap(),
            backdropTags = emptyList(),
            parentBackdropItemId = null,
            parentBackdropTags = emptyList(),
            parentLogoItemId = null,
            parentLogoTag = null,
            parentThumbItemId = null,
            parentThumbTag = null,
            seriesPrimaryTag = null,
            primaryAspectRatio = null,
            userData = UserData(playCount = p[8].toIntOrNull() ?: 0),
            people = emptyList(),
            mediaSources = emptyList(),
            childCount = null,
            recursiveItemCount = null,
            providerIds = emptyMap(),
            isFolder = false,
            locationType = null,
            isMissing = false,
            status = null,
            artists = if (p[3].isNotBlank()) listOf(p[3]) else emptyList(),
            album = p[4].ifBlank { null },
            albumKey = null,
            kind = null,
            key = null,
            artworkHash = null,
            artistImage = null,
            count = null,
            chapters = emptyList(),
        )
    }

    /**
     * 读缓存：**不做时间过期**（见类注释）。有多少返回多少。
     *
     * ⚠️ 调用方必须在 IO 线程调用（ViewModel 里已用 withContext(IO)）。
     * 出错一律返回空列表，不抛异常、不崩溃。
     */
    fun read(context: Context, libraryId: String, tab: String): List<Item> = runCatching {
        val f = fileOf(context, libraryId, tab)
        if (!f.exists()) {
            android.util.Log.i("ETKN-Music", "列表缓存: 文件不存在（${f.name}）")
            return emptyList()
        }
        val raw = f.readText()
        if (raw.isBlank()) return emptyList()
        val list = raw.split(ROW).mapNotNull(::decode)
        val days = (System.currentTimeMillis() - f.lastModified()) / 86_400_000L
        android.util.Log.i(
            "ETKN-Music",
            "列表缓存命中 ${list.size} 首（${days} 天前写入，${raw.length / 1024}KB）",
        )
        list
    }.getOrElse {
        android.util.Log.w("ETKN-Music", "列表缓存读取失败", it)
        emptyList()
    }

    /**
     * 写缓存：先写临时文件再原子改名，避免写一半被杀进程留下坏文件。
     *
     * 失败要打日志 —— 上一版 runCatching 静默吞异常，
     * 导致「缓存写了但没生效」这种情况完全查不出来。
     */
    fun write(context: Context, libraryId: String, tab: String, items: List<Item>) {
        runCatching {
            val f = fileOf(context, libraryId, tab)
            val raw = items.joinToString(ROW.toString()) { encode(it) }
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(raw)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            android.util.Log.i(
                "ETKN-Music",
                "列表缓存已写盘 ${items.size} 首（${raw.length / 1024}KB）",
            )
        }.onFailure {
            android.util.Log.w("ETKN-Music", "列表缓存写入失败（libraryId=$libraryId tab=$tab）", it)
        }
    }

    fun clear(context: Context, libraryId: String, tab: String) {
        runCatching { fileOf(context, libraryId, tab).delete() }
    }

    /** 清空全部（退出登录/换账号时用）。 */
    fun clearAll(context: Context) {
        runCatching { File(context.filesDir, DIR).deleteRecursively() }
    }
}
