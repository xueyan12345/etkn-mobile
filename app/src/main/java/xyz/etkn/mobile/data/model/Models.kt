package xyz.etkn.mobile.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import xyz.etkn.mobile.data.net.arr
import xyz.etkn.mobile.data.net.bool
import xyz.etkn.mobile.data.net.double
import xyz.etkn.mobile.data.net.has
import xyz.etkn.mobile.data.net.int
import xyz.etkn.mobile.data.net.intOrNull
import xyz.etkn.mobile.data.net.long
import xyz.etkn.mobile.data.net.longOrNull
import xyz.etkn.mobile.data.net.obj
import xyz.etkn.mobile.data.net.objects
import xyz.etkn.mobile.data.net.rows
import xyz.etkn.mobile.data.net.str
import xyz.etkn.mobile.data.net.strList
import xyz.etkn.mobile.data.net.strMap
import xyz.etkn.mobile.data.net.strOrNull
import java.util.Locale

const val TICKS_PER_MS = 10_000L

@Immutable
data class UserData(
    val played: Boolean = false,
    val isFavorite: Boolean = false,
    val playbackPositionTicks: Long = 0,
    val playedPercentage: Double? = null,
    val unplayedItemCount: Int? = null,
    val playCount: Int = 0,
) {
    companion object {
        fun from(o: JsonObject?): UserData = if (o == null) UserData() else UserData(
            played = o.bool("Played"),
            isFavorite = o.bool("IsFavorite"),
            playbackPositionTicks = o.long("PlaybackPositionTicks"),
            playedPercentage = o.double("PlayedPercentage"),
            unplayedItemCount = o.intOrNull("UnplayedItemCount"),
            playCount = o.int("PlayCount"),
        )
    }

    fun merge(o: JsonObject): UserData = copy(
        played = if (o.has("Played")) o.bool("Played") else played,
        isFavorite = if (o.has("IsFavorite")) o.bool("IsFavorite") else isFavorite,
        playbackPositionTicks = o.longOrNull("PlaybackPositionTicks") ?: playbackPositionTicks,
        playedPercentage = o.double("PlayedPercentage") ?: playedPercentage,
        unplayedItemCount = o.intOrNull("UnplayedItemCount") ?: unplayedItemCount,
    )
}

@Immutable
data class PersonRef(
    val id: String,
    val name: String,
    val role: String,
    val type: String,
    val imageTag: String?,
) {
    companion object {
        fun from(o: JsonObject) = PersonRef(
            id = o.str("Id"),
            name = o.str("Name"),
            role = o.str("Role"),
            type = o.str("Type"),
            imageTag = o.strOrNull("PrimaryImageTag"),
        )
    }
}

