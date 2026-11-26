package xyz.etkn.mobile.ui.screens.music

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.Playlist
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.actions.PopupChoice
import xyz.etkn.mobile.ui.actions.rememberPopupAnchor
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.graphViewModel
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.ErrorState
import xyz.etkn.mobile.ui.components.GlassBackBar
import xyz.etkn.mobile.ui.components.GlassDialog
import xyz.etkn.mobile.ui.components.InlineLoading
import xyz.etkn.mobile.ui.components.LoadingState
import xyz.etkn.mobile.ui.components.MessageState
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.PillToggle
import xyz.etkn.mobile.ui.components.SectionHeader
import xyz.etkn.mobile.ui.components.SquareCard
import xyz.etkn.mobile.ui.components.formatClock
import xyz.etkn.mobile.ui.components.glassBarClearance
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.components.images
import xyz.etkn.mobile.ui.components.px
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.nav.MusicListRoute
import xyz.etkn.mobile.ui.screens.library.LargeTitle
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.Corner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import xyz.etkn.mobile.ui.nav.MusicSearchRoute
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding

@Composable
fun MusicHomeScreen() {
    val graph = LocalContext.current.graph
    val vm = graphViewModel { MusicLibrariesViewModel(it) }
    val libraries by vm.libraries.collectAsStateWithLifecycle()
    val selected by graph.music.libraryId.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    var tab by rememberSaveable { mutableStateOf(MusicTab.Recommended) }
    RequestNotificationsOnce()

    /**
     * 用户口径（2026-11-27，#33）**去掉「音乐」大标题，整体上移，右侧加音乐专用搜索**。
     *
     * 用户原话：「音乐界面把最上方的音乐俩字去掉，不要留空，整体上移」
     *          「再右边加个搜索功能。这个功能专门用来搜索音乐的，不要搜索其他库，
     *            就搜索音乐。搜专辑/歌手/歌名都可以」
     */
    /**
     * 用户口径（2026-11-27，#36）**顶部补状态栏间距，对齐探索页**。
     *
     * 修的 bug（用户原话）：「界面提的太高了，你可以参考探索界面的那个最上边的高度是到哪？
     *                        现在提的有点太高了，都已经压住状态栏了」
     *
     * 根因：删掉 `LargeTitle("音乐")` 时，它内部带的 `windowInsetsPadding(statusBars)`
     * 也一起没了 → 整个 Column 从屏幕最顶端开始画 → 压住状态栏。
     *
     * 探索页的写法（对齐参考）：`statusBars` 内边距 + 一点呼吸空间。
     */
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 4.dp),
    ) {
        when (val s = libraries) {
            Load.Loading -> LoadingState()
            is Load.Failed -> ErrorState(s.message, vm::load)
            is Load.Ready -> {
                if (s.value.isEmpty()) {
                    MessageState("服务器上没有音乐库", icon = Icons.Rounded.MusicNote)
                    return@Column
                }
                if (s.value.size > 1) {
                    LazyRow(contentPadding = PaddingValues(horizontal = Etkn.space.screen), horizontalArrangement = Arrangement.spacedBy(PillGap)) {
                        items(s.value, key = { it.id }) { lib ->
                            PillToggle(checked = lib.id == selected, onClick = { graph.music.selectLibrary(lib.id) }) { Text(lib.name) }
                        }
                    }
                }
                /**
                 * 分类胶囊 + 最右侧音乐搜索按钮（#33）。
                 *
                 * 搜索按钮固定在行尾不随胶囊滚动，位置稳定可见。
                 * 点击进入 `MusicSearchRoute(parentId)` —— 只搜当前音乐库，
                 * 不会像全局搜索那样把影视库也扫进来。
                 */
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(PillGap),
                    ) {
                        items(MusicTab.entries.toList()) { t ->
                            PillToggle(checked = t == tab, onClick = { tab = t }) { Text(t.label) }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { selected?.let { nav.to(MusicSearchRoute(it)) } }) {
                        Icon(Icons.Rounded.Search, "搜索音乐")
                    }
                }
                val parent = selected ?: return@Column
                when (tab) {
                    MusicTab.Recommended -> Recommended(parent)
                    MusicTab.Playlists -> Playlists(parent)
                    else -> MusicListBody(MusicListRoute(parent, tab.wire, "", tab.label), header = null)
                }
            }
        }
    }
}

