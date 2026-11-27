package xyz.etkn.mobile.ui.screens.me

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.session.SessionState
import xyz.etkn.mobile.data.settings.ColorSource
import xyz.etkn.mobile.data.settings.DanmakuStyle
import xyz.etkn.mobile.data.settings.DecoderPreference
import xyz.etkn.mobile.data.settings.HeroMode
import xyz.etkn.mobile.data.settings.HeroModePerAccount
import xyz.etkn.mobile.data.settings.NavPosition
import xyz.etkn.mobile.data.settings.PlayerEngine
import xyz.etkn.mobile.data.settings.Settings
import xyz.etkn.mobile.data.settings.SkipMode
import xyz.etkn.mobile.data.settings.ThemeMode
import xyz.etkn.mobile.data.settings.UiScale
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.common.LocalWindowLayout
import xyz.etkn.mobile.ui.components.GlassBackBar
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.PillToggle
import xyz.etkn.mobile.ui.components.glassBarClearance
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.nav.DOCK_MAX
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.nav.TopLevel
import xyz.etkn.mobile.ui.nav.visibleTabs
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.LocalSettings
import xyz.etkn.mobile.ui.theme.Corner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import xyz.etkn.mobile.data.settings.HiddenLibrariesPerAccount
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background

enum class SettingsPage(val title: String) {
    Appearance("外观"),
    /**
     * 用户口径（2026-11-27）**新增「媒体库设置」板块**，位置在外观和播放之间。
     *
     * 从「外观」搬过来：首页推荐、隐藏媒体库（都按账号独立）。
     * 从「播放」搬过来：最新列表隐藏已观看（服务端存）、入库与获取通知（本地存）。
     */
    MediaLibrary("媒体库设置"),
    Playback("播放"),
    Danmaku("弹幕"),
    Update("检查更新"),
}

