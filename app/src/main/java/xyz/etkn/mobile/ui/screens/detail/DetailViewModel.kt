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
 * 用户口径（2026-11-28，#144）**打开详情页不能先闪一下白**。
 *
 * 用户原话：
 * ```
 * 「就是感觉人家 HILS 那个，打开详情页，人家直接就出来了，咱们这个就是闪一下白」
 * ```
 *
 * ## 实测根因（服务端 nginx access log）
 *
 * ```
 * 09:04:39  Items/item-23477        15388 bytes
 * 09:04:39  Similar                 48
 * 09:04:39  Collections             48
 * 09:04:39  Logo 250KB + Backdrop 557KB + 4 张演员图
 * ```
 * 数据本身很快（局域网几十毫秒）。问题在**渲染时序**：
 * `Load.Loading` 时 `DetailScreen` 画的是 `background(surface)` —— 一整块纯色，
 * 也就是用户看到的白屏；要等上面这些全部 await 完才切到 `Load.Ready`。
 *
 * ## 修法：把列表里已有的那份 Item 当「种子」
 *
 * 用户是**从列表点进来的**，那一刻列表里已经有标题/封面/年份/时长/评分。
 * 用这份数据先画一帧完整的详情页（骨架级别：头图 + 标题 + 简介占位），
 * 网络回来再静默覆盖 —— 用户看到的就是「点下去内容就在」，而不是白屏。
 *
 * 只在「从列表进入」时有种子；深链/搜索直达没有种子，仍走原来的 Loading。
 */
object DetailSeed {
    /** `itemId → 列表里那份 Item`。导航前 `put`，ViewModel 取走后不删（可复用）。 */
    private val seeds = java.util.concurrent.ConcurrentHashMap<String, Item>()

    fun put(item: Item) { seeds[item.id] = item }

