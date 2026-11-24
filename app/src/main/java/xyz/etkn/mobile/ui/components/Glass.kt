package xyz.etkn.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.ExperimentalHazeApi
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.Corner

/**
 * Blur source behind the app chrome (dock, mini player); null means "no blur, paint flat".
 * Bars that float *inside* a screen cannot sample it — Haze skips sources that are ancestors of
 * the effect — so such screens wrap their scrolling content in [glassSource] with their own
 * [rememberHazeState] and pass it to [glass] explicitly.
 */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

fun Modifier.glassSource(state: HazeState?): Modifier = if (state == null) this else hazeSource(state)

/** How far every glass layer blurs what is behind it: in-app (Haze) and behind dialog windows alike. */
val GlassBlur = 40.dp

/**
 * Glass surface: real backdrop blur (Android 12+ via Haze, translucent fallback below), a soft
 * drop shadow and a faint top highlight.
 */
@OptIn(ExperimentalHazeApi::class)
@Composable
fun Modifier.glass(
    shape: Shape,
    elevation: Dp = 12.dp,
    blur: Dp = GlassBlur,
    border: Boolean = true,
    tint: Color? = null,
    state: HazeState? = LocalHaze.current,
): Modifier {
    val tokens = Etkn.tokens
    val surface = MaterialTheme.colorScheme.surface
    var m = this
    if (elevation > 0.dp) {
        m = m.shadow(elevation, shape, clip = false, ambientColor = tokens.glassShadow, spotColor = tokens.glassShadow)
    }
    m = m.clip(shape)
    m = if (state == null) {
        m.background(tint ?: tokens.glassFlat, shape)
    } else {
        m.hazeEffect(
            state = state,
            style = HazeStyle(
                backgroundColor = surface,
                tints = listOf(
                    HazeTint(tint ?: tokens.glassTint),
                    HazeTint(Brush.verticalGradient(0f to tokens.glassHighlight, 0.2f to Color.Transparent)),
                ),
                blurRadius = blur,
                noiseFactor = 0.06f,
                fallbackTint = HazeTint(tint ?: tokens.glassFlat),
            ),
        ) {
            // Blur a downscaled copy: at this radius it looks the same and costs a fraction,
            // which matters while a page turn changes what is behind the glass every frame.
            inputScale = HazeInputScale.Auto
        }
    }
    if (border) m = m.border(1.dp, tokens.glassBorder, shape)
    return m
}

/**
 * One floating control of a split top bar (each control its own small glass shape). [fraction]
 * fades the glass in, for bars that start clear over artwork; [haze] is the page's own source.
 */
@Composable
fun GlassPiece(haze: HazeState, shape: Shape, modifier: Modifier = Modifier, fraction: Float = 1f, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (fraction > 0f) {
            Box(Modifier.matchParentSize().graphicsLayer { alpha = fraction }.glass(shape, elevation = 8.dp, state = haze))
        }
        content()
    }
}

/**
 * Back button over artwork that turns into a glass pill with the title once the page scrolls;
 * [actions] (usually [ArtworkIconButton]s, told whether they sit on glass) go to the right.
 * [haze] must be the screen-local source wrapping that page's scrolling content.
 */
@Composable
fun GlassBackBar(
    title: String,
    collapsed: Boolean,
    haze: HazeState,
    onBack: () -> Unit,
    actions: @Composable RowScope.(onGlass: Boolean) -> Unit = {},
) {
    val shape = Corner.pill
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Etkn.space.screen, vertical = 6.dp)
            .then(if (collapsed) Modifier.glass(shape, elevation = 8.dp, state = haze) else Modifier)
            .height(52.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ArtworkBackButton(onBack, onGlass = collapsed)
        Text(
            if (collapsed) title else "",
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
        )
        actions(collapsed)
    }
}

/** Top padding that keeps scrolling content clear of a floating [GlassBackBar] at rest. */
@Composable
fun glassBarClearance(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 68.dp

@Composable
fun ArtworkBackButton(onBack: () -> Unit, modifier: Modifier = Modifier, onGlass: Boolean = false) =
    ArtworkIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "返回", onBack, modifier, onGlass)

/** Round button over artwork (dark veil, white icon) that goes flat once it sits on glass. */
@Composable
fun ArtworkIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onGlass: Boolean = false,
    /**
     * 用户口径（2026-11-26）**状态高亮**。
     *
     * 有些图标按钮是「开关」语义（收藏、已播放）：光换图标形状不够，
     * 小尺寸下空心/实心差别不明显。highlighted = true 时给一层主题色圆底 + 主题色图标，
     * 一眼就能看出当前是「已开启」状态。
     */
    highlighted: Boolean = false,
) {
    val colors = when {
        highlighted -> IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        )
        onGlass -> IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.Transparent)
        else -> IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.35f),
            contentColor = Color.White,
        )
    }
    FilledTonalIconButton(onClick = onClick, modifier = modifier, colors = colors) {
        Icon(icon, description)
    }
}
