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
 * Download mirrors, in default preference order.
 *
 * 用户口径（2026-11-23）：加速源必须真的能下，不能悄悄退回直连。
 * 实测（2026-11-23）：
 *   gh-proxy.com      → HTTP 403（失效，已弃用）
 *   ghfast.top        → HTTP 206 正常，可断点续传（目前最稳，放第一位）
 *   gh-proxy.net      → HTTP 200
 * 所以「加速源」= 按顺序尝试多个镜像，谁通用谁；全都不通才退回 GitHub 直连。
 */
enum class UpdateSource(val label: String) {
    Proxy("加速源"),
    GitHub("GitHub 直连");

    /** 加速源候选镜像（按成功率排序）。末尾那个才是 GitHub 原始地址。 */
    fun candidates(githubUrl: String): List<String> = when (this) {
        Proxy -> listOf(
            "https://ghfast.top/$githubUrl",
            "https://gh-proxy.net/$githubUrl",
            githubUrl,
        )
        GitHub -> listOf(githubUrl)
    }

    /** 首选地址（保持旧调用方可用）。 */
    fun wrap(githubUrl: String): String = candidates(githubUrl).first()
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