@Composable
private fun RequestNotificationsOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun Recommended(parentId: String) {
    val vm = graphViewModel(key = "rec-$parentId") { MusicRecommendedViewModel(it, parentId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val graph = LocalContext.current.graph
    when (val s = state) {
        Load.Loading -> LoadingState()
        is Load.Failed -> ErrorState(s.message, vm::load)
        is Load.Ready -> LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
            /**
             * 用户口径（2026-11-27，#33）**删掉「最新专辑」模块**（用户圈掉的那块）。
             * 推荐页只保留「最近听的」和「最近常听」两个歌曲列表。
             */
            trackSection("recent", "最近听的", s.value.recent) { graph.music.play(s.value.recent, s.value.recent.indexOf(it), "recent") }
            trackSection("frequent", "最近常听", s.value.frequent) { graph.music.play(s.value.frequent, s.value.frequent.indexOf(it), "frequent") }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.trackSection(key: String, title: String, tracks: List<Item>, onPlay: (Item) -> Unit) {
    if (tracks.isEmpty()) return
    item(key = "$key-head") { SectionHeader(title, Modifier.padding(top = Etkn.space.section - 8.dp)) }
    items(tracks, key = { "$key-${it.id}" }) { TrackRow(it, onClick = { onPlay(it) }) }
}

@Composable
private fun Playlists(parentId: String) {
    val vm = graphViewModel(key = "pl-$parentId") { MusicPlaylistsViewModel(it, parentId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Playlist?>(null) }
    when (val s = state) {
        Load.Loading -> LoadingState()
        is Load.Failed -> ErrorState(s.message, vm::load)
        is Load.Ready -> LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
            item {
                FilledTonalButton(onClick = { creating = true }, modifier = Modifier.padding(horizontal = Etkn.space.screen, vertical = 8.dp)) {
                    Icon(Icons.Rounded.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("新建歌单")
                }
            }
            items(s.value, key = { it.key }) { list ->
                ListItem(
                    onClick = { nav.to(MusicListRoute(parentId, "playlists", list.key, list.name)) },
                    supportingContent = { Text("${list.count} 首") },
                    leadingContent = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
                    trailingContent = { IconButton(onClick = { deleting = list }) { Icon(Icons.Rounded.Delete, "删除歌单") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(list.name) }
            }
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        GlassDialog(
            onDismissRequest = { creating = false },
            title = { Text("新建歌单") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, singleLine = true, label = { Text("名称") }) },
            confirmButton = { TextButton(onClick = { vm.create(name); creating = false }, enabled = name.isNotBlank()) { Text("创建") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }
    deleting?.let { list ->
        GlassDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除歌单“${list.name}”？") },
            text = { Text("只删除歌单，保留音乐文件。") },
            confirmButton = { TextButton(onClick = { vm.delete(list); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
fun MusicListScreen(route: MusicListRoute) {
    val nav = LocalNav.current
    val haze = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        MusicListBody(route, header = route.title, top = glassBarClearance(), modifier = Modifier.glassSource(haze))
        GlassBackBar(route.title, collapsed = true, haze = haze, onBack = nav::back)
    }
}

/** Groups render as a square-cover grid; tracks as full-width rows (one grid, span-aware). */
@Composable
private fun MusicListBody(route: MusicListRoute, header: String?, top: Dp = 0.dp, modifier: Modifier = Modifier) {
    val vm = graphViewModel(key = "ml-${route.parentId}-${route.tab}-${route.key}-${route.tracksOnly}") {
        MusicListViewModel(it, route.parentId, route.tab, route.key, route.tracksOnly)
    }
    val paged by vm.paged.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val overview by vm.overview.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val grid = rememberLazyGridState()
    /**
     * 用户口径（#47）**必须在 Composable 作用域订阅排序结果**。
     *
     * `collectAsStateWithLifecycle` 是 @Composable，不能写在 LazyVerticalGrid 的
     * item 作用域里（那里不是 Composable 上下文，会报
     * 「@Composable invocations can only happen from the context of a @Composable function」）。
     */
    val sortedList by vm.sortedTracks.collectAsStateWithLifecycle()
    val shouldLoad by remember(paged) {
        derivedStateOf { paged.canLoadMore && (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= paged.items.size - 12 }
    }
    LaunchedEffect(grid, paged) { snapshotFlow { shouldLoad }.collect { if (it) vm.loadMore() } }

    when {
        paged.items.isEmpty() && folders.isEmpty() && paged.loading -> LoadingState()
        paged.items.isEmpty() && folders.isEmpty() && paged.error != null -> ErrorState(paged.error!!, vm::retry)
        paged.items.isEmpty() && folders.isEmpty() && paged.endReached -> MessageState("暂无音乐", icon = Icons.Rounded.MusicNote)
        else -> LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(140.dp),
            contentPadding = PaddingValues(start = Etkn.space.screen, end = Etkn.space.screen, top = top, bottom = LocalBottomInset.current),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().then(modifier),
        ) {
            overview?.takeIf { it.isNotBlank() }?.let { bio ->
                item(span = { GridItemSpan(maxLineSpan) }, key = "bio") { ArtistBio(bio) }
            }
            val hasTracks = paged.items.any { it.isAudio && !it.isMusicGroup }
            /**
             * 用户口径（2026-11-27，#34）**列表顶部：播放全部 + 排序菜单**。
             *
             * 用户原话：
             *  「首先就是播放全部按钮，然后右边有个排序菜单，可以点开之后按照选择的方式排序，
             *    点这个播放全部就会把这些歌曲全部加入播放列表，
             *    并且顺序就按照排序的顺序添加到播放列表」
             *
             * 注意「播放全部」不再是原来的 `vm.play(null)`（那只播已加载的 60 条），
             * 改成 `vm.playAll()` —— 会先把剩余分页拉完（601 首都进队列），再按当前排序播。
             */
            if (header != null && hasTracks) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "play-all") {
                    var menu by remember { mutableStateOf(false) }
                    var count by remember { mutableStateOf<Int?>(null) }
                    val sort by vm.sort.collectAsStateWithLifecycle()
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        /**
                         * 用户口径（2026-11-27，#47）**按钮上要显示有多少首**。
                         *
                         * 用户原话：「加一个全部播放的这个按钮，然后要显示多少首歌」。
                         *
                         * 数字来源优先级：
                         *  · 已经点过播放全部 → 用实际进队列的数（最准）
                         *  · 否则用分页返回的 total（服务端总数，601 这种）
                         */
                        val totalSongs = paged.total.takeIf { it > 0 } ?: sortedList.size
                        Button(
                            onClick = { vm.playAll { count = it } },
                            modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                count?.let { "播放全部 $it 首" }
                                    ?: if (totalSongs > 0) "播放全部 · $totalSongs 首" else "播放全部",
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        FilledTonalButton(onClick = vm::shuffle, modifier = Modifier.height(ButtonDefaults.MediumContainerHeight)) {
                            Icon(Icons.Rounded.Shuffle, null)
                            Spacer(Modifier.width(6.dp))
                            Text("随机")
                        }
                        Spacer(Modifier.weight(1f))
                        /** 排序菜单：点开选一种，列表立刻按它重排；「播放全部」也照这个顺序进队列。 */
                        Box {
                            FilledTonalIconButton(onClick = { menu = true }) {
                                Icon(Icons.Rounded.Sort, "排序")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                MusicSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = { vm.setSort(option); menu = false },
                                        trailingIcon = {
                                            if (option == sort) Icon(Icons.Rounded.Check, null)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (route.tab == "artists" && route.key.isNotEmpty() && !route.tracksOnly) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "artist-tracks") {
                    TextButton(onClick = { nav.to(route.copy(tracksOnly = true, title = "${route.title} · 全部歌曲")) }) { Text("播放歌手全部歌曲") }
                }
            }
            items(folders, key = { "f-" + (it.key ?: it.id) }) { folder -> SquareCard(folder, onClick = { nav.open(folder) }, width = null) }
            /** 用户口径（#47）列表按当前排序显示（`sortedList` 在 Composable 顶层订阅）。 */
            val ordered = if (route.tab == "songs" || route.tracksOnly) sortedList else paged.items
            ordered.forEach { entry ->
                if (entry.isAudio && !entry.isMusicGroup) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "t-" + entry.id, contentType = "track") {
                        TrackRow(entry, onClick = { vm.play(entry) }, horizontalPadding = 0.dp)
                    }
                } else {
                    item(key = "g-" + (entry.key ?: entry.id), contentType = "group") {
                        SquareCard(entry, onClick = { nav.open(entry) }, width = null)
                    }
                }
            }
            if (paged.loading) item(span = { GridItemSpan(maxLineSpan) }) { InlineLoading() }
            if (paged.error != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FilledTonalButton(onClick = vm::retry) { Text("加载失败，重试") } }
                }
            }
        }
    }
}

@Composable
private fun ArtistBio(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.animateContentSize().clickable { expanded = !expanded }.padding(vertical = 4.dp),
    )
}

@Composable
fun TrackRow(track: Item, onClick: () -> Unit, horizontalPadding: androidx.compose.ui.unit.Dp = Etkn.space.screen) {
    val graph = LocalContext.current.graph
    val music by graph.music.state.collectAsStateWithLifecycle()
    val playing = music.current?.id == track.id
    var picker by remember { mutableStateOf(false) }
    val more = rememberPopupAnchor()
    val choices = {
        listOf(
            PopupChoice("加入播放队列", Icons.AutoMirrored.Rounded.QueueMusic) { graph.music.append(listOf(track)) },
            PopupChoice("添加到歌单", Icons.AutoMirrored.Rounded.PlaylistAdd) { picker = true },
        )
    }
    Row(
        Modifier.fillMaxWidth().clip(Corner.card)
            .combinedClickable(onClick = onClick, onLongClick = { more.open(choices()) })
            .background(if (playing) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .padding(horizontal = horizontalPadding.coerceAtLeast(8.dp), vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(images().music(track, 56.dp.px()), Modifier.size(48.dp).clip(Corner.small), track.name, placeholder = Icons.Rounded.MusicNote)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                track.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                listOfNotNull(track.artistLine.ifEmpty { null }, track.album).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (playing && music.isPlaying) Icon(Icons.Rounded.Equalizer, "正在播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        else if (track.runtimeMs > 0) Text(formatClock(track.runtimeMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { more.open(choices()) }, modifier = more.modifier) { Icon(Icons.Rounded.MoreVert, "更多") }
    }
    if (picker) PlaylistPicker(track, onDismiss = { picker = false })
}

@Composable
private fun PlaylistPicker(track: Item, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    val parent by graph.music.libraryId.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var lists by remember { mutableStateOf<List<Playlist>?>(null) }
    LaunchedEffect(parent) { lists = runCatching { graph.api.playlists(parent ?: "") }.getOrDefault(emptyList()) }
    GlassDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加到歌单") },
        text = {
            val current = lists
            when {
                current == null -> InlineLoading()
                current.isEmpty() -> Text("还没有歌单，请先在“歌单”中新建")
                else -> Column {
                    current.forEach { list ->
                        Text(
                            "${list.name}  ·  ${list.count} 首",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clip(Corner.card).clickable {
                                scope.launch { runCatching { graph.api.addToPlaylist(list.key, track.id) }; onDismiss() }
                            }.padding(vertical = 12.dp, horizontal = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
