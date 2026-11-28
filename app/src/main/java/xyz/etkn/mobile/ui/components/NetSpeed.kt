package xyz.etkn.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * 用户口径（2026-11-28，#197）**播放页加载时下方显示网速**（仅视频播放页）。
 *
 * 用户原话：
 * ```
 * 「网速那个你可以参考，播放界面右上角有一个网速」
 * 「播放时候加上网速」→「音乐播放页不加网速」→「就视频播放页加」
 * ```
 *
 * ## 口径和视频播放器右上角完全一致（同一套算法）
 *
 * 之前这段逻辑内联在 `player/PlayerControls.kt` 的 `rememberPlayerStatus()` 里。
 * 抽出来是为了让「加载动画下方的网速」和「右上角网速」
 * 用**同一份实现**，不会出现两个数字对不上。
 *
 * ## 算法（沿用已验证的口径）
 *
 * ```
 * TrafficStats.getUidRxBytes(自己uid)  ← 只算本 App，不混系统其他流量
 * 每秒取差值 → 格式化成 B/s / KB/s / MB/s
 * 没有流量的那一秒显示 "0 B/s" 而不是空串
 *   （否则界面会「有时显示有时不显示」）
 * ```
 *
 * 用 `getUidRxBytes` 的原因（当时实测过）：`getTotalRxBytes()` 是
 * 全机器接收总量，家里别的设备、后台更新都会算进来，
 * 和播放器实际下载速度对不上。
 */
@Composable
fun rememberNetSpeed(): String {
    var speed by remember { mutableStateOf("0 B/s") }
    LaunchedEffect(Unit) {
        var lastRx = 0L
        while (true) {
            val uid = android.os.Process.myUid()
            val uiBytes = android.net.TrafficStats.getUidRxBytes(uid)
            val rx =
                if (uiBytes == android.net.TrafficStats.UNSUPPORTED.toLong()) 0L else uiBytes
            speed = when {
                lastRx <= 0L || rx <= lastRx -> "0 B/s"
                else -> {
                    val bytes = rx - lastRx
                    when {
                        bytes >= 1024 * 1024 ->
                            String.format(Locale.ROOT, "%.1f MB/s", bytes / 1048576f)
                        bytes >= 1024 ->
                            String.format(Locale.ROOT, "%.0f KB/s", bytes / 1024f)
                        else -> "$bytes B/s"
                    }
                }
            }
            lastRx = rx
            kotlinx.coroutines.delay(1_000L)
        }
    }
    return speed
}
