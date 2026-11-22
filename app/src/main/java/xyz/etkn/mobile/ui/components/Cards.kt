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
            // 底：浅蓝灰实心圆（参考图底色）
            drawCircle(color = Color(0xFFB9C6DE), radius = radius, center = center)
            // 进度：内部更亮的扇形，从 12 点顺时针
            if (fraction > 0.001f) {
                drawArc(
                    color = Color(0xFFF2F6FF),
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = true,
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height),
                )
            }
            // 用户口径（2026-11-23）：外面那圈白描边去掉，只留浅灰圆底 + 白色扇形。
        }
        Text(
            unplayed.toString(),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF23306B),
        )
    }
}

/** 16:9 continue-watching card with progress and remaining time. */
@Composable
fun WideCard(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 用户口径（2026-11-23）：平板实测继续观看封面偏大 —— 改成按窗口宽度自适应。
    // 手机上仍是 210dp；平板随屏宽放大但封顶 300dp（一屏至少放得下 3 张）。
    width: Dp = responsiveCardWidth(
        base = Etkn.space.wideCard,
        maxWidth = 300.dp,
        fractionOfScreen = 0.42f,
    ),
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
        // 用户口径（2026-11-23 照参考图）：剧名居中。
        Text(
            item.seriesName ?: item.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
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
    // 用户口径（2026-11-23）：平板适配，媒体库卡也跟着屏宽走（手机 200dp，封顶 260dp）。
    width: Dp = responsiveCardWidth(base = 200.dp, maxWidth = 260.dp, fractionOfScreen = 0.40f),
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
fun PersonCard(person: PersonRef, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 104.dp) {
    val url = images().person(person, width.px())
    Column(modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        Artwork(
            url,
            Modifier.width(width).aspectRatio(0.78f).clip(Corner.card).clickable(onClick = onClick),
            person.name,
            placeholder = Icons.Rounded.Person,
        )
        Text(
            person.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        if (person.role.isNotEmpty()) {
            Text(
                "饰 ${person.role}",
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
