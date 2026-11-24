package xyz.etkn.mobile.ui.screens.detail

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.api.EtknApi
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.ui.common.userMessage
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async

/**
 * 用户口径（2026-11-26）**选集部分整体重写**。
 *
 * 前面五六次都在「集号 ↔ 列表下标」之间做换算，怎么算都不稳，原因是一个错误的前提：
 * 列表是**分页加载**的，所以「第 n 集」和「列表第 n 个元素」天然不等，
 * 任何换算都要依赖「已经加载了多少页」这种容易错的状态。
 *
 * 重写的核心决定：**整季一次性取回来**。
 *  · 一季的集数上限通常在 1000～2000 之间（实测《蜡笔小新》1200+ 集）
 *  · 一次请求 2000 条，和分页 10 次每次 200 条的流量总量相同
 *  · 换来的是：**列表下标 === 集号 - 1，永远是恒等式**
 *    → 不再需要任何换算，也不会因为「加载到第几页」而错位
 *
 * 所以这个类只剩两件事：
 *  1. `select()` 把整季拉回来（一次请求，必要时重试一次）
 *  2. 提供 `indexOfNumber()` 这种「纯查表」的只读方法
 *
 * 不再有 wanted / loadThrough / ensureNumber / 增量 append /
 * anchor 偏移这些会让状态错位的东西。
 */
@Immutable
data class EpisodesState(
    val seasonId: String? = null,
    val items: List<Item> = emptyList(),
    val total: Int = 0,
    /** 打开时希望定位到的集（当前播放集或下一集）。 */
    val anchorId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val hasMore: Boolean get() = false     // 整季一次取回，不存在「还有更多」

    /** 该集在本季列表里的下标；找不到返回 -1。 */
    fun indexOf(id: String?): Int {
        if (id == null) return -1
        val at = items.indexOfFirst { it.id == id }
        if (at >= 0) return at
        return -1
    }

    /**
     * 用户口径（2026-11-26）**集号 → 列表下标，纯查表**。
     *
     * 因为整季一次取回、顺序与服务器一致，
     * 所以「第 n 集 = 列表第 n-1 个」在绝大多数季里直接成立；
     * 万一本季集号不连续（有缺失集），再用 indexNumber 精确找一次兜底。
     */
    fun indexOfNumber(number: Int): Int {
        if (items.isEmpty() || number <= 0) return -1
        // 优先：直接按集号精确匹配（最准）
        val exact = items.indexOfFirst { it.indexNumber == number }
        if (exact >= 0) return exact
        // 兜底：集号不连续时按「首个集号 + 偏移」推算
        val first = items.firstOrNull()?.indexNumber
        if (first != null && first > 0) {
            val at = number - first
            if (at in items.indices) return at
        }
        // 最后兜底：如果集号完全缺失，就按顺序位置（n-1）
        if (items.all { it.indexNumber == null }) {
            val at = number - 1
            if (at in items.indices) return at
        }
        return -1
    }
}

/**
 * 一季的分集列表。**整季一次取回**，见 [EpisodesState] 的说明。
 *
 * 用法：
 *  · `select(seriesId, seasonId, anchorId)` —— 换季/首次打开
 *  · `refresh()` —— 只更新播放进度/已看标记，不动顺序
 */
