package xyz.etkn.mobile.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import kotlin.math.abs
import xyz.etkn.mobile.ui.components.formatClock
import xyz.etkn.mobile.ui.theme.Corner

internal sealed interface GestureFeedback {
    data class Seek(val targetMs: Long, val deltaMs: Long) : GestureFeedback
    data class Level(val icon: ImageVector, val fraction: Float) : GestureFeedback
    data class Text(val text: String) : GestureFeedback
    /** 长按调速时的当前倍速；[hideAfterMs] 到点后自动消失，不挡画面。 */
    data class Speed(val speed: Float) : GestureFeedback
}

@Composable
internal fun GestureLayer(
    enabled: Boolean,
    session: VideoSession,
    player: Player,
    onToggle: () -> Unit,
    onFeedback: (GestureFeedback?) -> Unit,
) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val activity = context as? Activity
    Box(
        Modifier.fillMaxSize()
            .pointerInput(enabled, player) {
                detectTapGestures(
                    onTap = { onToggle() },
                    onDoubleTap = { offset ->
                        if (!enabled) return@detectTapGestures
                        val delta = when {
                            offset.x < size.width * 0.35f -> -10_000L
                            offset.x > size.width * 0.65f -> 10_000L
                            else -> 0L
                        }
                        if (delta == 0L) {
                            if (player.isPlaying) player.pause() else player.play()
                        } else {
                            val target = (player.currentPosition + delta).coerceAtLeast(0)
                            player.seekTo(target)
                            onFeedback(GestureFeedback.Seek(target, delta))
                        }
                    },
                )
            }
            // 用户口径（2026-11-22）：长按倍速 + 长按期间左右滑动调速。
            //   长按（系统长按时长）→ 2.0×，保持原来「长按 2 倍速」的观感
            //   继续右滑 → 每 40dp +0.1×，上限 4.0×
            //   左滑     → 每 40dp -0.1×，下限 0.3×
            //   松手恢复原速；倍速徽标 5 秒后自动隐藏，不挡画面。
            // 用 detectDragGesturesAfterLongPress 而不是手搓 awaitPointerEventScope：
            // 后者会和双击手势抢事件，把双击快进退吃掉。
            .pointerInput(enabled, player) {
                if (!enabled) return@pointerInput
                // 用户口径（2026-11-23）：灵敏度太低，同样距离原来只加 0.2，改成加 0.5。
                // 原 40dp 一格 0.1×；现 16dp 一格 0.1×，即同样 40dp 会走 2.5 格 = 0.25×，
                // 再把步长提到 0.2×/格 → 40dp 约 0.5×，符合要求。
                val stepPx = 16.dp.toPx()
                // 累计水平位移（跨 onDrag 帧），只在这次手势内有效
                var dxTotal = 0f
                var prevSpeed = 1f
                var lastSpeed = 2f
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        dxTotal = 0f
                        prevSpeed = player.playbackParameters.speed
                        lastSpeed = 2f
                        if (player.isPlaying) {
                            player.setPlaybackSpeed(2f)
                            onFeedback(GestureFeedback.Speed(2f))
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dxTotal += change.position.x - change.previousPosition.x
                        val steps = (dxTotal / stepPx).toInt()
                        // 用户口径（2026-11-23）：步长从 0.2 改回 0.1（0.1 一档加减），
                        // 滑动手感（16dp 一格）和范围 0.3~4.0 都保持不变。
                        val target = kotlin.math.round(((2f + steps * 0.1f).coerceIn(0.3f, 4f)) * 10f) / 10f
                        if (target != lastSpeed) {
                            lastSpeed = target
                            player.setPlaybackSpeed(target)
                            onFeedback(GestureFeedback.Speed(target))
                        }
                    },
                    onDragEnd = {
                        player.setPlaybackSpeed(prevSpeed)
                        onFeedback(null)
                    },
                    onDragCancel = {
                        player.setPlaybackSpeed(prevSpeed)
                        onFeedback(null)
                    },
                )
            }
            .pointerInput(enabled, player) {
                if (!enabled) return@pointerInput
                var mode = 0 // 1 seek, 2 brightness, 3 volume
                var startPosition = 0L
                var seekTarget = 0L
                var level = 0f
                var accumulated = androidx.compose.ui.geometry.Offset.Zero
                detectDragGestures(
                    onDragStart = { start ->
                        mode = 0
                        accumulated = androidx.compose.ui.geometry.Offset.Zero
                        startPosition = player.currentPosition
                        seekTarget = startPosition
                        level = if (start.x < size.width / 2) {
                            // 用户口径（2026-11-23）：记忆上次亮度。优先用存过的值，没有才用系统当前值。
                            context.getSharedPreferences("etkn_player", Context.MODE_PRIVATE)
                                .getFloat("screen_brightness", -1f)
                                .takeIf { it >= 0f }
                                ?: (activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: 0.5f)
                        } else {
                            audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        }
                    },
                    onDragEnd = {
                        if (mode == 1) player.seekTo(seekTarget)
                        onFeedback(null)
                    },
                    onDragCancel = { onFeedback(null) },
                    onDrag = { change, drag ->
                        change.consume()
                        accumulated += drag
                        if (mode == 0) {
                            if (abs(accumulated.x) < 24f && abs(accumulated.y) < 24f) return@detectDragGestures
                            mode = if (abs(accumulated.x) > abs(accumulated.y)) 1 else if (change.position.x < size.width / 2) 2 else 3
                        }
                        when (mode) {
                            1 -> {
                                val duration = player.duration.takeIf { it > 0 } ?: return@detectDragGestures
                                val span = minOf(duration, 10 * 60_000L)
                                val delta = (accumulated.x / size.width * span).toLong()
                                seekTarget = (startPosition + delta).coerceIn(0, duration)
                                onFeedback(GestureFeedback.Seek(seekTarget, seekTarget - startPosition))
                            }
                            2 -> {
                                level = (level - drag.y / (size.height * 0.8f)).coerceIn(0.01f, 1f)
                                activity?.window?.let { w ->
                                    val attrs = w.attributes
                                    attrs.screenBrightness = level
                                    w.attributes = attrs
                                }
                                // 用户口径（2026-11-23）：记住这次调的亮度，下次进播放器沿用。
                                context.getSharedPreferences("etkn_player", Context.MODE_PRIVATE)
                                    .edit().putFloat("screen_brightness", level).apply()
                                onFeedback(GestureFeedback.Level(Icons.Rounded.Brightness6, level))
                            }
                            3 -> {
                                level = (level - drag.y / (size.height * 0.8f)).coerceIn(0f, 1f)
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
                                onFeedback(GestureFeedback.Level(Icons.AutoMirrored.Rounded.VolumeUp, level))
                            }
                        }
                    },
                )
            },
    )
}

