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
import xyz.etkn.mobile.data.settings.HeroSnapshot

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

class HomeViewModel(
    private val graph: AppGraph,
    /** 用户口径（2026-11-23）：轮播快照要按账号存本地，需 Application Context。 */
    private val context: android.content.Context,
) : ViewModel() {
    private val api = graph.api
    private val _state = MutableStateFlow<Load<HomeData>>(Load.Loading)
    val state: StateFlow<Load<HomeData>> = _state.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private var heroJob: Job? = null
    /** 上一次算出 hero 的账号 key：用于判断切账号后不能沿用旧轮播。 */
    private var lastHeroAccountKey: String? = null
    private var lastLoad = 0L

    init {
        showCached()
        load()
        // Changes from the item menu: reload the shelves quietly (the hero is kept).
        viewModelScope.launch { graph.itemChanges.collect { load() } }
        /**
         * 用户口径（2026-11-26）：播放器退出/切集上报完成后 → **立刻**刷续播，
         * 不受 onResume 那个 60 秒节流限制。
         */
        viewModelScope.launch {
            graph.playbackChanged.collect {
                lastLoad = 0L          // 清掉节流，允许马上刷
                refreshResume()
            }
        }
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
            /**
             * 用户口径（2026-11-24）：**缓存路径也要按账号的 HeroSnapshot 出图**。
             *
             * 原来这里用 fallbackHero(最新入库头 6 部) —— 完全不看账号，
             * 于是「切账号 → 先显示缓存的旧图，必须手动刷新才变」。
             * 现在优先读当前账号的 snapshot；没有 snapshot 才退回 fallback。
             */
            val shown = HeroSnapshot.read(context, sessionKey())
            val pool = cached.latest.flatMap { it.items }.filter(::hasBackdrop).distinctBy { it.id }
            val byId = pool.associateBy { it.id }
            val fromSnapshot = shown?.ids?.mapNotNull { byId[it] }.orEmpty()
            val hero = if (fromSnapshot.isNotEmpty()) fromSnapshot else fallbackHero(cached)
            /**
             * 用户口径（2026-11-26）**绝不让 hero 变空**。
             *
             * HomeScreen 里 `hasHero = data.hero.isNotEmpty()`，
             * 一旦为空，hero 那个 item 会被整个移除、换成 12dp 的 Spacer
             * → 顶部整块消失，用户看到的就是一次白闪。
             *
             * 所以这里如果算出来是空（缓存里没有该账号的对应条目），
             * 就保留上一次已经在显示的 hero，宁可短暂显示旧图也不闪。
             */
            val keepOld = (_state.value as? Load.Ready)?.value?.hero.orEmpty()
            _state.value = Load.Ready(cached.copy(hero = hero.ifEmpty { keepOld }))
        }
    }

    private fun load(manual: Boolean = false) {
        if (manual) _refreshing.value = true
        viewModelScope.launch {
            try {
                /**
                 * 用户口径（2026-11-24）**修「切换账号后轮播图用旧缓存」**。
                 *
                 * 原来这里无条件把「上一次的 hero」塞进新数据：
                 *     .let { fresh -> fresh.copy(hero = (_state.value as? Load.Ready)?.value?.hero.orEmpty()) }
                 * 切账号时 _state.value 里还是上一个账号的 hero → 直接沿用了
                 * → 用户看到的就是「切过去还是老的，必须手动刷新」。
                 *
                 * 现在只有【同一个账号】才沿用上一次的 hero（避免首页闪一下空轮播）；
                 * 账号变了就让它空着，由 loadHero 立刻按新账号的 HeroSnapshot 算。
                 */
                /**
                 * 用户口径（2026-11-26）**修「切账号轮播图闪一下」**。
                 *
                 * 上一版在这里按「是否同账号」决定要不要保留上一次的 hero：
                 * 账号变了就 `emptyList()` → 轮播图区按空列表渲染一帧空白
                 * → 用户看到的就是一次约 100ms 的白闪
                 *（实测录屏第 15 帧：顶部大图整体泛白、图片近乎消失，下一帧才恢复）。
                 *
                 * 正确做法：**永远保留上一次的 hero 作为占位**，不要清空。
                 * 新账号的推荐算好后由 loadHero 一次性替换 —— 这样中间不会出现空白帧。
                 * 「切过去还是旧图」的问题已由 HeroSnapshot 按账号读取解决（见 loadHero），
                 * 与这里的占位无关，所以保留旧图不会带来「显示上一个账号推荐」的问题。
                 */
                val data = withContext(graph.cacheUse(ResponseCache.Mode.Store)) { fetch() }
                    .let { fresh ->
                        val keep = (_state.value as? Load.Ready)?.value?.hero.orEmpty()
                        fresh.copy(hero = keep)
                    }
                val fromCache = (_state.value as? Load.Ready) != null && lastLoad == 0L
                lastLoad = System.currentTimeMillis()
                _state.value = Load.Ready(data)
                LauncherEntries.publishResume(graph.app, data.resume, keep = graph.sessions.current?.privateAccount == false)
                // A shown hero (cached or previous) is swapped in one step, never shrunk to one card.
                /**
                 * 用户口径（2026-11-26）**hero 数量不够就补齐，不必等手动刷新**。
                 *
                 * 修的问题（h 账号实测）：进首页只看到 3 个轮播，
                 * 等半天不动，必须手动刷新才补齐到 8（现在 10）个。
                 *
                 * 根因：原来只有 `data.hero.isEmpty()` 才触发 loadHero，
                 * 但首屏用 fallbackHero 兜底时已经填了 3 个（非空），
                 * 于是网络数据回来后不再补齐。
                 *
                 * 现在：只要少于 HERO_SIZE 就补齐。
                 * 补齐过程是「算好整体替换」，不会中间清空 → 不会闪。
                 */
                val heroShort = data.hero.size < HERO_SIZE
                if (heroShort || manual || fromCache) loadHero(data, quiet = !manual)
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
     * 用户口径（2026-11-23）轮播图：
     * · 不读服务端 `PersonalRecommendations`（服务端 media.item_vectors 是空表，
     *   personal_vector 推荐实测一直返回 0 条，还会因为缺 ShuffleSeed 报 400）
     * · 从本账号所有影视库各自取一批，合并去重后**整体打乱**随机抽 8 部
     * · 按账号独立，**「上次生成满 12 小时」才换一批**（不是按日历 0 点/12 点换）
     *
     * 为什么不用日历桶：用户两天没打开 App，一打开日历桶早变了好几轮，
     * 但更关键的是「上午看到一批好片、下午再打开就换掉了，找不回来」。
     * 现在改成记 lastShownAt：不足 12 小时就用上次那 8 个 id，
     * 超过 12 小时才重抽。这样「一天开十次」看到的都是同一批，
     * 「两天没开」一进来就是新的一批。
     */
    private fun loadHero(data: HomeData, quiet: Boolean = false) {
        heroJob?.cancel()
        heroJob = viewModelScope.launch {
            val accountKey = sessionKey()
            lastHeroAccountKey = accountKey
            val shown = HeroSnapshot.read(context, accountKey)
            if (shown != null && System.currentTimeMillis() - shown.at < HERO_TTL_MS) {
                /**
                 * 未过期：沿用上次那 8 个 id。
                 * 但要先剔除「已看完 / 已删除」的 —— 用户明确要求轮播只推没看完的。
                 * 剔除后不足 8 个就当场补新的（不用等到过期）。
                 */
                val pool = heroPool(data)
                val byId = pool.associateBy { it.id }
                val kept = shown.ids.mapNotNull { byId[it] }.filter { !isFinished(it) }
                /**
                 * 用户口径（2026-11-26）**快照里的第 1 位也要是「继续播放」**。
                 *
                 * 旧快照（改版前存的 8 个纯随机）没有继续播放位，
                 * 直接沿用会让用户「改完还是老样子」。所以这里检查：
                 * 第 1 位必须来自当前 resume 列表，否则视为无效快照 → 重抽。
                 */
                val resumeIds = data.resume.map { it.id }.toSet()
                val firstId: String? = kept.firstOrNull()?.id
                val firstIsResume = firstId != null && firstId in resumeIds
                if (kept.size >= HERO_SIZE && firstIsResume) {
                    publishHero(kept.take(HERO_SIZE))
                    return@launch
                }
                // 掉的太多（看完了/删了）→ 重抽并刷新时间戳
            }
            val seed = System.currentTimeMillis()
            /**
             * 用户口径（2026-11-23）：轮播图 = 把所有媒体库的片子全部汇总 → 整体打乱 → 随机取 8 个。
             *
             * 明确不要分层抽样（不按库轮流取、不给每个库配额）。就是全量池打乱。
             * 桶内种子固定（0 点/12 点各一批），12 小时内不变。
             */
            val pool = coroutineScope {
                data.latest.map { shelfData ->
                    async {
                        val types = when (shelfData.library.collectionType) {
                            "movies" -> "Movie"
                            "tvshows" -> "Series"
                            else -> null
                        }
                        runCatching { api.latest(shelfData.library.id, types, HERO_POOL_PER_LIBRARY) }
                            .getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
            val candidates = pool.asSequence()
                .filter(::hasBackdrop)
                .filter { !isFinished(it) }
                .distinctBy { it.id }
                .toList()
            if (candidates.isNotEmpty()) {
                val picked = candidates.shuffled(Random(seed)).take(HERO_SIZE)
                publishHero(picked)
                // 记下这一批 + 时间戳，12 小时内不再换
                HeroSnapshot.write(context, accountKey, picked.map { it.id })
                return@launch
            }
            // 连库列表都还没到（首屏缓存路径）时退回旧的兜底，别让轮播空着
            if (!quiet) {
                val fallback = fallbackHero(data)
                if (fallback.isNotEmpty()) {
                    val randomNine = fallback.shuffled(Random(seed)).take(HERO_RANDOM_SIZE)
                    val picked = pickResumeFirst(data, randomNine, seed)
                    publishHero(picked)
                    HeroSnapshot.write(context, accountKey, picked.map { it.id })
                }
            }
        }
    }

    /** 账号 key（serverId|userId），轮播按账号独立。 */
    private fun sessionKey(): String? =
        (graph.sessions.state.value as? xyz.etkn.mobile.data.session.SessionState.SignedIn)?.session?.key

    /** 轮播候选池：本账号所有影视库各取一批，合并去重。 */
    private suspend fun heroPool(data: HomeData): List<Item> = coroutineScope {
        data.latest.map { shelfData ->
            async {
                val types = when (shelfData.library.collectionType) {
                    "movies" -> "Movie"
                    "tvshows" -> "Series"
                    else -> null
                }
                runCatching { api.latest(shelfData.library.id, types, HERO_POOL_PER_LIBRARY) }
                    .getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
            .asSequence()
            .filter(::hasBackdrop)
            .distinctBy { it.id }
            .toList()
    }

    private fun publishHero(items: List<Item>) {
        _state.update { current -> if (current is Load.Ready) Load.Ready(current.value.copy(hero = items)) else current }
    }

    /**
     * 用户口径（2026-11-26）**拼装轮播：第 1 位继续播放，后面全库随机**。
     *
     * 「继续播放」里既有分集（Episode）也有电影（Movie），都算 —— 用户明确要求。
     * 分集本身通常没有独立 backdrop（背景图），它的图片挂在所属剧上，
     * 所以这里不做 hasBackdrop 过滤，否则会把分集全滤掉、第一位永远是电影。
     * 图片取不到时由 HeroCarousel 那边按海报兜底。
     */
    private fun pickResumeFirst(data: HomeData, random: List<Item>, seed: Long): List<Item> {
        val resumePool = data.resume.filter { it.id.isNotBlank() }
        val first = if (resumePool.isNotEmpty()) {
            // 用同一个 seed 保证 12 小时内稳定（不是每次刷新都换）
            resumePool[Random(seed + 7).nextInt(resumePool.size)]
        } else {
            null
        }
        val rest = random.filter { it.id != first?.id }
        return (listOfNotNull(first) + rest).take(HERO_SIZE)
    }

    private fun fallbackHero(data: HomeData) =
        data.latest.flatMap { it.items }.filter(::hasBackdrop).distinctBy { it.id }.take(HERO_SIZE)

    /**
     * 用户口径（2026-11-23）：轮播图只推荐「没看完的 + 完全没看的」，
     * 已经看完的不再推（用户口径：完全看完的不推，更适合追剧）。
     *
     * 判据（关键：Series 自己的 played / playbackPositionTicks 恒为 0，
     * 播放进度记在 Episode 上，所以剧集必须看 unplayedItemCount）：
     *  · 电影：userData.played == true → 已看完
     *  · 剧集：unplayedItemCount == 0 且 recursiveItemCount > 0 → 全集看完
     *  · 字段拿不到 → 当作没看完（保守保留，别让轮播空掉）
     */
    private fun isFinished(item: Item): Boolean {
        val data = item.userData
        // 电影/单集：played 直接可用
        if (!item.isSeries) return data.played
        // 剧集：有任何一集未看就说明没追完
        val unplayed = data.unplayedItemCount
        val total = item.recursiveItemCount
        if (unplayed != null && total != null && total > 0) return unplayed <= 0
        // 拿不到计数时退回 played（对剧集通常为 false，等于保留）
        return data.played
    }

    private fun hasBackdrop(item: Item) = item.backdropTags.isNotEmpty() || item.parentBackdropTags.isNotEmpty()

    companion object {
        /**
     * 用户口径（2026-11-26）**轮播图改成 10 个**。
     *
     * 需求原文：「我想把轮播改成10个，我需要把第一个轮播图位置留下来，
     * 专门放继续播放里面的剧（无论是电视剧形式还是电影形式都算）
     * 随机抽出来一个放在第一个轮播图，后面的九个还是跟之前逻辑一样，
     * 在库里随机抽取，不要分层」
     *
     * 所以：第 1 个 = 继续播放随机 1 部，第 2~10 个 = 全库打乱随机 9 部。
     */
    private const val HERO_SIZE = 10

    /** 后面「全库随机」那把要凑几个（总 10 - 第 1 个继续播放位）。 */
    private const val HERO_RANDOM_SIZE = HERO_SIZE - 1
        private const val HERO_POOL_PER_LIBRARY = 30
        /** 轮播图「多久换一批」：满 12 小时（按上次生成时间算，不跟日历走）。 */
        private const val HERO_TTL_MS = 12L * 60L * 60L * 1000L
        private val NON_LATEST = setOf("music", "boxsets", "playlists", "livetv", "folders")
    }
}
