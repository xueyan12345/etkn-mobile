package xyz.etkn.mobile.data.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.etkn.mobile.data.update.UpdateSource

private val Context.settingsDataStore by preferencesDataStore(name = "etkn_settings")

enum class ThemeMode(val label: String) { System("跟随系统"), Server("跟随服务器"), Light("浅色"), Dark("深色") }

enum class ColorSource(val label: String) { Server("服务器主题色"), Wallpaper("系统壁纸取色") }

enum class DecoderPreference(val label: String) { Auto("自动"), Hardware("仅硬件"), Software("软件 (FFmpeg)") }

/** Video core. Auto: discs and HDR / Dolby Vision on ExoPlayer (true HDR output), the rest on mpv. */
enum class PlayerEngine(val label: String) { Auto("自动"), Exo("ExoPlayer"), Mpv("mpv") }

enum class SkipMode(val label: String) { Button("显示按钮"), Auto("自动跳过"), Off("关闭") }

/**
 * Whole-app size: scales density (layout and text together) like the system display size.
 *
 * 用户口径（2026-11-22）：原来四个写死档位（小/标准/大/特大）换成 0.8x~1.3x 的连续倍率，
 * 外观页用滑块调、实时显示「0.9x」。手机平板上各自调到合适为止。
 * 存储仍沿用这份 SharedPreferences（attachBaseContext 时同步读，早于任何资源访问）。
 */
object UiScale {
    /** 可调区间。低于 0.8 布局会挤，高于 1.3 字会溢出卡片。 */
    const val MIN = 0.8f
    const val MAX = 1.3f
    /** 滑块步长：0.05 一档，够细也不至于滑不到。 */
    const val STEP = 0.05f
    const val DEFAULT = 1f

    private const val FILE = "etkn_display"
    private const val KEY = "ui_scale_factor"
    /** 旧的四档枚举存在同一个 key 下（Small/Default/Large/Larger），读时做一次迁移。 */
    private const val LEGACY_KEY = "ui_scale"

    private fun clamp(value: Float) = value.coerceIn(MIN, MAX)

    fun readFactor(context: Context): Float {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val raw = prefs.getFloat(KEY, Float.NaN)
        if (!raw.isNaN()) return clamp(raw)
        // 迁移：老版本的枚举名 → 对应倍率
        val legacy = prefs.getString(LEGACY_KEY, null)
        val migrated = when (legacy) {
            "Small" -> 0.9f
            "Large" -> 1.1f
            "Larger" -> 1.2f
            else -> DEFAULT
        }
        prefs.edit(commit = true) { putFloat(KEY, migrated) }
        return migrated
    }

    fun writeFactor(context: Context, factor: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit(commit = true) { putFloat(KEY, clamp(factor)) }
    }

    /** 「0.9x」这种展示文案。 */
    fun label(factor: Float): String = String.format(java.util.Locale.ROOT, "%.2fx", clamp(factor)).replace(".00x", "x").replace(Regex("\\.(\\d)0x"), ".$1x")

    /**
     * Overrides only `densityDpi`: every window of the activity (dialogs, sheets, menus)
     * then measures with the scaled density, exactly like the system display-size setting.
     */
    fun applyTo(activity: android.view.ContextThemeWrapper, base: Context) {
        val factor = readFactor(base)
        if (factor == 1f) return
        val dpi = base.resources.configuration.densityDpi
        activity.applyOverrideConfiguration(android.content.res.Configuration().apply { densityDpi = (dpi * factor).toInt() })
    }
}

/** Where the tab bar sits on wide windows (tablets, unfolded, landscape); phones always use the dock. */
enum class NavPosition(val label: String) { Side("侧边"), Bottom("底部") }

/** Home recommendation banner. */
enum class HeroMode(val label: String) { Auto("自动轮播"), Manual("手动切换"), Off("隐藏") }

