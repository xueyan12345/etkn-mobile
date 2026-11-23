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
import xyz.etkn.mobile.ui.common.rememberArtworkSeed
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
import xyz.etkn.mobile.ui.theme.ArtworkTheme
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

    // The page fades in when its data arrives rather than replacing a spinner.
    AnimatedContent(state, contentKey = { it::class }, transitionSpec = { contentSwap() }, label = "detail") { s ->
        when (s) {
            Load.Loading -> LoadingState()
            is Load.Failed -> Box(Modifier.fillMaxSize()) {
                ErrorState(s.message, vm::load)
                ArtworkBackButton(nav::back, Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(8.dp))
            }
            is Load.Ready -> DetailContent(s.value, vm, nav)
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
private fun DetailContent(data: DetailData, vm: DetailViewModel, nav: AppNav) {
    val item = data.item
    val backdrop = images().backdrop(item, 64) ?: images().poster(item, 64)
    val seed = rememberArtworkSeed(backdrop)
    ArtworkTheme(seed) {
        val episodes by vm.episodes.state.collectAsStateWithLifecycle()
        val list = rememberLazyListState()
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
                            onAll = { nav.to(EpisodesRoute(seriesId, episodes.seasonId, item.seriesName ?: item.name, seed)) },
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
    // images() 是 @Composable，不能在 runCatching 里调；直接算（内部已做容错）
    val thumb = images().chapter(item, chapter, 480)
    Column(
        Modifier
            .width(180.dp)
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
             * 用户口径（2026-11-23）**剧名大字叠在海报左上**，按参考图样式：
             *  · 距左边缘约 5% 屏宽，中文主体顶部约在屏高 13-14% 位置
             *  · 中文：白色书法/粗体大字，约 40% 屏宽
             *  · 上方：红色小号英文副标（服务端若有英文名/logo 就用，没有就跳过）
             *  · 无底板、无色块，直接压在照片上（靠 detailScrim 保证可读）
             *
             * 原来的做法是剧名放在海报**底部**、和元数据一起（BottomCenter），
             * 参考图是在左上、独立成视觉主标题，所以这次挪上去。
             */
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth(0.86f)
                    .padding(start = Etkn.space.screen)
                    .padding(top = height * 0.20f),
                horizontalAlignment = Alignment.Start,
            ) {
                // 剧名上面一行：红色小号英文/副标（单集页显示剧名，系列页也用它当副标）
                val subTitle = item.seriesName?.takeIf { item.isEpisode || item.isSeason }
                if (subTitle != null) {
                    Text(
                        subTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val logo = if (item.isEpisode) null else urls.logo(item, 720)
                if (logo != null) {
                    // 服务端有剧名 Logo 图：优先用图（最贴参考图的书法艺术字效果）
                    Artwork(
                        logo,
                        Modifier.fillMaxWidth(0.72f).heightIn(max = 108.dp).height(96.dp),
                        item.name,
                        contentScale = ContentScale.Fit,
                        background = Color.Transparent,
                        alignment = Alignment.CenterStart,
                    )
                } else {
                    // 没有 Logo 图：用大号粗体白字 + 投影，模拟参考图的视觉重量
                    Text(
                        if (item.isEpisode) listOfNotNull(item.episodeCode, item.name).joinToString("  ") else item.name,
                        style = (if (item.isEpisode) MaterialTheme.typography.headlineMedium else HeroTitleStyle)
                            .copy(
                                fontSize = if (item.isEpisode) 26.sp else 38.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                shadow = Shadow(Color.Black.copy(alpha = 0.55f), blurRadius = 16f),
                            ),
                        textAlign = TextAlign.Start,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 元数据（类型标签等）仍在海报底部，和播放按钮一起
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = Etkn.space.screen).padding(bottom = 4.dp),
                horizontalAlignment = align,
            ) {
                if (item.isEpisode || item.isSeason) {
                    // 单集/季：底部保留剧名小字，和参考图「Logo 下方再一行常规剧名」一致
                    item.seriesName?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(10.dp))
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
    Column(horizontalAlignment = align, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            item.productionYear?.let { Text(it.toString(), style = MaterialTheme.typography.labelLarge) }
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
