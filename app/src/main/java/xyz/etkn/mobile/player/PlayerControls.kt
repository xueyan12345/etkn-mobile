package xyz.etkn.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VideoSettings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.formatClock
import xyz.etkn.mobile.ui.theme.Corner

@Composable
internal fun Controls(
    ui: VideoUi,
    playing: Boolean,
    position: Long,
    duration: Long,
    buffered: Long,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSheet: (Sheet) -> Unit,
    onResize: () -> Unit,
    resizeLabel: String,
    onLock: () -> Unit,
    onNext: () -> Unit,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(200.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))),
        )
        // Top bar
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(ui.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ui.subtitle.isNotEmpty()) {
                    Text(ui.subtitle, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (ui.sources.size > 1) PlayerAction(Icons.Rounded.VideoSettings, "版本") { onSheet(Sheet.Versions) }
            PlayerAction(if (ui.castingTo != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast, "投屏") { onSheet(Sheet.Cast) }
            PlayerAction(Icons.Rounded.PictureInPictureAlt, "画中画", onPip)
            PlayerAction(Icons.Rounded.MoreVert, "更多") { onSheet(Sheet.More) }
        }
        // Center transport
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSeekBy(-10_000) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Replay10, "后退 10 秒", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(76.dp)) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放", tint = Color.White, modifier = Modifier.size(60.dp))
            }
            IconButton(onClick = { onSeekBy(10_000) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Forward10, "快进 10 秒", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
        // Bottom bar
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            val previews = ui.previews
            if (scrubbing && previews != null && duration > 0) SeekThumbnail(previews, scrub, duration)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatClock(if (scrubbing) (scrub * duration).toLong() else position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                val seek = remember { SliderState() }
                seek.value = if (scrubbing) scrub else if (duration > 0) position.toFloat() / duration else 0f
                Slider(
                    state = seek,
                    onValueChange = { scrubbing = true; scrub = it },
                    onValueChangeFinished = { onSeek((scrub * duration).toLong()); scrubbing = false },
                    colors = SliderDefaults.colors(inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text(formatClock(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerAction(Icons.Rounded.LockOpen, "锁定", onLock)
                Spacer(Modifier.weight(1f))
                PlayerTextAction(Icons.Rounded.Audiotrack, "音轨") { onSheet(Sheet.Audio) }
                PlayerTextAction(Icons.Rounded.ClosedCaption, "字幕") { onSheet(Sheet.Subtitle) }
                if (ui.danmakuSupported) {
                    PlayerTextAction(if (ui.danmakuOn) Icons.Rounded.ChatBubble else Icons.Rounded.ChatBubbleOutline, "弹幕") { onSheet(Sheet.Danmaku) }
                }
                PlayerTextAction(Icons.Rounded.Speed, if (ui.speed == 1f) "倍速" else "${ui.speed}×") { onSheet(Sheet.Speed) }
                PlayerTextAction(Icons.Rounded.AspectRatio, resizeLabel, onResize)
                if (ui.item?.isEpisode == true) PlayerTextAction(Icons.AutoMirrored.Rounded.PlaylistPlay, "选集") { onSheet(Sheet.Episodes) }
                if (ui.next != null) PlayerTextAction(Icons.Rounded.SkipNext, "下一集", onNext)
            }
        }
    }
}

@Composable
private fun PlayerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
}

@Composable
private fun PlayerTextAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** The frame under the finger while scrubbing, riding above the seek bar. */
@Composable
private fun SeekThumbnail(previews: SeekPreviews, fraction: Float, duration: Long) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val width = 176.dp
        val height = width / previews.aspectRatio
        val x = ((maxWidth - width) * fraction.coerceIn(0f, 1f))
        Artwork(
            previews.urlAt((fraction * duration).toLong()),
            Modifier.offset(x = x).width(width).height(height).clip(Corner.card)
                .border(1.dp, Color.White.copy(alpha = 0.5f), Corner.card),
            background = Color.Black,
        )
    }
}
