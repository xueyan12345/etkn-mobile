package xyz.etkn.mobile.music

import android.app.PendingIntent
import android.content.Intent
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.etkn.mobile.MainActivity
import xyz.etkn.mobile.graph
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import xyz.etkn.mobile.R

/**
 * 音乐播放服务：托管一个 MediaSession，让播放能在后台继续，
 * 并提供系统通知、锁屏和蓝牙/耳机按键控制。
 *
 * ## 用户口径汇总
 *
 * ```
 * #60 删掉为"App 关闭时点亮系统卡片"加的一大堆补丁（MediaLibraryService +
 *     320 行 Callback），回到 MediaSessionService
 * #69 蓝牙耳机按下一首要能唤醒并播放（当时删过头了）
 * #27 没在播时，「播放」和「下一首」都等于「开始播放」；
 *     只有正在播放时，下一首才是真的下一首
 * #70 正在播放时按下一首只能跳 1 首，不能跳 2 首
 * ```
 *
 * ## 这台机器上必须自己接媒体键（adb 实测结论）
 *
 * 试过只靠 Media3 默认处理，结果：
 * ```
 * MediaSessionStack: getMediaButtonSession() mMediaButtonSession=xyz.etkn.mobile/.../572
 * onSessionPlaybackStateChanged: playbackState=PAUSED(2)
 * ```
 * Session 建起来了、按键也路由到我们，但**没有一个人去执行"播放"** ——
 * `onPlayerCommandRequest` 和 `onPlaybackResumption` 都没被调用。
 * 所以媒体键必须由我们自己的 Receiver 接住并处理。
 *
 * ## 防重复执行（关键设计）
 *
 * 之前"点一次跳两首"的原因是**两个入口同时动手**：
 * ```
 * ✗ onPlayerCommandRequest → systemNext()
 * ✗ onStartCommand         → systemNext()
 * ```
 * 现在**只留一个入口**，并且在它开头做一次硬判断：
 * ```
 * 正在播放（isPlaying && 队列非空）→ 立刻 return，一个字都不做
 *                                    → 交给 Media3 默认逻辑（切歌只切 1 首）
 * 没在播                          → 接管，把 remembered 播起来
 * ```
 * 两个场景互斥，不可能同时执行，所以不存在跳两首。
 * 不用时间窗去重（那个会因 await 登录态而失效，已被证明不可靠）。
 *
 * 另：`onPlayerCommandRequest` 完全不再实现 —— 下一首/上一首一行都不拦。
 */
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null

    private companion object {
        /**
         * 媒体卡片用的通知 id / 渠道。
         *
         * 用一个固定的 id：每次 `startForeground` 都更新同一条，
         * 不会堆积成一串通知。
         */
        const val CARD_ID = 4211
        const val CARD_CHANNEL = "etkn_now_playing"
    }

    override fun onCreate() {
        super.onCreate()
        val graph = applicationContext.graph
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        /** 通知栏封面走图片客户端（令牌只发给 ETKN 同源）。 */
        val bitmaps = DataSourceBitmapLoader.Builder(this)
            .setDataSourceFactory(OkHttpDataSource.Factory(graph.http.images))
            .build()
        session = MediaSession.Builder(this, graph.music.player)
            .setSessionActivity(open)
            .setBitmapLoader(bitmaps)
            .setCallback(object : MediaSession.Callback {
                /**
                 * 用户口径（2026-11-28，#161）**播视频时把音乐从蓝牙按键上摘下来**。
                 *
                 * 用户原话：
                 * ```
                 * 「用蓝牙耳机播放或者连蓝牙设备播放音乐的时候，如果我暂停播放音乐，
                 *   转去播放视频，那么这个蓝牙耳机操作下一首还是好使，只不过音乐不播放，
                 *   单纯的跳下一首，这个在系统的状态栏上那个播放小窗能看到。
                 *   然后我在耳机上触发播放暂停，视频能暂停，
                 *   但是视频暂停的同时，又触发了音乐的播放，因为之前音乐是暂停的。
                 *   所以我想播放视频的时候，是不是应该把音乐那边断掉跟蓝牙的操作，
                 *   然后视频播放退出后，恢复蓝牙设备对音乐的操控」
                 * 「你改的是不是还是要注意别把我那个耳机唤醒音乐播放操作干没了，
                 *   还有要注意，别把那个车机 CarLife 上面的歌词啥的干没了」
                 * ```
                 *
                 * ## 根因（官方文档 + 本仓库代码）
                 *
                 * Android 媒体键的派发优先级（developer.android.com/media/legacy/media-buttons）：
                 * ```
                 * ① App 的 UI Activity 可见      → 按键给前台 Activity
                 * ② UI 隐藏、session active      → 按键给 session
                 * ③ UI 隐藏、session inactive    → 用来重启 session
                 * ```
                 * 视频播放页是**可见的 Activity**，按键本来就该归它。
                 * 但音乐的 `MediaSession` 也同时挂着 —— 同一次 `KEYCODE_MEDIA_PLAY`
                 * 被两边都吃到，视频暂停/播放的同时音乐跟着动。
                 *
                 * 之前 `MusicSessionHolder.yieldToVideo()` 只做了 `player.pause()`，
                 * **暂停 ≠ 交出按键**：会话还在，蓝牙按「播放」照样能把音乐叫起来。
                 * 而且它只在「音乐正在播」时才 pause，用户这种「先暂停音乐再播视频」的情形
                 * 压根没进 if 分支，等于什么都没做。
                 *
                 * ## 改法：在命令执行前拦截（不 release、不动蓝牙唤醒链）
                 *
                 * `onPlayerCommandRequest` 是 Media3 在**执行命令之前**问一句的地方，
                 * 返回 `RESULT_ERROR_NOT_SUPPORTED` 就表示「这条命令我不处理」。
                 *
                 * ```
                 * videoHolding == true 时：
                 *   PLAY_PAUSE / PLAY  → 拦掉   （否则音乐被蓝牙唤醒复活）
                 *   NEXT / PREVIOUS    → 拦掉   （否则音乐在后台偷偷切歌）
                 *   PAUSE / STOP       → 放行   （音乐已经暂停了，放行无害）
                 * videoHolding == false → 一律放行（完全保持原来的蓝牙唤醒行为）
                 * ```
                 *
                 * ## 三条红线都没碰
                 *
                 * ```
                 * ✗ 不 release 会话       → 蓝牙唤醒链路完好（松开后按键照常到达）
                 * ✗ 不动 Receiver/按键分流 → EtknMediaButtonReceiver、MusicService 按键处理原样
                 * ✗ 不动音频栈/歌词        → CarLife 车机的歌词照旧（它走音频会话元数据，不走这条）
                 * ```
                 * 只在「视频页面处于前台」这个窗口内临时返回 not supported，
                 * 视频退出（`onDestroy` → `resumeFromVideo()`）立刻恢复正常。
                 */
                override fun onPlayerCommandRequest(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    @androidx.media3.common.Player.Command playerCommands: Int,
                ): Int {
                    if (MusicSessionHolder.isVideoHolding()) {
                        when (playerCommands) {
                            /**
                             * 用户实测反馈（2026-11-28，#163）**第 4 步没拦住**。
                             *
                             * 用户原话：
                             * ```
                             * 「第一个、第二个、第三个都没问题，但是第四个，
                             *   正在播放视频的时候耳机按暂停，视频暂停了，音乐又播放了」
                             * ```
                             *
                             * ## 为什么上一版拦不住
                             *
                             * 上一版只拦了 `SEEK_TO_NEXT/PREVIOUS`，**故意放过
                             * `COMMAND_PLAY_PAUSE`**，理由是「播放和暂停在 Media3 里合并成
                             * 一个命令（`COMMAND_PLAY_PAUSE = 1`），拦了会连用户想暂停音乐也拦掉」。
                             *
                             * 这个理由在**视频占用期间不成立**：
                             * ```
                             * 视频在前台时，用户按播放/暂停想控制的是「视频」，
                             * 音乐本来就该保持安静 —— 此时拦掉 PLAY_PAUSE 完全正确。
                             * ```
                             * 而且 `MusicSessionHolder.yieldToVideo()` 里的
                             * `playWhenReady = false` 只是一次性设置，
                             * 蓝牙按下 PLAY 会把它改回 true —— 所以必须在这里硬拦。
                             *
                             * ## 现在：视频占用期间，播放/暂停 + 切歌全部拦掉
                             *
                             * ```
                             * COMMAND_PLAY_PAUSE              → 拦  （否则音乐被叫起来）
                             * COMMAND_SEEK_TO_NEXT/PREVIOUS   → 拦  （否则后台偷偷切歌）
                             * COMMAND_STOP                    → 放行（停下来无害，也不出声）
                             * 其余                            → 放行
                             * ```
                             *
                             * ## 为什么不会误伤「暂停音乐」
                             *
                             * 这个 if 只在 `videoHolding == true`（视频页面处于前台）时成立。
                             * 用户离开视频页 → `onDestroy` → `resumeFromVideo()` 立刻清掉标记，
                             * 音乐这边一切按键行为恢复原样。
                             *
                             * ## 蓝牙唤醒红线仍然完好
                             *
                             * 拦的是「命令执行」，**会话没有 release、Receiver 没有动**
                             * （`EtknMediaButtonReceiver` / `onStartCommand` / `systemPlay`
                             * 一行都没改）。视频退出后蓝牙按键照常到达、照常能唤醒音乐。
                             */
                            androidx.media3.common.Player.COMMAND_PLAY_PAUSE,
                            androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT,
                            androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                            androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS,
                            androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                            -> {
                                android.util.Log.i("ETKN-Music", "视频占用中 → 拦下音乐命令 cmd=$playerCommands")
                                return androidx.media3.session.SessionResult.RESULT_ERROR_NOT_SUPPORTED
                            }
                        }
                    }
                    return super.onPlayerCommandRequest(mediaSession, controller, playerCommands)
                }

                /**
                 * 只实现 `onPlaybackResumption`：系统主动问「要播哪首」时给答案。
                 * 上一首/下一首/播放暂停在 `videoHolding == false` 时
                 * 全部交给 MediaSession 默认逻辑（原行为不变），
                 * 避免与 Receiver 那条路重复执行。
                 */
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    isForPlayback: Boolean,
                ) = this@MusicService.onPlaybackResumption(mediaSession, controller)
            })
            .build()
        /** 用户口径 #77：交给 WifiExitGuard 在退出前释放，卡片才会立刻消失。 */
        session?.let { MusicSessionHolder.attach(it) }

        /**
         * 用户口径（#174）**服务一起来就把媒体卡片建出来**，不等播放。
         *
         * 会话刚建好时 `currentMediaItem` 可能还是 null（队列要等
         * `restoreForResumption()` 异步解析真实直链后才填上），
         * 所以这里先试一次，再挂一个观察者：队列一有内容立刻出卡片。
         */
        updateMediaCard()
        graph.music.player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateMediaCard()
            }
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateMediaCard()
            }
        })

        /**
         * 用户口径（#74 修正）**监听也要挂在 Service 上**。
         *
         * 用户实测「WiFi 断掉，关闭 APP 不好使」。
         * 只挂在 Application 上不够 —— App 切后台久一点，或被系统回收，
         * ConnectivityManager 的进程内回调就没人响应了。
         * MusicService 是前台服务（MediaSessionService），播放期间一定活着，
         * 在这里再挂一份，断开时一定能收到。
         * （WifiExitGuard.start 内部会先 stop 旧的，重复调用安全。）
         */
        runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(1_000L) {
                    applicationContext.graph.settings.flow.first().wifiExitEnabled
                } ?: false
            }
        }.getOrDefault(false).let { on ->
            if (on) {
                runCatching { WifiExitGuard.start(applicationContext) }
                android.util.Log.i("ETKN-WifiExit", "Service 侧监听已启动=$on")
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * 用户口径（2026-11-28，#174）**不播放也要显示媒体卡片**。
     *
     * 用户原话：
     * ```
     * 「压根就没实现，APP 就是打开的，但是系统的那个音乐播放控件是空白的」
     * 「按我的理解是只要这个 APP 不全部退出，
     *   它那个音乐控件上就一直应该显示现在正在播放的音乐，
     *   就算现在没有音乐，应该也是暂停的状态」
     * ```
     *
     * ## 走过的弯路（都实测过，别再走）
     *
     * ```
     * ① onUpdateNotification(session, forceForeground=true)
     *    实测：一次都没被调用 —— Media3 判断「没在播放就不需要通知」，
     *    根本不进这个钩子，改参数写什么都无效
     * ② 加 DefaultMediaNotificationProvider
     *    实测：这个类不在 media3-session 1.11.1 的公开 API 里，编译不过
     * ③ 通知权限没开
     *    adb 查到 granted=false（USER_FIXED），但用户手动打开后通知仍然是 0
     *    → 权限不是根因，只是同时存在的第二个问题
     * ```
     *
     * ## 现在：自己发一条标准 MediaStyle 通知
     *
     * 不依赖 Media3 的内部判断。用平台 API：
     * ```
     * Notification.Builder(this, channel)   ← 普通渠道，不依赖 Media3 通知
     *     .setStyle(MediaStyle().setMediaSession(token))
     *     .setSmallIcon / setContentTitle / setContentText
     * startForeground(NOTIFICATION_ID, notification)
     * ```
     * `setMediaSession(token)` 是关键：把通知和 MediaSession 绑定后，
     * 系统媒体卡片（含 OPPO 那个）会把它当成「正在播放的媒体」来渲染，
     * 显示歌名/歌手，并给出播放/暂停/上一首/下一首按钮。
     *
     * 暂停态也显示，因为通知是我们主动发的，不看播放状态。
     *
     * ## 何时更新
     *
     * 每次 `publish()`（换歌/进度变化）都会走到 `updateMediaCard()`，
     * 内容取 `MediaMetadata` —— 和会话保持一致，不会出现「卡片和播放对不上」。
     */
    private var cardBuilt = false

    /** 建/刷新那条常驻媒体通知。任何能拿到 session 的地方都可以调。 */
    fun updateMediaCard() {
        val s = session ?: return
        val p = s.player
        val meta = p.currentMediaItem?.mediaMetadata ?: return
        val title = meta.title?.toString().orEmpty().ifBlank { return }
        val artist = (meta.artist ?: meta.subtitle)?.toString().orEmpty()
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            /** Android 8+ 必须走渠道；低版本忽略。 */
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val ch = android.app.NotificationChannel(
                    CARD_CHANNEL,
                    "正在播放",
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(ch)
            }
            val intent = android.content.Intent(this, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val content = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(this, CARD_CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(this)
            }
            b.setSmallIcon(R.drawable.ic_notification)
            b.setContentTitle(title)
            b.setContentText(artist)
            b.setContentIntent(content)
            b.setOngoing(true)
            b.setShowWhen(false)
            b.setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            /**
             * MediaStyle 里**不绑 session token** —— 见下方说明。
             *
             * Media3 的 `androidx.media3.session.MediaSession` 没有公开
             * `getPlatformToken()`（api.txt 里查不到），而平台
             * `MediaStyle.setMediaSession()` 需要的是
             * `android.media.session.MediaSession.Token`。
             *
             * 但这条通知本来就不需要它：
             * ```
             * 系统媒体卡片识别「这是媒体通知」靠的是：
             *   ① 通知由 MediaStyle 渲染（有媒体样式外观）
             *   ② 同一个 App 有活跃的媒体会话（我们本来就有，实测在 Sessions Stack 里）
             * 卡片上的按钮走的是**会话**，不是通知里写死的 action
             * ```
             * 所以不给 token 也能显示，并且按钮点了会正确作用于当前播放器。
             */
            @Suppress("DEPRECATION")
            b.style = android.app.Notification.MediaStyle().setShowActionsInCompactView(0)
            startForeground(CARD_ID, b.build())
            if (!cardBuilt) {
                cardBuilt = true
                android.util.Log.i("ETKN-Music", "媒体卡片已建立：$title / $artist")
            }
        }.onFailure { android.util.Log.w("ETKN-Music", "媒体卡片建立失败", it) }
    }

    /**
     * 用户口径（#69）**系统问「要播哪首」时给答案**。
     *
     * 有记忆 → 返回那一首 + 起播位置，并异步真正开始播。
     * 没记忆 → 返回空列表（系统不会拉起播放，符合预期）。
     */
    private fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val music = applicationContext.graph.music
        val remembered = music.remembered.value
        android.util.Log.i(
            "ETKN-Music",
            "onPlaybackResumption: item=${remembered?.itemId} name=${remembered?.name}",
        )
        if (remembered == null) {
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), 0, 0L),
            )
        }
        applicationContext.graph.scope.launch {
            runCatching { music.resumeRemembered() }
        }
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(
                ImmutableList.of(music.resumeMediaItem(remembered)),
                0,
                remembered.positionMs,
            ),
        )
    }

    /**
     * 用户口径（#69/#70）**媒体键入口 —— 只在这里动手，且只在「没在播」时**。
     *
     * 实测背景：系统把 MediaButton 广播转成 `startService` 时**不带原始 Intent**，
     * 所以这里读不到 keyCode。keyCode 由 [EtknMediaButtonReceiver] 先存进
     * [MediaButtonHint]（同进程静态变量），这里取出来。
     *
     * 决策表（这是防"跳两首"的核心）：
     * ```
     * 正在播放  → 立刻 return，一个字都不做（Media3 自己处理，只切 1 首）
     * 没在播    → 把 remembered 播起来（播放键/下一首键都算"开始播放"）
     * ```
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pressed = MediaButtonHint.consume()
        val player = applicationContext.graph.music.player

        /** ① 硬判断：正在播就彻底放手。 */
        if (player.isPlaying && player.mediaItemCount > 0) {
            android.util.Log.i(
                "ETKN-Music",
                "onStartCommand: 正在播(${player.currentMediaItemIndex})，放手给 Media3，key=$pressed",
            )
            return super.onStartCommand(intent, flags, startId)
        }

        /** ② 只有媒体键触发的启动才接管（普通启动不动）。 */
        if (pressed == null) {
            android.util.Log.i("ETKN-Music", "onStartCommand: 无媒体键，跳过")
            return super.onStartCommand(intent, flags, startId)
        }

        /** ③ 暂停键就是暂停，不要播。 */
        if (pressed == KeyEvent.KEYCODE_MEDIA_PAUSE || pressed == KeyEvent.KEYCODE_MEDIA_STOP) {
            android.util.Log.i("ETKN-Music", "onStartCommand: 暂停键，忽略启动")
            return super.onStartCommand(intent, flags, startId)
        }

        /**
         * ④ 其余键（播放 / 下一首 / 上一首 / 耳机线控）在「没在播」时
         *    一律当作「开始播放」，接着上次的进度播，不跳歌。
         */
        android.util.Log.i("ETKN-Music", "onStartCommand: 未在播 + key=$pressed → 开始播放")
        applicationContext.graph.scope.launch {
            runCatching {
                /** 冷启动要等两个前提，缺一个都会"起来了但没声音"。 */
                withTimeoutOrNull(15_000L) {
                    applicationContext.graph.sessions.state.first {
                        it is xyz.etkn.mobile.data.session.SessionState.SignedIn
                    }
                }
                withTimeoutOrNull(5_000L) {
                    applicationContext.graph.music.remembered.first { it != null }
                }
                applicationContext.graph.music.resumeRemembered()
                android.util.Log.i(
                    "ETKN-Music",
                    "onStartCommand: 已触发播放 isPlaying=${applicationContext.graph.music.player.isPlaying}",
                )
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            applicationContext.graph.music.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }
}

