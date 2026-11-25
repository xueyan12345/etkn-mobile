package xyz.etkn.mobile.data.update

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject
import xyz.etkn.mobile.data.net.long
import xyz.etkn.mobile.data.net.objects
import xyz.etkn.mobile.data.net.str

/** Public release channel of this app. */
object ReleaseRepo {
    // 用户口径（2026-11-23）：改成自己 fork 的仓库，App 内「检查更新」直接从这里的 Release 拉包。
    const val SLUG = "xueyan12345/etkn-mobile"
    const val LATEST_API = "https://api.github.com/repos/$SLUG/releases/latest"
    const val LATEST_SUMS = "https://github.com/$SLUG/releases/latest/download/SHA256SUMS.txt"
    fun assetUrl(tag: String, name: String) = "https://github.com/$SLUG/releases/download/$tag/$name"
}

/**
 * Download source — 用户口径（2026-11-26）**只留一个加速源，其它全部删掉**。
 *
 * 需求原文：「你把那个代理删了吧，然后你看看原来那个项目，也就是咱们这个项目
 * 原来的作者用的加速源是啥……你能不能也套用他那个，然后去掉那些方式，
 * 就留一个加速源的更新，别的什么代理啥的，还有什么源原来的官方的那个都不要」
 *
 * 原作者（上游仓库 Alumos/etkn-mobile）的实现，原文：
 * ```
 * enum class UpdateSource(val label: String) {
 *     Proxy("加速源"),
 *     GitHub("GitHub 直连");
 *     fun wrap(githubUrl: String): String = when (this) {
 *         Proxy -> "https://gh-proxy.com/$githubUrl"
 *         GitHub -> githubUrl
 *     }
 * }
 * ```
 * 也就是说他用的是 **gh-proxy.com** 做前缀拼接（把完整 GitHub 地址贴在域名后面）。
 *
 * 现在完全照抄这一套，只保留加速源这一条通道：
 *  · 删掉自建公网代理（891730.xyz:23423）—— 那是「有时快有时慢」的来源之一，
 *    走代理等于让流量绕一圈，且代理节点本身波动大
 *  · 删掉 ghproxy.net / ghfast.top 这些轮换镜像
 *  · 删掉「GitHub 直连」兜底 —— 手机网络经常解析不了 github.com，留着只会多等一轮超时
 *
 * 只有一个源的好处：行为可预测。快就是快，慢就是慢，不会因为轮换到某个坏镜像
 * 而出现「有时候快有时候慢」。
 */
enum class UpdateSource(val label: String) {
    /** 加速源（gh-proxy.com，实测最快）。 */
    Proxy("加速源");

    /**
     * 用户口径（2026-11-27）**单一源失败就整体失败，必须留一个兜底**。
     *
     * 上一版只留 gh-proxy.com 一个源，结果用户那边「加速源下载失败」就彻底下不了。
     * 实测（我这边）：gh-proxy.com 273KB/s 可用，但它是第三方服务，会抽风。
     *
     * 现在：加速源为主，失败自动退 GitHub 直连。
     * 只加这一个兜底，不再恢复之前那 4 个镜像轮换 ——
     * 那会造成「有时候快有时候慢」（用户明确反对）。
     */
    fun wrap(githubUrl: String): String = "https://gh-proxy.com/$githubUrl"

    /**
     * 候选地址（按优先级）：
     *  ① gh-proxy.com 加速源（通常最快）
     *  ② GitHub 直连（加速源抽风时兜底，能解析 github.com 就可用）
     */
    fun candidates(githubUrl: String): List<String> = listOf(
        "https://gh-proxy.com/$githubUrl",
        githubUrl,
    )
}

@Immutable
data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

@Immutable
data class ReleaseInfo(val version: String, val tag: String, val notes: String, val assets: List<ReleaseAsset>) {
    /** The APK for this device: first supported ABI with a split, else the universal build. */
    fun assetFor(abis: List<String>): ReleaseAsset? {
        val apks = assets.filter { it.name.endsWith(".apk") }
        abis.forEach { abi -> apks.firstOrNull { it.name.endsWith("-$abi.apk") }?.let { return it } }
        return apks.firstOrNull { it.name.endsWith("-universal.apk") }
    }
}

object Releases {
    // ABI names are explicit so a pre-release suffix can never swallow "-arm64".
    private val ApkName = Regex("""^ETKN-Mobile-(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)-(arm64-v8a|armeabi-v7a|x86_64|x86|universal)\.apk$""")

    /** `GET /repos/{slug}/releases/latest`; `digest` is "sha256:<hex>" when GitHub has it. */
    fun fromApi(json: JsonObject): ReleaseInfo? {
        val tag = json.str("tag_name").ifEmpty { return null }
        val assets = json.objects("assets").mapNotNull { asset ->
            val name = asset.str("name").ifEmpty { return@mapNotNull null }
            val url = asset.str("browser_download_url").ifEmpty { ReleaseRepo.assetUrl(tag, name) }
            val digest = asset.str("digest").removePrefix("sha256:").lowercase().takeIf { it.matches(Hex64) }
            ReleaseAsset(name, url, asset.long("size", -1), digest)
        }
        return ReleaseInfo(tag.removePrefix("v"), tag, json.str("body").trim(), assets)
    }

    /** Fallback when the API is unreachable: the release's own SHA256SUMS.txt names every APK. */
    fun fromSums(text: String): ReleaseInfo? {
        val rows = text.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size != 2 || !parts[0].lowercase().matches(Hex64)) return@mapNotNull null
            val name = parts[1].removePrefix("*")
            val match = ApkName.matchEntire(name) ?: return@mapNotNull null
            Triple(match.groupValues[1], name, parts[0].lowercase())
        }.toList()
        val version = rows.firstOrNull()?.first ?: return null
        val tag = "v$version"
        val assets = rows.filter { it.first == version }.map { (_, name, sha) -> ReleaseAsset(name, ReleaseRepo.assetUrl(tag, name), -1, sha) }
        return ReleaseInfo(version, tag, "", assets)
    }

    /** Semantic comparison of x.y.z; a pre-release sorts before its release. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun compare(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val diff = coreA.getOrElse(i) { 0 }.compareTo(coreB.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return when {
            preA == preB -> 0
            preA == null -> 1
            preB == null -> -1
            else -> preA.compareTo(preB)
        }
    }

    private fun split(version: String): Pair<List<Int>, String?> {
        val clean = version.trim().removePrefix("v")
        val core = clean.substringBefore('-')
        val pre = clean.substringAfter('-', "").ifEmpty { null }
        return core.split('.').map { it.toIntOrNull() ?: 0 } to pre
    }

    private val Hex64 = Regex("^[0-9a-f]{64}$")
}
