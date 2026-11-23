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
fun PlayerSidePanel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
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
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    surface.copy(alpha = 0.88f),
                                    RoundedCornerShape(12.dp),
                                ),
                        ) {
                            // 上下内边距 ≈ 参考图的 185px（≈ 30dp）
                            Column(Modifier.fillMaxSize().padding(vertical = 30.dp)) {
                                currentContent()
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
            val gap = (metrics.widthPixels * 0.054f).toInt().coerceAtLeast((8 * dp).toInt())

            window.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            window.setWindowAnimations(R.style.Animation_Etkn_SidePanel)
            // window 背景全透明 —— 圆角完全由 Compose 画，外侧不会有任何描边/方角。
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

            window.attributes = window.attributes.apply {
                // 宽度 51% 屏宽，上限 420dp（横屏大屏时不至于占满）
                width = (metrics.widthPixels * 0.51f).toInt()
                    .coerceIn((260 * dp).toInt(), (420 * dp).toInt())
                // 高度按内容包裹：wrap_content 由 Column 决定，配合垂直居中 = 浮动卡片
                height = WindowManager.LayoutParams.WRAP_CONTENT
                x = gap
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
