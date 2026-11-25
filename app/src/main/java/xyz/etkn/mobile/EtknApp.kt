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