@Immutable
data class MediaStream(
    val index: Int,
    val type: String,
    val codec: String,
    val language: String,
    val title: String,
    val displayTitle: String,
    val isDefault: Boolean,
    val isExternal: Boolean,
    val deliveryUrl: String?,
    val width: Int,
    val height: Int,
    val channels: Int,
    val bitRate: Long,
    val videoRange: String,
    val videoRangeType: String,
    val colorTransfer: String,
    val profile: String,
    /** Emby-style HDR / Dolby Vision labels (`HDR10`, `DolbyVision` / `DoviProfile81` …). */
    val extendedVideoType: String = "",
    val extendedVideoSubType: String = "",
    /** Blu-ray image tracks only: the MPEG-TS PID from the server's disc probe, else -1. */
    val transportStreamId: Int = -1,
    /** Frames per second (RealFrameRate, else AverageFrameRate); 0 when unknown. */
    val frameRate: Float = 0f,
    /**
     * 用户口径（2026-11-28，#140）**详情页要列出完整媒体信息**。
     *
     * 用户原话：「这些信息在 ETKN 的服务器上都有」「你就完全给它复刻过来就行」
     *
     * 下面这几个字段以前没解析，详情页那张"视频"卡要显示
     * 「原色 / 色彩空间 / 位深度 / 像素格式 / 宽高比」，所以补上。
     * 全部来自 API 的 MediaStreams，实测 item-23147 都有值。
     */
    val colorPrimaries: String = "",
    val colorSpace: String = "",
    val bitDepth: Int = 0,
    val pixelFormat: String = "",
    val aspectRatio: String = "",
    /** Subtitle calibration shared with Web / TV: key, saved offset, and burned-in (cannot shift). */
    val offsetKey: String = "",
    val offsetMs: Int = 0,
    val burnedIn: Boolean = false,
) {
    val label: String get() = displayTitle.ifEmpty { title.ifEmpty { language.ifEmpty { codec.uppercase() } } }
    val previewLabel: String get() = title.ifEmpty { displayTitle.ifEmpty { language.ifEmpty { codec.uppercase() } } }

    /**
     * 用户口径（2026-11-28，#139）**动态范围标签：HDR10 / DV / HLG**。
     *
     * 实测证据（醉梦 S1E2 `item-23147` 的 video stream）：
     * ```
     * VideoRange     = "HDR10"     ← 有值
     * VideoRangeType = 空           ← 原代码只读这个，所以永远不显示
     * ColorPrimaries = "bt2020"
     * ColorTransfer  = "smpte2084"  ← PQ = HDR10 特征
     * BitDepth       = 10
     * ```
     *
     * 三档回退，谁先有值用谁：
     * ```
     * ① videoRangeType（最细：DolbyVision / HDR10Plus / HDR10 / HLG）
     * ② videoRange（HDR10 / HLG / DolbyVision / SDR）
     * ③ colorTransfer 反推（smpte2084 → HDR10、arib-std-b67 → HLG）
     * ```
     *
     * 显示规则（用户原话「有的还是杜比视界要加 DV」）：
     * ```
     * dolbyvision / dovi*   → "DV"
     * hdr10plus / hdr10+    → "HDR10+"
     * hdr10 / smpte2084     → "HDR10"
     * hlg / arib-std-b67    → "HLG"
     * sdr / 空              → null（不显示）
     * ```
     *
     * 全部小写比较：服务端各版本写法不统一
     * （`dolbyvision` / `DolbyVision` / `DOVI` 都出现过）。
     */
    fun dynamicRangeLabel(): String? {
        val candidates = listOf(videoRangeType, videoRange, extendedVideoType, extendedVideoSubType)
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
        for (c in candidates) {
            when {
                c.contains("dolby") || c.contains("dovi") -> return "DV"
                c.contains("hdr10+") || c.contains("hdr10plus") -> return "HDR10+"
                c.contains("hdr10") -> return "HDR10"
                c.contains("hlg") -> return "HLG"
                c == "sdr" -> return null
            }
        }
        /** 最后一档：色彩传输特性反推（老数据只有这个）。 */
        return when (colorTransfer.lowercase()) {
            "smpte2084", "st2084" -> "HDR10"
            "arib-std-b67" -> "HLG"
            else -> null
        }
    }

    companion object {
        fun from(o: JsonObject) = MediaStream(
            index = o.int("Index", -1),
            type = o.str("Type"),
            codec = o.str("Codec"),
            language = o.str("Language"),
            title = o.str("Title"),
            displayTitle = o.str("DisplayTitle"),
            isDefault = o.bool("IsDefault"),
            isExternal = o.bool("IsExternal"),
            deliveryUrl = o.strOrNull("DeliveryUrl"),
            width = o.int("Width"),
            height = o.int("Height"),
            channels = o.int("Channels"),
            bitRate = o.long("BitRate"),
            videoRange = o.str("VideoRange"),
            videoRangeType = o.str("VideoRangeType"),
            colorTransfer = o.str("ColorTransfer"),
            profile = o.str("Profile"),
            extendedVideoType = o.str("ExtendedVideoType"),
            extendedVideoSubType = o.str("ExtendedVideoSubType"),
            transportStreamId = o.int("TransportStreamId", -1),
            frameRate = (o.double("RealFrameRate") ?: o.double("AverageFrameRate") ?: 0.0).toFloat(),
            colorPrimaries = o.str("ColorPrimaries"),
            colorSpace = o.str("ColorSpace"),
            bitDepth = o.int("BitDepth"),
            pixelFormat = o.str("PixelFormat"),
            aspectRatio = o.str("AspectRatio"),
            offsetKey = o.str("SubtitleOffsetKey"),
            offsetMs = o.int("SubtitleOffsetMs"),
            burnedIn = o.bool("IsBurnedIn"),
        )
    }
}

