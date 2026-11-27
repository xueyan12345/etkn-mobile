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
                 * 只实现 `onPlaybackResumption`：系统主动问「要播哪首」时给答案。
                 * **不实现 `onPlayerCommandRequest`** —— 上一首/下一首/播放暂停
                 * 全部交给 MediaSession 默认逻辑，避免与 Receiver 那条路重复执行。
                 */
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    isForPlayback: Boolean,
                ) = this@MusicService.onPlaybackResumption(mediaSession, controller)
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
