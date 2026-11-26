package xyz.etkn.mobile.ui.screens.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 用户口径（2026-11-27，#43）**「顺序播放」要一个三个横向箭头搭在一起的图标**。
 *
 * 用户原话：
 *  「播放界面左下角的那个不是有4种形态的播放吗，那个顺序播放的按钮，
 *    长得有点像播放列表，你给我换一个，换成那种三个横向的箭头搭在一起的那种，
 *    你上网搜搜，肯定有顺序播放的图标，换一下」
 *
 * 为什么不用 Material 现成的：
 * ```
 * PlaylistPlay  三条横线 + 播放箭头 → 跟"播放列表"按钮撞脸（用户说的就是这个）
 * Repeat        两个箭头成环       → 已被"列表循环"占用
 * RepeatOne     环 + 数字 1        → 已被"单曲循环"占用
 * Shuffle       交叉箭头           → 已被"随机播放"占用
 * LowPriority   箭头 + 三条横线    → 语义是"低优先级"，不是播放顺序
 * ```
 * Material 里没有语义正好是「顺序播放」的图标，所以自绘一个：
 * 三个等距、同向的横向箭头，一眼区分于其它三种。
 *
 * 路径（24×24 视口，纯描边，圆头圆角）：
 * ```
 *       →   y=5
 *       →   y=12
 *       →   y=19
 * ```
 */
val SequentialPlayIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SequentialPlay",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        /** 三条横向箭头：横线 + 右向箭头头部。 */
        listOf(5f, 12f, 19f).forEach { y ->
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(3f, y)
                lineTo(17f, y)
                /** 箭头头部（上下两笔汇到尖端）。 */
                moveTo(13f, y - 3f)
                lineTo(17f, y)
                lineTo(13f, y + 3f)
            }
        }
    }.build()
}
