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

/**
 * 排序键：首字母 + 原串（小写）。纯 String，保证比较可传递。
 *
 * 用户口径（2026-11-27，#16）**剥掉歌名前的书名号等标点再取首字母**。
 *
 * 用户截图实测：《以沫》排在最前面（它应该是 Y）。
 * 根因：这首歌全名是「《以沫》——默读广播剧主题曲」，
 * 第一个字符是 `《`，不是字母也不是汉字 → 归到 `#`，
 * 而 `#` 的 ASCII 是 0x23，比 `A` 的 0x41 小 → 排到 A 前面去了。
 *
 * 修法两条：
 *  ① 取首字母前先剥掉开头的标点/空白（《》【】「」""''·《》等）
 *  ② 归到 `#` 的（数字/符号开头的）用 `~`(0x7E) 作 key，**排到 Z 后面**
 *     而不是排在 A 前面
 */
/**
 * 会被剥掉的「前缀噪音字符」：书名号、引号、括号、标点、空白。
 *
 * 用 `charArrayOf` 显式列出，避免字符串转义踩坑（之前 `"` 和 `\` 混在一起导致语法错误）。
 */
/**
 * 会被剥掉的「前缀噪音字符」：书名号、引号、括号、标点、空白。
 *
 * 用 Unicode 码位显式构造，避免 Kotlin 字符串里 `"` 和 `\` 的转义踩坑
 * （之前写字符字面量时被 Python 二次转义，导致语法错误）。
 */
private val LEADING_JUNK: Set<Char> = buildSet {
    // 中文书名号 / 引号 / 括号 / 间隔号 / 顿号（用码位声明）
    val cjk = intArrayOf(
        0x300A, 0x300B, 0x3010, 0x3011, 0x300C, 0x300D, 0x300E, 0x300F,
        0xFF08, 0xFF09, 0x3014, 0x3015, 0x3008, 0x3009, 0x00B7, 0x3001,
        0xFF0C, 0x3002, 0xFF1A, 0xFF1B, 0xFF01, 0xFF1F, 0x2014, 0x3000,
    )
    cjk.forEach { add(it.toChar()) }
    // ASCII 标点与空白：0x20-0x2F / 0x3A-0x40 / 0x5B-0x60 / 0x7B-0x7E
    for (c in 0x20..0x2F) add(c.toChar())
    for (c in 0x3A..0x40) add(c.toChar())
    for (c in 0x5B..0x60) add(c.toChar())
    for (c in 0x7B..0x7E) add(c.toChar())
    // 制表 / 换行
    intArrayOf(0x09, 0x0A, 0x0D).forEach { add(it.toChar()) }
}

private fun stripLeadingJunk(text: String): String {
    var i = 0
    val t = text.trim()
    while (i < t.length && t[i] in LEADING_JUNK) i++
    return if (i >= t.length) t else t.substring(i)
}

