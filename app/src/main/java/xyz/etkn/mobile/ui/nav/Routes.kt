package xyz.etkn.mobile.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.serialization.Serializable

/** Start destination: the top-level tabs in one swipeable pager. */
@Serializable data object TabsRoute
@Serializable data object HomeRoute
@Serializable data object LibrariesRoute
@Serializable data object MusicRoute
@Serializable data object SearchRoute
@Serializable data object MeRoute

@Serializable data class LibraryRoute(val id: String, val name: String, val collectionType: String? = null)
@Serializable data class DetailRoute(val id: String)
@Serializable data class PersonRoute(val id: String, val name: String)
/** Titles of one category card of a library ([kind] Genres / Tags / Networks / Companies). */
@Serializable data class FacetRoute(val libraryId: String, val kind: String, val value: String)
/** All episodes of a series, opened at [seasonId]. */
/** [seed]: the series page's artwork colour, so the grid keeps its palette. */
@Serializable data class EpisodesRoute(val seriesId: String, val seasonId: String?, val title: String, val seed: Int? = null)
@Serializable data object FavoritesRoute
@Serializable data class MusicListRoute(
    val parentId: String,
    val tab: String,
    val key: String = "",
    val title: String,
    val tracksOnly: Boolean = false,
)
@Serializable data object NowPlayingRoute
@Serializable data object AccountsRoute
@Serializable data object DownloadsRoute
@Serializable data class SettingsRoute(val page: String)
@Serializable data object DiscoverRoute
@Serializable data class DiscoverDetailRoute(val id: String, val kind: String, val title: String)
@Serializable data class DiscoverPersonRoute(val id: String, val name: String)
/** [season] < 0 = whole title. */
@Serializable data class CloudRoute(val id: String, val kind: String, val title: String, val season: Int = -1)

enum class TopLevel(val route: Any, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Home(HomeRoute, "首页", Icons.Outlined.Home, Icons.Rounded.Home),
    Libraries(LibrariesRoute, "媒体库", Icons.Outlined.VideoLibrary, Icons.Rounded.VideoLibrary),
    Music(MusicRoute, "音乐", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    Discover(DiscoverRoute, "探索", Icons.Outlined.Explore, Icons.Rounded.Explore),
    Search(SearchRoute, "搜索", Icons.Outlined.Search, Icons.Rounded.Search),
    Me(MeRoute, "我的", Icons.Outlined.AccountCircle, Icons.Rounded.AccountCircle),
}

/**
 * Tabs shown in the dock / rail. The user's pick (首页 always) or, by default, everything with
 * 探索 taking 搜索's place on phones (search stays in the home top bar). 探索 needs the account
 * right; the phone dock holds at most [DOCK_MAX] tabs.
 */
fun visibleTabs(chosen: Set<String>?, canDiscover: Boolean, side: Boolean): List<TopLevel> {
    // 用户口径（2026-11-23）：底栏不再显示「媒体库」入口（首页里有那一行可点进去）。
    // 注意：只是不放进底栏，LibrariesRoute 页面本身保留，顶部入口仍可导航过去。
    // 用户口径（2026-11-23）：底栏再删掉「搜索」（搜索已挪到「我的」页顶部）。
    // 页面/枚举/SearchRoute 全部保留，只是不进底栏。
    val all = TopLevel.entries.filter { it != TopLevel.Libraries && it != TopLevel.Search }
    val picked = chosen?.let { names -> all.filter { it == TopLevel.Home || it.name in names } }
        ?: all.filter { tab ->
            when (tab) {
                TopLevel.Search -> side || !canDiscover
                else -> true
            }
        }
    val allowed = picked.filter { it != TopLevel.Discover || canDiscover }
    return if (side) allowed else allowed.take(DOCK_MAX)
}

const val DOCK_MAX = 5

fun TopLevel.matches(destination: NavDestination): Boolean = when (this) {
    TopLevel.Home -> destination.hasRoute<HomeRoute>()
    TopLevel.Libraries -> destination.hasRoute<LibrariesRoute>()
    TopLevel.Music -> destination.hasRoute<MusicRoute>()
    TopLevel.Discover -> destination.hasRoute<DiscoverRoute>()
    TopLevel.Search -> destination.hasRoute<SearchRoute>()
    TopLevel.Me -> destination.hasRoute<MeRoute>()
}
