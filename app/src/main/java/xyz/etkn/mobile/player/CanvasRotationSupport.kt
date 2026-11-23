package xyz.etkn.mobile.player

import android.os.Build

/**
 * 用户口径（2026-11-23）**画布旋转兜底只对特定机型开放**。
 *
 * 背景：
 *  · 绝大多数设备（手机、普通平板）系统方向完全正常，requestedOrientation 直接就生效，
 *    根本不需要旋转画面。
 *  · 但 OPPO Pad Mini（OPD2515，Android 16）这类大屏设备，系统默认忽略方向请求，
 *    只能靠「TextureView + 整层 90° 旋转」才能横屏观看。
 *
 * 为什么必须严格门控：
 *  这套兜底一旦在正常设备上被误触发，就会把本该满屏的画面
 *  压成中间一个小方块（用户实测：手机播放变成中央小窗）。
 *  所以判定必须同时满足：
 *    ① 机型是我们确认需要兜底的那台
 *    ② 系统确实没把方向转过去（调用方另行判断）
 *
 * 新增机型只需往 [CANVAS_ROTATION_MODELS] 里加一条，不用动播放器逻辑。
 */
object CanvasRotationSupport {

    /**
     * 需要「画布旋转兜底」的机型清单。
     *
     * 判定用 Build.MODEL（用户看到的机型名）+ Build.DEVICE 双保险：
     *  · OPD2515 = OPPO Pad Mini 的型号代码（用户日志实测确认）
     *  · 部分 ROM 的 MODEL 会写成市场名，所以两个都比
     */
    private val CANVAS_ROTATION_MODELS = setOf(
        "OPD2515",        // OPPO Pad Mini（用户设备，日志确认）
        "OPPO Pad Mini",  // 市场名兜底
    )

    /**
     * 这台设备是否允许使用「画布 90° 旋转」兜底。
     *
     * 注意：这里只回答「机型是否在白名单」，不代表现在就该转。
     * 实际是否旋转由播放器再判断「系统方向有没有转成功」。
     */
    val enabled: Boolean by lazy {
        val model = Build.MODEL.orEmpty().trim()
        val device = Build.DEVICE.orEmpty().trim()
        val matched = CANVAS_ROTATION_MODELS.any {
            it.equals(model, ignoreCase = true) || it.equals(device, ignoreCase = true)
        }
        android.util.Log.i(
            "ETKN-Rotate",
            "device gate: model=$model device=$device canvasRotation=$matched",
        )
        matched
    }
}
