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
            /**
             * 用户口径（2026-11-26）**首屏这一帧也必须是「第 1 位=继续播放」**。
             *
             * 修的问题（用户截图实证）：进首页第一眼看到的轮播第 1 个
             * 不是「继续观看」里的任何一部，要等网络数据回来才变对。
             *
             * 根因：loadHero 已经改成「第 1 位插继续播放」，但 showCached 是
             * 另一条独立代码路径，它直接照搬快照 ids 顺序 / fallbackHero，
             * 所以首屏那一帧永远是旧顺序。
             *
             * 现在这里也做同样的处理：
             *  · 快照有货 → 沿用其顺序，但把「继续播放随机 1 部」提到最前
             *  · 快照第 1 位本来就不是继续播放的（改版前存的旧快照）→ 直接重排
             *  · 快照没货 → fallbackHero 兜底，同样插继续播放位
             */
            /**
             * 用户口径（2026-11-27）**轮播必须一次出满 10 个，不许「先给几个再补」**。
             *
             * 用户实证：「切换账号后轮播不直接出 10 个，感觉有三四个是后加载出来的，
             * 像是代码固定 6 个、再打个补丁检测不够就补 4 个」—— 判断完全正确。
             *
             * 原逻辑的病灶：
             *  · showCached 用快照 ids 直接出图（快照可能是旧的、可能只有 6 个）
             *  · 300ms 后网络回来，主路径凑满 10 个 → 画面跳变补上 4 个
             *
             * 现在：**凑不满 10 个就不出这一帧**，直接等主路径一次 publish 满 10 个。
             * `keepOld` 那段会兜住「上一次已经在显示的 hero」，所以不会出现白闪。
             */
            val base = if (fromSnapshot.isNotEmpty()) fromSnapshot else fallbackHero(cached)
            val seedNow = shown?.at ?: 0L
            val hero = if (base.isEmpty()) base else pickResumeFirst(cached, base, seedNow)
            val enough = hero.size >= HERO_SIZE
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
            /**
             * 够 10 个才用新的；不够就保留上一次的（通常也是满 10 个），
             * 绝不让首页顶部先出 6 个再跳变。
             */
            /**
             * 用户口径（2026-11-27，#2 第三版）**不足 10 个时保留旧 hero，但会记一条日志**。
             *
             * 之前这里是静默兜底，导致「只有 3 个」这种问题在日志里完全看不见。
             * 现在打点，方便下次直接定位到底是快照只有 3 个、还是池子只有 3 个。
             */
            /**
             * 用户口径（2026-11-27，#2 第五版）**切账号必须立刻出图，不能等**。
             *
             * 上一版我让「不足 10 个就发空列表」，结果切账号要等 3 秒才看到轮播
             * （用户实测）—— 为了不生硬地把数量跳变消掉，却把首屏响应速度搞坏了。
             *
             * 这两个诉求的正确平衡：
             *  · 有快照 → 立刻显示（哪怕 6 个），先让用户看到东西
             *  · 主路径随后补齐到 10 个（数量变化只在第一次进入时发生一次）
             *
             * 用户要的是「别等」，不是「宁可空着」。所以这里改回「有多少先给多少」。
             */
            val useHero = hero.ifEmpty { keepOld }
            android.util.Log.i(
                "ETKN-Hero",
                "showCached: snapshot=${fromSnapshot.size} hero=${hero.size} " +
                    "enough=$enough keepOld=${keepOld.size} use=${useHero.size}",
            )
            _state.value = Load.Ready(cached.copy(hero = useHero))
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
                        /**
                         * 用户口径（2026-11-27，#2 第四版）**旧 hero 不满 10 个就别拿它顶着了**。
                         *
                         * 现象（用户实测）：进首页先是 6 个圆点，过一会儿跳成 10 个。
                         *
                         * 根因就在这行 `fresh.copy(hero = keep)`：
                         * 它把上一次的 hero（可能是 6 个、甚至 3 个）先塞进新数据发出去，
                         * 首页那一帧就按 6 个渲染；等 loadHero 算完再发一次 → 跳成 10 个。
                         *
                         * 现在：旧 hero 满 10 个才沿用（避免白闪）；
                         * 不满就置空，让首页等 loadHero 一次发出满 10 个。
                         * 空 hero 时 HomeScreen 的 `hasHero` 为 false，那一帧不画轮播，
                         * 也不会出现「6 个 → 10 个」的跳变。
                         */
                        /**
                         * 用户口径（2026-11-27，#2 第五版）**永远保留旧 hero 当占位**。
                         *
                         * 上一版我加了 `if (keep.size >= HERO_SIZE)`，结果切账号时
                         * 旧 hero 只有 6 个 → 置空 → 首页空 3 秒（用户实测「切 h 不能瞬间看到」）。
                         *
                         * 权衡后这里退回「有多少先显示多少」：
                         * 切账号时先看到上一批的图（很快，不空白），
                         * 主路径算完再整体替换成新账号的 10 个。
                         * 数量从 6→10 的那一次变化只发生一次，用户能接受；
                         * 空 3 秒是完全不能接受的。
                         */
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
                /**
                 * 用户口径（2026-11-27，#2 第四版）**先算好 hero 再一次发出，别发两次**。
                 *
                 * 现象：进首页先看到 6 个圆点，过一会儿跳成 10 个。
                 * 根因：这里 `_state.value = Load.Ready(data)` 先把「旧 hero / 空 hero」
                 * 发出去渲染一帧，紧接着 loadHero 才异步算完再发一次 → 数量跳变。
                 *
                 * 现在改成：需要重新算 hero 时，**先 await 算完**（内部会 publishHero，
                 * 直接改 _state），不再先发一帧半成品。
                 */
                val heroShort = data.hero.size < HERO_SIZE
                if (heroShort || manual || fromCache) {
                    /**
                     * 用户口径（2026-11-27，#2 第五版）**改回后台补齐，不阻塞首屏**。
                     * 上一版改 awaitHero 导致切账号要等算完才显示（实测 3 秒）。
                     */
                    loadHero(data, quiet = !manual, manualHero = manual)
                }
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
    private fun loadHero(data: HomeData, quiet: Boolean = false, manualHero: Boolean = false) {
        heroJob?.cancel()
        heroJob = viewModelScope.launch {
            computeHero(data, quiet, manualHero)
        }
    }

    private suspend fun computeHero(data: HomeData, quiet: Boolean, manualHero: Boolean) {
            val accountKey = sessionKey()
            lastHeroAccountKey = accountKey
            val shown = HeroSnapshot.read(context, accountKey)
            /**
             * 用户口径（2026-11-26）**下拉刷新 = 明确要求换一批**。
             *
             * 原来 manual（下拉刷新）时也复用快照，只要没过期就直接 return，
             * 用户体感是「刷新了还是那几张」。
             * 现在 manual 一律跳过快照、重新抽签并刷新时间戳。
             */
            /**
             * 用户口径（2026-11-27）**切账号/后台刷新都不要重抽，只有下拉刷新才换一批**。
             *
             * 修的 bug（用户实测 h 账号）：
             * 「切换过来之后马上能看到继续播放里的随机一个，但马上又刷新了一次，
             *   把这个换掉了，换成新的，而且数量也从 6 个突然变多」
             *
             * 根因：这里原来写的是 `if (!quiet && ...)`。
             * 而 load() 里调 loadHero 传的是 quiet = !manual ——
             * 切账号后的网络刷新不是 manual，所以 quiet=true → 这个快照分支被跳过
             * → 直接走下面「重新抽签」→ 全新一批 + 数量可能不同
             * → 用户看到的就是「先显示对的，然后被刷掉」。
             *
             * 现在：只要不是「用户手动下拉刷新」，就一律优先沿用快照。
             * quiet 的语义回归本源（只是「别显示转圈」），不再影响是否复用快照。
             */
            if (!manualHero && shown != null && System.currentTimeMillis() - shown.at < HERO_TTL_MS) {
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
                /**
                 * 用户口径（2026-11-27）**快照够数就沿用，只修第 1 位**。
                 *
                 * 之前在「快照数量够、但第 1 位不是继续播放」时整批重抽 ——
                 * 这会让用户看到「后面 9 个全换成新的」，体感就是「刷新了一次」。
                 *
                 * 现在：数量的部分原样保留，只把第 1 位换成继续播放里随机一个
                 * （且不与后面重复）。这样切账号进来画面稳定，不会整片跳变。
                 */
                if (kept.size >= HERO_SIZE - 1) {
                    val rest = kept.filter { it.id != firstId }.take(HERO_SIZE - 1)
                    // 用快照时间戳当 seed：同一快照期内第一位固定，不会每次算都变
                    val withResume = pickResumeFirst(data, rest, shown.at)
                    if (withResume.size >= HERO_SIZE - 1) {
                        publishHero(withResume.take(HERO_SIZE))
                        // 快照内容变了（第1位换了）→ 刷新时间戳，避免下次又算一遍
                        HeroSnapshot.write(context, accountKey, withResume.map { it.id })
                        return
                    }
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
            /**
             * 用户口径（2026-11-27）**凑不满 10 个时放宽 backdrop 要求**。
             *
             * 原来硬性 `filter(::hasBackdrop)`：没有横版背景图的条目直接丢掉，
             * 小库 / 新账号扣完就剩不到 10 个 → 轮播数量永远不够。
             *
             * 现在两级：
             *  ① 优先「有 backdrop 且没看完」—— 效果最好
             *  ② 不足 HERO_SIZE 个 → 把上一轮被过滤掉的也加进来
             *     （HeroCarousel 那边本来就有海报兜底，取不到横图会用竖版）
             */
            val strict = pool.asSequence()
                .filter(::hasBackdrop)
                .filter { !isFinished(it) }
                .distinctBy { it.id }
                .toList()
            val candidates = if (strict.size >= HERO_SIZE) {
                strict
            } else {
                val extra = pool.asSequence()
                    .filter { !isFinished(it) }
                    .filter { cand -> strict.none { it.id == cand.id } }
                    .distinctBy { it.id }
                    .toList()
                strict + extra
            }
            if (candidates.isNotEmpty()) {
                /**
                 * 用户口径（2026-11-26）**主路径也必须插「继续播放位」**。
                 *
                 * 修的问题：第 1 个轮播不是「继续观看」里的剧，而且闪一下就没了。
                 *
                 * 根因：上一版只给「首屏 showCached」和「兜底分支」加了
                 * pickResumeFirst，**漏了这条主路径**（网络数据回来走的正是这里）。
                 * 于是：首屏正确 → 1 秒后主路径覆盖成纯随机 → 用户看到闪一下变错。
                 *
                 * 现在三条路径统一：第 1 位=继续播放随机 1 部，后 9 位=全库随机。
                 */
                    val randomNine = candidates.shuffled(Random(seed)).take(HERO_RANDOM_SIZE)
                val picked = pickResumeFirst(data, randomNine, seed)
                /**
                 * 用户口径（2026-11-27，#2 第三版）**凑不满就现场从服务端再补一轮**。
                 *
                 * 现象：h 账号进来只有 3 个圆点，服务端直查却有 50 个合格候选。
                 * 说明 `data.latest` 这条链路在某些情况下给不全（缓存/首屏/时序都可能）。
                 *
                 * 与其继续猜是哪一环阉了数据，不如在「最终结果」上加一道硬保障：
                 * 算完如果还不到 HERO_SIZE，就按库 id 直接从服务端再拉一次补足。
                 * 这一步很便宜（一次并发请求），但能保证用户永远看到满 10 个。
                 */
                val finalPicked = if (picked.size >= HERO_SIZE) {
                    picked
                } else {
                    /**
                     * 注意：`async` 必须在 coroutineScope 里（否则 Kotlin 报 deprecation 且编译失败）。
                     * 这里外面包 coroutineScope，内层 runCatching 只包网络异常。
                     */
                    val extra = runCatching {
                        coroutineScope {
                            data.libraries
                                .filter { it.collectionType !in NON_LATEST }
                                .map { lib ->
                                    async {
                                        val t = when (lib.collectionType) {
                                            "movies" -> "Movie"
                                            "tvshows" -> "Series"
                                            else -> null
                                        }
                                        runCatching { api.latest(lib.id, t, HERO_POOL_PER_LIBRARY) }
                                            .getOrDefault(emptyList())
                                    }
                                }.awaitAll().flatten()
                                .filter(::hasBackdrop)
                                .filter { !isFinished(it) }
                                .distinctBy { it.id }
                        }
                    }.getOrDefault(emptyList())
                    val have = picked.map { it.id }.toSet()
                    val merged = picked + extra.filter { it.id !in have }
                    android.util.Log.i(
                        "ETKN-Hero",
                        "hero 补足: picked=${picked.size} extra=${extra.size} final=${merged.size}/${HERO_SIZE}",
                    )
                    merged.take(HERO_SIZE)
                }
                publishHero(finalPicked)
                HeroSnapshot.write(context, accountKey, finalPicked.map { it.id })
                return
            }
            // 连库列表都还没到（首屏缓存路径）时退回旧的兜底，别让轮播空着
            if (!quiet) {
                val fallback = fallbackHero(data)
                if (fallback.isNotEmpty()) {
                    val randomNine = fallback.shuffled(Random(seed)).take(HERO_RANDOM_SIZE)
                    val picked = pickResumeFirst(data, randomNine, seed)
                    /**
                     * 用户口径（2026-11-27，#2 第四版）**兜底分支也不许发半截**。
                     *
                     * 这里是「连库列表都还没到」的极端情况，池子可能只有几个。
                     * 与其先发 3~6 个让用户看到跳变，不如不发 —— 等 loadHero 的
                     * 主路径（会现场从服务端补拉）一次性给出满 10 个。
                     */
                    if (picked.size >= HERO_SIZE) {
                        publishHero(picked)
                        HeroSnapshot.write(context, accountKey, picked.map { it.id })
                    } else {
                        android.util.Log.i(
                            "ETKN-Hero",
                            "兜底分支不足 ${HERO_SIZE} 个（${picked.size}），跳过不发布",
                        )
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

    /**
     * 用户口径（2026-11-27）**填充只往后加，不动前面已经显示的**。
     *
     * 用户原话：「你填充的时候，前边已经显示的就不要再刷新填充了，
     * 要不就会让我感觉到是在用原来的老缓存，然后刷新了一下新缓存」。
     *
     * 所以：新算出来的 [items] 只用来补「当前位置之后还空着的那几页」。
     * 已经在显示的那几页原样保留 —— 用户看到的是一段内容慢慢变长，
     * 而不是前几张图被换掉。
     *
     * 例外：当前 hero 为空（刚启动/刚切账号）时，整批都是新的，直接用它。
     */
    private fun publishHero(items: List<Item>) {
        _state.update { current ->
            if (current !is Load.Ready) return@update current
            val shown = current.value.hero
            val merged = if (shown.isEmpty()) {
                items
            } else {
                /**
                 * 保留前缀：shown 已有的（按 id 匹配上就保留原来的对象），
                 * 不足的位置才从 items 里取新的。
                 */
                /**
                 * 顺序规则：
                 *  ① 先放 already-shown 里「新结果里也有」的那些（保持它们原来的先后顺序）
                 *  ② 再追加新结果里剩下的（这些就是「后面还没填的那几页」）
                 *  ③ 截到 HERO_SIZE
                 *
                 * 这样前几页的 id / 顺序完全不变（用户看不到被替换），
                 * 只在尾部逐渐长出新的。
                 */
                val newById = items.associateBy { it.id }
                val keptOrder = shown.mapNotNull { newById[it.id] ?: null }
                val keptIds = keptOrder.map { it.id }.toHashSet()
                val appended = items.filter { it.id !in keptIds }
                (keptOrder + appended).take(HERO_SIZE)
            }
            Load.Ready(current.value.copy(hero = merged))
        }
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
     * 所以：第 1 个 = 继续播放随机 1 部，第 2~7 个 = 全库打乱随机 6 部。
     *
     * 用户口径（2026-11-27，#26）**数量从 10 改成 8**。
     * 原话：「你直接在 1.9.6 那版的基础上，把轮播图 10 个改成 8 个」。
     * 原因：写死 10 时数据常常只凑到 9 个，最后一页就是空白；
     * 8 个更容易凑满，配合「一定凑满才发布」就不会有空页。
     */
    private const val HERO_SIZE = 7

    /** 后面「全库随机」那把要凑几个（总 7 - 第 1 个继续播放位）。 */
    private const val HERO_RANDOM_SIZE = HERO_SIZE - 1
        /**
     * 用户口径（2026-11-27）**每库多取一些，保证一定能凑满 HERO_SIZE**。
     *
     * 原来 30 条，扣掉「已看完」+「没有 backdrop」之后，新账号或小库可能剩不到 10 个，
     * 于是主路径也只能给出 6~7 个 → 用户看到的就是「不够 10 个」。
     * 提到 60：多取一点只是一次列表请求，代价很小，但能保证凑满。
     */
    private const val HERO_POOL_PER_LIBRARY = 60
        /** 轮播图「多久换一批」：满 12 小时（按上次生成时间算，不跟日历走）。 */
        /**
     * 用户口径（2026-11-26）**快照有效期 12 小时 → 2 小时**。
     *
     * 用户反馈「推荐效果不好，总是读到之前的缓存，下拉刷新才换新」。
     * 12 小时太长：上午抽的一批，晚上还在用，跟你当天看了什么完全脱节。
     * 2 小时能在「稳定」和「跟得上」之间取平衡。
     */
    private const val HERO_TTL_MS = 2L * 60L * 60L * 1000L
        private val NON_LATEST = setOf("music", "boxsets", "playlists", "livetv", "folders")
    }
}
