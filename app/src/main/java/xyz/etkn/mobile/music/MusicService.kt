package xyz.etkn.mobile.music

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import xyz.etkn.mobile.MainActivity
import xyz.etkn.mobile.graph

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
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

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
