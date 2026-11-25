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
    /** itemId → 缓存右边界字节（超过就不再写盘，但数据照常返回）。-1 = 不限制。 */
    private val limits = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val pool = Executors.newFixedThreadPool(4)

    /** 启动（幂等）。返回监听端口，失败返回 0。 */
    fun start(store: BlockStore, http: OkHttpClient): Int {
        if (port > 0) return port
        synchronized(this) {
            if (port > 0) return port
            this.store = store
            this.client = http
            val socket = runCatching {
                ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            }.onFailure {
                Log.e(TAG, "proxy start FAILED: ${it.javaClass.simpleName} ${it.message}")
            }.getOrNull() ?: return 0
            server = socket
            port = socket.localPort
            thread(name = "etkn-proxy", isDaemon = true) {
                while (!socket.isClosed) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: break
                    pool.execute {
                        /**
                         * 用户口径（2026-11-27）**客户端断开是正常现象，不该刷屏报错**。
                         *
                         * 日志实证：13 次异常全是 ConnectionReset / Broken pipe ——
                         * 那是播放器 seek 时主动断开旧连接（正常行为），
                         * 我们还在往这个 socket 写就会抛。
                         *
                         * 现在单独识别这两类异常，只记 debug 级一行，不打堆栈。
                         */
                        try {
                            handle(conn)
                        } catch (e: java.net.SocketException) {
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

    fun stop() {
        runCatching { server?.close() }
        server = null
        port = 0
    }

    /** 给某一集登记上游地址（每次播放前调用）。 */
    /**
     * 用户口径（2026-11-27）**登记上游地址 + 缓存上限**。
     *
     * [cacheLimitBytes] 是「从播放位置往后最多写多少字节」的右边界，
     * 超过就不再写盘（数据仍然照常返回给播放器，播放不中断）。
     * 传 -1 表示不限制。
     */
    fun register(itemId: String, upstreamUrl: String, totalBytes: Long, cacheLimitBytes: Long = -1L) {
        sources[itemId] = upstreamUrl
        lengths[itemId] = totalBytes
        limits[itemId] = cacheLimitBytes
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
            Log.i(TAG, "proxy req item=$itemId range=${rangeHeader ?: "none"} total=${if (total > 0) "${total / 1048576}MB" else "?"}")

            /**
             * 用户口径（2026-11-27）**Range 语义必须严格正确，否则播放器读到错位数据**。
             *
             * 修的 bug：
             *  · total 未知（-1）时 end 也是 -1 → Content-Length 和 Content-Range 全算错
             *    → mpv 解析头部后按错误长度读 → 数据错位 → 播放失败
             *  · 「end 省略」的 Range（`bytes=1000-`，最常见）原来被当成 end=-1 处理
             *
             * 现在：
             *  · total 已知 → end 默认 total-1，并夹紧
             *  · total 未知 → 不编造长度，用 chunked 方式（不写 Content-Length）
             *  · 显式支持 `bytes=start-` 这种开放区间
             */
            var start = 0L
            var end = if (total > 0) total - 1 else -1L
            rangeHeader?.let { raw ->
                val spec = raw.substringAfter("bytes=", "").trim()
                val parts = spec.split("-")
                parts.getOrNull(0)?.toLongOrNull()?.let { start = it }
                parts.getOrNull(1)?.toLongOrNull()?.let { end = it }
            }
            val hasRange = rangeHeader != null
            if (total > 0) {
                if (end < 0 || end >= total) end = total - 1
                if (start > end) start = end
            }
            val contentLength = if (end >= start) (end - start + 1) else -1L

            // 头部
            val header = buildString {
                append("HTTP/1.1 ")
                append(if (hasRange) "206 Partial Content" else "200 OK")
                append("\r\n")
                // 不写死 video/mp4：片源可能是 mkv/strm，让播放器自己嗅探
                append("Content-Type: application/octet-stream\r\n")
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

            streamBody(bs, itemId, upstreamUrl, start, contentLength, total, limits[itemId] ?: -1L, output)
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
        cacheLimit: Long,
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
                    Log.i(TAG, "proxy local-hit item=$itemId from=${pos / 1048576}MB have=${have / 1048576}MB")
                    val want = if (left > 0) minOf(have, left) else have
                    val chunk = ByteArray(256 * 1024)
                    var remaining = want
                    while (remaining > 0) {
                        val n = minOf(remaining, chunk.size.toLong()).toInt()
                        // chunk 是我们自己的数组，从 0 开始写，所以 bufferOffset=0
                        val got = bs.read(itemId, pos, n, chunk, 0, total)
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
                    // 超过缓存右边界就不写盘（数据照常返回，播放不中断）
                    if (cacheLimit <= 0 || pos < cacheLimit) {
                        cacheFromBuffer(bs, itemId, pos, buf, read, total)
                    }
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

    /**
     * 用户口径（2026-11-27）**缓存攒块：允许从块中间开始，攒满再落盘**。
     *
     * 修的 bug（日志实证：代理跑了很久、local-hit 一直是 0）：
     * 原来写的是「必须 inBlock == 0 且够一整块」才写盘。
     * 但播放器 seek 时请求的起点是任意字节（比如 1.2GB + 3MB），
     * 第一次 read 拿到的数据落在块中间 → inBlock != 0 → 直接 return 丢弃。
     * 结果：只要不是从文件第 0 字节顺序读，一块都存不下来。
     *
     * 现在：
     *  · 数据落在块中间的哪一段，就记到这一块的缓冲区里（按偏移放对位置）
     *  · 攒满一整块才落盘（保证「有文件 = 内容完整」）
     *  · 攒不满就留在内存，下次继续（不写半块）
     */
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

            // 换块 → 先把上一块该落的落盘
            if (idx != pendingBlockIndex) {
                flushPending(bs, itemId, total)
                pendingBlockIndex = idx
                pending = ByteArray(expected)
                pendingFilled = 0
                pendingBlocks = BooleanArray(expected)
            }

            // 这一块能装多少
            val room = expected - inBlock
            val n = minOf(left, room)
            if (n <= 0) return
            // 按真实偏移放进缓冲区（不要求从头连续，允许中间有洞）
            System.arraycopy(buf, off, pending, inBlock, n)
            for (k in inBlock until inBlock + n) pendingBlocks[k] = true
            pendingFilled += n
            // 整块都覆盖了 → 落盘
            if (pendingFilled >= expected && pendingBlocks.all { it }) {
                bs.writeBlock(itemId, idx, pending, expected)
                pendingBlockIndex = -1L
                pendingFilled = 0
            }
            pos += n
            off += n
            left -= n
        }
    }

    /** 攒块状态（内存）。切集/换块时重置。 */
    private var pendingBlockIndex = -1L
    private var pending = ByteArray(0)
    private var pendingFilled = 0
    private var pendingBlocks = BooleanArray(0)

    /** 把当前攒了一半的块落盘（只有整块齐了才写）。 */
    private fun flushPending(bs: BlockStore, itemId: String, total: Long) {
        val idx = pendingBlockIndex
        if (idx < 0) return
        val expected = bs.expectedSize(idx, total).toInt()
        if (expected > 0 && pendingFilled >= expected && pendingBlocks.all { it }) {
            bs.writeBlock(itemId, idx, pending, expected)
        }
        pendingBlockIndex = -1L
        pendingFilled = 0
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
