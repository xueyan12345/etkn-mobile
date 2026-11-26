package xyz.etkn.mobile.music

import android.content.Context
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.UserData

/**
 * 用户口径（2026-11-27，#19/#23）**歌曲列表本地缓存**。
 *
 * 用户原话（需求来源）：
 *  「我发现每次好像进入APP打开，都是要重新请求服务器重新拉一下，
 *    这个能不能写进本地缓存呀，就像是视频的那页一样，
 *    刚进去先看到的是本地缓存，要不进去之后看到的是空白，
 *    然后等刷新之后才能看到缓存」
 *
 * 目标行为：
 * ```
 * 进「歌曲」页
 *   → 立刻显示上次缓存的完整列表（0 延迟，不空白）
 *   → 后台静默拉服务端最新数据
 *   → 拿到后替换并回写缓存
 * ```
 *
 * ## 为什么**不**按时间过期
 *
 * 用户质疑（完全正确）：
 *  「14天过期，那么第14天的时候，打开，你给我清理了，那会不会又出现这种问题？
 *    就是没有数据了，东西啥都没了，还得重新加载，列表里的歌也都没有了需要重新弄」
 *
 * 两者的用途根本不同：
 * ```
 * 接口缓存(ResponseCache)  过期 = 数据可能错了，必须重新拉 → 该删
 * 歌曲列表缓存(本类)       只是"先把列表画出来"的占位，
 *                          进页面后很快就被服务端最新数据覆盖 → 删它只有坏处
 * ```
 *
 * 删掉的唯一后果：第 14 天进页面又看到一次空白，等网络回来。
 * 所以这里**永不按时间过期**，永远「先显示旧的，后台再刷新」。
 *
 * 真正会被清掉的时机只有三个：
 *  · 用户点「清空缓存」
 *  · 退出登录 / 换账号（缓存必须失效）
 *  · 服务端返回列表为空（说明这批歌真没了，覆盖成空）
 */
object MusicListCache {
    private const val PREFS = "etkn_music_list_cache"
    private const val ROW = '\u001E'   // 记录分隔（一首歌一行）
    private const val COL = '\u001F'   // 字段分隔

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

    private fun keyOf(libraryId: String, tab: String) = "list_${libraryId}_$tab"
    private fun timeKeyOf(libraryId: String, tab: String) = "time_${libraryId}_$tab"

    /**
     * 读缓存：**不做时间过期**（见类注释）。有多少返回多少。
     *
     * `savedAt` 只用于日志（看缓存有多旧），不参与任何判定。
     */
    fun read(context: Context, libraryId: String, tab: String): List<Item> = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(keyOf(libraryId, tab), null) ?: return emptyList()
        val list = raw.split(ROW).mapNotNull(::decode)
        val savedAt = prefs.getLong(timeKeyOf(libraryId, tab), 0L)
        if (savedAt > 0) {
            val days = (System.currentTimeMillis() - savedAt) / 86_400_000L
            android.util.Log.i("ETKN-Music", "列表缓存命中 ${list.size} 首（${days} 天前写入）")
        }
        list
    }.getOrDefault(emptyList())

    fun write(context: Context, libraryId: String, tab: String, items: List<Item>) {
        runCatching {
            val raw = items.joinToString(ROW.toString()) { encode(it) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(keyOf(libraryId, tab), raw)
                /** 时间戳只用于日志，不做过期判定。 */
                .putLong(timeKeyOf(libraryId, tab), System.currentTimeMillis())
                .apply()
        }
    }

    fun clear(context: Context, libraryId: String, tab: String) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(keyOf(libraryId, tab)).remove(timeKeyOf(libraryId, tab)).apply()
        }
    }

    /** 清空全部（退出登录/换账号时用）。 */
    fun clearAll(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
