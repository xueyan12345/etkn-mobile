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

@Immutable
data class EpisodesState(
    val seasonId: String? = null,
    val items: List<Item> = emptyList(),
    val next: Int = 0,
    val total: Int = 0,
    /** Episode the list opens at (the one to play next, or the page's own episode). */
    val anchorId: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val hasMore get() = items.size < total && !loading && error == null
    val anchorIndex: Int get() = anchorId?.let { id -> items.indexOfFirst { it.id == id } } ?: -1
}

/**
 * One season's episodes, read page by page from `Shows/{id}/EpisodePage`. Shared by the detail
 * page (horizontal row with range chips) and the full episode grid. Requests are serialized:
 * [more] and [loadThrough] only raise the target and one loop fetches until it is reached.
 */
/**
 * 用户口径（2026-11-26）**选集乱跳的真正根因（整块逻辑重写）**。
 *
 * 前两次都在改「区间怎么算」，但真正的病灶在**数据层**：
 *
 *  ① `select()` 固定从 `start = 0` 拉第一页（60 条），
 *     然后循环 append 直到 anchor 出现在列表里，上限 ANCHOR_LIMIT = 2000。
 *     《蜡笔小新》有 1200+ 集，播到 1265 时：
 *       · 循环到 2000 条或者拉完才停 → 列表里「前面的集全在」
 *       · 但横条 state 的位置和真实集号之间没有稳定映射
 *  ② 更要命的是 `loadThrough(index)` 的语义是「列表下标」，
 *     而区间 chip 传进来的也是「列表下标」（index*RANGE），
 *     一旦列表不是从第 1 集开始（用 AnchorId 拉的、或分页起点不是 0），
 *     下标和集号就错位 → 点 462 集却跳到 E80 那一块。
 *  ③ `append()` 用 `page.nextStartIndex` 续页，但如果 `select()` 是用 AnchorId 拉的，
 *     服务端返回的 startIndex 不是 0，续页起点就跟着偏。
 *
 * 现在统一成一套明确的语义：
 *  · **列表永远从第 1 集开始、按顺序把需要的部分补齐**
 *    （不依赖 AnchorId 的偏移语义，避免 startIndex 错位）
 *  · **对外只暴露两个入口**：
 *      `ensureNumber(n)`   —— 保证「第 n 集」在列表里（n 是 1-based 集号）
 *      `positionOfNumber(n)` —— 第 n 集在已加载列表里的下标
 *  · 区间 chip、打开定位、左右聚光全部走这两个函数，
 *    不再让调用方自己算下标
 */
