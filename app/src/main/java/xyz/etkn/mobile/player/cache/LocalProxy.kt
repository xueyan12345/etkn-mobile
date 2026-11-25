package xyz.etkn.mobile.player.cache

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 用户口径（2026-11-27）**本机缓存代理：给 mpv 用（Exo 走 CachedDataSource，两者共用块缓存）**。
 *
 * ── 为什么需要这个 ──
 * mpv 只能吃「文件路径」或「HTTP 地址」，它没有 DataSource 的概念。
 * 而我们的缓存是「按块存在私有目录」的，mpv 没法直接读。
 *
 * 所以在本机起一个极小的 HTTP 服务：
 * ```
 * mpv 请求 http://127.0.0.1:<port>/item/<itemId>
 *   ├ 本地块有 → 直接从块文件读出来返回（零网络，秒播）
 *   └ 本地块没有 → 向上游发 Range，边读边写块、边返回给 mpv
 * ```
 *
 * 安全边界：
 *  · 只绑 127.0.0.1（同一台设备，外部访问不到）
 *  · 只服务 /item/ 这一个路径，不做通用代理
 *  · 端口随机，进程退出即释放
 */
object LocalProxy {

    private const val TAG = "ETKN-Cache"

    @Volatile private var server: ServerSocket? = null
    @Volatile private var port: Int = 0
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var store: BlockStore? = null

    /** itemId → 上游真实地址。播放前由 VideoSession 登记。 */
    private val sources = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** itemId → 总字节数（-1 = 未知）。 */
    private val lengths = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val pool = Executors.newFixedThreadPool(4)

    /** 启动（幂等）。返回监听端口，失败返回 0。 */
    fun start(store: BlockStore, http: OkHttpClient): Int {
        if (port > 0) return port
        synchronized(this) {
            if (port > 0) return port
            this.store = store
            this.client = http
            val socket = runCatching {
                ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            }.onFailure { Log.w(TAG, "proxy start failed", it) }.getOrNull() ?: return 0
            server = socket
            port = socket.localPort
            thread(name = "etkn-proxy", isDaemon = true) {
                while (!socket.isClosed) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: break
                    pool.execute { runCatching { handle(conn) }.onFailure { Log.w(TAG, "proxy handle", it) } }
                }
            }
            Log.i(TAG, "proxy listening 127.0.0.1:$port")
            return port
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        port = 0
    }

    /** 给某一集登记上游地址（每次播放前调用）。 */
    fun register(itemId: String, upstreamUrl: String, totalBytes: Long) {
        sources[itemId] = upstreamUrl
        lengths[itemId] = totalBytes
    }

    /** 取这一集的本地代理地址；未启动或未登记返回 null。 */
    fun urlFor(itemId: String): String? {
        val p = port
        if (p <= 0) return null
        if (!sources.containsKey(itemId)) return null
        return "http://127.0.0.1:$p/item/$itemId"
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
                writeHead(output, 404, 0, null); return
            }

            val total = lengths[itemId] ?: -1L
            val bs = store ?: run { writeHead(output, 500, 0, null); return }

            // 解析 Range: bytes=start-end
            var start = 0L
            var end = if (total > 0) total - 1 else -1L
            rangeHeader?.let { raw ->
                val spec = raw.substringAfter("bytes=", "").trim()
                val parts = spec.split("-")
                parts.getOrNull(0)?.toLongOrNull()?.let { start = it }
                parts.getOrNull(1)?.toLongOrNull()?.let { end = it }
            }
            val hasRange = rangeHeader != null
            if (total > 0 && (end < 0 || end >= total)) end = total - 1
            val contentLength = if (end >= 0) (end - start + 1) else -1L