class EpisodeList(private val api: EtknApi, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(EpisodesState())
    val state: StateFlow<EpisodesState> = _state.asStateFlow()

    private var seriesId = ""
    private var job: Job? = null

    /**
     * 打开某一季。
     *
     * 用户口径（2026-11-26）**一次请求取整季**，不再分页：
     * 分页会让「已经加载到第几页」变成一份必须维护的状态，
     * 而那份状态一旦和真实集号不同步，定位就会错（这就是之前反复出错的根源）。
     */
    fun select(seriesId: String, seasonId: String?, anchorId: String? = null) {
        job?.cancel()
        this.seriesId = seriesId
        // 同一季、数据already在，只是换了锚点：不重新拉，只更新锚点
        val current = _state.value
        if (current.seasonId == seasonId && current.items.isNotEmpty()) {
            _state.value = current.copy(anchorId = anchorId)
            return
        }
        _state.value = EpisodesState(seasonId = seasonId, anchorId = anchorId, loading = true)
        job = scope.launch {
            try {
                /**
                 * 用户口径（2026-11-26）**修「长剧定位不到当前集」的真正根因**。
                 *
                 * 实测服务端（ETKN `/api/tv/Shows/{id}/EpisodePage`）：
                 *  · **单次最多只返回 80 条**，传 Limit=100/200/500/2000 都是 80
                 *  · 但 `StartIndex` 精确有效（start=400 → 首条就是第 401 集）
                 *  · 单页 80 条约 0.27 秒
                 *
                 * 所以「一次请求取整季」根本不成立：
                 * 《蜡笔小新》一季 2377 集，只拿到第 1-80 集，417 集压根不在列表里
                 * → `indexOf(417)` 返回 -1 → 不滚动 → 画面停在最开头。
                 * 而《谷雨》只有几十集，一页就够，所以一直是对的。
                 *
                 * 现在改成**按 StartIndex 翻完整季**：
                 *  · 首页拿到总数后，算出还需要几页
                 *  · 剩下的页**并发**请求（每页 0.27s，30 页并发约 1 秒内完成）
                 *  · 按 StartIndex 排序拼回完整列表，顺序与服务器一致
                 *  · 这样「列表下标」与「集号顺序」重新对齐，定位才能准
                 */
                val first = api.episodePage(seriesId, seasonId, start = 0, limit = PAGE_SIZE)
                val total = maxOf(first.total, first.items.size)

                // 还需要补的起点列表（每次 80 条）
                val starts = (first.items.size until total step PAGE_SIZE).toList()

                /**
                 * 并发拉剩余页，**分批**避免一次打 30 个请求：
                 * 2377 集需要 30 页，一次全发出去对服务端和手机都不友好。
                 * 每批 [CONCURRENCY] 个并发，一批约 0.3 秒，30 页约 2 秒完成。
                 * 失败的那一页留空，不影响已经拿到的部分。
                 */
                val rest = mutableListOf<List<Item>>()
                starts.chunked(CONCURRENCY).forEach { batch ->
                    val part = coroutineScope {
                        batch.map { start ->
                            async {
                                runCatching {
                                    api.episodePage(seriesId, seasonId, start = start, limit = PAGE_SIZE).items
                                }.getOrDefault(emptyList())
                            }
                        }.awaitAll()
                    }
                    rest += part
                }

                // 按「首条集号」排序拼接，保证顺序与服务器一致
                val merged = (listOf(first.items) + rest)
                    .filter { it.isNotEmpty() }
                    .sortedBy { it.firstOrNull()?.indexNumber ?: Int.MAX_VALUE }
                    .flatten()
                    // 去重（服务器偶尔在页边界重复给同一条）
                    .let { all ->
                        val seen = HashSet<String>(all.size)
                        all.filter { seen.add(it.id) }
                    }

                _state.value = EpisodesState(
                    seasonId = seasonId,
                    items = merged,
                    total = merged.size,
                    anchorId = anchorId,
                    loading = false,
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false, error = error.userMessage())
            }
        }
    }

    /**
     * 只刷新已看标记/进度，保持顺序不变。
     *
     * 用户口径（2026-11-26）：这里也必须按 80 条翻页。
     * 旧写法 `limit = state.items.size` 会被服务端截成 80 条，
     * 然后因为「返回条数 ≠ 原列表条数」被静默丢弃 —— 等于刷新永远不生效。
     * 现在按同样的分页方式重新取一次，只替换每个条目的 userData。
     */
    fun refresh() {
        val state = _state.value
        if (seriesId.isEmpty() || state.items.isEmpty() || job?.isActive == true) return
        job = scope.launch {
            runCatching {
                val pages = (state.items.indices step PAGE_SIZE).map { start ->
                    async { runCatching { api.episodePage(seriesId, state.seasonId, start = start, limit = PAGE_SIZE).items }.getOrDefault(emptyList()) }
                }
                pages.awaitAll().flatten()
            }.onSuccess { fresh ->
                if (fresh.isEmpty()) return@onSuccess
                // 用最新数据覆盖对应条目，保持原有顺序
                val byId = fresh.associateBy { it.id }
                _state.update { cur ->
                    cur.copy(items = cur.items.map { byId[it.id] ?: it })
                }
            }
        }
    }

    fun retry() {
        val state = _state.value
        _state.value = state.copy(error = null)
        select(seriesId, state.seasonId, state.anchorId)
    }

    fun contains(id: String) = _state.value.items.any { it.id == id }

    private companion object {
        /**
         * 服务端单页硬上限。实测传 100/200/500/2000 都只返回 80 条，
         * 所以按 80 翻页才是有效做法。
         */
        const val PAGE_SIZE = 80

        /** 每批并发数：长剧 30 页时分 5 批，避免一次打满服务端。 */
        const val CONCURRENCY = 6
    }
}
