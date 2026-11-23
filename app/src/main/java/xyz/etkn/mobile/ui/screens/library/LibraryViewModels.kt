package xyz.etkn.mobile.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.etkn.mobile.AppGraph
import xyz.etkn.mobile.data.api.EtknApi
import xyz.etkn.mobile.data.api.LibraryQuery
import xyz.etkn.mobile.data.api.SortKey
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.ItemsPage
import xyz.etkn.mobile.data.net.ResponseCache
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.Paged
import xyz.etkn.mobile.ui.common.userMessage

class LibrariesViewModel(private val graph: AppGraph) : ViewModel() {
    private val api = graph.api
    private val _state = MutableStateFlow<Load<List<Item>>>(Load.Loading)
    val state: StateFlow<Load<List<Item>>> = _state.asStateFlow()

    init {
        // Last session's list first; the network answer replaces it quietly.
        viewModelScope.launch {
            val cached = runCatching { withContext(graph.cacheUse(ResponseCache.Mode.Read)) { api.views() } }.getOrNull()
            if (!cached.isNullOrEmpty() && _state.value !is Load.Ready) _state.value = Load.Ready(cached)
        }
        load()
    }

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        viewModelScope.launch {
            val result = runCatching { withContext(graph.cacheUse(ResponseCache.Mode.Store)) { api.views() } }
            result.onSuccess { _state.value = Load.Ready(it) }
                .onFailure { if (_state.value !is Load.Ready) _state.value = Load.Failed(it.userMessage()) }
        }
    }
}

/**
 * Generic paged grid. [fetch] receives the start index and page size; StartIndex advances by
 * rows actually returned, stopping at TotalRecordCount or an empty page.
 */
abstract class PagedViewModel : ViewModel() {
    protected val _paged = MutableStateFlow(Paged<Item>())
    val paged: StateFlow<Paged<Item>> = _paged.asStateFlow()
    private var job: Job? = null
    private var generation = 0
    protected open val pageSize = 60

    protected abstract suspend fun fetch(start: Int, limit: Int): ItemsPage

    fun reset() {
        job?.cancel()
        generation++
        _paged.value = Paged()
        loadMore()
    }

    fun retry() {
        _paged.update { it.copy(error = null) }
        loadMore()
    }