/** Local danmaku look, same ranges as ETKN TV (font size defaults smaller for phones). */
@Immutable
data class DanmakuStyle(
    val enabled: Boolean = false,
    val rows: Int = 6,
    val density: Int = 100,
    val fontSize: Int = 18,
    val opacity: Int = 85,
    val speed: Float = 1f,
    val themeColor: Boolean = false,
) {
    companion object {
        val Rows = 1..16
        val Density = 10..100
        val FontSize = 12..40
        val Opacity = 10..100
        val Speed = 0.5f..2f
    }
}

@Immutable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.System,
    val colorSource: ColorSource = ColorSource.Server,
    val amoled: Boolean = false,
    val decoder: DecoderPreference = DecoderPreference.Auto,
    val engine: PlayerEngine = PlayerEngine.Auto,
    val skipMode: SkipMode = SkipMode.Button,
    val autoPlayNext: Boolean = true,
    val defaultSpeed: Float = 1f,
    val serverThemeColor: String? = null,
    val serverDarkMode: Boolean? = null,
    val danmaku: DanmakuStyle = DanmakuStyle(),
    val autoUpdate: Boolean = true,
    val updateSource: UpdateSource = UpdateSource.Proxy,
    /**
     * 用户口径（2026-11-26）**播放日志开关**：默认关闭，只有排障时才打开。
     * 打开后才会有日志文件产生，避免日志无限增长。
     */
    val diagnosticsEnabled: Boolean = false,
    // 用户口径（2026-11-23）：缓存与预加载六项（照参考图的缓存设置页）
    /** 预加载下一集（把下一集提前写进磁盘缓存）。 */
    val preloadNext: Boolean = true,
    /** 内存侧前向缓冲上限（MB）。 */
    val cacheMemoryMb: Int = 400,
    /** 内存侧前向缓冲上限（秒）。 */
    val cacheMemorySeconds: Int = 1200,
    /** 磁盘缓存上限（GB）。 */
    val cacheDiskGb: Int = 10,
    /**
     * 用户口径（2026-11-27，#39）**音乐缓存单独设一个大小**（MB）。
     *
     * 用户原话：
     *  「音乐单独缓存，那个你要在我的，播放那个设置里，加一个选项，
     *    就是专门给音乐设置一个大小区间，可以选择的，类似于视频那个」
     *
     * 和视频的 [cacheDiskGb] 完全分开：音乐不参与视频配额，视频淘汰也不影响音乐。
     * 默认 2048MB（2GB）—— 一首无损 30-50MB，够放 40-60 首，覆盖「最近在听」足够。
     */
    val musicCacheMb: Int = 2048,
    /**
     * 用户口径 #74：连着指定 WiFi 且正在放歌时，一断就彻底退出。
     * 默认关闭；WiFi 名称用系统列表选（照 MacroDroid）。
     */
    val wifiExitEnabled: Boolean = false,
    val wifiExitSsid: String = "",
    /** 本次 WiFi 连接期间是否真的放过歌 —— 没放过就不断 WiFi 杀 App。 */
    val wifiExitArmed: Boolean = false,
    /**
     * 用户口径（2026-11-28，#175）**自动记住连过的 WiFi**。
     *
     * 用户原话：
     * ```
     * 「刚才连 CarLife 的时候，我去点那个 WiFi 也搜不到，
     *   你这样，把那个输入和这个自动填这个都加上」
     * ```
     *
     * ## 为什么需要它
     *
     * Android 10+ 把 `getConfiguredNetworks()` 收紧成「只返回自己创建的」，
     * App 拿不到系统保存的全部 WiFi（adb `cmd wifi list-networks` 能看到 CarLife，
     * 但 App 里读不到）。所以列表天然不全，用户没得选。
     *
     * ## 思路：不靠读列表，靠「连上时自己记」
     *
     * 只要 App 在运行期间检测到连上某个 WiFi，就把它记进这个列表。
     * 用户下次要用 CarLife 时，它已经在候选里了，直接点选。
     *
     * 存最近 [MAX_RECENT_WIFI] 个，新的在前，去重。
     */
    val recentWifis: List<String> = emptyList(),
    /** 播放结束后自动清空该集磁盘缓存。 */
    val cacheClearOnFinish: Boolean = false,
    val heroMode: HeroMode = HeroMode.Auto,
    /** Names of the chosen top-level tabs; null = automatic default. */
    val tabs: Set<String>? = null,
    val navPosition: NavPosition = NavPosition.Side,
    /** System notifications for new / replaced titles and cloud acquisitions. */
    val ingestNotices: Boolean = true,
    /** Keep the sound of a video going when the app goes to the background. */
    val backgroundPlay: Boolean = false,
)

