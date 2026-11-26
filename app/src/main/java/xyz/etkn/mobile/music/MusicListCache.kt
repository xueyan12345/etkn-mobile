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
    private fun timeKeyOf(libraryId: String, tab: String) = "time_${libraryId}_$tab"

    /**
     * 用户口径（2026-11-27，#22）**过期规则照视频/接口缓存那套**。
     *
     * 参考实现：`data/net/ResponseCache.kt:40`
     * ```
     * fun prune(maxAgeDays: Int = 14, maxBytes: Long = 16L * 1024 * 1024)
     * ```
     * 即「**14 天过期**，最多 16MB，App 启动时清理一次」。
     *
     * 音乐列表缓存沿用同一套：
     *  · 超过 [MAX_AGE_MS]（14 天）→ 读取时当作没有，同时删掉
     *  · 每次写入刷新时间戳
     *  · [prune] 在 App 启动时调用，清理所有过期的库
     *  · 用户点「清空缓存」时直接 [clear]
     */
    private const val MAX_AGE_MS = 14L * 86_400_000L   // 14 天，和 ResponseCache 一致

    fun read(context: Context, libraryId: String, tab: String): List<Item> = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        /** 过期检查：超过 14 天就当没缓存。 */
        val savedAt = prefs.getLong(timeKeyOf(libraryId, tab), 0L)
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > MAX_AGE_MS) {
            android.util.Log.i("ETKN-Music", "列表缓存已过期（${'$'}{(System.currentTimeMillis() - savedAt) / 86_400_000L} 天）")
            prefs.edit().remove(keyOf(libraryId, tab)).remove(timeKeyOf(libraryId, tab)).apply()
            return emptyList()
        }
        val raw = prefs.getString(keyOf(libraryId, tab), null) ?: return emptyList()
        raw.split(ROW).mapNotNull(::decode)
    }.getOrDefault(emptyList())

    fun write(context: Context, libraryId: String, tab: String, items: List<Item>) {
        runCatching {
            val raw = items.joinToString(ROW.toString()) { encode(it) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(keyOf(libraryId, tab), raw)
                /** 写入时刷新时间戳（用于 14 天过期判定）。 */
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

    /**
     * 启动时清理过期条目（照 `ResponseCache.prune()` 的做法）。
     *
     * 只按时间清理；音乐列表本身不大（601 首约 100KB），不需要按容量淘汰。
     */
    fun prune(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val stale = prefs.all.keys.filter { k ->
                k.startsWith("time_") && (now - ((prefs.all[k] as? Long) ?: 0L)) > MAX_AGE_MS
            }
            if (stale.isEmpty()) return@runCatching
            prefs.edit().apply {
                stale.forEach { tk ->
                    remove(tk)
                    remove("list_" + tk.removePrefix("time_"))
                }
            }.apply()
            android.util.Log.i("ETKN-Music", "列表缓存清理 ${stale.size} 个过期库")
        }
    }
}
