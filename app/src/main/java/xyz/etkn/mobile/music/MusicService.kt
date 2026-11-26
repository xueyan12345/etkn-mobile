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
import kotlinx.coroutines.launch

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
        /**
         * 用户口径（2026-11-27，#1 真根因）**主动进入前台服务状态，避免被 ColorOS 冻结**。
         *
         * adb 实测证据（一加 13 / ColorOS 16）：
         * ```
         * OplusHansManager: freeze uid: 10687 xyz.etkn.mobile pids: [11210] scene: LcdOff
         * MediaPlayerWrapper: The session was destroyed xyz.etkn.mobile
         * ```
         *
         * 结论：ColorOS 的 OplusHansManager 一息屏（scene: LcdOff）就冻结整个进程，
         * MediaSession 被销毁 → 状态栏卡片变灰、点不了、队列恢复也没机会跑。
         *
         * 这不是我们代码的 bug，是 ColorOS 的后台管理策略。
         * 官方对抗手段只有一个：**让 Service 常驻前台**（有持续通知就不冻结）。
         *
         * `MediaSessionService` 本来会在「真正播放」时自动进前台，
         * 但我们恢复的队列是 prepare 但不 play，不算播放中，
         * 于是它一直是后台状态 → 一息屏就被冻。这里显式进前台。
         *
         * 注意必须 try-catch：Android 12+ 对后台启动前台服务有限制，
         * 失败也不能让 Service 崩掉。
         */
        runCatching { enterForeground() }
        /**
         * 用户口径（#1）**Session 必须知道「当前在播哪首」**，
         * 否则系统控制中心拿到的是 `queueTitle=null, size=0`，
         * 显示成「未在播放」→ 播放键灰。
         *
         * 实测：`init done: mediaItemCount=1 queue=1`（MusicConnection 里确实有 1 条）
         * 但 `dumpsys media_session` 显示 `size=0` ——
         * 因为 Session 的 PlaybackState 停留在 `NONE(0)`，Media3 不会往系统推 queue。
         *
         * 这里在 Session 建好后强制同步一次：把播放器的当前项 metadata
         * 显式喂给 Session（`setMediaItem` 已做，但状态没转过去）。
         */
        runCatching { syncSessionMetadata() }
    }

    /**
     * 把播放器的当前 MediaItem metadata 显式同步到 Session。
     *
     * 作用：让 `dumpsys media_session` 里 `metadata` 不再是 `description=null`，
     * 系统控制中心才能显示歌名/歌手/时长，并把播放键点亮。
     */
    private fun syncSessionMetadata() {
        val s = session ?: return
        val player = s.player
        val item = player.currentMediaItem ?: return
        val meta = item.mediaMetadata
        android.util.Log.i(
            "ETKN-Music",
            "syncSession: title=${meta.title} artist=${meta.artist} dur=${meta.durationMs} " +
                "count=${player.mediaItemCount} state=${player.playbackState}",
        )
        /**
         * 关键：`replaceMediaItem` 不会让 Media3 重算 PlaybackState，
         * 所以 Session 依旧报 NONE。必须调 `prepare()` ——
         * 它会把状态推成 BUFFERING，Session 才会把 metadata/queue 同步给系统。
         *
         * 实测依据：
         * ```
         * syncSession: count=1 state=2   ← player 内部状态是 PAUSED（对）
         * dumpsys:     state=NONE(0)     ← Session 报给系统的还是 NONE（错）
         * ```
         * 说明 Session 没收到 player 的状态通知，需要主动触发一次。
         */
        runCatching { player.prepare() }
    }

    /**
     * 把本 Service 提升为前台服务（带一条常驻通知）。
     *
     * 用 `mediaPlayback` 类型，Android 14+ 要求该类前台服务必须有通知。
     */
    private fun enterForeground() {
        val nm = getSystemService(android.content.Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        val channelId = "etkn_music_service"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId,
                    "音乐播放服务",
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("ETKN 音乐")
            .setContentText("准备就绪")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(
                1,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(1, notification)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session


    override fun onTaskRemoved(rootIntent: Intent?) {
        /**
         * 用户口径（2026-11-27，#1 最终修复）**App 被划掉时绝对不能 stopSelf()**。
         *
         * 系统音乐为什么"不用点，播放键就是亮的"（用户原话）：
         * 因为它的 Service 一直在跑，MediaSession 一直存在，
         * ColorOS 的控制中心有对象可绑 → 按钮亮着、能点。
         *
         * 我们原来这里 `stopSelf()` —— Service 一停，MediaSession 立刻销毁，
         * 控制中心拿不到任何 session，日志就是：
         * ```
         * OplusQsMediaPanelViewController --> bindMediaCarouselController
         *     historyList is empty
         * ```
         * 没有历史、没有 Session → 按钮是暗的，点也不发命令（所以点了完全没反应）。
         *
         * 现在的做法：
         *  · 只把播放暂停，**保留 Session 和 MediaItem**
         *  · 不 stopSelf，Service 靠前台服务状态常驻（有通知，ColorOS 不冻）
         *  · 这样划掉 App 之后，控制中心依然能看到「雨一直下 / 张宇」，
         *    播放键是亮的，点了就接着播
         *
         * 代价：会常驻一条通知。但这是系统音乐同样的行为，用户可接受。
         */
        runCatching { applicationContext.graph.music.pause() }
        // 不调用 stopSelf()：让 Session 活着，控制中心才有对象可绑
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
            val music = applicationContext.graph.music
            val remembered = music.remembered.value
            if (remembered == null) {
                // 从没播过：返回空列表，系统就不会拉起播放（符合预期）
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        ImmutableList.of(), 0, 0L,
                    ),
                )
            }
            /**
             * 用户口径（2026-11-27，#13）**系统点播放要真的出声**。
             *
             * 只返回 `MediaItem` 的话，Media3 会拿占位 URL 去 prepare，
             * 而 `resolve()` 需要网络换真直链 —— 系统那边的 `PlaybackState`
             * 会停在 BUFFERING 很久，用户感觉「点了没反应」。
             *
             * 这里在返回 item 的同时**主动触发一次真实播放**（异步），
             * 保证系统卡片点下去之后确实开始播。
             */
            applicationContext.graph.scope.launch {
                runCatching { music.resumeRemembered() }
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    /* mediaItems = */ ImmutableList.of(
                        music.resumeMediaItem(remembered),
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
         * 用户口径（2026-11-27，#26）**系统/蓝牙/车机发来的「播放/下一首」命令要真执行**。
         *
         * 用户实测（车上连着 CarLife）：
         *  「我在车上播放也一样连接CarLife，点击方向盘的下一曲，就可以直接播放，而且是继续播放」
         *
         * adb 实测：App 被 force-stop 后按 KEYCODE_MEDIA_NEXT（keyevent 87），
         * 进程**能**被拉起（说明自启动白名单生效了），但只恢复到 PAUSED 原曲，
         * 没有真的跳到下一首 —— 因为命令没人接。
         *
         * 这个回调就是接命令的地方：
         *  · `COMMAND_PLAY_PAUSE` → 播放/暂停（要等登录态 + 真直链）
         *  · `COMMAND_SEEK_TO_NEXT` / `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` → 下一首
         *  · `COMMAND_SEEK_TO_PREVIOUS*` → 上一首
         *
         * 关键：这些命令可能在「App 刚被拉起、token 还没恢复」时到达，
         * 所以统一走 `music.executeSystemCommand()`，它内部会等会话就绪。
         */
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            android.util.Log.i(
                "ETKN-Music",
                "onPlayerCommandRequest: cmd=$playerCommand from=${controller.packageName}",
            )
            val music = applicationContext.graph.music
            when (playerCommand) {
                Player.COMMAND_PLAY_PAUSE -> applicationContext.graph.scope.launch {
                    runCatching { music.systemPlay() }
                }
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                -> applicationContext.graph.scope.launch {
                    runCatching { music.systemNext() }
                }
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                -> applicationContext.graph.scope.launch {
                    runCatching { music.systemPrevious() }
                }
                else -> return android.os.Bundle.EMPTY.let {
                    /** 不认识的命令：返回 -1 让 Media3 走默认处理。 */
                    -1
                }
            }
            /** `RESULT_SUCCESS`(=0) 表示"已处理"。 */
            return androidx.media3.session.SessionResult.RESULT_SUCCESS
        }

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
