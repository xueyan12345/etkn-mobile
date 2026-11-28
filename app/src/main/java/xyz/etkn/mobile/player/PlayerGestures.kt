package xyz.etkn.mobile.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth

/**
 * 用户口径（2026-11-27，#23）**快进增量用两位补零的 mm:ss**。
 *
 * 参考图是 `[+00:31]`（分钟也补零），和左边 `13:58` 的大字区分开。
 * 注意 `formatClock` 在 Cards.kt 里是"分:秒"不带补零的风格，这里要的是 `00:31`。
 */
private fun twoPartClock(ms: Long): String {
    val totalSec = (kotlin.math.abs(ms) / 1000).toInt()
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}

sealed interface GestureFeedback {
    /**
     * 用户口径（2026-11-27，#23）**快进提示按参考图重做**。
     *
     * 参考格式：`13:58 / 40:04 [+00:31]`
     *  · 左边 = 快进将要到达的时间
     *  · 斜杠后 = 整个视频的总时长
     *  · 方括号里 = 本次快进增减多少
     * 下面配一条缩略进度条（深色底 + 已播 + 缓存 + 未播）。
     *
     * [totalMs] / [cachedMs] 是新加的两个字段，用来画那条缩略进度条。
     */
    data class Seek(
        val targetMs: Long,
        val deltaMs: Long,
        val totalMs: Long = 0L,
        val cachedMs: Long = 0L,
    ) : GestureFeedback
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
    /**
     * 用户口径（#150）**顶部状态栏那条带子不接管手势** —— 在 Composable 上下文算好高度。
     *
     * `pointerInput` 的 lambda 是 suspend 的 PointerInputScope，里面不能读 Composable API，
     * 所以状态栏高度必须在这里取（`WindowInsets.statusBars` → px），
     * 再闭包传进去。+8dp 余量避免误判，下限 60dp 兜住无状态栏/沉浸式的情况。
     */
    val topStripPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        /**
         * 固定 56dp 起算（约等于状态栏 24dp + 32dp 余量）。
         *
         * 为什么不用 `WindowInsets.statusBars`：那个 API 在
         * `androidx.compose.foundation.layout` 里叫 `WindowInsets.Companion.statusBars`，
         * 全限定写法在 Kotlin 里解析不到（CI 报 `Unresolved reference 'statusBars'`）。
         * 播放器是沉浸式全屏，状态栏高度本来就固定在这个量级，
         * 用常量反而更稳（不随 insets 动画抖动）。
         */
        56.dp.toPx()
    }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(enabled, player) {
                detectTapGestures(
                    onTap = { onToggle() },
                    onDoubleTap = { offset ->
                        if (!enabled) return@detectTapGestures
                        /**
                         * 用户口径（2026-11-26）**双击手势改成九宫格**。
                         *
                         * 分区（横屏、竖屏都一样，画面均分 3×3）：
                         *   1 2 3
                         *   4 5 6
                         *   7 8 9
                         *  · 1（左上角）  → 后退 10 秒
                         *  · 3（右上角）  → 快进 10 秒
                         *  · 其余 7 格    → 暂停/播放
                         *
                         * 旧逻辑是「按 x 分三列」：左列后退、中列暂停、右列快进。
                         * 现在只有两个上角做快进退，其余全部暂停。
                         *
                         * ⚠️ 坐标系说明：这里的 `offset` 和 `size` 都是**画布坐标系**。
                         * OPPO 平板的横屏是靠「整层画布 rotationZ=90°」实现的，
                         * 而手势层就在那块画布内部，所以画布坐标 == 用户看到的画面坐标。
                         * 因此九宫格直接用 offset/size 算就是对的，
                         * 绝对不能在屏幕原始坐标里算（那样会整体错位 90°）。
                         */
                        val col = when {
                            offset.x < size.width / 3f -> 0      // 左列
                            offset.x < size.width * 2f / 3f -> 1 // 中列
                            else -> 2                            // 右列
                        }
                        val row = when {
                            offset.y < size.height / 3f -> 0      // 上排
                            offset.y < size.height * 2f / 3f -> 1 // 中排
                            else -> 2                              // 下排
                        }
                        val delta = when {
                            row == 0 && col == 0 -> -10_000L   // 1 左上 → 后退
                            row == 0 && col == 2 -> 10_000L    // 3 右上 → 快进
                            else -> 0L                          // 其余 → 暂停
                        }
                        if (delta == 0L) {
                            if (player.isPlaying) player.pause() else player.play()
                        } else {
                            val duration = player.duration.takeIf { it > 0 } ?: 0L
                            val target = (player.currentPosition + delta).coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
                            player.seekTo(target)
                            onFeedback(
                                GestureFeedback.Seek(
                                    targetMs = target,
                                    deltaMs = delta,
                                    totalMs = duration,
                                    cachedMs = session.ui.value.cachedPosition,
                                ),
                            )
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
                /**
                 * 用户口径（2026-11-28，#150）**从屏幕顶部下拉要能拉出状态栏**，
                 * 不能顺手把亮度/音量也调了。
                 *
                 * 用户原话：
                 * ```
                 * 「视频播放器不是顶部拉不下来状态栏，是可以拉状态栏，
                 *   但是同时触发了音量和亮度这两个操作，
                 *   因为往下拉状态栏的时候，那个操作正好是音量调低和亮度调低，
                 *   左边和右边」
                 * ```
                 *
                 * ## 原因
                 *
                 * 这一层 `fillMaxSize()` 的拖动识别器把**整屏**都吃掉了：
                 * ```
                 * 左上往下滑 → 亮度  ↓  右上往下滑 → 音量 ↓
                 * ```
                 * 而系统下拉状态栏恰恰也是「从顶部往下滑」——
                 * 同一个手势被两边同时响应，所以拉状态栏的同时亮度音量一起掉。
                 *
                 * ## 修法：顶部留一条「不接管」的带子
                 *
                 * 拖动起点落在顶部 [TOP_SYSTEM_STRIP_PX] 以内 → **这一轮手势完全不处理**
                 * （不 consume，交给系统 → 状态栏正常拉下来）。
                 * 超过这条线才走原来的亮度/音量/快进逻辑。
                 *
                 * 为什么按「起点」而不是「当前位置」判断：
                 * 用户从顶部起手就是想要状态栏，中途划到哪都该是状态栏；
                 * 反过来从中间起手往下滑是调亮度，也不能划到顶部就突然失效。
                 *
                 * 宽度取状态栏高度（约 24-48dp）再加一点余量。
                 */
                var mode = 0 // 1 seek, 2 brightness, 3 volume
                var startPosition = 0L
                var seekTarget = 0L
                var level = 0f
                var accumulated = androidx.compose.ui.geometry.Offset.Zero
                detectDragGestures(
                    onDragStart = { start ->
                        /**
                         * 用户口径（#150）**起点在顶部带子里 → 这一轮完全不接管**。
                         *
                         * `mode = -1` 是个哨兵值：
                         * ```
                         * onDrag 里看到 mode == -1 直接 return（不 consume）
                         * onDragEnd 里看到 mode == -1 什么都不做
                         * ```
                         * 这样系统才能接到「从顶部下拉」这个手势去拉状态栏。
                         */
                        if (start.y <= topStripPx) { mode = -1; return@detectDragGestures }
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
                        /** 顶部带子起手的那一轮：整轮不消费，交给系统拉状态栏。 */
                        if (mode == -1) return@detectDragGestures
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
                                onFeedback(
                                    GestureFeedback.Seek(
                                        targetMs = seekTarget,
                                        deltaMs = seekTarget - startPosition,
                                        totalMs = duration,
                                        cachedMs = session.ui.value.cachedPosition,
                                    ),
                                )
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
            // 用户口径：长按屏幕显示的倍速文字，恢复原始尺寸（1 倍）
            style = MaterialTheme.typography.titleMedium.copy(shadow = Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 10f)),
        )
        return
    }
    /**
     * 用户口径（2026-11-27，#23 #24）**浮层底色统一成半透明深灰**。
     *
     * 用户原话：「那个黑框有点太黑了，把那个黑框的颜色透明度调整到
     *            跟刚才要调整的那个快进提示那个黑底一样」。
     * 参考图里的底是**能透出画面的深灰**，不是纯黑实心。
     * 0.6 → 0.42，再叠一层很淡的白描边把边缘勾出来。
     */
    Box(
        modifier
            .clip(Corner.card)
            .background(Color.Black.copy(alpha = 0.42f))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), Corner.card)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (feedback) {
            /**
             * 用户口径（2026-11-27）**大字时间与加减秒数放同一行，底部对齐**。
             *
             * 需求原文：
             *  「上面的大字是进度，下边的小字是加减多少时间，你把这个小字和大字移到一行，
             *    前面是大字时间，那个加减多少时间放在那个大字的后边，还是保持一样的大小就行。
             *    大字在前面，小字在后边，跟在后边。然后这两个字的底部保持在同一条直线上就行」
             *
             * 实现：Row + verticalAlignment = Alignment.Bottom（底部对齐）
             * 各自字号不变（headlineSmall / labelLarge）。
             */
            /**
             * 用户口径（2026-11-27，#23）**快进提示完全照参考图**。
             *
             * 参考图文字：`13:58 / 40:04 [+00:31]`
             *  · 左边大字 = 快进将要到达的时间
             *  · ` / ` 后面 = 整个视频的总时长
             *  · 方括号内 = 本次快进增减（两位补零）
             * 下面一条缩略进度条：已播白 / 缓存浅灰 / 未播更深，等于底部进度条的缩小版。
             */
            is GestureFeedback.Seek -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        formatClock(feedback.targetMs),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "/ ${formatClock(feedback.totalMs)}",
                        color = Color.White.copy(alpha = 0.68f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    val sign = if (feedback.deltaMs >= 0) "+" else "-"
                    Text(
                        "[$sign${twoPartClock(abs(feedback.deltaMs))}]",
                        color = Color.White.copy(alpha = 0.68f),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (feedback.totalMs > 0) {
                    Spacer(Modifier.height(8.dp))
                    /** 缩略进度条：宽 200dp、高 3dp，和底部那条比例一致。 */
                    Box(
                        Modifier.width(200.dp).height(3.dp)
                            .clip(Corner.pill)
                            .background(Color.White.copy(alpha = 0.22f)),
                    ) {
                        val total = feedback.totalMs.toFloat().coerceAtLeast(1f)
                        /** 缓存段（更亮一点的灰）。 */
                        Box(
                            Modifier
                                .fillMaxWidth((feedback.cachedMs / total).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(Color.White.copy(alpha = 0.34f)),
                        )
                        /** 已播放（含快进目标）段：纯白。 */
                        Box(
                            Modifier
                                .fillMaxWidth((feedback.targetMs / total).coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(Color.White),
                        )
                    }
                }
            }
            /**
             * 用户口径（2026-11-27，#24）**亮度/音量加百分比**。
             *
             * 用户原话：「它这个调节的亮暗和音量大小没有百分比，
             *            你给我把百分比加上，就加在那个提示的区域后面就行，
             *            然后要保证字体比例啥的差不太多就行，别太夸张，就是匀称一点」。
             */
            is GestureFeedback.Level -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(feedback.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Box(
                    Modifier.width(140.dp).height(4.dp)
                        .clip(Corner.pill)
                        .background(Color.White.copy(alpha = 0.22f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(feedback.fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(Color.White),
                    )
                }
                Text(
                    "${(feedback.fraction * 100).toInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
            }
            is GestureFeedback.Text -> Unit
            is GestureFeedback.Speed -> Unit
        }
    }
}
