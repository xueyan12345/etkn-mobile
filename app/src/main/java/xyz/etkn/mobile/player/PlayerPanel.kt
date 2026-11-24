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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable

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
    /**
     * 用户口径（2026-11-24）**这就是「浮层没跟着转」的根因**。
     *
     * dialog 是 `remember(host)` 创建的，setContent 的 lambda 只跑一次，
     * 里面直接引用外部的 `rotated` 会被固化成【第一次组合时的值】——
     * 那时 forceRotate 还是 false（还没判定），所以浮层永远不旋转。
     *
     * 用 rememberUpdatedState 包一层：lambda 里读的是 State，
     * rotated 变化时弹窗内容会跟着重组，和播放器同步。
     */
    val currentRotated by rememberUpdatedState(rotated)

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
                            val availableW = if (currentRotated) maxHeight else maxWidth
                            val panelWidth = (availableW * 0.51f).coerceIn(260.dp, 420.dp)
                            // 右侧留白：屏宽 5.4%（参考图实测）；旋转画布下已在画布内，无需再留
                            val panelGap = if (currentRotated) 0.dp else (availableW * 0.054f)
                            val canvas = if (currentRotated) {
                                Modifier.requiredSize(maxHeight, maxWidth).graphicsLayer { rotationZ = 90f }
                            } else {
                                Modifier.fillMaxSize()
                            }
                            /**
                             * 画布必须居中！否则 requiredSize(2520,1680) 的画布
                             * 会摆在窗口左上角，转 90° 后超出窗口被裁掉上下两条。
                             */
                            /**
                             * 用户口径（2026-11-26）**点面板以外的地方要能关闭**。
                             *
                             * 之前窗口是全屏 MATCH_PARENT + Gravity.FILL，
                             * Dialog 自己就铺满整屏，所以 setCanceledOnTouchOutside(true)
                             * 永远不会触发（它判的是「点在 Dialog 外面」，
                             * 而 Dialog 已经没有「外面」了）→ 用户只能按系统返回键关。
                             *
                             * 现在在画布最外层铺一个透明点击层：
                             * 点空白处 → 关闭；点面板本体 → 被面板自己的 clickable 消费，不关。
                             */
                            Box(
                                Modifier
                                    .then(canvas)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = currentDismiss,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                /**
                                 * 用户口径（2026-11-24）**别把面板撑满全高**。
                                 *
                                 * 之前写 fillMaxHeight()，面板被拉成整屏高，
                                 * 内容超出就被上下边缘裁掉（截图里第 21 集和最后一集都截断了）。
                                 * 参考图是「按内容包裹 + 垂直居中」的浮动卡片。
                                 */
                                /**
                                 * 用户口径（2026-11-26）**position 换算终版：屏幕右侧 = 画布顶部**。
                                 *
                                 * 画布 rotationZ = 90f（顺时针），画布内点 (dx,dy) 转到屏幕是 (-dy, dx)：
                                 *   画布右侧 (dx>0,dy=0) → 屏幕下方   ← v1.2.24 用的 CenterEnd，错了
                                 *   画布底部 (dx=0,dy>0) → 屏幕左侧   ← v1.2.25 用的 BottomCenter，也错
                                 *   画布顶部 (dx=0,dy<0) → 屏幕右侧   ← 正确，用户要的就是这个
                                 *   画布左侧 (dx<0,dy=0) → 屏幕上方
                                 *
                                 * 所以旋转态必须贴【画布顶部】，并用 top 留出安全边距。
                                 */
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .padding(
                                            end = panelGap,
                                            top = if (currentRotated) panelGap else 0.dp,
                                        ),
                                    contentAlignment = if (currentRotated) Alignment.TopCenter
                                                       else Alignment.CenterEnd,
                                ) {
                                    Box(
                                        Modifier
                                            .width(panelWidth)
                                            .wrapContentHeight()
                                            .background(
                                                surface.copy(alpha = 0.88f),
                                                RoundedCornerShape(12.dp),
                                            )
                                            // 消费面板内的点击，避免冒泡到外层把它关掉
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = {},
                                            ),
                                    ) {
                                        Column(Modifier.padding(vertical = 24.dp)) {
                                            currentContent()
                                        }
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
