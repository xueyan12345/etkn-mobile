package xyz.etkn.mobile.player.cache

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 用户口径（2026-11-27）**本地缓存块存储（替代原来的「另起一条流写整文件」）**。
 *
 * ── 为什么要重做 ──
 * 原来缓存和播放是两条互不相干的流：
 *   · 我们自己起 OkHttp 请求往磁盘写 item-xxx.part
 *   · 播放器（Exo）自己直连服务器，压根不读这份文件
 * 结果：缓冲条画的是「后台下载进度」，播放器能用的是「它自己几秒网络缓冲」
 *      → 拖进缓冲条范围也不秒播，缓冲条还忽长忽短。
 *
 * ── 现在的做法 ──
 * 缓存拆成固定大小的块（默认 1MB），按「块」管理：
 *   · 每块一个文件 `<dir>/<itemId>/<blockIndex>`
 *   · 记录哪些块已经有了（内存位图 + 磁盘目录即真相）
 *   · 播放器要哪段，本地有就从块里读，没有就从上游拉并写块
 *
 * 这样带来三个直接结果：
 *   ① 缓冲进度 = 已连续覆盖的块 → 与播放器读到哪天然一致，不再忽长忽短
 *   ② 下到哪就能播到哪（不需要整集下完），拖进已缓存区间立刻命中
 *   ③ 缓存只有一份，不再两条流重复下载
 */
