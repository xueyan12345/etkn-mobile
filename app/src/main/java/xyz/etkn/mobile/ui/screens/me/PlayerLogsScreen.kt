package xyz.etkn.mobile.ui.screens.me

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.etkn.mobile.data.diagnostics.Diagnostics
import xyz.etkn.mobile.ui.components.GlassBackBar
import xyz.etkn.mobile.ui.components.GlassSheet
import xyz.etkn.mobile.ui.theme.Etkn
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 用户口径（2026-11-26）**「播放器日志」独立页面（替换原来的「导出日志」弹窗）**。
 *
 * 需求原文：
 *  「导出日志那个功能改进一下，导出日志功能改成播放日志，然后点击进去是个独立的页面，
 *   跟上面的设置那个还有外观一样，是个单独的界面，然后里面加个开关只有开关打开，
 *   才会保存日志记录，这样只有有问题的时候才记录日志，还能保证日志比较小导出也比较方便」
 *
 * 结构（照参考图）：
 *  ① 开关「保存播放日志」—— 关 = 完全不记录，不占空间
 *  ② 「清空日志」
 *  ③ 日志文件列表（exo_log_年月日_时分秒.log），每个可单独导出
 */
@Composable
fun PlayerLogsScreen(
    onDismiss: () -> Unit,
    graph: xyz.etkn.mobile.AppGraph,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = xyz.etkn.mobile.ui.theme.LocalSettings.current
    var enabled by remember(settings.diagnosticsEnabled) { mutableStateOf(settings.diagnosticsEnabled) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var exporting by remember { mutableStateOf<File?>(null) }

    fun refresh() {
        scope.launch {
            files = withContext(Dispatchers.IO) { Diagnostics.sessionFiles(context) }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { target ->
        val file = exporting
        if (target != null && file != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Diagnostics.exportFile(context, target, file) } }
            Toast.makeText(context, if (result.isSuccess) "日志已导出" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    GlassSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("播放器日志", style = MaterialTheme.typography.titleLarge)

            /**
             * ① 开关：只有打开才记录。
             * 说明文字放在下面一行，避免用户不知道「开了会占空间」。
             */
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("保存播放日志", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (enabled) "正在记录，复现问题后请关掉并导出"
                        else "关闭状态不占用任何空间",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        scope.launch {
                            val real = withContext(Dispatchers.IO) { Diagnostics.setEnabled(context, on) }
                            enabled = real
                            runCatching { graph.settings.setDiagnosticsEnabled(real) }
                            if (!real && on) Toast.makeText(context, "日志启动失败", Toast.LENGTH_SHORT).show()
                            refresh()
                        }
                    },
                )
            }

            /**
             * ② 清空日志：删掉所有日志文件（正在记录的会重建）。
             */
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { Diagnostics.clear(context) }
                        refresh()
                        Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Rounded.DeleteOutline, null, Modifier.padding(end = 6.dp))
                    Text("清空日志")
                }
            }

            /**
             * ③ 文件列表：每个文件一行，右侧导出按钮。
             */
            if (files.isEmpty()) {
                Text(
                    if (enabled) "还没有日志（记录刚开始）" else "暂无日志文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(files, key = { it.absolutePath }) { file ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "${fmtSize(file.length())} · ${fmtTime(file.lastModified())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { exporting = file; save.launch(file.name) }) {
                                Icon(Icons.Rounded.Download, "导出")
                            }
                        }
                    }
                }
            }

            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun fmtTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))
