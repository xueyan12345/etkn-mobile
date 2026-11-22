package xyz.etkn.mobile.data.api

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.etkn.mobile.data.discover.CloudProvider
import xyz.etkn.mobile.data.discover.CloudResource
import xyz.etkn.mobile.data.discover.CloudResources
import xyz.etkn.mobile.data.discover.DiscoverItem
import xyz.etkn.mobile.data.discover.DiscoverKind
import xyz.etkn.mobile.data.discover.DiscoverPage
import xyz.etkn.mobile.data.discover.DiscoverPerson
import xyz.etkn.mobile.data.discover.DiscoverSeason
import xyz.etkn.mobile.data.discover.PersonMatch
import xyz.etkn.mobile.data.events.AcquireStatus
import xyz.etkn.mobile.data.events.IngestPage
import xyz.etkn.mobile.data.model.EpisodePage
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.ItemsPage
import xyz.etkn.mobile.data.model.LibraryCounts
import xyz.etkn.mobile.data.model.MediaSource
import xyz.etkn.mobile.data.model.MusicHome
import xyz.etkn.mobile.data.model.MusicPage
import xyz.etkn.mobile.data.model.PlaybackInfo
import xyz.etkn.mobile.data.model.Playlist
import xyz.etkn.mobile.data.model.UserInfo
import xyz.etkn.mobile.data.model.itemList
import xyz.etkn.mobile.data.model.items
import xyz.etkn.mobile.data.net.ApiClient
import xyz.etkn.mobile.data.net.Credentials
import xyz.etkn.mobile.data.net.EtknException
import xyz.etkn.mobile.data.net.bool
import xyz.etkn.mobile.data.net.enc
import xyz.etkn.mobile.data.net.int
import xyz.etkn.mobile.data.net.long
import xyz.etkn.mobile.data.net.obj
import xyz.etkn.mobile.data.net.objects
import xyz.etkn.mobile.data.net.query
import xyz.etkn.mobile.data.net.rows
import xyz.etkn.mobile.data.net.str
import xyz.etkn.mobile.data.net.strOrNull
import xyz.etkn.mobile.data.session.ActiveSession
import xyz.etkn.mobile.player.danmaku.DanmakuPage
import xyz.etkn.mobile.player.danmaku.DanmakuTimeline

/** Sort keys exposed by the TV poster wall; ratings are separate server fields. */
enum class SortKey(val wire: String, val label: String) {
    DateCreated("DateCreated", "最新入库"),
    PremiereDate("PremiereDate", "首映日期"),
    SortName("SortName", "名称"),
    Tmdb("TMDbRating", "TMDb 评分"),
    Douban("DoubanRating", "豆瓣评分"),
    Imdb("IMDbRating", "IMDb 评分"),
    Critic("CriticRating", "烂番茄"),
    Year("ProductionYear", "年份"),
    Played("DatePlayed", "最近播放"),
    Random("Random", "随机"),
}

data class LibraryQuery(
    val parentId: String,
    val includeTypes: String?,
    val sort: SortKey = SortKey.DateCreated,
    val descending: Boolean = true,
    val favoritesOnly: Boolean = false,
    val genre: String? = null,
)

/**
 * Typed wrapper over the ETKN TV-dedicated routes (`/api/tv`). Every call is bound to the
 * session it was issued with; callers compare [ActiveSession.generation] to drop stale results.
 */