/**
 * 用户口径（#69）**媒体键事件的静态中转站**。
 *
 * 为什么需要它：
 * ```
 * 按媒体键 → 系统发 MEDIA_BUTTON 广播 → 拉起进程 → Media3 startService（不带原始 Intent）
 *                                                       ↑ onStartCommand 读不到 keyCode
 * ```
 * [EtknMediaButtonReceiver] 在转发给 Media3 之前先把 keyCode 存进这里（同一进程），
 * Service 起来后 [consume] 读走，就知道用户刚才按了哪个键。
 */
object MediaButtonHint {
    @Volatile private var keyCode: Int? = null
    @Volatile private var at: Long = 0L

    fun remember(code: Int) {
        keyCode = code
        at = System.currentTimeMillis()
        android.util.Log.i("ETKN-Music", "MediaButtonHint: 记住 key=$code")
    }

    /** Service 起来时调用；**只认 10 秒内**的事件，避免误判。 */
    fun consume(): Int? {
        val code = keyCode
        val age = System.currentTimeMillis() - at
        keyCode = null
        return if (code != null && age in 0..10_000L) code else null
    }
}

/**
 * 用户口径（#69）**自己的 MediaButtonReceiver**。
 *
 * 照 QQ 音乐的做法（它也是自己的 Receiver + 播放 Service 双注册），
 * 在转发给 Media3 之前先记下 keyCode，解决「冷启动时拿不到按键」的问题。
 */
class EtknMediaButtonReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        event?.let {
            MediaButtonHint.remember(it.keyCode)
            android.util.Log.i("ETKN-Music", "Receiver: 收到媒体键 ${it.keyCode}")
        }
        /** 继续交给 Media3 的标准 Receiver（它负责拉起 Service / 转发 Session）。 */
        runCatching {
            androidx.media3.session.MediaButtonReceiver().onReceive(context, intent)
        }.onFailure {
            android.util.Log.w("ETKN-Music", "转发给 Media3 MediaButtonReceiver 失败", it)
        }
    }
}
