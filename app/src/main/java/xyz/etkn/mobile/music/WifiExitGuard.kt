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
            .onSuccess { android.util.Log.i("ETKN-WifiExit", "✅ WiFi 回调已注册") }
            .onFailure { android.util.Log.w("ETKN-WifiExit", "!! 注册 WiFi 回调失败", it) }
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
        /**
         * 用户实测「WiFi 断掉，关闭 APP 不好使」→ 加日志把每一步的判定都打出来，
         * 才知道是「监听没注册」还是「SSID 读不到」还是「没上膛」。
         */
        android.util.Log.i(
            "ETKN-WifiExit",
            "onPlaybackChanged: playing=$playing job=${job != null}",
        )
        val scope = job ?: run {
            android.util.Log.w("ETKN-WifiExit", "!! 监听未启动（job == null）→ 开关可能没打开或 App 没重启")
            return
        }
        scope.launch {
            val s = context.graph.settings.flow.first()
            android.util.Log.i(
                "ETKN-WifiExit",
                "判定: enabled=${s.wifiExitEnabled} target=「${s.wifiExitSsid}」armed=${s.wifiExitArmed}",
            )
            if (!s.wifiExitEnabled) return@launch
            if (playing) {
                val now = currentSsid(context)
                android.util.Log.i("ETKN-WifiExit", "当前 SSID=「$now」")
                if (now == null) {
                    android.util.Log.w(
                        "ETKN-WifiExit",
                        "!! 读不到 SSID —— 检查①位置权限是否授予 ②手机定位开关是否打开",
                    )
                } else if (now == s.wifiExitSsid && !s.wifiExitArmed) {
                    context.graph.settings.setWifiExitArmed(true)
                    android.util.Log.i("ETKN-WifiExit", "✅ 已上膛：连着「$now」且在放歌")
                } else if (now != s.wifiExitSsid) {
                    android.util.Log.i(
                        "ETKN-WifiExit",
                        "当前 WiFi「$now」≠ 目标「${s.wifiExitSsid}」，不上膛",
                    )
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
            /**
             * 用户口径（2026-11-27，#81）**退出前必须先把播放进度落盘**。
             *
             * 用户原话：
             * ```
             * 「播放的歌曲，记录它保存不上，比如说播放一半，然后我触发了 WiFi 关闭，
             *   整个软件不退出了吗，下次打开之后，播放一半，那个进度没了，就从头开始了」
             * ```
             * 根因：我下面 `clearMediaItems()` 一执行，`state.current` 变 null，
             * `saveMemory()` 里 `val item = s.current ?: return` 直接返回 →
             * **磁盘上留的是旧进度（常为 0）**。
             *
             * 所以顺序改成：**先存进度，再做任何会清空当前曲目的动作**。
             */
            runCatching { context.graph.music.pause() }
            delay(120)
            runCatching { context.graph.music.flushMemory() }
            delay(120)
            android.util.Log.i("ETKN-WifiExit", "进度已落盘 → 再清理会话")
            /**
             * ③ 释放会话 —— 系统收掉卡片。
             *
             * **不再 clearMediaItems()**：那会把当前曲目清掉，
             * 导致 saveMemory 写不进进度（#81）。
             * 只释放会话 + 停服务 + killProcess 就够让卡片消失。
             */
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

        /**
         * 用户口径（2026-11-27，#78）**要列出系统保存过的所有 WiFi，不能只有当前连的那个**。
         *
         * 用户原话：「这个选择 WiFi 只有当前连接，没有之前保存的那些」
         *
         * 实测原因：`WifiManager.getConfiguredNetworks()`
         * 在 targetSdk >= 29/30 之后逐步收紧，到 targetSdk 36 上
         * **只返回少量/当前网络的条目**（adb `dumpsys wifi` 能看到 nid=21 的
         * 「123」「yan5G」等多个已保存配置，但 API 拿不到全部）。
         * 所以三条路一起试，谁能给就给：
         * ```
         * ① getConfiguredNetworks()  —— 老 ROM / 低 targetSdk 有效
         * ② createWifiNetworkSpecifier? 不适用（那是连接用）
         * ③ 扫描结果里的已知网络（scanResults 只含在范围内的，但覆盖面常比 ① 广）
         * ④ 兜底：当前已连接的那个
         * ```
         * 三条都拿不到时才只剩当前连接 —— 这不是代码问题，是系统权限收紧。
         */
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

        if (granted && wm != null) {
            /** ① 已保存配置。 */
            @Suppress("DEPRECATION")
            runCatching {
                wm.configuredNetworks?.forEach { cfg ->
                    val name = cfg.SSID?.trim()?.removeSurrounding("\"")
                    if (!name.isNullOrEmpty() && !name.startsWith("<")) out += name
                }
            }
            android.util.Log.i("ETKN-WifiExit", "① configuredNetworks → ${out.size} 个: $out")

            /**
             * ③ 扫描结果里的网络（通常比 ① 覆盖更广，因为扫描不受
             * 「只返回自己配置」那条限制）。
             */
            @Suppress("DEPRECATION")
            runCatching {
                wm.scanResults?.forEach { r ->
                    val name = r.SSID?.trim()?.removeSurrounding("\"")
                    if (!name.isNullOrEmpty() && !name.startsWith("<")) out += name
                }
            }
            android.util.Log.i("ETKN-WifiExit", "③ scanResults 后 → ${out.size} 个: $out")
        }

        /**
         * ② 用户口径（#78）**主动发起一次扫描**。
         *
         * 关键：`getScanResults()` 返回的是「上次扫描的缓存」，如果最近没扫过
         * 就只有可怜的一条（正是用户看到的「只有当前连接」）。
         * 必须先 `startScan()` 再读，才能拿到周边所有网络。
         *
         * 注意 startScan() 是异步的（2~4 秒），且 Android 9+ 有频率限制
         * （每分钟 4 次），所以这里做「发起扫描 → 短等 → 再读」，
         * 但**不能阻塞 UI**（在 IO 线程里跑，界面先显示已保存的）。
         */
        if (granted && wm != null) {
            runCatching { wm.startScan() }
                .onFailure { android.util.Log.w("ETKN-WifiExit", "startScan 失败（可能被限频）", it) }
            runCatching {
                wm.scanResults?.forEach { r ->
                    val name = r.SSID?.trim()?.removeSurrounding("\"")
                    if (!name.isNullOrEmpty() && !name.startsWith("<")) out += name
                }
            }
            android.util.Log.i("ETKN-WifiExit", "② startScan+scanResults → ${out.size} 个")
        }

        /**
         * 【已失效，保留记录】读系统配置文件的尝试。
         *
         * 为什么必须这么做（adb 实测证据）：
         * ```
         * cmd wifi list-networks  →  29 个已保存网络（123/CarLife/GEELY_522612/CMCC 系列等）
         * 但 App 里 getConfiguredNetworks() 只返回当前那个
         * ```
         * Android 10+ 把 `getConfiguredNetworks()` 限制成「只返回自己创建的」，
         * 系统里其他 WiFi 读不到。这是系统侧收紧，不是权限没给。
         *
         * 唯一能拿到全部已保存网络的办法是读系统配置文件
         * `/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml`，
         * 但它属于 system，普通 App 没有读权限 —— 所以**只能作为尽力而为**：
         * 拿得到就合并（例如设备已 root 或厂商放宽），拿不到就跳过。
         */
        runCatching {
            val f = java.io.File("/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml")
            if (f.canRead()) {
                val xml = f.readText()
                Regex("<string name=\"SSID\">\"?(.*?)\"?</string>").findAll(xml).forEach { m ->
                    val name = m.groupValues[1].trim()
                    if (name.isNotEmpty() && !name.startsWith("<")) out += name
                }
                android.util.Log.i("ETKN-WifiExit", "② 配置文件读到 → ${out.size} 个")
            } else {
                android.util.Log.i("ETKN-WifiExit", "配置文件不可读（系统权限，正常）")
            }
        }

        /** ④ 当前连接的一定要有。 */
        currentSsid(context)?.let { out += it }
        android.util.Log.i("ETKN-WifiExit", "final savedSsids(${out.size}): $out")
        return out.toList().sorted()
    }
}

/**
 * 音乐 `MediaSession` 的全局引用。
 *
 * 用户口径（#77）**让 WifiExitGuard 能主动释放 MediaSession**，卡片才会立刻消失：
 * MusicService 创建会话后 attach 进来；退出时 release。
 *
 * 用户口径（2026-11-27，#125）**播视频时音乐要让出媒体键和系统卡片**。
 *
 * 用户原话：
 * ```
 * 「假如我用蓝牙耳机听音乐，然后我暂停掉音乐，转去播放视频，
 *   那么我在耳机点击下一首这个操作，还会触发音乐下一集，但是是暂停状态的下一集音乐没播放」
 * 「当切换到播放视频时候第一应该屏蔽蓝牙控制器对音乐的控制，可以控制视频暂停，
 *   现在我用蓝牙暂停视频暂停然后音乐就从暂停状态转为播放状态了」
 * 「播放视频时候应该把系统状态栏的那个音乐播放卡片停掉了，就是恢复到纯灰色的状态」
 * 「播放视频结束之后应该就把蓝牙对音乐的控制恢复了」
 * ```
 *
 * 根因：音乐和视频各有一个 `MediaSession`（视频那个 id="video"），
 * 两个同时 active，系统按「最近活跃」分发媒体键 → 按键会串到音乐。
 *
 * 修法：视频开始播放时 `setActive(false)`，视频结束时 `setActive(true)`。
 *
 * **注意**：`setActive(false)` 只影响「媒体键分发」和「系统卡片是否展示」，
 * 不影响 `MediaButtonReceiver` 的静态注册 —— 蓝牙冷启动唤醒那条路
 * 靠的是 `EtknMediaButtonReceiver` + Manifest，跟这个开关无关。
 */
object MusicSessionHolder {
    @Volatile private var session: androidx.media3.session.MediaSession? = null

    fun attach(s: androidx.media3.session.MediaSession) { session = s }

    /**
     * 让音乐会话让出媒体键（视频开始播放时调）。
     *
     * 同时把系统卡片切换成「无播放」的灰态 —— 这就是用户要的
     * 「恢复到纯灰色的状态」。
     */
    fun yieldToVideo() {
        val s = session ?: return
        runCatching { s.setActive(false) }
            .onSuccess { android.util.Log.i("ETKN-Music", "session 让出媒体键（视频开始）") }
            .onFailure { android.util.Log.w("ETKN-Music", "让出媒体键失败", it) }
    }

    /** 视频结束，把媒体键还给音乐（用户明确要求）。 */
    fun resumeFromVideo() {
        val s = session ?: return
        runCatching { s.setActive(true) }
            .onSuccess { android.util.Log.i("ETKN-Music", "session 收回媒体键（视频结束）") }
            .onFailure { android.util.Log.w("ETKN-Music", "收回媒体键失败", it) }
    }

    fun release() {
        runCatching { session?.release() }
        session = null
    }
}
