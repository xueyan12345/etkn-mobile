package xyz.etkn.mobile.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize

/** Material window width classes: phone (< 600 dp), small tablet / unfolded (600–839), tablet (≥ 840). */
enum class WindowLayout {
    Compact, Medium, Expanded;

    val wide: Boolean get() = this != Compact
}

val LocalWindowLayout = staticCompositionLocalOf { WindowLayout.Compact }

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
 * 用户口径（2026-11-23）：卡片尺寸不能只用两个固定档（手机 210dp / 宽屏 260dp）。
 * 平板实测继续观看封面明显偏大 —— 固定 dp 在大屏上占屏比例失衡。
 * 这里按窗口宽度算一个合理的卡片宽度，并夹在可用范围内。
 *
 * [base] 手机上想要的宽度；[fraction] 该宽度大约占手机屏宽的比例。
 * 返回值随窗口线性放大，但不超过 [maxWidth]。
 */
@Composable
fun responsiveCardWidth(
    base: androidx.compose.ui.unit.Dp,
    maxWidth: androidx.compose.ui.unit.Dp,
    fractionOfScreen: Float = 0.56f,
): androidx.compose.ui.unit.Dp {
    val screen = windowSizeDp().width
    val desired = screen * fractionOfScreen
    // 宽屏上别太大：至少保证一屏能放下 2.2 张，避免封面占满半屏
    val capped = if (desired > maxWidth) maxWidth else desired
    // 不小于手机上的基准尺寸
    return if (capped < base) base else capped
}
