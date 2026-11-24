package xyz.etkn.mobile.ui.nav

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.etkn.mobile.OpenRequest
import xyz.etkn.mobile.data.session.ActiveSession
import xyz.etkn.mobile.data.session.SavedAccount
import xyz.etkn.mobile.data.session.StoredSessions
import xyz.etkn.mobile.data.settings.NavPosition
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.actions.ItemMenu
import xyz.etkn.mobile.ui.actions.ItemMenuHost
import xyz.etkn.mobile.ui.actions.LocalItemMenu
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.common.WhileStarted
import xyz.etkn.mobile.ui.common.currentWindowLayout
import xyz.etkn.mobile.ui.components.LocalHaze
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.screens.music.MiniPlayer
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.LocalSettings
import xyz.etkn.mobile.ui.common.isTabletDevice

/** Space screens must leave at the bottom for the floating dock / mini player. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

/**
 * Space a tab page's content leaves at the start for the side dock. Pages under it get it as
 * padding; 首页 takes it itself so its poster runs on under the glass.
 */
val LocalStartInset = staticCompositionLocalOf { 0.dp }

@Composable
fun MainShell(session: ActiveSession) {
    val context = LocalContext.current
    val controller = rememberNavController()
    // 用户口径（2026-11-23）：列表页点击时要异步查续播，所以把 composition scope 交给 AppNav。
    val navScope = rememberCoroutineScope()
    val nav = remember(controller, navScope) { AppNav(controller, context, navScope) }
    val hazeState = rememberHazeState()
    val backStack by controller.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val isNowPlaying = destination?.hasRoute<NowPlayingRoute>() == true
    val music = context.graph.music
    val nowPlaying by music.state.collectAsStateWithLifecycle()
    val showMini = nowPlaying.current != null && !isNowPlaying
    val layout = currentWindowLayout()
    /**
     * 用户口径（2026-11-26）：**手机横屏不再被当成宽屏**。
     * 必须同时是「宽窗口」且「确实是平板设备」，才启用侧栏 Dock 那套布局。
     */
    val wide = layout.wide && isTabletDevice()
    val canDiscover by context.graph.discover.allowed.collectAsStateWithLifecycle()
    val settings = LocalSettings.current
    // Phones always use the bottom dock; wide windows use the side dock unless the user prefers the bottom one.
    val side = wide && settings.navPosition == NavPosition.Side
    val tabs = visibleTabs(settings.tabs, canDiscover, side)
    val pager = rememberPagerState { tabs.size }
    val pagerScope = rememberCoroutineScope()
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    nav.tabs = tabs
    nav.selectTab = { tab ->
        val index = tabs.indexOf(tab)
        if (index >= 0) pagerScope.launch { pager.animateScrollToPage(index, animationSpec = spring) }
    }
    val onTabs = destination == null || destination.hasRoute<TabsRoute>()
    // On the pager the highlight follows the page being swiped to; over pages, a pushed tab route.
    val current = if (onTabs) tabs.getOrNull(pager.targetPage)
    else TopLevel.entries.firstOrNull { tab -> destination?.hierarchy?.any { tab.matches(it) } == true }

    LaunchedEffect(session.key) {
        val discover = context.graph.discover
        discover.clear()
        launch { discover.allowed.value = runCatching { context.graph.api.canDiscover() }.getOrDefault(false) }
        context.graph.syncServerAppearance()
    }

    val dockVisible = onTabs && !side
    val sideVisible = onTabs && side
    val bottomInset: Dp = (if (dockVisible) Etkn.space.dockBottomInset else 24.dp) +
        (if (showMini) Etkn.space.miniPlayer + 8.dp else 0.dp)

    LaunchedEffect(Unit) {
        context.graph.openRequests.collect { request ->
            context.graph.openRequests.resetReplayCache()
            when (request) {
                is OpenRequest.Title -> nav.to(DetailRoute(request.itemId))
                OpenRequest.Search -> nav.top(TopLevel.Search)
                OpenRequest.Downloads -> nav.to(DownloadsRoute)
            }
        }
    }
    IngestNoticesEffect(session, settings.ingestNotices)

    val menuScope = rememberCoroutineScope()
    val itemMenu = remember(session.key) { ItemMenu(context.graph, menuScope) }
    // 用户口径（2026-11-22）：底栏长按「我的」快捷切号。
    val storedSessions by context.graph.sessions.accounts.collectAsStateWithLifecycle(initialValue = StoredSessions())
    val switchAccount: suspend (SavedAccount) -> Unit = { account ->
        if (account.key != session.key) context.graph.sessions.switchTo(account)
    }

    CompositionLocalProvider(
        LocalNav provides nav,
        LocalHaze provides hazeState,
        LocalBottomInset provides bottomInset,
        LocalStartInset provides if (sideVisible) Etkn.space.sideDockInset else 0.dp,
        LocalWindowLayout provides layout,
        LocalItemMenu provides itemMenu,
    ) {
        Box(Modifier.fillMaxSize()) {
            AppNavHost(controller, tabs, pager, verticalTabs = side, modifier = Modifier.fillMaxSize().glassSource(hazeState))
            AnimatedVisibility(
                sideVisible,
                modifier = Modifier.align(Alignment.CenterStart)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start))
                    .padding(start = 12.dp),
                enter = fadeIn() + slideInHorizontally { -it },
                exit = fadeOut() + slideOutHorizontally { -it },
            ) {
                Dock(
                    tabs, current, nav, pager, vertical = true,
                    storedAccounts = storedSessions.accounts,
                    activeAccountKey = session.key,
                    onSwitchAccount = switchAccount,
                )
            }
            Column(
                Modifier.align(if (side) Alignment.BottomEnd else Alignment.BottomCenter)
                    .then(if (wide) Modifier.widthIn(max = if (side) 520.dp else 640.dp) else Modifier).fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    // Same side margin as the top bars and the content, so their edges line up.
                    .padding(horizontal = Etkn.space.screen)
                    .padding(bottom = if (dockVisible) 10.dp else 12.dp),
            ) {
                AnimatedVisibility(showMini, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
                    MiniPlayer(onOpen = { nav.to(NowPlayingRoute) }, modifier = Modifier.padding(bottom = 8.dp))
                }
                AnimatedVisibility(dockVisible, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                    Dock(
                        tabs, current, nav, pager, vertical = false,
                        storedAccounts = storedSessions.accounts,
                        activeAccountKey = session.key,
                        onSwitchAccount = switchAccount,
                    )
                }
            }
            ItemMenuHost(itemMenu, hazeState)
        }
    }
}

/**
 * While the app is in front: asks once for the notification permission (Android 13+), then
 * checks 入库通知 and pending acquisitions every minute.
 */
@Composable
private fun IngestNoticesEffect(session: ActiveSession, enabled: Boolean) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(enabled) {
        val prefs = context.getSharedPreferences("ingest_notices", Context.MODE_PRIVATE)
        if (enabled && Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked_permission", false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("asked_permission", true).apply()
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    WhileStarted(session.key to enabled) {
        if (!enabled) return@WhileStarted
        while (true) {
            context.graph.ingest.check(session)
            delay(60_000)
        }
    }
}
