package xyz.etkn.mobile.ui.screens.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.etkn.mobile.data.discover.ChipTone
import xyz.etkn.mobile.data.discover.CloudProvider
import xyz.etkn.mobile.data.discover.CloudResource
import xyz.etkn.mobile.data.discover.CloudResources
import xyz.etkn.mobile.data.discover.DiscoverItem
import xyz.etkn.mobile.data.discover.DiscoverKind
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.graphViewModel
import xyz.etkn.mobile.ui.common.userMessage
import xyz.etkn.mobile.ui.components.CardShape
import xyz.etkn.mobile.ui.components.GlassBackBar
import xyz.etkn.mobile.ui.components.GlassDialog
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.nav.CloudRoute
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.PillToggle

@Composable
fun CloudScreen(route: CloudRoute) {
    val graph = LocalContext.current.graph
    val kind = DiscoverKind.entries.firstOrNull { it.wire == route.kind } ?: DiscoverKind.Movie
    val season = route.season.takeIf { it >= 0 }
    val vm = graphViewModel(key = "cloud-${route.kind}-${route.id}-${route.season}") {
        val item = graph.discover.get(kind, route.id) ?: DiscoverItem(
            JsonObject(mapOf("id" to JsonPrimitive(route.id), "title" to JsonPrimitive(route.title), "media_type" to JsonPrimitive(kind.mediaType))),
            kind,
        )
        CloudViewModel(it, item, season)
    }
    val results by vm.results.collectAsStateWithLifecycle()
    val submitted by vm.submitted.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val haze = rememberHazeState()
    var source by remember { mutableStateOf<CloudProvider?>(null) }
    var confirm by remember { mutableStateOf<CloudResource?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    val title = route.title + if (season == null) "" else " · 第 $season 季"

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().glassSource(haze),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 72.dp,
                bottom = LocalBottomInset.current + 16.dp,
            ),
        ) {
            item(key = "sources") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(PillGap),
                ) {
                    val total = results.values.sumOf { (it as? Load.Ready)?.value?.size ?: 0 }
                    PillToggle(checked = source == null, onClick = { source = null }) { Text("全部 $total") }
                    CloudProvider.enabled.forEach { provider ->
                        val count = (results[provider] as? Load.Ready)?.value?.size
                        PillToggle(checked = source == provider, onClick = { source = provider }) {
                            Text(provider.label + (count?.let { " $it" } ?: ""))
                        }
                    }
                }
            }
            CloudProvider.enabled.filter { source == null || source == it }.forEach { provider ->
                val state = results[provider] ?: Load.Loading
                item(key = "head-${provider.wire}") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = Etkn.space.screen, end = Etkn.space.screen, top = 20.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(provider.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Text(
                            when (state) {
                                Load.Loading -> "搜索中…"
                                is Load.Failed -> state.message
                                is Load.Ready -> if (state.value.isEmpty()) "暂无匹配资源" else "${state.value.size} 个版本"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (state is Load.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state is Load.Ready) {
                    val bySeason = provider == CloudProvider.SharedPool && kind == DiscoverKind.Series
                    val groups: List<Pair<Int?, List<CloudResource>>> = if (bySeason) {
                        state.value.groupBy { CloudResources.season(it.source) }.entries
                            .sortedWith(compareBy(nullsLast<Int>()) { it.key }).map { it.key to it.value }
                    } else {
                        listOf(null to state.value)
                    }
                    groups.forEach { (number, rows) ->
                        if (bySeason) {
                            item(key = "season-${provider.wire}-$number") {
                                Text(
                                    when (number) { null -> "未标注季"; 0 -> "特别篇"; else -> "第 $number 季" } + " · ${rows.size} 个版本",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = Etkn.space.screen, top = 12.dp, bottom = 2.dp),
                                )
                            }
                        }
                        itemsIndexed(rows, key = { index, _ -> "${provider.wire}-$number-$index" }) { _, row ->
                            ResourceCard(row, submitted = row in submitted) { confirm = row }
                        }
                    }
                }
            }
        }
        GlassBackBar(title, collapsed = true, haze = haze, onBack = nav::back)
    }

    confirm?.let { row ->
        GlassDialog(
            onDismissRequest = { confirm = null },
            title = { Text("确认获取此版本") },
            text = { Text(CloudResources.label(row) + "\n\n将按 ETKN 配置转存或获取并入库；可能消耗服务端资源积分。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        message = vm.acquire(row).fold(
                            { runId -> "获取任务已提交" to ((if (runId > 0) "任务 #$runId 已创建，" else "获取任务已创建，") + "入库完成或失败时服务端会发送通知。") },
                            { "获取提交失败" to it.userMessage() },
                        )
                    }
                }) { Text("确认获取") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
    message?.let { (head, body) ->
        GlassDialog(
            onDismissRequest = { message = null },
            title = { Text(head) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun ResourceCard(row: CloudResource, submitted: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen, vertical = 5.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                CloudResources.versionLabel(row).takeIf { it.isNotEmpty() }?.let { TonePill(it, ChipTone.Default) }
                CloudResources.transferLabel(row).takeIf { it.isNotEmpty() }?.let { TonePill(it, if (it == "可转存") ChipTone.Info else ChipTone.Success) }
                if (submitted) TonePill("已提交", ChipTone.Success)
            }
            val chips = CloudResources.chips(row)
            if (chips.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) { chips.forEach { TonePill(it.text, it.tone) } }
            }
            val remark = (row.source["remark"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim().orEmpty()
            if (row.provider != CloudProvider.SharedPool && remark.isNotEmpty()) {
                Text(remark, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun TonePill(text: String, tone: ChipTone) {
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (tone) {
        ChipTone.Success -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ChipTone.Warning -> scheme.primaryContainer to scheme.onPrimaryContainer
        ChipTone.Info -> scheme.secondaryContainer to scheme.onSecondaryContainer
        ChipTone.Default -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
    Surface(shape = Corner.small, color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}
