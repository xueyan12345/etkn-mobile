package xyz.etkn.mobile.player.cache

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 用户口径（2026-11-27）**给 mpv 用的极薄本机代理，底层是官方 SimpleCache**。
 *
 * ── 为什么 mpv 需要它 ──
 * Exo 可以直接用 CacheDataSource（数据源层就带缓存）；
 * mpv 只接受「文件路径」或「HTTP 地址」，没有 DataSource 概念。
 * 所以起一个只绑 127.0.0.1 的 HTTP 服务，把 SimpleCache 读出来喂给它。
 *
 * ── 和上一版的关键区别 ──
 * 上一版代理是自己写的块存储（BlockStore）：要求攒满 1MB 才落盘，
 * 而播放器请求跳跃、经常读到一半就断开 → 一块都存不下来（日志实证）。
 *
 * 这一版代理只是「搬运工」：
 *  · 缓存读写全部交给官方 SimpleCache（span 级，读到多少写多少）
 *  · 代理自己不存任何数据、不做任何攒块
 *  · 这样就消除了失败根因
 *
 * 安全：只绑回环地址，只服务 /item/<id>，不对外开放。
 */
@UnstableApi
object LocalProxy {

    private const val TAG = "ETKN-Cache"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var port: Int = 0

    /** itemId → 上游真实地址。 */
    private val sources = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val pool = Executors.newFixedThreadPool(4)

    /** 启动（幂等）。必须先把缓存和上游数据源准备好。 */
    fun start(upstream: DataSource.Factory): Int {
        if (port > 0) return port
        synchronized(this) {
            if (port > 0) return port
            val socket = runCatching {
                ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            }.onFailure {
                Log.e(TAG, "proxy start failed: ${it.javaClass.simpleName} ${it.message}")
            }.getOrNull() ?: return 0
            server = socket
            port = socket.localPort
            upstreamFactory = upstream
            thread(name = "etkn-proxy", isDaemon = true) {
                while (!socket.isClosed) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: break
                    pool.execute {
                        try {
                            handle(conn)
                        } catch (e: java.net.SocketException) {
                            // 播放器 seek 时会主动断开，正常现象
                            Log.d(TAG, "proxy client gone: ${e.message}")
                        } catch (e: java.io.IOException) {
                            Log.d(TAG, "proxy io: ${e.message}")
                        } catch (t: Throwable) {
                            Log.w(TAG, "proxy handle", t)
                        }
                    }
                }
            }
            Log.i(TAG, "proxy listening 127.0.0.1:$port")
            return port
        }
    }

    @Volatile private var upstreamFactory: DataSource.Factory? = null

    /** 登记某一集的上游地址（播放前调用）。 */
    fun register(itemId: String, upstreamUrl: String) {
        sources[itemId] = upstreamUrl
    }

    /** 这一集的本地代理地址；未启动或未登记返回 null。 */
    fun urlFor(itemId: String): String? {
        val p = port
        if (p <= 0) return null
        if (!sources.containsKey(itemId)) return null
        return "http://127.0.0.1:$p/item/$itemId"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        port = 0
    }

    // ---------------------------------------------------------------- 协议

    private fun handle(socket: Socket) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())

            val requestLine = readLine(input) ?: return
            var rangeHeader: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("range:")) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            val path = requestLine.split(" ").getOrNull(1) ?: return
            val itemId = path.removePrefix("/item/").substringBefore('?')
            val upstreamUrl = sources[itemId]
            if (upstreamUrl == null) {
                writeError(output, 404)
                return
            }
            val factory = upstreamFactory
            if (factory == null) {
                writeError(output, 500)
                return
            }

            /**
             * 用官方 CacheDataSource 处理这一个请求：
             * 它会自动「命中读缓存 / 未命中走上游并写入缓存」。
             * 代理这边只负责把字节转发给 mpv。
             */
            val ds = factory.createDataSource()
            val start = parseRangeStart(rangeHeader)
            val dataSpec = DataSpec.Builder()
                .setUri(android.net.Uri.parse(upstreamUrl))
                .setKey(itemId)
                .setPosition(start)
                .build()

            val length = runCatching { ds.open(dataSpec) }.getOrNull() ?: run {
                runCatching { ds.close() }
                writeError(output, 502)
                return
            }

            val total = if (length > 0 && start == 0L) length else -1L
            val header = buildString {
                append("HTTP/1.1 ")
                append(if (rangeHeader != null) "206 Partial Content" else "200 OK")
                append("\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (rangeHeader != null && total > 0) {
                    append("Content-Range: bytes $start-${start + length - 1}/$total\r\n")
                }
                if (length > 0) append("Content-Length: ").append(length).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray())
            output.flush()

            val buf = ByteArray(256 * 1024)
            try {
                while (true) {
                    val read = runCatching { ds.read(buf, 0, buf.size) }.getOrDefault(-1)
                    if (read <= 0) break
                    output.write(buf, 0, read)
                    output.flush()
                }
            } finally {
                runCatching { ds.close() }
            }
        }
    }

    private fun parseRangeStart(header: String?): Long {
        if (header == null) return 0L
        val spec = header.substringAfter("bytes=", "").trim()
        return spec.substringBefore('-').toLongOrNull() ?: 0L
    }

    private fun writeError(out: BufferedOutputStream, code: Int) {
        val text = "HTTP/1.1 $code ${if (code == 404) "Not Found" else "Error"}\r\n" +
            "Content-Length: 0\r\nConnection: close\r\n\r\n"
        runCatching { out.write(text.toByteArray()); out.flush() }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }
}
