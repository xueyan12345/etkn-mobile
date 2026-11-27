package xyz.etkn.mobile.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Semantic colours that Material roles do not cover: scrims over artwork, glass layers,
 * badges. Every derivation lives here; components only read tokens.
 *
 * Scrims over images stay dark in both themes (white text over a bright poster must stay
 * readable); glass layers flip with the theme.
 */
@Immutable
data class EtknTokens(
    val isDark: Boolean,
    val onImage: Color,
    val onImageMuted: Color,
    val onImageTrack: Color,
    val glassTint: Color,
    val glassFlat: Color,
    val glassBorder: Color,
    val glassHighlight: Color,
    val glassShadow: Color,
    val heroTopScrim: Brush,
    val heroBottomScrim: Brush,
    val heroSideScrim: Brush,
    val posterScrim: Brush,
    val wideScrim: Brush,
    val detailScrim: Brush,
    /**
     * 用户口径（2026-11-26）方案 D：详情页信息区的**深色渐变托底**。
     *
     * 不用黑框/圆角底板（用户嫌「大黑框难看」），改成从底往上渐变的遮罩：
     *  · 底部够黑（0.92）→ 任何亮色海报上文字都清楚
     *  · 用 1.5 次方曲线往上淡出到完全透明 → 顶部没有硬边，齐边融入画面
     */
    val detailPlateScrim: Brush,
    val skeleton: Color,
    val rating: Color,
)

fun etknTokens(scheme: ColorScheme, dark: Boolean): EtknTokens = EtknTokens(
    isDark = dark,
    onImage = Color.White,
    onImageMuted = Color.White.copy(alpha = 0.78f),
    onImageTrack = Color.White.copy(alpha = 0.26f),
    glassTint = scheme.surfaceContainer.copy(alpha = if (dark) 0.5f else 0.56f),
    glassFlat = scheme.surfaceContainer.copy(alpha = if (dark) 0.94f else 0.96f),
    glassBorder = scheme.outlineVariant.copy(alpha = if (dark) 0.42f else 0.55f),
    glassHighlight = Color.White.copy(alpha = if (dark) 0.07f else 0.5f),
    glassShadow = Color.Black.copy(alpha = if (dark) 0.55f else 0.18f),
    heroTopScrim = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.62f),
        0.5f to Color.Black.copy(alpha = 0.22f),
        1f to Color.Transparent,
    ),
    heroBottomScrim = Brush.verticalGradient(
        0f to Color.Transparent,
        0.55f to scheme.surface.copy(alpha = 0.78f),
        1f to scheme.surface,
    ),
    heroSideScrim = Brush.horizontalGradient(
        0f to scheme.surface.copy(alpha = 0.86f),
        0.45f to scheme.surface.copy(alpha = 0.35f),
        0.75f to Color.Transparent,
    ),
    posterScrim = Brush.verticalGradient(
        0f to Color.Transparent,
        0.45f to Color.Transparent,
        0.7f to Color.Black.copy(alpha = 0.55f),
        1f to Color.Black.copy(alpha = 0.86f),
    ),
    wideScrim = Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.28f),
        0.35f to Color.Transparent,
        0.6f to Color.Transparent,
        1f to Color.Black.copy(alpha = 0.66f),
    ),
    // 用户口径（2026-11-23）：详情页背景之前发虚、雾蒙蒙像盖了层白罩。
    // 原因：旧配置在 45% 处就叠了 0.5 不透明度的主题色，整张背景图被糊掉。
    // 现在：中上部完全透明（背景图原样清晰），只在底部 55% 之后开始过渡到页面底色，
    // 保证下面的文字/按钮可读。
    /**
     * 方案 D：详情页信息区渐变托底。
     * 归一化坐标是从上到下（0 = 渐变区顶部，1 = 底部）。
     * 顶部完全透明 → 中部快速加深 → 底部 0.92 黑，形成没有硬边的托底。
     */
    detailPlateScrim = Brush.verticalGradient(
        0.00f to Color.Transparent,
        0.22f to Color.Black.copy(alpha = 0.28f),
        0.45f to Color.Black.copy(alpha = 0.58f),
        0.68f to Color.Black.copy(alpha = 0.80f),
        0.85f to Color.Black.copy(alpha = 0.90f),
        1.00f to Color.Black.copy(alpha = 0.94f),
    ),
    detailScrim = Brush.verticalGradient(
        0f to Color.Transparent,
        0.55f to Color.Transparent,
        0.78f to scheme.surface.copy(alpha = 0.72f),
        1f to scheme.surface,
    ),
    skeleton = scheme.surfaceContainerHigh,
    rating = if (dark) Color(0xFFFFC857) else Color(0xFFB07800),
)

val LocalEtknTokens = staticCompositionLocalOf<EtknTokens> { error("EtknTokens not provided") }

object Etkn {
    val tokens: EtknTokens
        @Composable @ReadOnlyComposable get() = LocalEtknTokens.current

    val space = Spacing
}

object Spacing {
    val screen: Dp = 20.dp
    val section: Dp = 28.dp
    val item: Dp = 12.dp

