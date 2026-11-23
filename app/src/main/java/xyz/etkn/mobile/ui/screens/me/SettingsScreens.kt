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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.material3.Spacer
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog

enum class SettingsPage(val title: String) { Appearance("外观"), Playback("播放"), Danmaku("弹幕"), Update("检查更新") }

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
                        // 用户口径（2026-11-23）：首页推荐按账号独立设置，只影响当前登录的这个账号。
                        val sessionKey = (graph.sessions.state.collectAsStateWithLifecycle().value as? SessionState.SignedIn)?.session?.key
                        val heroNow = remember(sessionKey, settings.heroMode) { HeroModePerAccount.read(context, sessionKey, settings.heroMode) }
                        ChoiceRow("首页推荐", HeroMode.entries, heroNow, { it.label }) {
                            // 用户口径（2026-11-23）：只写当前账号，绝不改全局默认值。
                            // 原来这里同时 setHeroMode(it) 会把全局值也改掉，
                            // 于是没单独设置过的账号跟着一起变 —— 按账号独立就失效了。
                            HeroModePerAccount.write(context, sessionKey, it)
                            (context as? Activity)?.recreate()
                        }
                        val wide = LocalWindowLayout.current.wide
                        if (wide) {
                            ChoiceRow("导航位置", NavPosition.entries, settings.navPosition, { it.label }) { scope.launch { graph.settings.setNavPosition(it) } }
                        }
                        TabPicker(settings, side = wide && settings.navPosition == NavPosition.Side) { scope.launch { graph.settings.setTabs(it) } }
                    }
                    SettingsPage.Playback -> {
                        ChoiceRow("播放内核", PlayerEngine.entries, settings.engine, { it.label }) { scope.launch { graph.settings.setEngine(it) } }
                        ChoiceRow("解码", DecoderPreference.entries, settings.decoder, { it.label }) { scope.launch { graph.settings.setDecoder(it) } }
                        ChoiceRow("片头片尾", SkipMode.entries, settings.skipMode, { it.label }) { scope.launch { graph.settings.setSkipMode(it) } }
                        ChoiceRow("默认倍速", listOf(1f, 1.25f, 1.5f, 2f), settings.defaultSpeed, { "${it}×" }) { scope.launch { graph.settings.setDefaultSpeed(it) } }
                        SwitchRow("自动播放下一集", settings.autoPlayNext) { scope.launch { graph.settings.setAutoPlayNext(it) } }
                        SwitchRow("后台播放", settings.backgroundPlay) { scope.launch { graph.settings.setBackgroundPlay(it) } }
                        ServerPlaybackPreferences()
                        SwitchRow("入库与获取通知", settings.ingestNotices) { scope.launch { graph.settings.setIngestNotices(it) } }
                        // 用户口径（2026-11-23）：照参考图的缓存设置六项
                        CacheSettings(settings)
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
private fun CacheSettings(settings: xyz.etkn.mobile.data.settings.AppSettings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graph = context.graph
    var usage by remember { mutableLongStateOf(0L) }
    LaunchedEffect(settings.cacheDiskGb) {
        usage = xyz.etkn.mobile.player.PlaybackCache.diskUsage()
    }
    SwitchRow("预加载", settings.preloadNext) { scope.launch { graph.settings.setPreloadNext(it) } }
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
                xyz.etkn.mobile.player.PlaybackCache.clearDisk()
                usage = xyz.etkn.mobile.player.PlaybackCache.diskUsage()
            },
        ) { Text("清空磁盘缓存") }
        Spacer(Modifier.weight(1f))
        Text(
            formatBytes(usage),
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
