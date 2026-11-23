package xyz.etkn.mobile.ui.screens.search

import androidx.compose.foundation.lazy.items
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.etkn.mobile.AppGraph
import xyz.etkn.mobile.data.discover.DiscoverFilter
import xyz.etkn.mobile.data.discover.DiscoverItem
import xyz.etkn.mobile.data.discover.DiscoverKind
import xyz.etkn.mobile.data.model.ItemsPage
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.Paged
import xyz.etkn.mobile.ui.common.userMessage
import xyz.etkn.mobile.ui.screens.library.PagedViewModel

/** What a search looks through; 影视 by default. */
enum class SearchScope(val label: String, val hint: String, val none: String) {
    Video("影视", "搜索电影、剧集", "没有找到相关影视"),
    Music("音乐", "搜索专辑、歌手", "没有找到相关音乐"),
    MusicVideo("MV", "搜索 MV", "没有找到相关 MV"),
}

/**
 * Library search in one [SearchScope]. For 影视, when the library has nothing for the term and
 * the account may discover, TMDb is searched for movies and series at the same time.
 */
class SearchViewModel(private val graph: AppGraph) : PagedViewModel() {
    private val api = graph.api
    private val term = MutableStateFlow("")
    var current = ""
        private set
    private val _scope = MutableStateFlow(SearchScope.Video)
    val scope: StateFlow<SearchScope> = _scope.asStateFlow()
    /**
     * 用户口径（2026-11-23）：音乐 / MV 标签页的在线结果（走 /api/shared-pool/music）。
     * null = 这个账号没权限（非管理员），界面就不显示在线区块。
     */
    private val _sharedMusic = MutableStateFlow<Load<List<DiscoverItem>>?>(null)
    val sharedMusic: StateFlow<Load<List<DiscoverItem>>?> = _sharedMusic.asStateFlow()
    private var musicAllowed: Boolean? = null
    private var musicJob: Job? = null
    private val _discover = MutableStateFlow<Map<DiscoverKind, Load<List<DiscoverItem>>>?>(null)
    /** Null while the library answers (or has answers); otherwise one result per kind. */
    val discover: StateFlow<Map<DiscoverKind, Load<List<DiscoverItem>>>?> = _discover.asStateFlow()
    private var discoverJob: Job? = null
    private var discoverTerm = ""

    init {
        followChanges(graph)
        viewModelScope.launch {
            term.debounce(350).distinctUntilChanged().collect { value ->
                current = value.trim()
                discoverJob?.cancel()
                discoverTerm = ""
                _discover.value = null
                if (current.isNotEmpty()) reset() else _paged.value = Paged(endReached = true)
            }
        }
        viewModelScope.launch {
            combine(paged, graph.discover.allowed) { page, allowed -> page to allowed }.collect { (_, allowed) ->
                /**
                 * 用户口径（2026-11-23）：以前是「库内搜不到（empty）才去探索搜」，
                 * 导致库里有的片子永远看不到在线资源。
                 * 现在：无论库里有没有，只要输入了关键词就同时搜探索。
                 */
                if (allowed && _scope.value == SearchScope.Video && current.isNotEmpty() && discoverTerm != current) {
                    searchDiscover(current)
                }
                // 用户口径（2026-11-23）：音乐/MV 标签页也要接在线共享池。
                // 需要管理员权限，探不到就不显示（界面上按 null 处理）。
                if (_scope.value != SearchScope.Video && current.isNotEmpty() && discoverTerm != current) {
                    searchSharedMusic(current, _scope.value)
                }
            }
        }
    }

    private fun searchDiscover(query: String) {
        discoverTerm = query
        _discover.value = DiscoverKind.entries.associateWith { Load.Loading }
        discoverJob = viewModelScope.launch {
            DiscoverKind.entries.forEach { kind ->
                launch {
                    val result = runCatching { api.discoverQuery(kind, DiscoverFilter(query = query).body(kind, 1)).items }
                        .onSuccess { items -> items.forEach(graph.discover::put) }
                        .fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })
                    _discover.update { it?.plus(kind to result) }
                }
            }
        }
    }

    /**
     * 用户口径（2026-11-23）音乐 / MV 的在线搜索。
     * kind 用服务端的 music / music_video；没管理员权限时整体返回 null（界面隐藏）。
     */
    private fun searchSharedMusic(query: String, target: SearchScope) {
        discoverTerm = query
        musicJob?.cancel()
        _sharedMusic.value = Load.Loading
        musicJob = viewModelScope.launch {
            if (musicAllowed == null) musicAllowed = runCatching { api.canSharedMusic() }.getOrDefault(false)
            if (musicAllowed != true) { _sharedMusic.value = null; return@launch }
            val kind = if (target == SearchScope.MusicVideo) "music_video" else "music"
            val result = runCatching {
                api.sharedPoolMusic(kind, query)?.items.orEmpty()
            }.fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })
            _sharedMusic.value = result
        }
    }

    fun retryDiscover() {
        discoverTerm = ""
        if (current.isNotEmpty()) searchDiscover(current)
    }

    fun onQuery(value: String) {
        term.value = value
    }

    fun setScope(scope: SearchScope) {
        if (scope == _scope.value) return
        _scope.value = scope
        discoverJob?.cancel()
        discoverTerm = ""
        _discover.value = null
        if (current.isNotEmpty()) reset()
    }

    override suspend fun fetch(start: Int, limit: Int): ItemsPage = when (_scope.value) {
        SearchScope.Video -> api.search(current, start, limit)
        SearchScope.Music -> api.searchMusic(current, start, limit)
        // One filtered page: later pages would only repeat the filter on fewer matches.
        SearchScope.MusicVideo -> if (start == 0) api.searchMusicVideos(current) else ItemsPage(emptyList(), 0)
    }
}
