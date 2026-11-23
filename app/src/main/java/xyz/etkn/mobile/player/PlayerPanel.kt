package xyz.etkn.mobile.player

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
 * 用户口径（2026-11-23）：播放器右侧浮层**重做**，不再沿用原来那套毛玻璃面板。
 *
 * 为什么要重做（用户明确指出）：之前是在原面板上加了一块黑色色块去盖，
 * 那层半透明暗边是原面板自己的 window 背景 + 1dp 描边，
 * 加色块只是盖在它上面，从外面看那条暗边永远删不掉 —— 属于走捷径。
 *
 * 现在的做法：
 *  · 不用毛玻璃、不用 setStroke 描边、不用 window 背景模糊
 *  · window 背景就一块**不透明圆角纯色**（surface 色），四条边都是干净的同色圆角
 *  · 不请求 FLAG_DIM_BEHIND，外部完全不压暗
 *  · 自带 scrim：点面板外面关闭，但那个 scrim 是**全透明**的（只接收点击，不压暗画面）
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
                        // 面板本体自己画圆角背景，window 背景保持全透明，
                        // 这样圆角外侧不会被 window 背景染出一圈方角/暗边。
                        Box(
                            Modifier.fillMaxSize()
                                .background(surface, RoundedCornerShape(20.dp)),
                        ) {
                            Column(Modifier.fillMaxSize().padding(top = 12.dp, bottom = 8.dp)) {
                                currentContent()
                            }
                        }
                    }
                }
            }
            setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            window?.decorView?.setViewTreeViewModelStoreOwner(host.findViewTreeViewModelStoreOwner())
        }
    }
    DisposableEffect(dialog, surface) {
        dialog.window?.let { window ->
            val metrics = host.resources.displayMetrics
            val dp = metrics.density
            val margin = (12 * dp).toInt()
            window.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            window.setWindowAnimations(R.style.Animation_Etkn_SidePanel)
            // 用户口径（2026-11-23）：window 背景全透明 —— 圆角由 Compose 自己画。
            // 之前这里 setBackgroundDrawable(GradientDrawable + setStroke) 就是那圈暗边的来源。
            window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window.attributes = window.attributes.apply {
                width = (host.width * 0.42f).toInt()
                    .coerceIn((300 * dp).toInt(), (420 * dp).toInt())
                    .coerceAtMost(host.width - margin * 2)
                height = host.height - margin * 2
                x = margin
                dimAmount = 0f
            }
            // 不压暗外部画面
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 不启用跨窗口模糊（模糊是毛玻璃效果的来源，这里要纯色）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                window.setBackgroundBlurRadius(0)
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
