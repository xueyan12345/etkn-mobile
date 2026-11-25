package xyz.etkn.mobile.player.cache

import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * 用户口径（2026-11-27）**缓存数据源：播放器要数据时先问本地，没有再拉上游**。
 *
 * 这是「重构」的核心：把缓存插到「播放器 ↔ 网络」之间。
 * ```
 * 播放器要 [position, position+length) 这段
 *   ├ 本地块齐全 → 直接从块文件读（零网络，必然秒播）
 *   └ 本地缺     → 向上游发 Range 请求，读到的同时攒块写盘，边读边给
 * ```
 *
 * 与前两版的区别（那两版为什么都失败了）：
 *  · 我们另起一条流写整文件 → 播放器不读它
 *  · 缓冲条自己算字节区间 → 与播放器真实进度无关，忽长忽短
 * 现在播放器和缓存共用同一份数据，缓冲条直接用播放器 bufferedPosition。
 */
class CachedDataSource(
    private val upstreamFactory: HttpDataSource.Factory,
    private val store: BlockStore,
    private val itemId: () -> String,
    private val totalBytes: () -> Long,
) : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val TAG = "ETKN-Cache"
    }

    private var upstream: HttpDataSource? = null
    private var uri: String? = null

    /** 本次读取的绝对位置。 */
    private var readPosition = 0L
    private var bytesRemaining = 0L
    private var opened = false

    /**
     * 攒块缓冲：上游读到但不完整的块先放这里，
     * 凑满一块再落盘。这样即使播放器从中间开始拖，
     * 也能把「后半块 + 下一块前半」拼成一个完整块存下来。
     */
    private var pendingBlockIndex = -1L
    private var pending = ByteArray(0)
    private var pendingFilled = 0

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri.toString()
        readPosition = dataSpec.position
        bytesRemaining = dataSpec.length
        val id = itemId()
        val total = totalBytes()

        /**
         * 先问本地：从 readPosition 起连续多少字节可用。
         * 有就不发网络请求 —— 这是「打开就秒播」的关键。
         */
        if (id.isNotEmpty()) {
            val have = store.contiguousBytesFrom(id, readPosition, total)
            if (have > 0) {
                opened = true
                val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) have else minOf(have, bytesRemaining)
                Log.i(
                    TAG,
                    "local-hit item=$id from=${readPosition / 1048576}MB have=${want / 1048576}MB " +
                        "total=${if (total > 0) "${total / 1048576}MB" else "?"}",
                )
                transferStarted(dataSpec)
                return want
            }
        }

        // 本地没有 → 开上游 Range 请求
        val ds = upstream()
        val openedLength = ds.open(dataSpec)
        opened = true
        Log.i(
            TAG,
            "network item=$id from=${readPosition / 1048576}MB len=" +
                "${if (openedLength == C.LENGTH_UNSET.toLong()) "?" else "${openedLength / 1048576}MB"} " +
                "total=${if (total > 0) "${total / 1048576}MB" else "?"}",
        )
        if (bytesRemaining == C.LENGTH_UNSET.toLong()) bytesRemaining = openedLength
        transferStarted(dataSpec)
        return openedLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(length.toLong(), bytesRemaining).toInt()
        }
        if (want <= 0) return C.RESULT_END_OF_INPUT

        val id = itemId()
        val total = totalBytes()

        // ① 本地优先（每次都重新问：上游边读边写块，覆盖面会变大）
        if (id.isNotEmpty()) {
            val have = store.contiguousBytesFrom(id, readPosition, total)
            if (have > 0) {
                val n = minOf(want.toLong(), have).toInt()
                val got = store.read(id, readPosition, n, buffer, total)
                if (got > 0) {
                    readPosition += got
                    if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= got
                    bytesTransferred(got)
                    return got
                }
            }
        }

        // ② 本地不够 → 从上游读，并顺手攒块
        val src = upstream ?: openUpstreamAt(readPosition, want).also { upstream = it }
        val read = src.read(buffer, offset, want)
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (read > 0) {
            feed(id, readPosition, buffer, offset, read, total)
            readPosition += read
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= read
            bytesTransferred(read)
        }
        return read
    }

    /**
     * 把读到的数据喂进「攒块缓冲」，凑满一块就落盘。
     *
     * 为什么要攒而不是直写：上游给的数据是流式的，
     * 一次 read 可能只有几 KB，也可能跨块。必须对齐块边界才能标记完整。
     */
    private fun feed(id: String, position: Long, buffer: ByteArray, offset: Int, length: Int, total: Long) {
        if (id.isEmpty() || total <= 0) return
        runCatching {
            var pos = position
            var off = offset
            var left = length
            while (left > 0) {
                val idx = store.blockIndexFor(pos)
                val inBlock = (pos - store.blockStart(idx)).toInt()
                val expected = store.expectedSize(idx, total).toInt()
                if (expected <= 0) return

                // 换了块 → 上一块如果攒满就落盘，然后重置
                if (idx != pendingBlockIndex) {
                    flushPending(id, total)
                    pendingBlockIndex = idx
                    pending = ByteArray(expected)
                    pendingFilled = 0
                }

                val room = expected - inBlock
                val n = minOf(left, room)
                if (n <= 0) break
                // 只接受「从头开始连续」的数据（跳过中间洞，避免拼错）
                if (inBlock == pendingFilled) {
                    System.arraycopy(buffer, off, pending, inBlock, n)
                    pendingFilled = inBlock + n
                    if (pendingFilled >= expected) {
                        store.writeBlock(id, idx, pending, expected)
                        pendingBlockIndex = -1L
                        pendingFilled = 0
                    }
                }
                pos += n
                off += n
                left -= n
                // 块已经齐了但还有剩余 → 下一轮循环会换块
                if (pendingBlockIndex == -1L && left > 0) continue
            }
        }.onFailure { Log.w(TAG, "feed failed item=$id pos=$position", it) }
    }

    private fun flushPending(id: String, total: Long) {
        val idx = pendingBlockIndex
        if (idx < 0) return
        val expected = store.expectedSize(idx, total).toInt()
        if (expected > 0 && pendingFilled >= expected) {
            store.writeBlock(id, idx, pending, expected)
        }
        pendingBlockIndex = -1L
        pendingFilled = 0
    }

    /** 上游流断掉/被跳过之后要续读 → 按当前位置新开 Range。 */
    private fun openUpstreamAt(position: Long, length: Int): HttpDataSource {
        val ds = this.upstream ?: upstreamFactory.createDataSource().also { this.upstream = it }
        val spec = DataSpec.Builder()
            .setUri(uri ?: "")
            .setPosition(position)
            .setLength(length.toLong())
            .build()
        ds.open(spec)
        return ds
    }

    override fun getUri(): android.net.Uri? = uri?.let { android.net.Uri.parse(it) }

    override fun close() {
        flushPending(itemId(), totalBytes())
        runCatching { upstream?.close() }
        upstream = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun upstream(): HttpDataSource =
        upstream ?: upstreamFactory.createDataSource().also { upstream = it }

    override fun getResponseHeaders(): Map<String, List<String>> =
        upstream?.responseHeaders ?: emptyMap()

    class Factory(
        private val upstream: HttpDataSource.Factory,
        private val store: BlockStore,
        private val itemId: () -> String,
        private val totalBytes: () -> Long,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            CachedDataSource(upstream, store, itemId, totalBytes)
    }
}

/** OkHttpClient → HttpDataSource.Factory（与现有 PlayerFactory 风格一致）。 */
fun httpFactory(client: OkHttpClient, userAgent: String): HttpDataSource.Factory =
    androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client)
        .setUserAgent(userAgent)
