package xyz.etkn.mobile.music

import androidx.media3.common.Player
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import xyz.etkn.mobile.MainActivity
import xyz.etkn.mobile.graph
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

/**
 * 音乐播放服务：托管一个 MediaSession，让播放能在后台继续，
 * 并提供系统通知、锁屏和蓝牙控制。
 *
 * ## 用户口径（2026-11-27，#60）**回归上游极简实现，删掉我加的所有补丁**
 *
 * 用户原话：
 * ```
 * 「上一首、下一首那个，我估计是你之前咱们一直整那个播放组件跟系统自带的音乐
 *   显示的不一样的事弄的，现在我不需要音乐组件在 APP 不在后台的时候显示播放按钮，
 *   你可以把这部分你之前弄的那些代码啥都去掉，我猜估计是你代码套重了，
 *   我现在只需要连接蓝牙、耳机，在耳机上按下一首的按钮，能直接唤醒它播放就行，
 *   其余的不用 APP 不在后台的时候，非要在它上面显示了。
 *   然后正常播放音乐的话，本来在播放当中，音乐组件就是正常能显示上一首、下一首
 *   播放，还有音乐的专辑图的，本来没开始改之前就好使。
 *   所以你检查检查是不是你自己代码弄太多，弄重复了，
 *   下一步我觉得你应该删代码，而不是在打补丁」
 * ```
 *
 * 确实是我把代码搞重了。这个文件从 853 行回到上游的 52 行结构。
 *
 * ### 删掉的东西（全是我为「App 不在后台也要点亮系统卡片」加的）
 * ```
 * ✗ MediaLibraryService        —— 为了"系统卡片播放键可点亮"引入，
 *                                 它的 Callback 反而让"下一首"被执行两次
 * ✗ MusicCallback（320 行）    —— onConnect / onPlaybackResumption /
 *                                 onPlayerCommandRequest / 内容库浏览
 * ✗ onStartCommand（140 行）   —— 媒体键分流，"点一次跳两首"的另一个元凶
 * ✗ MediaButtonHint            —— 按键静态中转 + 去重窗口
 * ✗ EtknMediaButtonReceiver    —— 自定义 Receiver
 * ✗ syncSessionMetadata        —— 强推 metadata 给 Session
 * ✗ enterForeground            —— 绕 ColorOS 冻结的前台通知
 * ✗ 静默起播相关               —— 在 MusicConnection 里，把音量设 0 再还原
 *                                 （就是搞出"完全没声音"的那个）
 * ```
 *
 * ### 保留下来的能力
 * ```
 * ✓ 后台播放 + 系统媒体通知 + 锁屏控制
 * ✓ 蓝牙/耳机按键控制（MediaSession 自带，上游 52 行版本本来就有）
 * ✓ 正常播放时系统卡片显示歌名/歌手/专辑图/上一首/下一首（本来就好使）
 * ```
 *
 * 上一首/下一首回到由 `MediaSession` 默认处理 —— 一次按键只切一首。
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
                 * 用户口径（2026-11-27，#69）**蓝牙唤醒要能真的开始播**。
                 *
                 * 实测（adb，force-stop 后按 KEYCODE_MEDIA_NEXT）：
                 * ```
                 * MediaSessionStack: getMediaButtonSession() mMediaButtonSession=xyz.etkn.mobile/.../572
                 * onSessionPlaybackStateChanged: playbackState=PAUSED(2)
                 * ```
                 * Session 建起来了、按键也路由到了我们，但状态停在 PAUSED ——
                 * **没人执行"播放"**。`onPlaybackResumption` 没被调用，
                 * 那个回调只在系统主动问「你要播什么」时才触发，
                 * 这次系统只是把按键转发给已存在的 Session。
                 *
                 * 所以要在这里接住「播放」类命令：没在播就起播。
                 * 上一首/下一首**不在这里处理** —— 交给 MediaSession 默认逻辑，
                 * 保证"点一次只切一首"（这是之前反复踩的坑）。
                 */
                override fun onPlayerCommandRequest(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int,
                ): Int {
                    val music = applicationContext.graph.music
                    val p = music.player
                    val isPlayPause = playerCommand == Player.COMMAND_PLAY_PAUSE
                    val isSeekNext = playerCommand == Player.COMMAND_SEEK_TO_NEXT ||
                        playerCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM

                    android.util.Log.i(
                        "ETKN-Music",
                        "onPlayerCommandRequest: cmd=$playerCommand playing=${p.isPlaying} " +
                            "idx=${p.currentMediaItemIndex} count=${p.mediaItemCount}",
                    )

                    /**
                     * 用户口径（2026-11-27，#27）**没在播时，「播放」和「下一首」都等于开始播**。
                     *
                     * 用户原话：
                     *  「我要求是 APP 如果没在后台的话，播放和下一曲都要唤醒 APP 的音乐播放的开始播放，
                     *    下一曲这个按键也是播放，只有真的在播放的时候，下一曲才是下一曲」
                     *
                     * 所以：
                     * ```
                     * 没在播 + PLAY_PAUSE  → resumeRemembered()（接着上次进度播）
                     * 没在播 + NEXT        → 同上（不跳歌，直接开始播）
                     * 正在播 + PLAY_PAUSE  → 返回 -1，交给默认逻辑（暂停）
                     * 正在播 + NEXT        → 返回 -1，交给默认逻辑（切下一首，只切一首）
                     * ```
                     * 关键：只在「没在播」时才自己动手，其余全部放手给 Media3 ——
                     * 这样既解决了冷启动唤醒，又不会重复执行导致"点一次跳两首"。
                     */
                    if (!p.isPlaying && (isPlayPause || isSeekNext)) {
                        android.util.Log.i(
                            "ETKN-Music",
                            "未在播 + 媒体键(${if (isSeekNext) "NEXT" else "PLAY"}) → 开始播放",
                        )
                        applicationContext.graph.scope.launch {
                            runCatching { music.resumeRemembered() }
                        }
                        return androidx.media3.session.SessionResult.RESULT_SUCCESS
                    }
                    return -1
                }

                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    isForPlayback: Boolean,
                ) = this@MusicService.onPlaybackResumption(mediaSession, controller, isForPlayback)
            })
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * 用户口径（2026-11-27，#68）**蓝牙耳机按「下一首」要能唤醒并播放**。
     *
     * 用户原话：「蓝牙耳机触发下一首不能唤醒播放了」
     *
     * 是我上一版删过头了：媒体键在 App 已关闭时，系统会先拉起 Service，
     * 然后**调用 `onPlaybackResumption` 问「你要播哪首」**。
     * 上游 52 行的 `MediaSessionService` 没有实现它，
     * 系统拿不到要播的内容，于是"唤醒了但不出声"。
     *
     * 这次只补最小实现，不重新引入之前那套 320 行的 MusicCallback：
     * ```
     * 有记忆 → 返回那一首 + 起播位置，并异步真正开始播
     * 没记忆 → 返回空列表（系统不会拉起播放，符合预期）
     * ```
     *
     * 为什么不开 `MediaLibraryService`：那个会让系统控制中心的按钮在
     * App 关闭时也亮着 —— 用户明确说不需要，且它导致过"点一次跳两首"。
     * `MediaSession.Callback.onPlaybackResumption` 就够蓝牙唤醒用了。
     */
    private fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val music = applicationContext.graph.music
        val remembered = music.remembered.value
        android.util.Log.i(
            "ETKN-Music",
            "onPlaybackResumption: item=${remembered?.itemId} name=${remembered?.name} pos=${remembered?.positionMs}",
        )
        if (remembered == null) {
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(ImmutableList.of(), 0, 0L),
            )
        }
        /** 真正开始播（进主线程异步，避免阻塞系统回调）。 */
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
