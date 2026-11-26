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
/**
 * 用户口径（2026-11-27，#11/#13）**按首字母排序必须从 A 开始，且不能崩**。
 *
 * 之前用 `Collator.getInstance(Locale.CHINA)` 直接比较整串：
 *  · 崩溃：`Comparison method violates its general contract!`
 *    —— Collator 在多音字/变体上不满足传递性，TimSort 直接抛异常（点了就闪退）
 *  · 顺序错：用户看到第一个是「以沫」而不是 a 开头的歌
 *
 * 现在的做法（简单、稳定、可验证）：
 *  ① 从歌名/歌手名里取出**拼音首字母**（英文取首字母大写、数字/符号归 #）
 *  ② 先按「首字母」排，再按「原串小写」排
 *  ③ 纯 `String.compareTo`，天然满足传递性，不会崩
 *
 * 首字母表用「汉字 → 拼音首字母」的完整 GB2312 区段划分（22 个区间，覆盖常用字）。
 * 一个字的拼音首字母是唯一确定的，所以按它排序必然从 A 走到 Z。
 */
private fun pinyinInitialOf(text: String): Char {
    val c = text.trim().firstOrNull() ?: return '#'
    // 英文/数字直接用它自己
    if (c in 'a'..'z') return c.uppercaseChar()
    if (c in 'A'..'Z') return c
    if (c in '0'..'9') return '#'
    if (c.code < 0x4E00 || c.code > 0x9FFF) return '#'
    /**
     * GB2312 一级汉字按拼音排序，区段边界如下（每个区间首字母相同）。
     * 这是被广泛使用的标准分段，比"猜"准确得多。
     */
    val bounds = arrayOf(
        'A' to 0xB0A1, 'B' to 0xB0C5, 'C' to 0xB2C1, 'D' to 0xB4EE,
        'E' to 0xB6EA, 'F' to 0xB7A2, 'G' to 0xB8C1, 'H' to 0xB9FE,
        'J' to 0xBBF7, 'K' to 0xBFA6, 'L' to 0xC0AC, 'M' to 0xC2E8,
        'N' to 0xC4C3, 'O' to 0xC5B6, 'P' to 0xC5BE, 'Q' to 0xC6DA,
        'R' to 0xC8BB, 'S' to 0xC8F6, 'T' to 0xCBFA, 'W' to 0xCDDA,
        'X' to 0xCEF4, 'Y' to 0xD1B9, 'Z' to 0xD4D1,
    )
    /**
     * 汉字用 GBK 编码字节做区间判定（String 的 UTF-16 码位不能直接比 GB2312 区段）。
     * 拿不到 GBK 字节时退回码位近似。
     */
    val gbk = runCatching { text.trim().substring(0, 1).toByteArray(charset("GBK")) }.getOrNull()
    if (gbk != null && gbk.size == 2) {
        val code = ((gbk[0].toInt() and 0xFF) shl 8) or (gbk[1].toInt() and 0xFF)
        var result = '#'
        for ((letter, bound) in bounds) {
            if (code >= bound) result = letter else break
        }
        return result
    }
    return '#'
}

/** 排序键：首字母 + 原串（小写）。纯 String，保证比较可传递。 */
private fun sortKeyOf(text: String): String =
    pinyinInitialOf(text).toString() + "\u0000" + text.lowercase()

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
    /**
     * 用户口径（2026-11-27，#8）**排序选项要能记住**。
     *
     * 原话：「排序固定不住，没有记忆，每次都恢复默认初始的状态，你要把这个选项有记忆固定住」
     *
     * 存 SharedPreferences（按 tab 分开存），进页面时读回来。
     */
    private val _sort = MutableStateFlow(readSortPref())

    private fun sortPrefKey() = "music_sort_$tab"
    private fun readSortPref(): MusicSort = runCatching {
        val name = graph.app.getSharedPreferences("etkn_music_sort", android.content.Context.MODE_PRIVATE)
            .getString(sortPrefKey(), null)
        name?.let { MusicSort.valueOf(it) }
    }.getOrNull() ?: MusicSort.AddedTime

    private fun saveSortPref(v: MusicSort) {
        runCatching {
            graph.app.getSharedPreferences("etkn_music_sort", android.content.Context.MODE_PRIVATE)
                .edit().putString(sortPrefKey(), v.name).apply()
        }
    }
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

    /**
     * 用户口径（2026-11-27，#9）**切排序必须先把整库拉完再排**。
     *
     * 用户原话：
     *  「排序固定不住…而且排序有问题，选中之后需要往下滑动一会，
     *    然后才会刷新不会整库直接刷新，全部直接就变」
     *  「它不是一次性请求所有的歌曲数据，好像一次请求60个，
     *    我往下翻到60之后他才刷新全部」
     *  「所以就导致排序出问题，因为不是全部的」
     *
     * 根因：列表是分页的（一次 60 条），排序只作用于「已加载的那部分」，
     * 所以看上去只有翻页后才"慢慢刷新"，而且排出来的顺序是错的
     * （比如「以沫」y 开头却排在最前面 —— 因为它只在第一页里出现过）。
     *
     * 现在：切换排序时先 `loadEverything()` 把全库拉完，再整体重排。
     * 中途给 UI 一个 loading 标记，避免用户以为没反应。
     */
    fun setSort(value: MusicSort) {
        if (_sort.value == value) return
        _sort.value = value
        saveSortPref(value)
        /** 全量拉取后重排（只有「歌曲」tab 需要，其他 tab 本身就是分组）。 */
        if (tab == "songs" || tracksOnly) {
            viewModelScope.launch {
                _sortLoading.value = true
                runCatching {
                    val all = loadEverything()
                    _sortedTracks.value = applySort(all, value)
                }
                _sortLoading.value = false
            }
        }
    }

    /** 切换排序时全量重排的加载态。 */
    private val _sortLoading = MutableStateFlow(false)
    val sortLoading: StateFlow<Boolean> = _sortLoading.asStateFlow()

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
     * 用户口径（#11/#13）**按首字母：纯字符串比较，从 A 开始**。
     *
     * 用 `sortKeyOf()` 生成 "首字母\0原串" 的 key —— 纯 String.compareTo，
     * 满足传递性，不会触发 TimSort 的 "Comparison method violates its general contract"。
     */
    MusicSort.FirstLetter -> list.sortedBy { sortKeyOf(it.name) }
    MusicSort.ArtistLetter -> list.sortedBy {
        sortKeyOf(it.artistLine) + "\u0000" + it.name.lowercase()
    }
    MusicSort.PlayCount -> list.sortedByDescending { it.userData.playCount }
}
