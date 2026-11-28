package xyz.etkn.mobile.ui.actions

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveDone
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.etkn.mobile.AppGraph
import xyz.etkn.mobile.ItemChange
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.data.net.bool
import xyz.etkn.mobile.data.net.long
import xyz.etkn.mobile.data.net.str
import xyz.etkn.mobile.ui.common.userMessage
import xyz.etkn.mobile.ui.components.glass
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.data.cast.CastDevice
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import xyz.etkn.mobile.data.model.MediaSource
import xyz.etkn.mobile.graph
import androidx.compose.foundation.layout.Arrangement
import xyz.etkn.mobile.ui.components.GlassSheet
import xyz.etkn.mobile.ui.components.EtknLoading

/** Long-press / overflow menu of the current shell; null outside it (previews, player). */
val LocalItemMenu = staticCompositionLocalOf<ItemMenu?> { null }

enum class ItemAction { Favorite, Played, Refresh, Metadata, Images, Previews, Delete, HideResume, Cast }

internal data class Entry(val action: ItemAction, val label: String, val icon: ImageVector, val danger: Boolean = false)

/** One row of a [GlassPopup]: overflow actions and pickers (sort keys, account shortcuts …). */
data class PopupChoice(
    val label: String,
    val icon: ImageVector? = null,
    /**
     * 用户口径（2026-11-23）：账号快捷浮层里每人要显示 ETKN 服务器上给该账号设的头像。
     * 有 url 就画头像（小圆角方框），没有就退回 [icon]。
     */
    val avatarUrl: String? = null,
    val selected: Boolean = false,
    val trailing: ImageVector? = null,
    val onClick: () -> Unit,
)

/** A small glass menu dropped from [anchor]; drawn by [ItemMenuHost] so it blurs the page like the item menu. */
internal class GlassPopup(val anchor: Rect, val choices: List<PopupChoice>)

data class MenuTarget(val item: Item, val anchor: Rect, val resume: Boolean)

/** The editors / confirmations the menu opens after it closes. */
sealed interface ItemDialog {
    val item: Item

    data class Refresh(override val item: Item) : ItemDialog
    data class Delete(override val item: Item) : ItemDialog
    data class Cast(override val item: Item) : ItemDialog
    data class Metadata(override val item: Item) : ItemDialog
    data class Images(override val item: Item) : ItemDialog
}

/**
 * Same actions and permission rules as the ETKN web context menu: user data for everyone,
 * 刷新 for admins or `CanRefreshMetadata`, 删除 for admins or `CanDeleteMedia`, editors and
 * thumbnail extraction for admins. Management applies to local `item-*` titles only; the
 * server checks every permission again.
 */
@Stable
class ItemMenu(private val graph: AppGraph, private val scope: CoroutineScope) {
    var target by mutableStateOf<MenuTarget?>(null)
        private set
    var dialog by mutableStateOf<ItemDialog?>(null)
    internal var popup by mutableStateOf<GlassPopup?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
    private var admin by mutableStateOf(false)
    private var canRefresh by mutableStateOf(false)
    private var canDelete by mutableStateOf(false)
    private var busy = false

    private val api get() = graph.api

    fun open(item: Item, anchor: Rect, resume: Boolean = false) {
        if (busy || !eligible(item)) return
        graph.sessions.current?.let {
            admin = it.isAdmin
            canRefresh = it.canRefreshMetadata
            canDelete = it.canDeleteMedia
        }
        target = MenuTarget(item, anchor, resume)
        // Permissions can change while signed in; the visible menu follows the fresh answer.
        scope.launch {
            runCatching { api.currentUser() }.onSuccess {
                admin = it.isAdmin
                canRefresh = it.canRefreshMetadata
                canDelete = it.canDeleteMedia
            }
        }
    }

    fun dismiss() {
        target = null
        popup = null
    }

    fun show(anchor: Rect, choices: List<PopupChoice>) {
        if (choices.isNotEmpty()) popup = GlassPopup(anchor, choices)
    }

