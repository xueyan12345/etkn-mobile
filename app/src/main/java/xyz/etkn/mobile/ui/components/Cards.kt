package xyz.etkn.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.model.PersonRef
import xyz.etkn.mobile.ui.actions.rememberItemPress
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.theme.Etkn
import java.util.Locale
import xyz.etkn.mobile.ui.theme.Corner
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import xyz.etkn.mobile.ui.common.responsiveCardWidth
import androidx.compose.foundation.layout.wrapContentSize

val CardShape = Corner.card

fun formatRating(value: Double?): String? = value?.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1f", it) }

/** 2:3 poster with title + year below; watched tick and rating badge on the artwork. */
@Composable
fun PosterCard(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = if (LocalWindowLayout.current.wide) Etkn.space.posterWidthWide else Etkn.space.posterWidth,
    showMeta: Boolean = true,
) {
    val widthPx = (width ?: 160.dp).px()
    val url = images().poster(item, widthPx)
    val press = rememberItemPress(item)
    Column(
        modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .then(press.modifier)
            .clip(CardShape)
            .combinedClickable(onClick = onClick, onLongClick = if (press.enabled) press::open else null),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(CardShape)) {
            Artwork(url, Modifier.fillMaxSize(), item.name, placeholder = Icons.Rounded.Movie)
            PosterBadges(item)
            if (item.progress > 0f && !item.userData.played) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp).height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Etkn.tokens.onImageTrack,
                    drawStopIndicator = {},
                )
            }
        }
        if (showMeta) {
            // 用户口径（2026-11-23）：片名和年份都居中（参考图就是这样）
            Text(
                item.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp),
            )
            val meta = listOfNotNull(item.productionYear?.toString(), item.episodeCode).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    // 用户口径（2026-11-22）：原来没有底部留白，年份行下缘贴在容器底边被裁掉一截，
                    // 现在补 bottom padding，四位数完整可见。
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 1.dp, bottom = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PosterBadges(item: Item) {
    val rating = formatRating(item.bestRating)
    if (rating != null) {
        Row(
            Modifier.align(Alignment.TopStart).padding(6.dp)
                .background(Color.Black.copy(alpha = 0.55f), Corner.small)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Star, null, Modifier.size(12.dp), tint = Etkn.tokens.rating)
            Spacer(Modifier.width(2.dp))
            Text(rating, style = MaterialTheme.typography.labelSmall, color = Color.White)
        }
    }
    val unplayed = item.userData.unplayedItemCount ?: 0
    when {
        item.userData.played -> Icon(
            Icons.Rounded.CheckCircle, "已观看",
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            tint = MaterialTheme.colorScheme.primary,
        )
        item.isSeries && unplayed > 0 -> EpisodesRing(item)
    }
}

