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
    /**
     * 用户口径（2026-11-23）：点剧集 → 有播放记录就进「上次看到的那一集」，没有就进剧集详情页。
     *
     * ⚠️ 这个函数**绝不能发网络请求**。前两轮的教训：
     *  · 第一轮把跳转包在 scope.launch{ 请求; 跳转 } 里 → 请求失败就静默不跳，点了没反应
     *  · 第二轮虽然查到了，但是「先跳第一层再替换成第二层」两次导航 → 闪跳 / 被 singleTop 拦下
     *
     * 现在：首页手里本来就有续播列表（data.resume，每条是 Episode，自带 SeriesId），
     * 直接本地匹配 → 立刻同步导航。零请求、零延迟、绝不会点不动。
     *
     * @param resume 首页/列表页已经拿到的续播条目（提供不了就传空，走剧集详情页）
     */
    fun openSmart(item: Item, resume: List<Item> = emptyList()) {
        if (!item.isSeries) { open(item); return }
        // 本地找：这部剧是否有正在看的单集
        val watching = resume.firstOrNull { it.seriesId == item.id || it.id == item.id }
        to(DetailRoute(watching?.id ?: item.id))
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

    /** [startMs] 非空时从该毫秒起播（章节点击用），优先级高于续播进度。 */
    fun play(
        item: Item,
        resume: Boolean = true,
        sourceId: String? = null,
        startMs: Long? = null,
        /** 用户口径（2026-11-27，#16）详情页选的音轨/字幕，播放时优先使用。 */
        audioIndex: Int? = null,
        subtitleIndex: Int? = null,
    ) {
        PlayerLauncher.play(context, item, resume, sourceId, startMs, audioIndex, subtitleIndex)
    }

    private fun musicParent(): String = context.graph.music.libraryId.value ?: ""
}

val LocalNav = staticCompositionLocalOf<AppNav> { error("AppNav not provided") }