    internal fun entriesFor(t: MenuTarget): List<Entry> {
        val item = t.item
        val managed = MANAGED_ID.matches(item.id)
        return buildList {
            add(
                if (item.userData.isFavorite) Entry(ItemAction.Favorite, "取消收藏", Icons.Rounded.Favorite)
                else Entry(ItemAction.Favorite, "收藏", Icons.Rounded.FavoriteBorder),
            )
            if (item.type != "Audio") {
                add(
                    if (item.userData.played) Entry(ItemAction.Played, "标记为未看", Icons.Rounded.RemoveDone)
                    else Entry(ItemAction.Played, "标记为已看", Icons.Rounded.Done),
                )
            }
            if (managed && (admin || canRefresh)) add(Entry(ItemAction.Refresh, "刷新", Icons.Rounded.Refresh))
            if (managed && admin) {
                add(Entry(ItemAction.Metadata, "编辑元数据", Icons.Rounded.Edit))
                add(Entry(ItemAction.Images, "编辑图片", Icons.Rounded.Image))
                if (item.type in VIDEO_TYPES) add(Entry(ItemAction.Previews, "提取缩略图", Icons.Rounded.Slideshow))
            }
            if (managed && (admin || canDelete)) add(Entry(ItemAction.Delete, "删除", Icons.Rounded.Delete, danger = true))
            // 用户口径（2026-11-23）：投屏从播放器移到这里（视频类才显示）
            if (item.type in VIDEO_TYPES) add(Entry(ItemAction.Cast, "投屏", Icons.Rounded.Cast))
            if (t.resume) add(Entry(ItemAction.HideResume, "从继续观看移除", Icons.Rounded.VisibilityOff))
        }
    }

    internal fun choose(action: ItemAction) {
        val t = target ?: return
        val item = t.item
        target = null
        when (action) {
            ItemAction.Favorite -> perform(if (item.userData.isFavorite) "已取消收藏" else "已收藏", item.id) {
                api.setFavorite(item.id, !item.userData.isFavorite)
            }
            ItemAction.Played -> perform(if (item.userData.played) "已标记为未看" else "已标记为已看", item.id) {
                api.setPlayed(item.id, !item.userData.played)
            }
            ItemAction.HideResume -> perform("已从继续观看移除", item.id) { api.hideFromResume(item.id) }
            ItemAction.Previews -> perform("缩略图任务已提交，已有缩略图自动跳过", null) { api.extractPreviews(listOf(item.id)) }
            ItemAction.Refresh -> dialog = ItemDialog.Refresh(item)
            ItemAction.Metadata -> dialog = ItemDialog.Metadata(item)
            ItemAction.Images -> dialog = ItemDialog.Images(item)
            ItemAction.Delete -> dialog = ItemDialog.Delete(item)
            ItemAction.Cast -> dialog = ItemDialog.Cast(item)
        }
    }

    /** One request at a time; errors become the notice instead of a crash. */
    private fun perform(success: String, changed: String?, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
                notice = success
                changed?.let { changed(it) }
            } catch (error: Exception) {
                notice = error.userMessage()
            } finally {
                busy = false
            }
        }
    }

    fun changed(id: String, deleted: Boolean = false) {
        graph.itemChanges.tryEmit(ItemChange(id, deleted))
    }

    /** Submits the refresh, then follows the server task so lists repaint when it is done. */
    fun refresh(item: Item, action: String) {
        dialog = null
        scope.launch {
            val result = try {
                api.refreshItem(item.id, action)
            } catch (error: Exception) {
                notice = error.userMessage()
                return@launch
            }
            if (result.str("reason") == "task_already_running") {
                notice = "已有刷新任务在运行，请稍后重试"
                return@launch
            }
            notice = "元数据刷新任务已提交"
            val run = result.long("RefreshRunId")
            if (run <= 0) return@launch
            repeat(REFRESH_POLLS) {
                delay(5_000)
                val status = runCatching { api.refreshStatus(item.id, run) }.getOrNull() ?: return@repeat
                if (status.bool("finished")) {
                    notice = if (status.str("status") == "succeeded") "「${item.name}」已刷新" else "刷新任务已结束（${status.str("status")}）"
                    changed(item.id)
                    return@launch
                }
            }
        }
    }

    companion object {
        private val MANAGED_ID = Regex("^item-[1-9]\\d*$")
        private val ELIGIBLE = setOf("Movie", "Series", "Season", "Episode", "Video", "Audio", "MusicVideo", "BoxSet")
        val VIDEO_TYPES = setOf("Movie", "Series", "Season", "Episode", "Video")
        private const val REFRESH_POLLS = 120

        fun eligible(item: Item): Boolean = item.id.isNotEmpty() && item.type in ELIGIBLE
    }
}

