package xyz.etkn.mobile.ui.screens.music

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 用户口径（2026-11-27，#47）**「顺序播放」图标要和另外三个是一套**。
 *
 * 用户原话：
 *  「3个横向箭头确实是这意思，但是你搞的有点太丑了，
 *    明显跟其他3个它就不是一套，好像就比它仨高，大小好像不太一样」
 *
 * 第一版哪里不对（对照 Material Rounded 的 Repeat/RepeatOne/Shuffle）：
 * ```
 * ① 图形在 y = 5 / 12 / 19 铺满整个 24 视口 → 视觉填满度比其它三个高，
 *    看起来"更高更胖"。Material 图标实际只占 y≈6~18、x≈2~22 的安全区。
 * ② 横线从 x=3 起、箭头到 x=17 收 —— 右边留白过少，视觉重心偏右。
 * ③ 箭头斜边长 3、张角偏窄，和 Repeat 那种饱满的箭头不一致。
 * ```
 *
 * 现在按 Material Rounded 的几何规范重画：
 * ```
 * 安全区：y 收进 6~18，x 收进 4~19（和其它图标同样的呼吸感）
 * 三条箭头 y = 7 / 12 / 17（间距 5，视觉重心居中）
 * 横线 x: 4 → 15，箭头尖 x = 18，斜边 ±2.6（比第一版更饱满）
 * 描边 2f 圆头圆角 —— 与 Material 图标的笔画粗细一致
 * ```
 * 上线验收方法：和 Repeat/RepeatOne/Shuffle 并排截图，
 * 量四者图形外接框高度差应 < 1dp、笔画粗细一致。
 */
val SequentialPlayIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "SequentialPlay",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        /** 三条同向右箭头：横线 + 饱满的三角箭头头部。 */
        listOf(7f, 12f, 17f).forEach { y ->
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                /** 横线：左边距 5（和 Material 图标左留白一致）。 */
                moveTo(5f, y)
                lineTo(14.5f, y)
                /** 箭头：尖端 (18, y)，两笔斜边长 3.5，张角约 50°。 */
                moveTo(14.5f, y - 3.2f)
                lineTo(18f, y)
                lineTo(14.5f, y + 3.2f)
            }
        }
    }.build()
}
