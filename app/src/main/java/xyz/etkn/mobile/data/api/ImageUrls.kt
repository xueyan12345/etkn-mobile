package xyz.etkn.mobile.data.api

import xyz.etkn.mobile.data.model.Chapter
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.PersonRef
import xyz.etkn.mobile.data.net.enc
import xyz.etkn.mobile.data.net.int
import xyz.etkn.mobile.data.net.str

/**
 * Artwork URL rules taken from the TV client. Widths are requested in physical pixels, rounded
 * up to a small set of buckets so Coil's memory/disk cache can share bitmaps across screens.
 */
class ImageUrls(private val server: String, private val userId: String) {

    fun poster(item: Item, widthPx: Int): String? {
        if (item.isMusicGroup || item.isAudio) return music(item, widthPx)
        val own = item.imageTags["Primary"]
        return when {
            item.isEpisode && item.seriesId != null && item.seriesPrimaryTag != null ->
                image(item.seriesId, "Primary", widthPx, item.seriesPrimaryTag)
            own != null -> image(item.id, "Primary", widthPx, own)
            item.isSeason && item.seriesId != null && item.seriesPrimaryTag != null ->
                image(item.seriesId, "Primary", widthPx, item.seriesPrimaryTag)
            else -> null
        }
    }

    /** Own primary only (episode stills, season posters, library covers). */
    fun primary(item: Item, widthPx: Int): String? =
        if (item.isMusicGroup || item.isAudio) music(item, widthPx)
        else item.imageTags["Primary"]?.let { image(item.id, "Primary", widthPx, it) }

    /** 16:9 artwork for continue-watching / episode cards. */
    fun landscape(item: Item, widthPx: Int): String? {
        item.imageTags["Thumb"]?.let { return image(item.id, "Thumb", widthPx, it) }
        if (item.isEpisode) item.imageTags["Primary"]?.let { return image(item.id, "Primary", widthPx, it) }
        if (item.parentThumbItemId != null && item.parentThumbTag != null) {
            return image(item.parentThumbItemId, "Thumb", widthPx, item.parentThumbTag)
        }
        return backdrop(item, widthPx) ?: primary(item, widthPx)
    }

    /**
     * 用户口径（2026-11-24）**章节卡专用：只取「整部剧」的封面，绝不碰本集自己的图**。
     *
     * 为什么需要这个而不用 landscape()：
     * landscape() 内部第 2 条是 `if (item.isEpisode) item.imageTags["Primary"]`，
     * 传 episode 进去会把本集剧照当成封面用 —— 结果同一排章节卡里
     * 一张是本集特写、其余是别的场景，视觉上完全不统一。
     *
     * 回退顺序（全部是父级/系列级）：
     *  ① 父级横版 Thumb（parentThumbItemId + parentThumbTag）—— 16:9 最合适
     *  ② 父级背景图（parentBackdropItemId + parentBackdropTags）
     *  ③ 系列海报（seriesId + seriesPrimaryTag）—— 竖版，靠 Crop 裁成 16:9
     *  ④ 都没有 → null（调用方画占位图标）
     */
    fun seriesCover(item: Item, widthPx: Int): String? {
        item.parentThumbItemId?.let { pid ->
            item.parentThumbTag?.let { tag -> return image(pid, "Thumb", widthPx, tag) }
        }
        item.parentBackdropItemId?.let { pid ->
            item.parentBackdropTags.firstOrNull()?.let { tag ->
                return image(pid, "Backdrop", widthPx, tag)
            }
        }
        // 系列海报：item 自己是剧集时用 seriesId，是季/系列时用自己
        val seriesItem = if (item.isEpisode) item.seriesId else item.id
        val seriesTag = if (item.isEpisode) item.seriesPrimaryTag else item.imageTags["Primary"]
        if (seriesItem != null && seriesTag != null) return image(seriesItem, "Primary", widthPx, seriesTag)
        return null
    }

    fun backdrop(item: Item, widthPx: Int): String? {
        item.backdropTags.firstOrNull()?.let { return image(item.id, "Backdrop", widthPx, it) }
        val parent = item.parentBackdropItemId
        val tag = item.parentBackdropTags.firstOrNull()
        if (parent != null && tag != null) return image(parent, "Backdrop", widthPx, tag)
        return null
    }