/**
 * Tracks where a card sits so the menu can grow out of it. Use [modifier] on the card and call
 * [open] from its long click.
 */
@Stable
class ItemPress internal constructor() {
    internal var menu: ItemMenu? = null
    internal var item: Item? = null
    internal var resume = false
    private var bounds = Rect.Zero
    val modifier: Modifier = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }

    val enabled: Boolean get() = menu != null && item?.let(ItemMenu::eligible) == true

    fun open() {
        val target = item ?: return
        menu?.open(target, bounds, resume)
    }
}

/** Anchor for a glass popup: put [modifier] on the button, call [open] from its click. */
@Stable
class PopupAnchor internal constructor() {
    internal var menu: ItemMenu? = null
    private var bounds = Rect.Zero
    val modifier: Modifier = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() }

    fun open(choices: List<PopupChoice>) {
        menu?.show(bounds, choices)
    }
}

@Composable
fun rememberPopupAnchor(): PopupAnchor {
    val anchor = remember { PopupAnchor() }
    anchor.menu = LocalItemMenu.current
    return anchor
}

@Composable
fun rememberItemPress(item: Item, resume: Boolean = false): ItemPress {
    val press = remember { ItemPress() }
    press.menu = LocalItemMenu.current
    press.item = item
    press.resume = resume
    return press
}

/**
 * Drawn above the whole shell (root coordinates = window): a light scrim, the glass menu growing
 * out of the pressed card, the editors it opens, and a glass notice pill for results.
 */
@Composable
fun ItemMenuHost(menu: ItemMenu, haze: HazeState?) {
    val target = menu.target
    // Keeps the last target on screen while the exit animation runs.
    val last = remember { arrayOfNulls<MenuTarget>(1) }
    if (target != null) last[0] = target
    val current = target ?: last[0]

    val popup = menu.popup
    val lastPopup = remember { arrayOfNulls<GlassPopup>(1) }
    if (popup != null) lastPopup[0] = popup
    val shownPopup = popup ?: lastPopup[0]

    BackHandler(enabled = target != null || popup != null) { menu.dismiss() }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(target != null || popup != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menu.dismiss() },
            )
        }
        if (current != null) {
            MenuPlacement(current.anchor) {
                val origin = TransformOrigin(0.5f, 0f)
                AnimatedVisibility(
                    target != null,
                    enter = fadeIn() + scaleIn(initialScale = 0.85f, transformOrigin = origin),
                    exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = origin),
                ) {
                    MenuCard(current, menu, haze)
                }
            }
        }
        if (shownPopup != null) {
            MenuPlacement(shownPopup.anchor, below = true) {
                val origin = TransformOrigin(1f, 0f)
                AnimatedVisibility(
                    popup != null,
                    enter = fadeIn() + scaleIn(initialScale = 0.85f, transformOrigin = origin),
                    exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = origin),
                ) {
                    PopupCard(shownPopup, menu, haze)
                }
            }
        }
        menu.notice?.let { message ->
            LaunchedEffect(message) {
                delay(3_000)
                if (menu.notice == message) menu.notice = null
            }
            Text(
                message,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 12.dp, start = 24.dp, end = 24.dp)
                    .glass(Corner.pill, elevation = 10.dp, state = haze)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
    }
    when (val d = menu.dialog) {
        is ItemDialog.Refresh -> RefreshDialog(onDismiss = { menu.dialog = null }) { menu.refresh(d.item, it) }
        is ItemDialog.Delete -> DeleteDialog(d.item, menu)
        is ItemDialog.Cast -> CastSheet(d.item, menu)
        is ItemDialog.Metadata -> MetadataSheet(d.item, menu)
        is ItemDialog.Images -> ImageSheet(d.item, menu)
        null -> Unit
    }
}