private fun sortKeyOf(text: String): String {
    val cleaned = stripLeadingJunk(text)
    val initial = pinyinInitialOf(cleaned)
    /** `#` 换成 `~`（0x7E），保证排到 Z 之后，而不是排在 A 之前。 */
    val key = if (initial == '#') '~' else initial
    return "$key\u0000${cleaned.lowercase()}\u0000${text.lowercase()}"
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

    /**
     * 用户口径（2026-11-27，#17）**歌曲清单必须一次全部加载完，不能翻着翻着才出来**。
     *
     * 用户原话：
     *  「我每次打开，看到得歌曲清单界面是不全的…虽然显示的歌曲是按照字母排序的，
     *    但是还有部分音乐得我向下翻，往下一直翻，翻着翻着就一个个都出来了，
     *    它还不是按照这个排序的顺序出来，它是跳着出来的，一会上面出来点，一会下面出来点」
     *
     * 根因：列表是分页的（一次 60 条 / 601 首 = 11 页），
     * 而排序只能排「已加载的那部分」。用户往下翻的时候，
     * 新一页的数据被追加到列表末尾再重排 → 视觉上就是"跳着出来"。
     *
     * 修法：**歌曲 tab 进页面就一次把全库拉完**（601 首约 11 个请求，并发 4 路，
     * 一两秒内完成），拿到完整列表后统一排序、一次性渲染。
     * 这样用户看到的第一眼就是完整的、正确排序的清单。
     */
    private val _allLoaded = MutableStateFlow(false)
    val allLoaded: StateFlow<Boolean> = _allLoaded.asStateFlow()

    init {
        if (tab == "songs" || tracksOnly) {
            /**
             * 用户口径（2026-11-27，#19）**先显示本地缓存，再后台刷新**。
             *
             * 原话：「刚进去先看到的是本地缓存，要不进去之后看到的是空白，
             *        然后等刷新之后才能看到缓存」
             *
             * 顺序：
             * ① 同步读磁盘缓存（几毫秒）→ 立刻渲染出来
             * ② 后台拉服务端全量 → 和缓存比对，内容变了才替换
             */
            val cached = xyz.etkn.mobile.music.MusicListCache.read(
                graph.app, parentId, tab,
            )
            if (cached.isNotEmpty()) {
                _sortedTracks.value = applySort(cached, _sort.value)
                _allLoaded.value = true
                android.util.Log.i("ETKN-Music", "列表缓存命中 ${cached.size} 首")
            }
            /** 后台刷新（不管有没有缓存都拉一次，保证数据是最新的）。 */
            viewModelScope.launch {
                runCatching {
                    val all = loadEverything()
                    if (all.isNotEmpty()) {
                        _sortedTracks.value = applySort(all, _sort.value)
                        _allLoaded.value = true
                        /** 写回缓存（下次进页面秒开）。 */
                        xyz.etkn.mobile.music.MusicListCache.write(
                            graph.app, parentId, tab, all,
                        )
                        android.util.Log.i("ETKN-Music", "列表已刷新并落盘 ${all.size} 首")
                    }
                }
            }
        } else {
            /** 其他 tab（专辑/歌手/歌单）：保持分页（本身就是分组卡片，不需要全量）。 */
            viewModelScope.launch {
                combine(_sort, _paged) { sort, page ->
                    applySort(page.items.filter { it.isAudio && !it.isMusicGroup }, sort)
                }.collect { _sortedTracks.value = it }
            }
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
                    /** 全量数据已缓存就直接排；没有就拉一次。 */
                    val all = if (_allLoaded.value) {
                        _sortedTracks.value
                    } else {
                        loadEverything()
                    }
                    _sortedTracks.value = applySort(all, value)
                    _allLoaded.value = true
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

    /**
     * 用户口径（2026-11-27，#20）**从缓存列表点歌也能播**。
     *
     * 缓存里的 Item 只有展示字段（没有 MediaSources / 直链），
     * 直接拿去播放会解析失败。这里交给 `MusicConnection.playFromCache()`：
     * 它会把列表里的每首歌按 id 去补详情（有并发限制），再真正起播。
     */
    fun play(track: Item?) {
        val list = _sortedTracks.value.ifEmpty { tracks }
        if (list.isEmpty()) return
        val index = track?.let { t -> list.indexOfFirst { it.id == t.id } }?.coerceAtLeast(0) ?: 0
        graph.music.play(list, index, "$tab:$key")
    }

    /** 把整张列表（所有分页）拉完；已加载的部分直接复用，只补后面的页。 */
    private suspend fun loadEverything(): List<Item> {
        /**
         * 用户口径（#17）**先把 total 探出来，再决定要不要补页**。
         *
         * 注意 `paged.value.total` 在首页还没返回时是 0，
         * 之前直接 `if (total <= start) return` 会误以为"已经全了"，
         * 只返回 60 条 —— 这就是"清单不全"的另一半原因。
         */
        var total = paged.value.total
        if (total <= 0) {
            /** 首页还没到：先拉一次首页拿 total。 */
            runCatching { fetch(0, pageSize) }.onSuccess { total = it.total }
        }
        val loaded = paged.value.items.toMutableList()
        var start = loaded.size
        if (total > 0 && total <= start) return loaded.filter { it.isAudio && !it.isMusicGroup }
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
    MusicSort.ArtistLetter -> list.sortedBy { sortKeyOf(it.artistLine) + "\u0000" + it.name.lowercase() }
    MusicSort.PlayCount -> list.sortedByDescending { it.userData.playCount }
}
