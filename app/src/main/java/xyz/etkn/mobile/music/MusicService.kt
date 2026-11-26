package xyz.etkn.mobile.music

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.common.MediaItem as Media3Item
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import xyz.etkn.mobile.MainActivity
import xyz.etkn.mobile.graph

/**
 * 音乐播放服务。
 *
 * 用户口径（2026-11-27，#42）**必须是 MediaLibraryService，否则系统卡片按钮永远是灰的**。
 *
 * 官方文档（Android Developers / Media3 / Background playback）原文：
 * ```
 * By default, playback resumption is turned off. This means the user can't resume
 * playback when your service isn't running.
 * To opt-in to this feature, you need to declare a media button receiver and
 * implement the onPlaybackResumption method.
 *
 * Note: The Android System UI resumption notification is only available
 * if you implement a MediaLibraryService to serve a content library.
 * ```
 *
 * 用户实测对照：
 *  · 系统自带音乐设为默认（后台已彻底关闭）→ 媒体卡片中间播放键**可点**，点了直接续播
 *  · 换成 ETKN（原实现是 MediaSessionService）→ 同一个位置**灰色点不动**
 *
 * 差别就在这里：系统 UI 的「续播卡片」只对 MediaLibraryService 开放。
 * 这不是 OPPO 特有的适配问题，是 Android 平台标准 API 的要求，所有机型通用。
 *
 * 三个要点：
 *  · 继承 `MediaLibraryService`，`onGetSession` 返回 `MediaLibrarySession`
 *  · `onPlaybackResumption` 用**带 `isForPlayback` 的新签名**
 *    （蓝牙耳机直连播放时系统会传 true）
 *  · Service 起来时把「上次那首」预装进播放器并 prepare —— 这样卡片才有
 *    封面、总时长、可点的播放键（对照系统音乐那张卡片：00:00 / 04:00）
 */
class MusicService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

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
        session = MediaLibrarySession.Builder(this, graph.music.player, MusicCallback())
            .setSessionActivity(open)
            .setBitmapLoader(bitmaps)
            .build()
        /**
         * 用户口径（#42）**起来就把上次那首预装进播放器**。
         *
         * 为什么必须这么做：系统卡片要显示「封面 / 总时长 / 可点的播放键」，
         * 前提是播放器里已经有一个 MediaItem 且已 prepare（duration 已知）。
         * 播放器空着的时候 `mediaItemCount == 0`、`duration == 0`、
         * `isCommandAvailable(COMMAND_PLAY_PAUSE) == false` → 卡片整块是灰的。
         *
         * 对照图（系统音乐）：00:00 / 04:00 —— 时长已知，正是"预装 + prepare"的结果。
         */
        applicationContext.graph.music.restoreForResumption()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        /**
         * 用户口径（#42）**App 被划掉时不要再 clearMediaItems**。
         *
         * 原来这里调 `graph.music.stop()`，而 `stop()` 里有 `clearMediaItems()` ——
         * 播放器被清空 → 系统卡片立刻变灰、时长归零、续播入口消失。
         *
         * 现在只暂停、保留 MediaItem：
         *  · 卡片继续显示封面/歌名/时长
         *  · 点播放键能立刻接着播
         *  · Service 由系统按需回收，回收后走 onPlaybackResumption 续播
         */
        applicationContext.graph.music.pause()
        if (session?.player?.playWhenReady == true) return
        stopSelf()
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    /**
     * 用户口径（#42）**系统 UI / 蓝牙耳机的控制入口**。
     */
    private inner class MusicCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            /**
             * 声明允许的命令。
             *
             * 系统状态栏那个方形控件只用这几个：
             * `COMMAND_PLAY_PAUSE`、上一首/下一首对应的 SEEK_TO_*_MEDIA_ITEM。
             * 不声明的话那里只有一个灰点，点不动。
             */
            val builder = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            builder.add(Player.COMMAND_PLAY_PAUSE)
            builder.add(Player.COMMAND_PREPARE)
            builder.add(Player.COMMAND_STOP)
            builder.add(Player.COMMAND_SEEK_TO_NEXT)
            builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS)
            builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            builder.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            builder.add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            builder.add(Player.COMMAND_GET_TIMELINE)
            builder.add(Player.COMMAND_GET_METADATA)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(builder.build())
                .build()
        }

        /**
         * 用户口径（#42）**App 不在后台时，点卡片/蓝牙耳机能续播上次那首**。
         *
         * 官方签名带 `isForPlayback`：
         *  · `true` = 蓝牙耳机/车机这类「真的要播」，系统会拉起前台服务
         *  · `false` = 系统 UI 只是恢复界面
         *
         * 两参数那个旧签名在新版 Media3 里可能不被调用，所以必须用这个。
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val remembered = applicationContext.graph.music.remembered.value
            if (remembered == null) {
                // 从没播过：返回空列表，系统就不会拉起播放（符合预期）
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.of(), 0, 0L,
                    ),
                )
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    /* mediaItems = */ ImmutableList.of(
                        applicationContext.graph.music.resumeMediaItem(remembered),
                    ),
                    /* startIndex = */ 0,
                    /* startPositionMs = */ remembered.positionMs,
                ),
            )
        }

        /**
         * 系统把 MediaItem 传回来时原样返回。
         *
         * 我们的 MediaItem 只带 `itemId`（直链会过期，不塞），
         * 真正解析在 `MusicConnection.resolve()` 里按 itemId 现取。
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<Media3Item>,
        ): ListenableFuture<MutableList<Media3Item>> = Futures.immediateFuture(mediaItems)

        /**
         * 内容库浏览：ETKN 不做 MediaBrowser 浏览树（不需要 Android Auto 那套），
         * 返回空库即可 —— 系统只要求"类型是 MediaLibraryService"，不要求真的有曲库树。
         */
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<Media3Item>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<Media3Item>>> =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
    }

    /** 根节点（空库的占位 root）。 */
    private fun rootItem(): Media3Item = Media3Item.Builder()
        .setMediaId("etkn-root")
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setTitle("ETKN 音乐")
                .build(),
        )
        .build()
}
