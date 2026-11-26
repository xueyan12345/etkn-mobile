package xyz.etkn.mobile.ui.screens.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.debounce
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.components.ErrorState
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.components.LoadingState
import xyz.etkn.mobile.ui.components.MessageState
import xyz.etkn.mobile.ui.common.graphViewModel
import xyz.etkn.mobile.ui.components.GlassBackBar
import xyz.etkn.mobile.ui.components.glassBarClearance
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.nav.MusicSearchRoute
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.theme.Etkn
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.chrisbanes.haze.rememberHazeState
import xyz.etkn.mobile.ui.components.SquareCard
import androidx.compose.foundation.lazy.grid.items

/**
 * 用户口径（2026-11-27，#33）**音乐专用搜索页**。
 *
 * 用户原话：
 *  「再右边加个搜索功能。这个功能专门用来搜索音乐的，不要搜索其他库，
 *    就搜索音乐。搜专辑/歌手/歌名都可以」
 *
 * 与全局搜索的区别：
 *  · 只搜**当前这个音乐库**（[MusicSearchRoute.parentId]），不扫影视库
 *  · 不出「在线共享池」那部分（那是全局搜索的行为）
 *  · 结果分三块：歌曲（可直点播放）、专辑、歌手
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSearchScreen(route: MusicSearchRoute) {
    val nav = LocalNav.current
    val haze = rememberHazeState()
    val vm = graphViewModel(key = "msearch-${route.parentId}") { MusicSearchViewModel(it, route.parentId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val grid = rememberLazyGridState()
    /** 在外层取出：内层 `onClick` 是普通 lambda，不能直接调 LocalContext.current。 */
    val music = LocalContext.current.graph.music

    // 输入停顿 320ms 再发请求，避免每敲一个字打一次服务端
    LaunchedEffect(Unit) {
        snapshotFlow { query }
            .debounce(320)
            .collect { vm.search(it) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(glassBarClearance()))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = Etkn.space.screen)
                    .padding(top = 4.dp, bottom = 8.dp),
                singleLine = true,
                placeholder = { Text("搜歌名 / 歌手 / 专辑") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, "清空") }
                    }
                },
                shape = Corner.pill,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            )

            when (val s = state) {
                Load.Loading -> LoadingState()
                is Load.Failed -> ErrorState(s.message, { vm.search(query) })
                is Load.Ready -> {
                    val result = s.value
                    if (query.isBlank()) {
                        MessageState("搜歌名、歌手或专辑", icon = Icons.Rounded.MusicNote)
                    } else if (result.isEmpty) {
                        MessageState("没有找到「$query」", icon = Icons.Rounded.MusicNote)
                    } else {
                        LazyVerticalGrid(
                            state = grid,
                            columns = GridCells.Adaptive(140.dp),
                            contentPadding = PaddingValues(
                                start = Etkn.space.screen,
                                end = Etkn.space.screen,
                                bottom = LocalBottomInset.current,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            /** ① 歌曲：直接点就播（这是最常用的） */
                            if (result.tracks.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "h-tracks") {
                                    SectionLabel("歌曲 · ${result.tracks.size}")
                                }
                                result.tracks.forEach { track ->
                                    item(span = { GridItemSpan(maxLineSpan) }, key = "t-${track.id}") {
                                        TrackRow(
                                            track,
                                            onClick = {
                                                keyboard?.hide()
                                                music.play(result.tracks, result.tracks.indexOf(track), "search")
                                            },
                                            horizontalPadding = 0.dp,
                                        )
                                    }
                                }
                            }
                            /** ② 专辑 */
                            if (result.albums.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "h-albums") {
                                    SectionLabel("专辑 · ${result.albums.size}")
                                }
                                items(result.albums.size, key = { "a-" + result.albums[it].name }) { i ->
                                    val album: Item = result.albums[i]
                                    /** 用户口径（#40）点专辑 → 按专辑名再搜一次，列出这张专辑的歌。 */
                                    SquareCard(
                                        album,
                                        onClick = { query = album.name },
                                        width = null,
                                    )
                                }
                            }
                            /** ③ 歌手 */
                            if (result.artists.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }, key = "h-artists") {
                                    SectionLabel("歌手 · ${result.artists.size}")
                                }
                                items(result.artists.size, key = { "ar-" + result.artists[it].name }) { i ->
                                    val artist: Item = result.artists[i]
                                    /** 用户口径（#40）点歌手 → 按歌手名再搜，列出他的歌。 */
                                    SquareCard(
                                        artist,
                                        onClick = { query = artist.name },
                                        width = null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        GlassBackBar("搜索音乐", collapsed = true, haze = haze, onBack = nav::back)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Etkn.space.section - 8.dp, bottom = 2.dp),
    )
}


/**
 * 用户口径（2026-11-27，#33）**音乐搜索 ViewModel**。
 *
 * 三路并发、各自限量，合并成一个结果集：
 *  · 歌曲：`Items?IncludeItemTypes=Audio&SearchTerm=&ParentId=音乐库`
 *  · 专辑：`Music?Tab=albums&Key=<term>`（服务端按关键字过滤）
 *  · 歌手：`Music?Tab=artists&Key=<term>`
 *
 * 为什么不复用全局的 `searchMusic()`：那个不传 ParentId，会把所有音乐库都扫进来，
 * 而且不带歌手分组 —— 用户明确要求「不要搜索其他库，就搜索音乐」。
 */
class MusicSearchViewModel(
    private val graph: xyz.etkn.mobile.AppGraph,
    private val parentId: String,
) : androidx.lifecycle.ViewModel() {
    private val api = graph.api
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<Load<MusicSearchResult>>(Load.Ready(MusicSearchResult()))
    val state: kotlinx.coroutines.flow.StateFlow<Load<MusicSearchResult>> = _state
    private var job: kotlinx.coroutines.Job? = null
    private var lastTerm = ""

    fun search(term: String) {
        val t = term.trim()
        if (t == lastTerm) return
        lastTerm = t
        job?.cancel()
        if (t.isEmpty()) {
            _state.value = Load.Ready(MusicSearchResult())
            return
        }
        job = viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = try {
                coroutineScope {
                    val tracks = async { fetchTracks(t) }
                    val albums = async { fetchGroups(t, "albums") }
                    val artists = async { fetchGroups(t, "artists") }
                    Load.Ready(
                        MusicSearchResult(
                            tracks = tracks.await(),
                            albums = albums.await(),
                            artists = artists.await(),
                        ),
                    )
                }
            } catch (e: Exception) {
                Load.Failed(e.message ?: "搜索失败")
            }
        }
    }

    /**
     * 用户口径（2026-11-27，#37）**搜索必须真的按关键字过滤**。
     *
     * 修的 bug：搜什么都返回同一批结果。
     *
     * 根因：原先把搜索词传给了 `musicList` 的 `Key` 参数，
     * 但那个 Key 是「分组过滤键」（专辑名/歌手名，精确匹配某个分组），
     * 不是搜索词 —— 传进去服务端当"没有过滤"处理，直接返整张列表。
     *
     * 实测证据（音乐库 20，601 首）：
     * ```
     * Key=邓紫棋        → 601 条（全部，搜索词被忽略）
     * SearchTerm=邓紫棋 → 14 条（正确）
     * ```
     *
     * 现在改用 Items 接口的 SearchTerm，并限定 ParentId 只在这个音乐库里搜。
     */
    private suspend fun fetchTracks(term: String): List<Item> = runCatching {
        api.searchMusicInLibrary(parentId, term, "Audio", 0, 120).first
            .filter { it.isAudio && !it.isMusicGroup }
    }.getOrDefault(emptyList())

    /**
     * 用户口径（2026-11-27，#37）**专辑 / 歌手从歌曲结果派生**。
     *
     * 实测：ETKN 音乐库里 `Items` 只有 `Type=Audio`，没有 `MusicAlbum`/`MusicArtist` 条目
     *（`Music?Tab=albums` 返回的也是 Audio，只是把名字换成了专辑名）。
     * 所以专辑/歌手直接从命中的歌曲上取 `album` / `artistLine` 去重，
     * 用 `copy()` 造一个分组条目（不手写几十个字段，避免漏字段编译失败）。
     */
    private suspend fun fetchGroups(term: String, tab: String): List<Item> {
        val tracks = fetchTracks(term)
        val byAlbum = tab == "albums"
        val seen = LinkedHashMap<String, Item>()
        tracks.forEach { track ->
            val key = if (byAlbum) track.album.orEmpty().trim() else track.artistLine.trim()
            if (key.isEmpty() || key in seen) return@forEach
            /**
             * 用户口径（2026-11-27，#40）**封面必须能显示**。
             *
             * 修的 bug：搜索结果里「专辑 · 7」那一排全是灰块，没有图。
             *
             * 根因：上一版用
             * ```
             * track.copy(id = "album:$key", name = key, ...)
             * ```
             * 造了一个**假 ID**。而 `SquareCard` 取图的规则是
             * `images().music(item, ...)` → 内部用 `item.id` 拼
             * `/api/tv/Items/{id}/Images/Primary` —— 服务端不认识 `album:xxx`
             * 这种 id，直接 404，于是显示占位图。
             *
             * 现在：**id 保持该专辑下第一首歌的真实 itemId**（这样封面能正常拿到，
             * 因为歌曲自己带 Primary 图），只把 `name` 换成专辑名/歌手名用于显示。
             * 点进去时按 `name` 去搜这个专辑/歌手的歌，不依赖 id。
             */
            seen[key] = track.copy(
                name = key,
                type = if (byAlbum) "MusicAlbum" else "MusicArtist",
                isFolder = true,
            )
        }
        return seen.values.toList()
    }
}

/**
 * 用户口径（2026-11-27，#33）音乐搜索结果：歌曲 / 专辑 / 歌手 三块。
 */
data class MusicSearchResult(
    val tracks: List<Item> = emptyList(),
    val albums: List<Item> = emptyList(),
    val artists: List<Item> = emptyList(),
) {
    val isEmpty: Boolean get() = tracks.isEmpty() && albums.isEmpty() && artists.isEmpty()
}
