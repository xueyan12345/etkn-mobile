package xyz.etkn.mobile.music

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.etkn.mobile.graph

/**
 * 用户口径（2026-11-27，#74）**离开指定 WiFi 且当时正在放歌 → 彻底关闭 App**。
 *
 * 用户原话：
 * ```
 * 「当连接特定 wifi 并正在播放音乐的情况下。
 *   如果这个设定的 wifi 断开了，直接彻底关闭 etkn」
 * 「确认断开就立即退出」
 * 「主要就是用在车上」
 * ```
 *
 * ## 为什么需要「armed」这个中间状态
 *
 * 条件里有两个 AND：
 * ```
 * ① 连着指定的 WiFi
 * ② 正在播放音乐
 * ```
 * 不能只看「WiFi 断了」就杀 —— 那样用户在车上开机、还没开始听歌就熄火，
 * App 也被杀了，属于误伤。
 *
 * 所以：
 * ```
 * 连上指定 WiFi + 真的开始放歌  → armed = true（上膛）
 * 指定 WiFi 断开 + armed       → 立刻退出
 * 指定 WiFi 断开 + 没 armed     → 不动
 * ```
 * `armed` 持久化在设置里，进程被系统杀掉再拉起来也还在。
 *
 * ## 权限
 *
 * Android 10+ 读 WiFi 名称需要位置权限。没有权限时拿不到 SSID（会返回
 * `<unknown ssid>`），此时功能静默不生效 —— 不会误杀。
 */
object WifiExitGuard {
    private var job: CoroutineScope? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** 反注册回调时要用它（单例不能长期持有 Activity）。 */
    @Volatile private var appContext: Context? = null

    /**
     * 开始监听。App 启动时调用一次；重复调用是安全的（会先停掉旧的）。
     */
    fun start(context: Context) {
        stop()
        appContext = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        job = scope

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                scope.launch { handleLost(context.applicationContext) }
            }
        }
        callback = cb
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onFailure { android.util.Log.w("ETKN-WifiExit", "注册 WiFi 回调失败", it) }

        /** 启动时也校准一次：如果此刻就没连着指定 WiFi，但 armed 还是 true（异常状态），清掉。 */
        scope.launch { syncArmed(context.applicationContext) }
    }

    fun stop() {
        val ctx = appContext
        val cb = callback
        if (ctx != null && cb != null) {
            runCatching {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            }
        }
        callback = null
        job = null
    }

    /**
     * 播放开始/停止时调用，维护 armed 状态。
     *
     * 用户口径的「正在播放」指的是**真的在出声**，不是"点了播放但还没加载好"。
     * 这里由 [MusicConnection] 在 playing 状态变化时调用。
     */
    fun onPlaybackChanged(context: Context, playing: Boolean) {
        val scope = job ?: return
        scope.launch {
            val s = context.graph.settings.flow.first()
            if (!s.wifiExitEnabled) return@launch
            if (playing) {
                /** 只有连着指定 WiFi 才上膛。 */
                val now = currentSsid(context)
                if (now != null && now == s.wifiExitSsid && !s.wifiExitArmed) {
                    context.graph.settings.setWifiExitArmed(true)
                    android.util.Log.i("ETKN-WifiExit", "已上膛：连着「$now」且在放歌")
                }
            } else {
                if (s.wifiExitArmed) {
                    context.graph.settings.setWifiExitArmed(false)
                    android.util.Log.i("ETKN-WifiExit", "已解除：停止播放")
                }
            }
        }
    }

    private suspend fun handleLost(context: Context) {
        val s = context.graph.settings.flow.first()
        if (!s.wifiExitEnabled || s.wifiExitSsid.isBlank()) return
        if (!s.wifiExitArmed) {
            android.util.Log.i("ETKN-WifiExit", "WiFi 断开但未上膛（这次连接期间没放歌），不退出")
            return
        }
        /**
         * 二次确认：`onLost` 有时会在切换到另一个 WiFi 时也触发，
         * 所以这里再读一次当前 SSID，确认真的不在目标 WiFi 上了。
         */
        val now = currentSsid(context)
        if (now != null && now == s.wifiExitSsid) {
            android.util.Log.i("ETKN-WifiExit", "onLost 但仍在「$now」上，忽略")
            return
        }
        android.util.Log.i("ETKN-WifiExit", "离开「${s.wifiExitSsid}」（当前=$now）且已上膛 → 彻底退出")
        context.graph.settings.setWifiExitArmed(false)
        /** 「彻底关闭」= 杀掉整个进程，不留 Service 在后台。 */
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private suspend fun syncArmed(context: Context) {
        val s = context.graph.settings.flow.first()
        if (!s.wifiExitEnabled) return
        val now = currentSsid(context)
        if (s.wifiExitArmed && now != s.wifiExitSsid) {
            context.graph.settings.setWifiExitArmed(false)
            android.util.Log.i("ETKN-WifiExit", "启动校准：当前「$now」不等于目标，清除上膛")
        }
    }

    /**
     * 当前连着的 WiFi 名称（SSID）。拿不到返回 null。
     *
     * Android 10+ 必须同时满足：①有位置权限 ②定位开关打开，
     * 否则 `WifiInfo.ssid` 会返回 `<unknown ssid>`。
     */
    @SuppressLint("MissingPermission")
    fun currentSsid(context: Context): String? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val raw = wm.connectionInfo?.ssid ?: return null
        /** 系统在拿不到时会返回 `<unknown ssid>` 或带引号的原始值。 */
        val clean = raw.trim().removeSurrounding("\"")
        return clean.takeIf { it.isNotEmpty() && !it.startsWith("<") }
    }

    /**
     * 枚举系统保存过的 WiFi（用户要「像 MacroDroid 那样弹窗选，不用手打」）。
     *
     * 优先走 `WifiManager.configuredNetworks`（系统保存的所有网络，含当前没连的），
     * 这是 MacroDroid 那类软件的做法。拿不到时退回「当前已连接的那个」。
     */
    @SuppressLint("MissingPermission")
    fun savedSsids(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (granted && wm != null) {
            @Suppress("DEPRECATION")
            runCatching {
                wm.configuredNetworks?.forEach { cfg ->
                    val name = cfg.SSID?.trim()?.removeSurrounding("\"")
                    if (!name.isNullOrEmpty() && !name.startsWith("<")) out += name
                }
            }
            currentSsid(context)?.let { out += it }
        }
        return out.toList().sorted()
    }
}
