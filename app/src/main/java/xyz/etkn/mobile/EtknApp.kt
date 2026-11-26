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
