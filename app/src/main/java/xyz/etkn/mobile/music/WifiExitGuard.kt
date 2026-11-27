package xyz.etkn.mobile.music

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import xyz.etkn.mobile.graph

/**
 * 用户口径（2026-11-27，#74/#77）**离开指定 WiFi 且当时正在放歌 → 彻底关闭 App**。
 *
 * 用户原话：
 * ```
 * 「当连接特定 wifi 并正在播放音乐的情况下。如果这个设定的 wifi 断开了，
 *   直接彻底关闭 etkn」
 * 「wifi 的名你能不能像 MACRODROID 这个软件一样，能直接弹出个窗口，
 *   选择系统已经连接过或者保存过的 wifi，这样就不用手动填了，并且更准确」
 * 「确认断开就立即退出」
 * 「主要就是用在车上」
 * 「APP 确实是退出了，但是这个后台卡片能不能一块删掉啊」
 * 「后台卡片还在」
 * ```
 *
 * ## armed（上膛）状态机
 *
 * 条件是两个 AND：① 连着指定 WiFi ② 正在播放音乐。
 * 不能只看 WiFi 断开就杀 —— 那样在车上还没开始听歌就熄火，App 也会被杀（误伤）。
 * ```
 * 连着指定 WiFi + 真的在放歌 → armed = true
 * 指定 WiFi 断开 + armed      → 清理并退出
 * 指定 WiFi 断开 + 没 armed    → 不动
 * ```
 * armed 存设置里（持久化），进程被系统杀掉再拉起来也还在。
 *
 * ## 退出时的清理顺序（#77 的重点）
 *
 * 只 killProcess 不够 —— 系统的媒体卡片是 MediaSession 发布出去的，
 * 进程被杀时系统来不及回收，卡片会残留，点它还能把 App 拉起来。所以：
 * ```
 * ① 暂停播放
 * ② 清空媒体队列（卡片失去歌名/封面）
 * ③ 释放 MediaSession（主动告诉系统「这个会话没了」）
 * ④ 撤通知 + 停前台服务
 * ⑤ 最后 killProcess
 * ```
 * 每步之间留一点间隔让系统处理。
 */
object WifiExitGuard {
    private var job: CoroutineScope? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    /** 反注册回调要用它（单例不能长期持有 Activity）。 */
    @Volatile private var appContext: Context? = null

    /** 防重入：清理跑到一半又收到 onLost 时不要重复执行。 */
    @Volatile private var exiting = false

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
     * 播放状态变化时调用，维护 armed。
     *
     * 用户口径的「正在播放」指真的在出声。由 MusicConnection 的
     * `onIsPlayingChanged` 调进来（那里只加一行调用，不改其它逻辑）。
     */
    fun onPlaybackChanged(context: Context, playing: Boolean) {
        val scope = job ?: return
        scope.launch {
            val s = context.graph.settings.flow.first()
            if (!s.wifiExitEnabled) return@launch
            if (playing) {
                val now = currentSsid(context)
                if (now != null && now == s.wifiExitSsid && !s.wifiExitArmed) {
                    context.graph.settings.setWifiExitArmed(true)
                    android.util.Log.i("ETKN-WifiExit", "已上膛：连着「$now」且在放歌")
                }
            } else if (s.wifiExitArmed) {
                context.graph.settings.setWifiExitArmed(false)
                android.util.Log.i("ETKN-WifiExit", "已解除：停止播放")
            }
        }
    }

    private suspend fun handleLost(context: Context) {
        if (exiting) return
        val s = context.graph.settings.flow.first()
        if (!s.wifiExitEnabled || s.wifiExitSsid.isBlank()) return
        if (!s.wifiExitArmed) {
            android.util.Log.i("ETKN-WifiExit", "WiFi 断开但未上膛（这次没放歌），不退出")
            return
        }
        /** 二次确认：切到另一个 WiFi 时 onLost 也会触发，这里再读一次当前 SSID。 */
        val now = currentSsid(context)
        if (now != null && now == s.wifiExitSsid) {
            android.util.Log.i("ETKN-WifiExit", "onLost 但仍在「$now」，忽略")
            return
        }
        exiting = true
        android.util.Log.i("ETKN-WifiExit", "离开「${s.wifiExitSsid}」（当前=$now）且已上膛 → 清理后退出")
        context.graph.settings.setWifiExitArmed(false)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            /** ① 暂停。 */
            runCatching { context.graph.music.pause() }
            delay(150)
            /** ② 清空队列 —— 卡片没有内容可显示。 */
            runCatching { context.graph.music.player.clearMediaItems() }
            delay(150)
            /** ③ 释放会话 —— 系统立刻收掉卡片。 */
            MusicSessionHolder.release()
            delay(200)
            /** ④ 撤通知 + 停服务。 */
            runCatching { nm?.cancelAll() }
            runCatching { context.stopService(android.content.Intent(context, MusicService::class.java)) }
            delay(300)
        } catch (t: Throwable) {
            android.util.Log.w("ETKN-WifiExit", "清理过程异常（仍然退出）", t)
        }
        /** ⑤ 彻底结束进程。 */
        android.util.Log.i("ETKN-WifiExit", "清理完成 → killProcess")
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private suspend fun syncArmed(context: Context) {
        val s = context.graph.settings.flow.first()
        if (!s.wifiExitEnabled) return
        val now = currentSsid(context)
        if (s.wifiExitArmed && now != s.wifiExitSsid) {
            context.graph.settings.setWifiExitArmed(false)
            android.util.Log.i("ETKN-WifiExit", "启动校准：当前「$now」≠目标，清除上膛")
        }
    }

    /**
     * 当前连着的 WiFi 名称。拿不到返回 null。
     *
     * Android 10+ 必须同时满足：①有位置权限 ②定位开关打开，
     * 否则 `WifiInfo.ssid` 返回 `<unknown ssid>`。
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
        val clean = raw.trim().removeSurrounding("\"")
        return clean.takeIf { it.isNotEmpty() && !it.startsWith("<") }
    }

    /**
     * 枚举系统保存过的 WiFi（用户要「像 MacroDroid 那样弹窗选」）。
     *
     * 优先 `WifiManager.configuredNetworks`（含当前没连的），拿不到时退回当前已连接的那个。
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

/**
 * 用户口径（#77）**让 WifiExitGuard 能主动释放 MediaSession**，卡片才会立刻消失。
 *
 * MusicService 创建会话后 attach 进来；退出时 release。
 */
object MusicSessionHolder {
    @Volatile private var session: androidx.media3.session.MediaSession? = null

    fun attach(s: androidx.media3.session.MediaSession) { session = s }

    fun release() {
        runCatching { session?.release() }
        session = null
    }
}