            // 头部
            val header = buildString {
                append("HTTP/1.1 ")
                append(if (hasRange) "206 Partial Content" else "200 OK")
                append("\r\n")
                append("Content-Type: video/mp4\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (contentLength > 0) {
                    append("Content-Length: ").append(contentLength).append("\r\n")
                }
                if (hasRange) {
                    append("Content-Range: bytes $start-$end/")
                        .append(if (total > 0) total else "*").append("\r\n")
                }
                append("Connection: close\r\n\r\n")
            }
            output.write(header.toByteArray())
            output.flush()

            streamBody(bs, itemId, upstreamUrl, start, contentLength, total, output)
            runCatching { output.flush() }
        }
    }

    /**
     * 把 [start] 起的数据写给客户端：本地有就读本地，没有就从上游拉并写块。
     * 这是「下到哪播到哪」在代理层的实现。
     */
    private fun streamBody(
        bs: BlockStore,
        itemId: String,
        upstreamUrl: String,
        start: Long,
        length: Long,
        total: Long,
        output: BufferedOutputStream,
    ) {
        var pos = start
        var left = length
        var upstream: InputStream? = null

        try {
            while (left != 0L) {
                // ① 本地连续可读
                val have = bs.contiguousBytesFrom(itemId, pos, total)
                if (have > 0) {
                    val want = if (left > 0) minOf(have, left) else have
                    val chunk = ByteArray(256 * 1024)
                    var remaining = want
                    while (remaining > 0) {
                        val n = minOf(remaining, chunk.size.toLong()).toInt()
                        val got = bs.read(itemId, pos, n, chunk, total)
                        if (got <= 0) break
                        output.write(chunk, 0, got)
                        pos += got
                        remaining -= got
                        if (left > 0) left -= got
                    }
                    continue
                }

                // ② 本地没有 → 上游
                val http = client ?: return
                val endByte = if (total > 0) total - 1 else pos + 4L * 1024 * 1024
                val req = Request.Builder()
                    .url(upstreamUrl)
                    .header("Range", "bytes=$pos-$endByte")
                    .build()
                val response = runCatching { http.newCall(req).execute() }.getOrNull() ?: return
                if (!response.isSuccessful) { response.close(); return }
                upstream = response.body?.byteStream() ?: run { response.close(); return }

                val buf = ByteArray(256 * 1024)
                val src = upstream!!
                while (left != 0L) {
                    val n = if (left > 0) minOf(left, buf.size.toLong()).toInt() else buf.size
                    val read = runCatching { src.read(buf, 0, n) }.getOrDefault(-1)
                    if (read <= 0) break
                    // 写块（只写完整块）
                    cacheFromBuffer(bs, itemId, pos, buf, read, total)
                    output.write(buf, 0, read)
                    output.flush()
                    pos += read
                    if (left > 0) left -= read
                }
                runCatching { src.close() }
                response.close()
                upstream = null
                if (left > 0) return   // 上游结束但没读完 → 停止
            }
        } finally {
            runCatching { upstream?.close() }
        }
    }

    /** 把缓冲里「刚好构成完整块」的部分落盘。 */
    private fun cacheFromBuffer(bs: BlockStore, itemId: String, position: Long, buf: ByteArray, length: Int, total: Long) {
        if (total <= 0) return
        var pos = position
        var off = 0
        var left = length
        while (left > 0) {
            val idx = bs.blockIndexFor(pos)
            val inBlock = (pos - bs.blockStart(idx)).toInt()
            val expected = bs.expectedSize(idx, total).toInt()
            if (expected <= 0) return
            // 只有整块都覆盖到才写（避免半块被当成完整）
            if (inBlock == 0 && left >= expected) {
                bs.writeBlock(itemId, idx, buf.copyOfRange(off, off + expected), expected)
                pos += expected
                off += expected
                left -= expected
            } else {
                return
            }
        }
    }

    private fun writeHead(out: BufferedOutputStream, code: Int, length: Long, extra: String?) {
        val text = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(if (code == 200) "OK" else "Not Found").append("\r\n")
            append("Content-Length: ").append(if (length > 0) length else 0).append("\r\n")
            extra?.let { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
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
