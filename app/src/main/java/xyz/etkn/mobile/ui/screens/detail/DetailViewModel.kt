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
    /**
     * 用户口径（2026-11-27）**单集页头部用「剧集」数据**（复用已删掉的剧集详情页那条接口）。
     * item 是 Episode 时这里放 `api.item(seriesId)` 的结果；其他情况为 null。
     */
    val seriesItem: Item? = null,
    /**
     * 用户口径（2026-11-27，#16）**详情页选中的音轨/字幕**。
     * null = 没选过（按服务端默认）；-1 = 关闭字幕；>=0 = 指定流的 Index。
     */
    val selectedAudioIndex: Int? = null,
    val selectedSubtitleIndex: Int? = null,
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
    /** 剧集层只自动落到具体集一次，之后用户自己选集不再被拽回。 */
    private var jumpOnce = false

    /**
     * 用户口径（2026-11-27，#14）**切集提速用的同剧缓存**。
     *
     * 同一部剧内切集时，季列表和剧集那条（头图）完全不变，
     * 没必要每切一集都重新请求并等待它们 —— 这是切集慢的主要原因。
     * 切到别的剧时 `cachedSeriesId` 不匹配，自动失效重取。
     */
    private var cachedSeriesId: String? = null
    private var cacheSeasons: List<Item> = emptyList()
    private var cacheSeriesItem: Item? = null

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
                    /**
                     * 用户口径（2026-11-27）**单集页也要拿到 seriesId**。
                     *
                     * 修的 bug：剧集层现在会自动跳到具体某一集（见下面的 jumpTo），
                     * 而这里原来只认 Series / Season 两种，Episode 进来是 null →
                     * `seasons` 和 `target` 都不请求 → 季卡区整块不渲染，
                     * 用户看到「详情页看不到季的模块」。
                     *
                     * Episode 的父级就是 series，直接用它的 seriesId 即可。
                     */
                    val seriesId = when {
                        base.isSeries -> base.id
                        base.isSeason -> base.seriesId
                        base.isEpisode -> base.seriesId
                        else -> null
                    }
                    val item = base
                    /**
                     * 用户口径（2026-11-27）**单集页的头部大图要用「剧集」那张**。
                     *
                     * 用户要求：「你去查看之前咱们删掉的那个详情页，它那个调取的接口是哪个？你复用过来就 OK 了」。
                     *
                     * 那个已删掉的剧集详情页，`item` 本身就是 Series，
                     * 数据来自 `api.item(seriesId)` —— 里面的 Backdrop/Thumb/Logo 就是剧集图。
                     * 现在单集页虽然 item 是 Episode，但 `seriesId` 已经拿得到，
                     * 所以这里直接把剧集那条也请求一次，喂给头部用（不用自己猜父级字段）。
                     */
                    /**
                     * 用户口径（2026-11-27，#14）**切集要快：同剧的季列表和剧集图不再重复请求**。
                     *
                     * 用户实测：「人家这个切集速度也比咱们的快」。
                     *
                     * 慢的根因：每切一集，下面这些都重新走一遍网络并全部 await 完才出画面：
                     *  · `api.seasons(sid)`      —— 季列表，同一部剧永远不变
                     *  · `api.item(seriesId)`    —— 剧集那条（头图用），也是同一部剧不变
                     *  · `api.playbackTarget`    —— 只有剧集层/季层才需要
                     *
                     * 现在：同剧复用上一次的结果（内存缓存，切剧时自动失效），
                     * 只有真正的"这一集自己的数据"才重新请求。
                     */
                    val sameSeries = cachedSeriesId == seriesId && seriesId != null
                    val cachedSeriesItem = cacheSeriesItem
                    val cachedSeasons = cacheSeasons
                    val seriesItem = if (base.isEpisode && base.seriesId != null) {
                        if (sameSeries && cachedSeriesItem != null) null
                        else async { runCatching { api.item(base.seriesId) }.getOrNull() }
                    } else null
                    val seasons = seriesId?.let { sid ->
                        if (sameSeries && cachedSeasons.isNotEmpty()) null
                        else async { runCatching { api.seasons(sid) }.getOrDefault(emptyList()) }
                    }
                    val target = seriesId?.let { sid ->
                        async { runCatching { api.playbackTarget(sid, if (item.isSeason) item.id else null) }.getOrNull() }
                    }
                    val similar = if (item.isMovie || item.isSeries) async { runCatching { api.similar(item.id) }.getOrDefault(emptyList()) } else null
                    val collections = if (item.isMovie) async { runCatching { api.itemCollections(item.id) }.getOrDefault(emptyList()) } else null
                    val full = detailed?.await()
                    DetailData(
                        item = if (full != null) base.copy(mediaSources = full.mediaSources, chapters = full.chapters.ifEmpty { base.chapters }) else base,
                        seasons = if (seasons != null) {
                            seasons.await().also { cacheSeasons = it; cachedSeriesId = seriesId }
                        } else cachedSeasons,
                        target = target?.await(),
                        similar = similar?.await().orEmpty(),
                        collections = collections?.await().orEmpty(),
                        /** 剧集那条数据：单集页头部大图/艺术字用它，切集时保持不变。 */
                        seriesItem = if (seriesItem != null) {
                            seriesItem.await().also { cacheSeriesItem = it }
                        } else cachedSeriesItem,
                    )
                }
                // A newer episode was picked while this one loaded.
                if (requested != id) return@launch
                /**
                 * 用户口径（2026-11-27）**剧集层不再单独停留，直接落到具体某一集**。
                 *
                 * 原来是两种形态：
                 *  · 没播过 → 剧集详情页，按钮「播放 第1季第1集」
                 *  · 有记录 → 剧集详情页，按钮「继续播放 26:26」+「从头播放」
                 *
                 * 用户要求合成一种：点开剧集直接进「那一集」的详情页 ——
                 * 有播放记录就进正在看的那集，没有就进第1季第1集。
                 * 这样季卡区也能跟着进到单集页（原来只在剧集页显示，搬过去才不丢）。
                 *
                 * 只跳一次（jumpOnce），跳过去之后用户自己点其他集不再被拽回来。
                 * 电影/Video 走不到这里（seriesId 为 null）。
                 */
                val jumpTo = if (data.item.isSeries && !jumpOnce && data.target != null) data.target else null
                if (jumpTo != null && jumpTo.id != data.item.id) {
                    jumpOnce = true
                    Load.Ready(data)
                    show(jumpTo.id)
                    return@launch
                }
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

    /**
     * 用户口径（2026-11-27，#19）**切季要跟着定位到该季的集**。
     *
     * 修的 bug（用户实测）：
     * 「比如我现在选中第 3 季，上面的剧集按理来说应该显示第 3 季的第一集
     *   （因为第 3 季现在没有播放记录）；如果第 3 季有播放记录，就定位到播放的那一集。
     *   现在无论切换哪一季，显示的都是之前选中的那一集。」
     *
     * 根因：这里原来只调 `episodes.select(seriesId, seasonId)` —— 那只换"选集行显示哪一季"，
     * **完全没有更新当前正在看的是哪一集**，所以上方头图/标题/简介还是上一季那一集。
     *
     * 现在的顺序：
     *  ① 先让选集行切到新季（快，用户立刻看到那一季的缩略图）
     *  ② 再在这一季里找「有进度且没看完」的集
     *     · 找到 → show(那一集)
     *     · 没有 → show(这一季第一集)
     */
    fun selectSeason(seriesId: String, seasonId: String) {
        episodes.select(seriesId, seasonId)
        viewModelScope.launch {
            /**
             * 直接调 episodePage 取该季前若干集找进度，比等 episodes 状态回填更快也更准
             *（episodes 的 state 是异步的，这里读它可能还是上一季的数据）。
             */
            val page = runCatching {
                api.episodePage(seriesId, seasonId, start = 0, limit = 200)
            }.getOrNull() ?: return@launch
            val list = page.items
            if (list.isEmpty()) return@launch
            val resumable = list.firstOrNull { it.positionMs > 0 && !it.userData.played }
            val target = resumable ?: list.first()
            if (target.id != id) show(target.id)
        }
    }

    /**
     * 用户口径（2026-11-27，#16）**记下用户选的音轨/字幕，播放时优先使用**。
     *
     * 以前这两个选项点了只收起菜单（详情页传的是空函数），播放永远按服务端默认走。
     * 现在存进 DetailData，由 PlayerActivity 读出来传给 VideoSession。
     */
    fun selectAudio(index: Int) {
        _state.update { s ->
            if (s is Load.Ready) Load.Ready(s.value.copy(selectedAudioIndex = index)) else s
        }
    }

    fun selectSubtitle(index: Int?) {
        _state.update { s ->
            if (s is Load.Ready) Load.Ready(s.value.copy(selectedSubtitleIndex = index)) else s
        }
    }

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
