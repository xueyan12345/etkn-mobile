package xyz.etkn.mobile.ui.actions

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.etkn.mobile.data.api.EtknApi
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.net.double
import xyz.etkn.mobile.data.net.int
import xyz.etkn.mobile.data.net.long
import xyz.etkn.mobile.data.net.obj
import xyz.etkn.mobile.data.net.objects
import xyz.etkn.mobile.data.net.str
import xyz.etkn.mobile.data.net.strList
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.common.userMessage
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.GlassDialog
import xyz.etkn.mobile.ui.components.GlassSheet
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.PillToggle
import xyz.etkn.mobile.ui.components.images
import xyz.etkn.mobile.ui.screens.me.SwitchRow
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.components.EtknLoading

// ---------- Refresh ----------

/** Same choices and defaults as TV / Web: metadata on, images off, at least one required. */
@Composable
internal fun RefreshDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var metadata by remember { mutableStateOf(true) }
    var images by remember { mutableStateOf(false) }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("刷新元数据") },
        text = {
            Column {
                SwitchRow("替换所有元数据", metadata) { metadata = it }
                SwitchRow("替换现有图片", images) { images = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = metadata || images,
                onClick = {
                    onSubmit(
                        when {
                            metadata && images -> "replace_metadata_images"
                            metadata -> "replace_metadata"
                            else -> "replace_images"
                        },
                    )
                },
            ) { Text("刷新") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------- Delete ----------

private data class Version(val id: String, val label: String)

/**
 * Reads the item first: with several versions the user picks one (or all of them); otherwise a
 * plain confirmation naming what the linked delete covers. Cancel is the safe default.
 */
@Composable
internal fun DeleteDialog(item: Item, menu: ItemMenu) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var versions by remember { mutableStateOf<List<Version>?>(null) }
    var choice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(item.id) {
        versions = runCatching { versionsOf(graph.api.rawItem(item.id)) }.getOrDefault(emptyList())
    }
    val scopeText = when (item.type) {
        "Series" -> "整部剧集及其全部季、分集"
        "Season" -> "本季及其全部分集"
        else -> "该媒体项的全部版本"
    }
    val list = versions
    val dismiss = { if (!busy) menu.dialog = null }
    GlassDialog(
        onDismissRequest = dismiss,
        title = { Text("删除媒体项") },
        text = {
            Column {
                Text("确定删除“${item.name}”吗？将删除$scopeText，并进入联动删除流程，可能同时删除关联文件。")
                when {
                    list == null -> Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) { EtknLoading() }
                    list.size > 1 -> Column(Modifier.padding(top = 12.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        VersionOption("全部版本", choice == null) { choice = null }
                        list.forEach { version -> VersionOption(version.label, choice == version.id) { choice = version.id } }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = list != null && !busy,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                onClick = {
                    busy = true
                    val version = choice
                    scope.launch {
                        try {
                            if (version != null) {
                                graph.api.deleteVersion(item.id, version)
                                menu.notice = "媒体版本删除任务已提交"
                                menu.changed(item.id)
                            } else {
                                graph.api.deleteItem(item.id)
                                menu.notice = "删除任务已提交"
                                menu.changed(item.id, deleted = true)
                            }
                            menu.dialog = null
                        } catch (error: Exception) {
                            menu.notice = error.userMessage()
                            busy = false
                        }
                    }
                },
            ) { Text(if (choice == null) "确认删除" else "删除此版本") }
        },
        dismissButton = { TextButton(onClick = dismiss, enabled = !busy) { Text("取消") } },
    )
}

@Composable
private fun VersionOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Corner.card).selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** `Versions[]` when the server sends it, else the `version-N` media sources (as the web client does). */
private fun versionsOf(raw: JsonObject): List<Version> {
    val explicit = raw.objects("Versions").map { Version(it.str("Id"), versionLabel(it)) }
    if (explicit.isNotEmpty()) return explicit.filter { it.id.isNotEmpty() }
    return raw.objects("MediaSources").mapNotNull { source ->
        val id = source.str("Id")
        if (!VERSION_ID.matches(id)) null else Version(id.removePrefix("version-"), versionLabel(source))
    }
}

private fun versionLabel(source: JsonObject): String {
    val size = source.long("Size").takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1f GB", it / 1_073_741_824.0) }
    return listOfNotNull(source.str("Name").ifEmpty { source.str("Container").uppercase() }.ifEmpty { "版本" }, size).joinToString(" · ")
}

private val VERSION_ID = Regex("^version-[1-9]\\d*$")

// ---------- Metadata ----------

/** The web editor's fields, prefilled from the full item; empty ratings are sent as null. */
@Composable
internal fun MetadataSheet(item: Item, menu: ItemMenu) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ratings by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var name by remember { mutableStateOf(item.name) }
    var overview by remember { mutableStateOf("") }
    var customRating by remember { mutableStateOf("") }
    var doubanId by remember { mutableStateOf("") }
    var tmdb by remember { mutableStateOf("") }
    var douban by remember { mutableStateOf("") }
    var genres by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }

    LaunchedEffect(item.id) {
        try {
            val raw = graph.api.rawItem(item.id)
            name = raw.str("Name").ifEmpty { item.name }
            overview = raw.str("Overview")
            customRating = raw.str("CustomRatingCode").ifEmpty { raw.str("CustomRating") }
            doubanId = raw.str("DoubanId").ifEmpty { raw.obj("ProviderIds")?.str("Douban").orEmpty() }
            tmdb = (raw.double("TMDbRating") ?: raw.double("CommunityRating"))?.let(::rating).orEmpty()
            douban = raw.double("DoubanRating")?.let(::rating).orEmpty()
            genres = raw.strList("Genres").joinToString("，")
            tags = raw.strList("Tags").joinToString("，")
            loaded = true
        } catch (e: Exception) {
            error = e.userMessage()
        }
        ratings = runCatching { graph.api.ratingOptions() }.getOrDefault(emptyList())
    }

    GlassSheet(onDismissRequest = { if (!busy) menu.dialog = null }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("编辑元数据", style = MaterialTheme.typography.titleLarge)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (!loaded && error == null) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { EtknLoading() }
                return@Column
            }
            OutlinedTextField(name, { name = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            RatingPicker(ratings, customRating) { customRating = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(tmdb, { tmdb = it }, label = { Text("TMDb 评分") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(douban, { douban = it }, label = { Text("豆瓣评分") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            OutlinedTextField(doubanId, { doubanId = it }, label = { Text("豆瓣 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(genres, { genres = it }, label = { Text("类型") }, placeholder = { Text("用逗号分隔") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tags, { tags = it }, label = { Text("标签") }, placeholder = { Text("用逗号分隔") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(overview, { overview = it }, label = { Text("简介") }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = { menu.dialog = null }, enabled = !busy) { Text("取消") }
                Button(
                    enabled = !busy && name.isNotBlank() && validRating(tmdb) && validRating(douban),
                    onClick = {
                        busy = true
                        val body = buildJsonObject {
                            put("name", name.trim())
                            put("overview", overview.trim())
                            put("genres", EtknApi.stringArray(split(genres)))
                            put("tags", EtknApi.stringArray(split(tags)))
                            put("custom_rating", customRating)
                            put("douban_id", doubanId.trim())
                            put("tmdb_rating", tmdb.trim().toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("douban_rating", douban.trim().toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonNull)
                        }
                        scope.launch {
                            try {
                                graph.api.updateMetadata(item.id, body)
                                menu.notice = "元数据已保存"
                                menu.changed(item.id)
                                menu.dialog = null
                            } catch (e: Exception) {
                                error = e.userMessage()
                                busy = false
                            }
                        }
                    },
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun RatingPicker(options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.second == value }?.first ?: value
    Box {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text("自定义分级") },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledBorderColor = MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth().clickable(enabled = options.isNotEmpty()) { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("无") }, onClick = { onChange(""); expanded = false })
            options.forEach { (text, code) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onChange(code); expanded = false })
            }
        }
    }
}

private fun rating(value: Double): String = if (value <= 0) "" else String.format(Locale.ROOT, "%.1f", value)
private fun validRating(text: String): Boolean = text.isBlank() || text.trim().toDoubleOrNull()?.let { it in 0.0..10.0 } == true
private fun split(text: String): List<String> = text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

// ---------- Images ----------

private enum class ImageKind(val wire: String, val label: String, val ratio: Float, val cell: Int) {
    Primary("Primary", "海报", 2f / 3f, 104),
    Backdrop("Backdrop", "背景", 16f / 9f, 168),
    Logo("Logo", "标志", 16f / 9f, 150),
}

/**
 * Online candidates (TMDb / Fanart through the server's image proxy) per type, optionally in
 * every language; a tap applies one, the upload button sends a local picture.
 */
@Composable
internal fun ImageSheet(item: Item, menu: ItemMenu) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val urls = images()
    var kind by remember { mutableStateOf(ImageKind.Primary) }
    var allLanguages by remember { mutableStateOf(false) }
    val cache = remember { mutableStateMapOf<Boolean, List<JsonObject>>() }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(item.id, allLanguages) {
        if (cache.containsKey(allLanguages)) return@LaunchedEffect
        loadError = null
        runCatching { graph.api.remoteImages(item.id, allLanguages) }
            .onSuccess { cache[allLanguages] = it }
            .onFailure { loadError = it.userMessage() }
    }

    fun submit(work: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                work()
                menu.notice = "${kind.label}已更新"
                menu.changed(item.id)
            } catch (e: Exception) {
                menu.notice = e.userMessage()
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val type = kind.wire
        submit {
            val (bytes, mime, fileName) = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val data = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("无法读取图片")
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "image.jpg"
                Triple(data, resolver.getType(uri) ?: "image/jpeg", name)
            }
            graph.api.uploadImage(item.id, type, fileName, mime, bytes)
        }
    }

    GlassSheet(onDismissRequest = { if (!busy) menu.dialog = null }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("编辑图片", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (busy) EtknLoading(Modifier.size(36.dp), size = 6.dp)
                FilledTonalButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !busy) {
                    Icon(Icons.Rounded.Upload, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("上传")
                }
            }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(PillGap), modifier = Modifier.weight(1f)) {
                    ImageKind.entries.forEach { value ->
                        PillToggle(checked = kind == value, onClick = { kind = value }) { Text(value.label) }
                    }
                }
                Text("全部语言", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
                Switch(checked = allLanguages, onCheckedChange = { allLanguages = it })
            }
        }
        val candidates = cache[allLanguages]?.filter { it.str("type").equals(kind.wire, ignoreCase = true) }
        Box(Modifier.fillMaxWidth().height(460.dp)) {
            when {
                loadError != null -> Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                candidates == null -> EtknLoading(Modifier.align(Alignment.Center))
                candidates.isEmpty() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                    Text("没有找到可用图片", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(kind.cell.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(candidates, key = { it.str("source_url") + it.str("type") }) { image ->
                        Column(
                            Modifier.clip(Corner.card).clickable(enabled = !busy) {
                                submit { graph.api.selectImage(item.id, image, kind.wire) }
                            },
                        ) {
                            Artwork(
                                urls.absolute(image.str("thumbnail_url").ifEmpty { image.str("url") }),
                                Modifier.fillMaxWidth().aspectRatio(kind.ratio).clip(Corner.card),
                                contentScale = if (kind == ImageKind.Logo) ContentScale.Fit else ContentScale.Crop,
                            )
                            Text(
                                listOfNotNull(
                                    provider(image.str("source_provider")),
                                    image.str("language").ifEmpty { "无语言" },
                                    image.int("width").takeIf { it > 0 }?.let { "$it×${image.int("height")}" },
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp, start = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun provider(value: String): String = when (value.trim().lowercase()) {
    "tmdb" -> "TMDb"
    "fanart" -> "Fanart"
    "" -> "未知来源"
    else -> value
}