@Composable
fun SettingsScreen(page: SettingsPage) {
    val graph = LocalContext.current.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current
    val haze = rememberHazeState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
        Column(Modifier.fillMaxSize().glassSource(haze).verticalScroll(rememberScrollState()).padding(top = glassBarClearance())) {
            SettingsGroup {
                when (page) {
                    SettingsPage.Appearance -> {
                        ChoiceRow("主题", ThemeMode.entries, settings.themeMode, { it.label }) { scope.launch { graph.settings.setTheme(it) } }
                        val sources = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ColorSource.entries else listOf(ColorSource.Server)
                        if (sources.size > 1) {
                            ChoiceRow("配色", sources, settings.colorSource, { it.label }) { scope.launch { graph.settings.setColorSource(it) } }
                        }
                        SwitchRow("纯黑深色", settings.amoled) { scope.launch { graph.settings.setAmoled(it) } }
                        val context = LocalContext.current
                        // 用户口径（2026-11-22）：写死的四档换成连续倍率滑块（0.8x~1.3x）。
                        // 拖动时只更新文案，松手才写入并重建 Activity —— 拖动过程中反复 recreate 会闪屏。
                        var scaleFactor by remember { mutableFloatStateOf(UiScale.readFactor(context)) }
                        SliderRow(
                            label = "封面缩放倍率",
                            value = scaleFactor,
                            range = UiScale.MIN..UiScale.MAX,
                            step = UiScale.STEP,
                            format = { UiScale.label(it) },
                        ) { value ->
                            scaleFactor = value
                            val snapped = (value / UiScale.STEP).roundToInt() * UiScale.STEP
                            if (snapped != UiScale.readFactor(context)) {
                                UiScale.writeFactor(context, snapped)
                                (context as? Activity)?.recreate()
                            }
                        }
                        val wide = LocalWindowLayout.current.wide
                        if (wide) {
                            ChoiceRow("导航位置", NavPosition.entries, settings.navPosition, { it.label }) { scope.launch { graph.settings.setNavPosition(it) } }
                        }
                        TabPicker(settings, side = wide && settings.navPosition == NavPosition.Side) { scope.launch { graph.settings.setTabs(it) } }
                    }
                    /**
                     * 用户口径（2026-11-27）**新板块：媒体库设置**（位置 = 外观和播放之间）。
                     *
                     * 内容全部来自原「外观」和原「播放」，搬过来后功能必须照旧生效：
                     *  · 首页推荐 —— 按账号独立（HeroModePerAccount），改完要 recreate 让首页重排
                     *  · 隐藏媒体库 —— 按账号独立（HiddenLibrariesPerAccount），改完 recreate
                     *  · 最新列表隐藏已观看 —— 存服务端 playback_preferences
                     *  · 入库与获取通知 —— 存本地 DataStore，生效点 MainShell 的 IngestNoticesEffect
                     */
                    SettingsPage.MediaLibrary -> {
                        val mlContext = LocalContext.current
                        val mlSessionKey = (graph.sessions.state.collectAsStateWithLifecycle().value as? SessionState.SignedIn)?.session?.key
                        val heroNow = remember(mlSessionKey, settings.heroMode) {
                            HeroModePerAccount.read(mlContext, mlSessionKey, settings.heroMode)
                        }
                        ChoiceRow("首页推荐", HeroMode.entries, heroNow, { it.label }) {
                            // 只写当前账号，绝不改全局默认值（沿用 11-23 的口径）
                            HeroModePerAccount.write(mlContext, mlSessionKey, it)
                            (mlContext as? Activity)?.recreate()
                        }
                        val hiddenLibs = remember(mlSessionKey) {
                            HiddenLibrariesPerAccount.read(mlContext, mlSessionKey)
                        }
                        var hiddenLibsNow by remember(mlSessionKey) { mutableStateOf(hiddenLibs) }
                        SwitchRow("隐藏媒体库", hiddenLibsNow) {
                            hiddenLibsNow = it
                            HiddenLibrariesPerAccount.write(mlContext, mlSessionKey, it)
                            (mlContext as? Activity)?.recreate()
                        }
                        /** 服务端存的开关，见 ServerPreferences.kt 的 ServerHideWatchedLatest。 */
                        ServerHideWatchedLatest()
                        /** 本地存的通知开关，生效点 MainShell.kt 的 IngestNoticesEffect。 */
                        SwitchRow("入库与获取通知", settings.ingestNotices) {
                            scope.launch { graph.settings.setIngestNotices(it) }
                        }
                    }
                    SettingsPage.Playback -> {
                        ChoiceRow("播放内核", PlayerEngine.entries, settings.engine, { it.label }) { scope.launch { graph.settings.setEngine(it) } }
                        ChoiceRow("解码", DecoderPreference.entries, settings.decoder, { it.label }) { scope.launch { graph.settings.setDecoder(it) } }
                        ChoiceRow("片头片尾", SkipMode.entries, settings.skipMode, { it.label }) { scope.launch { graph.settings.setSkipMode(it) } }
                        ChoiceRow("默认倍速", listOf(1f, 1.25f, 1.5f, 2f), settings.defaultSpeed, { "${it}×" }) { scope.launch { graph.settings.setDefaultSpeed(it) } }
                        SwitchRow("自动播放下一集", settings.autoPlayNext) { scope.launch { graph.settings.setAutoPlayNext(it) } }
                        SwitchRow("后台播放", settings.backgroundPlay) { scope.launch { graph.settings.setBackgroundPlay(it) } }
                        ServerPlaybackPreferences()
                        // 用户口径（2026-11-23）：照参考图的缓存设置六项
                        CacheSettings(settings)
                        // 用户口径（2026-11-27 #74）：放最后
                        WifiExitSettings(settings)
                    }
                    SettingsPage.Danmaku -> DanmakuControls(settings.danmaku) { scope.launch { graph.settings.setDanmaku(it) } }
                    SettingsPage.Update -> UpdateSettings(settings)
                }
            }
            Spacer(Modifier.height(LocalBottomInset.current + 16.dp))
        }
    }
    Box(Modifier.widthIn(max = 720.dp)) { GlassBackBar(page.title, collapsed = true, haze = haze, onBack = nav::back) }
    }
}

/**
 * Which top-level pages the dock / rail shows. 首页 is fixed; 探索 only appears for accounts with
 * the right; the phone dock stops at [DOCK_MAX].
 */
