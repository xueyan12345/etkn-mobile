package xyz.etkn.mobile.ui.screens.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.MediaSource
import xyz.etkn.mobile.data.model.resolutionLabel
import xyz.etkn.mobile.data.update.formatBytes
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.actions.LocalItemMenu
import xyz.etkn.mobile.ui.actions.PopupChoice
import xyz.etkn.mobile.ui.actions.rememberItemPress
import xyz.etkn.mobile.ui.actions.rememberPopupAnchor
import xyz.etkn.mobile.ui.common.Load
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.common.WhileStarted
import xyz.etkn.mobile.ui.common.graphViewModel
import xyz.etkn.mobile.ui.common.userMessage
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.ArtworkBackButton
import xyz.etkn.mobile.ui.components.ArtworkIconButton
import xyz.etkn.mobile.ui.components.ErrorState
import xyz.etkn.mobile.ui.components.GlassBackBar
import xyz.etkn.mobile.ui.components.LoadingState
import xyz.etkn.mobile.ui.components.PersonAvatar
import xyz.etkn.mobile.ui.components.PosterCard
import xyz.etkn.mobile.ui.components.RatingScore
import xyz.etkn.mobile.ui.components.SectionHeader
import xyz.etkn.mobile.ui.components.formatClock
import xyz.etkn.mobile.ui.components.formatDuration
import xyz.etkn.mobile.ui.components.formatRating
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.components.images
import xyz.etkn.mobile.ui.components.px
import xyz.etkn.mobile.ui.components.shelf
import xyz.etkn.mobile.ui.nav.AppNav
import xyz.etkn.mobile.ui.nav.DownloadsRoute
import xyz.etkn.mobile.ui.nav.EpisodesRoute
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.nav.PersonRoute
import xyz.etkn.mobile.ui.nav.TopLevel
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.Motion
import xyz.etkn.mobile.ui.theme.HeroTitleStyle
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.PillToggle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog
import xyz.etkn.mobile.ui.actions.ItemPress
import xyz.etkn.mobile.ui.components.PersonCard
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.ui.unit.sp
import xyz.etkn.mobile.data.model.Chapter
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Divider
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Tune
import xyz.etkn.mobile.data.model.MediaStream
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

@Composable
fun DetailScreen(id: String) {
    val vm = graphViewModel(key = id) { DetailViewModel(it, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val nav = LocalNav.current
    val deleted by vm.deleted.collectAsStateWithLifecycle()
    LaunchedEffect(deleted) { if (deleted) nav.back() }
    var resumed by remember { mutableStateOf(false) }
    // Coming back from the player: refresh progress / watched state.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (resumed) vm.load() else resumed = true }
    WhileStarted(vm) { vm.followServer() }

    /**
     * 用户口径（2026-11-23）**页面级滚动状态**：放在 AnimatedContent 之外，
     * 这样切集（data 变了、内容重组）时它不会被重建，滚动位置得以保持。
     */
    val pageList = rememberLazyListState()

    // The page fades in when its data arrives rather than replacing a spinner.
    /**
     * contentKey 用「ready / not-ready」而不是具体类：
     * 切集时 state 始终是 Load.Ready（ViewModel 的 load() 已保证不进 Loading），
     * key 不变 → AnimatedContent 不会换页 → 子树不重建 → 滚动位置保持、无硬切。
     */
    AnimatedContent(
        state,
        contentKey = { it is Load.Ready },
        transitionSpec = { contentSwap() },
        label = "detail",
    ) { s ->
        when (s) {
            Load.Loading -> LoadingState()
            is Load.Failed -> Box(Modifier.fillMaxSize()) {
                ErrorState(s.message, vm::load)
                ArtworkBackButton(nav::back, Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(8.dp))
            }
            is Load.Ready -> DetailContent(s.value, vm, nav, pageList)
        }
    }
}

private fun contentSwap() = fadeIn(tween(Motion.FADE_IN, delayMillis = Motion.FADE_OUT)) togetherWith fadeOut(tween(Motion.FADE_OUT))

/**
 * 用户口径（2026-11-27，#13）**整页不做动画，动画只给选集行**。
 *
 * 我上一版给这里加了 180ms 整页淡入，用户明确要求撤掉：
 * 「你做选中集的动画不是我想要的，你先把这个恢复成上一版的样子」
 * 「我不是要加整个详情界面的动画，我只是要在切集的那个一行，也就是选集的那个板块加移动的那个动画」
 *
 * 所以这里恢复成直接透传（上一版的样子）。
 * 切集时的过渡由 EpisodeShelf 里的选中框平滑移动实现，见 EpisodeShelf。
 */
@Composable
private fun Swap(data: DetailData, content: @Composable (DetailData) -> Unit) {
    content(data)
}

/**
 * The page takes its colours from the artwork ([ArtworkTheme]); on phones the header, meta lines
 * and actions are centred under the logo, on wide windows they stay left-aligned.
 */
@Composable
private fun DetailContent(
    data: DetailData,
    vm: DetailViewModel,
    nav: AppNav,
    /** 页面级滚动状态：切集不重建，保证滚动位置保持（用户口径 2026-11-23）。 */
    hoistedList: androidx.compose.foundation.lazy.LazyListState,
) {
    val item = data.item
    /**
     * 用户口径（2026-11-24）**详情页不再按剧集画面动态取色**（方案A）。
     *
     * 之前：backdrop 变 → rememberArtworkSeed 重新取色 → ArtworkTheme 重建整套
     * Material3 配色（seedColorScheme + tintedSurfaces + etknTokens）
     * → 整棵子树重组重绘。切一集就重算一遍，又慢又生硬。
     *
     * 现在：直接用 App 主题色（设置里的服务器主题色），主题在切集时完全不变，
     * 只有真正变化的内容（主图/标题/简介/选中态）重组 → 这才是丝滑的关键。
     */
    run {
        val episodes by vm.episodes.state.collectAsStateWithLifecycle()
        /**
         * 用户口径（2026-11-23）**滑动位置要跨切集保持**。
         *
         * 原来这里是 rememberLazyListState()，作用域在 DetailContent 内。
         * 每次切集 data 变化 → ArtworkTheme(seed) 的 seed 变化 → 整棵子树重组
         * → LazyListState 被重新创建 → 滚动位置丢失（表现为「突然跳回顶部」）。
         *
         * 现在用 hoistedList 从外层传进来，生命周期跟着页面、不跟着 data。
         */
        val list = hoistedList
        val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 } }
        val haze = rememberHazeState()
        /**
     * 用户口径（2026-11-23）：手机端详情页原来整体居中，平板端左对齐，两边看着不一致。
     * 现在手机也统一成左对齐（和平台端一致）：简介、标签、标题、按钮全部靠左。
     * 这个开关保留，将来要恢复手机居中只需改回 !wide。
     */
    val centered = false
        val seriesId = if (item.isSeries) item.id else item.seriesId
        val press = rememberItemPress(item)
        /**
         * 用户口径（2026-11-23）**切集不许把页面滚回顶部**。
         *
         * 这里原来有：
         *   LaunchedEffect(item.id) { if (list.firstVisibleItemIndex > 0 ...) list.animateScrollToItem(0) }
         * 每换一集强制滚回顶部 —— 用户滚到「章节/演职人员」再点别的集，
         * 整页被拉回初始位置，看着像重新加载了一遍（参考视频里的应用不会这样）。
         *
         * 现在完全删除：滚动位置由 LazyListState 自己保持，
         * 切集只刷新内容，不动视口。
         */

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().glassSource(haze)) {
                item(key = "header", contentType = "header") { Swap(data) { DetailHeader(it, centered, vm) } }
                item(key = "actions", contentType = "actions") { Swap(data) { ActionRow(it, centered, vm, nav, press) } }
                if (item.mediaSources.size > 1) {
                    item(key = "versions") { VersionRow(item.mediaSources, data.selectedSource, vm::selectSource) }
                }
                item(key = "info", contentType = "info") { Swap(data) { InfoBlock(it.item, centered) } }
                if (seriesId != null && (item.isSeries || item.isSeason || item.isEpisode)) {
                    item(key = "episodes", contentType = "episodes") {
                        EpisodeShelf(
                            state = episodes,
                            seasons = if (item.isEpisode) emptyList() else data.seasons,
                            currentId = item.id.takeIf { item.isEpisode },
                            /**
                             * 用户口径（2026-11-26）：把当前集的真实集号传进去，
                             * 让选集分页区间能一次算对（不再依赖列表加载状态而乱跳）。
                             */
                            currentNumber = item.indexNumber,
                            list = vm.episodes,
                            onSeason = { vm.selectSeason(seriesId, it) },
                            // On an episode's page the others replace it in place; elsewhere they open.
                            onOpen = { if (item.isEpisode) vm.show(it.id) else nav.open(it) },
                            // 用户口径（2026-11-24）：不再按画面取色，seed 用默认值 null
                            onAll = { nav.to(EpisodesRoute(seriesId, episodes.seasonId, item.seriesName ?: item.name)) },
                        )
                    }
                }
                // 用户口径（2026-11-23）：先「章节」后「演职人员」，和参考图顺序一致。
                ChaptersSection(item, nav)
                /**
                 * 用户口径（2026-11-27）**季卡区搬到「演职人员」上方**。
                 *
                 * 位置口径（用户原话）：「放在演员的上方、剧集列表的下方」。
                 * 实际顺序：剧集列表 → 章节 → 季卡 → 演职人员
                 *（章节区夹在剧集列表和演职人员之间，所以季卡落在章节之后）。
                 *
                 * 同时把它从「只有剧集层显示」放宽到「单集页也显示」——
                 * 因为剧集层现在会自动跳到具体某一集（见 DetailViewModel.load），
                 * 若不加这条，进到单集页后季卡就没了，用户也就没法换季。
                 */
                /**
                 * 用户口径（2026-11-27，#15）**只有一季也要显示这个区域**。
                 *
                 * 原来条件是 `data.seasons.size > 1` —— 单季剧永远看不到季卡区，
                 * 用户也就没法确认「这部片有几个季、现在在看哪一季」。
                 * 现在只要拿得到季列表（>=1）就显示，单季时就一张卡。
                 */
                if ((item.isSeries || item.isSeason || item.isEpisode) && data.seasons.isNotEmpty()) {
                    item(key = "seasons", contentType = "seasons") {
                        Column(Modifier.padding(top = Etkn.space.section)) {
                            SectionHeader("季", showChevron = false)
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = Etkn.space.screen),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(data.seasons, key = { it.id }) { season ->
                                    SeasonCard(
                                        season = season,
                                        selected = season.id == episodes.seasonId,
                                        onClick = { seriesId?.let { vm.selectSeason(it, season.id) } },
                                    )
                                }
                            }
                        }
                    }
                }
                if (item.people.isNotEmpty()) {
                    item(key = "people") {
                        Column(Modifier.padding(top = Etkn.space.section)) {
                            SectionHeader("演职人员", showChevron = false)
                            LazyRow(contentPadding = PaddingValues(horizontal = Etkn.space.screen), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                // 参考图：竖向圆角矩形大卡，下方姓名 + 「饰 角色名」两行居中
                                items(item.people.take(40), key = { it.id + it.role }) { person ->
                                    PersonCard(person, onClick = { nav.to(PersonRoute(person.id, person.name)) })
                                }
                            }
                        }
                    }
                }
                shelf("collections", "所属合集", data.collections) { PosterCard(it, onClick = { nav.open(it) }) }
                shelf("similar", "相似推荐", data.similar) { PosterCard(it, onClick = { nav.open(it) }) }
                item(key = "bottom") { Spacer(Modifier.height(LocalBottomInset.current + 24.dp)) }
            }
            GlassBackBar(item.seriesName?.takeIf { item.isEpisode || item.isSeason } ?: item.name, collapsed, haze, nav::back) { onGlass ->
                // 用户口径（2026-11-23）：右上角三个图标 —— 下载 / 收藏 / 已播放。
                // 原来的搜索按钮删掉（没用），位置换成下载。
                DetailTopActions(item, null, onGlass, vm)
            }
        }
    }
}