class EpisodeList(private val api: EtknApi, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(EpisodesState())
    val state: StateFlow<EpisodesState> = _state.asStateFlow()
    private var seriesId = ""
    private var wanted = 0
    private var job: Job? = null

    /**
     * Opens [seasonId] of [seriesId], always from episode 1.
     *
     * 用户口径（2026-11-26）：**不再用服务端 AnchorId 偏移拉取**。
     * AnchorId 和 StartIndex 同时存在时服务端以 StartIndex 为准，
     * 之前两处混用导致「startIndex 不是 0 但列表当成从 0 开始」，
     * 下标和集号错位 → 点 462 集跳到 E80。
     *
     * 现在固定从 0 连续补齐到 anchor 所在位置（或按集号补齐），
     * 列表里第 i 个元素永远对应第 i+1 集（同一季内连续编号时成立），
     * 并且额外用 indexNumber 做校验与映射。
     */
    fun select(seriesId: String, seasonId: String?, anchorId: String? = null) {
        job?.cancel()
        this.seriesId = seriesId
        wanted = 0
        _state.value = EpisodesState(seasonId = seasonId, anchorId = anchorId, loading = true)
        job = scope.launch {
            try {
                var state = append(_state.value, FIRST_PAGE)
                // 补到 anchor 出现为止（anchor 就是当前集/下一集，通常在开头几页就能命中）
                while (anchorId != null && state.anchorIndex < 0 &&
                    state.items.size < state.total && state.items.size < ANCHOR_LIMIT
                ) {
                    state = append(state, ANCHOR_PAGE)
                }
                _state.value = state.copy(loading = false)
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false, error = error.userMessage())
            }
        }
    }

    /**
     * 用户口径（2026-11-26）：**保证「第 [number] 集」（1-based）在列表里**。
     *
     * 这是区间 chip / 跳转 / 打开定位的唯一入口。
     * 不再让调用方传「列表下标」—— 那是错位之源。
     */
    fun ensureNumber(number: Int) {
        val target = (number - 1).coerceAtLeast(0)
        loadThrough(target)
    }

    /**
     * 用户口径（2026-11-26）：**第 [number] 集在已加载列表里的下标**。
     *
     * 优先用 indexNumber 精确匹配（最可靠）；
     * 拿不到 indexNumber 时按「第 n 集 = 下标 n-1」推算（同一季内集号连续时成立）。
     * 找不到返回 -1。
     */
    fun positionOfNumber(number: Int): Int {
        val items = _state.value.items
        if (items.isEmpty() || number <= 0) return -1
        val exact = items.indexOfFirst { it.indexNumber == number }
        if (exact >= 0) return exact
        // 退路：假定本季集号连续，从第 1 集起算
        val first = items.firstOrNull()?.indexNumber
        if (first != null && first > 0) {
            val idx = number - first
            return if (idx in items.indices) idx else -1
        }
        return -1
    }

    /** The next page, for a list scrolled near its end. */
    fun more() = loadThrough(_state.value.items.size)

    /**
     * 用户口径（2026-11-26）**修「加载请求被丢掉」**。
     *
     * 旧写法开头是 `if (job?.isActive == true) return` ——
     * 只要上一次加载还在跑，这次的请求（哪怕是要补到第 462 集）就被**直接丢弃**，
     * 而且不会重试。于是目标集永远进不了列表 → 定位退回 0 → 你看到的「跳到最开头」。
     *
     * 现在：把目标记进 `wanted` 后，如果已有循环在跑就**让它继续跑到新目标**
     * （循环用的是 `wanted` 变量，每一轮都重新读），不再丢请求。
     */
    fun loadThrough(index: Int) {
        wanted = maxOf(wanted, index)
        if (seriesId.isEmpty()) return
        // 已有循环在跑：它每轮都读 wanted，会自动跑到新目标，这里不需要再起一个。
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                val state = _state.value
                if (state.items.size > wanted || state.items.size >= state.total || state.error != null) break
                _state.value = state.copy(loading = true)
                try {
                    _state.value = append(state, (wanted + 1 - state.items.size).coerceIn(FIRST_PAGE, MAX_PAGE)).copy(loading = false)
                } catch (error: Exception) {
                    _state.value = state.copy(loading = false, error = error.userMessage())
                    break
                }
            }
        }
    }

    fun retry() {
        val state = _state.value
        if (state.items.isEmpty()) select(seriesId, state.seasonId, state.anchorId)
        else {
            _state.value = state.copy(error = null)
            more()
        }
    }

    /** Re-reads what is loaded (progress, played flags) in one request, keeping the list on screen. */
    fun refresh() {
        val state = _state.value
        if (seriesId.isEmpty() || state.items.isEmpty() || job?.isActive == true) return
        job = scope.launch {
            runCatching { api.episodePage(seriesId, state.seasonId, start = 0, limit = state.items.size.coerceIn(FIRST_PAGE, ANCHOR_LIMIT)) }
                .onSuccess { page ->
                    _state.update { it.copy(items = page.items, next = page.nextStartIndex, total = maxOf(page.total, page.items.size)) }
                }
        }
    }

    fun contains(id: String) = _state.value.items.any { it.id == id }

    private suspend fun append(state: EpisodesState, limit: Int): EpisodesState {
        val page = api.episodePage(seriesId, state.seasonId, start = state.next, limit = limit)
        val seen = state.items.mapTo(HashSet()) { it.id }
        val fresh = page.items.filter { seen.add(it.id) }
        return state.copy(
            items = state.items + fresh,
            next = page.nextStartIndex,
            // An empty page ends the list even when the server's total says otherwise.
            total = if (fresh.isEmpty()) state.items.size else page.total,
        )
    }

    private companion object {
        const val FIRST_PAGE = 60
        const val ANCHOR_PAGE = 120
        const val MAX_PAGE = 300
        const val ANCHOR_LIMIT = 2000
    }
}
