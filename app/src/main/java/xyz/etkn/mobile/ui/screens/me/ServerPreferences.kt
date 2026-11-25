package xyz.etkn.mobile.ui.screens.me

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.etkn.mobile.data.net.arr
import xyz.etkn.mobile.data.net.bool
import xyz.etkn.mobile.data.net.obj
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.components.GlassSheet
import xyz.etkn.mobile.ui.theme.Corner

/** Server values and labels, as the Web 个人偏好 shows them. */
private val AudioLanguages = listOf("chi" to "国语", "yue" to "粤语", "original" to "原语言", "eng" to "英语", "jpn" to "日语", "kor" to "韩语")
private val SubtitleKinds = listOf(
    "effect" to "特效字幕", "chs" to "简体中文", "cht" to "繁体中文", "chs_eng" to "简英双语", "cht_eng" to "繁英双语",
    "chs_jpn" to "简日双语", "cht_jpn" to "繁日双语", "chs_kor" to "简韩双语", "cht_kor" to "繁韩双语",
    "original" to "原语言", "eng" to "英文", "jpn" to "日文", "kor" to "韩文",
)

/**
 * This account's playback preferences on the server (`ClientPreferences.playback_preferences`).
 * The server applies them itself: PlaybackInfo's default audio / subtitle follow the priority, and
 * 最新 drops watched titles. A null list follows the server-wide default ([global]).
 */
@Immutable
data class PlaybackPreferences(
    val audio: List<String>?,
    val subtitles: List<String>?,
    val globalAudio: List<String>,
    val globalSubtitles: List<String>,
    val hideWatchedLatest: Boolean,
) {
    companion object {
        fun from(client: JsonObject): PlaybackPreferences? {
            val prefs = client.obj("playback_preferences") ?: return null
            val own = prefs.obj("preferences")
            val global = prefs.obj("global_preferences")
            fun JsonObject?.list(key: String) = this?.arr(key)?.mapNotNull { (it as? JsonPrimitive)?.content }
            return PlaybackPreferences(
                audio = own.list("audio_lang_priority")?.takeIf { it.isNotEmpty() },
                subtitles = own.list("subtitle_priority")?.takeIf { it.isNotEmpty() },
                globalAudio = global.list("audio_lang_priority").orEmpty(),
                globalSubtitles = global.list("subtitle_priority").orEmpty(),
                hideWatchedLatest = prefs.bool("hide_watched_latest"),
            )
        }
    }
}

/** 默认音轨 / 默认字幕 / 最新隐藏已观看, read from and written to the server. */
@Composable
internal fun ServerPlaybackPreferences() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf<PlaybackPreferences?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { prefs = runCatching { PlaybackPreferences.from(graph.api.clientPreferences()) }.getOrNull() }
    val current = prefs ?: return

    fun save(key: String, value: JsonElement) = scope.launch {
        runCatching { graph.api.savePlaybackPreference(key, value) }
        prefs = runCatching { PlaybackPreferences.from(graph.api.clientPreferences()) }.getOrNull() ?: prefs
    }

    PriorityRow("默认音轨", current.audio, current.globalAudio, AudioLanguages) { editing = "audio_lang_priority" }
    PriorityRow("默认字幕", current.subtitles, current.globalSubtitles, SubtitleKinds) { editing = "subtitle_priority" }

    when (editing) {
        "audio_lang_priority" -> PrioritySheet("默认音轨", current.audio.orEmpty(), AudioLanguages, onDismiss = { editing = null }) { picked ->
            editing = null
            save("audio_lang_priority", picked.toJson())
        }
        "subtitle_priority" -> PrioritySheet("默认字幕", current.subtitles.orEmpty(), SubtitleKinds, onDismiss = { editing = null }) { picked ->
            editing = null
            save("subtitle_priority", picked.toJson())
        }
    }
}

/**
 * 用户口径（2026-11-27）**「最新列表隐藏已观看」单独抽出来**，供「媒体库设置」板块使用。
 *
 * 为什么必须拆开：它存在**服务端**（`playback_preferences` 的 `hide_watched_latest`），
 * 而 `ServerPlaybackPreferences()` 里另外两项（默认音轨、默认字幕）是播放相关，
 * 应该留在「播放」页。整块搬走会把音轨字幕也带走，所以单独提供一个入口。
 *
 * 功能链路（确认过是真的生效，不是摆设）：
 *  · 读：`clientPreferences()` → `PlaybackPreferences.from()` → `hideWatchedLatest`
 *  · 写：`savePlaybackPreference("hide_watched_latest", JsonPrimitive(bool))`
 *  · 生效：服务端按此过滤「最新」列表，客户端拉 `Items/Latest` 时由服务端返回已过滤结果
 */
@Composable
internal fun ServerHideWatchedLatest() {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var prefs by remember { mutableStateOf<PlaybackPreferences?>(null) }
    LaunchedEffect(Unit) {
        prefs = runCatching { PlaybackPreferences.from(graph.api.clientPreferences()) }.getOrNull()
    }
    val current = prefs ?: return
    SwitchRow("最新列表隐藏已观看", current.hideWatchedLatest) {
        scope.launch {
            runCatching { graph.api.savePlaybackPreference("hide_watched_latest", JsonPrimitive(it)) }
            prefs = runCatching { PlaybackPreferences.from(graph.api.clientPreferences()) }.getOrNull() ?: prefs
        }
    }
}

/** An empty pick means "follow the server-wide default" (JSON null). */
private fun List<String>.toJson() = if (isEmpty()) JsonNull else JsonArray(map(::JsonPrimitive))

private fun summary(values: List<String>, options: List<Pair<String, String>>) =
    values.joinToString(" → ") { value -> options.firstOrNull { it.first == value }?.second ?: value }

@Composable
private fun PriorityRow(label: String, own: List<String>?, global: List<String>, options: List<Pair<String, String>>, onClick: () -> Unit) {
    val text = when {
        own != null -> summary(own, options)
        global.isNotEmpty() -> "跟随全局 · " + summary(global, options)
        else -> "跟随全局"
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 16.dp))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (own != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Tap in the order wanted; the number is the rank. 跟随全局 clears the personal list. */
@Composable
private fun PrioritySheet(title: String, initial: List<String>, options: List<Pair<String, String>>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var picked by remember { mutableStateOf(initial) }
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp))
            options.forEach { (value, label) ->
                val rank = picked.indexOf(value)
                Row(
                    Modifier.fillMaxWidth().clip(Corner.card)
                        .clickable { picked = if (rank >= 0) picked - value else picked + value }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (rank >= 0) {
                        Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                            Text("${rank + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onSave(emptyList()) }, modifier = Modifier.weight(1f)) { Text("跟随全局") }
                Button(onClick = { onSave(picked) }, modifier = Modifier.weight(1f)) { Text("完成") }
            }
        }
    }
}