/**
 * 用户口径（2026-11-23）**章节按参考图重做**：
 *
 *  ① 每个章节都有缩略图（宽卡 + 圆角），不再是纯文字
 *  ② 缩略图下方：章节名（居中）+ 时间码（灰色居中）
 *  ③ **点击章节直接从那个时间点播放**（这是关键，之前点了没反应）
 *  ④ 没有缩略图的章节用占位卡（浅色底 + 图标），不留空白
 *
 * 数据来源：服务端 Chapters（片头 / 片头结束 / 片尾 等 marker 也在其中）。
 * 播放跳转复用 nav.play(item, resume=false, startTicks=章节起点)。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.ChaptersSection(
    item: Item,
    nav: AppNav,
) {
    /**
     * 用户口径（2026-11-26）**章节区只显示片头 / 片头结束 / 片尾三类**。
     *
     * 规则：
     *  · 只保留 markerType ∈ {IntroStart, IntroEnd, CreditsStart}
     *  · 正片章节（普通分段）全部过滤掉
     *  · 三类一个都没有 → 整块「章节」不渲染（标题也不出现），电影通常就是这种情况
     *  · 有一部分的显示那一部分（有啥显示啥）
     */
    val chapters = item.chapters.filter { c ->
        c.name.isNotBlank() && c.markerType in SKIP_MARKERS
    }
    if (chapters.isEmpty()) return
    item(key = "chapters") {
        Column(Modifier.padding(top = Etkn.space.section)) {
            SectionHeader("章节", showChevron = false)
            LazyRow(
                contentPadding = PaddingValues(horizontal = Etkn.space.screen),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(chapters.size) { index ->
                    val chapter = chapters[index]
                    ChapterCard(
                        chapter = chapter,
                        item = item,
                        onPlay = {
                            /**
                             * 点击章节 = 从该时间点起播。
                             * 章节起点是 ticks（100ns 单位），播放器要毫秒，所以 /10_000。
                             * 注意不能用 resume=true —— 那是「续播上次进度」，
                             * 会覆盖掉章节起点。
                             */
                            val startMs = (chapter.startTicks / 10_000L).coerceAtLeast(0L)
                            nav.play(item, resume = false, startMs = startMs)
                        },
                    )
                }
            }
        }
    }
}

/**
 * 单个章节卡：缩略图（16:9 宽卡）+ 章节名 + 时间码。
 *
 * 缩略图地址走 ETKN 的 chapter 图接口；服务端没给图时用占位卡
 * （浅色底 + 书本/章节图标），保证一排卡片高度一致、不留空洞。
 */