@Composable
private fun TabPicker(settings: Settings, side: Boolean, onChange: (Set<String>) -> Unit) {
    val canDiscover by LocalContext.current.graph.discover.allowed.collectAsStateWithLifecycle()
    val shown = visibleTabs(settings.tabs, canDiscover, side)
    val limit = if (side) Int.MAX_VALUE else DOCK_MAX
    Column(Modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 8.dp)) {
        Text(if (side) "侧栏" else "底栏", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(PillGap), verticalArrangement = Arrangement.spacedBy(PillGap)) {
            // 用户口径（2026-11-23）：底栏已经删掉「媒体库」，这里预览也要一起消失，
            // 否则设置页还显示一个实际没有的图标。
            TopLevel.entries.filter { it != TopLevel.Libraries && it != TopLevel.Search && (it != TopLevel.Discover || canDiscover) }.forEach { tab ->
                val on = tab in shown
                // 首页 is always there: shown on, but nothing to toggle.
                val fixed = tab == TopLevel.Home
                PillToggle(
                    checked = on,
                    enabled = fixed || on || shown.size < limit,
                    onClick = {
                        if (!fixed) {
                            val next = if (on) shown - tab else shown + tab
                            onChange(next.filter { it != TopLevel.Home }.mapTo(HashSet()) { it.name })
                        }
                    },
                    icon = if (on) tab.selectedIcon else tab.icon,
                ) { Text(tab.label) }
            }
        }
    }
}

/** Danmaku switches and sliders; shared by the settings page and the player sheet. */
@Composable
fun DanmakuControls(style: DanmakuStyle, onChange: (DanmakuStyle) -> Unit) {
    SwitchRow("显示弹幕", style.enabled) { onChange(style.copy(enabled = it)) }
    SwitchRow("跟随主题色", style.themeColor) { onChange(style.copy(themeColor = it)) }
    SliderRow("行数", style.rows.toFloat(), DanmakuStyle.Rows.first.toFloat()..DanmakuStyle.Rows.last.toFloat(), 1f, { it.roundToInt().toString() }) {
        onChange(style.copy(rows = it.roundToInt()))
    }
    SliderRow("密度", style.density.toFloat(), DanmakuStyle.Density.first.toFloat()..DanmakuStyle.Density.last.toFloat(), 10f, { "${it.roundToInt()}%" }) {
        onChange(style.copy(density = it.roundToInt()))
    }
    SliderRow("字号", style.fontSize.toFloat(), DanmakuStyle.FontSize.first.toFloat()..DanmakuStyle.FontSize.last.toFloat(), 1f, { it.roundToInt().toString() }) {
        onChange(style.copy(fontSize = it.roundToInt()))
    }
    SliderRow("不透明度", style.opacity.toFloat(), DanmakuStyle.Opacity.first.toFloat()..DanmakuStyle.Opacity.last.toFloat(), 5f, { "${it.roundToInt()}%" }) {
        onChange(style.copy(opacity = it.roundToInt()))
    }
    SliderRow("速度", style.speed, DanmakuStyle.Speed, 0.25f, { "${(it * 100).roundToInt() / 100f}×" }) {
        onChange(style.copy(speed = it))
    }
}

/** Where every settings row starts its label (and a pill row its first pill), inside a [SettingsGroup]. */
internal val ROW_INSET = 20.dp

@Composable
internal fun SettingsGroup(horizontal: Dp = Etkn.space.screen, content: @Composable () -> Unit) {
    Surface(
        shape = Corner.panel,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = horizontal, vertical = 4.dp),
    ) { Column(Modifier.padding(vertical = 8.dp)) { content() } }
}

