package xyz.etkn.mobile.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** Material window width classes: phone (< 600 dp), small tablet / unfolded (600–839), tablet (≥ 840). */
enum class WindowLayout {
    Compact, Medium, Expanded;

    /**
     * 用户口径（2026-11-26）：宽屏判定必须同时满足「窗口够宽」和「确实是平板设备」。
     *
     * 只看宽度会让手机横屏被当成平板（侧栏 Dock、内容压窄、分页半页错乱）。
     * 设备形态用 `smallestScreenWidthDp >= 600` 判断：
     * 手机横屏这个值仍是手机的值（通常 360-420），平板恒 ≥ 600。
     */
    val wide: Boolean get() = this != Compact
}

val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.Compact }

/**
 * 用户口径（2026-11-26）**手机永远不按「宽屏」处理**。
 *
 * 原来 `wide` 只看宽度（≥600dp）：
 * 手机横过来宽度就超过 600dp，于是被当成平板 → 出现侧栏 Dock、
 * 内容被压窄、pager 更容易停在半页状态（就是「两个页面并排」那种错乱）。
 *
 * 现在加一层「最小宽度」门槛：只有真正的平板才可能进入宽屏分支。
 * 手机横屏宽度通常 780-900dp 但 **shortestScreenWidthDp < 600**，
 * 平板（含 OPPO Pad Mini）的 shortestScreenWidthDp ≥ 600。
 */
@Composable
fun isTabletDevice(): Boolean {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    return ctx.resources.configuration.smallestScreenWidthDp >= 600
}

/**
 * The window in dp at the density the app actually draws with. Derived from pixels rather than
 * `Configuration.screenWidthDp`, which ignores the in-app page size (an overridden densityDpi).
 */
@Composable
fun windowSizeDp(): DpSize {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { DpSize(size.width.toDp(), size.height.toDp()) }
}

@Composable
fun currentWindowLayout(): WindowLayout {
    val width = windowSizeDp().width.value
    return when {
        width >= 840 -> WindowLayout.Expanded
        width >= 600 -> WindowLayout.Medium
        else -> WindowLayout.Compact
    }
}

/**
 * 用户口径（2026-11-23，平板尺寸彻底重做）：
 *
 * 之前两版都错在同一个地方 —— 用「屏幕宽度 × 固定比例」算卡片宽度：
 *   responsiveCardWidth(base=210, max=300, fraction=0.42) 在 840dp 上算出 352dp(封顶300)
 *   比手机上的 260dp 还大 → 平板看着更挤、一张卡占掉近半屏。
 *
 * 正确算法是反过来：**先定「这一行要放几张」，再拿它反推卡片宽度**。
 * 平板一行放得下更多张，所以卡片应该更小，而不是更大。
 *
 * [base]      手机上一行放 2.2 张时的基准宽度（用于紧凑屏兜底）
 * [minColumns] 宽屏时一行至少放几张（卡片宽度的上限由它决定）
 */
@Composable
fun responsiveCardWidth(
    base: androidx.compose.ui.unit.Dp,
    maxWidth: androidx.compose.ui.unit.Dp,
    fractionOfScreen: Float = 0.56f,
): androidx.compose.ui.unit.Dp {
    val screen = windowSizeDp().width
    val layout = currentWindowLayout()
    // 宽屏：按「一行放几张」反推，绝不比手机基准更宽
    if (layout.wide) {
        // 平板一行放 4 张（手机约 2.2 张）；算上屏幕左右边距与卡片间距的余量
        val columns = if (layout == WindowLayout.Expanded) 4.2f else 3.2f
        val byColumns = screen / columns
        return (if (byColumns < base) byColumns else base).coerceAtLeast(120.dp)
    }
    // 手机：沿用比例算法
    val desired = screen * fractionOfScreen
    val capped = if (desired > maxWidth) maxWidth else desired
    return if (capped < base) base else capped
}

/**
 * 用户口径（2026-11-23）：网格按「一行几张」直接算，而不是靠固定 dp 撞运气。
 * 首页媒体库分类卡、海报行、网格页统一用它，平板才不会出现「第三张被切一半」。
 */
@Composable
fun gridColumns(default: Int): Int {
    val screen = windowSizeDp().width.value
    return when {
        screen >= 1200 -> default + 3
        screen >= 1000 -> default + 2
        screen >= 840 -> default + 1
        screen >= 600 -> default
        else -> default - 1
    }.coerceAtLeast(2)
}