@Composable
private fun ChapterCard(chapter: Chapter, item: Item, onPlay: () -> Unit) {
    /**
     * 用户口径（2026-11-23）**章节缩略图不能是纯黑**。
     *
     * 现状：images().chapter() 永远返回一个 URL（String 而非 String?），
     * 服务器没生成该章节图时那个地址 404 → 渲染成纯黑方块，很难看。
     * 而且因为永远非空，「没图就显示占位图标」的分支根本走不到。
     *
     * 现在的三级回退：
     *  ① 章节自己真的有图（imagePath 或 imageTag 非空）→ 用章节图
     *  ② 没有 → 回落到这部剧的横版图（landscape → backdrop → poster）
     *  ③ 连横版图都没有 → 占位图标（浅色底 + 书本），绝不出现纯黑
     */
    val urls = images()
    val hasOwnImage = !chapter.imagePath.isNullOrBlank() || !chapter.imageTag.isNullOrBlank()
    /**
     * 用户口径（2026-11-24）**章节没图时用「整部剧」的封面，不是单集图**。
     *
     * 上一版这里调 urls.landscape(item, 480)，item 是【当前这一集】——
     * 而 landscape() 内部第 2 条是 `if (item.isEpisode) item.imageTags["Primary"]`，
     * 于是「片头结束」那张直接取了本集自己的剧照，跟别的章节不是同一张，
     * 屏幕上就出现「三张会议场景 + 一张男主特写」的乱象。
     *
     * 现在只用【父级/系列级】的图，绝不碰 episode 自己的 Primary：
     *  ① 父级横版 Thumb（parentThumbItemId + parentThumbTag）—— 首选，16:9 正好
     *  ② 父级背景图（parentBackdropItemId + parentBackdropTags）
     *  ③ 系列海报（seriesId + seriesPrimaryTag），用 ContentScale.Crop 居中裁
     *  ④ 都没有 → 占位图标
     * 这样同一集内所有章节卡都是同一张系列封面，干净一致。
     */
    val thumb: String? = when {
        hasOwnImage -> urls.chapter(item, chapter, 480)
        // 用户口径（2026-11-24）：只用系列级封面（父级 Thumb → 父级 Backdrop → 系列海报）
        else -> urls.seriesCover(item, 480)
    }
    Column(
        Modifier
            /** 用户口径（#92）：放大到跟演员图一样大，横版 16:9。 */
            .width(Etkn.space.episodeCard)
            .clip(Corner.card)
            .clickable(onClick = onPlay),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(Corner.card)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                Artwork(thumb, Modifier.fillMaxSize(), chapter.name, contentScale = ContentScale.Crop)
            } else {
                // 没有缩略图：占位卡（参考图里「片头结束 / 章节 1」就是这种浅色卡 + 图标）
                Icon(
                    Icons.Rounded.MenuBook,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Text(
            chapter.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Text(
            formatClock(chapter.startTicks / 10_000),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 1.dp),
        )
    }
}

@Composable
private fun DetailHeader(data: DetailData, centered: Boolean, vm: DetailViewModel) {
    val item = data.item
    val urls = images()
    val align = if (centered) Alignment.CenterHorizontally else Alignment.Start
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Phones: a tall poster-like header; tablets: a wide band so the details stay on screen.
        val height = if (maxWidth >= 600.dp) (maxWidth * 0.42f).coerceIn(340.dp, 520.dp) else (maxWidth * 1.08f).coerceIn(360.dp, 560.dp)
        val widthPx = maxWidth.px()
        Box(Modifier.fillMaxWidth().height(height)) {
            /**
             * 用户口径（2026-11-27）**切集时头顶这张大图必须保持不变**。
             *
             * 原来这里是 `backdrop(item) ?: landscape(item) ?: poster(item)`，
             * 而 `backdrop()` 的第一条就是「本集自己的 backdrop」、
             * `landscape()` 的第二条也是「本集自己的 Primary 剧照」，
             * 服务端给每集都生成了剧照 → 每次切集图都变，看着很跳。
             *
             * 现在单集页一律走 `seriesCover()`：
             *  ① 父级横版 Thumb（整部剧的 16:9 图）
             *  ② 父级 Backdrop
             *  ③ 系列海报
             * 全部是「整部剧」层级的图，切集时同一张，不会跳。
             * 电影 / Video（没有 seriesId）仍按原来的三级回退，不受影响。
             */
            /**
             * 用户口径（2026-11-27，#3 第二版）**直接用「剧集」那条数据取图**。
             *
             * 上一版我用父级字段自己拼（seriesCover/seriesLogo），结果还是显示单集图 ——
             * 说明服务端给分集塞了 parentThumb/parentBackdrop 指向分集自己的图，
             * 拿父级字段拼不可靠。
             *
             * 现在按用户指的路子办：**复用已删掉的那个剧集详情页的接口**。
             * 那一版 item 是 Series，数据就是 `api.item(seriesId)`；
             * 这里 `data.seriesItem` 存的就是同一个请求的结果。
             * 对它调 backdrop/landscape/poster，拿到的必然和原来剧集页顶部一模一样。
             */
            /**
             * 用户口径（2026-11-27，#18）**顶部大图按季区分**。
             *
             * 服务端实测（《极限挑战》）：
             * ```
             * Series  Backdrop: 4-2751-backdrop-...   ← 整部剧只有这 1 张
             * 第1季   Primary:  4-2764-primary-...    ← 各季不同
             * 第2季   Primary:  4-2760-primary-...
             * 第3季   Primary:  4-2770-primary-...
             * 第4季   Primary:  4-2752-primary-...
             * 但四季的 BackdropImageTags 全是 4-2751-backdrop-...（共用）
             * ```
             * 所以只看 backdrop 的话，切季头顶这张永远不变。
             *
             * 取值顺序（用户已确认这个方案）：
             *  ① 当前季自己的 Backdrop（季有独立横图时最好）
             *  ② 当前季自己的 Primary 海报（竖版，裁成横幅）—— 各季一定不同
             *  ③ 全剧 Backdrop（兜底，只有一步）
             */
            val currentSeason = data.seasons.firstOrNull { it.id == item.seasonId }
                ?: data.seasons.firstOrNull { it.id == data.target?.seasonId }
            val seasonArt = currentSeason.let { season ->
                if (season == null) null
                else urls.backdrop(season, widthPx) ?: urls.poster(season, widthPx)
            }
            val artFor = data.seriesItem ?: item
            val headerArt = seasonArt
                ?: urls.backdrop(artFor, widthPx)
                ?: urls.landscape(artFor, widthPx)
                ?: urls.poster(artFor, widthPx)
            Artwork(
                headerArt,
                Modifier.fillMaxSize(),
                item.name,
                placeholder = Icons.Rounded.Movie,
            )
            Box(Modifier.fillMaxWidth().height(140.dp).background(Etkn.tokens.heroTopScrim))
            Box(Modifier.fillMaxSize().background(Etkn.tokens.detailScrim))
                        /**
             * 用户口径（2026-11-23）**头部按参考图重做（第一层 + 第二层共用）**。
             *
             * 参考图结构（从上到下）：
             *  ① 海报上叠「服务器自带的 TMDB 艺术字图」（白色带字横版图）
             *     —— 距左约 5% 屏宽、距顶约 12-15% 屏高（之前太靠上、和顶部图标挤在一起）
             *  ② 海报下方信息区：**纯黑粗体剧名**（不是白字压图）
             *  ③ 集/季信息（单集页才有）：「S1:E34 - 集标题」
             *  ④ 评分 / 年份 / 时长 / 类型
             *
             * 我上一版改坏的地方（已纠正）：
             *  · 剧名出现两次、位置混乱
             *  · 集标题被放大到比剧名还大
             *  · 文字直接压在人物上、断行难看
             */
            /**
             * 用户口径（2026-11-27）**艺术字也跟着「整部剧」走**，切集时不许跳。
             * 单集页优先取父级（剧）的 Logo，电影/Video 走原来的 logo()。
             */
            /** 艺术字同理：用剧集那条数据取 Logo，切集不动。 */
            val heroLogo = urls.logo(data.seriesItem ?: item, 720)
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.78f)
                    // 参考图实测：距左约 5% 屏宽、中文主体距顶约 13% 屏高
                    .padding(start = Etkn.space.screen)
                    .padding(top = height * 0.12f),
            ) {
                if (heroLogo != null) {
                    // 服务器有 TMDB 艺术字图 → 直接用它（含白字 + 英文，最贴参考图）
                    Artwork(
                        heroLogo,
                        Modifier.fillMaxWidth().heightIn(max = 120.dp).height(104.dp),
                        item.name,
                        contentScale = ContentScale.Fit,
                        background = Color.Transparent,
                        alignment = Alignment.CenterStart,
                    )
                }
                // 没有艺术字图时不在这里画白字——留给下方信息区的纯黑剧名，
                // 避免出现「白字压图 + 黑字重复」两层剧名（上一版的问题）
            }
            /**
             * 用户口径（2026-11-26）**方案 D：深色渐变托底（不用黑框）**。
             *
             * 演进过程（都是用户否掉的）：
             *  · 第一版：文字直接压海报 + 原有半透明渐变 → 亮色海报（鬼灭）看不清
             *  · 第二版：加一块 0.78 不透明度的深色圆角底板 → 能看清了，但用户嫌「大黑框难看」
             *
             * 现在按用户选定的方案 D 重做：
             *  · **不给边框、不给圆角**，改成一条从底往上渐变的深色遮罩，齐边融入画面
             *  · 渐变用 1.5 次方曲线（越往上越透明），顶部完全过渡到透明，
             *    没有硬边，不会像「贴上去的一条黑带」
             *  · 底部够黑（约 0.92）保证任何亮海报上文字都清楚
             *  · 渐变高度 340dp 折算成海报高度的比例，跟着海报高度走
             */
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(height * 0.30f)
                    .background(Etkn.tokens.detailPlateScrim),
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = Etkn.space.screen)
                    /**
                     * 用户口径（2026-11-26）**剧名位置稍微往下**。
                     * 需求原文：「这个名位置也稍微有点靠上，稍微往下点」
                     * 12dp → 6dp，整块标题往下压一点，不贴底也不悬空。
                     */
                    .padding(bottom = 6.dp),
                horizontalAlignment = align,
            ) {
                /**
                 * 用户口径（2026-11-23）：**图下必须有纯黑粗体剧名**（参考图第②行）。
                 * 单集页这里显示剧名（seriesName），系列页显示本剧名。
                 */
                /**
                 * 用户口径（2026-11-26）**分级等级徽章（OfficialRating）挪到剧名后面**。
                 *
                 * 原来它在元数据区里，排在「日期 / 时长」下面单独一行。
                 * 现在跟剧名同一行：剧名占满左侧，分级小徽章紧跟在剧名右边。
                 * 剧名太长时徽章仍在行末（Row 里剧名 weight(1f) 先占满再让位）。
                 */
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.seriesName?.takeIf { item.isEpisode || item.isSeason } ?: item.name,
                        /**
                         * 用户口径（2026-11-26）**剧名字号加大**。
                         *
                         * 需求原文：「这个详情界面的电影名或者是电视剧名，字体有点小，稍微大点」
                         *
                         * 原来是 MaterialTheme.typography.titleLarge（M3 默认 22sp），
                         * 现在直接写 26sp —— 比默认大 4sp，是「稍微大点」而不是猛加一档。
                         * 不用 typography 是为了不受主题各档位影响，保证这里恒定。
                         */
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                            lineHeight = 32.sp,
                        ),
                        // 渐变托底是深色，文字固定浅色（不跟主题走，否则浅色主题会出现深字压深底）
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    item.officialRating?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.width(8.dp))
                        RatingBadge(it)
                    }
                }
                /**
                 * 用户口径（2026-11-24）：**「S1:E8 - 集名」从头部移走**。
                 *
                 * 原来这行在剧名和元数据之间，把头部撑成三行；
                 * 现在移到「播放按钮」下面、「简介」上面（见下方 episodeLabel 区块）。
                 *
                 * 头部空出来的高度这样补：
                 *  · Spacer 10dp → 16dp
                 *  · MetaLines 内部行距 6dp → 8dp
                 * 视觉上头部仍然饱满，不再出现「少一行显得空」。
                 */
                Spacer(Modifier.height(16.dp))
                /**
                 * 用户口径（2026-11-26）**左右两栏：左边元数据，右边三条技术信息**。
                 *
                 * 原来三条技术信息（视频/音频/字幕）是通栏堆在播放按钮上方，
                 * 右侧一大片留白。现在改成：
                 *   左栏：剧名下方那几行（评分 / 日期 / 时长 / 类型）
                 *   右栏：4K HEVC（+ 小字）、音轨、字幕
                 * 播放按钮仍然通栏在下方。
                 *
                 * 层级：series / movie / episode 显示右栏；season 不显示（右栏留空，左栏照旧）。
                 */
                /**
                 * 用户口径（2026-11-27）**家庭视频库（homevideos）的条目也要显示这三行**。
                 *
                 * 修的 bug：LTX破解 / 推送下载 等库的 `CollectionType = homevideos`，
                 * 里面条目的 `Type` 是 **`Video`**，不是 `Movie`。
                 * 原来这个判据只写了 series / movie / episode 三种，
                 * 于是 `Video` 类型的片子右栏三条技术信息整块不渲染 —— 用户看到的就是
                 * 「不知道为啥不显示媒体信息」。
                 *
                 * 判定改成「只要有可播放的视频版本就显示」，不再枚举类型：
                 *  · Series / Season 取 target（下一集）的版本
                 *  · Movie / Video / Episode / MusicVideo 取自己选中的版本
                 * 源为空（还没刮削出 mediainfo）时下面 techSrc != null 会兜住，不会显示空行。
                 */
                val showTech = item.isPlayable || item.isSeries || item.isSeason
                val techSrc = techSource(data)
                if (showTech && techSrc != null) {
                    /**
                     * 用户口径（2026-11-26）**两栏底部对齐**。
                     *
                     * 问题：左栏（评分/日期时长/类型）和右栏（视频/音频/字幕）
                     * 行数不一样，用 Top 对齐时左边矮一截，类型那行和右边的字幕行错开，
                     * 中间留出一块大空白，看着很散。
                     *
                     * 现在用 **Bottom 对齐**：两栏最底下一行（类型 / 字幕）落在同一条基线上，
                     * 中间的空白被吸收到上方，视觉上两栏是一个整体。
                     */
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Box(Modifier.weight(1f)) {
                            MetaLines(item, techSrc, align)
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) {
                            MediaTechRows(
                                item = item,
                                source = techSrc,
                                sourceCount = item.mediaSources.size,
                                selectedIndex = data.selectedSource,
                                onSelectSource = { vm.selectSource(it) },
                                selectedAudioIndex = data.selectedAudioIndex,
                                selectedSubtitleIndex = data.selectedSubtitleIndex,
                                onSelectAudio = { vm.selectAudio(it) },
                                onSelectSubtitle = { vm.selectSubtitle(it) },
                                compact = true,
                            )
                        }
                    }
                } else {
                    MetaLines(item, techSource(data), align)
                }
            }
        }
    }
}

