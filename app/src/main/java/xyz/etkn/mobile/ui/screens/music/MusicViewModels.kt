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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.combine

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
/**
 * 用户口径（2026-11-27，#11）**按字母/拼音排序用系统 Collator**。
 *
 * 为什么不用自建的「汉字 Unicode 区段 → 首字母」表：
 * 同一区段里常混着不同拼音首字母的字（阿 a / 波 b / 次 c 可能相邻），
 * 靠区段猜必然出错。`Collator.getInstance(Locale.CHINA)` 是 JDK 内置的
 * 中文排序规则，**本身就是按拼音排的**，这才是正统做法。
 */
private val COLLATOR: java.util.Comparator<String> =
    java.text.Collator.getInstance(java.util.Locale.CHINA).let { c ->
        c.strength = java.text.Collator.PRIMARY
        java.util.Comparator { a, b -> c.compare(a, b) }
    }

/** 是否以汉字开头（用于把中文排在非中文前面）。 */
private fun isCjk(text: String): Boolean {
    val c = text.trim().firstOrNull() ?: return false
    return Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
}

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

    /**
     * 用户口径（2026-11-27，#47）**排序必须真的让列表变**。
     *
     * 修的 bug（用户原话）：
     *  「必须要保证点完之后，这个排序要好使，不能光把这个按钮加上」
     *
     * 根因：原来写的是
     * ```
     * val sortedTracks: List<Item> get() = applySort(tracks, _sort.value)
     * ```
     * 这是个**普通 getter**，Compose 在重组时读到它不会订阅 `_sort` 的变化，
     * 所以点排序后 `_sort.value` 变了、`sortedTracks` 算出来也变了，
     * **但界面不会重组** → 看起来就是「点了没反应」。
     *
     * 现在改成 StateFlow：排序变化 + 分页数据变化都会推一次新列表，
     * UI 用 collectAsStateWithLifecycle 订阅它，一定会刷新。
     */
    private val _sortedTracks = MutableStateFlow<List<Item>>(emptyList())
    val sortedTracks: StateFlow<List<Item>> = _sortedTracks.asStateFlow()

    init {
        // 排序或分页数据任一变化，就重算一次并推给 UI
        viewModelScope.launch {
            combine(_sort, _paged) { sort, page -> applySort(page.items.filter { it.isAudio && !it.isMusicGroup }, sort) }
                .collect { _sortedTracks.value = it }
        }
    }

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
            /** 用户口径（#47）「播放全部」按当前排序整体进队列。 */
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
        val list = _sortedTracks.value.ifEmpty { tracks }
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
            val parts: List<List<Item>> = coroutineScope {
                val deferreds = batch.map { pageStart ->
                    async<List<Item>> {
                        runCatching { fetch(pageStart, pageSize).items }.getOrDefault(emptyList())
                    }
                }
                deferreds.awaitAll()
            }
            parts.forEach { page -> loaded += page }
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
    /**
     * 用户口径（2026-11-27，#11）**按字母排序必须是 A → B → C 的顺序**。
     *
     * 修的 bug（用户原话）：
     *  「这个按字母排序也不对呀」「按字母排序不应该是A，然后B然后C吗」
     *
     * 根因有两个：
     *
     * ① **`#` 排在 `A` 前面**。原来直接按 `pinyinInitial()` 的返回值做字符串比较，
     *    而非字母开头的（数字、符号、生僻字）统一返回 `"#"`，
     *    而 `'#'` 的码位是 0x23，比 `'A'` 的 0x41 小 ——
     *    所以列表变成 `# A B C ...`，用户看到的「顺序不对」就是这些 `#` 项全挤在最上面。
     *
     * ② **汉字按 Unicode 区段切字母本身不准**。同一区段里往往混着不同拼音首字母的字
     *    （比如「阿(a)/波(b)/次(c)」可能被划进同一段），靠区段猜必然错。
     *
     * 现在的做法：
     *  · 中文项按 **拼音全串** 排序（`Pinyin.full()`，见下），首字母自然就对了
     *  · 非中文项（英文/数字/符号）排在字母之后（`#` 放到最后，符合常见音乐 App 习惯）
     *  · 首字母索引也只从中文/英文本里取，不再出现 `#` 打头
     */
    /** 中文在前、非中文在后，同组内用系统 Collator 按拼音比较。 */
    MusicSort.FirstLetter -> list.sortedWith(
        compareBy({ if (isCjk(it.name)) 0 else 1 }, { it.name }, COLLATOR),
    )
    MusicSort.ArtistLetter -> list.sortedWith(
        compareBy({ if (isCjk(it.artistLine)) 0 else 1 }, { it.artistLine }, COLLATOR, compareBy { it.name }),
    )
    MusicSort.PlayCount -> list.sortedByDescending { it.userData.playCount }
}