/**
 * 用户口径（2026-11-23，照参考图最终版）剧集角标：
 * · 底色 = 浅蓝灰实心圆（参考图就是这个色，不是纯白）
 * · 进度 = 圆内部按「已看集数/总集数」填出一块更亮的扇形（饼图式，从 12 点顺时针）
 * · 数字 = 深蓝居中（未看集数）
 *
 * 上一版做的是「外圈描边圆环」，参考图确认不是那样，改成圆内扇形填充。
 * watchedFraction 走 Item.watchedFraction（服务端 PlayedPercentage 对 Series 恒为 null，
 * 用 (总集数-未看集数)/总集数 算）。
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.EpisodesRing(item: Item) {
    val unplayed = item.userData.unplayedItemCount ?: 0
    val fraction = item.watchedFraction
    Box(
        Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f
            val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            // 用户口径（2026-11-23）：颜色对调 —— 白底 = 没播放的部分；
            // 灰色扇形 = 已播放的部分。
            drawCircle(color = Color.White, radius = radius, center = center)
            // 进度：内部更亮的扇形，从 12 点顺时针
            if (fraction > 0.001f) {
                drawArc(
                    color = Color(0xFFC3CBD9),
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = true,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height),
                )
            }
            // 用户口径（2026-11-23）：外面那圈白描边去掉，只留浅灰圆底 + 白色扇形。
        }
        /**
         * 用户口径（2026-11-23）**角标数字按位数动态缩放，不能超格/截断**。
         *
         * 问题：圆只有 24dp、字号固定 13sp。
         * 三位数（如 168、200+）在这个圆里放不下 → 被 View 边界裁掉，
         * 用户看到「200 多集只用显示 20」就是这么来的。
         *
         * 规则（用户明确）：位数越多字号越小，必须完整放进圆内。
         *   1 位 → 11.5sp（最大）
         *   2 位 → 9.5sp
         *   3 位 → 7.5sp
         *   4 位及以上 → 6.5sp（极端情况，保证不溢出）
         * 同时收紧 letterSpacing，避免三位数横向撑破。
         */
        val text = unplayed.toString()
        val digits = text.length
        /**
         * 用户口径（2026-11-23 二次调整）：**上一版太保守了，数字偏小**。
         *
         * 按 24dp 圆反推可用内径（安全内径约 18.7dp，四周留呼吸空间），
         * 粗体数字宽度 ≈ 0.556 × 字号、高度 ≈ 0.72 × 字号：
         *   1 位 → 宽限 33.6 / 高限 18.8  → 15sp（最饱满，1-9 集最常见）
         *   2 位 → 宽限 16.8 / 高限 18.8  → 12.5sp
         *   3 位 → 宽限 11.2 / 高限 18.8  → 9.5sp
         *   4 位以上 → 宽限 8.4          → 7.5sp（极端情况）
         * 每档都比上一版放大 2-3sp，数字在圆里更匀称。
         */
        /**
         * 用户口径（2026-11-27，#94）**角标数字在大平板上不能超格**。
         *
         * 用户原话：
         * ```
         * 「剧集右上角角标的剧集数量数字，在大平板上显示是出格的，
         *   他貌似不受设置里面的封面缩放倍率控制」
         * ```
         *
         * 根因：上面字号是**写死的 sp**（15 / 12.5 / 9.5 / 7.5），
         * 而圆圈是 `size(24.dp)`。
         * 设置里的「显示比例」改的是全局 density —— dp 和 sp 会一起缩放，
         * 所以理论上比例不变；但**系统字体大小设置会额外放大 sp 而不放大 dp**，
         * 大平板上用户常把字体调大 → 数字撑破 24dp 圆。
         *
         * 改法：**字号按「圆的 dp 尺寸」换算，不再用固定 sp**。
         * ```
         * 圆 24dp；可用内径约 18.7dp（留呼吸空间）
         * 单字宽 ≈ 0.556 × 字号、高 ≈ 0.72 × 字号
         * 1 位 → 高限 18.7/0.72 = 26dp，取宽限也行 → 15dp
         * 2 位 → 15.6/2/0.556+... → 12.5dp
         * 3 位 → 9.5dp
         * 4 位+ → 7.5dp
         * ```
         * 用 `.dp` 转成 sp 时**按当前 fontScale 反算**，抵消掉系统字体放大：
         * 这样无论用户把字体调多大，数字始终占圆的固定比例，不会出格。
         */
        val density = androidx.compose.ui.platform.LocalDensity.current
        val baseDp = when {
            digits <= 1 -> 15f
            digits == 2 -> 12.5f
            digits == 3 -> 9.5f
            else -> 7.5f
        }
        /** fontScale 越大，实际 sp 要越小，才能保持同样的物理尺寸。 */
        val fontScale = density.fontScale.coerceAtLeast(0.5f)
        val size = baseDp / fontScale
        /**
         * 用户口径（2026-11-27，#113）**角标数字在灰圆里不居中，偏下偏左**。
         *
         * 用户原话：
         * ```
         * 「剧集的右上角小标不是有小数字吗，那个小数字不居中，
         *   就是在那个灰色的小圆圈里头，偏下，偏左下」
         * ```
         *
         * 根因（两个叠加）：
         * ```
         * ① Text 带 Modifier.fillMaxWidth(0.88f) —— 宽度主导布局，
         *    文字按排版基线摆放，而中文数字的 baseline 下方还有 descender 空间，
         *    视觉重心天然偏低。
         * ② 外层 Box 的 contentAlignment = Center 是按**行高盒子**居中，
         *    不是按**字形包围盒**居中，行高里上下留白不对称 → 偏左下。
         * ```
         *
         * 改法：
         * ```
         * · 去掉 fillMaxWidth，宽度交还给内容（wrapContent），
         *   让 Box 的 Center 能按文字实际宽度居中
         * · includeFontPadding = false —— 去掉字体内部的上下额外 padding，
         *   这是 Compose 里修「文字视觉不居中」的关键开关
         * · lineHeight 设成与字号相等，避免行高盒子比字形大
         * ```
         */
        Text(
            text,
            fontSize = size.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF23306B),
            letterSpacing = when {
                digits <= 1 -> 0.sp
                digits == 2 -> (-0.2).sp
                else -> (-0.3).sp
            },
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            /**
             * `includeFontPadding = false` 去掉字体自带的上下 padding ——
             * 这是「中文数字在圆里偏下」最常见的原因。
             * `lineHeight` 与字号一致，让行高盒子贴合字形。
             */
            style = androidx.compose.ui.text.TextStyle(
                fontSize = size.sp,
                lineHeight = size.sp,
                textAlign = TextAlign.Center,
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                    includeFontPadding = false,
                ),
            ),
            modifier = Modifier.wrapContentSize(),
        )
    }
}