/** The file the tech line describes: the chosen version, or for a series the episode up next. */
private fun techSource(data: DetailData): MediaSource? = with(data) {
    if (item.isSeries || item.isSeason) target?.mediaSources?.firstOrNull() else item.mediaSources.getOrNull(selectedSource)
}

/** Scores by source; year, rating badge and status; then runtime and file; then genres. */
@Composable
private fun MetaLines(item: Item, source: MediaSource?, align: Alignment.Horizontal) {
    /**
     * 用户口径（2026-11-26）：**渐变托底是深色，文字固定浅色**。
     * 用主题色（onSurface/onSurfaceVariant）在浅色主题下会是深字压深底，看不见。
     */
    val muted = Color.White.copy(alpha = 0.72f)
    val ratings = item.ratings
    // 用户口径（2026-11-24）：集号行移出头部后，行距 6dp → 8dp 让信息区更饱满
    Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (ratings.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp, align),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                ratings.forEach { RatingScore(it, textColor = Color.White) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            formatRating(item.bestRating)?.takeIf { ratings.isEmpty() }?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Star, null, Modifier.size(16.dp), tint = Etkn.tokens.rating)
                    Text(it, style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(start = 2.dp))
                }
            }
            /**
             * 用户口径（2026-11-24）**补「完整上映日期 + 时长」**。
             *
             * 数据源（已实测确认）：
             *  · 日期：media.items.release_date → API 的 PremiereDate
             *    覆盖：movie 100% / series 93% / episode 32%
             *  · 时长：version_analysis → RunTimeTicks
             *    覆盖：全部库都有（含 LTX破解）
             *
             * 取不到就不显示，绝不编造、不显示 0。
             * 有完整日期就显示日期，没有就退回年份。
             */
            val dateText = item.premiereDate?.take(10)?.replace("-", "/")
            if (dateText != null) {
                Text(dateText, style = MaterialTheme.typography.labelLarge, color = Color.White)
            } else {
                item.productionYear?.let { Text(it.toString(), style = MaterialTheme.typography.labelLarge, color = Color.White) }
            }
            // 时长：单集显示本集时长，电影显示总时长
            formatRuntime(item.runtimeMs)?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = muted)
            }
            /**
             * 用户口径（2026-11-26）：分级徽章已挪到剧名那一行（见 DetailHeader），
             * 这里不再重复渲染。
             *
             * 用户口径（2026-11-23）：**「制作中」状态标记删掉**（参考图红圈里要去掉的内容）。
             * 原来这里是 item.statusLabel?.takeIf { item.isSeries }。
             */
        }
        /**
         * 用户口径（2026-11-23）：**视频技术规格整行删掉**
         * （参考图红圈里的「4K HEVC 25FPS DD+ 5.1 3.8 GB」）。
         * 原来这里渲染 techLine(item, source)。
         * 第二层（单集页）另有右侧技术浮卡，那里保留。
         */
        if (item.genres.isNotEmpty()) {
            Text(item.genres.take(4).joinToString(" · "), style = MaterialTheme.typography.labelLarge, color = muted, maxLines = 1)
        }
    }
}

