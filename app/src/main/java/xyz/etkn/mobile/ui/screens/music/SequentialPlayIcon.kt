package xyz.etkn.mobile.ui.screens.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 「顺序播放」图标：两条同向的横向箭头。
 *
 * ## 用户口径（2026-11-27，#65）**三个箭头改成两个，三个有点挤**
 *
 * 用户原话：
 * ```
 * 「左下角点三个箭头你给他变成两个箭头，3个箭头有点挤」
 * ```
 *
 * 演进过程：
 * ```
 * 第 1 版：y=5/12/19 三条，铺满 24 视口 → 用户「太丑，跟其他3个不是一套，比它们高」
 * 第 2 版：y=7/12/17 三条，收进安全区   → 视觉重量对齐了，但用户仍嫌「有点挤」
 * 第 3 版：y=8/16 两条（本版）          → 笔画更舒展，跟 Repeat/Shuffle 一样透气
 * ```
 *
 * 几何规范（对齐 Material Rounded 的 Repeat / RepeatOne / Shuffle）：
 * ```
 * 安全区：y 收进 6~18、x 收进 4~19（同其它三个图标的呼吸感）
 * 两条箭头 y = 8 / 16（间距 8，重心正好在 12）
 * 横线 x: 5 → 14.5；箭头尖 x = 18；斜边 ±3.4（比三条版更饱满）
 * 描边 2f 圆头圆角 —— 与 Material 图标笔画粗细一致
 * ```
 *
 * 验收方法：和 Repeat/RepeatOne/Shuffle 并排截图，
 * 量四者图形外接框高度差 < 1dp、笔画粗细一致、留白比例相近。
 */
val SequentialPlayIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SequentialPlay",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        /** 两条同向右箭头：横线 + 饱满的三角箭头头部。 */
        listOf(8f, 16f).forEach { y ->
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                /** 横线：左边距 5（和 Material 图标左留白一致）。 */
                moveTo(5f, y)
                lineTo(14.5f, y)
                /** 箭头：尖端 (18, y)，两笔斜边长 3.4，张角约 52°。 */
                moveTo(14.5f, y - 3.4f)
                lineTo(18f, y)
                lineTo(14.5f, y + 3.4f)
            }
        }
    }.build()
}
