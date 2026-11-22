package xyz.etkn.mobile.ui.screens.me

import androidx.compose.material.icons.rounded.Star
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch
import xyz.etkn.mobile.BuildConfig
import xyz.etkn.mobile.data.session.SessionState
import xyz.etkn.mobile.data.session.StoredSessions
import xyz.etkn.mobile.data.settings.Settings
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.components.GlassBackBar
import xyz.etkn.mobile.ui.components.GlassDialog
import xyz.etkn.mobile.ui.components.glassBarClearance
import xyz.etkn.mobile.ui.components.glassSource
import xyz.etkn.mobile.ui.nav.AccountsRoute
import xyz.etkn.mobile.ui.nav.DownloadsRoute
import xyz.etkn.mobile.ui.nav.FavoritesRoute
import xyz.etkn.mobile.ui.nav.LocalBottomInset
import xyz.etkn.mobile.ui.nav.LocalNav
import xyz.etkn.mobile.ui.nav.SettingsRoute
import xyz.etkn.mobile.ui.screens.connect.ConnectScreen
import xyz.etkn.mobile.ui.theme.Etkn
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.actions.rememberPopupAnchor
import xyz.etkn.mobile.ui.actions.PopupChoice
import xyz.etkn.mobile.ui.screens.search.SearchScreen
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width