/** 16:9 continue-watching card with progress and remaining time. */
@Composable
fun WideCard(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 用户口径（2026-11-23）：继续观看卡**宽屏不许比手机更宽**。
    // 前两版一直错在「屏越宽卡越大」，平板上反而更挤。现在封顶就是手机尺寸，
    // 平板靠「一行多放几张」来利用空间，而不是把单张放大。
    /**
     * 用户口径（#93）**横版卡宽度按「与竖版海报面积相同」反推**。
     *
     * 不再用 responsiveCardWidth（它按屏幕比例/列数算，结果跟海报对不上），
     * 直接取常量：手机 193dp、宽屏 245dp。
     * 见 Tokens.kt 的推导注释。
     */
    width: Dp = if (LocalWindowLayout.current.wide) Etkn.space.wideCardWide else Etkn.space.wideCard,
    /** Continue-watching cards add 从继续观看移除 to the long-press menu. */
    resume: Boolean = false,
    /** Set: the centre button plays and the rest of the card does [onClick]; else the whole card plays. */
    onPlay: (() -> Unit)? = null,
) {
    val url = images().landscape(item, width.px())
    val tokens = Etkn.tokens
    val press = rememberItemPress(item, resume)
    Column(modifier.width(width)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(press.modifier).clip(CardShape)
                .combinedClickable(onClick = onClick, onLongClick = if (press.enabled) press::open else null),
        ) {
            Artwork(url, Modifier.fillMaxSize(), item.name, placeholder = Icons.Rounded.Movie)
            Box(Modifier.fillMaxSize().background(tokens.wideScrim))
            Box(
                Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape)
                    .then(if (onPlay != null) Modifier.clickable(onClickLabel = "播放", role = Role.Button, onClick = onPlay) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow, if (onPlay != null) "播放" else null,
                    Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.35f), CircleShape).padding(8.dp),
                    tint = Color.White,
                )
            }
            // 用户口径（2026-11-23 照参考图）：集数不再叠在缩略图上，移到文字区第二行行首。
            val remaining = (item.runtimeMs - item.positionMs).coerceAtLeast(0)
            if (remaining > 0 && item.positionMs > 0) {
                Text(
                    "剩余 ${formatDuration(remaining)}", style = MaterialTheme.typography.labelSmall,
                    color = tokens.onImageMuted,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = 14.dp),
                )
            }
            if (item.progress > 0f) {
                LinearProgressIndicator(
                    progress = { item.progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp).height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = tokens.onImageTrack,
                    drawStopIndicator = {},
                )
            }
        }
        /**
         * 用户口径（2026-11-27，#118）**继续观看的剧名跟下面国产剧集海报保持一致**。
         *
         * 用户原话：
         * ```
         * 「继续观看画圆圈的文字，我想跟下边的文字不一样，是没加粗还是怎么的」
         * 「就是把继续观看的变成跟下面的一致」
         * 「不删东西，就是字号和字体的粗细跟下边的保持一致」
         * ```
         *
         * 原来：bodyMedium + FontWeight.SemiBold（加了粗）
         * 海报：titleSmall（没加 fontWeight）
         * 两处层级写法不同 → 看着不统一。
         * 现在统一到海报那套：titleSmall，不再手写 fontWeight。
         */
        Text(
            item.seriesName ?: item.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
        if (item.isEpisode) {
            // 第二行 = 集数（S1E34）+ 剧集标题，整行居中。
            val code = item.episodeCode
            val label = listOfNotNull(code, item.name.takeIf { it.isNotBlank() }).joinToString(" ")
            Text(
                label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 6.dp),
            )
        } else {
            /**
             * 用户口径（2026-11-27，#95）**电影也要有第二行，放年份**。
             *
             * 用户原话：
             * ```
             * 「我画圈的那块，剧集的下面有剧集信息，但是电影是空的，
             *   把年份放在电影名的下面」
             * ```
             * 原来只有 `if (item.isEpisode)` 分支 → 电影第二行是空的，
             * 「继续观看」那一排里电影比剧集矮一截，视觉不齐。
             *
             * 现在电影补一行年份（ProductionYear），没有年份就留空但占位，
             * 保证同期卡片高度一致。
             */
            val year = item.productionYear
            Text(
                if (year != null && year > 0) "${year}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 6.dp),
            )
        }
    }
}

