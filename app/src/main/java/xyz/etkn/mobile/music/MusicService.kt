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

    companion object {
        /**
         * 服务实例是否存活。
         *
         * 用户实测（2026-11-28，#192）**卡片一次显示一次不显示**，
         * adb 抓到故障态的真实状态是：
         * ```
         * 进程活着，但 ccc=0（MusicService 不在）、会话空、通知无
         * ```
         * 也就是说**服务实例本身没能起来**，而它的创建/销毁时刻
         * 受 ColorOS 各种策略影响，我们在代码里堵不住每一条路径。
         *
         * 所以改成「自我修复」：前台 Activity 每次 onStart 都检查这个标记，
         * 没起来就重新拉一次。不依赖「谁杀了它」，只看「它现在在不在」。
         */
        @Volatile var alive = false

        /** 上次「媒体键启动」的时间戳，用于 onStartCommand 防抖（#204）。 */
        @Volatile private var lastKeyStartAt = 0L

        /** 自动重建的次数与时间窗，用于限流（#199）。 */
        @Volatile private var rebuildCount = 0
        @Volatile private var lastRebuildAt = 0L
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
        /** 标记实例存活 —— 用于诊断「服务是否真的起来了」。 */
        alive = true
        super.onCreate()

        /**
         * 用户口径（2026-11-28，#220）**杀掉 App 后卡片不该再自己冒出来**。
         *
         * ## 现场证据（dumpsys activity services）
         *
         * ```
         * stopIfKilled=false          ← START_STICKY：服务被杀，系统会重建
         * startRequested=true         ← 启动请求一直挂着
         * restartTime=-1m13s          ← 确实发生过自动重启
         * ```
         *
         * ## 为什么之前的改法会翻车（务必别重犯）
         *
         * 上一版在 `onTaskRemoved` 里 `MusicSessionHolder.release()` 释放会话，
         * 结果卡片变成「打开一次有、打开一次没有」——
         * **服务还活着、会话却被释放了**，下次打开 App 时没有会话可用。
         *
         * ## 这次只拦「被系统重建」这一次，别的一概不碰
         *
         * ```
         * 用户划掉任务 → 记一个标记 taskKilled = true（只写标记，不碰会话/播放）
         * 系统重建服务 → onCreate 看到标记
         *              → 说明「不是用户主动打开 App」，直接把服务停掉，不再建会话
         * 用户重新打开 App → 标记被清 → 正常流程，一切照旧
         * ```
         *
         * 关键：**只写一个布尔标记**，不释放会话、不清队列、不动续播记忆。
         * 所以即使在「不该拦」的情况下误拦了，代价也只是这一次服务没起来，
         * 用户下次打开 App 立刻恢复 —— 不会像上次那样把会话搞坏。
         */
        val killedPrefs = getSharedPreferences("etkn_task", MODE_PRIVATE)
        if (killedPrefs.getBoolean("taskKilled", false) &&
            !killedPrefs.getBoolean("appForeground", false)
        ) {
            android.util.Log.i("ETKN-Music", "onCreate: 用户已划掉任务 → 不再重建服务")
            killedPrefs.edit().putBoolean("taskKilled", false).apply()
            stopSelf()
            return
        }

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
                    /**
                     * 用户口径（2026-11-28，#191）**暂停时按下一首 = 继续播放**。
                     *
                     * 用户实测（CarLife 车机方向盘）：
                     * ```
                     * 「我按方向盘上的下一首歌，它没有继续播放，它是直接下一首了，
                     *   然后也不播放，必须得手动点击那个卡片上的暂停按钮，它才播放」
                     * ```
                     *
                     * ## 原因
                     *
                     * 下面这行 `super.onPlayerCommandRequest` 把命令直接交给 Media3，
                     * 而 Media3 对 `SEEK_TO_NEXT` 的语义就是「切歌」——
                     * **不管当前是不是暂停，切完仍然是暂停**。
                     * 所以车机方向盘按下一首 → 歌变了但不出声 → 用户以为没反应。
                     *
                     * ## 用户要的行为（原话）
                     * ```
                     * 「如果当前这个音乐是暂停的状态，点下一曲就是播放；
                     *   如果当前这个音乐是播放的状态，点下一曲就是下一曲」
                     * ```
                     *
                     * 这正是蓝牙耳机/CarLife 的通用直觉：没在播的时候任何媒体键
                     * 都应该「把音乐叫起来」。
                     *
                     * ## 只做这一件事，不越界
                     *
                     * ```
                     * 暂停 + NEXT/PREV  → 切歌，然后把 playWhenReady 置 true（开始播）
                     * 播放中 + NEXT     → 原样放行（照常下一首）
                     * 视频占用          → 上面那段已经拦掉了，不会走到这
                     * ```
                     * 不碰 `restoreForResumption()`、不碰蓝牙 Receiver、
                     * 不碰 `saveMemory()` —— 续播红线完好。
                     */
                    /**
                     * 用户口径（2026-11-28，#196）**把收到的命令原样记下来**。
                     *
                     * ## 为什么必须记原始值
                     *
                     * 上一版我凭「猜」用了位运算：
                     * ```kotlin
                     * playerCommands and (COMMAND_SEEK_TO_NEXT or ...) != 0
                     * ```
                     * 结果 Media3 的常量是**位掩码**：
                     * ```
                     * COMMAND_PLAY_PAUSE   = 1  (0001)
                     * COMMAND_SEEK_TO_NEXT = 9  (1001) ← 含 bit0
                     * ```
                     * `1 and 9 = 1 ≠ 0` → 用户点暂停被当成「下一首」，
                     * 暂停键直接变成切歌。
                     *
                     * 根子是**没查清车机方向盘到底发什么命令**就动手。
                     *
                     * ## 现在只记录，不改行为
                     *
                     * 把每一次收到的命令、原始值、以及能否解出已知名字都打出来：
                     * ```
                     * ETKN-Cmd: raw=9 playing=false -> SEEK_TO_NEXT
                     * ETKN-Cmd: raw=1 playing=true  -> PLAY_PAUSE
                     * ```
                     * 用户上车按几个键，把 App 里导出的日志发来，
                     * 就能拿到真实映射，再按真实值写规则 —— 不再猜。
                     *
                     * 注意：**这里绝对不 return**，继续走 `super` 默认逻辑，
                     * 保证暂停/播放/切歌行为与线上一致，不受诊断影响。
                     */
                    runCatching {
                        android.util.Log.i(
                            "ETKN-Cmd",
                            "raw=${playerCommands} hex=0x${playerCommands.toString(16)} " +
                                "playing=${graph.music.player.isPlaying} " +
                                "state=${graph.music.player.playbackState} " +
                                "name=${commandName(playerCommands)}",
                        )
                    }

                    /**
                     * 用户口径（#195）**这里完全回滚到默认行为**。
                     *
                     * ## 事故（我引入的）
                     *
                     * 用户实测：
                     * ```
                     * 「我现在在系统状态栏上点暂停按钮不是暂停功能了，是下一曲」
                     * ```
                     *
                     * ## 真因：Media3 的命令常量是组合位
                     *
                     * 官方源码常量值：
                     * ```
                     * COMMAND_PLAY_PAUSE       = 1   (二进制 0001)
                     * COMMAND_SEEK_TO_PREVIOUS = 7
                     * COMMAND_SEEK_TO_NEXT     = 9   (二进制 1001) ← 本身就含 bit0！
                     * ```
                     * 我原来的判断：
                     * ```kotlin
                     * playerCommands and (COMMAND_SEEK_TO_NEXT or ...) != 0
                     * ```
                     * 点暂停时 `playerCommands = 1`，而 `1 and 9 = 1 ≠ 0`
                     * → 被误判成「下一首」→ 切歌 + 播放。
                     * **暂停键因此变成了下一曲。**
                     *
                     * ## 教训
                     *
                     * Media3 的 `COMMAND_*` 不是互斥枚举，是**位掩码**，
                     * 小值的组合位会被大常量覆盖。判命令必须用
                     * `playerCommands == COMMAND_X`（精确相等），
                     * 或只检查**独立**的单一位，不能用 or 组合后做 and。
                     *
                     * ## 现状
                     *
                     * 整段自定义拦截删除，恢复 `super`：
                     * 暂停就是暂停、下一首就是下一首，与 v1.20.8 之前一致。
                     *
                     * 「暂停时按下一首=开始播放」这个需求暂时不做，
                     * 等有实测条件（车机/蓝牙）时再按精确匹配实现。
                     */
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
         * 用户口径（2026-11-28，#200）**蓝牙耳机唤醒又坏了，这是回归 bug**。
         *
         * 用户原话：
         * ```
         * 「蓝牙耳机唤醒是让你给我删了吗」
         * 「拉起来播放」（App 完全退出后，按耳机键要能拉起并播放）
         * 「本来就是这样的啊设置好了都，这个功能之前的版本是好使的，
         *   不知道哪个版本开始就不好使了」
         * ```
         *
         * ## 回归点（git 查到的，不是猜）
         *
         * `b1832b3`「refactor: 删代码，不打补丁」（11-27）把
         * **MusicService.kt 从 853 行砍到 93 行**，同时删掉了这三段：
         * ```kotlin
         * // ① session 建好后，同步把 remembered 的 MediaItem 塞进 player
         * if (mem != null && player.mediaItemCount == 0) {
         *     player.setMediaItem(resumeMediaItem(mem))
         * }
         * // ② 监听 remembered，一拿到也装进 player
         * // ③ runCatching { player.prepare() } 触发一次 Session 状态同步
         * ```
         * 这几段是**专门为「App 不在前台时蓝牙按键能认到」**写的，
         * 当时代码注释里就记着实机日志：
         * ```
         * OplusMediaControlService: D players is empty
         * ```
         * 也就是说：player 里没有 MediaItem → ColorOS 判定 players 为空
         * → 把会话踢出媒体栈 → 蓝牙按键没有接收者 → 按了没反应。
         *
         * ## 现在只恢复最小的一块
         *
         * 不整段恢复（当时删它是因为「代码套重了」），只补这一步：
         * ```
         * Session 建好 → player 是空的但磁盘有记忆 → 同步塞一首进去
         * ```
         * 目的只有一个：让 ColorOS 的媒体栈里始终有一个有内容的会话，
         * 蓝牙按键才找得到落点，才能把 App 拉起来播放。
         *
         * 不动 `restoreForResumption()`（真正的续播解析）、
         * 不动 `MediaLibraryService`、不动 `onPlaybackResumption`。
         */
        runCatching {
            val player = graph.music.player
            val mem = graph.music.remembered.value
            if (mem != null && player.mediaItemCount == 0) {
                val item = graph.music.resumeMediaItem(mem)
                player.setMediaItem(item)
                /** 触发一次状态同步，让 Session 立刻把 metadata 推给系统。 */
                player.prepare()
                android.util.Log.i(
                    "ETKN-Music",
                    "Session 建立后预装 MediaItem（蓝牙唤醒前提）：${mem.name} count=${player.mediaItemCount}",
                )
            }
        }.onFailure { android.util.Log.w("ETKN-Music", "预装 MediaItem 失败", it) }

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
        /**
         * 用户口径（2026-11-28，#187）**服务一起来就必须进前台，不能等队列**。
         *
         * ## 实测证据（adb，决定性的）
         *
         * 故障态下抓到的真实状态：
         * ```
         * 进程 8175 还活着
         * 会话栈 = 空、通知 = 无、MusicService = 不存在
         * ```
         * 同时系统日志显示 ColorOS 在**反复杀进程**：
         * ```
         * 16:19:15.563  UserAwareMgr: process killed pid=6231 flags='bg'
         * 16:19:16.574  Start proc 8270 for service MusicService
         * 16:19:20.318  UserAwareMgr: process killed pid=8270 flags='bg'
         * 16:19:21.141  Background started FGS: MusicService
         * 16:19:23.958  UserAwareMgr: process killed pid=7669 flags='bg'
         * ```
         * `UserAwareMgr` 是 ColorOS 的后台管理，`flags='bg'` = 后台进程 → 可杀。
         *
         * ## 为什么会被判成后台
         *
         * 之前这段写的是：
         * ```kotlin
         * val meta = p.currentMediaItem?.mediaMetadata ?: return   // ← 队列没恢复就 return
         * val title = meta.title?.toString().orEmpty().ifBlank { return }
         * ```
         * 而 **MusicService 启动比队列恢复快**：
         * ```
         * Service.onCreate → updateMediaCard() → currentMediaItem 还是 null
         * → 直接 return → 从没调过 startForeground
         * → 服务一路都是"后台服务" → ColorOS 随时可杀
         * ```
         * `dumpsys` 里 `startForegroundCount: 0` 就是铁证。
         *
         * 于是形成交替：
         * ```
         * 打开 App → 服务起来、赶在被杀前恢复好队列 → 卡片亮
         * 下次打开 → 服务刚起来就被判 bg 杀掉 → 卡片灭
         * ```
         *
         * ## 现在：没有标题也要进前台
         *
         * 用占位文案「正在加载」先进前台，把服务钉住；
         * 队列恢复后 `onMediaItemTransition` / `onMediaMetadataChanged`
         * 会再调一次，把文案换成真实歌名。
         */
        val meta = p.currentMediaItem?.mediaMetadata
        val title = meta?.title?.toString().orEmpty().ifBlank { "正在加载…" }
        val artist = (meta?.artist ?: meta?.subtitle)?.toString().orEmpty()
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            /** Android 8+ 必须走渠道。 */
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
            /**
             * 用户口径（2026-11-28，#176）**必须绑 MediaSession token，系统才认媒体控件**。
             *
             * 官方文档原话（`Notification.MediaStyle` 页面）：
             * ```
             * Finally, if you attach a MediaSession.Token using
             * android.app.Notification.MediaStyle.setMediaSession(MediaSession.Token),
             * the System UI can identify this as a notification representing
             * an active media session.
             * ```
             *
             * ## 前面几版为什么失败（都实测过）
             *
             * ```
             * ① 平台 MediaStyle() 不绑 token
             *    → startForeground 成功、通知能显示，但通知被静默丢弃
             * ② 换成普通通知（不带 MediaStyle）
             *    → 通知正常显示（1.19.8 实测状态栏有「龙卷风 / G.E.M. 邓紫棋」）
             *      但 OPPO 的播放控件仍然空白 —— 因为它只认「带 token 的媒体通知」
             * ③ Media3 的 MediaSession 没有公开 getPlatformToken()
             *    → 拿不到 android.media.session.MediaSession.Token
             * ```
             *
             * ## 正解：`MediaStyleNotificationHelper.MediaStyle(session)`
             *
             * 这是 Media3 官方给的工具（`androidx.media3:media3-ui`，
             * 本仓库已依赖）。它接收 **Media3 的 session**，
             * 内部负责把 token 绑到通知上 —— 我们不需要碰平台 token。
             *
             * 注意它作用在 `NotificationCompat.Builder` 上（core 库），
             * 不是平台 `Notification.Builder`，所以整段用 compat 写。
             */
            val builder = androidx.core.app.NotificationCompat.Builder(this, CARD_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(content)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(
                    androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(s)
                )
            startForeground(CARD_ID, builder.build())
            if (!cardBuilt) {
                cardBuilt = true
                android.util.Log.i("ETKN-Music", "媒体卡片已建立（带 session token）：$title / $artist")
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

        /**
         * ② 只有媒体键触发的启动才接管（普通启动不动）。
         *
         * ## 用户实测（2026-11-28，#202）
         *
         * ```
         * 「耳机后台拉起的下一首是打开暂停」
         * ```
         * 也就是说：耳机确实把 App 拉起来了（说明 `b1832b3` 删掉的那段
         * 「预装 MediaItem」恢复有效），但拉起后停在**暂停**状态，没开始播。
         *
         * ## 为什么会漏
         *
         * `pressed` 来自 `MediaButtonHint`，只有 `EtknMediaButtonReceiver`
         * 先收到广播才会被赋值。
         * 但实际存在两条拉起路径：
         * ```
         * 路径 A：耳机广播 → Receiver → 记录 keyCode → 转发 Media3 → 拉起服务
         *          → pressed 有值 ✅
         * 路径 B：系统直接通过会话恢复服务（不经过我们的 Receiver）
         *          → pressed == null → 上一版直接 return → 不播放 ❌
         * ```
         * 用户遇到的就是 B。
         *
         * ## 现在：没有 keyCode 也不再直接放弃
         *
         * 只在「明确是暂停/停止键」时才不播（下面第 ③ 段）。
         * 拿不到 keyCode 的情况，按「用户希望它响」处理 ——
         * 因为这种冷启动只可能由媒体按键或系统恢复触发，
         * 不会是用户随手点图标（那走 Activity 不走 Service）。
         *
         * 若误判（比如系统后台恢复服务），代价是开始播放音乐，
         * 用户按一下暂停即可；而漏判的代价是「按了没反应」，
         * 对上车场景更糟。
         */
        /**
         * 用户实测（2026-11-28，#204）**持续暂停和播放**。
         *
         * ## 日志铁证
         *
         * ```
         * 19:34:29.928  无 keyCode → 按要播放处理
         * 19:34:29.928  未在播 + key=null → 开始播放
         * 19:34:29.965  无 keyCode → 按要播放处理     ← 37ms 后又来
         * 19:34:30.527  播放触发完成 isPlaying=true
         * 19:34:30.888  无 keyCode → 按要播放处理     ← 又来了
         * 19:34:30.891  又来了
         * ```
         * `onStartCommand` 在几百毫秒内被反复调用，每次都执行 `play()`，
         * 于是播放/暂停不停来回切。
         *
         * ## 上一版错在哪
         *
         * 为了让「耳机冷启动也能播放」，我把 `pressed == null` 时的 `return` 去掉了，
         * 改成「按要播放处理」。但 `onStartCommand` 不只由媒体键触发：
         * ```
         * startForeground / 会话重建 / 系统恢复服务 / startService 幂等重入
         * ```
         * 这些都会调 `onStartCommand`，进去就 `play()` —— 变成自我驱动的循环。
         *
         * ## 现在恢复严格判断
         *
         * **没有 keyCode 一律不接管**，退回 Media3 默认行为。
         * 也就是说宁可「耳机冷启动那一下不播」，也不能让 App 自己抽风。
         *
         * 真正要支持「耳机冷启动直接播」，得靠 MediaSession 的
         * `onPlaybackResumption` 或者给媒体键单独记一个一次性标记，
         * 不能用 onStartCommand 无差别接管 —— 那条路必然自激。
         */
        if (pressed == null) {
            android.util.Log.i("ETKN-Music", "onStartCommand: 无 keyCode，放手给 Media3")
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
        /**
         * 用户实测（#204）**防抖**：同一次按键可能触发多次 `onStartCommand`，
         * 于是 `play()` 被调多次，表现为「持续暂停和播放」。
         *
         * 2 秒内只处理一次媒体键启动。
         */
        val now = System.currentTimeMillis()
        if (now - lastKeyStartAt < 2_000L) {
            android.util.Log.i(
                "ETKN-Music",
                "onStartCommand: 2 秒内重复触发(距上次 ${now - lastKeyStartAt}ms)，忽略",
            )
            return super.onStartCommand(intent, flags, startId)
        }
        lastKeyStartAt = now
        android.util.Log.i("ETKN-Music", "onStartCommand: 未在播 + key=$pressed → 开始播放")
        applicationContext.graph.scope.launch {
            runCatching {
                /** 冷启动要等两个前提，缺一个都会"起来了但没声音"。 */
                withTimeoutOrNull(15_000L) {
                    applicationContext.graph.sessions.state.first {
                        it is xyz.etkn.mobile.data.session.SessionState.SignedIn
                    }
                }
                val mem = withTimeoutOrNull(5_000L) {
                    applicationContext.graph.music.remembered.first { it != null }
                }
                if (mem == null) {
                    /**
                     * 用户实测（#202）**耳机拉起后停在暂停**。
                     *
                     * 原来这里不管记忆有没有拿到都硬调 `resumeRemembered()`，
                     * 拿不到时它内部会静默返回 —— 表现为「拉起来了但不播」。
                     *
                     * 现在明确区分：没有记忆就退化成「直接播当前装好的那首」，
                     * 因为 `onCreate` 里已经预装了 MediaItem（恢复蓝牙唤醒那段），
                     * 播放器里是有内容的，直接 `play()` 就能出声。
                     */
                    android.util.Log.w(
                        "ETKN-Music",
                        "onStartCommand: 没拿到记忆队列，改为直接播放已装载的曲目",
                    )
                    runCatching {
                        val p = applicationContext.graph.music.player
                        if (p.mediaItemCount > 0) {
                            /**
                             * `play()` 必须在播放器能播之后才有效果。
                             * 冷启动时直链还没解析，`prepare()` 后会先 BUFFERING，
                             * 此时 `play()` 只是设置「准备好就播」，不会立刻出声。
                             * 所以要等它到 READY（最多 8 秒）再确认一次。
                             */
                            p.prepare()
                            p.play()
                            var waited = 0L
                            while (waited < 8_000L && p.playbackState != Player.STATE_READY) {
                                kotlinx.coroutines.delay(200L)
                                waited += 200L
                            }
                            android.util.Log.i(
                                "ETKN-Music",
                                "兜底播放：等待 ${waited}ms 后 state=${p.playbackState} " +
                                    "isPlaying=${p.isPlaying} pos=${p.currentPosition}ms",
                            )
                            /** 到了 READY 但没在播，再点一次（有些机型第一次 play 被吞）。 */
                            if (!p.isPlaying) p.play()
                        }
                    }
                } else {
                    applicationContext.graph.music.resumeRemembered()
                }
                /** 延迟一拍再读状态，`play()` 之后 isPlaying 不是立刻变 true 的。 */
                kotlinx.coroutines.delay(600L)
                val nowPlaying = applicationContext.graph.music.player.isPlaying
                android.util.Log.i(
                    "ETKN-Music",
                    "onStartCommand: 播放触发完成 isPlaying=$nowPlaying " +
                        "state=${applicationContext.graph.music.player.playbackState}",
                )
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        /**
         * 用户口径（2026-11-28，#190）**划掉后台不能让服务自杀**。
         *
         * ## adb 抓到的决定性证据
         *
         * 两次启动的完整对比（`ConferenceScene` 记录的前台服务状态）：
         * ```
         * 第1次（显示）pid=18742: 13.190 fgState=2 → 19.111 fgState=0
         *                        活了 5.9 秒，是你杀它才停的
         * 第2次（不显示）pid=19089: 19.619 fgState=2 → 19.679 fgState=0
         *                        只活了 60 毫秒就自己停了
         * ```
         * 紧接着：
         * ```
         * 16:38:19.670  addSession  id./791
         * 16:38:19.702  removeSession id./791   ← 32ms 后就被销毁
         * 之后再也没有 addSession —— 所以「等多长时间都不显示」
         * ```
         *
         * ## 原因就是这里
         *
         * `onTaskRemoved` 是「用户从最近任务里划掉 App」的回调。
         * 原来写的是：
         * ```kotlin
         * if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
         *     graph.music.stop()
         *     stopSelf()          // ← 服务自杀
         * }
         * ```
         * 用户打开 App 时音乐是**暂停**状态（`playWhenReady = false`），
         * 一划掉后台就命中这个条件 → 服务自杀 →
         * **会话销毁 → 控制中心卡片消失，且再打开也不重建**（因为新的
         * task-removed 事件会继续把它杀掉）。
         *
         * ## 现在：只有「真的没有任何内容」才停
         *
         * 队列里有歌就保持服务活着 —— 这正是「打开 App 卡片就该一直在」的前提。
         * 用户明确要求过（原话）：
         * ```
         * 「只要这个 APP 不全部退出，它那个音乐控件上就一直应该显示现在正在播放的音乐，
         *   就算现在没有音乐，应该也是暂停的状态」
         * ```
         * 注意：`setStopWithTask` 已在 manifest 设为 false，
         * 所以划掉后台时**服务本来就不该被销毁**，这里更不该自己调 `stopSelf()`。
         */
        val player = session?.player
        val hasContent = player != null && player.mediaItemCount > 0

        /**
         * 用户口径（#220）**划掉任务 = 明确的关闭意图**。
         *
         * 这里只做一件事：写个标记告诉自己「用户已经划掉了」。
         * 真正的处理在 `onCreate` —— 系统若因此重建服务，那边看到标记就直接停，
         * 不再建会话、不再出卡片。
         *
         * 刻意**不在这里** stopSelf / release 会话 / 清队列：
         * 上一版就是在这一步动手，把「一次有一次没有」的毛病带回来了。
         * 写标记是纯记录，对当前正在跑的实例零影响。
         */
        getSharedPreferences("etkn_task", MODE_PRIVATE)
            .edit()
            .putBoolean("taskKilled", true)
            .putBoolean("appForeground", false)
            .apply()
        android.util.Log.i(
            "ETKN-Music",
            "onTaskRemoved: 已记录「用户划掉」（队列=${player?.mediaItemCount ?: 0}首），" +
                "不再主动停服务以免破坏会话",
        )
        if (!hasContent) {
            applicationContext.graph.music.stop()
            stopSelf()
        }
    }

    override fun onDestroy() {
        alive = false
        /**
         * 用户口径（2026-11-28，#201）**这段自动重建必须删掉**。
         *
         * 用户实测：
         * ```
         * 「修出问题了，那个播放卡片一直在跳，好像是频繁启停」
         * ```
         *
         * 原因：服务被销毁时立刻 `startService` 重建，而重建后
         * `onCreate` 里的 `prepare()` / 唤醒逻辑又会触发一次状态变化，
         * 招惹 ColorOS 再次停掉它 —— 形成
         * ```
         * onDestroy → 重建 → onCreate → prepare → 被停 → onDestroy → ...
         * ```
         * 于是卡片就一直在「出现→消失→出现」地跳。
         *
         * 教训：**「被系统杀就自重建」这种对抗系统的写法不能用**。
         * ColorOS 的进程/服务管理是它的地盘，硬重建只会被判定成异常应用，
         * 待机分桶继续往下降，越修越糟。
         *
         * 蓝牙唤醒的正确修法在**别处**：`onCreate` 里 Session 建好后
         * 预装 MediaItem（那一段保留），让系统媒体栈里始终有内容可认，
         * 而不是靠不停重启服务去占位。
         */
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
/**
 * 把 Media3 的 `playerCommands` 位掩码翻译成可读名字，只用于日志诊断。
 *
 * 之所以要单独写：Media3 的 `COMMAND_*` 是**位掩码不是枚举**，
 * `COMMAND_SEEK_TO_NEXT(9)` 里本身就含 `COMMAND_PLAY_PAUSE(1)` 那一位，
 * 直接拿名字对照会看串行。这里按位逐个展开，能看出真实构成。
 */
private fun commandName(cmd: Int): String {
    if (cmd == 0) return "NONE"
    val parts = ArrayList<String>()
    if (cmd and androidx.media3.common.Player.COMMAND_PLAY_PAUSE != 0) parts += "PLAY_PAUSE(1)"
    if (cmd and androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS != 0) parts += "SEEK_TO_PREVIOUS(7)"
    if (cmd and androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM != 0) parts += "SEEK_TO_NEXT_MEDIA_ITEM(8)"
    if (cmd and androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT != 0) parts += "SEEK_TO_NEXT(9)"
    if (parts.isEmpty()) parts += "OTHER($cmd)"
    return parts.joinToString("+")
}

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
