package xyz.etkn.mobile.player

import android.content.Context
import androidx.core.content.edit

/**
 * 用户口径（2026-11-26）**记住「每部片子该用哪个内核」**。
 *
 * ── 为什么需要这个 ──
 * 起播时先是不知道片源类型的（轨道信息要等解封装后才有），
 * 所以原来的流程必然是：
 * ```
 * engineFor → Exo（第一次起播，几秒）
 *   → onTracksChanged 识别到 HDR / 老编码
 *   → 自动切 mpv
 *   → playSource 重新 load（第二次起播，又几秒）
 * ```
 * **HDR 片源每次都要起播两次** —— 这就是用户反馈「走马上任起播要十几二十秒」的根因。
 *
 * ── 做法 ──
 * 第一次识别出某部片子该用哪个内核后，把这个结论记下来（itemId → 内核）。
 * 下次进同一集时，engineFor 先查表 → 直接用对的内核起播，一次到位。
 *
 * ── 为什么要按 itemId 而不是按「HDR 标志」 ──
 * 播放器还没有轨道信息时，唯一的已知量就是 itemId。
 * 所以只能「先记住这一集的结果」，下次直接用。
 */
object EngineMemory {

    private const val PREFS = "engine_memory"

    /** 记住这部片子用哪个内核。 */
    fun remember(context: Context, itemId: String, engine: Engine) {
        if (itemId.isBlank()) return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit { putString(key(itemId), engine.name) }
        }
    }

    /** 查这部片子该用哪个内核；没记录过返回 null。 */
    fun lookup(context: Context, itemId: String): Engine? {
        if (itemId.isBlank()) return null
        return runCatching {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(key(itemId), null) ?: return null
            Engine.entries.firstOrNull { it.name == name }
        }.getOrNull()
    }

    /** 清掉某条记录（比如两个内核都播不了时，避免下次还走错的）。 */
    fun forget(context: Context, itemId: String) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(key(itemId)) }
        }
    }

    private fun key(itemId: String) = itemId.replace(Regex("[^A-Za-z0-9_-]"), "_")
}
