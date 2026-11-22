package xyz.etkn.mobile.ui.screens.me

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.etkn.mobile.BuildConfig
import xyz.etkn.mobile.data.settings.Settings
import xyz.etkn.mobile.data.update.DownloadState
import xyz.etkn.mobile.data.update.UpdateSource
import xyz.etkn.mobile.data.update.UpdateState
import xyz.etkn.mobile.data.update.formatBytes
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.components.GlassSheet

/** Settings → 检查更新. */
@Composable
internal fun UpdateSettings(settings: Settings) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val state by graph.updates.state.collectAsStateWithLifecycle()
    SwitchRow("启动时自动检查", settings.autoUpdate) { scope.launch { graph.settings.setAutoUpdate(it) } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("当前版本", style = MaterialTheme.typography.bodyLarge)
            Text(
                "v${BuildConfig.VERSION_NAME}" + when {
                    state.upToDate -> " · 已是最新"
                    state.release != null -> " · 可更新到 v${state.release!!.version}"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.checking) {
            LoadingIndicator(Modifier.size(40.dp))
        } else {
            FilledTonalButton(onClick = { graph.updates.check() }) { Text("检查更新") }
        }
    }
    state.error?.let {
        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    }
    if (state.release != null) UpdatePanel(state)
}

/** Shown once after an automatic check finds a newer release. */
@Composable
fun UpdatePrompt() {
    val graph = LocalContext.current.graph
    val state by graph.updates.state.collectAsStateWithLifecycle()
    if (!state.prompt || state.release == null) return
    GlassSheet(
        onDismissRequest = graph.updates::dismissPrompt,
    ) {
        Text(
            "发现新版本 v${state.release!!.version}",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            "当前 v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        UpdatePanel(state)
        Spacer(Modifier.height(24.dp))
    }
}

/** Release notes, mirror choice, download progress / speed and install. */
@Composable
private fun UpdatePanel(state: UpdateState) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val release = state.release ?: return
    var permissionDenied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) graph.updates.download()
    }
    val start = {
        val needs = graph.updates.needsStoragePermission &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needs) permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else graph.updates.download()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        if (release.notes.isNotBlank()) {
            Text(
                release.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        val running = state.download is DownloadState.Running || state.download is DownloadState.Verifying
        // 用户口径（2026-11-23）：下载源选项框去掉，固定就用加速源（内部多镜像回落：
        // ghfast.top → gh-proxy.net → 直连），用户不需要自己选。
        LaunchedEffect(Unit) {
            if (state.source != UpdateSource.Proxy) {
                graph.updates.setSource(UpdateSource.Proxy)
                scope.launch { graph.settings.setUpdateSource(UpdateSource.Proxy) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            when (val download = state.download) {
                DownloadState.Idle -> Button(onClick = start, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("下载更新")
                }
                is DownloadState.Running -> {
                    val fraction = if (download.total > 0) download.done.toFloat() / download.total else 0f
                    if (download.total > 0) {
                        LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                formatBytes(download.done) + if (download.total > 0) " / ${formatBytes(download.total)}" else "",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "${formatBytes(download.bytesPerSecond)}/s · ${download.source.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = graph.updates::cancel) { Text("取消") }
                    }
                }
                DownloadState.Verifying -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingIndicator(Modifier.size(32.dp))
                    Text("正在校验安装包…", modifier = Modifier.padding(start = 12.dp))
                }
                is DownloadState.Done -> {
                    Text("已保存到 下载/${download.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { graph.updates.install(context) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.InstallMobile, null, Modifier.size(ButtonDefaults.IconSize))
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                        Text("安装")
                    }
                }
                is DownloadState.Failed -> {
                    Text(download.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = start) { Text("重试") }
                }
            }
            if (permissionDenied) {
                Text(
                    "需要存储权限才能保存到下载目录",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}
