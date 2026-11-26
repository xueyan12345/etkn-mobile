package xyz.etkn.mobile.music

import android.content.Context
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.UserData

/**
 * 用户口径（2026-11-27，#19）**歌曲列表本地缓存**。
 *
 * 用户原话：
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
 *   → 拿到后替换（并且只在内容真的变了时才滚动位置）
 * ```
 *
 * 实现：
 *  · 存 SharedPreferences，key 按「库 id + tab」分开
 *  · `Item` 不是 @Serializable，所以只存排序/播放/展示必需的字段，
 *    用 `\u001F`（单元分隔符）拼接，`\u001E`（记录分隔符）分行
 *  · 601 首约 100KB，SharedPreferences 能吃下（远小于 1MB 的坑）
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

    fun read(context: Context, libraryId: String, tab: String): List<Item> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(keyOf(libraryId, tab), null) ?: return emptyList()
        raw.split(ROW).mapNotNull(::decode)
    }.getOrDefault(emptyList())

    fun write(context: Context, libraryId: String, tab: String, items: List<Item>) {
        runCatching {
            val raw = items.joinToString(ROW.toString()) { encode(it) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(keyOf(libraryId, tab), raw).apply()
        }
    }

    fun clear(context: Context, libraryId: String, tab: String) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(keyOf(libraryId, tab)).apply()
        }
    }
}