@Immutable
data class Chapter(
    val startTicks: Long,
    val name: String,
    val markerType: String,
    val imageTag: String?,
    val imagePath: String?,
    val index: Int,
) {
    companion object {
        fun from(o: JsonObject, position: Int) = Chapter(
            startTicks = o.long("StartPositionTicks"),
            name = o.str("Name"),
            markerType = o.str("MarkerType"),
            imageTag = o.strOrNull("ImageTag"),
            imagePath = o.strOrNull("ImagePath"),
            index = o.int("ChapterIndex", position),
        )
    }
}

@Immutable
data class MediaSource(
    val id: String,
    val name: String,
    val path: String,
    val container: String,
    val directStreamUrl: String?,
    val runTimeTicks: Long,
    val size: Long,
    val bitrate: Long,
    val streams: List<MediaStream>,
    val defaultAudioIndex: Int?,
    val defaultSubtitleIndex: Int?,
    val chapters: List<Chapter>,
    val raw: JsonObject,
) {
    val video: MediaStream? get() = streams.firstOrNull { it.type == "Video" }
    val audio: List<MediaStream> get() = streams.filter { it.type == "Audio" }
    val subtitles: List<MediaStream> get() = streams.filter { it.type == "Subtitle" }

    /** Short slot label like the TV version button: "4K HDR" / "1080p". */
    /**
     * 用户口径（2026-11-28，#139）**媒体信息要显示完整的动态范围标识**。
     *
     * 用户原话：
     * ```
     * 「hills 的媒体信息显示他是有 HDR10 的这种信息的」
     * 「咱们那个没有」
     * 「不光要加上 HDR10，有的还是杜比视界要加 DV，
     *   反正就是我的意思你这个显示不全」
     * ```
     *
     * ## 原来的 bug：读错字段
     *
     * ```kotlin
     * v.videoRangeType.takeIf { it.isNotEmpty() && it != "SDR" }
     * ```
     * 读的是 `VideoRangeType`。但实测 ETKN 返回的视频流里：
     * ```
     * VideoRange     = "HDR10"      ← 有值
     * VideoRangeType = null/空       ← 读的这个，永远是空
     * ColorPrimaries = "bt2020"
     * ColorTransfer  = "smpte2084"   ← PQ，HDR10 特征
     * BitDepth       = 10
     * ```
     * → 拼出来只有 `4K`，HDR 标识永远不出现。
     *
     * ## 现在的顺序：三档回退，谁都拿不到才不显示
     *
     * ```
     * ① VideoRangeType   —— 服务端有时会给更细的值（DolbyVision/HDR10Plus…）
     * ② VideoRange       —— HDR10 / HLG / DolbyVision / SDR
     * ③ 从 ColorTransfer 反推 —— smpte2084→HDR10、arib-std-b67→HLG
     *                       （极少数老数据只有色彩字段）
     * ```
     * `SDR` 不显示（普通片不该挂标识，只有 HDR/DV 才值得标出来）。
     *
     * `ExtendedVideoType` / `ExtendedVideoSubType` 也一起认 ——
     * 那是 Emby 系服务端标杜比视界（含 profile 8.1 这类细分）的字段。
     */
    val slotLabel: String
        get() = name.ifEmpty {
            val v = video ?: return container.uppercase()
            listOfNotNull(resolutionLabel(v.width, v.height), v.dynamicRangeLabel())
                .joinToString(" ")
        }

    companion object {
        fun from(o: JsonObject) = MediaSource(
            id = o.str("Id"),
            name = o.str("Name"),
            path = o.str("Path"),
            container = o.str("Container"),
            directStreamUrl = o.strOrNull("DirectStreamUrl"),
            runTimeTicks = o.long("RunTimeTicks"),
            size = o.long("Size"),
            bitrate = o.long("Bitrate"),
            streams = o.objects("MediaStreams").map(MediaStream::from),
            defaultAudioIndex = o.intOrNull("DefaultAudioStreamIndex"),
            defaultSubtitleIndex = o.intOrNull("DefaultSubtitleStreamIndex"),
            chapters = o.objects("Chapters").mapIndexed { i, c -> Chapter.from(c, i) },
            raw = o,
        )
    }
}

fun resolutionLabel(width: Int, height: Int): String? = when {
    width >= 3800 || height >= 2100 -> "4K"
    width >= 2500 || height >= 1400 -> "2K"
    width >= 1900 || height >= 1000 -> "1080p"
    width >= 1260 || height >= 700 -> "720p"
    width > 0 -> "${height}p"
    else -> null
}

