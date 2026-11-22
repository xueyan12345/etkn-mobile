package xyz.etkn.mobile.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.session.SavedAccount
import xyz.etkn.mobile.data.session.SavedAccount
import xyz.etkn.mobile.ui.actions.PopupChoice
import xyz.etkn.mobile.ui.actions.PopupChoice
import xyz.etkn.mobile.ui.actions.rememberPopupAnchor
import xyz.etkn.mobile.ui.actions.rememberPopupAnchor
import xyz.etkn.mobile.ui.components.glass
import xyz.etkn.mobile.ui.theme.Etkn
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import xyz.etkn.mobile.ui.theme.Corner

/**
 * Floating glass dock of the tab pager: a bar along the bottom, or a slim column at the side of
 * wide windows ([vertical]). The selected tab sits in one tonal pill that follows the pager.
 *
 * Tap a tab, or slide a finger along the dock: the pill and the pages follow the finger, and on
 * release they settle on the nearest tab (a flick goes on to the next one). Between two tabs the
 * pill stretches along the dock and slims across it, so it reads as one drop moving over.
 */
@Composable
internal fun Dock(
    tabs: List<TopLevel>,
    current: TopLevel?,
    nav: AppNav,
    pager: PagerState,
    vertical: Boolean,
    modifier: Modifier = Modifier,
    /** 用户口径（2026-11-22）：长按「我的」快捷切号所需数据与动作。 */
    storedAccounts: List<SavedAccount> = emptyList(),
    activeAccountKey: String? = null,
    onSwitchAccount: suspend (SavedAccount) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val spring = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val pillShape = Corner.inPanel(6.dp)
    val last = (tabs.size - 1).coerceAtLeast(0).toFloat()
    val position = { (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, last) }
    var held by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (held) 1.06f else 1f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "dockLift")
    val frame = if (vertical) Modifier.width(Etkn.space.sideDock) else Modifier.fillMaxWidth().height(68.dp)
    BoxWithConstraints(modifier.then(frame).glass(Corner.panel, elevation = 14.dp).padding(6.dp)) {
        val slot: Dp = if (vertical) SIDE_SLOT else maxWidth / tabs.size.coerceAtLeast(1)
        val slotPx = with(LocalDensity.current) { slot.toPx() }
        val drag = rememberDraggableState { delta ->
            val page = pager.layoutInfo.pageSize + pager.layoutInfo.pageSpacing
            if (page > 0) pager.dispatchRawDelta(delta * page / slotPx)
        }
        val gestures = Modifier.draggable(
            drag,
            if (vertical) Orientation.Vertical else Orientation.Horizontal,
            onDragStarted = { held = true },
            onDragStopped = { velocity ->
                held = false
                val at = position()
                val target = when {
                    velocity > FLICK -> ceil(at)
                    velocity < -FLICK -> floor(at)
                    else -> at.roundToInt().toFloat()
                }.toInt().coerceIn(0, last.toInt())
                pager.animateScrollToPage(target, animationSpec = spring)
            },
        )
        if (current != null) {
            Box(
                // Read in layout and draw only: the pill tracks the pager (finger or spring) frame by frame.
                Modifier.graphicsLayer {
                    val at = position()
                    val stretch = sin(PI * abs(at - floor(at))).toFloat()
                    val along = lift * (1f + 0.3f * stretch)
                    val across = lift * (1f - 0.1f * stretch)
                    transformOrigin = TransformOrigin.Center
                    if (vertical) {
                        translationY = slotPx * at
                        scaleY = along
                        scaleX = across
                    } else {
                        translationX = slotPx * at
                        scaleX = along
                        scaleY = across
                    }
                }
                    .then(if (vertical) Modifier.size(Etkn.space.sideDock - 12.dp, slot) else Modifier.width(slot).fillMaxHeight())
                    .padding(3.dp)
                    .background(colors.secondaryContainer, pillShape),
            )
        }
        val items: @Composable (Modifier) -> Unit = { itemModifier ->
            tabs.forEach { tab ->
                val active = tab == current
                val tint by animateColorAsState(if (active) colors.onSecondaryContainer else colors.onSurfaceVariant, label = "dockTint")
                // 用户口径（2026-11-22）：长按底栏「我的」弹快捷切号浮层（不跳页，就地切）。
                val accountMenu = if (tab == TopLevel.Me) rememberPopupAnchor() else null
                val onLongClickTab: (() -> Unit)? = if (accountMenu != null && storedAccounts.size > 1) {
                    {
                        accountMenu.open(
                            storedAccounts.map { acc ->
                                PopupChoice(
                                    label = acc.userName,
                                    icon = Icons.Rounded.Person,
                                    selected = acc.key == activeAccountKey,
                                    trailing = if (acc.sealedToken == null) Icons.Rounded.Lock else null,
                                    onClick = { scope.launch { runCatching { onSwitchAccount(acc) } } },
                                )
                            },
                        )
                    }
                } else null
                Column(
                    itemModifier.then(accountMenu?.modifier ?: Modifier)
                        .clip(pillShape)
                        .combinedClickable(
                            role = Role.Tab,
                            onClickLabel = tab.label,
                            onLongClickLabel = if (onLongClickTab != null) "切换账号" else null,
                            onLongClick = onLongClickTab,
                            onClick = { nav.top(tab) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (active) tab.selectedIcon else tab.icon, null, tint = tint)
                    Text(
                        tab.label, color = tint, maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        if (vertical) {
            Column(gestures.selectableGroup()) { items(Modifier.size(Etkn.space.sideDock - 12.dp, slot)) }
        } else {
            Row(gestures.fillMaxSize().selectableGroup()) { items(Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

private val SIDE_SLOT = 64.dp

/** px/s past which a released slide goes on to the next tab. */
private const val FLICK = 600f
