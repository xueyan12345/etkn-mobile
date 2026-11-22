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
import androidx.compose.material3.MaterialTheme
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
import java.text.SimpleDateFormat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import android.content.Context
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke

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
    // 用户口径（2026-11-23）：顶部中间显示系统时间 / 网速 / 电量，每秒刷新
    val status = rememberPlayerStatus()
    Box(Modifier.fillMaxSize()) {
        // 上下渐变，保证文字/按钮在亮画面上也看得清
        Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(240.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.84f)))),
        )

        // ===== 用户口径（2026-11-23，照参考图重排）=====
        // 正中间：只有时间。
        Text(
            status.time,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 8.dp),
        )
        // 右上角：网速 + 电量百分比 + 电池图标（同一行，紧贴右边）。
        Row(
            Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (status.speed.isNotEmpty()) {
                Text(status.speed, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
            Text("${status.battery}%", color = Color.White, fontSize = 12.sp)
            // 电池图标：随电量变长度（像素风）
            BatteryGlyph(status.battery)
        }

        // ===== 顶栏：返回 + 剧名 + 右侧功能图标 =====
        // 用户口径（2026-11-23）：功能图标整体下移，明显低于「时间」那一行（照参考图）。
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 8.dp).padding(top = 30.dp),
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
        Box(Modifier.align(Alignment.CenterStart).padding(start = 22.dp)) {
            PlayerAction(Icons.Rounded.LockOpen, "锁定", onLock)
        }

        // ===== 用户口径（2026-11-23）右侧垂直居中：倍速 + / 1.0x / − =====
        SpeedPad(
            speed = ui.speed,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 22.dp),
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
                // 用户口径（2026-11-23）按参考图重做进度条：
                // 细轨道 + 纯白已播放 + 半透明白未播放 + 缓冲条（浅白，夹在轨道层）
                // + 实心白色圆形播放头；时间文字在左侧外侧同一行。
                Text(
                    formatClock(if (scrubbing) (scrub * duration).toLong() else position),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    val progress = if (scrubbing) scrub
                    else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    val bufferedFrac = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    // 整条高度 26dp，里面自绘：轨道 3dp、缓冲 3dp、播放头直径 14dp
                    Canvas(
                        Modifier.fillMaxWidth().height(26.dp)
                            .pointerInput(duration) {
                                detectTapGestures { offset ->
                                    if (duration > 0) {
                                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                                        onSeek((frac * duration).toLong())
                                    }
                                }
                            }
                            .pointerInput(duration) {
                                detectHorizontalDragGestures(
                                    onDragStart = { offset ->
                                        if (duration > 0) {
                                            scrubbing = true
                                            scrub = (offset.x / size.width).coerceIn(0f, 1f)
                                        }
                                    },
                                    onDragEnd = {
                                        if (duration > 0) onSeek((scrub * duration).toLong())
                                        scrubbing = false
                                    },
                                    onDragCancel = { scrubbing = false },
                                ) { change, _ ->
                                    if (duration > 0) {
                                        scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                                        change.consume()
                                    }
                                }
                            },
                    ) {
                        val cy = size.height / 2f
                        val trackH = 3.dp.toPx()
                        val thumbR = 7.dp.toPx()
                        val r = androidx.compose.ui.geometry.CornerRadius(trackH / 2f, trackH / 2f)
                        // 未播放轨道（半透明白）
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.30f),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, cy - trackH / 2f),
                            size = androidx.compose.ui.geometry.Size(size.width, trackH),
                            cornerRadius = r,
                        )
                        // 缓冲进度（比轨道稍亮一点，压在轨道上）
                        if (bufferedFrac > 0.001f) {
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.55f),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, cy - trackH / 2f),
                                size = androidx.compose.ui.geometry.Size(size.width * bufferedFrac, trackH),
                                cornerRadius = r,
                            )
                        }
                        // 已播放（纯白）
                        drawRoundRect(
                            color = Color.White,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, cy - trackH / 2f),
                            size = androidx.compose.ui.geometry.Size(size.width * progress, trackH),
                            cornerRadius = r,
                        )
                        // 播放头：实心白圆
                        drawCircle(
                            color = Color.White,
                            radius = thumbR,
                            center = androidx.compose.ui.geometry.Offset(
                                (size.width * progress).coerceIn(thumbR, size.width - thumbR),
                                cy,
                            ),
                        )
                    }
                }
                Text(
                    formatClock(duration),
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 用户口径（2026-11-23）按参考图：左边 上一集 / 暂停 / 下一集；
                // 右边 弹幕开关 / 弹幕设置 / 音轨 / 字幕 / 选集（共 5 个）。
                // 弹幕那两个是这次按用户要求加回来的（之前换成了音轨/字幕/选集）。
                PlayerAction(Icons.Rounded.SkipPrevious, "上一集", onPrevious)
                PlayerAction(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放", onPlayPause)
                PlayerAction(Icons.Rounded.SkipNext, "下一集", onNext)
                Spacer(Modifier.weight(1f))
                DanmakuAction(0, if (ui.danmakuOn) "关闭弹幕" else "开启弹幕", ui.danmakuOn, onToggleDanmaku)
                DanmakuAction(1, "弹幕设置", ui.danmakuOn) { onSheet(Sheet.Danmaku) }
                PlayerAction(Icons.Rounded.Audiotrack, "音轨") { onSheet(Sheet.Audio) }
                PlayerAction(Icons.Rounded.ClosedCaption, "字幕") { onSheet(Sheet.Subtitle) }
                if (ui.item?.isEpisode == true) PlayerAction(Icons.AutoMirrored.Rounded.PlaylistPlay, "选集") { onSheet(Sheet.Episodes) }
            }
        }
    }
}