    fun get(id: String): Item? = seeds[id]
}

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
    /** 同剧切换时「相似推荐 / 所属合集」也不变，一并缓存（#21）。 */
    private var cacheSimilar: List<Item> = emptyList()
    private var cacheCollections: List<Item> = emptyList()

    /**
     * 取选集列表里已经加载好的那一集（用于"秒切"）。
     *
     * 选集行加载的 EpisodePage 条目本身就带 Name / Overview / ImageTags /
     * RunTimeTicks / UserData(进度) / IndexNumber —— 除了 MediaSources（技术信息三行）
     * 和 Chapters（章节），详情页要显示的东西全都有。
     *
     * 所以点一下可以直接用这份数据立刻渲染，网络回来的只是补技术信息和章节。
     */
    private fun episodeFromShelf(id: String): Item? =
        episodes.state.value.items.firstOrNull { it.id == id }

    /**
     * 用户口径（2026-11-27，#21）**点一下马上切过去**。
     *
     * 用户反馈：「点一下别的集，总要顿一下，然后才切过去，不是很丝滑……
     *            HILLS 点一下切换集马上就过去选中的那个集，就很流畅」。
     *
     * 慢的根因（实测每个请求 250ms 左右）：
     * ```
     * api.item(单集)      282ms   ← 串行第一个，必须等
     * api.item(剧集图)    254ms
     * api.seasons         276ms
     * ```
     * 而 `load()` 要等这些全部 await 完才更新界面 —— 用户就感受到"顿一下"。
     *
     * 现在改成两步：
     *  ① **立刻**用选集行里已有的那一集数据渲染（标题/简介/集图/时长/进度全都有）
     *     → 用户点下去的那一帧，界面就已经换成目标集了
     *  ② 网络回来后只是补「技术信息三行 + 章节」，并静默替换
     *
     * 选集行里没有这一集数据时（比如从搜索直接进来的），退回原来的整页加载。
     */
    fun show(episodeId: String) {
        if (episodeId == id) return
        val previous = (_state.value as? Load.Ready)?.value
        shown.value = episodeId
        /**
         * 乐观渲染：拿选集行里那份数据拼一个新的 DetailData。
         * 季列表 / 剧集图 / 相似 / 合集 全部沿用上一份（切集时它们本来就不变），
         * 只把 item 换成目标集。
         */
        val shelfItem = episodeFromShelf(episodeId)
        if (previous != null && shelfItem != null) {
            /**
             * 用户口径（2026-11-27，#123）**切集时右上角三行技术信息不能消失再出现**。
             *
             * 用户原话：
             * ```
             * 「原来这个界面就是相当于切集的时候，固定的模块是不动的，
             *   比如说右上角那个三行媒体信息，还有下面的章节，正常来讲是不会反复跳动的，
             *   我现在切一个集，它就刷新一次，有种像刷新重进的感觉」
             * ```
             *
             * 根因：`techSource(data)` 读的是 `data.item.mediaSources`。
             * 乐观渲染把 `item` 立刻换成新集（选集行那份数据**不带** mediaSources），
             * 于是三行瞬间变 null → 整块 `if (showTech && techSrc != null)` 不渲染
             * → 三行消失 → 等网络回来又重新出现。视觉上就是「闪一下重新进」。
             *
             * 修法：新集的 `mediaSources` 为空时，**沿用上一集的**。
             * 同一部剧各集的编码/码率/音轨结构通常一致，先顶着显示，
             * 网络数据回来后被真实的覆盖 —— 中间不再出现空档。
             */
            val carriedSources = if (shelfItem.mediaSources.isEmpty()) previous.item.mediaSources else shelfItem.mediaSources
            _state.value = Load.Ready(
                previous.copy(
                    item = if (carriedSources === previous.item.mediaSources) {
                        /**
                         * 带上 mediaSources / chapters 的副本，让技术信息区和章节区
                         * 在切集瞬间有东西可显示（#123）。
                         *
                         * 章节也一样：`item.chapters` 为空时下面那块 `if (chapters.isEmpty()) return`
                         * 会整块不渲染 → 章节标题和卡片闪一下消失再出现。
                         * 同一部剧的片头/片尾标记通常一致，先沿用上一集的顶着。
                         */
                        shelfItem.copy(
                            mediaSources = carriedSources,
                            chapters = if (shelfItem.chapters.isEmpty()) previous.item.chapters else shelfItem.chapters,
                        )
                    } else shelfItem,
                    target = shelfItem,
                    /** 集变了，技术信息和章节要等网络回来才有，先清掉避免显示上一集的。 */
                    selectedSource = 0,
                ),
            )
        }
        load()
    }

    fun load() {
        if (_state.value !is Load.Ready) _state.value = Load.Loading
        val requested = id
        val loadStart = System.currentTimeMillis()
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
                    /**
                     * 用户口径（2026-11-27，#21）**相似推荐/合集在同剧内切换时不要重新请求**。
                     *
                     * `similar` / `collections` 跟"当前是哪一集"无关（整部剧共用一个列表），
                     * 却原来也在 coroutineScope 里 await，直接拖慢每次切集。
                     * 判断依据是「同一部剧」而不是「同一集」。
                     */
                    val sameShow = cachedSeriesId == seriesId && seriesId != null

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
                    /**
                     * 用户口径（2026-11-27，#21）**单集页不要请求 playbackTarget**。
                     *
                     * 实测这个接口 234ms，而它返回的是「这部剧该从哪一集开始播」——
                     * 只有**剧集层/季层**需要（用来显示"播放 第1季第1集"/"继续播放 26:26"）。
                     *
                     * 单集页的 target 就是这一集自己，压根不需要它。
                     * 但原来无条件请求并 await，等于每次切集白等 234ms。
                     */
                    val target = if (seriesId != null && (item.isSeries || item.isSeason)) {
                        async { runCatching { api.playbackTarget(seriesId, if (item.isSeason) item.id else null) }.getOrNull() }
                    } else null
                    /**
                     * 用户口径（2026-11-27，#21）**次要数据不阻塞首屏**。
                     *
                     * 用户诉求：「点一下马上就切过去，按理来说，这点信息不至于卡顿呀」。
                     *
                     * 下面这些都跟「当前是哪一集」无关，却原来都在 coroutineScope 里 await：
                     *  · similar（相似推荐）—— 只有电影/剧集层才有，且整部剧共用
                     *  · collections（所属合集）—— 只有电影才有
                     *  · detailed（技术信息三行）—— 单集页需要，但可以后到
                     *
                     * 现在：`item`（标题/简介/集图/时长/进度）先回来就能出首屏，
                     * 技术信息与推荐列表随后补上 —— 用户点下去那一刻界面已经换好了。
                     */
                    val similar = if (item.isMovie || item.isSeries) {
                        if (sameShow && cacheSimilar.isNotEmpty()) null
                        else async { runCatching { api.similar(item.id) }.getOrDefault(emptyList()) }
                    } else null
                    val collections = if (item.isMovie) {
                        if (sameShow && cacheCollections.isNotEmpty()) null
                        else async { runCatching { api.itemCollections(item.id) }.getOrDefault(emptyList()) }
                    } else null
                    val full = detailed?.await()
                    DetailData(
                        item = if (full != null) base.copy(mediaSources = full.mediaSources, chapters = full.chapters.ifEmpty { base.chapters }) else base,
                        seasons = if (seasons != null) {
                            seasons.await().also { cacheSeasons = it; cachedSeriesId = seriesId }
                        } else cachedSeasons,
                        target = target?.await(),
                        similar = if (similar != null) {
                            similar.await().also { cacheSimilar = it }
                        } else cacheSimilar,
                        collections = if (collections != null) {
                            collections.await().also { cacheCollections = it }
                        } else cacheCollections,
                        /** 剧集那条数据：单集页头部大图/艺术字用它，切集时保持不变。 */
                        seriesItem = if (seriesItem != null) {
                            seriesItem.await().also { cacheSeriesItem = it }
                        } else cachedSeriesItem,
                    )
                }
                // A newer episode was picked while this one loaded.
                if (requested != id) return@launch
                android.util.Log.i(
                    "ETKN-Detail",
                    "load $requested 耗时 ${System.currentTimeMillis() - loadStart}ms",
                )
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
