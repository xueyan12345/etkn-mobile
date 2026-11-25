package xyz.etkn.mobile.data.update

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import xyz.etkn.mobile.BuildConfig
import xyz.etkn.mobile.data.net.EtknJson
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest

@Immutable
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val source: UpdateSource, val done: Long, val total: Long, val bytesPerSecond: Long) : DownloadState
    data object Verifying : DownloadState
    data class Done(val uri: Uri, val name: String) : DownloadState
    data class Failed(val message: String) : DownloadState
}

@Immutable
data class UpdateState(
    val checking: Boolean = false,
    /** A release newer than this build, or null. */
    val release: ReleaseInfo? = null,
    val upToDate: Boolean = false,
    val error: String? = null,
    val source: UpdateSource = UpdateSource.Proxy,
    val download: DownloadState = DownloadState.Idle,
    /** Ask the user (auto check found something). */
    val prompt: Boolean = false,
)

/**
 * Checks GitHub for a newer release and downloads the matching APK into the public Download
 * folder through the chosen mirror (gh-proxy first, GitHub direct as fallback), verifying the
 * SHA-256 GitHub publishes. Partial files are always removed on failure or cancel.
 */
class UpdateManager(
    private val context: Context,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null
    private var autoChecked = false
    @Volatile private var activeCall: Call? = null
    private val userAgent = "ETKN-Mobile/${BuildConfig.VERSION_NAME} (Android)"

    /**
     * 用户口径（2026-11-26）**自建公网代理已删除**。
     *
     * 原来这里配了 http://891730.xyz:23423（带 Basic 认证）走代理直连 GitHub。
     * 删掉的原因（用户原话「app内更新还是不是很顺畅，有时候快，有时候慢」）：
     *  · 走代理等于让下载流量绕一圈，速度取决于代理节点当时的状况，波动很大
     *  · 原作者用的是 gh-proxy.com 前缀式加速源，不碰代理，行为更稳定
     *
     * 现在只剩 [client] 一个通道，配合 UpdateSource.wrap() 的加速源地址。
     */

    /** Startup check, once per process. */
    fun autoCheck() {
        if (autoChecked) return
        autoChecked = true
        check(prompt = true)
    }

    fun check(prompt: Boolean = false) {
        if (_state.value.checking || _state.value.download is DownloadState.Running) return
        _state.update { it.copy(checking = true, error = null, upToDate = false) }
        scope.launch {
            val result = runCatching { latest() }
            _state.update { state ->
                result.fold(
                    onSuccess = { release ->
                        val newer = release?.takeIf { Releases.isNewer(it.version, BuildConfig.VERSION_NAME) }
                        state.copy(
                            checking = false,
                            release = newer,
                            upToDate = release != null && newer == null,
                            error = if (release == null) "没有找到可用的发行版本" else null,
                            prompt = prompt && newer != null,
                            download = if (newer?.tag != state.release?.tag) DownloadState.Idle else state.download,
                        )
                    },
                    onFailure = { state.copy(checking = false, error = if (prompt) null else "检查更新失败，请稍后重试") },
                )
            }
        }
    }

    fun dismissPrompt() = _state.update { it.copy(prompt = false) }

    fun setSource(source: UpdateSource) = _state.update { it.copy(source = source) }

    /** GitHub API first (has notes and digests); SHA256SUMS through each mirror as a fallback. */
    private suspend fun latest(): ReleaseInfo? {
        runCatching { fetchText(ReleaseRepo.LATEST_API, accept = "application/vnd.github+json") }
            .mapCatching { Releases.fromApi(EtknJson.parseToJsonElement(it) as JsonObject) }
            .getOrNull()?.let { return it }
        for (source in mirrorOrder(_state.value.source)) {
            runCatching { fetchText(source.wrap(ReleaseRepo.LATEST_SUMS)) }.getOrNull()
                ?.let(Releases::fromSums)?.let { return it }
        }
        throw IOException("unreachable")
    }

    private suspend fun fetchText(url: String, accept: String = "*/*"): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", accept).build()
        // 先走用户公网代理；代理不通（超时/拒绝）就退回直连。
        return runCatching { withCall(request, client) { it.requireOk().body.string() } }
            .getOrElse { withCall(request) { it.requireOk().body.string() } }
    }

    private fun Response.requireOk(): Response {
        if (!isSuccessful) throw IOException("HTTP ${code}")
        return this
    }

    /**
     * Blocking OkHttp call on the IO pool. Cancelling the coroutine is not enough to unblock a
     * stalled socket read, so [cancel] also cancels the call itself.
     */
    private suspend fun <T> withCall(
        request: Request,
        http: OkHttpClient = client,
        block: suspend (Response) -> T,
    ): T = withContext(Dispatchers.IO) {
        val call = http.newCall(request)
        activeCall = call
        try {
            call.execute().use { block(it) }
        } finally {
            if (activeCall === call) activeCall = null
        }
    }

    // ---------- Download ----------

    val needsStoragePermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun download() {
        val release = _state.value.release ?: return
        val asset = release.assetFor(Build.SUPPORTED_ABIS.toList()) ?: run {
            _state.update { it.copy(download = DownloadState.Failed("该版本没有适配本机的安装包")) }
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            var lastError = "下载失败"
            for (source in mirrorOrder(_state.value.source)) {
                // 用户口径（2026-11-23）：同一次选择内，把该源的所有镜像依次试一遍。
                // 加速源 = ghfast.top → gh-proxy.net → 直连；谁通用谁，
                // 不再出现「选着加速源，实际走直连」的情况。
                for (mirror in source.candidates(asset.url)) {
                    _state.update { it.copy(download = DownloadState.Running(source, 0, asset.size, 0)) }
                    val target = runCatching { withContext(Dispatchers.IO) { Target.create(context, asset.name) } }.getOrElse {
                        _state.update { s -> s.copy(download = DownloadState.Failed("无法写入下载目录")) }
                        return@launch
                    }
                    try {
                    val sha = transfer(mirror, target, asset, source)
                    _state.update { it.copy(download = DownloadState.Verifying) }
                    if (asset.sha256 != null && sha != asset.sha256) throw IOException("校验失败，文件可能被篡改或损坏")
                    val uri = withContext(Dispatchers.IO) { target.commit() }
                    _state.update { it.copy(download = DownloadState.Done(uri, asset.name)) }
                    return@launch
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { target.discard() }
                    _state.update { it.copy(download = DownloadState.Idle) }
                    throw cancel
                    } catch (error: Exception) {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { target.discard() }
                        // Cancel aborts the socket, which surfaces as an IOException: stop, don't fall back.
                        if (!isActive) {
                            _state.update { it.copy(download = DownloadState.Idle) }
                            return@launch
                        }
                        lastError = when {
                            error.message?.startsWith("校验失败") == true -> error.message!!
                            else -> "${source.label}下载失败（${error.message ?: error.javaClass.simpleName}）"
                        }
                        // 校验失败说明文件真的坏了，换镜像也没用，直接终止
                        if (error.message?.startsWith("校验失败") == true) break
                    }
                }
            }
            _state.update { it.copy(download = DownloadState.Failed(lastError)) }
        }
    }

    fun cancel() {
        job?.cancel()
        activeCall?.cancel()
    }

    /** Streams to [target] while hashing; publishes progress and speed about four times a second. */
    /**
     * 用户口径（2026-11-26）**下载只走一个加速源**。
     *
     * 之前有 4 条通道轮着试（自建代理 → ghproxy.net → ghfast.top → GitHub 直连），
     * 这正是「有时候快有时候慢」的根源：
     *  · 每条通道速度不同，落到哪条看当时哪条先通
     *  · 失败的通道要先超时才会切下一条，用户体感就是「卡一下才动」
     *  · 手机网络经常解析不了 github.com，直连那条注定白等
     *
     * 现在对齐原作者的做法：只用 gh-proxy.com 这一个加速源（见 UpdateSource.wrap）。
     * 行为可预测 —— 快就是快，慢就是慢，不再有「换个通道忽然变快」的错觉。
     *
     * 失败也不再轮换，直接报错让人重试（重试仍然走同一个加速源）。
     */
    private suspend fun transfer(url: String, target: Target, asset: ReleaseAsset, source: UpdateSource): String =
        transferOnce(source.wrap(url), target, asset, source, client)

    private suspend fun transferOnce(
        url: String, target: Target, asset: ReleaseAsset, source: UpdateSource, http: OkHttpClient,
    ): String {
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        return withCall(request, http) { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val total = response.body.contentLength().takeIf { it > 0 } ?: asset.size
            run {
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var done = 0L
                var windowStart = SystemClock.elapsedRealtime()
                var windowBytes = 0L
                var speed = 0L
                target.open().use { out ->
                    response.body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            windowBytes += read
                            val now = SystemClock.elapsedRealtime()
                            if (now - windowStart >= 250) {
                                val instant = windowBytes * 1000 / (now - windowStart)
                                speed = if (speed == 0L) instant else (speed * 3 + instant) / 4
                                windowStart = now
                                windowBytes = 0
                                _state.update { it.copy(download = DownloadState.Running(source, done, total, speed)) }
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw IOException("连接中断")
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
    }

    // ---------- Install ----------

    /** Opens the system installer, or the "install unknown apps" switch the first time. */
    fun install(activity: Context) {
        val done = _state.value.download as? DownloadState.Done ?: return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(done.uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun mirrorOrder(first: UpdateSource): List<UpdateSource> = listOf(first) + UpdateSource.entries.filter { it != first }

    /** Destination in the public Download folder: MediaStore on Android 10+, a file before. */
    private sealed interface Target {
        fun open(): OutputStream
        fun commit(): Uri
        fun discard()

        class Store(private val context: Context, private val uri: Uri) : Target {
            override fun open(): OutputStream = context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("无法写入")
            override fun commit(): Uri {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri
            }
            override fun discard() {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }

        class Legacy(private val context: Context, private val file: File) : Target {
            private val part = File(file.parentFile, file.name + ".part")
            override fun open(): OutputStream = part.outputStream()
            override fun commit(): Uri {
                if (file.exists()) file.delete()
                if (!part.renameTo(file)) throw IOException("无法保存安装包")
                return FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            }
            override fun discard() {
                part.delete()
            }
        }

        companion object {
            fun create(context: Context, name: String): Target {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    // Replace an earlier copy we saved ourselves instead of piling up "name (1).apk".
                    runCatching { resolver.delete(collection, "${MediaStore.MediaColumns.DISPLAY_NAME}=?", arrayOf(name)) }
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(collection, values) ?: throw IOException("无法创建下载文件")
                    return Store(context, uri)
                }
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists() && !dir.mkdirs()) throw IOException("下载目录不可用")
                return Legacy(context, File(dir, name))
            }
        }
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(java.util.Locale.ROOT, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[index])
}