/** A labelled row of [PillToggle]s; the first pill starts exactly under the label. */
@Composable
internal fun <T> ChoiceRow(label: String, options: List<T>, selected: T, name: (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PillGap),
        ) {
            options.forEach { option ->
                PillToggle(checked = option == selected, onClick = { onSelect(option) }, modifier = Modifier.semantics { role = Role.RadioButton }) {
                    Text(name(option), maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Slider that previews locally while dragging and commits once on release. */
@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    val steps = (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(local), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        val state = remember(steps, range) { SliderState(local, steps, range) }
        state.value = local
        Slider(
            state = state,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
        )
    }
}

/**
 * 用户口径（2026-11-23）：缓存设置（照参考图六项）。
 *  · 预加载                    开关
 *  · 最大缓存大小              数值（MB）
 *  · 最大缓存时长              数值（秒）
 *  · 最大磁盘缓存大小           数值（GB）
 *  · 播放结束后自动清空磁盘缓存  开关
 *  · 清空磁盘缓存              按钮 + 当前占用
 */
@Composable
private fun CacheSettings(settings: xyz.etkn.mobile.data.settings.Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graph = context.graph
    var usage by remember { mutableLongStateOf(0L) }
    /**
     * 用户口径（2026-11-23）：**改完上限立刻收敛一次，并回读真实占用**。
     *
     * 之前这里只读了 usage 显示数字，改动「最大磁盘缓存大小」后
     * 不会触发任何清理 —— 用户把 5GB 改了，实际占用还是 12.7GB，
     * 看上去就是「设置没生效」。现在改完当场 trim + 重新统计。
     */
    /**
     * 用户口径（2026-11-27）**占用/清空必须读官方缓存（VideoCache），不是旧目录**。
     *
     * 修的 bug（用户截图实测）：日志里 disk usage=620MB，设置页却显示 0 MB。
     *
     * 根因：缓存已经全部迁到官方 SimpleCache（cacheDir/videocache），
     * 而这里还在读 PlaybackCache.diskUsage() —— 那是旧的自建目录 mediafiles/，
     * 早就没数据了。所以显示永远是 0，清空也清不到地方。
     */
    LaunchedEffect(settings.cacheDiskGb) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 先确保缓存实例按新上限建立（这样占用才读得到）
            xyz.etkn.mobile.player.cache.VideoCache.get(
                context.applicationContext,
                settings.cacheDiskGb.coerceIn(0, 200).toLong() * 1024L * 1024L * 1024L,
            )
            // 旧的整文件缓存目录清一次（历史遗留）
            xyz.etkn.mobile.player.PlaybackCache.trim(context, settings)
        }
        usage = xyz.etkn.mobile.player.cache.VideoCache.usedBytes()
    }
    SwitchRow("预加载下一集", settings.preloadNext) { scope.launch { graph.settings.setPreloadNext(it) } }
    NumberRow("最大缓存大小", settings.cacheMemoryMb, "MB", listOf(200, 400, 600, 800, 1200)) {
        scope.launch { graph.settings.setCacheMemoryMb(it) }
    }
    NumberRow("最大缓存时长", settings.cacheMemorySeconds, "秒", listOf(600, 1200, 1800, 2400, 3600)) {
        scope.launch { graph.settings.setCacheMemorySeconds(it) }
    }
    NumberRow("最大磁盘缓存大小", settings.cacheDiskGb, "GB", listOf(2, 5, 10, 20, 50)) {
        scope.launch { graph.settings.setCacheDiskGb(it) }
    }
    SwitchRow("播放结束后自动清空磁盘缓存", settings.cacheClearOnFinish) {
        scope.launch { graph.settings.setCacheClearOnFinish(it) }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = {
                /**
                 * 用户口径（2026-11-27）**清空要真的清官方缓存**。
                 * 之前清的是旧目录 mediafiles/，用户的 620MB 一点没动。
                 */
                xyz.etkn.mobile.player.cache.VideoCache.clearAll(context)
                xyz.etkn.mobile.player.PlaybackCache.clearDisk(context)
                usage = xyz.etkn.mobile.player.cache.VideoCache.usedBytes()
            },
        ) { Text("清空磁盘缓存") }
        Spacer(Modifier.weight(1f))
        Text(
            formatBytes(usage),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    /**
     * 用户口径（2026-11-27，#39）**音乐缓存单独一项**。
     *
     * 用户原话：
     *  「音乐单独缓存，那个你要在我的，播放那个设置里，加一个选项，
     *    就是专门给音乐设置一个大小区间，可以选择的，类似于视频那个」
     *
     * 和上面那几个视频缓存项完全独立：
     *  · 音乐有自己的目录 `filesDir/musiccache`
     *  · 音乐有自己的配额（这里选）
     *  · 视频的「最大磁盘缓存大小 / 播放完清空」不会碰到音乐
     */
    Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)))
    Text(
        "音乐缓存",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = ROW_INSET, top = 14.dp, bottom = 2.dp),
    )
    Text(
        "与视频缓存分开存放。播过的歌留在本地，重启 App 后封面/歌词/进度都能立刻显示，点播放直接本地起播。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = ROW_INSET),
    )
    /**
     * 用户口径（2026-11-27，#1 真根因）**ColorOS 自启动开关引导**。
     *
     * adb 实测（一加 13 / ColorOS 16）：
     * ```
     * OplusAppStartupManager: *Do not want to launch app xyz.etkn.mobile/10687
     * for broadcast Intent { act=android.intent.action.MEDIA_BUTTON
     * cmp=xyz.etkn.mobile/...MediaButtonReceiver }
     * ```
     * 系统发播放按键 → ColorOS 拦下不拉起进程 → 状态栏卡片一直是灰的。
     *
     * 这个白名单只能用户手动开，App 无法自己改（系统设置权限）。
     * 这里给一个一键跳转，避免用户找不到入口。
     */
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("允许后台运行（ColorOS 需手动开）", style = MaterialTheme.typography.bodyLarge)
            Text(
                "状态栏/车机那个播放按钮点不动，是因为系统拦截了自启动。到这里把「允许自启动」「允许后台活动」都打开。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = {
                val pkg = context.packageName
                // ColorOS 的自启动管理页，多个候选按序尝试
                val candidates = listOf(
                    android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    ),
                    android.content.ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                    ),
                    android.content.ComponentName(
                        "com.oplus.battery",
                        "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                    ),
                )
                var launched = false
                for (cmp in candidates) {
                    runCatching {
                        context.startActivity(
                            android.content.Intent().apply {
                                component = cmp
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            },
                        )
                        launched = true
                    }
                    if (launched) break
                }
                if (!launched) {
                    // 兜底：跳系统电池优化列表
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            },
        ) { Text("去设置") }
    }
    NumberRow("音乐缓存大小", settings.musicCacheMb, "MB", listOf(512, 1024, 2048, 4096, 8192, 16384, 20480)) {
        /** 同时写进 MusicCache 的内存配额，播放器下次创建就用新值（#39）。 */
        xyz.etkn.mobile.music.MusicCache.setLimit(it)
        scope.launch { graph.settings.setMusicCacheMb(it) }
    }
    var musicUsage by remember { mutableLongStateOf(0L) }
    LaunchedEffect(settings.musicCacheMb) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            /** 启动时把设置里的配额同步给 MusicCache，保证播放器读到的值是对的。 */
            xyz.etkn.mobile.music.MusicCache.setLimit(settings.musicCacheMb)
            xyz.etkn.mobile.music.MusicCache.get(context.applicationContext, settings.musicCacheMb)
            musicUsage = xyz.etkn.mobile.music.MusicCache.sizeBytes()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = {
                xyz.etkn.mobile.music.MusicCache.clear()
                musicUsage = xyz.etkn.mobile.music.MusicCache.sizeBytes()
            },
        ) { Text("清空音乐缓存") }
        Spacer(Modifier.weight(1f))
        Text(
            formatBytes(musicUsage),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 数值型设置行：标签 + 当前值（点了弹选择）。 */
@Composable
private fun NumberRow(label: String, value: Int, unit: String, options: List<Int>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = ROW_INSET, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            "$value $unit",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label) },
            text = {
                Column {
                    options.forEach { option ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(option); open = false }
                                .padding(vertical = 12.dp),
                        ) {
                            Text(
                                "$option $unit",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (option == value) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (option == value) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("取消") } },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / 1024.0 / 1024)
    else -> "0 MB"
}