class BlockStore(
    private val root: File,
    private val blockSize: Long = DEFAULT_BLOCK_SIZE,
) {

    companion object {
        private const val TAG = "ETKN-Cache"

        /** 1MB 一块：足够细（拖动定位精度约 0.5 秒），又不会文件数爆炸。 */
        const val DEFAULT_BLOCK_SIZE = 1L * 1024 * 1024

        @Volatile
        private var instance: BlockStore? = null

        fun get(context: Context): BlockStore =
            instance ?: synchronized(this) {
                instance ?: BlockStore(File(context.cacheDir, "blockcache")).also { instance = it }
            }
    }

    /** itemId → 已有哪些块（内存加速；磁盘目录才是真相）。 */
    private val known = ConcurrentHashMap<String, MutableSet<Long>>()

    fun dirFor(itemId: String): File = File(root, safe(itemId))

    fun fileFor(itemId: String, index: Long): File = File(dirFor(itemId), index.toString())

    /**
     * 这一块有没有、且是否完整。
     * 完整性用「长度 == 块大小」判断；最后一块允许更短。
     */
    fun hasBlock(itemId: String, index: Long, totalBytes: Long): Boolean {
        val f = fileFor(itemId, index)
        if (!f.isFile) return false
        val expected = expectedSize(index, totalBytes)
        return expected <= 0 || f.length() == expected
    }

    fun blockIndexFor(offset: Long): Long = offset / blockSize

    fun blockStart(index: Long): Long = index * blockSize

    /** 某块的期望长度（最后一块可能不满）。totalBytes <= 0 表示未知。 */
    fun expectedSize(index: Long, totalBytes: Long): Long {
        val start = blockStart(index)
        if (totalBytes <= 0) return blockSize
        return (totalBytes - start).coerceIn(0, blockSize)
    }

    /**
     * 已加载的块集合（惰性从磁盘扫描一次）。
     * 扫描是必要的：应用重启后内存集合是空的，但磁盘上的块还在。
     */
    fun blocksOf(itemId: String): MutableSet<Long> =
        known.getOrPut(itemId) {
            val dir = dirFor(itemId)
            val set = mutableSetOf<Long>()
            dir.listFiles()?.forEach { f ->
                f.name.toLongOrNull()?.let { set.add(it) }
            }
            set
        }

    /**
     * 从 [startBlock] 开始，连续覆盖到第几块（不含）。
     * 这是「缓冲条能画到哪」和「能不能秒播」的唯一判据。
     */
    fun contiguousEndBlock(itemId: String, startBlock: Long, totalBytes: Long): Long {
        val set = blocksOf(itemId)
        var i = startBlock
        while (true) {
            val f = fileFor(itemId, i)
            if (!f.isFile) break
            val expected = expectedSize(i, totalBytes)
            if (expected > 0 && f.length() != expected) break
            if (!set.contains(i)) set.add(i)
            i++
        }
        return i
    }

    /** 从 offset 开始，本地连续可读的字节数（用于告诉播放器「这段我有」）。 */
    fun contiguousBytesFrom(itemId: String, offset: Long, totalBytes: Long): Long {
        val startBlock = blockIndexFor(offset)
        val endBlock = contiguousEndBlock(itemId, startBlock, totalBytes)
        if (endBlock <= startBlock) return 0L
        val reachable = blockStart(endBlock)
        return (reachable - offset).coerceAtLeast(0L)
    }

    /**
     * 用户口径（2026-11-27）**缓冲条的唯一数据源：从播放位置往后真实连续落盘了多少字节**。
     *
     * 这是本次修复的核心 —— 条只能画「本地真的有数据」的部分。
     *
     * 为什么不能用播放器的 bufferedPosition：
     *  那是「已经请求到内存里的水位」，不是「已经写进磁盘」。
     *  两者差值就是虚假区间：条上看着有、拖过去却卡。
     */
    fun cachedBytesAhead(itemId: String, positionBytes: Long, totalBytes: Long): Long =
        contiguousBytesFrom(itemId, positionBytes, totalBytes)

    /**
     * 按偏移读一段（跨块）。返回实际读到的字节数；0 表示这段没有。
     *
     * ⚠️ 用户口径（2026-11-27）**必须支持 bufferOffset**。
     *
     * 修的严重 bug（日志实证 ErrorCode.IO_UNSPECIFIED + 「Top bit not zero」）：
     * 上一版这里没有 bufferOffset，数据一律从 buffer[0] 开始写。
     * 而 DataSource.read(buffer, offset, length) 契约要求从 buffer[offset] 开始写。
     * Exo 的 Extractor 常常传出 offset != 0（内部环形缓冲复用），
     * 于是数据被写到了错误位置 → 读出来的字节流错位 →
     * 解析 MKV/MP4 时报「Top bit not zero」→ 播放失败 → 自动换核到 mpv。
     *
     * 现在显式接收 bufferOffset，数据写到正确位置。
     */
    fun read(
        itemId: String,
        offset: Long,
        length: Int,
        buffer: ByteArray,
        bufferOffset: Int = 0,
        totalBytes: Long,
    ): Int {
        var done = 0
        var pos = offset
        while (done < length) {
            val idx = blockIndexFor(pos)
            val inBlock = (pos - blockStart(idx)).toInt()
            val f = fileFor(itemId, idx)
            if (!f.isFile) break
            val expected = expectedSize(idx, totalBytes)
            if (expected > 0 && f.length() != expected) break
            val want = minOf(length - done, (expected.toInt() - inBlock).coerceAtLeast(0))
            if (want <= 0) break
            val got = runCatching {
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(inBlock.toLong())
                    // 写到 buffer 的正确位置（bufferOffset + 已读量）
                    raf.read(buffer, bufferOffset + done, want)
                }
            }.getOrDefault(-1)
            if (got <= 0) break
            done += got
            pos += got
            if (got < want) break
        }
        return done
    }

    /** 写一块（整块落盘后再标记，避免半块被当成完整）。 */
    fun writeBlock(itemId: String, index: Long, data: ByteArray, length: Int): Boolean = runCatching {
        val dir = dirFor(itemId)
        if (!dir.isDirectory && !dir.mkdirs()) return@runCatching false
        val target = fileFor(itemId, index)
        val tmp = File(dir, "$index.tmp")
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(0)
            raf.write(data, 0, length)
        }
        // 原子替换：写完再改名，避免「有文件但内容不完整」
        if (target.exists()) target.delete()
        val ok = tmp.renameTo(target)
        if (ok) blocksOf(itemId).add(index)
        ok
    }.getOrElse {
        Log.w(TAG, "writeBlock failed item=$itemId block=$index", it)
        false
    }

    /** 整集是否已完整缓存（所有块齐全）。 */
    fun isComplete(itemId: String, totalBytes: Long): Boolean {
        if (totalBytes <= 0) return false
        val count = (totalBytes + blockSize - 1) / blockSize
        if (count <= 0) return false
        for (i in 0 until count) {
            if (!hasBlock(itemId, i, totalBytes)) return false
        }
        return true
    }

    /** 磁盘占用（按 itemId 汇总）。 */
    fun sizeOf(itemId: String): Long =
        dirFor(itemId).listFiles()?.sumOf { it.length() } ?: 0L

    /** 总占用。 */
    fun totalSize(): Long =
        root.listFiles()?.sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L } ?: 0L

    /**
     * 按 LRU 淘汰（录像机模式：达到上限就顶掉最久没碰的整集）。
     * [protect] 是不许删的 itemId（正在播的）。
     */
    fun trim(maxBytes: Long, protect: Set<String>) {
        if (maxBytes <= 0) return
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
        var total = dirs.sumOf { d -> d.listFiles()?.sumOf { it.length() } ?: 0L }
        if (total <= maxBytes) return
        // 按最后修改时间从旧到新（最久没碰的先删）
        dirs.sortedBy { it.lastModified() }.forEach { d ->
            if (total <= maxBytes) return
            if (d.name in protect) return@forEach
            val size = d.listFiles()?.sumOf { it.length() } ?: 0L
            if (d.deleteRecursively()) {
                total -= size
                known.remove(d.name)
            }
        }
    }

    fun clearItem(itemId: String) {
        known.remove(itemId)
        runCatching { dirFor(itemId).deleteRecursively() }
    }

    fun clearAll() {
        known.clear()
        runCatching { root.deleteRecursively() }
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