    fun loadMore() {
        val current = _paged.value
        if (!current.canLoadMore) return
        val ticket = generation
        _paged.value = current.copy(loading = true)
        job = viewModelScope.launch {
            try {
                val start = current.items.size
                val page = fetch(start, pageSize)
                if (ticket != generation) return@launch
                val seen = current.items.mapTo(HashSet()) { it.id }
                val merged = current.items + page.items.filter { seen.add(it.id) }
                _paged.value = Paged(
                    items = merged,
                    total = page.total,
                    loading = false,
                    endReached = page.items.isEmpty() || merged.size >= page.total,
                )
            } catch (error: Exception) {
                if (ticket != generation) return@launch
                _paged.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    fun replace(item: Item) {
        _paged.update { p -> p.copy(items = p.items.map { if (it.id == item.id) item else it }) }
    }

    /** Drops deleted rows and re-reads changed ones in place, keeping the scroll position. */
    protected fun followChanges(graph: AppGraph) {
        viewModelScope.launch {
            graph.itemChanges.collect { change ->
                if (_paged.value.items.none { it.id == change.id }) return@collect
                if (change.deleted) {
                    _paged.update { p -> p.copy(items = p.items.filterNot { it.id == change.id }, total = if (p.total > 0) p.total - 1 else p.total) }
                } else {
                    runCatching { graph.api.item(change.id, EtknApi.LIST_FIELDS) }.onSuccess(::replace)
                }
            }
        }
    }
}

/**
 * Ways into one library besides the full poster wall: its TMDb collections and the server's
 * category cards ([facet] is the `U/Facets` kind). Which ones a library offers follows its type.
 */
enum class LibraryView(val label: String, val facet: String? = null) {
    All("全部"), Collections("合集"), Types("类型", "Types"), Networks("电视网", "Networks"), Companies("出品公司", "Companies");

    companion object {
        fun forType(collectionType: String?): List<LibraryView> = when (collectionType) {
            "movies" -> listOf(All, Collections, Types, Companies)
            "tvshows" -> listOf(All, Types, Networks, Companies)
            null, "mixed" -> listOf(All, Types)
            else -> emptyList()
        }
    }
}

class LibraryViewModel(private val graph: AppGraph, private val libraryId: String, private val collectionType: String?) : PagedViewModel() {
    private val api = graph.api
    private val _query = MutableStateFlow(
        LibraryQuery(
            parentId = libraryId,
            includeTypes = EtknApi.includeTypesFor(collectionType),
            sort = if (collectionType == "boxsets") SortKey.SortName else SortKey.DateCreated,
            descending = collectionType != "boxsets",
        ),
    )
    val query: StateFlow<LibraryQuery> = _query.asStateFlow()
    private val _view = MutableStateFlow(LibraryView.All)
    val view: StateFlow<LibraryView> = _view.asStateFlow()

    /** Groups and plain folders list their direct children only (no recursion, no type filter). */
    val isFolderView = collectionType == "group" || collectionType == "folder"
    val views: List<LibraryView> = if (isFolderView) emptyList() else LibraryView.forType(collectionType)

    /** Category covers can be generated by administrators and accounts allowed to refresh metadata. */
    val canMakeCovers: Boolean get() = graph.sessions.current?.let { it.isAdmin || it.canRefreshMetadata } == true

    /**
     * 用户口径（2026-11-23）：列表页点剧集也要判断「有没有播放记录」。
     * 续播条目在这里一次性取好（每条是 Episode，自带 SeriesId），
     * 点击时纯本地匹配 → 不打请求、不会点不动。
     */
    private val _resume = MutableStateFlow<List<Item>>(emptyList())
    val resume: StateFlow<List<Item>> = _resume.asStateFlow()

    init {
        reset()
        followChanges(graph)
        viewModelScope.launch {
            _resume.value = runCatching { api.resume(50) }.getOrDefault(emptyList())
        }
    }

    override suspend fun fetch(start: Int, limit: Int): ItemsPage {
        val view = _view.value
        if (isFolderView || view != LibraryView.All) {
            if (start > 0) return ItemsPage(emptyList(), start)
            val items = when {
                isFolderView -> api.children(libraryId)
                view == LibraryView.Collections -> api.libraryCollections(libraryId)
                else -> api.facets(libraryId, view.facet!!)
            }
            return ItemsPage(items, items.size)
        }
        return api.libraryItems(_query.value, start, limit)
    }

    fun setView(view: LibraryView) {
        if (_view.value == view) return
        _view.value = view
        reset()
    }

    fun setSort(sort: SortKey) {
        _query.update { if (it.sort == sort) it.copy(descending = !it.descending) else it.copy(sort = sort, descending = sort != SortKey.SortName) }
        reset()
    }

    fun toggleFavorites() {
        _query.update { it.copy(favoritesOnly = !it.favoritesOnly) }
        reset()
    }

    /** Generates or fetches one category card's cover, then re-reads the cards. */
    suspend fun makeCover(card: Item): Result<Unit> = runCatching {
        api.generateFacetArtwork(libraryId, card.kind.orEmpty(), card.name)
    }.onSuccess { reset() }
}

/** Every title of one category card, with the library's sort keys. */
class FacetViewModel(graph: AppGraph, private val libraryId: String, private val kind: String, private val value: String) : PagedViewModel() {
    private val api = graph.api
    private val _sort = MutableStateFlow(SortKey.DateCreated to true)
    val sort: StateFlow<Pair<SortKey, Boolean>> = _sort.asStateFlow()

    init {
        reset()
        followChanges(graph)
    }

    override suspend fun fetch(start: Int, limit: Int): ItemsPage {
        val (key, descending) = _sort.value
        return api.facetItems(libraryId, kind, value, key, descending, start, limit)
    }

    fun setSort(key: SortKey) {
        _sort.update { (current, descending) -> if (current == key) key to !descending else key to (key != SortKey.SortName) }
        reset()
    }
}

class FavoritesViewModel(graph: AppGraph) : PagedViewModel() {
    private val api = graph.api
    // Favourite collections lead the list (read once per refresh); titles page after them.
    private var collections: List<Item> = emptyList()

    init {
        reset()
        followChanges(graph)
    }

    override suspend fun fetch(start: Int, limit: Int): ItemsPage {
        if (start > 0) return merge(start, limit, api.favorites((start - collections.size).coerceAtLeast(0), limit))
        return coroutineScope {
            val sets = async { runCatching { api.favoriteCollections() }.getOrDefault(emptyList()) }
            val titles = async { api.favorites(0, limit) }
            collections = sets.await()
            merge(0, limit, titles.await())
        }
    }

    private fun merge(start: Int, limit: Int, titles: ItemsPage): ItemsPage {
        val lead = collections.drop(start).take(limit)
        return ItemsPage(lead + titles.items.take(limit - lead.size), collections.size + titles.total)
    }
}