@Composable
fun MeScreen() {
    val graph = LocalContext.current.graph
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val settings by graph.settings.flow.collectAsStateWithLifecycle(initialValue = Settings())
    val session = (graph.sessions.state.collectAsStateWithLifecycle().value as? SessionState.SignedIn)?.session ?: return
    var confirmLogout by remember { mutableStateOf(false) }
    var exportingLog by remember { mutableStateOf(false) }
    // 用户口径（2026-11-23）：搜索覆盖层开关（搜索已从底栏挪到这里）
    var showSearch by remember { mutableStateOf(false) }

    // Tablets: a readable centred column instead of edge-to-edge rows.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
        item {
            Spacer(
                Modifier.windowInsetsPadding(WindowInsets.statusBars).height(12.dp),
            )
        }
        // 用户口径（2026-11-23）：搜索挪到「我的」页最上面。
        // 平时只是一条长搜索框，点一下才覆盖一层完整搜索界面（复刻原搜索页），
        // 底栏「搜索」标签已删。
        item {
            SearchEntry(
                Modifier.padding(horizontal = Etkn.space.screen, vertical = 12.dp),
                onClick = { showSearch = true },
            )
        }
        // 用户口径（2026-11-23）：顶部那块账号信息卡（头像 + 用户名 + 服务器地址）整块删掉，
        // 下面功能列表直接上移。
        item {
            SettingsGroup {
                Entry(Icons.Rounded.Favorite, "我的收藏") { nav.to(FavoritesRoute) }
                Entry(Icons.Rounded.Download, "下载") { nav.to(DownloadsRoute) }
                Entry(Icons.Rounded.SwitchAccount, "切换账号") { nav.to(AccountsRoute) }
            }
        }
        item {
            SettingsGroup {
                Entry(Icons.Rounded.Palette, "外观", settings.themeMode.label) { nav.to(SettingsRoute(SettingsPage.Appearance.name)) }
                Entry(Icons.Rounded.PlayCircle, "播放", "解码 · ${settings.decoder.label}") { nav.to(SettingsRoute(SettingsPage.Playback.name)) }
                Entry(Icons.Rounded.ChatBubble, "弹幕", if (settings.danmaku.enabled) "开启" else "关闭") { nav.to(SettingsRoute(SettingsPage.Danmaku.name)) }
                Entry(Icons.Rounded.SystemUpdate, "检查更新", "v${BuildConfig.VERSION_NAME} · ${if (settings.autoUpdate) "自动" else "手动"}") {
                    nav.to(SettingsRoute(SettingsPage.Update.name))
                }
                Entry(Icons.Rounded.BugReport, "导出日志") { exportingLog = true }
            }
        }
        item {
            SettingsGroup {
                Entry(Icons.AutoMirrored.Rounded.Logout, "退出登录", tint = MaterialTheme.colorScheme.error, chevron = false) { confirmLogout = true }
            }
        }
        item {
            Text(
                "ETKN Mobile ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(24.dp),
            )
        }
    }
    }

    if (exportingLog) LogExportSheet(onDismiss = { exportingLog = false })

    // 用户口径（2026-11-23）：点击搜索框后覆盖一层完整搜索界面（复刻原搜索页）
    if (showSearch) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showSearch = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            androidx.compose.material3.Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    SearchScreen()
                    IconButton(
                        onClick = { showSearch = false },
                        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(8.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack, "返回",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    if (confirmLogout) {
        GlassDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录？") },
            text = { Text("将退出 ${session.userName} 在 ${session.serverName} 上的登录。") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; graph.music.stop(); scope.launch { graph.sessions.logout() } }) { Text("退出") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun Entry(
    icon: ImageVector,
    label: String,
    summary: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    chevron: Boolean = true,
    onClick: () -> Unit,
) {
    ListItem(
        onClick = onClick,
        supportingContent = summary?.let { { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
        leadingContent = { Icon(icon, null, tint = tint) },
        trailingContent = if (chevron) {
            { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(label, color = tint) }
}

@Composable
fun AccountsScreen() {
    val graph = LocalContext.current.graph
    val nav = LocalNav.current
    val stored by graph.sessions.accounts.collectAsStateWithLifecycle(initialValue = StoredSessions())
    var adding by remember { mutableStateOf(false) }
    BackHandler(enabled = adding) { adding = false }
    if (adding) {
        ConnectScreen(onDone = { adding = false; nav.back() }, onCancel = { adding = false })
        return
    }
    val current = graph.sessions.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    val haze = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().glassSource(haze).verticalScroll(rememberScrollState()).padding(top = glassBarClearance())) {
        SettingsGroup {
            stored.accounts.forEach { account ->
                val active = account.key == current?.key
                val isDefault = account.key == stored.defaultKey
                ListItem(
                    onClick = {
                        if (!active) scope.launch {
                            val ok = runCatching { graph.sessions.switchTo(account) }.getOrDefault(false)
                            if (!ok) { message = "该账号需要重新输入密码"; adding = true }
                        }
                    },
                    supportingContent = {
                        // 用户口径（2026-11-22）：显示「当前」（正在用）和「默认」（冷启动用它），两者可以不是同一个。
                        Text(
                            account.serverName +
                                if (account.sealedToken == null && !active) " · 需要密码" else "",
                        )
                    },
                    leadingContent = { Icon(Icons.Rounded.Person, null) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isDefault) Text("默认", color = MaterialTheme.colorScheme.primary)
                            if (active) Text(if (isDefault) " · 当前" else "当前", color = MaterialTheme.colorScheme.primary)
                            val menu = rememberPopupAnchor()
                            IconButton(onClick = {
                                menu.open(
                                    listOf(
                                        PopupChoice(
                                            label = "设为默认账号",
                                            icon = Icons.Rounded.Star,
                                            selected = isDefault,
                                            onClick = {
                                                scope.launch {
                                                    graph.sessions.setDefaultAccount(if (isDefault) null else account.key)
                                                    message = if (isDefault) "已取消默认，改为跟随上次使用的账号" else "已设为默认：${account.userName}"
                                                }
                                            },
                                        ),
                                    ),
                                )
                            }) { Icon(Icons.Rounded.MoreVert, "更多", modifier = menu.modifier) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(account.userName) }
            }
            ListItem(
                onClick = { adding = true },
                leadingContent = { Icon(Icons.Rounded.SwitchAccount, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            ) { Text("添加账号或服务器") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(Etkn.space.screen)) }
        Spacer(Modifier.height(LocalBottomInset.current))
    }
    GlassBackBar("切换账号", collapsed = true, haze = haze, onBack = nav::back)
    }
}


/**
 * 用户口径（2026-11-23）：我的页顶部那条长搜索框。
 * 只是个入口 —— 点一下才弹出完整搜索界面（SearchScreen），平时不占功能。
 */
@Composable
private fun SearchEntry(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = Corner.panel,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search, "搜索",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "搜索电影、剧集、音乐",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