class EtknApi(
    private val client: ApiClient,
    private val session: () -> ActiveSession,
    private val onExpired: (ActiveSession) -> Unit,
) {

    private val s get() = session()
    private fun cred(a: ActiveSession = s) = Credentials(a.server, a.token)
    private fun u(a: ActiveSession = s) = "/api/tv/Users/${enc(a.userId)}"

    private suspend fun send(method: String, path: String, body: JsonElement? = null, raw: RequestBody? = null): JsonObject {
        val active = s
        try {
            return client.call(cred(active), method, path, body, raw)
        } catch (expired: EtknException.AuthExpired) {
            onExpired(active)
            throw expired
        }
    }

    suspend fun get(path: String): JsonObject = send("GET", path)
    suspend fun post(path: String, body: JsonElement = EMPTY): JsonObject = send("POST", path, body)
    suspend fun put(path: String, body: JsonElement): JsonObject = send("PUT", path, body)
    suspend fun delete(path: String): JsonObject = send("DELETE", path)

    // ---------- Account ----------

    suspend fun authenticate(server: String, username: String, password: String): Pair<String, UserInfo> {
        val body = buildJsonObject { put("Username", username); put("Pw", password) }
        val result = client.call(Credentials(server, ""), "POST", "/api/tv/Users/AuthenticateByName", body)
        val token = result.str("AccessToken")
        val user = result.obj("User") ?: throw EtknException.BadResponse(200)
        if (token.isEmpty()) throw EtknException.BadResponse(200)
        return token to UserInfo.from(user, topLevelPrivate = result.bool("PrivateAccount"))
    }

    suspend fun me(server: String, token: String): UserInfo =
        UserInfo.from(client.call(Credentials(server, token), "GET", "/api/tv/Users/Me"))

    suspend fun rememberAdmin(server: String, token: String): Boolean {
        val value = client.call(Credentials(server, token), "GET", "/api/tv/Users/Me/AccountPreferences")
        return value.bool("remember_admin_account")
    }

    suspend fun logout(a: ActiveSession) {
        runCatching { client.call(cred(a), "POST", "/api/tv/Sessions/Logout", EMPTY) }
    }

    suspend fun clientPreferences(): JsonObject = get("/api/tv/Users/Me/ClientPreferences")

    /** Audio / subtitle language priority (null = follow the server-wide default) or hide-watched-latest; one key per call. */
    suspend fun savePlaybackPreference(key: String, value: JsonElement) {
        post("/api/tv/Users/Me/PlaybackPreferences", buildJsonObject { put(key, value) })
    }

    /** New, replaced or extra versions since [after] (empty = start a cursor). */
    suspend fun ingestUpdates(after: String): IngestPage =
        IngestPage.from(get(query("${u()}/IngestUpdates", "After" to after.ifEmpty { null })))

    suspend fun acquireStatus(runId: Long): AcquireStatus = AcquireStatus.from(get("$D/acquire-status/$runId"))

    // ---------- Home ----------

    suspend fun views(): List<Item> = get("${u()}/Views").items()

    /**
     * Every library the user can see. `Views` lists a library group (分组) in place of its members,
     * so anything looking for libraries by type (music, the newest shelves) asks here, where each
     * group is opened into its libraries in place.
     */
    suspend fun libraries(): List<Item> = coroutineScope {
        views().map { view ->
            async { if (view.isLibraryGroup) children(view.id) else listOf(view) }
        }.flatMap { it.await() }
    }

    suspend fun resume(limit: Int = 24): List<Item> =
        get(query("${u()}/Items/Resume", "Limit" to limit, "Fields" to "Overview")).items()

    suspend fun latest(parentId: String, includeTypes: String?, limit: Int = 24): List<Item> =
        get(query("${u()}/Items/Latest", "ParentId" to parentId, "IncludeItemTypes" to includeTypes, "Limit" to limit)).items()

    suspend fun recommendations(seed: String, cursor: String?, limit: Int = 1): ItemsPage {
        val path = "${u()}/Items/PersonalRecommendations?Stream=true&Limit=$limit&Fields=Overview" +
            "&ShuffleSeed=${enc(seed)}&Cursor=${enc(cursor ?: "")}"
        val result = get(path)
        val next = result["NextCursor"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return ItemsPage(result.items(), 0, next)
    }

    // ---------- Library ----------

    suspend fun libraryItems(q: LibraryQuery, start: Int, limit: Int): ItemsPage {
        val path = query(
            "${u()}/Items",
            "Recursive" to true,
            "ParentId" to q.parentId,
            "IncludeItemTypes" to q.includeTypes,
            "Limit" to limit,
            "StartIndex" to start,
            "SortBy" to q.sort.wire,
            "SortOrder" to if (q.sort == SortKey.Random) "Ascending" else if (q.descending) "Descending" else "Ascending",
            "Fields" to LIST_FIELDS,
            "Filters" to if (q.favoritesOnly) "IsFavorite" else null,
            "Genres" to q.genre,
        )
        val result = get(path)
        val items = result.items()
        return ItemsPage(items, result.int("TotalRecordCount", start + items.size))
    }

    suspend fun children(parentId: String): List<Item> = get(query("${u()}/Items", "ParentId" to parentId)).items()

    /** TMDb collections with a member in this library; members list through [children]. */
    suspend fun libraryCollections(libraryId: String): List<Item> =
        get(query("${u()}/Libraries/${enc(libraryId)}/Collections", "Limit" to 200)).items()

    /** Collections a movie belongs to (empty for anything else). */
    suspend fun itemCollections(itemId: String): List<Item> =
        get(query("${u()}/Items/${enc(itemId)}/Collections", "Limit" to 60)).items()

    /** Category cards of a library: [kind] `Types` (genres and tags), `Networks` or `Companies`. */
    suspend fun facets(libraryId: String, kind: String): List<Item> =
        get(query("${u()}/Facets", "ParentId" to libraryId, "Kind" to kind)).items()

    /** Titles of one category card ([kind] is the card's own `Kind`: Genres, Tags, Networks, Companies). */
    suspend fun facetItems(libraryId: String, kind: String, value: String, sort: SortKey, descending: Boolean, start: Int, limit: Int): ItemsPage {
        val result = get(
            query(
                "${u()}/Browse",
                "ParentId" to libraryId, kind to value, "Limit" to limit, "StartIndex" to start,
                "SortBy" to sort.wire, "SortOrder" to if (sort == SortKey.Random || !descending) "Ascending" else "Descending",
                "Fields" to LIST_FIELDS,
            ),
        )
        val items = result.items()
        return ItemsPage(items, result.int("TotalRecordCount", start + items.size))
    }

    /** Generates (genres, tags) or fetches the logo of (networks, companies) one category card; admins and 刷新元数据 accounts. */
    suspend fun generateFacetArtwork(libraryId: String, kind: String, value: String) {
        post(query("${u()}/FacetArtwork", "ParentId" to libraryId, "Kind" to kind, "Value" to value, "Action" to if (kind == "Networks" || kind == "Companies") "icon" else null))
    }

    /** Re-scans a path / list library for new files (administrators). */
    suspend fun scanLibrary(libraryId: String) {
        post("/api/tv/Items/${enc(libraryId)}/Scan")
    }

    suspend fun favorites(start: Int, limit: Int): ItemsPage {
        val path = query(
            "${u()}/Browse",
            "Limit" to limit, "StartIndex" to start, "SortBy" to "DateCreated", "SortOrder" to "Descending",
            "Filters" to "IsFavorite",
        )
        val result = get(path)
        val items = result.items()
        return ItemsPage(items, result.int("TotalRecordCount", start + items.size))
    }

    /**
     * Favourite collections. `Filters=IsFavorite` never returns BoxSets (the server keeps them out
     * of item-state queries), but each collection's `UserData.IsFavorite` is real, so read the
     * collection list and keep the favourites.
     */
    suspend fun favoriteCollections(): List<Item> =
        get(query("${u()}/Items", "Recursive" to true, "IncludeItemTypes" to "BoxSet", "SortBy" to "SortName", "Limit" to 1000))
            .items().filter { it.userData.isFavorite }

    /**
     * What the account's libraries hold, by kind. Counts only: with `Limit=0` and no sort the
     * server answers from its count path without loading any item.
     */
    suspend fun libraryCounts(): LibraryCounts = coroutineScope {
        fun count(type: String) = async {
            get(query("${u()}/Items", "Recursive" to true, "IncludeItemTypes" to type, "Limit" to 0)).int("TotalRecordCount", 0)
        }
        val movies = count("Movie")
        val series = count("Series")
        val episodes = count("Episode")
        LibraryCounts(movies.await(), series.await(), episodes.await())
    }

    suspend fun search(term: String, start: Int, limit: Int): ItemsPage {
        val path = query(
            "${u()}/Items",
            "Recursive" to true, "IncludeItemTypes" to "Movie,Series", "Limit" to limit, "StartIndex" to start,
            "SortBy" to "SortName", "Fields" to "PrimaryImageAspectRatio,Overview,ProductionYear,CommunityRating,TMDbRating,UserData",
            "SearchTerm" to term,
        )
        val result = get(path)
        val items = result.items()
        return ItemsPage(items, result.int("TotalRecordCount", start + items.size))
    }

    /**
     * Albums whose name or artist matches (pinyin too), across every music library the user sees:
     * `U/Music` without `ParentId` reads all of them, groups included.
     */
    suspend fun searchMusic(term: String, start: Int, limit: Int): ItemsPage {
        val result = get(
            query(
                "${u()}/Music", "Tab" to "albums", "SearchTerm" to term,
                "SortBy" to "Name", "SortOrder" to "Ascending", "StartIndex" to start, "Limit" to limit,
            ),
        )
        val items = result.items()
        return ItemsPage(items, result.int("TotalRecordCount", start + items.size))
    }

    /**
     * Music videos matching [term]: the server files them as `Video` items flagged `MusicVideo`, and
     * no query narrows to that flag, so one generous page of videos is filtered here.
     */
    suspend fun searchMusicVideos(term: String): ItemsPage {
        val result = get(
            query(
                "${u()}/Items", "Recursive" to true, "IncludeItemTypes" to "Video", "SearchTerm" to term,
                "SortBy" to "SortName", "Limit" to MV_SEARCH_LIMIT, "Fields" to "PrimaryImageAspectRatio,ProductionYear,UserData",
            ),
        )
        val items = result.rows().filter { it.bool("MusicVideo") }.map(Item::from)
        return ItemsPage(items, items.size)
    }

    // ---------- Detail ----------

    suspend fun item(id: String, fields: String? = null): Item =
        Item.from(get(query("${u()}/Items/${enc(id)}", "Fields" to fields)))

    suspend fun similar(id: String): List<Item> =
        get(query("/api/tv/Items/${enc(id)}/Similar", "UserId" to s.userId, "Limit" to 20)).items()

    suspend fun person(id: String): Item = Item.from(get(query("/api/tv/Persons/${enc(id)}", "UserId" to s.userId)))

    suspend fun personItems(id: String, start: Int, limit: Int = 40): ItemsPage {
        val result = get(
            query(
                "/api/tv/Persons/${enc(id)}/Items",
                "UserId" to s.userId, "IncludeItemTypes" to "Movie,Series", "StartIndex" to start, "Limit" to limit,
            ),
        )
        val items = result.items()
        return ItemsPage(items, result.int("TotalRecordCount", start + items.size))
    }

    suspend fun seasons(seriesId: String): List<Item> =
        get(query("/api/tv/Shows/${enc(seriesId)}/Seasons", "UserId" to s.userId)).items()

    suspend fun playbackTarget(seriesId: String, seasonId: String? = null): Item? =
        get(
            query(
                "/api/tv/Shows/${enc(seriesId)}/PlaybackTarget",
                "UserId" to s.userId, "Fields" to "MediaSources,MediaStreams,Overview", "SeasonId" to seasonId,
            ),
        ).items().firstOrNull()

    suspend fun episodePage(
        seriesId: String,
        seasonId: String?,
        start: Int? = null,
        anchorId: String? = null,
        limit: Int = 40,
    ): EpisodePage {
        val result = get(
            query(
                "/api/tv/Shows/${enc(seriesId)}/EpisodePage",
                "UserId" to s.userId, "Limit" to limit, "SeasonId" to seasonId,
                "AnchorId" to anchorId, "StartIndex" to start,
            ),
        )
        val items = result.items()
        val startIndex = result.int("StartIndex", start ?: 0)
        return EpisodePage(
            items = items,
            startIndex = startIndex,
            nextStartIndex = result.int("NextStartIndex", startIndex + items.size),
            total = result.int("TotalRecordCount", startIndex + items.size),
        )
    }

    suspend fun nextEpisode(itemId: String): Item? =
        get(query("/api/tv/Items/${enc(itemId)}/NextEpisode", "UserId" to s.userId)).items().firstOrNull()

    suspend fun setFavorite(id: String, favorite: Boolean): JsonObject {
        val path = "${u()}/FavoriteItems/${enc(id)}"
        return if (favorite) post(path) else delete(path)
    }

    suspend fun setPlayed(id: String, played: Boolean): JsonObject {
        val path = "${u()}/PlayedItems/${enc(id)}"
        return if (played) post(path) else delete(path)
    }

    suspend fun hideFromResume(id: String) {
        post("${u()}/Items/${enc(id)}/HideFromResume")
    }

    // ---------- Management (permissions: admin, or TvPermissions per action) ----------

    /** The account as the server sees it now (permissions can change while signed in). */
    suspend fun currentUser(): UserInfo = UserInfo.from(get("/api/tv/Users/Me"))

    /** Raw detail JSON (Tags, CustomRating, DoubanId, Versions …) for the editors. */
    suspend fun rawItem(id: String): JsonObject = get("${u()}/Items/${enc(id)}")

    /** `action`: replace_metadata / replace_images / replace_metadata_images. Reply may carry `RefreshRunId` or `reason`. */
    suspend fun refreshItem(id: String, action: String): JsonObject = post(query("/api/tv/Items/${enc(id)}/Refresh", "Action" to action))

    /** `{RefreshRunId, status, finished}` for a run started by [refreshItem]. */
    suspend fun refreshStatus(id: String, runId: Long): JsonObject = get("/api/tv/Items/${enc(id)}/Refresh/$runId")

    /** Linked delete of every version (and seasons / episodes below a series). */
    suspend fun deleteItem(id: String) {
        delete("/api/tv/Items/${enc(id)}")
    }

    suspend fun deleteVersion(id: String, versionId: String) {
        delete("/api/tv/Items/${enc(id)}/Versions/${enc(versionId)}")
    }

    /** Saves a subtitle calibration for this version (Web and TV apply it too); 0 removes it. */
    suspend fun saveSubtitleOffset(itemId: String, mediaSourceId: String, key: String, offsetMs: Int) {
        post(
            "/api/tv/Items/${enc(itemId)}/${enc(mediaSourceId)}/SubtitleOffsets",
            buildJsonObject {
                put("SubtitleKey", key)
                put("OffsetMs", offsetMs)
            },
        )
    }

    /** Seek-thumbnail manifest of one version (empty object when none were extracted). */
    suspend fun previewThumbnails(itemId: String, mediaSourceId: String): JsonObject =
        get(query("/api/tv/Items/${enc(itemId)}/PreviewThumbnails", "MediaSourceId" to mediaSourceId))

    /** Queues seek-preview thumbnail extraction; titles that already have them are skipped. */
    suspend fun extractPreviews(ids: List<String>) {
        post("/api/tv/Manage/Items/preview-thumbnails", buildJsonObject { put("media_item_ids", stringArray(ids)) })
    }

    // ---------- Library maintenance (Web 媒体库卡片菜单) ----------

    /** Administrators: regenerates the library cover now; the server's error when it could not. */
    suspend fun generateLibraryCover(libraryId: String) {
        val result = post("/api/tv/Manage/Libraries/GenerateCover", libraries(libraryId))
        val failed = result.objects("items").firstOrNull { !it.bool("generated") }
        if (failed != null) error(failed.str("error").ifEmpty { "封面生成失败" })
    }

    /** Administrators: queues seek-preview extraction for the whole library (existing ones are skipped). */
    suspend fun extractLibraryPreviews(libraryId: String) {
        post("/api/tv/Manage/Libraries/PreviewThumbnails", libraries(libraryId))
    }

    /** Administrators and 刷新元数据 accounts; [action] as in [refreshItem]. False when there was nothing to refresh. */
    suspend fun refreshLibrary(libraryId: String, action: String): Boolean {
        val result = post("/api/tv/Manage/Libraries/Refresh", buildJsonObject {
            put("library_ids", stringArray(listOf(libraryId)))
            put("action", action)
        })
        check(result.str("reason") != "task_already_running") { "已有刷新任务正在运行，请稍后重试" }
        return result.bool("submitted")
    }

    private fun libraries(libraryId: String) = buildJsonObject { put("library_ids", stringArray(listOf(libraryId))) }

    /** Admin only: `{items: [{label, value}]}` for the custom rating picker. */
    suspend fun ratingOptions(): List<Pair<String, String>> =
        get("/api/tv/Manage/RatingOptions").objects("items").map { it.str("label") to it.str("value") }.filter { it.second.isNotEmpty() }

    /** Admin only. Body fields: name, overview, genres, tags, custom_rating, douban_id, tmdb_rating, douban_rating. */
    suspend fun updateMetadata(id: String, body: JsonObject) {
        put("/api/tv/Items/${enc(id)}/Metadata", body)
    }

    /** Admin only: online candidates `{type, url, thumbnail_url, source_url, source_provider, language, width, height}`. */
    suspend fun remoteImages(id: String, allLanguages: Boolean): List<JsonObject> =
        get(query("/api/tv/Items/${enc(id)}/RemoteImages", "include_all_languages" to allLanguages)).objects("items")

    suspend fun selectImage(id: String, candidate: JsonObject, type: String) {
        val body = buildJsonObject {
            put("url", candidate.str("source_url"))
            put("image_type", type.lowercase())
            put("source_provider", candidate.str("source_provider").ifEmpty { "tmdb" })
            candidate.strOrNull("language")?.let { put("language", it) }
        }
        post("/api/tv/Items/${enc(id)}/RemoteImages", body)
    }

    /** Raw image bytes as the request body; `type` is Primary / Backdrop / Logo. */
    suspend fun uploadImage(id: String, type: String, fileName: String, mime: String, bytes: ByteArray) {
        send(
            "POST",
            query("/api/tv/Items/${enc(id)}/Images/Upload", "image_type" to type, "file_name" to fileName),
            raw = bytes.toRequestBody(mime.toMediaType()),
        )
    }

    // ---------- Playback ----------

    suspend fun playbackInfo(itemId: String, audio: Boolean = false): PlaybackInfo {
        val path = if (audio) "/api/tv/Items/${enc(itemId)}/PlaybackInfo"
        else query("/api/tv/Items/${enc(itemId)}/PlaybackInfo", "UserId" to s.userId)
        val result = post(path)
        return PlaybackInfo(
            result.str("PlaySessionId"),
            result.objects("MediaSources").map(MediaSource::from),
            danmakuAvailable = result.obj("Danmaku")?.bool("Available"),
        )
    }

    /** `event` is "" (started), "Progress" or "Stopped". The reply may carry a fresh `Danmaku` state. */
    suspend fun report(event: String, payload: JsonObject, a: ActiveSession = s): JsonObject {
        val path = "/api/tv/Sessions/Playing" + if (event.isEmpty()) "" else "/$event"
        return client.call(cred(a), "POST", path, payload)
    }

    // ---------- Discover (TMDb ids; `D` = /api/tv/discover) ----------

    /** Discovery is a per-account right; the server enforces it again on every `D` route. */
    suspend fun canDiscover(): Boolean = get("/api/tv/auth/check_status").obj("user")?.bool("can_access_discovery") == true

    suspend fun discoverOptions(kind: DiscoverKind): JsonObject = get(query("$D/options", "media_type" to kind.wire))

    suspend fun discoverQuery(kind: DiscoverKind, body: JsonObject): DiscoverPage {
        val result = post("$D/query/${kind.wire}", body)
        return DiscoverPage(result.rows().map { DiscoverItem(it, kind) }, result.int("total_pages", 1).coerceIn(1, 500))
    }

    /** TMDb people by name for the actor / director filters: label = name, value = person id. */
    suspend fun discoverPeople(name: String): List<PersonMatch> =
        get(query("$D/people", "q" to name)).objects("items").mapNotNull { o ->
            val id = o.str("id").ifEmpty { return@mapNotNull null }
            PersonMatch(id, o.str("name").ifEmpty { id }, o.str("department"), o.str("known_for"))
        }

    suspend fun discoverSeries(tmdbId: String): JsonObject = get("$D/series/${enc(tmdbId)}")

    /** Same path segment the TV client sends (`series` for shows). */
    suspend fun discoverCredits(kind: DiscoverKind, tmdbId: String): JsonObject = get("$D/media/${kind.wire}/${enc(tmdbId)}/credits")

    suspend fun discoverPerson(personId: String): DiscoverPerson {
        val result = get("$D/person/${enc(personId)}/credits")
        val person = result.obj("person")
        return DiscoverPerson(
            name = person?.str("name").orEmpty(),
            biography = person?.str("biography").orEmpty().trim(),
            profilePath = person?.str("profile_path").orEmpty(),
            works = result.objects("works").map { DiscoverItem(it) },
        )
    }

    /** Movie (`season` null) or one season; returns the server's `demand_id` if any. */
    suspend fun discoverSubscribe(item: DiscoverItem, season: DiscoverSeason?): String {
        val poster = season?.posterPath?.ifEmpty { null } ?: item.posterPath
        var date = if (season == null) item.raw.str("release_date") else season.airDate
        if (date.isEmpty() && season != null) date = item.raw.str("first_air_date")
        val body = buildJsonObject {
            put("tmdb_id", item.id)
            put("name", item.title + if (season == null) "" else " 第 ${season.number} 季")
            put("entity_type", if (season == null) "movie" else "season")
            put("poster_path", poster)
            put("release_date", date)
            put("first_air_date", item.raw.str("first_air_date"))
            put("priority", 50)
            if (season != null) put("season_number", season.number)
        }
        return post("$D/subscribe", body).str("demand_id")
    }

    suspend fun cloudResources(provider: CloudProvider, item: DiscoverItem, season: Int?): List<CloudResource> {
        val type = item.kind.mediaType
        val path = when (provider) {
            CloudProvider.SharedPool -> query(
                "$D/shared-resources", "tmdb_id" to item.id, "item_type" to (if (season == null) type else "season"),
                "q" to item.title, "limit" to 100,
            )
            CloudProvider.Re0 -> query(
                "$D/re0-resources", "tmdb_id" to item.id, "media_type" to type, "require_complete" to "false", "season" to season,
            )
            CloudProvider.Channel -> query(
                "$D/channel-resources", "tmdb_id" to item.id, "media_type" to type, "q" to item.title, "year" to item.year,
                "season_number" to season,
            )
        }
        return CloudResources.parse(provider, get(path), season)
    }

    /** Confirmed by the user before calling: may spend server-side points. Returns `workflow_run_id`. */
    suspend fun acquire(row: CloudResource, item: DiscoverItem, season: Int?): Long {
        val watch = query("", "TmdbId" to item.id, "MediaType" to (if (item.kind == DiscoverKind.Movie) "movie" else "series"), "SeasonNumber" to season)
        val result = when (row.provider) {
            CloudProvider.SharedPool -> post("$D/shared-resources/acquire$watch", buildJsonObject { put("source", CloudResources.sharedSource(row.source)) })
            CloudProvider.Channel -> post("$D/channel-resources/acquire$watch", buildJsonObject { put("resource", row.source) })
            CloudProvider.Re0 -> post(
                "$D/re0-resources/acquire$watch",
                buildJsonObject {
                    put("slug", row.source.str("slug").ifEmpty { row.source.str("id") })
                    put("title", item.title)
                    put("tmdb_id", item.id)
                    put("media_type", item.kind.mediaType)
                    if (season != null) put("season_number", season)
                },
            )
        }
        return result.long("workflow_run_id")
    }

    // ---------- Danmaku ----------

    /** Server-side danmaku service switch (read only; the client never toggles it). */
    suspend fun danmakuAvailable(): Boolean = get("/api/tv/System/Danmaku").bool("Available")

    /** One 120 s window of comments starting at [startMs] for this play session. */
    suspend fun danmaku(itemId: String, playSessionId: String, startMs: Long): DanmakuPage {
        val result = get(query("/api/tv/Items/${enc(itemId)}/Danmaku", "local" to "true", "session" to playSessionId, "start" to startMs))
        return DanmakuPage(
            enabled = result.bool("enabled"),
            status = result.str("status"),
            cues = DanmakuTimeline.parse(result.objects("comments")),
        )
    }

    // ---------- Music ----------

    suspend fun musicHome(parentId: String, frequent: Int = 12): MusicHome {
        val result = get(
            query(
                "${u()}/Music",
                "ParentId" to parentId, "Key" to "", "Tab" to "recommended", "SortBy" to "Name",
                "SortOrder" to "Ascending", "FrequentLimit" to frequent,
            ),
        )
        return MusicHome(result.itemList("Latest"), result.itemList("Recent"), result.itemList("Frequent"))
    }

    suspend fun musicList(
        parentId: String,
        tab: String,
        key: String,
        start: Int,
        limit: Int = 60,
        tracksOnly: Boolean = false,
        newest: Boolean = key.isEmpty() && tab in setOf("albums", "artists", "songs"),
    ): MusicPage {
        val result = get(
            query(
                "${u()}/Music",
                "ParentId" to parentId, "Tab" to tab, "Key" to key,
                "SortBy" to if (newest) "DateCreated" else "Name",
                "SortOrder" to if (newest) "Descending" else "Ascending",
                "Tracks" to if (tracksOnly) 1 else null,
                "StartIndex" to start, "Limit" to limit,
            ),
        )
        val items = result.items()
        return MusicPage(
            items = items,
            folders = result.itemList("Folders"),
            total = result.int("TotalRecordCount", start + items.size),
            title = result.str("Title"),
            artistOverview = result.obj("Artist")?.strOrNull("Overview"),
        )
    }

    suspend fun playlists(parentId: String): List<Playlist> {
        val result = get(
            query("${u()}/Music", "ParentId" to parentId, "Tab" to "playlists", "Key" to "", "SortBy" to "Name", "SortOrder" to "Ascending"),
        )
        return result.objects("Playlists").map { Playlist(it.str("Key"), it.str("Name"), it.int("Count")) }
    }

    suspend fun createPlaylist(name: String): String =
        post("${u()}/Music/Playlists", buildJsonObject { put("Name", name.trim().take(80)) }).str("Key")

    suspend fun deletePlaylist(key: String) {
        delete("${u()}/Music/Playlists/${enc(key)}")
    }

    suspend fun addToPlaylist(key: String, itemId: String) {
        post("${u()}/Music/Playlists/${enc(key)}/Items/${enc(itemId)}")
    }

    /** GET first; only an empty (successful) text triggers the online lookup POST. */
    suspend fun lyrics(itemId: String, sourceId: String): String {
        val path = query("/api/tv/Music/${enc(itemId)}/Lyrics", "MediaSourceId" to sourceId)
        val text = get(path).str("text")
        if (text.isNotBlank()) return text
        return runCatching { post(path).str("text") }.getOrDefault("")
    }

    companion object {
        val EMPTY = JsonObject(emptyMap())
        private const val D = "/api/tv/discover"
        private const val MV_SEARCH_LIMIT = 200
        const val LIST_FIELDS =
            "PrimaryImageAspectRatio,Overview,CommunityRating,TMDbRating,DoubanRating,IMDbRating,CriticRating,ProductionYear,RecursiveItemCount,UserData"
        const val DETAIL_FIELDS = "MediaSources,MediaStreams,Overview"

        fun includeTypesFor(collectionType: String?): String? = when (collectionType) {
            "movies" -> "Movie"
            "tvshows" -> "Series"
            "homevideos" -> "Video"
            "boxsets" -> "BoxSet"
            "musicvideos" -> "MusicVideo"
            else -> "Movie,Series,Video"
        }

        fun stringArray(values: List<String>?): JsonElement =
            values?.let { JsonArray(it.map(::JsonPrimitive)) } ?: kotlinx.serialization.json.JsonNull
    }
}
