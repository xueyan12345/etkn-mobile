package xyz.etkn.mobile.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.discover.DiscoverItem
import xyz.etkn.mobile.data.discover.DiscoverKind
import xyz.etkn.mobile.data.discover.DiscoverSeason
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.common.graphViewModel
import xyz.etkn.mobile.ui.common.userMessage
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.CardShape
import xyz.etkn.mobile.ui.components.GlassBackBar
import xyz.etkn.mobile.ui.components.GlassDialog
import xyz.etkn.mobile.ui.components.InlineLoading
import xyz.etkn.mobile.ui.components.SectionHeader
import xyz.etkn.mobile.ui.components.formatRating
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.components.images
import xyz.etkn.mobile.ui.nav.CloudRoute
import xyz.etkn.mobile.ui.nav.DetailRoute
import xyz.etkn.mobile.ui.nav.DiscoverDetailRoute
import xyz.etkn.mobile.ui.nav.DiscoverPersonRoute
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.Corner

@Composable
fun DiscoverDetailScreen(route: DiscoverDetailRoute) {
    val kind = DiscoverKind.entries.firstOrNull { it.wire == route.kind } ?: DiscoverKind.Movie
    val vm = graphViewModel(key = "discover-${route.kind}-${route.id}") { DiscoverDetailViewModel(it, route.id, kind, route.title) }
    val item by vm.item.collectAsStateWithLifecycle()
    val extras by vm.extras.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
    val haze = rememberHazeState()
    var confirm by remember { mutableStateOf<ConfirmSubscribe?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    val missing = extras.seasons.count { it.subscribable }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().glassSource(haze)) {
            item(key = "header", contentType = "header") { DiscoverHeader(item) }
            item(key = "actions", contentType = "actions") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.mediaItemId.isNotEmpty()) {
                        Button(onClick = { nav.to(DetailRoute(item.mediaItemId)) }) {
                            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.IconSize))
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text("在片库观看")
                        }
                    }
                    if (item.kind == DiscoverKind.Movie) {
                        val subscribeLabel = if (item.status != null) "再次订阅" else "订阅"
                        val primary = item.mediaItemId.isEmpty()
                        if (primary) {
                            Button(onClick = { confirm = ConfirmSubscribe(null, false) }, enabled = !busy) { Text(subscribeLabel) }
                        } else {
                            FilledTonalButton(onClick = { confirm = ConfirmSubscribe(null, false) }, enabled = !busy) { Text(subscribeLabel) }
                        }
                    } else if (missing > 0) {
                        Button(onClick = { confirm = ConfirmSubscribe(null, true) }, enabled = !busy) { Text("订阅缺失季") }
                    }
                    FilledTonalButton(onClick = { nav.to(CloudRoute(item.id, item.kind.wire, item.title)) }) {
                        Icon(Icons.Rounded.CloudDownload, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                        Text("云资源")
                    }
                }
            }
            if (item.tagline.isNotEmpty() || item.overview.isNotEmpty()) {
                item(key = "overview") {
                    Column(Modifier.padding(horizontal = Etkn.space.screen, vertical = 4.dp)) {
                        if (item.tagline.isNotEmpty()) {
                            Text(item.tagline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(6.dp))
                        }
                        if (item.overview.isNotEmpty()) ExpandableText(item.overview)
                    }
                }
            }
            if (item.kind == DiscoverKind.Series) {
                item(key = "seasons-head") { SectionHeader("季度", Modifier.padding(top = Etkn.space.section)) }
                if (extras.seasonsLoading) item(key = "seasons-loading") { InlineLoading() }
                items(extras.seasons, key = { "season-${it.number}" }) { season ->
                    SeasonRow(
                        season,
                        busy = busy,
                        onSubscribe = { confirm = ConfirmSubscribe(season, false) },
                        onResources = { nav.to(CloudRoute(item.id, item.kind.wire, item.title, season.number)) },
                    )
                }
            }
            if (extras.cast.isNotEmpty()) {
                item(key = "cast") {
                    Column(Modifier.padding(top = Etkn.space.section)) {
                        SectionHeader("演员")
                        LazyRow(contentPadding = PaddingValues(horizontal = Etkn.space.screen), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(extras.cast.take(40), key = { it.id + it.character }) { member ->
                                CastAvatar(member.name, member.character, member.profilePath) {
                                    if (member.id.isNotEmpty()) nav.to(DiscoverPersonRoute(member.id, member.name))
                                }
                            }
                        }
                    }
                }
            }
            item(key = "bottom") { Spacer(Modifier.height(LocalBottomInset.current + 24.dp)) }
        }
        GlassBackBar(item.title, collapsed, haze, nav::back)
    }

    confirm?.let { request ->
        val target = when {
            request.allMissing -> "${item.title} 的 $missing 个缺失季"
            request.season != null -> "${item.title} · 第 ${request.season.number} 季"
            else -> item.title
        }
        GlassDialog(
            onDismissRequest = { confirm = null },
            title = { Text("确认订阅") },
            text = { Text("订阅“$target”？服务端将按订阅配置自动获取资源。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        val result = if (request.allMissing) vm.subscribeMissing() else vm.subscribe(request.season)
                        message = result.fold({ "订阅已提交" to it }, { "订阅失败" to it.userMessage() })
                    }
                }) { Text("订阅") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
    message?.let { (title, body) ->
        GlassDialog(
            onDismissRequest = { message = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }
}

private data class ConfirmSubscribe(val season: DiscoverSeason?, val allMissing: Boolean)

@Composable
private fun DiscoverHeader(item: DiscoverItem) {
    val urls = images()
    val tokens = Etkn.tokens
    Box(Modifier.fillMaxWidth()) {
        Artwork(
            urls.tmdb(item.backdropPath, "w1280") ?: urls.tmdb(item.posterPath, "w780"),
            Modifier.fillMaxWidth().then(if (LocalWindowLayout.current.wide) Modifier.height(420.dp) else Modifier.aspectRatio(16f / 10f)),
            item.title,
            placeholder = Icons.Rounded.Movie,
        )
        Box(Modifier.fillMaxWidth().height(140.dp).background(tokens.heroTopScrim))
        Box(Modifier.matchParentSize().background(tokens.detailScrim))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen), verticalAlignment = Alignment.Bottom) {
        Artwork(
            urls.tmdb(item.posterPath, "w342"),
            Modifier.width(108.dp).aspectRatio(2f / 3f).clip(CardShape),
            item.title,
            placeholder = Icons.Rounded.Movie,
        )
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            item.status?.let { StatusPill(it, Modifier.padding(bottom = 6.dp)) }
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                item.year.ifEmpty { null },
                item.kind.label,
                formatRating(item.vote)?.let { "★ $it" },
            ).joinToString("  ·  ")
            Text(meta, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
internal fun ExpandableText(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(CardShape).clickable { expanded = !expanded },
    )
}

@Composable
private fun SeasonRow(season: DiscoverSeason, busy: Boolean, onSubscribe: () -> Unit, onResources: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            images().tmdb(season.posterPath, "w185"),
            Modifier.width(48.dp).aspectRatio(2f / 3f).clip(Corner.small),
            season.name,
            placeholder = Icons.Rounded.Movie,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                if (season.number == 0) "特别篇" else "第 ${season.number} 季" + if (season.name.isNotEmpty()) " · ${season.name}" else "",
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${season.episodes} 集 · ${season.airDate.ifEmpty { "日期待定" }}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            season.status?.let { StatusPill(it, Modifier.padding(top = 4.dp)) }
        }
        IconButton(onClick = onResources) { Icon(Icons.Rounded.CloudDownload, "搜索本季云资源") }
        if (season.subscribable) TextButton(onClick = onSubscribe, enabled = !busy) { Text("订阅") }
    }
}

@Composable
private fun CastAvatar(name: String, role: String, profilePath: String, onClick: () -> Unit) {
    Column(Modifier.width(84.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(
            images().tmdb(profilePath, "w185"),
            Modifier.size(76.dp).clip(CircleShape).clickable(onClick = onClick),
            name,
            placeholder = Icons.Rounded.Person,
        )
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        if (role.isNotEmpty()) {
            Text(role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