@Composable
internal fun GestureBadge(feedback: GestureFeedback, modifier: Modifier) {
    // A plain notice (2× 倍速) is bare text with a soft shadow: no box over the picture.
    if (feedback is GestureFeedback.Text) {
        Text(
            feedback.text, modifier = modifier, color = Color.White, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 10f)),
        )
        return
    }
    // 长按调速：只显示「2.3×」，同样裸字 + 阴影，不盖画面。
    if (feedback is GestureFeedback.Speed) {
        Text(
            "%.1f×".format(feedback.speed), modifier = modifier, color = Color.White, fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 10f)),
        )
        return
    }
    Box(
        modifier.clip(Corner.card).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 20.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (feedback) {
            is GestureFeedback.Seek -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatClock(feedback.targetMs), color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                val sign = if (feedback.deltaMs >= 0) "+" else "-"
                Text("$sign${formatClock(abs(feedback.deltaMs))}", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelLarge)
            }
            is GestureFeedback.Level -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(feedback.icon, null, tint = Color.White)
                Spacer(Modifier.width(12.dp))
                LinearProgressIndicator(progress = { feedback.fraction }, modifier = Modifier.width(140.dp).height(6.dp), drawStopIndicator = {})
            }
            is GestureFeedback.Text -> Unit
            is GestureFeedback.Speed -> Unit
        }
    }
}
