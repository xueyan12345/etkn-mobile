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
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
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
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VideoSettings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.formatClock
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.components.images
import androidx.compose.ui.layout.ContentScale
import java.util.Locale
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable

/**
 * 播放器控制层。
 *
 * 用户口径（2026-11-23）按参考图重排：
 * · 左上：返回 + 剧名（服务端艺术字 logo，取不到就用文字标题）
 * · 顶部右侧：版本（多版本时）/ 画面比例 / 画中画 / 旋转屏幕 / 更多
 *   投屏已从这里移除（用户要求先去掉，后续另给位置）
 * · 中央：快退 10 秒 / 播放暂停 / 快进 10 秒（保持原样不动）
 * · 进度条上方：剧名 + 集号
 * · 进度条：加缓冲位置小条
 * · 进度条下方右侧图标：弹幕开关 / 弹幕设置 / 音轨 / 字幕 / 选集
 * · 左下：锁定
 */
@Composable
internal fun Controls(
    ui: VideoUi,
    playing: Boolean,
    duration: Long,
    position: Long,
    buffered: Long,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onSheet: (Sheet) -> Unit,
    onResize: () -> Unit,
    resizeLabel: String,
    onRotate: () -> Unit,
    rotateLabel: String,
    onLock: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onSpeed: (Float) -> Unit,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize()) {
        // 上下渐变，保证文字/按钮在亮画面上也看得清
        Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(240.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.84f)))),
        )

        // ===== 顶栏：返回 + 剧名 + 右侧功能图标 =====
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White) }
            // 用户口径（2026-11-23）：剧名要用服务端挂好的书法艺术字 logo（ImageTags.Logo），
            // 不是普通文字。取不到 logo 才退回文字标题。
            Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                // images() 是 @Composable，必须在 composable 上下文直接调，不能塞进 remember lambda
                val urls = images()
                val logoUrl = remember(ui.item?.id, urls) {
                    ui.item?.let { urls.logo(it, 480) }
                }
                if (logoUrl != null) {
                    Artwork(
                        logoUrl,
                        Modifier.height(32.dp).width(160.dp),
                        contentDescription = ui.title,
                        contentScale = ContentScale.Fit,
                        background = Color.Transparent,
                        alignment = Alignment.CenterStart,
                    )
                } else {
                    Text(
                        ui.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (ui.sources.size > 1) PlayerAction(Icons.Rounded.VideoSettings, "版本") { onSheet(Sheet.Versions) }
            PlayerAction(Icons.Rounded.AspectRatio, resizeLabel, onResize)
            PlayerAction(Icons.Rounded.PictureInPictureAlt, "画中画", onPip)
            PlayerAction(Icons.Rounded.ScreenRotation, rotateLabel, onRotate)
            PlayerAction(Icons.Rounded.MoreVert, "更多") { onSheet(Sheet.More) }
        }

        // ===== 用户口径（2026-11-23）左侧垂直居中：只放锁（从底栏移过来）=====
        Box(Modifier.align(Alignment.CenterStart).padding(start = 10.dp)) {
            PlayerAction(Icons.Rounded.LockOpen, "锁定", onLock)
        }

        // ===== 用户口径（2026-11-23）右侧垂直居中：倍速 + / 1.0x / − =====
        SpeedPad(
            speed = ui.speed,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            onChange = onSpeed,
            onPick = { onSheet(Sheet.Speed) },
        )

        // ===== 中央播放控制（保持原样，不缩不放）=====
        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onSeekBy(-10_000) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Replay10, "后退 10 秒", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = onPlayPause, modifier = Modifier.size(76.dp)) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (playing) "暂停" else "播放",
                    tint = Color.White, modifier = Modifier.size(60.dp),
                )
            }
            IconButton(onClick = { onSeekBy(10_000) }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.Forward10, "快进 10 秒", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }

        // ===== 底栏：剧名+集号 / 进度条 / 功能图标 =====
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            val previews = ui.previews
            if (scrubbing && previews != null && duration > 0) SeekThumbnail(previews, scrub, duration)

            // 用户口径（2026-11-23）：剧名 + 第几集放在进度条上方
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                Text(
                    buildString {
                        append(ui.title)
                        ui.subtitle.takeIf { it.isNotEmpty() }?.let { append("  ·  $it") }
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 用户口径（2026-11-23）：时间在左、进度条在右同一行；去掉右侧总时长
                Text(formatClock(if (scrubbing) (scrub * duration).toLong() else position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                val seek = remember { SliderState() }
                seek.value = if (scrubbing) scrub else if (duration > 0) position.toFloat() / duration else 0f
                // 缓冲条：在 Slider 下面叠一条细线显示已缓冲区间
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    val buffered = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    if (buffered > 0.002f) {
                        LinearProgressIndicator(
                            progress = { buffered },
                            modifier = Modifier.align(Alignment.Center).fillMaxWidth().height(2.dp),
                            color = Color.White.copy(alpha = 0.38f),
                            trackColor = Color.White.copy(alpha = 0.10f),
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    }
                    Slider(
                        state = seek,
                        onValueChange = { scrubbing = true; scrub = it },
                        onValueChangeFinished = { onSeek((scrub * duration).toLong()); scrubbing = false },
                        colors = SliderDefaults.colors(
                            // 用户口径：白色实心圆滑块 + 纯白已播放 + 半透明白未播放
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.32f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(formatClock(duration), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelMedium)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 用户口径（2026-11-23）按参考图：左边 上一集 / 暂停 / 下一集，
                // 右边 音轨 / 字幕 / 选集。锁和弹幕已移到左侧垂直居中区。
                PlayerAction(Icons.Rounded.SkipPrevious, "上一集", onPrevious)
                PlayerAction(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放", onPlayPause)
                PlayerAction(Icons.Rounded.SkipNext, "下一集", onNext)
                Spacer(Modifier.weight(1f))
                PlayerAction(Icons.Rounded.Audiotrack, "音轨") { onSheet(Sheet.Audio) }
                PlayerAction(Icons.Rounded.ClosedCaption, "字幕") { onSheet(Sheet.Subtitle) }
                if (ui.item?.isEpisode == true) PlayerAction(Icons.AutoMirrored.Rounded.PlaylistPlay, "选集") { onSheet(Sheet.Episodes) }
            }
        }
    }
}

@Composable
private fun PlayerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
}

/**
 * 右侧倍速控件（用户口径 2026-11-23，按参考图）：
 * 半透明深色圆角长条，垂直排列 + / 当前倍速 / −。
 * 点数字本身弹出倍速菜单可直接选。
 */
@Composable
private fun SpeedPad(speed: Float, modifier: Modifier, onChange: (Float) -> Unit, onPick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.36f))
            .padding(horizontal = 2.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable {
                onChange((((speed * 10).toInt() + 1) / 10f).coerceIn(0.3f, 4f))
            },
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium) }

        Box(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onPick).padding(horizontal = 6.dp, vertical = 4.dp),
        ) {
            Text(String.format(Locale.ROOT, "%.1fx", speed), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }

        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).clickable {
                onChange((((speed * 10).toInt() - 1) / 10f).coerceIn(0.3f, 4f))
            },
            contentAlignment = Alignment.Center,
        ) { Text("−", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium) }
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
