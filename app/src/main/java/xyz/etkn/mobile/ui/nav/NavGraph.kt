package xyz.etkn.mobile.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import xyz.etkn.mobile.ui.screens.detail.DetailScreen
import xyz.etkn.mobile.ui.screens.detail.EpisodesScreen
import xyz.etkn.mobile.ui.screens.discover.CloudScreen
import xyz.etkn.mobile.ui.screens.discover.DiscoverDetailScreen
import xyz.etkn.mobile.ui.screens.discover.DiscoverPersonScreen
import xyz.etkn.mobile.ui.screens.discover.DiscoverScreen
import xyz.etkn.mobile.ui.screens.home.HomeScreen
import xyz.etkn.mobile.ui.screens.library.FacetScreen
import xyz.etkn.mobile.ui.screens.library.FavoritesScreen
import xyz.etkn.mobile.ui.screens.library.LibrariesScreen
import xyz.etkn.mobile.ui.screens.library.LibraryScreen
import xyz.etkn.mobile.ui.screens.me.AccountsScreen
import xyz.etkn.mobile.ui.screens.me.DownloadsScreen
import xyz.etkn.mobile.ui.screens.me.MeScreen
import xyz.etkn.mobile.ui.screens.me.SettingsPage
import xyz.etkn.mobile.ui.screens.me.SettingsScreen
import xyz.etkn.mobile.ui.screens.music.MusicHomeScreen
import xyz.etkn.mobile.ui.screens.music.MusicListScreen
import xyz.etkn.mobile.ui.screens.music.NowPlayingScreen
import xyz.etkn.mobile.ui.screens.person.PersonScreen
import xyz.etkn.mobile.ui.screens.search.SearchScreen
import xyz.etkn.mobile.ui.theme.Motion
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * False while a tab sits in the pager without being the page shown: the neighbours are composed
 * in advance, so a screen must not grab focus (a focused field scrolls the pager to itself) or
 * start work meant for when it is open until this turns true. Always true outside the pager.
 */
val LocalTabActive = compositionLocalOf { true }

/** Every destination inside the shell. */
@Composable
internal fun AppNavHost(controller: NavHostController, tabs: List<TopLevel>, pager: PagerState, verticalTabs: Boolean, modifier: Modifier = Modifier) {
    // 用户口径（2026-11-22）：翻书动画删掉，页面之间改成淡入淡出。
    // 六个转场照样全部显式设置，避免 NavHost 默认的缩小/滑动冒出来。
    val fadeIn = fadeIn(tween(Motion.FADE_IN))
    val fadeOut = fadeOut(tween(Motion.FADE_OUT))
    NavHost(
            navController = controller,
            startDestination = TabsRoute,
            modifier = modifier,
            enterTransition = { fadeIn },
            exitTransition = { fadeOut },
            popEnterTransition = { fadeIn },
            popExitTransition = { fadeOut },
            predictivePopEnterTransition = { fadeIn },
            predictivePopExitTransition = { fadeOut },
        ) {
            page<TabsRoute> { TabPager(tabs, pager, verticalTabs) }
            // Tabs left out of the dock still open as ordinary pages.
            page<HomeRoute> { HomeScreen() }
            page<LibrariesRoute> { LibrariesScreen() }
            page<MusicRoute> { MusicHomeScreen() }
            page<SearchRoute> { SearchScreen() }
            page<MeRoute> { MeScreen() }
            page<LibraryRoute> { LibraryScreen(it.toRoute()) }
            page<FacetRoute> { FacetScreen(it.toRoute()) }
            page<DetailRoute> { DetailScreen(it.toRoute<DetailRoute>().id) }
            page<EpisodesRoute> { EpisodesScreen(it.toRoute()) }
            page<PersonRoute> { PersonScreen(it.toRoute()) }
            page<FavoritesRoute> { FavoritesScreen() }
            page<MusicListRoute> { MusicListScreen(it.toRoute()) }
            page<NowPlayingRoute> { NowPlayingScreen() }
            page<AccountsRoute> { AccountsScreen() }
            page<DownloadsRoute> { DownloadsScreen() }
            page<DiscoverRoute> { DiscoverScreen() }
            page<DiscoverDetailRoute> { DiscoverDetailScreen(it.toRoute()) }
            page<DiscoverPersonRoute> { DiscoverPersonScreen(it.toRoute()) }
            page<CloudRoute> { CloudScreen(it.toRoute()) }
            page<SettingsRoute> { entry ->
                val page = entry.toRoute<SettingsRoute>().page
                SettingsScreen(SettingsPage.entries.firstOrNull { it.name == page } ?: SettingsPage.Appearance)
            }
        }
}

