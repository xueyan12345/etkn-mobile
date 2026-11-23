package xyz.etkn.mobile.data.discover

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.etkn.mobile.data.net.EtknJson
import xyz.etkn.mobile.data.net.bool
import xyz.etkn.mobile.data.net.double
import xyz.etkn.mobile.data.net.int
import xyz.etkn.mobile.data.net.obj
import xyz.etkn.mobile.data.net.rows
import xyz.etkn.mobile.data.net.str
import java.util.Locale
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray

/**
 * Cloud resource providers searched in parallel for one title.
 *
 * 用户口径（2026-11-23）：只保留「共享池」。re0 和频道监听这两个渠道用户没有配置，
 * 之前会一路报错（re0 请求失败 500 HDHive access_token not found、频道监听未连接），
 * 搜索结果里也不该出现。整个 App 里凡是遍历 [entries] 的地方现在只剩共享池。
 */
enum class CloudProvider(val wire: String, val label: String) {
    SharedPool("shared_pool", "共享池"),
    Re0("re0", "re0"),
    Channel("channel", "频道");

    companion object {
        /**
         * 用户口径（2026-11-23）：实际要搜的渠道只有「共享池」。
         * re0 和频道监听用户没配置（会报 HDHive access_token not found / 频道监听未连接），
         * 所以界面上不查、不显示。枚举值保留是为了原有 when 分支不用改。
         */
        val enabled: List<CloudProvider> get() = listOf(SharedPool)
    }
}

@Immutable
class CloudResource(val provider: CloudProvider, val source: JsonObject) {
    val title: String get() = titleOf(source)
    override fun equals(other: Any?) = other is CloudResource && other.provider == provider && other.source == source
    override fun hashCode() = 31 * provider.hashCode() + source.hashCode()
}

enum class ChipTone { Default, Success, Warning, Info }

@Immutable
data class ResourceChip(val text: String, val tone: ChipTone)

/**
 * Port of ETKN TV's `CloudResources`: shared-pool parents are display shells whose `versions`
 * become exact rows (never inheriting the parent's SHA1, source id or files); everything else
 * is a row as returned. Pure functions, unit tested.
 */
object CloudResources {
    private val SeasonPattern = Regex("""(?i)(?:第\s*|\bS|Season\s*)(\d{1,3})(?:\s*季|\b)""")
    private val Inherit = arrayOf("title", "name", "tmdb_id", "season_number", "pan_type", "release_year", "year", "poster_path")

    fun parse(provider: CloudProvider, payload: JsonObject, season: Int?): List<CloudResource> {
        val result = ArrayList<CloudResource>()
        for (parent in payload.rows()) {
            val versions = if (provider == CloudProvider.SharedPool) parent["versions"] as? JsonArray else null
            if (versions == null || versions.isEmpty()) {
                add(result, provider, parent, season)
                continue
            }
            val groups = LinkedHashMap<String, MutableList<JsonObject>>()
            versions.forEachIndexed { index, element ->
                var exact = element as? JsonObject ?: return@forEachIndexed
                for (key in Inherit) {
                    if (exact[key].isBlankValue() && parent.containsKey(key)) exact = exact.withValues(key to parent[key]!!)
                }
                exact = exact.without("versions")
                groups.getOrPut(sharedVersionKey(exact, index)) { ArrayList() } += exact
            }
            var index = 0
            for (sources in groups.values) {
                val sorted = sources.sortedBy { if (transferLabel(CloudResource(provider, it)) == "可转存") 0 else 1 }
                var count = 0
                sorted.forEach { count += maxOf(1, it.int("_shared_pool_source_count", it.int("source_count", 1))) }
                val exact = sorted[0].withValues(
                    "_version_index" to JsonPrimitive(++index),
                    "_version_count" to JsonPrimitive(groups.size),
                    "_shared_pool_source_count" to JsonPrimitive(count),
                    "_shared_pool_alternative_sources" to JsonArray(sorted.take(20)),
                )
                add(result, provider, exact, season)
            }
        }
        return result
    }

    private fun add(rows: MutableList<CloudResource>, provider: CloudProvider, item: JsonObject, requested: Int?) {
        val season = season(item)
        if (requested != null && ((season == null && provider == CloudProvider.SharedPool) || (season != null && season != requested))) return
        rows += CloudResource(provider, item)
    }