    /**
     * 用户口径（2026-11-27，#100）**整套封面尺寸按参考图 1:1 复刻，0.9 倍率为唯一基准**。
     *
     * 用户原话：
     * ```
     * 「我现在APP设置的封面缩放倍率是0.9，我给你发个参考图，
     *   这个参考图包含集图大小，季图大小，演员图大小。还有首页的各个图大小。
     *   我想让你1:1的复刻我的0.9比例跟他这个一模一样。
     *   然后其他的比例需要你以这个0.9比例为模板。重新计算各个比例的大小」
     *
     * 「得根据我的手机尺寸计算，不能按平板的尺寸算，我的手机是一加13」
     * ```
     *
     * ## 怎么量出来的
     *
     * 参考截图 582×1280（整屏）。
     * 一加 13 = 1440×3168 px，基础 densityDpi 640。
     * **但显示比例是覆盖 densityDpi 实现的**（`UiScale`，见 AppSettings.kt）：
     * ```
     * 0.9 倍率 → densityDpi = 640 × 0.9 = 576 → 1dp = 3.6px → 屏宽 = 400dp
     * 换算系数 K = 400 / 582 = 0.6873 dp/px
     * ```
     * 注意不能按 360dp 算 —— 那等于把「显示比例」当成 1.0，
     * 量出来的值会被 0.9 再缩一次，装上去比参考图小一圈。
     *
     * ## 量到的原始像素 → dp
     * ```
     * 首页海报（2:3）      162 × 243 px → 111.3 × 167.0 dp
     * 海报间距               18 px      →  12.4 dp
     * 详情·季卡            165 × 203 px → 113.4 × 139.5 dp
     * 详情·演员卡          163 × 244 px → 112.0 × 167.7 dp
     * 详情·继续观看横卡     243 × 140 px → 167.0 ×  96.2 dp
     * ```
     * 自洽性验证：首页海报 111×167 与演员卡 112×168 几乎相同 ——
     * 这两个是分别从两张不同截图量的，结果一致说明尺寸没量错。
     *
     * ## 其他倍率怎么办
     *
     * **不需要额外换算**：这些常量是 dp，而「显示比例」改的是 densityDpi，
     * 系统会统一把 dp 缩放成对应像素。所以：
     * ```
     * 0.9 倍率 → 卡片 111dp 宽 → 实际 400px 宽（跟参考图一模一样）
     * 1.0 倍率 → 卡片 111dp 宽 → 实际 444px 宽（等比放大，比例不变）
     * 0.8 倍率 → 卡片 111dp 宽 → 实际 356px 宽
     * ```
     * 所有倍率下比例关系都不会变 —— 这就是用户要的「以 0.9 为模板重新计算」。
     */
    val posterWidth: Dp = 112.dp

    /**
     * 平板也用同一套（用户口径 #101）：
     * 「平板的比例我也想按这个走」+「没事，多出空位没事，只要大小一样就行」
     *
     * 原来平板单独一套大值（150dp），现在跟手机共用，多出来的横向空间就留白。
     */
    val posterWidthWide: Dp = 112.dp

    /** 海报间距：参考图量到 18px → 12.4dp。 */
    val posterGap: Dp = 12.dp

    /** 继续观看横卡：参考图量到 243×140px → 167×96dp（16:9 = 170×96，取实测 167）。 */
    val wideCard: Dp = 167.dp
    val wideCardHeight: Dp = 96.dp

    /** 平板同一个值（#101）。 */
    val wideCardWide: Dp = 167.dp

    /** 媒体库入口卡：与继续观看同一套尺寸（都是横版 16:9）。 */
    val libraryCard: Dp = 167.dp
    val libraryCardWide: Dp = 167.dp

    /** 详情页季卡：165×203px → 113×140dp。 */
    val seasonCard: Dp = 113.dp
    val seasonCardHeight: Dp = 140.dp

    /**
     * 详情页演员卡：163×244px → 112×168dp（含姓名文字，不含则高约 150dp）。
     * 参考图里三张演员卡一行，卡片是竖版人像。
     */
    val castCard: Dp = 112.dp
    val castCardHeight: Dp = 168.dp

    /** 章节卡 / 分集卡（横版 16:9）：跟演员卡同宽。 */
    val episodeCard: Dp = 112.dp

    val dockBottomInset: Dp = 104.dp
    /** Tablet side dock: its width, and the space tab pages leave for it (margin + dock + gap). */
    val sideDock: Dp = 76.dp
    val sideDockInset: Dp = 92.dp
    val miniPlayer: Dp = 64.dp
}

/** Content swaps (a page's data arriving, one episode replacing another): the old fades out, then the new fades in. */
object Motion {
    const val FADE_OUT = 180
    const val FADE_IN = 320
}

/**
 * The one corner scale. Every rounded thing uses one of these by what it is, never a radius of
 * its own: [small] for thumbnails under ~64 dp, badges and tags; [card] for artwork, rows and
 * fields; [panel] for surfaces that hold other things (groups, sheets, dialogs, menus, the dock,
 * the mini player); [pill] for buttons, toggles and bars. Something set inside a panel near its
 * edge takes [inPanel] so the two curves share a centre.
 */
object Corner {
    val panelRadius: Dp = 28.dp
    val small = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(16.dp)
    val panel = RoundedCornerShape(panelRadius)
    val pill = CircleShape

    /** Concentric with a [panel] whose edge is [gap] away. */
    fun inPanel(gap: Dp) = RoundedCornerShape((panelRadius - gap).coerceAtLeast(0.dp))
}
