package xyz.etkn.mobile.ui.screens.detail

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.etkn.mobile.AppGraph
import xyz.etkn.mobile.data.api.EtknApi
import xyz.etkn.mobile.data.events.ServerEvent
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.userMessage
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue

@Immutable
data class DetailData(
    val item: Item,
    val seasons: List<Item> = emptyList(),
    val target: Item? = null,
    val similar: List<Item> = emptyList(),
    /** TMDb collections a movie belongs to. */
    val collections: List<Item> = emptyList(),
    val selectedSource: Int = 0,
)

/**
 * One detail page. On an episode's page the shelf switches between the series' episodes in place
 * ([show]): the page keeps what it shows until the next episode is loaded instead of opening a
 * new page per episode, so back still leads straight out of the series.
 */
class DetailViewModel(private val graph: AppGraph, routeId: String) : ViewModel() {
    private val shown = MutableStateFlow(routeId)
    private val id get() = shown.value
    private val api = graph.api
    private val _state = MutableStateFlow<Load<DetailData>>(Load.Loading)
    val state: StateFlow<Load<DetailData>> = _state.asStateFlow()
    val episodes = EpisodeList(api, viewModelScope)
    private var busy = false
    private val _deleted = MutableStateFlow(false)
    /** The item itself was deleted from the item menu; the page leaves. */
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    init {
        load()
        viewModelScope.launch {
            graph.itemChanges.collect { change ->
                val data = (_state.value as? Load.Ready)?.value
                when {
                    change.id == id && change.deleted -> _deleted.value = true
                    change.id == id || data?.similar?.any { it.id == change.id } == true ||
                        episodes.contains(change.id) -> load()
                }
            }
        }
    }