/**
 * Library entry: server-generated 16:9 cover with the name underneath (not on the artwork).
 * 用户口径（2026-11-22）：文字从「压在图片上」改成「放在图片下方」，字加粗，间距留够。
 */
@Composable
fun LibraryCard(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 用户口径（2026-11-23）：媒体库分类卡按「一行放几张」反推宽度。
    // 平板一行要能完整放下 3 张以上（之前固定 200dp 在竖屏平板上只能放 2 张，第 3 张被切一半）。
    /** 用户口径（#93）媒体库入口卡与海报/继续观看同面积（193 / 宽屏 245）。 */
    width: Dp = if (LocalWindowLayout.current.wide) Etkn.space.libraryCardWide else Etkn.space.libraryCard,
    /** 用户口径（2026-11-23）：长按弹服务端功能菜单（只有管理员给）。 */
    onLongClick: (() -> Unit)? = null,
) {
    val url = images().primary(item, width.px())
    Column(
        modifier.width(width).clip(CardShape)
            .then(
                if (onLongClick != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else Modifier.clickable(onClick = onClick)
            ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(CardShape)) {
            Artwork(url, Modifier.fillMaxSize(), item.name, placeholder = libraryIcon(item.collectionType))
            Box(Modifier.fillMaxSize().background(Etkn.tokens.posterScrim))
        }
        Text(
            item.name,
            // 用户口径（2026-11-22）：媒体库分类名（国产剧集/华语电影…）比上一版调大一档，
            // 参考图里这个字是明显加粗、有分量的；区块标题（最新·xx）保持小一些。
            // 用户口径（2026-11-23）：分类名居中（照参考图）。
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp, bottom = 4.dp),
        )
    }
}

