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
    val posterWidth: Dp = 118.dp
    val posterWidthWide: Dp = 150.dp

    /**
     * 用户口径（2026-11-27，#93）**横版卡与竖版海报「面积一样大」**。
     *
     * 用户原话：
     * ```
     * 「媒体库的图跟下面剧集的图大小一致，只不过是横竖不一样，
     *   继续观看大小不同动。无论怎么修改设置里面的显示比例，他们都应该一样大」
     * 「我说的一样大，是面积一样大」
     * 「相当于横图跟竖图，它不冲突」
     * ```
     *
     * 按面积反推横版宽度（竖版海报 2:3，横版 16:9）：
     * ```
     * 手机：竖版 118×177 = 20886
     *       横版 W = √(20886 × 16/9) = 192.7 → 193×108
     * 宽屏：竖版 150×225 = 33750
     *       横版 W = √(33750 × 16/9) = 244.9 → 245×138
     * ```
     * 原来手机 210、宽屏 260（面积比海报大约 19%/12%），所以看着"继续观看更大"。
     */
    val wideCard: Dp = 193.dp
    val wideCardWide: Dp = 245.dp

    /**
     * 用户口径（#93）**媒体库入口卡也按面积对齐海报**。
     *
     * 媒体库卡是横版（16:9 附近），和继续观看卡同一套面积基准，
     * 这样「媒体库图 / 剧集海报 / 继续观看」三者视觉面积一致。
     */
    val libraryCard: Dp = 193.dp
    val libraryCardWide: Dp = 245.dp
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
