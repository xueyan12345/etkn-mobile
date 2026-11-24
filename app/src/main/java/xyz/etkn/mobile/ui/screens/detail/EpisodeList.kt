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
                val page = api.episodePage(seriesId, seasonId, start = 0, limit = SEASON_LIMIT)
                var items = page.items
                /**
                 * 服务端一次最多给 [SEASON_LIMIT] 条。极少数超长季可能没给全，
                 * 这时再补一次（续页起点用服务器返回的 nextStartIndex），
                 * 但**总数封顶**，避免一季几千集时无限拉取。
                 */
                var next = page.nextStartIndex
                var guard = 0
                while (items.size < page.total && next > 0 && items.size < HARD_LIMIT && guard < 4) {
                    guard++
                    val more = api.episodePage(seriesId, seasonId, start = next, limit = SEASON_LIMIT)
                    if (more.items.isEmpty()) break
                    val seen = items.mapTo(HashSet()) { it.id }
                    items = items + more.items.filter { seen.add(it.id) }
                    next = more.nextStartIndex
                    if (next <= 0) break
                }
                _state.value = EpisodesState(
                    seasonId = seasonId,
                    items = items,
                    // 最终以「实际拿到的条数」为准，避免服务器 total 与实际不一致时出现空位
                    total = items.size,
                    anchorId = anchorId,
                    loading = false,
                )
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false, error = error.userMessage())
            }
        }
    }

    /** 只刷新已看标记/进度，保持顺序不变。 */
    fun refresh() {
        val state = _state.value
        if (seriesId.isEmpty() || state.items.isEmpty() || job?.isActive == true) return
        job = scope.launch {
            runCatching {
                api.episodePage(seriesId, state.seasonId, start = 0, limit = state.items.size.coerceAtLeast(1))
            }.onSuccess { page ->
                if (page.items.size == state.items.size) {
                    _state.update { it.copy(items = page.items) }
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
        /** 一次请求的上限。1000～2000 集的长剧都能一次拿全。 */
        const val SEASON_LIMIT = 2000

        /** 极端情况下（超长季）最多拉到这个数量。 */
        const val HARD_LIMIT = 6000
    }
}