/**
 * Unified media item (Emby-style BaseItem subset). Also used for music groups returned by
 * `U/Music` (albums / artists / folders), which carry `Kind`, `Key` and `ArtworkHash`.
 */
@Immutable
data class Item(
    val id: String,
    val name: String,
    val type: String,
    val collectionType: String?,
    val sourceType: String?,
    val overview: String?,
    val taglines: List<String>,
    val productionYear: Int?,
    val premiereDate: String?,
    /** 用户口径（2026-11-27，#34）服务端 `DateCreated`，音乐按添加时间排序要用。 */
    val dateCreated: String?,
    val endDate: String?,
    val officialRating: String?,
    val communityRating: Double?,
    val criticRating: Double?,
    val tmdbRating: Double?,
    val doubanRating: Double?,
    val imdbRating: Double?,
    val runTimeTicks: Long,
    val genres: List<String>,
    val studios: List<String>,
    val seriesId: String?,
    val seriesName: String?,
    val seasonId: String?,
    val seasonName: String?,
    val parentIndexNumber: Int?,
    val indexNumber: Int?,
    val indexNumberEnd: Int?,
    val imageTags: Map<String, String>,
    val backdropTags: List<String>,
    val parentBackdropItemId: String?,
    val parentBackdropTags: List<String>,
    val parentLogoItemId: String?,
    val parentLogoTag: String?,
    val parentThumbItemId: String?,
    val parentThumbTag: String?,
    val seriesPrimaryTag: String?,
    val primaryAspectRatio: Double?,
    val userData: UserData,
    val people: List<PersonRef>,
    val mediaSources: List<MediaSource>,
    val childCount: Int?,
    val recursiveItemCount: Int?,
    val providerIds: Map<String, String>,
    val isFolder: Boolean,
    val locationType: String?,
    val isMissing: Boolean,
    val status: String?,
    /** Trailer links from the metadata (`RemoteTrailers[].Url`, usually YouTube); detail only. */
    val trailers: List<String> = emptyList(),
    val artists: List<String>,
    val album: String?,
    val albumKey: String?,
    val kind: String?,
    val key: String?,
    val artworkHash: String?,
    val artistImage: JsonObject?,
    val count: Int?,
    val chapters: List<Chapter>,
) {
    val isSeries get() = type == "Series"
    val isSeason get() = type == "Season"
    val isEpisode get() = type == "Episode"
    val isMovie get() = type == "Movie"
    val isAudio get() = type == "Audio"
    val isMusicGroup get() = !kind.isNullOrEmpty()
    /** A library group (分组) as `Views` lists it in place of its member libraries: `library-group-N`, mixed type. */
    val isLibraryGroup get() = sourceType == "group" || id.startsWith("library-group-")
    val isPlayable get() = type in PLAYABLE_TYPES

    /** A category card from `U/Facets` (name = value to browse by, [kind] = its field). */
    val isFacet get() = type == "TvFacet"

    val runtimeMs: Long get() = runTimeTicks / TICKS_PER_MS
    val positionMs: Long get() = userData.playbackPositionTicks / TICKS_PER_MS
    val progress: Float
        get() = when {
            runTimeTicks > 0 && userData.playbackPositionTicks > 0 ->
                (userData.playbackPositionTicks.toFloat() / runTimeTicks).coerceIn(0f, 1f)
            else -> ((userData.playedPercentage ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
        }

    /**
     * 用户口径（2026-11-23）：剧集看过的比例，用来画首页角标的进度环。
     * 实测 ETKN 对 Series 的 PlayedPercentage 恒为 null（服务端不给），
     * 但会给 UnplayedItemCount 和 RecursiveItemCount（总集数）。
     * 所以：已看 = 总集数 - 未看集数，比例 = 已看 / 总集数。
     * 服务端将来真给了 PlayedPercentage 就用它，优先更准的那个。
     */
    val watchedFraction: Float
        get() {
            userData.playedPercentage?.let { return (it / 100.0).toFloat().coerceIn(0f, 1f) }
            val total = recursiveItemCount ?: 0
            val unplayed = userData.unplayedItemCount ?: return 0f
            if (total <= 0) return 0f
            return ((total - unplayed).toFloat() / total).coerceIn(0f, 1f)
        }

    val bestRating: Double? get() = tmdbRating ?: doubanRating ?: communityRating ?: imdbRating

    val episodeCode: String?
        get() {
            val s = parentIndexNumber ?: return null
            val e = indexNumber ?: return null
            return if (indexNumberEnd != null && indexNumberEnd > e) "S${s}E$e-$indexNumberEnd" else "S${s}E$e"
        }

    /** 第1季第3集 (第1季第3-4集 for a double episode). */
    val episodeLabel: String?
        get() {
            val s = parentIndexNumber ?: return null
            val e = indexNumber ?: return null
            return if (indexNumberEnd != null && indexNumberEnd > e) "第${s}季第$e-${indexNumberEnd}集" else "第${s}季第${e}集"
        }

    /** Series status in words; the server passes TMDb's (Returning Series, Ended, …) or Emby's (Continuing). */
    val statusLabel: String?
        get() = when (status?.trim()?.lowercase()) {
            null, "" -> null
            "continuing", "returning series" -> "连载中"
            "ended" -> "已完结"
            "canceled", "cancelled" -> "已取消"
            "in production" -> "制作中"
            "planned" -> "筹备中"
            "pilot" -> "试播"
            else -> status
        }

    /** Every score the server sent, by source, for the ratings line. */
    val ratings: List<Rating>
        get() = listOf(
            RatingSource.Tmdb to tmdbRating,
            RatingSource.Imdb to imdbRating,
            RatingSource.Douban to doubanRating,
            RatingSource.RottenTomatoes to criticRating,
        ).mapNotNull { (source, score) -> score?.takeIf { it > 0 }?.let { Rating(source, it) } }

    val artistLine: String get() = artists.joinToString(" / ")

    fun withUserData(update: JsonObject): Item = copy(userData = userData.merge(update))

    companion object {
        private val PLAYABLE_TYPES = setOf("Movie", "Episode", "Video", "MusicVideo", "Audio", "Trailer")

        fun from(o: JsonObject): Item = Item(
            id = o.str("Id"),
            name = o.str("Name").ifEmpty { o.str("title").ifEmpty { o.str("name") } },
            type = o.str("Type"),
            collectionType = o.strOrNull("CollectionType"),
            sourceType = o.strOrNull("SourceType"),
            overview = o.strOrNull("Overview"),
            taglines = o.strList("Taglines"),
            productionYear = o.intOrNull("ProductionYear"),
            premiereDate = o.strOrNull("PremiereDate"),
            dateCreated = o.strOrNull("DateCreated"),
            endDate = o.strOrNull("EndDate"),
            officialRating = o.strOrNull("OfficialRating"),
            communityRating = o.double("CommunityRating"),
            criticRating = o.double("CriticRating"),
            tmdbRating = o.double("TMDbRating"),
            doubanRating = o.double("DoubanRating"),
            imdbRating = o.double("IMDbRating"),
            runTimeTicks = o.long("RunTimeTicks"),
            genres = o.strList("Genres"),
            studios = o.objects("Studios").map { it.str("Name") }.filter(String::isNotEmpty),
            seriesId = o.strOrNull("SeriesId"),
            seriesName = o.strOrNull("SeriesName"),
            seasonId = o.strOrNull("SeasonId"),
            seasonName = o.strOrNull("SeasonName"),
            parentIndexNumber = o.intOrNull("ParentIndexNumber"),
            indexNumber = o.intOrNull("IndexNumber"),
            indexNumberEnd = o.intOrNull("IndexNumberEnd"),
            imageTags = o.strMap("ImageTags"),
            backdropTags = o.strList("BackdropImageTags").ifEmpty { listOfNotNull(o.strOrNull("BackdropImageTag")) },
            parentBackdropItemId = o.strOrNull("ParentBackdropItemId"),
            parentBackdropTags = o.strList("ParentBackdropImageTags"),
            parentLogoItemId = o.strOrNull("ParentLogoItemId"),
            parentLogoTag = o.strOrNull("ParentLogoImageTag"),
            parentThumbItemId = o.strOrNull("ParentThumbItemId"),
            parentThumbTag = o.strOrNull("ParentThumbImageTag"),
            seriesPrimaryTag = o.strOrNull("SeriesPrimaryImageTag"),
            primaryAspectRatio = o.double("PrimaryImageAspectRatio"),
            userData = UserData.from(o.obj("UserData")),
            people = o.objects("People").map(PersonRef::from),
            mediaSources = o.objects("MediaSources").map(MediaSource::from),
            childCount = o.intOrNull("ChildCount"),
            recursiveItemCount = o.intOrNull("RecursiveItemCount"),
            providerIds = o.strMap("ProviderIds"),
            isFolder = o.bool("IsFolder"),
            locationType = o.strOrNull("LocationType"),
            isMissing = o.bool("IsMissing") || o.str("LocationType") == "Virtual",
            status = o.strOrNull("Status"),
            trailers = o.objects("RemoteTrailers").map { it.str("Url") }.filter { it.startsWith("http") }.distinct(),
            artists = o.strList("Artists").ifEmpty { listOfNotNull(o.strOrNull("Artist")) },
            album = o.strOrNull("Album"),
            albumKey = o.strOrNull("AlbumKey"),
            kind = o.strOrNull("Kind"),
            key = o.strOrNull("Key"),
            artworkHash = o.strOrNull("ArtworkHash"),
            artistImage = o.obj("ArtistImage"),
            count = o.intOrNull("Count"),
            chapters = o.objects("Chapters").mapIndexed { i, c -> Chapter.from(c, i) },
        )
    }
}

@Immutable
data class ItemsPage(val items: List<Item>, val total: Int, val nextCursor: String? = null)

@Immutable
data class EpisodePage(
    val items: List<Item>,
    val startIndex: Int,
    val nextStartIndex: Int,
    val total: Int,
)

@Immutable
data class UserInfo(
    val id: String,
    val name: String,
    val isAdmin: Boolean,
    val privateAccount: Boolean,
    val canRefreshMetadata: Boolean,
    val canDeleteMedia: Boolean,
    val imageTag: String?,
) {
    companion object {
        fun from(o: JsonObject, topLevelPrivate: Boolean? = null) = UserInfo(
            id = o.str("Id"),
            name = o.str("Name"),
            isAdmin = o.obj("Policy")?.bool("IsAdministrator") ?: false,
            privateAccount = topLevelPrivate ?: o.bool("PrivateAccount"),
            canRefreshMetadata = o.obj("TvPermissions")?.bool("CanRefreshMetadata") ?: false,
            canDeleteMedia = o.obj("TvPermissions")?.bool("CanDeleteMedia") ?: false,
            imageTag = o.strOrNull("PrimaryImageTag"),
        )
    }
}

@Immutable
data class PlaybackInfo(
    val playSessionId: String,
    val sources: List<MediaSource>,
    /** null when the server does not advertise danmaku at all. */
    val danmakuAvailable: Boolean? = null,
)

@Immutable
data class ServerIdentity(val url: String, val name: String, val id: String)

/** Root of `U/Music?Tab=recommended`. */
@Immutable
data class MusicHome(val latest: List<Item>, val recent: List<Item>, val frequent: List<Item>)

@Immutable
data class MusicPage(
    val items: List<Item>,
    val folders: List<Item>,
    val total: Int,
    val title: String,
    val artistOverview: String?,
)

@Immutable
data class Playlist(val key: String, val name: String, val count: Int)

fun JsonObject.items(): List<Item> = rows().map(Item::from)

/** Helper for arrays that are not under one of the standard list keys. */
fun JsonObject.itemList(key: String): List<Item> = (arr(key) ?: return emptyList()).mapNotNull { it as? JsonObject }.map(Item::from)

enum class RatingSource(val label: String) { Tmdb("TMDb"), Imdb("IMDb"), Douban("豆瓣"), RottenTomatoes("烂番茄") }

/** One source's score: out of 10, or for Rotten Tomatoes the Tomatometer percentage. */
@Immutable
data class Rating(val source: RatingSource, val score: Double) {
    val text: String get() = if (source == RatingSource.RottenTomatoes) "${score.toInt()}%" else "%.1f".format(Locale.ROOT, score)

    /** Tomatometer 60 % and up is "fresh". */
    val fresh: Boolean get() = score >= 60
}

/** Titles in the account's libraries: films, series and their episodes. */
@Immutable
data class LibraryCounts(val movies: Int, val series: Int, val episodes: Int)