    fun season(item: JsonObject): Int? {
        if (item.containsKey("season_number") && item["season_number"] !is JsonNull) return item.int("season_number")
        return SeasonPattern.find(titleOf(item))?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Exact shared-pool source: logical-group fields lifted, `best_asset_map` parsed if it is a string. */
    fun sharedSource(original: JsonObject): JsonObject {
        var exact = original
        val group = exact.obj("logical_group")
        for (key in arrayOf("logical_group_id", "content_version_key", "best_asset_map")) {
            if ((!exact.containsKey(key) || exact[key] is JsonNull) && group != null && group.containsKey(key)) exact = exact.withValues(key to group[key]!!)
        }
        val map = exact["best_asset_map"]
        if (map is JsonPrimitive && map.isString) {
            val parsed = runCatching { EtknJson.parseToJsonElement(map.content) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
            exact = exact.withValues("best_asset_map" to parsed)
        }
        return exact
    }

    private fun sharedVersionKey(item: JsonObject, fallback: Int): String {
        val exact = sharedSource(item)
        for (key in arrayOf("logical_group_id", "content_version_key")) {
            val value = exact.str(key).trim()
            if (value.isNotEmpty()) return "$key:${value.uppercase(Locale.ROOT)}"
        }
        val sha = firstValue(exact, "sha1", "file_sha1")
        if (sha.isNotEmpty()) return "sha1:${sha.uppercase(Locale.ROOT)}"
        val episodes = StringBuilder()
        exact.obj("best_asset_map")?.forEach { (episode, asset) ->
            val hash = (asset as? JsonObject)?.let { firstValue(it, "sha1", "file_sha1") }.orEmpty()
            if (hash.isNotEmpty()) episodes.append(episode).append(':').append(hash).append('|')
        }
        if (episodes.isNotEmpty()) return "episode_sha1:${episodes.toString().uppercase(Locale.ROOT)}"
        val source = firstValue(exact, "source_id", "source_ref_id", "id", "hub_id")
        return if (source.isEmpty()) "row:$fallback" else "source_id:${source.uppercase(Locale.ROOT)}"
    }

    fun label(row: CloudResource): String {
        val value = row.source
        val text = StringBuilder(row.title)
        for (key in arrayOf("resolution", "quality", "video_codec", "release_group", "share_size", "size_text", "progress_text")) {
            val part = value.str(key)
            if (part.isNotEmpty()) text.append("  ·  ").append(part)
        }
        return text.toString()
    }

    /** "版本 2/5" when a shared-pool parent aggregated several versions. */
    fun versionLabel(row: CloudResource): String {
        val count = row.source.int("_version_count")
        val index = row.source.int("_version_index")
        return if (count > 1) "版本 $index/$count" else ""
    }

    /** Shared pool: a valid share channel transfers, otherwise rapid upload. Empty for re0 / channel. */
    fun transferLabel(row: CloudResource): String {
        if (row.provider != CloudProvider.SharedPool) return ""
        val channel = row.source.obj("share_channel") ?: row.source.obj("logical_season_share_channel")
        val status = row.source.str("share_channel_status", channel?.str("status").orEmpty()).lowercase(Locale.ROOT)
        if (row.source.bool("share_transfer_available") || row.source.bool("has_valid_share_channel")) return "可转存"
        val mode = row.source.str("preferred_transfer_mode", row.source.str("transfer_mode")).lowercase(Locale.ROOT)
        return if (mode == "share" || status == "valid") "可转存" else "可秒传"
    }

    /** Up to 10 de-duplicated tags. */
    fun chips(row: CloudResource): List<ResourceChip> {
        val value = row.source
        val chips = ArrayList<ResourceChip>()
        val seen = HashSet<String>()
        fun push(text: String?, tone: ChipTone) {
            if (text.isNullOrEmpty() || !seen.add(text) || chips.size >= 10) return
            chips += ResourceChip(text, tone)
        }
        push(panTypeLabel(value.str("pan_type")), ChipTone.Default)
        push(sizeText(value), ChipTone.Default)
        push(firstValue(value, "video_resolution", "resolution", "resolution_display"), ChipTone.Success)
        val effect = firstValue(value, "effect", "effect_key", "effect_display", "hdr_type", "dynamic_range", "source")
        if (effect.isNotEmpty() && !effect.equals("SDR", true) && !effect.equals("channel", true) && effect != "共享秒传" && effect != "可秒传") {
            push(effect, ChipTone.Warning)
        }
        push(codecLabel(value), ChipTone.Default)
        push(frameRateLabel(value), ChipTone.Default)
        push(episodeProgress(value), ChipTone.Info)
        val sourceCount = value.int("_shared_pool_source_count")
        if (sourceCount > 1) push("共享源 $sourceCount", ChipTone.Info)
        (value["tag_labels"] as? JsonArray)?.forEach { element ->
            val label = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content.orEmpty()
            if (label.isNotEmpty()) push(label, if (label.contains("一致") || label.contains("完结") || label.contains("完整")) ChipTone.Success else ChipTone.Default)
        }
        if (value.bool("is_clean_version")) push("纯净版", ChipTone.Warning)
        if (value.bool("is_animation")) push("动漫", ChipTone.Success)
        if (value.bool("is_short_drama")) push("短剧", ChipTone.Info)
        if (row.provider == CloudProvider.Re0) {
            if (value.bool("already_owned")) {
                push("已解锁", ChipTone.Success)
            } else {
                val points = value.double("unlock_points") ?: 0.0
                push(if (points <= 0) "免费" else String.format(Locale.ROOT, "%.0f 积分", points), ChipTone.Warning)
            }
            val completion = value.str("_completion_label")
            if (completion.isNotEmpty()) push(completion, if (completion.contains("完整")) ChipTone.Success else ChipTone.Default)
            val quality = value.str("quality")
            if (quality.isNotEmpty() && quality != "[]") push(quality, ChipTone.Default)
        }
        if (row.provider == CloudProvider.Channel) {
            push(value.str("message_date"), ChipTone.Info)
            val channel = value.str("source_channel")
            if (channel.isNotEmpty()) push("来源：$channel", ChipTone.Info)
        }
        return chips
    }

    fun panTypeLabel(type: String): String {
        if (type.isEmpty()) return "115网盘"
        return when (type.lowercase(Locale.ROOT)) {
            "115" -> "115网盘"
            "rapid115", "rapid", "shared_pool" -> "115秒传"
            "magnet" -> "磁力"
            "ed2k" -> "ED2K"
            "bt" -> "BT"
            else -> if (type == "离线") "离线" else type.uppercase(Locale.ROOT)
        }
    }

    fun sizeText(source: JsonObject): String {
        for (key in arrayOf("share_size", "size_text")) source.str(key).takeIf { it.isNotEmpty() }?.let { return it }
        var bytes = 0.0
        for (key in arrayOf("total_size", "size", "file_size", "size_bytes")) {
            val value = source.double(key) ?: 0.0
            if (value > 0) {
                bytes = value
                break
            }
        }
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var index = 0
        while (bytes >= 1024 && index < units.lastIndex) {
            bytes /= 1024
            index++
        }
        return String.format(Locale.ROOT, if (index == 0) "%.0f %s" else if (index == 1) "%.1f %s" else "%.2f %s", bytes, units[index])
    }

    fun episodeProgress(value: JsonObject): String {
        val progress = value.str("progress_text").trim()
        if (progress.matches(Regex("""\d+\s*/\s*\d+"""))) return progress.replace(" ", "") + " 集"
        val current = value.double("progress_current") ?: value.double("episode_available") ?: -1.0
        val total = value.double("progress_total") ?: value.double("episode_total") ?: 0.0
        if (current >= 0 && total > 0) return String.format(Locale.ROOT, "%.0f/%.0f 集", current, total)
        val count = value.int("file_count")
        return if (count > 0) "$count 集" else ""
    }

    fun codecLabel(value: JsonObject): String {
        val text = firstValue(value, "video_codec", "codec")
        return if (text.isEmpty() || text.equals("未知", true)) "" else text
    }

    /** "24000/1001" → "23.976 fps". */
    fun frameRateLabel(value: JsonObject): String {
        val raw = firstValue(value, "frame_rate", "fps").replace("fps", "").replace("FPS", "").trim()
        if (raw.isEmpty()) return ""
        val fraction = Regex("""^(\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)$""").matchEntire(raw)
        val number = if (fraction != null) {
            fraction.groupValues[1].toDouble() / fraction.groupValues[2].toDouble()
        } else {
            raw.toDoubleOrNull()
        }
        if (number != null && number > 0) {
            val formatted = String.format(Locale.ROOT, "%.3f", number).trimEnd('0').trimEnd('.')
            return "$formatted fps"
        }
        return "$raw fps"
    }

    /** First non-empty value in the row itself or its nested summaries (objects or JSON strings). */
    fun firstValue(source: JsonObject, vararg keys: String): String {
        for (container in containers(source)) {
            for (key in keys) {
                val element = container[key] ?: continue
                if (element is JsonNull) continue
                val text = (if (element is JsonPrimitive) element.content else element.toString()).trim()
                if (text.isNotEmpty() && !text.equals("unknown", true) && text != "未知" && text != "[]") return text
            }
        }
        return ""
    }

    private fun containers(source: JsonObject): List<JsonObject> {
        val list = arrayListOf(source)
        for (key in arrayOf("version_summary", "summary_json", "media_signature_json", "rapid_meta_json")) {
            when (val value = source[key]) {
                is JsonObject -> list += value
                is JsonPrimitive -> if (value.isString) {
                    runCatching { EtknJson.parseToJsonElement(value.content) as? JsonObject }.getOrNull()?.let { list += it }
                }
                else -> Unit
            }
        }
        return list
    }
}

/**
 * 用户口径（2026-11-23）：共享池音乐 / MV 的一条。
 * 服务端结构：{package_id, kind, artwork, metadata{title, album, artists[], year, genre}, file_count, is_mine}
 * 注意标题在 metadata 里，不在顶层 —— 之前按顶层 title 取，结果整条都是空的。
 */
@Immutable
class SharedMusicItem(val raw: JsonObject) {
    val packageId: String = raw.str("package_id")
    val kind: String = raw.str("kind")
    private val meta: JsonObject? = (raw["metadata"] as? JsonObject)
    val title: String = meta?.str("title").orEmpty().ifEmpty { meta?.str("album").orEmpty() }
    val album: String = meta?.str("album").orEmpty()
    val artists: String = runCatching {
        (meta?.get("artists") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            ?.joinToString(" / ")
            .orEmpty()
    }.getOrDefault("")
    val year: Int = runCatching { meta?.int("year") ?: 0 }.getOrDefault(0)
    val artwork: String = raw.str("artwork")
    val fileCount: Int = raw.int("file_count")

    companion object {
        fun from(o: JsonObject) = SharedMusicItem(o)
    }
}
