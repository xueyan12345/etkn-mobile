package xyz.etkn.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * 用户口径（2026-11-28，#183）**全 App 统一用「三点跳动」加载动画**。
 *
 * 用户原话：
 * ```
 * 「用第二个，3个小点那个，所有的加载动画都用了这个，替换原来那个大圆点」
 * ```
 *
 * ## 为什么自己做，不用 Material3 的 `LoadingIndicator`
 *
 * Material3 那个是「形变方块」样式（Google 的默认），和这个 App 的整体观感不搭。
 * 三点跳动更轻、更克制，而且尺寸缩小时不会糊。
 *
 * ## 动画参数（对照用户选中的 2-three-dots.gif）
 *
 * ```
 * 三个点，间距 = 点身高 × 1.6
 * 每个点：上下浮动 ±点身高×0.55，相位依次滞后 0.9 弧度（形成波浪）
 * 亮度：0.35 ↔ 1.0 随相位变化
 * 一个完整周期 1.1 秒
 * ```
 * 相位滞后是这个动画的关键 —— 三个点如果同步跳，看起来像「一起呼吸」，
 * 错开之后才有「依次弹起」的流动感。
 *
 * ## 用法
 *
 * ```kotlin
 * EtknLoading()                    // 默认 8dp 点，适合列表/按钮
 * EtknLoading(size = 14.dp)        // 播放器那种大号
 * EtknLoading(tint = Color.White)  // 压在视频画面上
 * ```
 *
 * 它替代 `androidx.compose.material3.LoadingIndicator`，
 * 全仓库的加载场景都该用它（`LoadingState` / `InlineLoading` / 播放器 / 弹层）。
 */
@Composable
fun EtknLoading(
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
    tint: Color = Color.Unspecified,
    /** 无障碍描述；传 null 表示纯装饰。 */
    label: String? = "加载中",
) {
    /** 未指定颜色时跟主题走（白底深色 / 暗色底浅色都能看见）。 */
    val color = if (tint == Color.Unspecified) {
        androidx.compose.material3.LocalContentColor.current
    } else {
        tint
    }

    val transition = rememberInfiniteTransition(label = "etkn-loading")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "etkn-loading-phase",
    )

    val dot = size
    val gap = dot * 1.6f

    Row(
        modifier = if (label != null) {
            modifier.semantics { contentDescription = label }
        } else {
            modifier
        },
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            /**
             * 每个点的相位依次滞后 0.9 弧度。
             *
             * `sin` 取值 -1..1 → 映射成：
             * ```
             * 垂直位移  y = sin(phase - i*0.9) × dot×0.55
             * 不透明度  a = 0.35 + 0.65 × max(0, sin(phase - i*0.9))
             * ```
             * 用 `max(0, ...)` 让它「亮的时间短、暗的时间长」，
             * 视觉上更像依次点亮，而不是三个点都在呼吸。
             */
            val s = sin(phase - i * 0.9f)
            val alpha = 0.35f + 0.65f * maxOf(0f, s)
            val dy = s * dot.value * 0.55f

            Box(
                Modifier
                    .size(dot)
                    .graphicsLayer {
                        this.alpha = alpha
                        translationY = dy
                    }
                    .background(color, CircleShape),
            )
        }
    }
}
