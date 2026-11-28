package xyz.etkn.mobile

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowHardware
import coil3.request.crossfade
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.flow.first
import xyz.etkn.mobile.data.diagnostics.Diagnostics

class EtknApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        /**
         * 用户口径（2026-11-27，#128）**歌词缓存需要拿到 App 目录**。
         *
         * `EtknApi.lyrics()` 里没有 context（它是纯网络层），
         * 所以在这里把 Application 交给 `LyricsCache`：
         * ```
         * filesDir/lyricscache/<itemId>.lrc
         * ```
         * 歌词是纯文本、几 KB 一条，放 filesDir 而不是 cacheDir
         * （ColorOS 会清 cacheDir，用户已经踩过这个坑）。
         */
        xyz.etkn.mobile.music.LyricsCache.attach(this)
        graph = AppGraph(this)
        graph.sessions.restore()
        /**
         * 用户口径（2026-11-27，#9）**App 一启动就把音乐服务拉起来**。
         *
         * 这一条同时修三个问题（都是 adb dumpsys 实测出来的）：
         *
         * ```
         * dumpsys media_session   → Sessions Stack - have 0 sessions
         * dumpsys activity services → (nothing)
         * ps -A | grep etkn        → 进程 11366 在，但 MusicService 不存在
         * dumpsys package          → manifest 登记的 action 完全正确
         * ```
         *
         * 结论：manifest 没错、ColorOS 没拦，是 **MusicService 从来没被创建过** ——
         * 它原来只在 `play()` 里 `startService`，所以「从没播过歌」时它压根不存在，
         * MediaLibrarySession 也就从未创建，系统状态栏卡片无 Session 可控 → 灰的、点不了。
         *
         * 现在启动即触达，Session 立刻存在：
         *  · 状态栏卡片能显示封面/歌名/总时长/可点的播放键
         *  · 车机 CarLife / OPPO Car+ 能通过 MediaBrowserService 发现 ETKN
         *  · 系统点播放时能直接走 Session，不用先冷启动一轮
         *
         * 用 runCatching 包住：任何异常都不该让 App 起不来。
         */
        runCatching {
            startService(
                android.content.Intent(this, xyz.etkn.mobile.music.MusicService::class.java),
            )
        }
        /**
         * 用户口径（2026-11-28，#180）**首页打开也要点亮控制中心卡片**。
         *
         * 用户实测反馈：
         * ```
         * 「打开 APP 显示的就是首页啊」→ 控制中心不显示
         * （用 adb 命令行启动时显示，因为上次停在音乐页）
         * ```
         *
         * ## 根因：`graph.music` 是 `by lazy`，没人访问就不初始化
         *
         * ```kotlin
         * val music by lazy { MusicConnection(app, this) }   // AppGraph.kt
         * ```
         * 上面 `startService` 只是把 Service 拉起来，
         * **`MusicConnection` 这个单例根本还没被创建** ——
         * 它的 `init` 里才有 `restoreForResumption()`（恢复队列 + 唤醒卡片）。
         *
         * 而 `MusicConnection` 只在「用户进音乐页 / 点播放」时才第一次被访问，
         * 所以：
         * ```
         * 用户停在首页 → music 从未创建 → restore 没跑 → 唤醒没跑 → 卡片空白
         * 命令行启动（上次停在音乐页）→ 音乐页一开始就访问 music → 全都跑了
         * ```
         * 两个现象完全对上。
         *
         * ## 修法
         *
         * 在启动 MusicService 之后主动摸一下 `graph.music`，
         * 强制它初始化 —— 这样**不管用户停在哪个页面**，
         * 队列恢复 + 静音轻播都会执行。
         *
         * 放在 `runCatching` 里：音乐模块初始化失败也不该让 App 起不来。
         */
        runCatching {
            // 访问一次即可触发 lazy 初始化（其 init 内部会把 restore 挂到协程里）
            graph.music
        }.onFailure { android.util.Log.w("ETKN-App", "提前初始化音乐模块失败", it) }
        /**
         * 用户口径（2026-11-26）**日志默认不记录，由开关控制**。
         *
         * 这里只做「如果上次开着，就继续开着」的恢复；默认值是关。
         * 真正的启动/停止在 Diagnostics.setEnabled() 里。
         * 注意：要在 graph 建好之后再恢复（要读设置里的开关状态）。
         */
        /**
         * 用同步方式读一次「上次是否开着」，读不到就按关闭处理。
         * 这里在主线程做一次极短的磁盘读取（DataStore 首次读有小开销），
         * 用 runBlocking 是为了保证「日志开关状态」在 UI 起来前就绪。
         */
        val wasOn = runCatching {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                    graph.settings.flow.first().diagnosticsEnabled
                } ?: false
            }
        }.getOrDefault(false)
        if (wasOn) runCatching { Diagnostics.setEnabled(this, true) }

        /** 用户口径 #74：开关开着才注册 WiFi 监听（默认关，不占资源）。 */
        runCatching {
            val wifiExit = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                    graph.settings.flow.first().wifiExitEnabled
                } ?: false
            }
            if (wifiExit) xyz.etkn.mobile.music.WifiExitGuard.start(this)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http.images })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.22).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("artwork").toOkioPath())
                    .maxSizeBytes(384L * 1024 * 1024)
                    .build()
            }
            .crossfade(180)
            .allowHardware(true)
            .build()
}

val Context.graph: AppGraph get() = (applicationContext as EtknApp).graph
