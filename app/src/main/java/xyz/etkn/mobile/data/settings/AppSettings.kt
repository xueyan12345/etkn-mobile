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
    // 用户口径（2026-11-23）：缓存与预加载六项（照参考图的缓存设置页）
    /** 预加载下一集（把下一集提前写进磁盘缓存）。 */
    val preloadNext: Boolean = true,
    /** 内存侧前向缓冲上限（MB）。 */
    val cacheMemoryMb: Int = 400,
    /** 内存侧前向缓冲上限（秒）。 */
    val cacheMemorySeconds: Int = 1200,
    /** 磁盘缓存上限（GB）。 */
    val cacheDiskGb: Int = 10,
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
        val heroMode = stringPreferencesKey("hero_mode")
        val preloadNext = booleanPreferencesKey("preload_next")
        val cacheMemoryMb = intPreferencesKey("cache_memory_mb")
        val cacheMemorySeconds = intPreferencesKey("cache_memory_seconds")
        val cacheDiskGb = intPreferencesKey("cache_disk_gb")
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
        heroMode = enumOr(this[Keys.heroMode], HeroMode.Auto),
        preloadNext = this[Keys.preloadNext] ?: true,
        cacheMemoryMb = this[Keys.cacheMemoryMb] ?: 400,
        cacheMemorySeconds = this[Keys.cacheMemorySeconds] ?: 1200,
        cacheDiskGb = this[Keys.cacheDiskGb] ?: 10,
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
    suspend fun setCacheClearOnFinish(value: Boolean) = context.settingsDataStore.edit { it[Keys.cacheClearOnFinish] = value }

    suspend fun setAutoUpdate(value: Boolean) = context.settingsDataStore.edit { it[Keys.autoUpdate] = value }
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