@Composable
private fun RatingBadge(text: String) {
    // 用户口径（2026-11-26）：分级徽章现在画在深色渐变上，固定用浅色
    val color = Color.White.copy(alpha = 0.78f)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.6f), Corner.small).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

private fun techLine(item: Item, source: MediaSource?): List<String> {
    val out = ArrayList<String>(8)
    item.runtimeMs.takeIf { it > 0 && !item.isSeries }?.let { out += formatDuration(it) }
    source ?: return out
    source.video?.let { v ->
        resolutionLabel(v.width, v.height)?.let(out::add)
        when {
            v.videoRangeType.contains("DOVI", true) -> out += "杜比视界"
            v.videoRangeType.contains("HDR10Plus", true) -> out += "HDR10+"
            v.videoRange.equals("HDR", true) || v.videoRangeType.contains("HDR", true) -> out += "HDR"
        }
        if (v.codec.isNotEmpty()) out += v.codec.uppercase()
        if (v.frameRate > 0f) out += "${Math.round(v.frameRate)}FPS"
    }
    source.audio.firstOrNull { it.isDefault }?.let { a ->
        val codec = when (a.codec.lowercase()) {
            "truehd" -> "TrueHD"; "eac3" -> "DD+"; "ac3" -> "DD"; "dts" -> "DTS"; else -> a.codec.uppercase()
        }
        out += if (a.channels > 2) "$codec ${a.channels - 1}.1" else codec
    }
    if (source.size > 0) out += formatBytes(source.size)
    return out
}

@Composable
private fun ActionRow(
    data: DetailData,
    centered: Boolean,
    vm: DetailViewModel,
    nav: AppNav,
    press: ItemPress,
) {
    val item = data.item
    val target = if (item.isSeries || item.isSeason) data.target else item
    val source = item.mediaSources.getOrNull(data.selectedSource)?.id
    val resumable = target != null && target.positionMs > 0 && !target.userData.played
    val label = when {
        target == null -> "暂无可播放内容"
        (item.isSeries || item.isSeason) && target.episodeLabel != null -> (if (resumable) "继续播放" else "播放") + target.episodeLabel
        resumable -> "继续播放"
        else -> "播放"
    }
    fun play(resume: Boolean) = target?.let {
        nav.play(
            it, resume,
            sourceId = if (it.id == item.id) source else null,
            /** 用户口径（2026-11-27，#16）把选中的音轨/字幕带进播放器。 */
            audioIndex = data.selectedAudioIndex,
            subtitleIndex = data.selectedSubtitleIndex,
        )
    }

    /**
     * 用户口径（2026-11-26）**三条信息常驻，并放在播放按钮行的上方**。
     *
     * 层级规则（用户明确）：
     *  · series（剧集详情页）→ 显示
     *  · movie（电影详情页）→ 显示
     *  · episode（单集页）→ 显示
     *  · season（季详情页）→ **不显示**
     *
     * 原来这三条藏在「影片信息」胶囊后面、点开才浮出来 —— 用户要求删掉那个入口，
     * 三条直接铺在播放按钮上面（「从头播放」那一行的上方）。
     *
     * 数据取哪一份：
     *  · series / season 层要播的是 target（下一集），技术参数应取 target 的版本
     *  · movie / episode 层就是自己
     *  所以统一用 techSource(data)（它已经按「选中的版本或下一集」解析好了）。
     */
    // 用户口径（2026-11-26）：三条技术信息改到元数据区右侧渲染，这里不再需要这些变量。

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen).padding(top = 14.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        /**
         * 用户口径（2026-11-26）：三条技术信息已移到元数据区右侧（见 DetailHeader），
         * 这里不再通栏渲染。只保留播放按钮那一行。
         */

        // 用户口径（2026-11-23）：三个点（更多）移到「继续播放」大按钮右边。
        // 收藏/已播放/下载已移到右上角，这里不再重复。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PlayButton(
                    label = label,
                    time = target?.positionMs?.takeIf { resumable }?.let(::formatClock),
                    progress = if (resumable) target.progress else 0f,
                    enabled = target != null,
                    onClick = { play(resume = true) },
                )
            }
            // 用户口径（2026-11-23）：「从头播放」挪到「继续播放」和三点之间，同一行排开：
            // 继续播放 | 从头播放 | ⋮
            if (resumable) {
                Box(Modifier.padding(start = 8.dp)) {
                    FilledTonalButton(
                        onClick = { play(resume = false) },
                        modifier = Modifier.height(SmallAction),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Rounded.Replay, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("从头播放", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (press.enabled) {
                Box(Modifier.padding(start = 8.dp)) {
                    FilledTonalIconButton(onClick = press::open, modifier = Modifier.size(SmallAction).then(press.modifier)) {
                        Icon(Icons.Rounded.MoreVert, "更多", Modifier.size(20.dp))
                    }
                }
            }
        }

        /**
         * 用户口径（2026-11-24）**「S1:E8 - 集名」放到这里**：播放按钮下方、简介上方。
         * 只有单集页才有。
         */
        if (item.isEpisode) {
            val episodeLine = listOfNotNull(item.episodeCode, item.name).joinToString(" - ")
            if (episodeLine.isNotBlank()) {
                Text(
                    episodeLine,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                )
            }
        }
        if (item.trailers.isNotEmpty()) {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TrailerButton(item.trailers)
            }
        }
    }
}

private val SmallAction = 40.dp

/**
 * 用户口径（2026-11-23）：详情页右上角三个图标 —— 下载 / 收藏 / 已播放。
 * 下载对电影/单集直接入队；剧集弹选集面板（支持多选、全选）。
 * 投屏不在这里，放在「继续播放」右边的三点菜单里。
 */
