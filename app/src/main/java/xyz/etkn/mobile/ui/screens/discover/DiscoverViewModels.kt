package xyz.etkn.mobile.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.etkn.mobile.AppGraph
import xyz.etkn.mobile.data.discover.CastMember
import xyz.etkn.mobile.data.discover.CloudProvider
import xyz.etkn.mobile.data.discover.CloudResource
import xyz.etkn.mobile.data.discover.DiscoverFilter
import xyz.etkn.mobile.data.discover.DiscoverItem
import xyz.etkn.mobile.data.discover.DiscoverKind
import xyz.etkn.mobile.data.discover.DiscoverOptions
import xyz.etkn.mobile.data.discover.DiscoverPerson
import xyz.etkn.mobile.data.discover.DiscoverSeason
import xyz.etkn.mobile.data.discover.PersonMatch
import xyz.etkn.mobile.data.discover.withValues
import xyz.etkn.mobile.data.net.objects
import xyz.etkn.mobile.data.net.str
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.Paged
import xyz.etkn.mobile.ui.common.userMessage

/** Poster wall: page numbers from 1, stops on an empty page or `total_pages` (≤ 500). */
class DiscoverViewModel(private val graph: AppGraph) : ViewModel() {
    private val api = graph.api
    private val _kind = MutableStateFlow(DiscoverKind.Movie)
    val kind: StateFlow<DiscoverKind> = _kind.asStateFlow()
    private val _filter = MutableStateFlow(DiscoverFilter())
    val filter: StateFlow<DiscoverFilter> = _filter.asStateFlow()
    private val _paged = MutableStateFlow(Paged<DiscoverItem>())
    val paged: StateFlow<Paged<DiscoverItem>> = _paged.asStateFlow()
    private val _options = MutableStateFlow(DiscoverOptions())
    val options: StateFlow<DiscoverOptions> = _options.asStateFlow()
    private var page = 0
    private var loadJob: Job? = null
    /**
     * 用户口径（2026-11-24）：探索页列表的滚动位置。
     * 存在 ViewModel 里（导航期间保活），返回时用它恢复，不停在顶部。
     */
    var gridIndex: Int = 0
    var gridOffset: Int = 0

    init {
        reload()
    }

    fun setKind(kind: DiscoverKind) {
        if (kind == _kind.value) return
        _kind.value = kind
        _filter.value = DiscoverFilter(query = _filter.value.query)
        reload()
    }

    fun setFilter(filter: DiscoverFilter) {
        if (filter == _filter.value) return
        _filter.value = filter
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        page = 0
        _paged.value = Paged()
        loadOptions()
        loadMore()
    }

    fun retry() {
        _paged.update { it.copy(error = null) }
        loadMore()
    }

    fun loadMore() {
        val state = _paged.value
        if (!state.canLoadMore) return
        val kind = _kind.value
        val filter = _filter.value
        val target = page + 1
        _paged.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            runCatching { api.discoverQuery(kind, filter.body(kind, target)) }
                .onSuccess { result ->
                    page = target
                    result.items.forEach(graph.discover::put)
                    _paged.update {
                        val items = (it.items + result.items).distinctBy { item -> item.kind to item.id }
                        it.copy(items = items, loading = false, endReached = result.items.isEmpty() || target >= result.totalPages)
                    }
                }
                .onFailure { error -> _paged.update { it.copy(loading = false, error = error.userMessage()) } }
        }
    }

    private fun loadOptions() {
        val kind = _kind.value
        graph.discover.options[kind]?.let {
            _options.value = DiscoverOptions.from(it)
            return
        }
        _options.value = DiscoverOptions()
        viewModelScope.launch {
            runCatching { api.discoverOptions(kind) }.onSuccess { raw ->
                graph.discover.options[kind] = raw
                if (_kind.value == kind) _options.value = DiscoverOptions.from(raw)
            }
        }
    }

    /** People matching [name] for the actor / director pickers; empty on failure. */
    suspend fun findPeople(name: String): List<PersonMatch> =
        if (name.isBlank()) emptyList() else runCatching { api.discoverPeople(name.trim()) }.getOrDefault(emptyList())
}

data class DetailExtras(
    val seasons: List<DiscoverSeason> = emptyList(),
    val seasonsLoading: Boolean = false,
    val cast: List<CastMember> = emptyList(),
)

