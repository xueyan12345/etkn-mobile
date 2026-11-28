package xyz.etkn.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * 用户口径（2026-11-28，#212）**原样恢复以前能用的网速实现**。
 *
 * ## 用户原话（第三次说同一件事）
 *
 * ```
 * 「还有那个网速还是不对，你找以前的那个播放界面右上角的那个网速是怎么写的了不，
 *   跟你说800遍了，让你找以前的，你写过的，还自己瞎发挥，现在右上角还是一直是 0」
 * 「中间缓冲图标下边的数也不对，跟手机实际跑的对不上都」
 * ```
 *
 * ## 以前能用的版本（git 369b914，用户确认好用）
 *
 * ```kotlin
 * val rx = android.net.TrafficStats.getTotalRxBytes()   // ← 全机接收量
 * val speed = when {
 *     lastRx <= 0L || rx <= lastRx -> ""
 *     else -> { 差值 → KB/s 或 MB/s }
 * }
 * lastRx = rx
 * delay(1_000)
 * ```
 *
 * ## 我改坏的过程（记下来，别再犯）
 *
 * ```
 * ① 想「只算本 App」→ 换成 getUidRxBytes(自己uid)
 *    这个 API 在本机返回一个**不怎么变化的旧值**
 * ② 加三级兜底时写成 `uidBytes > 0L -> uidBytes`
 *    只要这个旧值 > 0，就永远命中第一个分支
 *    → 差值恒 0 → 界面永远 "0 B/s"
 * ```
 * 换句话说：兜底逻辑看起来「更稳」，实际把唯一生效的路径挡死了。
 *
 * ## 现在的写法
 *
 * **完全照搬 369b914**，不做任何「优化」：
 * ```
 * getTotalRxBytes()  —— 全机接收量，所有设备都可用
 * 每秒采样一次
 * 差值为 0 时显示 "0 B/s"（不空着，避免闪烁）
 * ```
 * 播放时本 App 的流量占绝对多数，全机口径足以反映真实下载速度。
 */
@Composable
fun rememberNetSpeed(): String {
    var speed by remember { mutableStateOf("0 B/s") }
    LaunchedEffect(Unit) {
        var lastRx = 0L
        while (true) {
            val rx = android.net.TrafficStats.getTotalRxBytes()
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
