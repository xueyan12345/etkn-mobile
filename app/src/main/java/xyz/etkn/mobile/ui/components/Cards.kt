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
            Text(
                item.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
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
                    modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 1.dp, bottom = 6.dp),
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
        item.isSeries && unplayed > 0 -> Surface(
            modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            shape = Corner.small,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Text(
                unplayed.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** 16:9 continue-watching card with progress and remaining time. */
@Composable
fun WideCard(
    item: Item,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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
            item.episodeCode?.let {
                Text(
                    it, style = MaterialTheme.typography.labelMedium, color = tokens.onImage,
                    modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                )
            }
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
        Text(
            item.seriesName ?: item.name,
            // 用户口径（2026-11-22）：继续观看的标题字偏大，收到 bodyMedium 加粗，看着舒服些。
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
        )
        if (item.isEpisode) {
            Text(
                item.name, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp, bottom = 6.dp),
            )
        }
    }
}

/**
 * Library entry: server-generated 16:9 cover with the name underneath (not on the artwork).
 * 用户口径（2026-11-22）：文字从「压在图片上」改成「放在图片下方」，字加粗，间距留够。
 */
@Composable
fun LibraryCard(item: Item, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 200.dp) {
    val url = images().primary(item, width.px())
    Column(modifier.width(width).clip(CardShape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(CardShape)) {
            Artwork(url, Modifier.fillMaxSize(), item.name, placeholder = libraryIcon(item.collectionType))
            Box(Modifier.fillMaxSize().background(Etkn.tokens.posterScrim))
        }
        Text(
            item.name,
            // 用户口径（2026-11-22）：媒体库分类名（国产剧集/华语电影…）比上一版调大一档，
            // 参考图里这个字是明显加粗、有分量的；区块标题（最新·xx）保持小一些。
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp, bottom = 4.dp),
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