    /**
     * 用户口径（2026-11-27）**只取「整部剧」的艺术字**，供单集页头部使用。
     *
     * 为什么需要单独一个：`logo(item)` 第一条取的是**本集自己的** Logo，
     * 只有本集没有 Logo 时才退到父级。少数分集自带 Logo →
     * 用户在集间切换时希望头部图完全不动，艺术字却跟着换 → 视觉跳动。
     *
     * 顺序：① 父级 Logo（整部剧）  ② 本集 Logo（父级没有时兜底）
     */
    fun seriesLogo(item: Item, widthPx: Int): String? {
        if (item.parentLogoItemId != null && item.parentLogoTag != null) {
            return image(item.parentLogoItemId, "Logo", widthPx, item.parentLogoTag)
        }
        return item.imageTags["Logo"]?.let { image(item.id, "Logo", widthPx, it) }
    }

    fun logo(item: Item, widthPx: Int): String? {
        item.imageTags["Logo"]?.let { return image(item.id, "Logo", widthPx, it) }
        if (item.parentLogoItemId != null && item.parentLogoTag != null) {
            return image(item.parentLogoItemId, "Logo", widthPx, item.parentLogoTag)
        }
        return null
    }

    fun person(person: PersonRef, widthPx: Int): String? =
        person.imageTag?.let { image(person.id, "Primary", widthPx, it) }

    fun user(userId: String = this.userId, tag: String?, widthPx: Int = 160): String =
        "$server/api/tv/Users/${enc(userId)}/Images/Primary?maxWidth=$widthPx" + (tag?.let { "&tag=${enc(it)}" } ?: "")

    fun chapter(item: Item, chapter: Chapter, widthPx: Int): String {
        val raw = chapter.imagePath
        val path = when {
            raw.isNullOrEmpty() -> "/api/tv/Items/${enc(item.id)}/Images/Chapter/${chapter.index}"
            raw.startsWith("/api/") -> raw
            raw.startsWith("/") -> "/api/tv$raw"
            else -> "/api/tv/$raw"
        }
        return "$server$path?maxWidth=${bucket(widthPx)}&quality=85&tag=${enc(chapter.imageTag ?: "")}"
    }

    fun music(item: Item, widthPx: Int): String? {
        val w = bucket(widthPx)
        item.artworkHash?.let {
            return "$server/api/image_proxy?cache=${enc(it)}&maxWidth=$w" +
                if (item.kind == "artists") "&variantGroup=avatar" else ""
        }
        item.artistImage?.let { a ->
            return "$server/api/tv/Items/${enc(a.str("ItemId"))}/Images/Backdrop/${a.int("Index")}?maxWidth=$w" +
                "&tag=${enc(a.str("Tag"))}&variantGroup=avatar"
        }
        return item.imageTags["Primary"]?.let { image(item.id, "Primary", widthPx, it) }
            ?: item.albumKey?.let { null }
    }

    fun tmdb(path: String?, size: String = "w342"): String? =
        path?.takeIf { it.isNotEmpty() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    /** A category card's cover, when the server has one ([Item.imageTags] Primary). */
    fun facet(libraryId: String, item: Item, widthPx: Int): String? {
        val tag = item.imageTags["Primary"] ?: return null
        return "$server/api/tv/Users/${enc(userId)}/FacetArtwork?ParentId=${enc(libraryId)}&Kind=${enc(item.kind.orEmpty())}" +
            "&Value=${enc(item.name)}&maxWidth=${bucket(widthPx)}&tag=${enc(tag)}"
    }

    fun absolute(path: String): String = if (path.startsWith("http")) path else server + path

    private fun image(id: String, type: String, widthPx: Int, tag: String): String =
        "$server/api/tv/Items/${enc(id)}/Images/$type?maxWidth=${bucket(widthPx)}&quality=90&tag=${enc(tag)}"

    companion object {
        private val BUCKETS = intArrayOf(160, 240, 320, 480, 640, 800, 1080, 1280, 1600, 1920)

        fun bucket(px: Int): Int = BUCKETS.firstOrNull { it >= px } ?: BUCKETS.last()
    }
}
