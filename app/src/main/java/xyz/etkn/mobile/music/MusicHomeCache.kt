package xyz.etkn.mobile.music

import android.content.Context
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.MusicHome
import xyz.etkn.mobile.data.model.UserData
import java.io.File

/**
 * 用户口径（2026-11-27，#89）**音乐推荐页本地缓存**。
 *
 * 用户原话：
 * ```
 * 「现在播放记录正常了，可以续播了，但是首页还是转圈，转一会才能有」
 * 「说错了，推荐页啊」
 * ```
 *
 * 推荐页原来只有 7 行实现：
 * ```
 * private val _state = MutableStateFlow(Load.Loading)   ← 进来必定 Loading
 * init { load() }                                       ← 每次都等网络
 * ```
 * 没有任何本地缓存，所以每次进推荐页都要等一次 `musicHome` 请求。
 *
 * 现在按和 [MusicListCache] 同一套做法：
 * ```
 * · 存普通文件 filesDir/musiclist/home_<lib>.txt（不进 SharedPreferences）
 * · 调用方负责 Dispatchers.IO
 * · 写入走「临时文件 + 原子改名」
 * · 永不按时间过期（只清：用户清缓存 / 退出登录 / 服务端返回空）
 * ```
 *
 * 数据结构是 `MusicHome(latest, recent, frequent)` 三个列表，
 * 序列化格式：三段之间用 `\u001D`（组分隔），每段内部沿用
 * [MusicListCache] 的「一行一首 + \u001F 分列」。
 */
object MusicHomeCache {
    private const val ROW = '\u001E'   // 记录分隔（一首歌一行）
    private const val COL = '\u001F'   // 字段分隔
    private const val GROUP = '\u001D' // 分区分隔（latest / recent / frequent）
    private const val DIR = "musiclist"

    private fun fileOf(context: Context, libraryId: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val safe = libraryId.replace('/', '_').replace('\\', '_')
        return File(dir, "home_$safe.txt")
    }

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
    ).joinToString(COL.toString()) { it.replace(COL, ' ').replace(ROW, ' ').replace(GROUP, ' ') }

    private fun decode(row: String): Item? {
        val p = row.split(COL)
        if (p.size < 10) return null
        val id = p[0].ifBlank { return null }
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
            runTimeTicks = p[6].toLongOrNull() ?: 0L,
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
     * 读推荐页缓存。**调用方必须放在 IO 线程**。
     * 出错返回 null（交给调用方走正常网络请求）。
     */
    fun read(context: Context, libraryId: String): MusicHome? = runCatching {
        val f = fileOf(context, libraryId)
        if (!f.exists()) return null
        val raw = f.readText()
        if (raw.isBlank()) return null
        val groups = raw.split(GROUP)
        if (groups.size < 3) return null
        val latest = groups[0].split(ROW).mapNotNull(::decode)
        val recent = groups[1].split(ROW).mapNotNull(::decode)
        val frequent = groups[2].split(ROW).mapNotNull(::decode)
        if (latest.isEmpty() && recent.isEmpty() && frequent.isEmpty()) return null
        android.util.Log.i(
            "ETKN-Music",
            "推荐页缓存命中：latest=${latest.size} recent=${recent.size} frequent=${frequent.size}",
        )
        MusicHome(latest = latest, recent = recent, frequent = frequent)
    }.getOrElse {
        android.util.Log.w("ETKN-Music", "推荐页缓存读取失败", it)
        null
    }

    fun write(context: Context, libraryId: String, home: MusicHome) {
        runCatching {
            val f = fileOf(context, libraryId)
            fun enc(list: List<Item>) = list.joinToString(ROW.toString()) { encode(it) }
            val raw = listOf(enc(home.latest), enc(home.recent), enc(home.frequent))
                .joinToString(GROUP.toString())
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(raw)
            if (f.exists()) f.delete()
            tmp.renameTo(f)
            android.util.Log.i("ETKN-Music", "推荐页缓存已写盘（${raw.length / 1024}KB）")
        }.onFailure {
            android.util.Log.w("ETKN-Music", "推荐页缓存写入失败", it)
        }
    }

    fun clear(context: Context, libraryId: String) {
        runCatching { fileOf(context, libraryId).delete() }
    }

    fun clearAll(context: Context) {
        runCatching { File(context.filesDir, DIR).deleteRecursively() }
    }
}
