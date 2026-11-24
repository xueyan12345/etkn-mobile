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
 * 用户口径（2026-11-23）**切集不再做整块交叉淡化**。
 *
 * 原来是 AnimatedContent(contentKey = item.id)：每换一集，
 * 头部/操作行/简介/章节/演职人员**各自**做一次淡出淡入，
 * 视觉上就是「整页在闪、在重新加载」，而且每个 Swap 都是独立动画，很费。
 *
 * 参考视频里的应用是「就地更新」：只有真正变化的文字/封面换了，版面完全不动。
 * 所以这里直接透传 —— 内容仍会随 data 重组（本来就该变的地方会变），
 * 但没有额外动画、没有整体重建感。
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
                item(key = "header", contentType = "header") { Swap(data) { DetailHeader(it, centered) } }
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
    val chapters = item.chapters.filter { it.name.isNotBlank() }
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
    val parentThumb = item.parentThumbItemId?.let { pid ->
        item.parentThumbTag?.let { tag -> urls.image(pid, "Thumb", 480, tag) }
    }
    val parentBackdrop = item.parentBackdropItemId?.let { pid ->
        item.parentBackdropTags.firstOrNull()?.let { tag -> urls.image(pid, "Backdrop", 480, tag) }
    }
    val seriesPoster = item.seriesId?.let { sid ->
        item.seriesPrimaryTag?.let { tag -> urls.image(sid, "Primary", 480, tag) }
    }
    val thumb: String? = when {
        hasOwnImage -> urls.chapter(item, chapter, 480)
        else -> parentThumb ?: parentBackdrop ?: seriesPoster
    }
    Column(
        Modifier
            .width(160.dp)   // 用户口径（2026-11-24）：和分集卡一起缩到 160dp
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
private fun DetailHeader(data: DetailData, centered: Boolean) {
    val item = data.item
    val urls = images()
    val align = if (centered) Alignment.CenterHorizontally else Alignment.Start
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Phones: a tall poster-like header; tablets: a wide band so the details stay on screen.
        val height = if (maxWidth >= 600.dp) (maxWidth * 0.42f).coerceIn(340.dp, 520.dp) else (maxWidth * 1.08f).coerceIn(360.dp, 560.dp)
        val widthPx = maxWidth.px()
        Box(Modifier.fillMaxWidth().height(height)) {
            Artwork(
                urls.backdrop(item, widthPx) ?: urls.landscape(item, widthPx) ?: urls.poster(item, widthPx),
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
            val heroLogo = urls.logo(item, 720)
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
            // 海报底部：剧名（纯黑）+ 元数据，都在渐隐区上
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = Etkn.space.screen)
                    .padding(bottom = 4.dp),
                horizontalAlignment = align,
            ) {
                /**
                 * 用户口径（2026-11-23）：**图下必须有纯黑粗体剧名**（参考图第②行）。
                 * 单集页这里显示剧名（seriesName），系列页显示本剧名。
                 */
                Text(
                    item.seriesName?.takeIf { item.isEpisode || item.isSeason } ?: item.name,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
                MetaLines(item, techSource(data), align)
            }            // 元数据（类型标签等）仍在海报底部，和播放按钮一起
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Etkn.space.screen).padding(bottom = 4.dp),
                horizontalAlignment = align,
            ) {
                /**
                 * 用户口径（2026-11-23）**这里原来有一行青绿色小字剧名，已删除**。
                 *
                 * 原因：同一屏剧名出现了三次 ——
                 *  ① 海报上的白色艺术字（服务器 TMDB 图，保留）
                 *  ② 上面 InfoColumn 里的纯黑粗体剧名（保留，参考图要求的那一行）
                 *  ③ 这个青绿色小字（多余，删掉）
                 * 参考图里图下只有一行剧名，所以这里直接进入元数据行。
                 */
                MetaLines(item, techSource(data), align)
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
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val ratings = item.ratings
    // 用户口径（2026-11-24）：集号行移出头部后，行距 6dp → 8dp 让信息区更饱满
    Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ratings.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp, align),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                ratings.forEach { RatingScore(it) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            formatRating(item.bestRating)?.takeIf { ratings.isEmpty() }?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Star, null, Modifier.size(16.dp), tint = Etkn.tokens.rating)
                    Text(it, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 2.dp))
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
                Text(dateText, style = MaterialTheme.typography.labelLarge)
            } else {
                item.productionYear?.let { Text(it.toString(), style = MaterialTheme.typography.labelLarge) }
            }
            // 时长：单集显示本集时长，电影显示总时长
            formatRuntime(item.runtimeMs)?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = muted)
            }
            item.officialRating?.let { RatingBadge(it) }
            /**
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
    val color = MaterialTheme.colorScheme.onSurfaceVariant
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
    fun play(resume: Boolean) = target?.let { nav.play(it, resume, sourceId = if (it.id == item.id) source else null) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen).padding(top = 14.dp),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
    ) {
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
         * 用户口径（2026-11-24）**「S1:E8 - 集名」放到这里**：
         * 播放按钮下面、简介上面。
         *
         * 只有单集页才有（item.isEpisode）。样式：中等字重、正常色，
         * 比剧名小、比简介标题大，作为「当前看的是哪一集」的明确标识。
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
    // 已播放
    ArtworkIconButton(
        Icons.Rounded.Done,
        if (item.userData.played) "标记未看" else "标记已看",
        { vm.togglePlayed() },
        onGlass = onGlass,
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