/**
 * The dock's tabs side by side: switching is a pager scroll, not a navigation, so the screens
 * stay composed (scroll position, loaded data), the neighbour is laid out in advance and a
 * horizontal swipe on a page moves between tabs (bottom dock only). Back returns to 首页 first.
 */
@Composable
private fun TabPager(tabs: List<TopLevel>, pager: PagerState, vertical: Boolean) {
    val scope = rememberCoroutineScope()
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    BackHandler(enabled = pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0, animationSpec = spring) } }
    val key = { page: Int -> tabs.getOrNull(page)?.name ?: page }
    val content: @Composable PagerScope.(Int) -> Unit = { page -> TabPage(tabs.getOrNull(page), pager.settledPage == page) }
    // Pages run the way the dock does. Beside a side dock they slide vertically and only the dock
    // moves them: a vertical swipe on a page scrolls the page.
    if (vertical) {
        VerticalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = false, key = key, pageContent = content)
    } else {
        /**
         * 用户口径（2026-11-23）**彻底禁止在页面上左右滑动切换标签**。
         *
         * 只写 userScrollEnabled = false 不够 —— 实测还能滑，而且松手会卡在
         * 两页中间（左露媒体库网格、右露音乐页），能来回拖。
         * 这可能来自内层 LazyRow 的手势竞争：横滑先被 Pager 认领、又被内层抢回去，
         * 两边都不 settle。
         *
         * 现在的双保险：
         *  ① userScrollEnabled = false（禁 Pager 自己的拖拽）
         *  ② 外层把横向拖拽事件消费掉，不让它传到 Pager
         *     只吃横向（|dx| > |dy|），纵向和点击照常透传，
         *     所以页面上下滚动、点卡片都不受影响。
         */
        /**
         * 用户口径（2026-11-24）**修「轮播图不能手动滑动」**。
         *
         * 上一版在 PointerEventPass.Initial 阶段就 consume 横向事件 ——
         * 那是最早的一轮，内层（轮播图 HorizontalPager、各 LazyRow）
         * 根本还没机会拿到手势，所以轮播图滑不动。
         *
         * 现在改到 Final 阶段（最后一轮）：
         *  · 内层已经消费了（change.isConsumed == true）→ 放行，不动
         *    这样轮播图、横向列表照常滑动
         *  · 内层没消费（普通页面区域横滑）→ 在这里吃掉
         *    外层 TabPager 收不到，标签页就不会被拖成两页并排
         *  · 纵向 / 点击 → 直接放行（不影响页面滚动和点按）
         */
        val blockHorizontalSwipe = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull() ?: continue
                    // 内层已经处理过（轮播图 / LazyRow）→ 不碰
                    if (change.isConsumed) continue
                    val dx = kotlin.math.abs(change.position.x - change.previousPosition.x)
                    val dy = kotlin.math.abs(change.position.y - change.previousPosition.y)
                    // 只吃「明确的横向拖拽」残余
                    if (dx > dy && dx > 0f) change.consume()
                }
            }
        }
        HorizontalPager(
            pager,
            Modifier.fillMaxSize().then(blockHorizontalSwipe),
            beyondViewportPageCount = 1,
            userScrollEnabled = false,
            key = key,
            pageContent = content,
        )
    }
}

@Composable
private fun TabPage(tab: TopLevel?, active: Boolean) {
    // 首页 runs its poster under the side dock and insets the rest itself; other tabs just start after it.
    val inset = LocalStartInset.current
    val ownInset = tab == TopLevel.Home
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(start = if (ownInset) 0.dp else inset)) {
        CompositionLocalProvider(LocalTabActive provides active, LocalStartInset provides if (ownInset) inset else 0.dp) {
            when (tab) {
                TopLevel.Home -> HomeScreen()
                TopLevel.Libraries -> LibrariesScreen()
                TopLevel.Music -> MusicHomeScreen()
                TopLevel.Discover -> DiscoverScreen()
                TopLevel.Search -> SearchScreen()
                TopLevel.Me -> MeScreen()
                null -> Unit
            }
        }
    }
}

/**
 * Every destination draws its own opaque page, so a page turning over another never shows through.
 */
private inline fun <reified T : Any> NavGraphBuilder.page(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable<T> { entry -> PageFrame { content(entry) } }

/**
 * 页面切换：翻书动画已按用户要求删除（2026-11-22），这里只保留每个目标自己的不透明底。
 * 转场由 NavHost 的 fadeIn/fadeOut 负责（见 AppNavHost）。
 */
@Composable
private fun AnimatedContentScope.PageFrame(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.ModulateAlpha }
            .background(MaterialTheme.colorScheme.surface),
    ) {
        content()
    }
}
