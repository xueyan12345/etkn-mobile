package xyz.etkn.mobile.ui.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.etkn.mobile.AppGraph
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.ItemsPage
import xyz.etkn.mobile.data.model.MusicHome
import xyz.etkn.mobile.data.model.Playlist
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.userMessage
import xyz.etkn.mobile.ui.screens.library.PagedViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async

/**
 * 用户口径（2026-11-27，#33）**首页分类精简并重排**。
 *
 * 用户原话：
 *  「去掉三个画圈的部分，两个按钮，一个模块。然后剩下的按钮排下顺序，推荐/歌曲/歌手/歌单」
 *
 * 删掉的：
 *  · 专辑（Albums）—— 用户圈掉
 *  · 文件夹（Folders）—— 用户圈掉
 *  · 「最新专辑」模块 —— 用户圈掉（见 Recommended）
 *
 * 顺序改成：推荐 → 歌曲 → 歌手 → 歌单
 */
enum class MusicTab(val wire: String, val label: String) {
    Recommended("recommended", "推荐"),
    Songs("songs", "歌曲"),
    Artists("artists", "歌手"),
    Playlists("playlists", "歌单"),
}

class MusicLibrariesViewModel(private val graph: AppGraph) : ViewModel() {
    private val _libraries = MutableStateFlow<Load<List<Item>>>(Load.Loading)
    val libraries: StateFlow<Load<List<Item>>> = _libraries.asStateFlow()

    init { load() }

    fun load() {
        _libraries.value = Load.Loading
        viewModelScope.launch {
            _libraries.value = runCatching { graph.api.libraries().filter { it.collectionType == "music" } }
                .fold({ list ->
                    if (graph.music.libraryId.value == null || list.none { it.id == graph.music.libraryId.value }) {
                        list.firstOrNull()?.let { graph.music.selectLibrary(it.id) }
                    }
                    Load.Ready(list)
                }, { Load.Failed(it.userMessage()) })
        }
    }
}

class MusicRecommendedViewModel(private val graph: AppGraph, private val parentId: String) : ViewModel() {
    private val _state = MutableStateFlow<Load<MusicHome>>(Load.Loading)
    val state: StateFlow<Load<MusicHome>> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = runCatching { graph.api.musicHome(parentId, 12) }.fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })
        }
    }
}

class MusicPlaylistsViewModel(private val graph: AppGraph, private val parentId: String) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<Playlist>>>(Load.Loading)
    val state: StateFlow<Load<List<Playlist>>> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = runCatching { graph.api.playlists(parentId) }.fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })
        }
    }

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { runCatching { graph.api.createPlaylist(name) }; load() }
    }

    fun delete(playlist: Playlist) {
        viewModelScope.launch { runCatching { graph.api.deletePlaylist(playlist.key) }; load() }
    }
}

/** Paged `U/Music` listing for a tab / group key. Folder entries and artist bio come with page 0. */
class MusicListViewModel(
    private val graph: AppGraph,
    private val parentId: String,
    private val tab: String,
    private val key: String,
    private val tracksOnly: Boolean,
) : PagedViewModel() {
    private val _folders = MutableStateFlow<List<Item>>(emptyList())
    val folders: StateFlow<List<Item>> = _folders.asStateFlow()
    private val _overview = MutableStateFlow<String?>(null)
    val overview: StateFlow<String?> = _overview.asStateFlow()
    override val pageSize = if (tab == "albums" && key.isNotEmpty()) 200 else 60

    init { reset() }

    override suspend fun fetch(start: Int, limit: Int): ItemsPage {
        val page = graph.api.musicList(parentId, tab, key, start, limit, tracksOnly)
        if (start == 0) {
            _folders.value = page.folders
            _overview.value = page.artistOverview
        }
        return ItemsPage(page.items, page.total)
    }

    val tracks: List<Item> get() = paged.value.items.filter { it.isAudio && !it.isMusicGroup }

    /**
     * 用户口径（2026-11-27，#34）**歌曲列表的排序方式**。
     *
     * 用户原话（截图里的排序菜单）：
     *  「播放全部按钮，然后右边有个排序菜单，可以点开之后按照选择的方式排序，
     *    点这个播放全部就会把这些歌曲全部加入播放列表，并且顺序就按照排序的顺序添加到播放列表」
     *
     * 四种方式与参考图一致：添加时间 / 首字母 / 歌手首字母 / 播放次数。
     */
    private val _sort = MutableStateFlow(MusicSort.AddedTime)
    val sort: StateFlow<MusicSort> = _sort.asStateFlow()

    /** 排序后的已加载列表（UI 直接用它渲染，切换排序立刻生效）。 */
    val sortedTracks: List<Item> get() = applySort(tracks, _sort.value)

    fun setSort(value: MusicSort) {
        _sort.value = value
    }

    /**
     * 用户口径（2026-11-27，#34）**「播放全部」要把整张列表都加进队列，按当前排序**。
     *
     * 难点：列表是分页的（每页 60，601 首要 11 页），
     * 直接拿已加载的 60 条去播，用户点「播放全部 601 首」实际只有 60 首。
     *
     * 所以这里先把剩余页全部拉完（并发 4 路），再按当前排序整批加入队列。
     */
    fun playAll(onDone: (Int) -> Unit = {}) {
        viewModelScope.launch {
            val all = loadEverything()
            val ordered = applySort(all, _sort.value)
            if (ordered.isNotEmpty()) {
                graph.music.play(ordered, 0, "$tab:$key")
                onDone(ordered.size)
            }
        }
    }

    fun shuffle() {
        val list = tracks.shuffled()
        if (list.isNotEmpty()) graph.music.play(list, 0, "$tab:$key")
    }

    fun play(track: Item?) {
        val list = sortedTracks
        if (list.isEmpty()) return
        val index = track?.let { t -> list.indexOfFirst { it.id == t.id } }?.coerceAtLeast(0) ?: 0
        graph.music.play(list, index, "$tab:$key")
    }

    /** 把整张列表（所有分页）拉完；已加载的部分直接复用，只补后面的页。 */
    private suspend fun loadEverything(): List<Item> {
        val loaded = paged.value.items.toMutableList()
        var start = loaded.size
        val total = paged.value.total
        if (total <= start) return loaded.filter { it.isAudio && !it.isMusicGroup }
        // 剩余页分批并发，每批 4 个请求
        val starts = (start until total step pageSize).toList()
        for (batch in starts.chunked(4)) {
            val parts = coroutineScope {
                batch.map { s -> async { runCatching { fetch(s, pageSize).items }.getOrDefault(emptyList()) } }.awaitAll()
            }
            parts.forEach { loaded += it }
            start += batch.size * pageSize
        }
        return loaded.filter { it.isAudio && !it.isMusicGroup }.distinctBy { it.id }
    }
}

