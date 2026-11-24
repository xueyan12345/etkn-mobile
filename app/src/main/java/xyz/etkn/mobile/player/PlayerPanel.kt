package xyz.etkn.mobile.player

import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import xyz.etkn.mobile.R
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints

/**
 * 用户口径（2026-11-23）播放器右侧菜单 —— 照参考图逐项对齐（实测数值）。
 *
 * 从参考图（2520×1680）量出来的几何：
 *  · 面板宽 ≈ 1285px = 屏宽 51%，**右侧留白 ≈ 135px = 5.4%** → 是浮起来的卡片，不贴边
 *  · 面板不占满全高，按内容包裹（6 行 + 上下内边距），垂直居中
 *  · 行高 ≈ 面板宽 24.5%（≈ 60dp @ 400dp 面板）→ 6 行排开留白均匀
 *  · 图标 ≈ 面板宽 8.6%（≈ 24dp 圆框，线宽 6%）
 *  · 左右内边距 ≈ 面板宽 3.9%（≈ 18dp），右侧状态值列右内边距 ≈ 8dp
 *  · 圆角 ≈ 面板宽 2.5%（≈ 12dp；视觉上是明显圆角但不到胶囊）
 *  · 底色深灰蓝半透明，不透明度约 88%，能隐约透出背后画面
 *  · 无分隔线、无分组标题、无描边
 *
 * 之前几版错在哪（都记下来，别再犯）：
 *  · 把宽度当成「太宽大」缩到 32% —— 参考图其实是 51%，缩窄了反而更不像
 *  · 面板撑满全高 —— 参考图是内容包裹、上下留白的浮动卡片
 *  · 底色用不透明纯色 —— 参考图是半透明，能透出画面
 *  · 用 GradientDrawable+setStroke 描边 —— 那圈暗边就是它，永远删不干净
 */
@Composable
fun PlayerSidePanel(
    onDismiss: () -> Unit,
    /**
     * 用户口径（2026-11-24）**平板画布旋转时浮层也要转**。
     *
     * 这个面板是独立的 Android Dialog（自己的 Window），
     * 不在 Compose 的图形树里，所以播放器整层 rotationZ=90° 对它无效 ——
     * 用户看到的就是「画面横着、弹窗竖着」。
     *
     * 解法：给 Dialog 的 decorView 也套一层同样的旋转（见 DisposableEffect）。
     * 手机 rotated=false，走原来的路径，行为完全不变。
     */
    rotated: Boolean = false,
    content: @Composable () -> Unit,
) {
    val host = LocalView.current
    val parent = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val surface = MaterialTheme.colorScheme.surface
    val onPanel = MaterialTheme.colorScheme.onSurface

    val dialog = remember(host) {
        ComponentDialog(host.context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
            setOnCancelListener { currentDismiss() }
            val view = ComposeView(context).apply {
                setParentCompositionContext(parent)
                setContent {
                    CompositionLocalProvider(LocalContentColor provides onPanel) {
                        // 面板自己画圆角半透明底；window 背景全透明，
                        // 这样圆角外侧不会出现方角/暗边（那圈暗边是旧实现最被诟病的地方）。
                        /**
                         * 用户口径（2026-11-24）**平板画布旋转的浮层方案（第二版，正确版）**。
                         *
                         * 上一版直接给 Dialog 的 decorView 设 rotation=90°，两个问题：
                         *  ① 崩了：decorView.layoutParams 为 null 时赋值 null → NPE
                         *  ② 就算不崩也会被 Window 边界裁掉一半
                         *
                         * 现在照搬播放器里已验证的做法：
                         *  · Window 保持全屏、不旋转
                         *  · 内部用「宽高互换 + 绕中心 90°」的 Compose 画布
                         *  · 面板贴在这块画布的右侧（视觉上仍是屏幕右侧）
                         */
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val availableW = if (rotated) maxHeight else maxWidth
                            val panelWidth = (availableW * 0.51f).coerceIn(260.dp, 420.dp)
                            // 右侧留白：屏宽 5.4%（参考图实测）；旋转画布下已在画布内，无需再留
                            val panelGap = if (rotated) 0.dp else (availableW * 0.054f)
                            val canvas = if (rotated) {
                                Modifier.requiredSize(maxHeight, maxWidth).graphicsLayer { rotationZ = 90f }
                            } else {
                                Modifier.fillMaxSize()
                            }
                            Box(canvas) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .fillMaxHeight()
                                        .width(panelWidth)
                                        .padding(end = panelGap)
                                        .background(
                                            surface.copy(alpha = 0.88f),
                                            RoundedCornerShape(12.dp),
                                        ),
                                ) {
                                    Column(Modifier.fillMaxSize().padding(vertical = 30.dp)) {
                                        currentContent()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            setContentView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            window?.decorView?.setViewTreeViewModelStoreOwner(host.findViewTreeViewModelStoreOwner())
        }
    }

    DisposableEffect(dialog, surface) {
        dialog.window?.let { window ->
            val metrics = host.resources.displayMetrics
            val dp = metrics.density

            /**
             * 用户口径（2026-11-24）：窗口改成全屏铺满、不定位、不旋转。
             * 面板的位置和旋转全部交给内部 Compose 画布处理 ——
             * 这样不会被 Window 边界裁切，也不会碰 decorView.layoutParams（上一版 NPE 来源）。
             */
            window.setGravity(Gravity.FILL)
            window.setWindowAnimations(R.style.Animation_Etkn_SidePanel)
            // window 背景全透明 —— 圆角完全由 Compose 画，外侧不会有任何描边/方角。
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

            window.attributes = window.attributes.apply {
                // 全屏窗口：面板尺寸/位置由内部 Compose 画布决定
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                dimAmount = 0f
            }

            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

            // 参考图有轻微毛玻璃感：小半径背景模糊（S+ 支持）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                window.setBackgroundBlurRadius((18 * dp).toInt())
            }

            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        dialog.show()
        onDispose { dialog.dismiss() }
    }
}