/**
 * 播放器顶部状态：系统时间 / 实时网速 / 电量。
 * 网速用 TrafficStats 两次采样差分（1 秒间隔），取不到就不显示（不编造 0.0）。
 */
private data class PlayerStatus(val time: String, val battery: Int, val speed: String)

@Composable
private fun rememberPlayerStatus(): PlayerStatus {
    val context = LocalContext.current
    var state by remember { mutableStateOf(PlayerStatus("", 0, "")) }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        var lastRx = 0L
        while (true) {
            val rx = android.net.TrafficStats.getTotalRxBytes()
            val speed = when {
                lastRx <= 0L || rx <= lastRx -> ""
                else -> {
                    val bytes = rx - lastRx
                    when {
                        bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB/s", bytes / 1048576f)
                        bytes >= 1024 -> String.format(Locale.ROOT, "%.0f KB/s", bytes / 1024f)
                        else -> "$bytes B/s"
                    }
                }
            }
            lastRx = rx
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            state = PlayerStatus(
                time = fmt.format(java.util.Date()),
                battery = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0,
                speed = speed,
            )
            kotlinx.coroutines.delay(1_000)
        }
    }
    return state
}

/** 像素风电池图标：外壳 + 右侧凸起 + 按电量填充的内部格子。 */
@Composable
private fun BatteryGlyph(percent: Int) {
    val level = percent.coerceIn(0, 100)
    val filled = (level / 20f).let { if (level > 0) it.coerceAtLeast(0.6f) else 0f }
    Canvas(Modifier.size(width = 24.dp, height = 12.dp)) {
        val bodyW = size.width - 3.dp.toPx()
        val stroke = 1.5.dp.toPx()
        val r = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
        // 外壳
        drawRoundRect(
            color = Color.White,
            size = androidx.compose.ui.geometry.Size(bodyW, size.height),
            cornerRadius = r,
            style = Stroke(width = stroke),
        )
        // 右侧凸起（正极）
        drawRoundRect(
            color = Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(bodyW, size.height * 0.30f),
            size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height * 0.40f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.8.dp.toPx(), 0.8.dp.toPx()),
        )
        // 内部填充
        if (filled > 0f) {
            val inset = stroke + 1.dp.toPx()
            val innerW = (bodyW - inset * 2) * (filled / 5f)
            drawRoundRect(
                color = Color.White,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(innerW, size.height - inset * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx(), 1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun PlayerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Color.White) }
}

/**
 * 用户口径（2026-11-23）：弹幕那排两个按钮改成参考图的样式 ——
 * 圆角矩形线框（无底板），内部横线/滑块意象，纯线条描边。
 * Material Icons 没有同款，这里用 Canvas 自绘。
 *
 * [kind] = 0 弹幕开关（方框 + 内部字块）；1 弹幕设置（方框 + 内部滑块线）。
 */
@Composable
private fun DanmakuAction(kind: Int, label: String, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Canvas(Modifier.size(24.dp)) {
            val tint = if (active) Color.White else Color.White.copy(alpha = 0.55f)
            val w = size.width
            val h = size.height
            val stroke = 1.7.dp.toPx()
            val pad = w * 0.10f
            val r = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
            // 外框：圆角矩形线框
            drawRoundRect(
                color = tint,
                topLeft = androidx.compose.ui.geometry.Offset(pad, h * 0.18f),
                size = androidx.compose.ui.geometry.Size(w - pad * 2, h * 0.64f),
                cornerRadius = r,
                style = Stroke(width = stroke),
            )
            if (kind == 0) {
                // 弹幕：框内两条横线（模拟文字行）
                val lx = pad + w * 0.13f
                val rx = w - pad - w * 0.13f
                drawLine(tint, androidx.compose.ui.geometry.Offset(lx, h * 0.40f), androidx.compose.ui.geometry.Offset(rx, h * 0.40f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, androidx.compose.ui.geometry.Offset(lx, h * 0.60f), androidx.compose.ui.geometry.Offset(lx + (rx - lx) * 0.6f, h * 0.60f), strokeWidth = stroke, cap = StrokeCap.Round)
            } else {
                // 设置：框内一条滑轨 + 一个滑块
                val lx = pad + w * 0.13f
                val rx = w - pad - w * 0.13f
                drawLine(tint, androidx.compose.ui.geometry.Offset(lx, h * 0.50f), androidx.compose.ui.geometry.Offset(rx, h * 0.50f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(tint, radius = stroke * 1.5f, center = androidx.compose.ui.geometry.Offset(lx + (rx - lx) * 0.35f, h * 0.50f))
            }
        }
    }
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
