package xyz.etkn.mobile.ui.nav

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.player.PlayerLauncher
import kotlinx.coroutines.launch

/** Thin navigation facade so screens never touch the NavController directly. */
@Stable
class AppNav(
    private val controller: NavHostController,
    private val context: Context,
    /** 点击时查续播用（不阻塞 UI 线程）。由 MainShell 传入 composition 的 scope。 */
    private val scope: kotlinx.coroutines.CoroutineScope,
) {

    fun back() {
        controller.popBackStack()
    }

    /** Tabs in the pager and how to move it; set by MainShell. */
    internal var tabs: List<TopLevel> = emptyList()
    internal var selectTab: (TopLevel) -> Unit = {}

    /** A tab in the pager is shown there (over pages are closed); any other opens as a page. */
    fun top(destination: TopLevel) {
        if (destination !in tabs) {
            to(destination.route)
            return
        }
        if (controller.currentDestination?.hasRoute<TabsRoute>() != true) controller.popBackStack<TabsRoute>(inclusive = false)
        selectTab(destination)
    }

    fun to(route: Any) = controller.navigate(route) { launchSingleTop = true }

    /** The title an item belongs to: an episode opens its series, anything else itself. */
    fun openTitle(item: Item) {
        val series = item.seriesId
        if (item.isEpisode && series != null) to(DetailRoute(series)) else open(item)
    }

    /**
     * 用户口径（2026-11-23，列表页点击行为 = 方案 B：点的时候才查，不预加载）：
     * · 这部剧有播放记录 → 查出续播的是哪一集 → 直接进那一集的单集详情页（第二层）
     * · 没有播放记录 / 查不到 → 进剧集详情页（第一层）
     * 只有剧集（Series）需要判断；电影、单集、文件夹等直接走 [open]。
     */
    fun openSmart(item: Item) {
        if (!item.isSeries) { open(item); return }
        scope.launch {
            val resume = runCatching { context.graph.api.resumeEpisodeOf(item.id) }.getOrNull()
            if (resume != null) to(DetailRoute(resume.id)) else to(DetailRoute(item.id))
        }
    }

    fun open(item: Item) {
        when {
            item.isMusicGroup -> to(MusicListRoute(parentId = musicParent(), tab = item.kind ?: "albums", key = item.key ?: "", title = item.name))
            item.isAudio -> context.graph.music.play(listOf(item), 0, null)
            item.type == "Person" -> to(PersonRoute(item.id, item.name))
            item.type == "CollectionFolder" || item.type == "UserView" -> openLibrary(item)
            item.type == "Folder" || item.type == "BoxSet" -> to(LibraryRoute(item.id, item.name, "folder"))
            else -> to(DetailRoute(item.id))
        }
    }

    fun openLibrary(library: Item) {
        if (library.collectionType == "music") {
            context.graph.music.selectLibrary(library.id)
            top(TopLevel.Music)
        } else {
            to(LibraryRoute(library.id, library.name, if (library.isLibraryGroup) "group" else library.collectionType))
        }
    }

    fun play(item: Item, resume: Boolean = true, sourceId: String? = null) {
        PlayerLauncher.play(context, item, resume, sourceId)
    }

    private fun musicParent(): String = context.graph.music.libraryId.value ?: ""
}

val LocalNav = staticCompositionLocalOf<AppNav> { error("AppNav not provided") }
