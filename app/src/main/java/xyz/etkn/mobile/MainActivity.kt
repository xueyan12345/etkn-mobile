package xyz.etkn.mobile

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import xyz.etkn.mobile.data.session.SessionState
import xyz.etkn.mobile.data.settings.Settings
import xyz.etkn.mobile.data.settings.UiScale
import xyz.etkn.mobile.ui.components.LoadingState
import xyz.etkn.mobile.ui.components.LocalImages
import xyz.etkn.mobile.ui.nav.MainShell
import xyz.etkn.mobile.ui.screens.connect.ConnectScreen
import xyz.etkn.mobile.ui.screens.me.UpdatePrompt
import xyz.etkn.mobile.ui.theme.EtknTheme
import androidx.compose.animation.core.tween

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        UiScale.applyTo(this, newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        preferFastestRefreshRate()
        openFrom(intent)
        setContent { EtknRoot(graph) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /**
     * Several ROMs keep apps at 60 Hz unless they ask, which makes page turns and scrolling stutter
     * on 90 / 120 / 144 Hz panels. Ask for the panel's fastest mode at the current resolution (a
     * different resolution would rescale the UI). Only this window asks; the player sets its own
     * rate from the video, and an adaptive (LTPO) panel still drops its rate while nothing moves.
     * Re-runs when a fold or a resolution change recreates the activity.
     */
    private fun preferFastestRefreshRate() {
        val display = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else null)
            ?: @Suppress("DEPRECATION") windowManager.defaultDisplay
            ?: return
        val current = display.mode
        val fastest = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (window.attributes.preferredDisplayModeId != fastest.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
        }
    }

    /** Notification, shortcut or widget: the shell opens it from [AppGraph.openRequests]. */
    private fun openFrom(intent: Intent?) {
        intent ?: return
        val request = intent.getStringExtra(EXTRA_ITEM)?.let(OpenRequest::Title) ?: when (intent.getStringExtra(EXTRA_PAGE)) {
            PAGE_SEARCH -> OpenRequest.Search
            PAGE_DOWNLOADS -> OpenRequest.Downloads
            else -> null
        }
        request?.let(graph.openRequests::tryEmit)
    }

    companion object {
        const val EXTRA_ITEM = "xyz.etkn.mobile.ITEM"
        const val EXTRA_PAGE = "xyz.etkn.mobile.PAGE"
        const val PAGE_SEARCH = "search"
        const val PAGE_DOWNLOADS = "downloads"
    }
}

@Composable
fun EtknRoot(graph: AppGraph) {
    // Wait for the stored preferences (a few ms) so page size and theme never flash a default.
    val stored: Settings? by graph.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val state by graph.sessions.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        val prefs = graph.settings.flow.first()
        graph.updates.setSource(prefs.updateSource)
        if (prefs.autoUpdate) graph.updates.autoCheck()
    }
    val settings = stored ?: return
    EtknTheme(settings) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            /**
             * 用户口径（2026-11-26）**切换账号轮播图白闪的真正根因在这里**。
             *
             * 这里原来写的是 `fadeIn() togetherWith fadeOut()`：
             * 切换账号 → targetState（session.key）变化 → 整棵树淡出再淡入。
             * 淡出到中间那一刻，屏幕上所有内容（含轮播图）都是半透明的，
             * 叠在 Surface 的纯色背景上 → 视觉上就是「闪一下白」。
             * 实测录屏抓到一帧顶部大图整体泛白、图片近乎消失，就是这一帧。
             *
             * 改法：
             *  · 只在「登录态之间」切换时不做全屏淡入淡出，直接切（内容自己会变）
             *  · 只有进入/退出登录（登录页 ↔ 主界面）这种跨场景才需要淡入淡出
             */
            val target = when (val s = state) {
                is SessionState.SignedIn -> s.session.key
                SessionState.SignedOut -> "signed-out"
                SessionState.Restoring -> "restoring"
            }
            AnimatedContent(
                targetState = target,
                transitionSpec = {
                    /**
                     * 只有「登录页 ↔ 主界面」这类跨场景切换才淡入淡出；
                     * 账号 ↔ 账号、账号 ↔ 恢复中 一律瞬时切换，避免整屏闪一下。
                     */
                    val scene = { key: String -> key == "signed-out" || key == "restoring" }
                    if (scene(initialState) || scene(targetState)) {
                        fadeIn() togetherWith fadeOut()
                    } else {
                        fadeIn(animationSpec = tween(0)) togetherWith fadeOut(animationSpec = tween(0))
                    }
                },
                label = "session",
            ) { target ->
                when {
                    target == "restoring" -> LoadingState()
                    target == "signed-out" -> ConnectScreen()
                    else -> {
                        val session = (state as? SessionState.SignedIn)?.session
                        if (session != null && session.key == target) {
                            val urls = remember(session.server, session.userId) { graph.images(session) }
                            key(session.key) {
                                CompositionLocalProvider(LocalImages provides urls) { MainShell(session) }
                            }
                        } else {
                            LoadingState()
                        }
                    }
                }
            }
            UpdatePrompt()
        }
    }
}
