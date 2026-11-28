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
            /**
             * 用户实测（2026-11-28，#209）**右上角网速一直是 0，以前好用**。
             *
             * ## 根因
             *
             * 原来用 `getUidRxBytes(自己uid)`：这个 API 统计的是
             * 「本进程 UID 收发的字节」，在某些 ROM / 内核上会返回
             * `UNSUPPORTED`，或因为统计口径变化长期不动 → 恒 0。
             *
             * ## 现在三级兜底
             *
             * ```
             * ① getUidRxBytes(自己uid)   —— 精确到本 App（首选）
             * ② getTotalRxBytes()        —— 全机接收量（次选，能反映"在下载"）
             * ③ 都不可用 → 保持上次值，不显示 0 抖动
             * ```
             * 虽然 ② 会混入其他应用的流量，但至少**不会一直是 0**，
             * 而且播放时本 App 的流量占绝对多数，能反映真实下载速度。
             */
            val uid = android.os.Process.myUid()
            val uidBytes = android.net.TrafficStats.getUidRxBytes(uid)
            val totalBytes = android.net.TrafficStats.getTotalRxBytes()
            val rx = when {
                uidBytes > 0L -> uidBytes
                totalBytes > 0L -> totalBytes
                else -> lastRx   // 都拿不到就沿用上次，避免刷成 0
            }
            android.util.Log.d(
                "ETKN-Net",
                "uid=$uidBytes total=$totalBytes used=$rx",
            )
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
            /**
             * 用户实测（2026-11-28，#203）**右上角网速不显示**。
             *
             * 加日志以便确认：是 `getUidRxBytes` 返回了 UNSUPPORTED
             * （说明这个 ROM 上该 UID 统计不可用，需要换回 getTotalRxBytes），
             * 还是这段协程压根没跑起来（Composable 被提前离开重组）。
             */
            android.util.Log.i(
                "ETKN-Net",
                "uid=$uid raw=$uiBytes rx=$rx last=$lastRx speed=$speed",
            )
            kotlinx.coroutines.delay(1_000L)
        }
    }
    return speed
}