/**
 * 用户口径（2026-11-27，#34）**歌曲排序方式**（和参考图的四项一致）。
 */
enum class MusicSort(val label: String) {
    AddedTime("按添加时间排序"),
    FirstLetter("按首字母排序"),
    ArtistLetter("按歌手首字母"),
    PlayCount("按播放次数排序"),
}

/**
 * 按 [sort] 排序。中文用拼音首字母（`pinyinInitial`），
 * 缺数据时退回原顺序，不会因为字段为空而乱序。
 */
/**
 * 用户口径（2026-11-27，#34）**中文按拼音首字母排序**。
 *
 * 不引第三方拼音库（AGENTS.md 里明确禁止为了小功能加依赖）。
 * 实现方式：GBK 编码区间映射 —— 汉字按拼音在 GBK 里是连续区段，
 * 拿每个区段的首字定边界即可。
 *
 * 非中文直接取首字符（英文歌按字母排），数字/符号统一排到 `#`。
 */
private val PINYIN_BOUNDS: List<Pair<Char, Char>> = listOf(
    // 每个区间的首字 → 首字母
    'A' to '阿', 'B' to '八', 'C' to '擦', 'D' to '搭', 'E' to '蛾',
    'F' to '发', 'G' to '噶', 'H' to '哈', 'J' to '击', 'K' to '喀',
    'L' to '垃', 'M' to '妈', 'N' to '拿', 'O' to '哦', 'P' to '啪',
    'Q' to '期', 'R' to '然', 'S' to '撒', 'T' to '塌', 'W' to '挖',
    'X' to '昔', 'Y' to '压', 'Z' to '匝',
)

private fun pinyinInitial(text: String): String {
    val c = text.trim().firstOrNull() ?: return "#"
    if (c.code < 0x4E00 || c.code > 0x9FA5) {
        // 非汉字：英文按大写字母，其余归到 #
        return if (c.isLetter()) c.uppercaseChar().toString() else "#"
    }
    // 找到最后一个 <= c 的边界（区段是单调递增的）
    var result = "#"
    for ((letter, bound) in PINYIN_BOUNDS) {
        if (c >= bound) result = letter.toString() else break
    }
    return result
}

private fun applySort(list: List<Item>, sort: MusicSort): List<Item> = when (sort) {
    MusicSort.AddedTime -> list.sortedByDescending { it.dateCreated ?: "" }
    MusicSort.FirstLetter -> list.sortedWith(compareBy({ pinyinInitial(it.name) }, { it.name.lowercase() }))
    MusicSort.ArtistLetter -> list.sortedWith(
        compareBy({ pinyinInitial(it.artistLine) }, { it.artistLine.lowercase() }, { pinyinInitial(it.name) }),
    )
    MusicSort.PlayCount -> list.sortedByDescending { it.userData.playCount }
}
