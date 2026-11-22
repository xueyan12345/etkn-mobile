package xyz.etkn.mobile.player

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import xyz.etkn.mobile.R
import xyz.etkn.mobile.ui.theme.Etkn

/**
 * Floating panel docked to the right edge of the player, so the picture stays visible on the left.
 *
 * It is its own window: on Android 12+ the compositor blurs the video behind it (window background
 * blur reaches the SurfaceView, which no in-app blur can sample). Without cross-window blur (older
 * Android, battery saver, some OEM builds) the panel is simply more opaque.
 *
 * A plain [ComponentDialog] instead of Compose's `Dialog`, whose layout pass resizes the window:
 * here the window itself is the panel, sized and positioned once.
 */
@Composable
fun PlayerSidePanel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalView.current
    val parent = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val currentDismiss by rememberUpdatedState(onDismiss)
    // Same glass recipe as the long-press menu: the theme's glass tint over a real blur,
    // a hairline border, and light content colour (the panel window has no Surface of its own).
    val tokens = Etkn.tokens
    val tint = tokens.glassTint
    val flat = tokens.glassFlat
    val border = tokens.glassBorder
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
                        Column(Modifier.fillMaxSize().padding(top = 16.dp, bottom = 8.dp)) { currentContent() }
                    }
                }
            }
            setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            window?.decorView?.setViewTreeViewModelStoreOwner(host.findViewTreeViewModelStoreOwner())
        }
    }
    DisposableEffect(dialog, tint, flat, border) {
        dialog.window?.let { window ->
            val metrics = host.resources.displayMetrics
            val dp = metrics.density
            val blur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window.windowManager.isCrossWindowBlurEnabled
            val margin = (12 * dp).toInt()
            window.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
            window.setWindowAnimations(R.style.Animation_Etkn_SidePanel)
            window.attributes = window.attributes.apply {
                width = (host.width * 0.42f).toInt().coerceIn((320 * dp).toInt(), (440 * dp).toInt()).coerceAtMost(host.width - margin * 2)
                height = host.height - margin * 2
                x = margin
                // 用户口径（2026-11-23）：菜单外面那条半黑阴影去掉 —— 不再压暗视频。
                // 面板自身的毛玻璃已经把层次分开，画面保持原亮度。
                dimAmount = 0f
            }
            // 不请求 FLAG_DIM_BEHIND：请求了才会在窗口外盖一层半透明黑。
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.setBackgroundDrawable(
                GradientDrawable().apply {
                    cornerRadius = 28 * dp
                    setColor((if (blur) tint else flat).toArgb())
                    setStroke(dp.toInt().coerceAtLeast(1), border.toArgb())
                },
            )
            if (blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setBackgroundBlurRadius((40 * dp).toInt())
            // The player is immersive; a focused dialog window would otherwise bring the bars back.
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        dialog.show()
        onDispose { dialog.dismiss() }
    }
}
