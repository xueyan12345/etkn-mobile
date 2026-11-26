package xyz.etkn.mobile.music

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import xyz.etkn.mobile.MainActivity
import xyz.etkn.mobile.graph

/**
 * Hosts a MediaSession around the shared music player so playback continues in the
 * background with a system media notification, lock-screen and Bluetooth controls.
 *
 * 用户口径（2026-11-27，#35）**必须真能响应系统/蓝牙的播放控制**。
 *
 * 修的 bug（用户实测）：
 *  「虽然我能选择默认应用，但是我的手机是 oppo，下拉系统状态栏，
 *    不能在播放音乐的那个方形控件上点击播放……
 *    连接蓝牙耳机之后，点击耳机的播放，或者下一集能直接唤醒 etkn 的音乐播放」
 *
 * 根因：原来只 `.setSessionActivity(open)`，**完全没有 `MediaSession.Callback`**。
 * 系统状态栏那个方形媒体控件、蓝牙耳机线控发的都是 MediaController 命令，
 * 走的是 Session 的 Callback；没有 Callback 就没人接，表现就是「点了没反应」。
 *
 * 另外「App 不在后台点耳机能续播」靠的是 `onPlaybackResumption`：
 * 系统会先查询「上次播到哪」，App 必须在这里返回一组 MediaItem + 起始位置，
 * 系统才能把播放器拉起来。
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
        // Artwork for the notification goes through the image client (token only to the ETKN origin).
        val bitmaps = DataSourceBitmapLoader.Builder(this)
            .setDataSourceFactory(OkHttpDataSource.Factory(graph.http.images))
            .build()
        session = MediaSession.Builder(this, graph.music.player)
            .setSessionActivity(open)
            .setBitmapLoader(bitmaps)
            .setCallback(MusicCallback())
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

    /**
     * 用户口径（#35）**媒体按键/系统控件的真正入口**。
     *
     * 三个关键点：
     *  · `onConnect`：放开播放/暂停/上一首/下一首这些命令，系统控件才会显示对应按钮
     *  · `onPlaybackResumption`：App 不在后台时点耳机 → 系统问「上次播到哪」，
     *    这里返回上次那首 + 它的进度，系统才能把播放续上
     *  · `onAddMediaItems`：把系统传回来的 MediaItem（我们只带了 id）解析成可播的条目
     */
    private inner class MusicCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            /**
             * 声明允许的命令。
             *
             * 系统状态栏的方形控件只显示 `COMMAND_PLAY_PAUSE`、上一首/下一首对应的
             * `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` / `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM`；
             * 不声明的话，那里只会显示一个灰的点不动。
             */
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .addAllCommands(
                    listOf(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_PREPARE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_METADATA,
                    ),
                )
                .removeAllCommands(Player.COMMAND_SET_MEDIA_ITEM)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }

        /**
         * 用户口径（#35）**App 不在后台、点耳机按钮也能续播上次那首**。
         *
         * 系统在「MediaSession 已经不存在」时会先调这个方法，
         * 拿回一组 MediaItem + startIndex + startPositionMs，
         * 然后用这些把 Service 拉起来接着播。
         *
         * 这里从 `graph.music.remembered` 读上次的歌和进度 ——
         * 就是迷你条上显示的那份「记忆状态」。
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val remembered = applicationContext.graph.music.remembered.value
            if (remembered == null) {
                // 从没播过：返回空，系统就不会拉起播放（符合预期）
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L),
                )
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    /* mediaItems = */ listOf(
                        applicationContext.graph.music.resumeMediaItem(remembered),
                    ),
                    /* startIndex = */ 0,
                    /* startPositionMs = */ remembered.positionMs,
                ),
            )
        }

        /**
         * 系统把 MediaItem 传回来时要变成能播的条目。
         *
         * 我们的 MediaItem 里只塞了 `itemId`（不塞直链，因为直链会过期），
         * 真正的解析在 `MusicConnection.resolve()` 里按 itemId 现取 —— 所以这里原样返回即可。
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<androidx.media3.common.MediaItem>,
        ): ListenableFuture<MutableList<androidx.media3.common.MediaItem>> =
            Futures.immediateFuture(mediaItems)
    }
}