@Composable
private fun DetailTopActions(
    item: Item,
    sourceId: String?,
    onGlass: Boolean,
    vm: DetailViewModel,
) {
    val graph = LocalContext.current.graph
    val nav = LocalNav.current
    val menu = LocalItemMenu.current
    val scope = rememberCoroutineScope()
    val session = graph.sessions.current
    // 剧集下载用的选集面板开关
    var showPicker by remember { mutableStateOf<Item?>(null) }

    // 收藏
    ArtworkIconButton(
        if (item.userData.isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
        if (item.userData.isFavorite) "取消收藏" else "收藏",
        { vm.toggleFavorite() },
        onGlass = onGlass,
    )
    /**
     * 用户口径（2026-11-26）**修「对号点了看不出状态」**。
     *
     * 之前不管已播放还是未播放，用的都是同一个 `Icons.Rounded.Done`，
     * 状态切换后图标完全一样 —— 用户分不清现在是哪个状态。
     *
     * 现在照收藏那个心形的做法：
     *  · 未播放 → 空心对号（DoneOutline / 描边）
     *  · 已播放 → 实心对号 + 高亮圆底（主题色）
     * 一眼就能看出当前是哪个状态。
     */
    ArtworkIconButton(
        if (item.userData.played) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
        if (item.userData.played) "标记未看" else "标记已看",
        { vm.togglePlayed() },
        onGlass = onGlass,
        highlighted = item.userData.played,
    )
    // 下载（私密账号不给）
    if (session != null && !session.privateAccount) {
        val entries by graph.downloads.entries.collectAsStateWithLifecycle()
        val saved = remember(entries, item.id) { graph.downloads.find(session, item.id) != null }
        ArtworkIconButton(
            if (saved) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
            if (saved) "已下载" else "下载",
            onClick = {
                when {
                    saved -> nav.to(DownloadsRoute)
                    // 剧集整季下载：弹选集面板（多选/全选），见 DownloadPicker
                    item.isSeries -> showPicker = item
                    else -> scope.launch {
                        menu?.notice = runCatching { graph.downloads.enqueue(session, item, sourceId) }
                            .fold({ "已加入下载" }, { it.userMessage() })
                    }
                }
            },
            onGlass = onGlass,
        )
    }

    showPicker?.let { series ->
        DownloadPicker(
            series = series,
            onDismiss = { showPicker = null },
        )
    }
}

/**
 * 用户口径（2026-11-23）：剧集下载要能选集 —— 多选 + 全选。
 * 列出该剧全部集，勾选后逐个入队下载。
 */
@Composable
private fun DownloadPicker(series: Item, onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    val menu = LocalItemMenu.current
    val scope = rememberCoroutineScope()
    val session = graph.sessions.current ?: return
    var episodes by remember { mutableStateOf<List<Item>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(series.id) {
        val seasons = runCatching { graph.api.seasons(series.id) }.getOrDefault(emptyList())
        val all = ArrayList<Item>()
        for (season in seasons) {
            val page = runCatching { graph.api.episodePage(series.id, season.id, limit = 500) }.getOrNull()
            if (page != null) all += page.items
        }
        episodes = all
        loading = false
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择下载集数") },
        text = {
            if (loading) {
                Text("加载中…")
            } else {
                androidx.compose.foundation.layout.Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        androidx.compose.material3.TextButton(onClick = {
                            selected = if (selected.size == episodes.size) emptySet() else episodes.map { it.id }.toSet()
                        }) { Text(if (selected.size == episodes.size) "取消全选" else "全选") }
                        Spacer(Modifier.weight(1f))
                        Text("已选 ${selected.size} / ${episodes.size}", style = MaterialTheme.typography.labelMedium)
                    }
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.height(320.dp)) {
                        items(episodes.size) { index ->
                            val ep = episodes[index]
                            val on = ep.id in selected
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { selected = if (on) selected - ep.id else selected + ep.id }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Checkbox(checked = on, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(ep.episodeLabel ?: ep.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = selected.isNotEmpty() && !working,
                onClick = {
                    working = true
                    scope.launch {
                        var ok = 0
                        var fail = 0
                        for (ep in episodes.filter { it.id in selected }) {
                            runCatching { graph.downloads.enqueue(session, ep) }
                                .onSuccess { ok++ }.onFailure { fail++ }
                        }
                        menu?.notice = if (fail == 0) "已加入下载：$ok 集" else "成功 $ok 集，失败 $fail 集"
                        onDismiss()
                    }
                },
            ) { Text(if (working) "提交中…" else "下载${if (selected.isNotEmpty()) " (${selected.size})" else ""}") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** Queues this title for offline viewing, or opens 下载 when it is already there. Not for private accounts. */
@Composable
private fun DownloadButton(item: Item, sourceId: String?) {
    val graph = LocalContext.current.graph
    val nav = LocalNav.current
    val menu = LocalItemMenu.current
    val scope = rememberCoroutineScope()
    val session = graph.sessions.current ?: return
    if (session.privateAccount) return
    val entries by graph.downloads.entries.collectAsStateWithLifecycle()
    val saved = remember(entries, item.id) { graph.downloads.find(session, item.id) != null }
    FilledTonalIconButton(
        onClick = {
            if (saved) nav.to(DownloadsRoute)
            else scope.launch {
                menu?.notice = runCatching { graph.downloads.enqueue(session, item, sourceId) }.fold({ "已加入下载" }, { it.userMessage() })
            }
        },
        modifier = Modifier.size(SmallAction),
    ) { Icon(if (saved) Icons.Rounded.DownloadDone else Icons.Rounded.Download, if (saved) "已下载" else "下载", Modifier.size(20.dp)) }
}

/** Opens the trailer in the app that handles its link (YouTube, a browser); several → pick one. */
@Composable
private fun TrailerButton(trailers: List<String>) {
    val uri = LocalUriHandler.current
    val anchor = rememberPopupAnchor()
    fun open(url: String) = runCatching { uri.openUri(url) }
    FilledTonalIconButton(
        onClick = {
            if (trailers.size == 1) open(trailers.first())
            else anchor.open(trailers.mapIndexed { index, url -> PopupChoice("预告片 ${index + 1}", Icons.Rounded.Theaters) { open(url) } })
        },
        modifier = Modifier.size(SmallAction).then(anchor.modifier),
    ) { Icon(Icons.Rounded.Theaters, "预告片", Modifier.size(20.dp)) }
}

/**
 * Full-width play button whose fill is the watch progress: the watched part in the primary
 * colour, the rest a step lighter, with the resume time after the label.
 */
@Composable
private fun PlayButton(label: String, time: String?, progress: Float, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val watched = scheme.primary
    val rest = lerp(scheme.primary, scheme.surface, 0.3f)
    val height = ButtonDefaults.MediumContainerHeight
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = scheme.onPrimary, disabledContainerColor = Color.Transparent),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = Modifier.fillMaxWidth().height(height).clip(ButtonDefaults.shape).drawBehind {
            if (!enabled) return@drawBehind drawRect(scheme.onSurface.copy(alpha = 0.12f))
            drawRect(if (progress > 0f) rest else watched)
            if (progress > 0f) drawRect(watched, size = Size(size.width * progress, size.height))
        },
    ) {
        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(ButtonDefaults.iconSizeFor(height)))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        time?.let {
            Text(
                it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(start = 10.dp), color = scheme.onPrimary.copy(alpha = 0.78f),
            )
        }
    }
}

@Composable
private fun VersionRow(sources: List<MediaSource>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Etkn.space.screen),
        horizontalArrangement = Arrangement.spacedBy(PillGap),
        modifier = Modifier.padding(top = 14.dp),
    ) {
        items(sources.size) { index ->
            PillToggle(checked = index == selected, onClick = { onSelect(index) }) {
                Text(sources[index].slotLabel.ifEmpty { "版本 ${index + 1}" }, maxLines = 1)
            }
        }
    }
}

/** Tagline and overview (tap to expand). */
@Composable
private fun InfoBlock(item: Item, centered: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen).padding(top = 18.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        item.taglines.firstOrNull()?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, textAlign = align, modifier = Modifier.padding(bottom = 6.dp))
        }
        item.overview?.let { overview ->
            Text(
                overview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                // 用户口径（2026-11-23）：这里原来有 .clip(Corner.card)，把 16dp 圆角直接裁在文字上，
                // 满宽文字的四个角就被圆角切掉了（「2」的左半边看着像被吃掉一块）。
                // 简介是纯文字、本来就没有底色，不需要裁切，去掉。
                modifier = Modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded },
            )
        }
    }
}

/**
 * 用户口径（2026-11-24）：时长显示格式。
 *  · ≥ 1 小时 → 「1小时59分」
 *  · < 1 小时 → 「45分25秒」
 *  · 0 或异常 → null（不显示，不编造）
 */