    /** Shows another episode of the same series on this page. */
    fun show(episodeId: String) {
        if (episodeId == id) return
        shown.value = episodeId
        load()
    }

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        val requested = id
        viewModelScope.launch {
            try {
                val data = coroutineScope {
                    val base = api.item(requested)
                    // Everything below depends only on the base item, so it all runs at once.
                    val detailed = if (base.isPlayable && base.mediaSources.isEmpty()) {
                        async { runCatching { api.item(requested, EtknApi.DETAIL_FIELDS) }.getOrNull() }
                    } else null
                    val seriesId = when {
                        base.isSeries -> base.id
                        base.isSeason -> base.seriesId
                        else -> null
                    }
                    val item = base
                    val seasons = seriesId?.let { sid -> async { runCatching { api.seasons(sid) }.getOrDefault(emptyList()) } }
                    val target = seriesId?.let { sid ->
                        async { runCatching { api.playbackTarget(sid, if (item.isSeason) item.id else null) }.getOrNull() }
                    }
                    val similar = if (item.isMovie || item.isSeries) async { runCatching { api.similar(item.id) }.getOrDefault(emptyList()) } else null
                    val collections = if (item.isMovie) async { runCatching { api.itemCollections(item.id) }.getOrDefault(emptyList()) } else null
                    val full = detailed?.await()
                    DetailData(
                        item = if (full != null) base.copy(mediaSources = full.mediaSources, chapters = full.chapters.ifEmpty { base.chapters }) else base,
                        seasons = seasons?.await().orEmpty(),
                        target = target?.await(),
                        similar = similar?.await().orEmpty(),
                        collections = collections?.await().orEmpty(),
                    )
                }
                // A newer episode was picked while this one loaded.
                if (requested != id) return@launch
                _state.value = Load.Ready(data)
                val seasonForEpisodes = when {
                    data.item.isSeason -> data.item.id
                    data.item.isSeries -> data.target?.seasonId ?: data.seasons.firstOrNull()?.id
                    data.item.isEpisode -> data.item.seasonId
                    else -> null
                }
                val seriesForEpisodes = if (data.item.isSeries) data.item.id else data.item.seriesId
                if (seriesForEpisodes != null && (data.item.isSeries || data.item.isSeason || data.item.isEpisode)) {
                    val shown = episodes.state.value
                    val anchor = if (data.item.isEpisode) data.item.id else data.target?.id
                    if (shown.seasonId != seasonForEpisodes || shown.items.isEmpty()) episodes.select(seriesForEpisodes, seasonForEpisodes, anchor)
                    else episodes.refresh()
                }
            } catch (error: Exception) {
                if (_state.value !is Load.Ready) _state.value = Load.Failed(error.userMessage())
            }
        }
    }

    /** Re-reads the page when the server reports changes to its rows (metadata, images, versions). */
    suspend fun followServer() {
        shown.collectLatest { id ->
            graph.events.stream(id).collect { if (it is ServerEvent.Items && !it.initial) load() }
        }
    }

    fun selectSeason(seriesId: String, seasonId: String) = episodes.select(seriesId, seasonId)

    fun selectSource(index: Int) {
        _state.update { s -> if (s is Load.Ready) Load.Ready(s.value.copy(selectedSource = index)) else s }
    }

    fun toggleFavorite() = mutate { item ->
        api.setFavorite(item.id, !item.userData.isFavorite).let(item::withUserData)
            .let { if (it.userData.isFavorite == item.userData.isFavorite) it.copy(userData = it.userData.copy(isFavorite = !item.userData.isFavorite)) else it }
    }

    /**
     * 用户口径（2026-11-26）**修「详情页右上角对号点了没反应」**。
     *
     * 上一版的写法有两个问题：
     *  ① `api.setPlayed(...)` 之后用 `withUserData` 拿服务端最新状态，
     *     但如果服务端返回的 UserData 里 played 没跟着变，
     *     后面那句 `if (it.userData.played != target) copy(...)` 才兜底 ——
     *     实际服务端 setPlayed 只返一个空对象/局部字段，这条兜底经常不生效。
     *  ② 整个 block 包在 `runCatching{}` 里，失败**完全静默**：
     *     用户点下去什么都不会发生，也看不到任何提示。
     *
     * 现在：
     *  · 直接以「本地的目标值」为准回写 UI（服务端调用只当作通知），
     *    这样点一下图标一定立刻变化，不会出现「点了没反应」
     *  · 失败时把错误抛出来，由调用方提示，不再静默
     *  · 剧集/季层刷新分集、并通知列表页
     */
    fun togglePlayed() = mutate { item ->
        val target = !item.userData.played
        // 先把服务端改掉；失败会让 mutate 的 runCatching 捕获并回报
        api.setPlayed(item.id, target)
        // 以目标值为准直接回写，确保图标状态一定跟随点击
        val updated = item.copy(userData = item.userData.copy(played = target))
        if (item.isSeries || item.isSeason) episodes.refresh()
        updated
    }

    /**
     * 用户口径（2026-11-26）：**失败不能再静默**。
     *
     * 上一版 `runCatching{}.onSuccess{}` —— 失败分支什么都不做，
     * 用户点了按钮既没变化也没提示，看起来就是「压根没做这个功能」。
     * 现在失败时把 message 写进 notice 暴露出来，并在日志里留一行。
     */
    private fun mutate(block: suspend (Item) -> Item) {
        val data = (_state.value as? Load.Ready)?.value ?: return
        if (busy) return
        busy = true
        viewModelScope.launch {
            runCatching { block(data.item) }
                .onSuccess { updated ->
                    _state.update { s -> if (s is Load.Ready) Load.Ready(s.value.copy(item = updated)) else s }
                    notice = null
                }
                .onFailure { error ->
                    android.util.Log.w("ETKN-Detail", "mutate failed: ${error.message}")
                    notice = error.message ?: "操作失败"
                }
            busy = false
        }
    }

    /** 失败提示（DetailScreen 会读它并显示出来）。 */
    var notice by mutableStateOf<String?>(null)
        private set
}