/**
 * Item menus centre on the card a third of the way down it; popups ([below]) drop from the
 * anchor's bottom edge, end-aligned, or rise above it when there is no room. Always inside the
 * safe area.
 */
@Composable
private fun MenuPlacement(anchor: Rect, below: Boolean = false, content: @Composable () -> Unit) {
    val safe = WindowInsets.safeDrawing
    Layout(content, Modifier.fillMaxSize()) { measurables, constraints ->
        val margin = 12.dp.roundToPx()
        val left = safe.getLeft(this, layoutDirection) + margin
        val top = safe.getTop(this) + margin
        val right = constraints.maxWidth - safe.getRight(this, layoutDirection) - margin
        val bottom = constraints.maxHeight - safe.getBottom(this) - margin
        val placeable = measurables.firstOrNull()?.measure(Constraints(maxWidth = (right - left).coerceAtLeast(0), maxHeight = (bottom - top).coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            if (placeable == null) return@layout
            val gap = 4.dp.roundToPx()
            val wantedX = if (below) anchor.right - placeable.width else anchor.center.x - placeable.width / 2f
            val wantedY = when {
                !below -> anchor.top + anchor.height / 3f
                anchor.bottom + gap + placeable.height <= bottom -> anchor.bottom + gap
                else -> anchor.top - gap - placeable.height
            }
            val x = wantedX.toInt().coerceIn(left, (right - placeable.width).coerceAtLeast(left))
            val y = wantedY.toInt().coerceIn(top, (bottom - placeable.height).coerceAtLeast(top))
            placeable.place(IntOffset(x, y))
        }
    }
}

@Composable
private fun MenuCard(target: MenuTarget, menu: ItemMenu, haze: HazeState?) {
    val entries = menu.entriesFor(target)
    val rowShape = Corner.inPanel(6.dp)
    Column(Modifier.width(MenuWidth).glass(Corner.panel, elevation = 16.dp, state = haze).padding(vertical = 6.dp)) {
        Text(
            target.item.seriesName?.takeIf { target.item.isEpisode }?.let { "$it · ${target.item.name}" } ?: target.item.name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        entries.forEach { entry ->
            val tint = if (entry.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(rowShape).clickable { menu.choose(entry.action) }
                    .padding(horizontal = 12.dp, vertical = RowPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(entry.icon, null, tint = tint, modifier = Modifier.size(MenuIcon))
                Spacer(Modifier.width(12.dp))
                Text(entry.label, style = MaterialTheme.typography.bodyMedium, color = tint)
            }
        }
    }
}

@Composable
private fun PopupCard(popup: GlassPopup, menu: ItemMenu, haze: HazeState?) {
    val rowShape = Corner.inPanel(6.dp)
    // Long pickers (dozens of seasons) scroll inside the card, opening at the selected row.
    val scroll = rememberScrollState()
    var selectedTop by remember { mutableIntStateOf(-1) }
    LaunchedEffect(selectedTop) { if (selectedTop > 0) scroll.scrollTo(selectedTop) }
    Column(
        Modifier.widthIn(min = 148.dp, max = 240.dp).width(IntrinsicSize.Max)
            .glass(Corner.panel, elevation = 16.dp, state = haze)
            .verticalScroll(scroll).padding(vertical = 6.dp),
    ) {
        popup.choices.forEach { choice ->
            val tint = if (choice.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Row(
                Modifier.fillMaxWidth()
                    .then(if (choice.selected) Modifier.onPlaced { selectedTop = it.positionInParent().y.toInt() } else Modifier)
                    .padding(horizontal = 6.dp).clip(rowShape)
                    .clickable { menu.dismiss(); choice.onClick() }
                    .padding(horizontal = 12.dp, vertical = RowPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (choice.avatarUrl != null) {
                    xyz.etkn.mobile.ui.components.Artwork(
                        choice.avatarUrl,
                        Modifier.size(MenuAvatar).clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)),
                        choice.label,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    Spacer(Modifier.width(12.dp))
                } else {
                    choice.icon?.let {
                        Icon(it, null, tint = tint, modifier = Modifier.size(MenuIcon))
                        Spacer(Modifier.width(12.dp))
                    }
                }
                Text(choice.label, style = MaterialTheme.typography.bodyMedium, color = tint, modifier = Modifier.weight(1f))
                choice.trailing?.let {
                    Spacer(Modifier.width(12.dp))
                    Icon(it, null, tint = tint, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// Compact rows: 40 dp tall (the minimum comfortable touch height), small icon and body text.
private val MenuWidth = 200.dp
private val MenuIcon = 18.dp
/** 账号头像尺寸：比通用图标小一点，不撑高行高。 */
private val MenuAvatar = 20.dp
private val RowPadding = 11.dp


/**
 * 用户口径（2026-11-23）：投屏从播放器移到详情页三点菜单。
 * 实现复用播放器那套：DLNA 发现设备 → 取该条目的可播放直链 → 推给设备。
 * 这里和播放器不同：播放器有正在播的 source，详情页没有，所以要先取 playbackInfo。
 */
@Composable
private fun CastSheet(item: Item, menu: ItemMenu) {
    val graph = LocalContext.current.graph
    val session = graph.sessions.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<CastDevice>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(round) {
        devices = null
        devices = runCatching { graph.cast.discover() }.getOrDefault(emptyList())
    }

    /**
     * 用户口径（2026-11-28，#198）**投屏界面压根没做好**。
     *
     * 用户原话：
     * ```
     * 「app有投屏功能你知道吗，我点投屏，那个投屏界面压根就没做好像，
     *   投屏俩字集中在屏幕的左上角，也没有弹窗界面」
     * ```
     *
     * ## 根因
     *
     * `CastSheet` 是 `@Composable`，但**没套任何容器**就直接输出内容：
     * ```kotlin
     * Text("投屏", ...)          ← 裸 Text
     * when (devices) { ... }     ← 裸列表
     * ```
     * 于是它被当成普通内容画在页面流里 —— 标题贴左上角、没有弹窗底、
     * 没有遮罩、也没法点击外部关闭。
     *
     * 对照同一个 `when (menu.dialog)` 分发的其他几个就明白了：
     * ```
     * MetadataSheet → GlassSheet(onDismissRequest = ...) { ... }   ✅
     * ImageSheet    → GlassSheet(...) { ... }                      ✅
     * CastSheet     → （什么都没有）                                ❌
     * ```
     *
     * ## 现在
     *
     * 照 `MetadataSheet` 的结构补上 `GlassSheet` 容器，标题也移进容器内，
     * 与「编辑元数据」那张保持一致的内边距和标题样式。
     */
    GlassSheet(onDismissRequest = { if (!busy) menu.dialog = null }) {
      Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
    Text("投屏", style = MaterialTheme.typography.titleLarge)

    when (val found = devices) {
        null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            /** 全 App 统一用三点跳动加载（#183 用户选的第 2 套）。 */
            EtknLoading()
        }
        else -> {
            if (found.isEmpty()) {
                Text(
                    "没有找到投屏设备",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(found.size) { index ->
                    val device = found[index]
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !busy) {
                                if (session == null) return@clickable
                                busy = true
                                scope.launch {
                                    val source: xyz.etkn.mobile.data.model.MediaSource? =
                                        runCatching { graph.api.playbackInfo(item.id).sources.firstOrNull() }.getOrNull()
                                    val url: String? = source?.let { src ->
                                        runCatching {
                                            xyz.etkn.mobile.player.PlaybackAddress.portable(
                                                src, session.server, session.token, session.userId,
                                            )
                                        }.getOrNull()
                                    }
                                    if (url == null) {
                                        menu.notice = "取播放地址失败"
                                    } else {
                                        val title = item.seriesName?.takeIf { item.isEpisode } ?: item.name
                                        runCatching { graph.cast.play(device, url, title, item.userData.playbackPositionTicks / 10_000) }
                                            .onSuccess { menu.notice = "已投屏到 ${device.name}" }
                                            .onFailure { menu.notice = it.message ?: "投屏失败" }
                                    }
                                    busy = false
                                    menu.dialog = null
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Cast, null)
                        Spacer(Modifier.width(12.dp))
                        Text(device.name)
                    }
                }
            }
            TextButton(onClick = { round++ }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("重新搜索") }
        }
    }
      }
    }
}