/** Grid entry for a library or folder: clean cover, name and size underneath (fills its cell). */
@Composable
fun LibraryTile(item: Item, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().clip(CardShape).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Artwork(
            images().primary(item, 420.dp.px()),
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(CardShape),
            item.name,
            placeholder = libraryIcon(item.collectionType),
        )
        Text(
            item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
        )
        (item.childCount ?: item.recursiveItemCount)?.takeIf { it > 0 }?.let { count ->
            Text(
                "$count 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

fun libraryIcon(type: String?) = when (type) {
    "music" -> Icons.Rounded.MusicNote
    else -> Icons.Rounded.Movie
}

/**
 * 用户口径（2026-11-23）：演职人员按参考图重做 ——
 * 竖向圆角矩形大卡（不是圆形头像），下面两行居中：姓名 / 「饰 角色名」。
 */
@Composable
/**
 * 用户口径（2026-11-27，#92）**电视剧详情页的演职人员要放大**。
 *
 * 用户原话：
 * ```
 * 「把电视剧的详情界面里面的演职人员放大点，
 *   季图也放大到跟演员一样大，章节图和集图也放大到那么大，只不过是横版」
 * ```
 * 三处尺寸统一到 132dp（原来 104dp，人物和季图偏小）：
 *   PersonCard 104 → 132
 *   SeasonCard 104 → 132
 *   章节卡/分集卡 160 → 132（横版 16:9，所以视觉面积相近）
 */
fun PersonCard(
    person: PersonRef,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 用户口径（2026-11-27，#109）**演员卡跟季卡一样大**。
     *
     * 用户原话：「演职人员的卡片大小，现在应该跟季的那个大小不太一样，改成跟季的大小一样」
     *
     * 季卡现在是 113dp 宽、比例 0.673（参考图 165×245px 量得）。
     * 演员卡原来是 112dp + 0.78 比例 → 112×144，
     * 比季卡的 113×168 矮 24dp，所以看着「演员卡明显比季卡小一圈」。
     * 现在两处都用同一组值：宽 113dp、比例 0.673。
     */
    width: Dp = Etkn.space.seasonCard,
) {
    val url = images().person(person, width.px())
    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(
            url,
            /** 跟季卡同一个比例（#109）。 */
            Modifier.width(width).aspectRatio(0.673f).clip(Corner.card).clickable(onClick = onClick),
            person.name,
            placeholder = Icons.Rounded.Person,
        )
        /**
         * 用户口径（#118）**演员名去掉手写的 SemiBold**，跟季卡/海报同一套。
         * 三处（剧集卡/季卡/演员卡）现在都是「style 决定字号+字重」，不再各处加粗。
         */
        Text(
            person.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (person.role.isNotEmpty()) {
            /**
             * 用户口径（2026-11-23）：**「饰」字重复已确诊**。
             *
             * 服务端 Role 字段本身就带「饰 」前缀（实测 ETKN 返回
             * Role="饰 吴秀雅"），这里再拼一次就变成「饰 饰 吴秀雅」。
             * 所以直接用角色原文，只在它明显没有前缀时才补一个。
             */
            val roleText = if (person.role.trimStart().startsWith("饰")) person.role.trim()
                           else "饰 ${person.role.trim()}"
            Text(
                roleText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}

@Composable
fun PersonAvatar(person: PersonRef, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 76.dp) {
    val url = images().person(person, size.px())
    Column(
        modifier.width(size + 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(
            url,
            Modifier.size(size).clip(CircleShape).clickable(onClick = onClick),
            person.name,
            placeholder = Icons.Rounded.Person,
        )
        Text(
            person.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (person.role.isNotEmpty()) {
            Text(
                person.role, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Square cover for albums / artists / playlists. */
@Composable
fun SquareCard(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 140.dp,
    round: Boolean = item.kind == "artists",
    subtitle: String? = null,
) {
    val url = images().music(item, (width ?: 180.dp).px())
    val shape = if (round) CircleShape else CardShape
    Column(
        modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .clip(CardShape).clickable(onClick = onClick),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Artwork(url, Modifier.fillMaxWidth().aspectRatio(1f).clip(shape), item.name, placeholder = Icons.Rounded.MusicNote)
        Text(
            item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
        val line = subtitle ?: listOfNotNull(
            item.count?.let { "$it 首" },
            item.artistLine.takeIf { it.isNotEmpty() },
        ).joinToString(" · ")
        if (line.isNotEmpty()) {
            Text(
                line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> "${h}小时${m}分"
        h > 0 -> "${h}小时"
        else -> "${m.coerceAtLeast(1)}分钟"
    }
}

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.secondaryContainer, content: Color = MaterialTheme.colorScheme.onSecondaryContainer) {
    Surface(modifier, shape = Corner.pill, color = container, contentColor = content) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