/**
 * 用户口径（2026-11-27，#74/#77）**离开指定 WiFi 时退出 App**。
 *
 * 用户原话：
 * ```
 * 「当连接特定 wifi 并正在播放音乐的情况下。如果这个设定的 wifi 断开了，
 *   直接彻底关闭 etkn」
 * 「wifi 的名你能不能像 MACRODROID 这个软件一样，能直接弹出个窗口，
 *   选择系统已经连接过或者保存过的 wifi，这样就不用手动填了，并且更准确」
 * 「确认断开就立即退出」
 * 「主要就是用在车上」
 * 「放这个设置的菜单里，放最后就行」
 * ```
 *
 * 位置：我的 → 播放 → 最底部（在缓存设置之后）。
 * WiFi 名称**不是输入框**，是弹窗列出系统保存过的网络让你点。
 */
@Composable
private fun WifiExitSettings(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graph = context.graph

    /** 读 SSID 必须位置权限（Android 10+ 系统规定），这里主动申请一次。 */
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }

    Spacer(Modifier.height(1.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)))
    Text(
        "离开此 WiFi 时退出",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = ROW_INSET, top = 14.dp, bottom = 2.dp),
    )
    Text(
        "连着下面选中的 WiFi 并正在放歌时，一旦这个 WiFi 断开就立即彻底关闭 ETKN（连后台播放卡片一起清掉）。主要用于车上：熄火后 WiFi 一断，App 自动退出。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = ROW_INSET),
    )

    var pickerOpen by remember { mutableStateOf(false) }
    var ssids by remember { mutableStateOf<List<String>>(emptyList()) }
    var granted by remember { mutableStateOf(false) }

    /**
     * 用户口径（#78）**扫描是异步的**：startScan() 之后要等 2~4 秒结果才回来。
     * 弹窗先显示能拿到的，随后自动补两轮（1.5s / 3.5s），
     * 这样用户不用关掉重开才能看到周边 WiFi。
     */
    LaunchedEffect(pickerOpen) {
        if (!pickerOpen) return@LaunchedEffect
        repeat(3) { round ->
            if (round > 0) kotlinx.coroutines.delay(if (round == 1) 1_500 else 2_000)
            ssids = xyz.etkn.mobile.music.WifiExitGuard.savedSsids(context.applicationContext)
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = ROW_INSET, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (settings.wifiExitSsid.isBlank()) "未选择 WiFi" else settings.wifiExitSsid,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (settings.wifiExitEnabled && settings.wifiExitSsid.isNotBlank()) {
                Text(
                    if (settings.wifiExitArmed) "已启用 · 放歌中（已上膛）" else "已启用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        TextButton(onClick = {
            granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ssids = xyz.etkn.mobile.music.WifiExitGuard.savedSsids(context.applicationContext)
            pickerOpen = true
        }) { Text("选择") }
        Switch(
            checked = settings.wifiExitEnabled,
            onCheckedChange = { on ->
                scope.launch { graph.settings.setWifiExitEnabled(on) }
                if (on) {
                    xyz.etkn.mobile.music.WifiExitGuard.start(context.applicationContext)
                } else {
                    xyz.etkn.mobile.music.WifiExitGuard.stop()
                    scope.launch { graph.settings.setWifiExitArmed(false) }
                }
            },
        )
    }

    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text("选择 WiFi") },
            text = {
                when {
                    !granted -> Column {
                        Text("需要「位置信息」权限才能读取 WiFi 名称（Android 系统规定）。")
                        Spacer(Modifier.height(10.dp))
                        TextButton(onClick = {
                            permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            pickerOpen = false
                        }) { Text("去授权") }
                    }
                    ssids.isEmpty() -> Column {
                        Text("没有读到 WiFi 列表。")
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "可能需要：①打开手机「定位开关」 ②在设置里允许本应用获取位置 ③先连接一次该 WiFi。\n扫不到的也可以直接手打。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        /**
                         * 用户口径（#78 补充）**扫不到的可以手打**。
                         *
                         * 真实情况：车上的 WiFi 熄火后就不广播了，扫描列表里没有；
                         * 系统保存列表又因 targetSdk 收紧读不到。
                         * 所以两者都拿不到时，必须能手动补 —— 否则这个功能没法用。
                         */
                        var manual by remember { mutableStateOf("") }
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.OutlinedTextField(
                                value = manual,
                                onValueChange = { manual = it },
                                label = { Text("扫不到？手动输入 WiFi 名") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                enabled = manual.isNotBlank(),
                                onClick = {
                                    scope.launch { graph.settings.setWifiExitSsid(manual.trim()) }
                                    pickerOpen = false
                                },
                            ) { Text("确定") }
                        }
                        ssids.forEach { ssid ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    scope.launch { graph.settings.setWifiExitSsid(ssid) }
                                    pickerOpen = false
                                }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = ssid == settings.wifiExitSsid,
                                    onClick = {
                                        scope.launch { graph.settings.setWifiExitSsid(ssid) }
                                        pickerOpen = false
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(ssid, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickerOpen = false }) { Text("关闭") } },
        )
    }
}