/**
 * 用户口径（2026-11-23）：轮播图（首页推荐）按账号独立设置。
 * 之前 heroMode 存在全局 Settings 里，一个账号改了所有账号都跟着变。
 * 现在每个账号一份，key 用 session.key（serverId|userId）。没设置过的账号用 fallback。
 */
object HeroModePerAccount {
    private const val FILE = "etkn_hero_mode"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(context: Context, accountKey: String?, fallback: HeroMode): HeroMode {
        if (accountKey.isNullOrBlank()) return fallback
        val name = prefs(context).getString(accountKey, null) ?: return fallback
        return runCatching { HeroMode.valueOf(name) }.getOrDefault(fallback)
    }

    fun write(context: Context, accountKey: String?, mode: HeroMode) {
        if (accountKey.isNullOrBlank()) return
        prefs(context).edit(commit = true) { putString(accountKey, mode.name) }
    }
}

/**
 * 用户口径（2026-11-23）**「隐藏媒体库」也按账号独立**（和轮播图一样）。
 *
 * 作用：外观页打开这个开关后，首页最上面那一行「媒体库」模块整块不显示。
 * 继续播放、最新媒体库、以及其余所有区块照常显示。
 *
 * 实现方式和 [HeroModePerAccount] 一致：每个账号一份，
 * key 用 session.key（serverId|userId），绝不写全局默认值
 * （那会把别的账号也一起改掉 —— c12 踩过的坑）。
 */
object HiddenLibrariesPerAccount {
    private const val FILE = "etkn_hidden_libraries"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(context: Context, accountKey: String?): Boolean {
        if (accountKey.isNullOrBlank()) return false
        return prefs(context).getBoolean(accountKey, false)
    }

    fun write(context: Context, accountKey: String?, hidden: Boolean) {
        if (accountKey.isNullOrBlank()) return
        prefs(context).edit(commit = true) { putBoolean(accountKey, hidden) }
    }
}

/**
 * 用户口径（2026-11-23）**轮播图按「上次生成满 12 小时」才换一批**。
 *
 * 之前用「日历 0 点 / 12 点桶号」当种子，问题有两个：
 *  ① 用户上午看到一批好片，下午再打开就被换掉了，找不回来；
 *  ② 两天没打开，一进来桶早变了好几轮 —— 这倒是对的，但和 ① 是同一个毛病：
 *     刷新时机跟着日历走，不跟用户行为走。
 *
 * 现在按账号存「上次生成时间 + 那 8 个 id」：
 *  · 距上次不足 12 小时 → 直接沿用（一天开十次都是同一批）
 *  · 超过 12 小时 → 重抽（两天没开，一进来就是新的）
 *  · 剔除已看完/已删除的，不够就当场补
 */
object HeroSnapshot {
    private const val FILE = "etkn_hero_snapshot"
    private const val KEY_IDS = "ids"
    private const val KEY_AT = "at"

    data class Shown(val ids: List<String>, val at: Long)

    private fun prefs(context: Context, accountKey: String) =
        context.getSharedPreferences("${FILE}_$accountKey", Context.MODE_PRIVATE)

    fun read(context: Context, accountKey: String?): Shown? {
        if (accountKey.isNullOrBlank()) return null
        val p = prefs(context, accountKey)
        val ids = p.getString(KEY_IDS, null) ?: return null
        val at = p.getLong(KEY_AT, 0L)
        if (at <= 0L) return null
        val list = ids.split('\u0001').filter { it.isNotBlank() }
        if (list.isEmpty()) return null
        return Shown(list, at)
    }

