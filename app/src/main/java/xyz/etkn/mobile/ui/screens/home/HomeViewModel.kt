package xyz.etkn.mobile.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.etkn.mobile.AppGraph
import xyz.etkn.mobile.data.events.ServerEvent
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.LibraryCounts
import xyz.etkn.mobile.data.net.ResponseCache
import xyz.etkn.mobile.launcher.LauncherEntries
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.userMessage
import kotlin.random.Random

@Immutable
data class LatestShelf(val library: Item, val items: List<Item>)

@Immutable
data class HomeData(
    val hero: List<Item> = emptyList(),
    val resume: List<Item> = emptyList(),
    val libraries: List<Item> = emptyList(),
    val latest: List<LatestShelf> = emptyList(),
    val musicLatest: Pair<Item, List<Item>>? = null,
    val counts: LibraryCounts? = null,
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    private val api = graph.api
    private val _state = MutableStateFlow<Load<HomeData>>(Load.Loading)
    val state: StateFlow<Load<HomeData>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private var heroJob: Job? = null
    private var lastLoad = 0L

    init {
        showCached()
        load()
        // Changes from the item menu: reload the shelves quietly (the hero is kept).
        viewModelScope.launch { graph.itemChanges.collect { load() } }
    }

    fun refresh() = load(manual = true)

    /** Reloads the shelves whenever the server's home revision moves (while the screen is started). */
    suspend fun followServer() {
        var seen: String? = null
        graph.events.stream().collect { event ->
            if (event !is ServerEvent.Home) return@collect
            if (seen != null && seen != event.revision) load()
            seen = event.revision
        }
    }

    /** Called when the screen resumes; reloads only the volatile shelves after a minute. */
    fun onResume() {
        if (System.currentTimeMillis() - lastLoad > 60_000) refreshResume()
    }

    /**
     * Last session's shelves from disk, painted while the network load runs; the hero starts
     * from cached titles with artwork until the recommendation stream answers.
     */
    private fun showCached() {
        viewModelScope.launch {
            val cached = runCatching { withContext(graph.cacheUse(ResponseCache.Mode.Read)) { fetch() } }.getOrNull() ?: return@launch
            if (_state.value is Load.Ready || cached.libraries.isEmpty()) return@launch
            _state.value = Load.Ready(cached.copy(hero = fallbackHero(cached)))
        }
    }

    private fun load(manual: Boolean = false) {
        if (manual) _refreshing.value = true
        viewModelScope.launch {
            try {
                val data = withContext(graph.cacheUse(ResponseCache.Mode.Store)) { fetch() }
                    .let { fresh -> fresh.copy(hero = (_state.value as? Load.Ready)?.value?.hero.orEmpty()) }
                val fromCache = (_state.value as? Load.Ready) != null && lastLoad == 0L
                lastLoad = System.currentTimeMillis()
                _state.value = Load.Ready(data)
                LauncherEntries.publishResume(graph.app, data.resume, keep = graph.sessions.current?.privateAccount == false)
                // A shown hero (cached or previous) is swapped in one step, never shrunk to one card.
                if (data.hero.isEmpty()) loadHero(data) else if (manual || fromCache) loadHero(data, quiet = true)
            } catch (error: Exception) {
                if (_state.value !is Load.Ready) _state.value = Load.Failed(error.userMessage())
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun fetch(): HomeData = coroutineScope {
        val viewsJob = async { api.views() }
        // Shelves come from the libraries themselves, including those inside a group.
        val librariesJob = async { api.libraries() }
        val resumeJob = async { runCatching { api.resume(20) }.getOrDefault(emptyList()) }
        val countsJob = async { runCatching { api.libraryCounts() }.getOrNull() }
        val views = viewsJob.await()
        val libraries = librariesJob.await()
        val videoLibraries = libraries.filter { it.collectionType !in NON_LATEST }
        val shelves = videoLibraries.map { library ->
            async {
                val types = when (library.collectionType) {
                    "movies" -> "Movie"
                    "tvshows" -> "Series"
                    else -> null
                }
                LatestShelf(library, runCatching { api.latest(library.id, types, 20) }.getOrDefault(emptyList()))
            }
        }
        val music = libraries.firstOrNull { it.collectionType == "music" }?.let { library ->
            async {
                runCatching {
                    library to api.musicList(library.id, "albums", "", 0, 20, newest = true).items
                }.getOrNull()
            }
        }
        HomeData(
            resume = resumeJob.await(),
            libraries = views,
            latest = shelves.awaitAll().filter { it.items.isNotEmpty() },
            musicLatest = music?.await()?.takeIf { it.second.isNotEmpty() },
            counts = countsJob.await(),
        )
    }

    private fun refreshResume() {
        viewModelScope.launch {
            val resume = runCatching { api.resume(20) }.getOrNull() ?: return@launch
            lastLoad = System.currentTimeMillis()
            _state.update { current -> if (current is Load.Ready) Load.Ready(current.value.copy(resume = resume)) else current }
        }
    }

    /**
     * 用户口径（2026-11-23）轮播图改成本地随机：
     * · 不读服务端 `PersonalRecommendations`（服务端 media.item_vectors 是空表，
     *   personal_vector 推荐实测一直返回 0 条，还会因为缺 ShuffleSeed 报 400）
     * · 改成：从本账号所有影视库各自取一批，合并去重后随机抽 8 部
     * · 12 小时固定不动：0 点、12 点各换一批（用时段桶当随机种子，同桶结果恒定）
     */
    private fun loadHero(data: HomeData, quiet: Boolean = false) {
        heroJob?.cancel()
        heroJob = viewModelScope.launch {
            val bucket = heroBucket()   // 0 点 / 12 点两档，桶内不变
            // 各库并行取，结果汇总到一个池子里
            val pool = coroutineScope {
                val jobs = data.latest.map { shelfData ->
                    async {
                        val types = when (shelfData.library.collectionType) {
                            "movies" -> "Movie"
                            "tvshows" -> "Series"
                            else -> null
                        }
                        runCatching { api.latest(shelfData.library.id, types, HERO_POOL_PER_LIBRARY) }
                            .getOrDefault(emptyList())
                    }
                }
                jobs.awaitAll().flatten()
            }
            // 只有带背景图的才能上轮播
            val candidates = pool.asSequence()
                .filter(::hasBackdrop)
                .distinctBy { it.id }
                .toList()
            if (candidates.isNotEmpty()) {
                // 同一个 12 小时桶内种子固定 → 抽出来的一批 12 小时不变
                publishHero(candidates.shuffled(Random(bucket)).take(HERO_SIZE))
                return@launch
            }
            // 连库列表都还没到（首屏缓存路径）时退回旧的兜底，别让轮播空着
            if (!quiet) {
                val fallback = fallbackHero(data)
                if (fallback.isNotEmpty()) {
                    publishHero(fallback.shuffled(Random(bucket)).take(HERO_SIZE))
                }
            }
        }
    }

    /** 12 小时一档的桶号：0~12 点、12~24 点各一个，用来做随机种子。 */
    private fun heroBucket(): Long {
        val now = java.util.Calendar.getInstance()
        val day = now.get(java.util.Calendar.DAY_OF_YEAR).toLong()
        val half = now.get(java.util.Calendar.HOUR_OF_DAY) / 12
        return day * 2 + half
    }

    private fun publishHero(items: List<Item>) {
        _state.update { current -> if (current is Load.Ready) Load.Ready(current.value.copy(hero = items)) else current }
    }

    private fun fallbackHero(data: HomeData) =
        data.latest.flatMap { it.items }.filter(::hasBackdrop).distinctBy { it.id }.take(HERO_SIZE)

    private fun hasBackdrop(item: Item) = item.backdropTags.isNotEmpty() || item.parentBackdropTags.isNotEmpty()

    companion object {
        private const val HERO_SIZE = 8
        private const val HERO_POOL_PER_LIBRARY = 30
        private val NON_LATEST = setOf("music", "boxsets", "playlists", "livetv", "folders")
    }
}