internal fun formatRuntime(ms: Long): String? {
    if (ms <= 0) return null
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "${h}小时${m}分" else "${m}分${s}秒"
}

/**
 * 用户口径（2026-11-24）**季卡片（照参考图）**。
 *
 * 构图：
 *  · 竖版海报（宽 104dp，比例 0.78 —— 和演职人员卡的尺寸/比例一致）
 *  · 海报右上角：集数角标（深色半透明圆角小块）
 *  · 海报右下角：评分（金色星 + 数值）
 *  · 海报下方：一行季名，居中
 *  · 选中态：海报描一圈主题色边框
 */
@Composable
private fun SeasonCard(
    season: Item,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val urls = images()
    /** 用户口径（#92）季图放大到跟演职人员一样大（104 → 132）。 */
    /** 用户口径（#100）季卡 113dp（参考图量得 165px → 113dp）。 */
    val width = Etkn.space.seasonCard
    val count = (season.recursiveItemCount ?: 0).takeIf { it > 0 } ?: (season.childCount ?: 0)
    val rating = season.communityRating
    Column(
        Modifier.width(width).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(width)
                .aspectRatio(0.78f)
                .clip(Corner.card)
                .then(
                    if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, Corner.card)
                    else Modifier,
                ),
        ) {
            Artwork(
                urls.poster(season, width.px()),
                Modifier.fillMaxSize(),
                season.name,
                placeholder = Icons.Rounded.Tv,
                contentScale = ContentScale.Crop,
            )
            // 右上角：集数
            if (count > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            // 右下角：评分
            if (rating != null && rating > 0) {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Star, null, Modifier.size(10.dp), tint = Etkn.tokens.rating)
                    Text(
                        String.format(java.util.Locale.US, "%.1f", rating),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                    )
                }
            }
        }
        Text(
            season.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/**
 * 用户口径（2026-11-26）**详情页常驻的三条信息：视频 / 音频 / 字幕**。
 *
 * 位置：播放按钮行的上方（「从头播放」那一行的上面）
 * 层级：series（剧集详情页）/ movie（电影详情页）/ episode（单集页）显示；season 不显示
 *
 * 视觉（照参考图）：
 *  · 每行「图标 + 主文字」，下面可带一行小字
 *  · 视频行：主「4K HEVC」，副「4K / HEVC / 3.2 GB / 4 Mbps」
 *  · 音频行：「国语 DDP stereo (默认)」
 *  · 字幕行：「无字幕」或字幕名
 *  · 该项有多条可选时右侧带箭头，点一下就地展开列表；只有一条时不给箭头
 *  · 展开的列表：选中项左侧勾选，点其它项切换
 *
 * 注意「(默认)」：服务端 DisplayTitle 本身已经带「(默认)」，
 * 客户端不能再拼一次（之前显示成「国语 DDP stereo (默认) (默认)」）。
 */
@Composable
private fun MediaTechRows(
    item: Item,
    source: MediaSource?,
    sourceCount: Int,
    selectedIndex: Int,
    onSelectSource: (Int) -> Unit,
    /**
     * 用户口径（2026-11-27，#16）**音轨/字幕选择要真的生效**。
     *
     * 原来是 `onSelected: () -> Unit` —— 详情页传的是 `{ }` 空函数，
     * 点了只是收起菜单，什么都不会发生。
     * 现在拆成两个带索引的回调，传给 ViewModel 存起来，播放时优先使用。
     */
    selectedAudioIndex: Int? = null,
    selectedSubtitleIndex: Int? = null,
    onSelectAudio: (Int) -> Unit = {},
    onSelectSubtitle: (Int?) -> Unit = {},
    /** 右栏紧凑样式：字号更小、行距更紧、图标更小。 */
    compact: Boolean = false,
) {
    /**
     * 用户口径（2026-11-26）：信息区已有深色渐变托底，文字固定浅色，
     * 不跟主题走（浅色主题下 onSurface 会是深字压深底）。
     */
    val onPanel = Color.White
    val muted = Color.White.copy(alpha = 0.72f)
    var expanded by remember { mutableStateOf(0) }   // 0 无 / 1 视频 / 2 音频 / 3 字幕

    /**
     * 用户口径（2026-11-27，#105）**这三行必须钉死，展开的选项要浮起来盖住下面**。
     *
     * 用户原话：
     * ```
     * 「我是说这三行位置就不要动，固定在这了，就算我点开选项，……
     *   点开的话，这3个也不要动，在这个第二个点开的基础上弹出一个小对话框」
     * 「现在的样子是我点第二行，它整个三行的位置就跳起来了」
     * 「上面的三行信息也没固定住」
     * ```
     *
     * 根因：这里原来是
     * ```
     * if (expanded == 2) {
     *     audios.forEach { MediaTechOption(...) }   // 直接插进同一个 Column
     * }
     * ```
     * 选项插进流式布局 → Column 变高 → 下面三行被往下推，
     * 而整个信息块又贴在海报底部对齐 → 看起来就是「三行往上跳」。
     *
     * 改法：选项**不插进 Column**，改用 `Popup`（独立窗口）浮在这一行下面。
     * 另外记录每一行在屏幕上的 y，Popup 才能精确锚在那一行下沿。
     *
     * 注意这是详情页（DetailScreen）的另一套实现，
     * 播放器里的 `AvSettingsPanel` 是独立的另一处，两边都要修。
     */
    var rowYs by remember { mutableStateOf(mapOf<Int, Float>()) }
    var rowHeightPx by remember { mutableStateOf(0f) }
    var blockX by remember { mutableStateOf(0f) }
    var blockW by remember { mutableStateOf(0) }

    if (source == null) return

    val video = source.video
    val resLabel = video?.let { resolutionLabel(it.width, it.height) }
    val codecLabel = video?.codec?.takeIf { it.isNotEmpty() }?.uppercase()
    val videoMain = listOfNotNull(resLabel, codecLabel).joinToString(" ").ifBlank { "视频" }
    /**
     * 用户口径（2026-11-27，#16）**副标题不要重复主标题的内容**。
     *
     * 用户原话：「第一行视频信息的好像标题是 4k HEVC，下面又重复了一次 4k/HEVC，
     * 下面的这个 4k/HEVC 可以去掉吗，因为跟上面显示的重复了，
     * 这样就能把后面的信息都漏出来」。
     *
     * 原来这里把 resLabel 和 codecLabel 又加了一遍（主标题已经有了），
     * 手机窄屏时前半截全是重复内容，把大小/码率挤出去了。
     * 现在副标题只放主标题里没有的信息：文件大小、码率。
     */
    val videoSub = buildList {
        source.size.takeIf { it > 0 }?.let { add(formatBytes(it)) }
        source.bitrate.takeIf { it > 0 }?.let { add("${it / 1_000_000} Mbps") }
    }.joinToString(" / ")

    val audios = source.audio
    val subs = source.subtitles
    val currentAudio = audios.firstOrNull { it.isDefault } ?: audios.firstOrNull()
    val currentSub = subs.firstOrNull { it.isDefault }

    Column(Modifier.fillMaxWidth()) {
        // ① 视频
        MediaTechRow(
            icon = Icons.Rounded.Movie,
            main = videoMain,
            sub = videoSub.takeIf { it.isNotBlank() },
            onPanel = onPanel,
            muted = muted,
            expandable = sourceCount > 1,
            expanded = expanded == 1,
            onClick = if (sourceCount > 1) { { expanded = if (expanded == 1) 0 else 1 } } else null,
            compact = compact,
            onPositioned = { y, x, w -> rowYs = rowYs + (1 to y); blockX = x; blockW = w },
        )
        /** 视频选项：浮层（不再插进 Column，避免把下面几行推下去）。 */
        if (expanded == 1) {
            MediaTechPopup(
                anchorY = rowYs[1] ?: 0f,
                anchorX = blockX,
                width = blockW,
                onDismiss = { expanded = 0 },
            ) {
                item.mediaSources.forEachIndexed { i, src ->
                    MediaTechOption(
                        label = src.name.ifBlank { src.slotLabel },
                        selected = i == selectedIndex,
                        onPanel = onPanel,
                        onClick = { onSelectSource(i); expanded = 0 },
                    )
                }
            }
        }
        Divider(color = onPanel.copy(alpha = 0.10f))

        // ② 音频
        /**
         * 用户口径（2026-11-27，#20）**这一行显示的文字也要跟着选中走**。
         *
         * 用户实测：「选完之后，三行信息本来的那个显示他不是按照选中的那个走的，
         *            那个还是依旧原来的那个显示，虽然对号选中的是别的，
         *            但是显示的不是对号上面的那个」。
         *
         * 根因：下面选项列表的勾选读的是 `selectedAudioIndex`（用户选择），
         * 而这里显示的文字读的是 `currentAudio`（服务端默认）—— 两边数据源不一致。
         * 现在统一：优先用户选的，没选过才用服务端默认。
         */
        val shownAudio = audios.firstOrNull { it.index == selectedAudioIndex } ?: currentAudio
        MediaTechRow(
            icon = Icons.Rounded.VolumeUp,
            main = shownAudio?.cleanTitle() ?: "无音轨",
            sub = null,
            onPanel = onPanel,
            muted = muted,
            expandable = audios.size > 1,
            expanded = expanded == 2,
            onClick = if (audios.size > 1) { { expanded = if (expanded == 2) 0 else 2 } } else null,
            compact = compact,
            onPositioned = { y, x, w -> rowYs = rowYs + (2 to y); blockX = x; blockW = w },
        )
        if (expanded == 2) {
            MediaTechPopup(
                anchorY = rowYs[2] ?: 0f,
                anchorX = blockX,
                width = blockW,
                onDismiss = { expanded = 0 },
            ) {
                audios.forEach { a ->
                    MediaTechOption(
                        label = a.cleanTitle(),
                        selected = (selectedAudioIndex ?: currentAudio?.index) == a.index,
                        onPanel = onPanel,
                        onClick = { expanded = 0; onSelectAudio(a.index) },
                    )
                }
            }
        }
        Divider(color = onPanel.copy(alpha = 0.10f))

        // ③ 字幕
        /** 同 #20：字幕行显示也要跟着用户选择走（-1 = 明确关闭）。 */
        val shownSub = when {
            selectedSubtitleIndex == -1 -> null
            selectedSubtitleIndex != null -> subs.firstOrNull { it.index == selectedSubtitleIndex }
            else -> currentSub
        }
        MediaTechRow(
            icon = Icons.Rounded.Subtitles,
            main = when {
                subs.isEmpty() -> "无字幕"
                shownSub != null -> shownSub.cleanTitle()
                else -> "关闭"
            },
            sub = null,
            onPanel = onPanel,
            muted = muted,
            expandable = subs.isNotEmpty(),
            expanded = expanded == 3,
            onClick = if (subs.isNotEmpty()) { { expanded = if (expanded == 3) 0 else 3 } } else null,
            compact = compact,
            onPositioned = { y, x, w -> rowYs = rowYs + (3 to y); blockX = x; blockW = w },
        )
        if (expanded == 3) {
            MediaTechPopup(
                anchorY = rowYs[3] ?: 0f,
                anchorX = blockX,
                width = blockW,
                onDismiss = { expanded = 0 },
            ) {
                /** -1 = 明确关闭字幕（和「没选过」的 null 区分开）。 */
                MediaTechOption(
                    label = "关闭",
                    selected = selectedSubtitleIndex == -1 || (selectedSubtitleIndex == null && currentSub == null),
                    onPanel = onPanel,
                    onClick = { expanded = 0; onSelectSubtitle(-1) },
                )
                subs.forEach { sub ->
                    MediaTechOption(
                        label = sub.cleanTitle(),
                        selected = (selectedSubtitleIndex ?: currentSub?.index) == sub.index,
                        onPanel = onPanel,
                        onClick = { expanded = 0; onSelectSubtitle(sub.index) },
                    )
                }
            }
        }
    }
}

/**
 * 用户口径（2026-11-26）**修「(默认)」重复**。
 *
 * 服务端 `DisplayTitle` 本身就带后缀（实测 `国语 DDP stereo (默认)`），
 * 之前代码又拼了一次 → 「国语 DDP stereo (默认) (默认)」。
 * 这里统一走这个函数：已经有「(默认)」就不再补。
 */
internal fun MediaStream.cleanTitle(): String {
    val base = displayTitle.ifBlank { codec.uppercase() }.trim()
    if (base.contains("(默认)") || base.contains("（默认）")) return base
    return if (isDefault) "$base (默认)" else base
}

/**
 * 用户口径（2026-11-27，#105）**选项浮层：独立 Popup 窗口，不参与父布局测量**。
 *
 * 为什么不直接在 Column 里叠一层：
 * ```
 * if (expanded == 2) { audios.forEach { MediaTechOption(...) } }
 * ```
 * `MediaTechOption` 是真实布局节点 —— 插进去 Column 就变高，下面的行被推下去，
 * 而整个信息块又贴海报底部 → 视觉上就是「三行往上跳」。
 *
 * `Modifier.offset` 也不行：它只改变**绘制位置**，不改变**测量尺寸**，
 * 父容器照样按放大后的高度测量。
 *
 * 只有 `Popup`（独立 Window）能既脱离测量、又能覆盖在下层之上。
 *
 * @param anchorY 被点那一行的**下沿**在窗口中的 y
 * @param anchorX 信息块的左边缘 x（让浮层左右对齐）
 * @param width   信息块宽度
 */
@Composable
private fun MediaTechPopup(
    anchorY: Float,
    anchorX: Float,
    width: Int,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(anchorX.toInt(), anchorY.toInt()),
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(
            focusable = true,
            dismissOnClickOutside = true,
        ),
    ) {
        Column(
            Modifier
                .width(with(density) { width.toDp() })
                .clip(RoundedCornerShape(12.dp))
                /**
                 * 浮层必须有不透明底，否则下面几行的文字会透上来
                 * （这个页面是压在海报上的半透明信息区）。
                 */
                .background(Color(0xF21A1A1E))
                .padding(vertical = 4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun MediaTechRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    main: String,
    sub: String?,
    onPanel: Color,
    muted: Color,
    expandable: Boolean,
    expanded: Boolean,
    onClick: (() -> Unit)?,
    compact: Boolean = false,
    /** 记录这一行在窗口里的 y，Popup 要用它锚位置（#105）。 */
    onPositioned: ((Float, Float, Int) -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            /**
             * 把「这一行的下沿」和「区块的 x / 宽」报给调用方，
             * 让选项浮层能精确贴在这一行下面、跟区块左右对齐。
             */
            .then(
                if (onPositioned == null) Modifier
                else Modifier.onGloballyPositioned { c ->
                    val pos = c.positionInRoot()
                    onPositioned(pos.y + c.size.height, pos.x, c.size.width)
                }
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = if (compact) 4.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(if (compact) 15.dp else 18.dp), tint = muted)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                main,
                style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = onPanel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub != null) {
                Text(
                    sub,
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
        if (expandable) {
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                null,
                Modifier.size(16.dp),
                tint = muted,
            )
        }
    }
}

@Composable
private fun MediaTechOption(
    label: String,
    selected: Boolean,
    onPanel: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 40.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) onPanel else onPanel.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = onPanel)
    }
}

/**
 * 用户口径（2026-11-26）：章节区只显示这三类标记章节。
 *
 * 服务器把这些标记也放在 Chapters 里（markerType 字段），
 * 正片分段没有 markerType 或为其它值，一律过滤。
 */
private val SKIP_MARKERS = setOf("IntroStart", "IntroEnd", "CreditsStart")
