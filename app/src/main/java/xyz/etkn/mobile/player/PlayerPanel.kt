package xyz.etkn.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 用户口径（2026-11-26）**播放器右侧面板 —— 第四版，彻底换实现方式**。
 *
 * 前三版都错在同一个地方：这个面板原来是**独立 Android Dialog（自己的 Window）**，
 * 不在播放器的 Compose 图形树里，所以播放器整层的画布旋转对它无效。
 * 我一直在「屏幕坐标系」里猜它该贴哪一边（CenterEnd / BottomCenter / TopCenter 都试过），
 * 但屏幕是竖屏 1680×2520、用户看到的是横屏 2520×1680，
 * 中间还隔着一次 90° 旋转 —— 靠猜对齐方式永远猜不对，而且画布没居中还会整体偏移。
 *
 * 现在不再用 Dialog，直接渲染在播放器的 Compose 树里、并且放在**已经旋转好的画布内部**：
 *  · 画布坐标系 == 用户看到的画面坐标系（宽 2520 高 1680 的那块）
 *  · 所以 `Alignment.CenterEnd` 就是「画面的右侧」，不需要任何坐标换算
 *  · 面板方向天然正确（跟着画布一起转），不会被 Window 边界裁切
 *  · 手机端画布不旋转，等价于直接铺在屏幕上，行为与原来一致
 *
 * 面板几何照参考图：宽 = 画布宽 51%（上限 520dp），右侧留白 = 画布宽 5.4%，
 * 高度按内容包裹、垂直居中 —— 是浮起来的卡片，不贴边、不占满全高。
 */
@Composable
fun PlayerSidePanel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    val onPanel = MaterialTheme.colorScheme.onSurface

    CompositionLocalProvider(LocalContentColor provides onPanel) {
        Box(Modifier.fillMaxSize()) {
            /**
             * 底层：全屏透明点击层。
             * 点面板以外任意位置关闭（不再依赖 Dialog 的 setCanceledOnTouchOutside，
             * 那个在窗口铺满整屏时永远不触发）。
             */
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val panelWidth = (maxWidth * 0.51f).coerceIn(260.dp, 520.dp)
                val edgeGap = maxWidth * 0.054f
                Box(
                    Modifier
                        // 画布坐标系的「右端」就是用户看到的画面右侧
                        .align(Alignment.CenterEnd)
                        .padding(end = edgeGap)
                        .width(panelWidth)
                        .wrapContentHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(surface.copy(alpha = 0.92f))
                        // 消费面板内部的点击，避免冒泡到下面那层把它关掉
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                        .padding(vertical = 24.dp),
                ) {
                    Column { content() }
                }
            }
        }
    }
}