    fun write(context: Context, accountKey: String?, ids: List<String>) {
        if (accountKey.isNullOrBlank()) return
        prefs(context, accountKey).edit(commit = true) {
            putString(KEY_IDS, ids.joinToString("\u0001"))
            putLong(KEY_AT, System.currentTimeMillis())
        }
    }
}

class AppSettings(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val color = stringPreferencesKey("color_source")
        val amoled = booleanPreferencesKey("amoled")
        val decoder = stringPreferencesKey("decoder")
        val engine = stringPreferencesKey("engine")
        val skip = stringPreferencesKey("skip_mode")
        val autoNext = booleanPreferencesKey("auto_next")
        val speed = floatPreferencesKey("default_speed")
        val serverColor = stringPreferencesKey("server_theme_color")
        val serverDark = stringPreferencesKey("server_dark")
        val dmEnabled = booleanPreferencesKey("danmaku_enabled")
        val dmRows = intPreferencesKey("danmaku_rows")
        val dmDensity = intPreferencesKey("danmaku_density")
        val dmFont = intPreferencesKey("danmaku_font_size")
        val dmOpacity = intPreferencesKey("danmaku_opacity")
        val dmSpeed = floatPreferencesKey("danmaku_speed")
        val dmTheme = booleanPreferencesKey("danmaku_theme_color")
        val autoUpdate = booleanPreferencesKey("auto_update")
        val updateSource = stringPreferencesKey("update_source")
        val diagnosticsEnabled = booleanPreferencesKey("diagnostics_enabled")
        val heroMode = stringPreferencesKey("hero_mode")
        val preloadNext = booleanPreferencesKey("preload_next")
        val cacheMemoryMb = intPreferencesKey("cache_memory_mb")
        val cacheMemorySeconds = intPreferencesKey("cache_memory_seconds")
        val cacheDiskGb = intPreferencesKey("cache_disk_gb")
        val musicCacheMb = intPreferencesKey("music_cache_mb")
        val wifiExitEnabled = booleanPreferencesKey("wifi_exit_enabled")
        val wifiExitSsid = stringPreferencesKey("wifi_exit_ssid")
        val wifiExitArmed = booleanPreferencesKey("wifi_exit_armed")
        val recentWifis = stringSetPreferencesKey("recent_wifis")
        val cacheClearOnFinish = booleanPreferencesKey("cache_clear_on_finish")
        val tabs = stringSetPreferencesKey("nav_tabs")
        val navPosition = stringPreferencesKey("nav_position")
        val ingestNotices = booleanPreferencesKey("ingest_notices")
        val backgroundPlay = booleanPreferencesKey("background_play")
    }

    val flow: Flow<Settings> = context.settingsDataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = Settings(
        themeMode = enumOr(this[Keys.theme], ThemeMode.System),
        colorSource = enumOr(this[Keys.color], ColorSource.Server),
        amoled = this[Keys.amoled] ?: false,
        decoder = enumOr(this[Keys.decoder], DecoderPreference.Auto),
        engine = enumOr(this[Keys.engine], PlayerEngine.Auto),
        skipMode = enumOr(this[Keys.skip], SkipMode.Button),
        autoPlayNext = this[Keys.autoNext] ?: true,
        defaultSpeed = this[Keys.speed] ?: 1f,
        serverThemeColor = this[Keys.serverColor],
        serverDarkMode = this[Keys.serverDark]?.toBooleanStrictOrNull(),
        danmaku = DanmakuStyle().let { d ->
            DanmakuStyle(
                enabled = this[Keys.dmEnabled] ?: d.enabled,
                rows = (this[Keys.dmRows] ?: d.rows).coerceIn(DanmakuStyle.Rows),
                density = (this[Keys.dmDensity] ?: d.density).coerceIn(DanmakuStyle.Density),
                fontSize = (this[Keys.dmFont] ?: d.fontSize).coerceIn(DanmakuStyle.FontSize),
                opacity = (this[Keys.dmOpacity] ?: d.opacity).coerceIn(DanmakuStyle.Opacity),
                speed = (this[Keys.dmSpeed] ?: d.speed).coerceIn(DanmakuStyle.Speed),
                themeColor = this[Keys.dmTheme] ?: d.themeColor,
            )
        },
        autoUpdate = this[Keys.autoUpdate] ?: true,
        updateSource = enumOr(this[Keys.updateSource], UpdateSource.Proxy),
        diagnosticsEnabled = this[Keys.diagnosticsEnabled] ?: false,
        heroMode = enumOr(this[Keys.heroMode], HeroMode.Auto),
        preloadNext = this[Keys.preloadNext] ?: true,
        cacheMemoryMb = this[Keys.cacheMemoryMb] ?: 400,
        cacheMemorySeconds = this[Keys.cacheMemorySeconds] ?: 1200,
        cacheDiskGb = this[Keys.cacheDiskGb] ?: 10,
            musicCacheMb = this[Keys.musicCacheMb] ?: 2048,
        wifiExitEnabled = this[Keys.wifiExitEnabled] ?: false,
        wifiExitSsid = this[Keys.wifiExitSsid] ?: "",
        wifiExitArmed = this[Keys.wifiExitArmed] ?: false,
        /**
         * 用 `stringSetPreferencesKey` 存，读出来转 List 时**保持插入顺序做不到**
         * （Set 无序），所以这里按字母排 —— 数量少，顺序不影响使用。
         */
        recentWifis = (this[Keys.recentWifis] ?: emptySet()).sorted(),
        cacheClearOnFinish = this[Keys.cacheClearOnFinish] ?: false,
        tabs = this[Keys.tabs],
        navPosition = enumOr(this[Keys.navPosition], NavPosition.Side),
        ingestNotices = this[Keys.ingestNotices] ?: true,
        backgroundPlay = this[Keys.backgroundPlay] ?: false,
    )

    suspend fun setTheme(mode: ThemeMode) = context.settingsDataStore.edit { it[Keys.theme] = mode.name }
    suspend fun setColorSource(source: ColorSource) = context.settingsDataStore.edit { it[Keys.color] = source.name }
    suspend fun setAmoled(value: Boolean) = context.settingsDataStore.edit { it[Keys.amoled] = value }
    suspend fun setDecoder(value: DecoderPreference) = context.settingsDataStore.edit { it[Keys.decoder] = value.name }
    suspend fun setEngine(value: PlayerEngine) = context.settingsDataStore.edit { it[Keys.engine] = value.name }
    suspend fun setSkipMode(value: SkipMode) = context.settingsDataStore.edit { it[Keys.skip] = value.name }
    suspend fun setAutoPlayNext(value: Boolean) = context.settingsDataStore.edit { it[Keys.autoNext] = value }
    suspend fun setDefaultSpeed(value: Float) = context.settingsDataStore.edit { it[Keys.speed] = value }

    suspend fun setTabs(value: Set<String>?) = context.settingsDataStore.edit { if (value == null) it.remove(Keys.tabs) else it[Keys.tabs] = value }
    suspend fun setNavPosition(value: NavPosition) = context.settingsDataStore.edit { it[Keys.navPosition] = value.name }
    suspend fun setBackgroundPlay(value: Boolean) = context.settingsDataStore.edit { it[Keys.backgroundPlay] = value }
    suspend fun setIngestNotices(value: Boolean) = context.settingsDataStore.edit { it[Keys.ingestNotices] = value }
    suspend fun setHeroMode(value: HeroMode) = context.settingsDataStore.edit { it[Keys.heroMode] = value.name }

    // 用户口径（2026-11-23）：缓存设置六项的写入
    suspend fun setPreloadNext(value: Boolean) = context.settingsDataStore.edit { it[Keys.preloadNext] = value }
    suspend fun setCacheMemoryMb(value: Int) = context.settingsDataStore.edit { it[Keys.cacheMemoryMb] = value }
    suspend fun setCacheMemorySeconds(value: Int) = context.settingsDataStore.edit { it[Keys.cacheMemorySeconds] = value }
    suspend fun setCacheDiskGb(value: Int) = context.settingsDataStore.edit { it[Keys.cacheDiskGb] = value }

    /** 用户口径（#39）音乐缓存大小（MB），与视频缓存完全独立。 */
    suspend fun setMusicCacheMb(value: Int) = context.settingsDataStore.edit { it[Keys.musicCacheMb] = value }

    /** 用户口径 #74。 */
    suspend fun setWifiExitEnabled(value: Boolean) =
        context.settingsDataStore.edit { it[Keys.wifiExitEnabled] = value }

    suspend fun setWifiExitSsid(value: String) =
        context.settingsDataStore.edit { it[Keys.wifiExitSsid] = value }

    suspend fun setWifiExitArmed(value: Boolean) =
        context.settingsDataStore.edit { it[Keys.wifiExitArmed] = value }

    /**
     * 用户口径（#175）**记下刚连上的 WiFi**，供设置页一键选用。
     *
     * 上限 [MAX_RECENT_WIFI] 个：这类名字本身不多，留太多反而难找。
     */
    suspend fun rememberWifi(ssid: String) {
        val name = ssid.trim()
        if (name.isEmpty()) return
        context.settingsDataStore.edit { prefs ->
            val old = prefs[Keys.recentWifis] ?: emptySet()
            if (name in old) return@edit
            val next = (old + name).toList()
            /**
             * 超出上限就只留最新的若干条。
             *
             * `stringSetPreferencesKey` 存的是 `Set`（无序），拿不到真正的
             * 「插入先后」，所以这里的「最新」只能近似 ——
             * 名字一共没几个、也不会频繁变动，够用。
             * 直接用 `List.takeLast` 再转回 Set，避免对 Set 调 takeLast（编译不过）。
             */
            prefs[Keys.recentWifis] = if (next.size > RECENT_WIFI_LIMIT) {
                next.takeLast(RECENT_WIFI_LIMIT).toSet()
            } else {
                next.toSet()
            }
        }
    }

    suspend fun setCacheClearOnFinish(value: Boolean) = context.settingsDataStore.edit { it[Keys.cacheClearOnFinish] = value }

    suspend fun setAutoUpdate(value: Boolean) = context.settingsDataStore.edit { it[Keys.autoUpdate] = value }
    suspend fun setDiagnosticsEnabled(value: Boolean) =
        context.settingsDataStore.edit { it[Keys.diagnosticsEnabled] = value }

    suspend fun setUpdateSource(value: UpdateSource) = context.settingsDataStore.edit { it[Keys.updateSource] = value.name }

    suspend fun setDanmaku(style: DanmakuStyle) = context.settingsDataStore.edit {
        it[Keys.dmEnabled] = style.enabled
        it[Keys.dmRows] = style.rows
        it[Keys.dmDensity] = style.density
        it[Keys.dmFont] = style.fontSize
        it[Keys.dmOpacity] = style.opacity
        it[Keys.dmSpeed] = style.speed
        it[Keys.dmTheme] = style.themeColor
    }

    /** Cache of the account's server-side appearance so the next cold start paints the right colors. */
    suspend fun setServerAppearance(color: String?, dark: Boolean?) = context.settingsDataStore.edit {
        if (color.isNullOrBlank()) it.remove(Keys.serverColor) else it[Keys.serverColor] = color
        if (dark == null) it.remove(Keys.serverDark) else it[Keys.serverDark] = dark.toString()
    }

    private inline fun <reified T : Enum<T>> enumOr(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}

/**
 * 用户口径（#175）**最近连过的 WiFi 候选上限**。
 *
 * 这类名字本身不多（家里/公司/车机几个），留太多反而在弹窗里难找。
 * 超出上限时新的一定保留，旧的自然被挤掉。
 */
private const val RECENT_WIFI_LIMIT = 8