/** Detail of one TMDb title: enrich from series / credits, subscribe movie, season or all missing seasons. */
class DiscoverDetailViewModel(private val graph: AppGraph, id: String, kind: DiscoverKind, title: String) : ViewModel() {
    private val api = graph.api
    private val _item = MutableStateFlow(
        graph.discover.get(kind, id) ?: DiscoverItem(
            JsonObject(
                mapOf("id" to JsonPrimitive(id), "title" to JsonPrimitive(title), "media_type" to JsonPrimitive(kind.mediaType)),
            ),
            kind,
        ),
    )
    val item: StateFlow<DiscoverItem> = _item.asStateFlow()
    private val _extras = MutableStateFlow(DetailExtras(seasonsLoading = kind == DiscoverKind.Series))
    val extras: StateFlow<DetailExtras> = _extras.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        val current = _item.value
        viewModelScope.launch {
            if (current.kind == DiscoverKind.Series) {
                val details = runCatching { api.discoverSeries(current.id) }.getOrNull()
                if (details != null) {
                    update(_item.value.enrich(details))
                    _extras.update { it.copy(seasons = details.objects("seasons").map(::DiscoverSeason).filter { s -> s.number >= 0 }.sortedBy { s -> s.number }) }
                }
                _extras.update { it.copy(seasonsLoading = false) }
            }
        }
        viewModelScope.launch {
            runCatching { api.discoverCredits(current.kind, current.id) }.onSuccess { credits ->
                update(_item.value.enrich(credits))
                val cast = credits.objects("cast").map {
                    CastMember(it.str("id"), it.str("name"), it.str("character"), it.str("profile_path"))
                }.filter { it.name.isNotEmpty() }
                _extras.update { it.copy(cast = cast) }
            }
        }
    }

    private fun update(item: DiscoverItem) {
        _item.value = item
        graph.discover.put(item)
    }

    /** Subscribes the movie (season null) or one season; returns a message for the user. */
    suspend fun subscribe(season: DiscoverSeason?): Result<String> {
        _busy.value = true
        return try {
            val demand = api.discoverSubscribe(_item.value, season)
            markWanted(listOfNotNull(season?.number), whole = season == null)
            Result.success("已写入统一需求" + (if (demand.isEmpty()) "" else "（需求 #$demand）") + "，服务端将按订阅配置自动获取资源，入库后会发送通知。")
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            _busy.value = false
        }
    }

    /** Season by season, stopping at the first error; earlier successes stay. */
    suspend fun subscribeMissing(): Result<String> {
        val missing = _extras.value.seasons.filter { it.subscribable }
        if (missing.isEmpty()) return Result.success("所有季度均已入库或订阅。")
        _busy.value = true
        val done = ArrayList<Int>()
        try {
            for (season in missing) {
                api.discoverSubscribe(_item.value, season)
                done += season.number
            }
            markWanted(done, whole = true)
            return Result.success("已按季度写入 ${done.size} 条统一需求。")
        } catch (error: Exception) {
            markWanted(done, whole = false)
            return Result.failure(IllegalStateException("${error.userMessage()}（已完成 ${done.size} 条）"))
        } finally {
            _busy.value = false
        }
    }

    private fun markWanted(numbers: List<Int>, whole: Boolean) {
        val wanted = "subscription_status" to JsonPrimitive("WANTED")
        if (whole) update(_item.value.copyWith(wanted))
        if (numbers.isNotEmpty()) {
            _extras.update { extras ->
                extras.copy(seasons = extras.seasons.map { if (it.number in numbers) DiscoverSeason(it.raw.withValues(wanted)) else it })
            }
        }
    }
}

class DiscoverPersonViewModel(private val graph: AppGraph, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow<Load<DiscoverPerson>>(Load.Loading)
    val state: StateFlow<Load<DiscoverPerson>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = Load.Loading
        viewModelScope.launch {
            _state.value = runCatching { graph.api.discoverPerson(id) }
                .onSuccess { person -> person.works.forEach(graph.discover::put) }
                .fold({ Load.Ready(it) }, { Load.Failed(it.userMessage()) })
        }
    }
}

/** Three providers searched in parallel; each shows its own result or failure. */
class CloudViewModel(private val graph: AppGraph, val item: DiscoverItem, val season: Int?) : ViewModel() {
    private val _results = MutableStateFlow(CloudProvider.enabled.associateWith<CloudProvider, Load<List<CloudResource>>> { Load.Loading })
    val results: StateFlow<Map<CloudProvider, Load<List<CloudResource>>>> = _results.asStateFlow()
    private val _submitted = MutableStateFlow<Set<CloudResource>>(emptySet())
    val submitted: StateFlow<Set<CloudResource>> = _submitted.asStateFlow()

    init {
        search()
    }

    fun search() {
        _results.value = CloudProvider.enabled.associateWith { Load.Loading }
        CloudProvider.enabled.forEach { provider ->
            viewModelScope.launch {
                val result = runCatching { graph.api.cloudResources(provider, item, season) }
                _results.update { it + (provider to result.fold({ rows -> Load.Ready(rows) }, { error -> Load.Failed(error.userMessage()) })) }
            }
        }
    }

    /** Submits one acquisition; its result is reported later by [xyz.etkn.mobile.data.events.IngestNotices]. */
    /**
     * 用户口径（2026-11-26）**按集转存**。
     *
     * @param episode 选中的单集（null = 整个资源全转）
     */
    suspend fun acquire(row: CloudResource, episode: CloudResource? = null): Result<Long> =
        runCatching { graph.api.acquire(row, item, season, episode) }
            .onSuccess { runId -> graph.sessions.current?.let { graph.ingest.track(it, runId) } }
            .onSuccess { _submitted.update { it + row } }

    /**
     * 用户口径（2026-11-26）**拉取某资源的分集清单**（供集号网格）。
     *
     * 只有共享池且是整季资源才有；普通账号没有权限时返回空列表，
     * 调用方退回「整季转存」。
     */
    suspend fun children(row: CloudResource): List<CloudResource> =
        runCatching { graph.api.sharedResourceChildren(row) }.getOrDefault(emptyList())
}
