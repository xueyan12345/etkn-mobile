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
import xyz.etkn.mobile.ui.components.InlineLoading
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import xyz.etkn.mobile.data.net.int

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
    /**
     * 用户口径（2026-11-26）**选集弹窗**（对应 ETKN 网页端的集号网格）。
     *  · picking         = 正在选集的那个资源（null = 没在选）
     *  · pickingChildren = 该资源的分集清单（null = 还在加载）
     */
    var picking by remember { mutableStateOf<CloudResource?>(null) }
    var pickingChildren by remember { mutableStateOf<List<CloudResource>?>(null) }
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
                            /**
                         * 用户口径（2026-11-26）：点资源卡先拉分集清单，
                         * 有分集就弹集号网格（可单集转存），没有就弹原来的整季确认框。
                         */
                        ResourceCard(row, submitted = row in submitted) {
                            picking = row
                            pickingChildren = null
                            scope.launch {
                                val kids = vm.children(row)
                                if (kids.isEmpty()) {
                                    // 没有分集信息（电影 / 无权限）→ 原整季确认
                                    picking = null
                                    confirm = row
                                } else {
                                    pickingChildren = kids
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
        GlassBackBar(title, collapsed = true, haze = haze, onBack = nav::back)
    }

    /**
     * 用户口径（2026-11-26）**选集弹窗**（集号网格，对应 ETKN 网页端）。
     *
     *  · 顶部：资源标题 + 版本信息
     *  · 中部：集号网格（每格一个集号，可多选）
     *  · 底部：全选 / 整季全转 / 只转选中
     *
     * 转存时把选中集的 sha1/size/source_id 逐个提交，
     * 服务端按 sha1 精确匹配到单文件（实证见 EtknApi.acquire 注释）。
     */
    picking?.let { row ->
        val kids = pickingChildren
        var chosen by remember(row) { mutableStateOf<Set<Int>>(emptySet()) }
        GlassDialog(
            onDismissRequest = { picking = null; pickingChildren = null },
            title = { Text("选择要转存的集") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        CloudResources.label(row),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    when {
                        kids == null -> {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { InlineLoading() }
                        }
                        kids.isEmpty() -> {
                            Text("这个资源没有分集信息，只能整季转存。", style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> {
                            Text(
                                "共 ${kids.size} 集，点集号多选；不选 = 整季全转",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            // 集号网格：每行 6 个
                            kids.chunked(6).forEach { line ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    line.forEach { ep ->
                                        val n = ep.source.int("episode_number")
                                        val on = n > 0 && n in chosen
                                        Surface(
                                            onClick = {
                                                if (n > 0) chosen = if (on) chosen - n else chosen + n
                                            },
                                            shape = Corner.small,
                                            color = if (on) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                                            contentColor = if (on) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f).height(40.dp),
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    if (n > 0) n.toString().padStart(2, '0') else "?",
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            }
                                        }
                                    }
                                    // 补齐空位，保证每行宽度一致
                                    repeat(6 - line.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = kids != null,
                    onClick = {
                        val picked = kids.orEmpty().filter { ep ->
                            val n = ep.source.int("episode_number")
                            n > 0 && n in chosen
                        }
                        picking = null
                        pickingChildren = null
                        scope.launch {
                            val result = if (picked.isEmpty()) {
                                // 没选 = 整季全转
                                vm.acquire(row)
                            } else {
                                // 逐个提交选中的集
                                var ok = 0
                                var fail = 0
                                var lastRun = 0L
                                picked.forEach { ep ->
                                    vm.acquire(row, ep).fold(
                                        { runId -> ok++; if (runId > 0) lastRun = runId },
                                        { fail++ },
                                    )
                                }
                                if (fail == 0) Result.success(lastRun)
                                else if (ok > 0) Result.success(lastRun).also {
                                    message = "部分提交失败" to "成功 $ok 集，失败 $fail 集。"
                                } else Result.failure(IllegalStateException("全部提交失败"))
                            }
                            message = result.fold(
                                { runId -> "获取任务已提交" to ((if (runId > 0) "任务 #$runId 已创建，" else "获取任务已创建，") + "入库完成或失败时服务端会发送通知。") },
                                { "获取提交失败" to it.userMessage() },
                            )
                        }
                    },
                ) { Text(if (chosen.isEmpty()) "整季全转" else "转存选中 ${chosen.size} 集") }
            },
            dismissButton = { TextButton(onClick = { picking = null; pickingChildren = null }) { Text("取消") } },
        )
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
