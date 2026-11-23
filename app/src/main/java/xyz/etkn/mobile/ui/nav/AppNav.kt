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
     * 用户口径（2026-11-23，列表页点击行为）：
     * · 这部剧有播放记录 → 直接进续播那一集的单集详情页（第二层）
     * · 没有播放记录 → 进剧集详情页（第一层）
     *
     * ⚠️ 曾经写成「点击时 await 网络请求查续播再跳」，实测后果是点击全部没反应：
     * 列表接口 LIST_FIELDS 本来就带 UserData，续播进度本地就有；再发一次请求不但慢，
     * 失败一次还让整次点击静默失效（严重 bug）。现在改成「先立刻跳，再后台补正」：
     *   1. 同步判断本地是否有播放记录 → 立即 navigate（点击零延迟，绝不会没反应）
     *   2. 只有确实有播放记录、但不知道是哪一集时，才后台查一次，拿到后替换成那一集
     */
    fun openSmart(item: Item) {
        if (!item.isSeries) { open(item); return }
        /**
         * 用户口径（2026-11-23）：只要这部剧有播放记录，点它就必须进「续播那一集的单集详情页」
         * （和「继续观看」里点出来的是同一个界面）。没播放记录才进剧集详情页。
         *
         * ⚠️ 之前判据用错了：看的是 Series 自己的 userData.playbackPositionTicks / playCount，
         * 但播放进度是记在 Episode 上的，Series 的这两项恒为 0 →
         * 永远判成「没看过」→ 直接进第一层 → 异步查续播那段一次都没跑。
         * 现在不再用本地 UserData 猜，改成「先跳第一层保证有反应，同时无条件查一次续播」，
         * 查到就替换成那一集（第二层）。
         */
        to(DetailRoute(item.id))
        scope.launch {
            val resume = runCatching { context.graph.api.resumeEpisodeOf(item.id) }.getOrNull() ?: return@launch
            // 只有用户还停在这部剧的详情页时才顶替，避免他中途点了别的被抢跳。
            if (controller.currentDestination?.hasRoute<DetailRoute>() == true) {
                to(DetailRoute(resume.id))
            }
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
