package xyz.etkn.mobile.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.actions.PopupChoice
import xyz.etkn.mobile.ui.actions.rememberPopupAnchor

/**
 * 用户口径（2026-11-23）：长按首页媒体库封面，弹 ETKN 服务端的四个功能，
 * 对应网页版媒体库封面上的菜单：
 *   · 生成封面   → 重新生成这个库的封面图
 *   · 提取缩略图 → 只补图片，不重读元数据
 *   · 扫描       → scan-media-library（只扫这个库）
 *   · 刷新       → virtual-libraries/{id}/refresh，让界面立刻更新
 *
 * 权限：调用方只在 session.isAdmin 时挂 onLongClick，非管理员没有菜单。
 * 认证：/api/tv 的 token 可以直接打这几个主站管理接口（实测 200/202）。
 */
class LibraryActions internal constructor(
    private val show: (List<PopupChoice>) -> Unit,
    private val run: (Item, String, suspend () -> Unit) -> Unit,
    private val api: xyz.etkn.mobile.data.api.EtknApi,
) {
    fun openMenu(library: Item) {
        show(
            listOf(
                PopupChoice("生成封面", Icons.Rounded.AutoAwesome) {
                    run(library, "生成封面") { api.generateLibraryCover(library.id) }
                },
                PopupChoice("提取缩略图", Icons.Rounded.Image) {
                    run(library, "提取缩略图") { api.extractLibraryThumbnails(library.id) }
                },
                PopupChoice("扫描", Icons.Rounded.Search) {
                    run(library, "扫描") { api.scanLibraryFiles(library.id) }
                },
                PopupChoice("刷新", Icons.Rounded.Refresh) {
                    run(library, "刷新") { api.refreshLibraryView(library.id) }
                },
            ),
        )
    }
}

@Composable
fun rememberLibraryActions(): LibraryActions {
    val context = LocalContext.current
    val anchor = rememberPopupAnchor()
    val scope = rememberCoroutineScope()
    val api = context.graph.api
    val appContext = context.applicationContext

    return remember(anchor, api) {
        LibraryActions(
            show = { choices -> anchor.open(choices) },
            run = { library, label, block ->
                scope.launch {
                    val result = runCatching { block() }
                    val text = result.fold(
                        onSuccess = { "$label 已提交：${library.name}" },
                        onFailure = { "$label 失败：${(it.message ?: "未知错误").take(60)}" },
                    )
                    android.widget.Toast.makeText(appContext, text, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            api = api,
        )
    }
}
