package xyz.etkn.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import xyz.etkn.mobile.ui.theme.Motion

/**
 * A page still loading: blank at first, the indicator fading in only if the wait is long enough
 * to notice, so a quick load goes straight from blank to the page fading in.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(SLOW_LOAD_MILLIS)
        slow = true
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        /**
         * 用户口径（2026-11-28，#183）**全 App 统一用「三点跳动」加载动画**。
         *
         * 用户当时的要求：
         * ```
         * 「不光播放器，就是所有加载的，它好像都是那个圈圈」
         * 「用第二个，3个小点那个」
         * ```
         *
         * ## 之前为什么不生效
         *
         * `EtknLoading.kt` 里早写好了三点跳动的 `EtknLoading`，
         * 但全 App 实际用的是 Material3 官方的 `LoadingIndicator()`
         * （就是「形变方块」那个圆圈），**自研的那个零引用**。
         * 所以用户看到的始终是圈圈 —— 写了等于没写。
         *
         * 现在把入口统一到这里：所有页面都通过 `LoadingState` /
         * `InlineLoading` 拿加载动画，改这一处就全 App 生效。
         */
        AnimatedVisibility(slow, enter = fadeIn(tween(Motion.FADE_IN)), exit = fadeOut()) { EtknLoading() }
    }
}

private const val SLOW_LOAD_MILLIS = 700L

@Composable
fun InlineLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        /** 同上：内联加载也用三点跳动（#183）。 */
        EtknLoading(size = 7.dp)
    }
}

@Composable
fun MessageState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) =
    MessageState(message, modifier, Icons.Rounded.CloudOff, "重试", onRetry)
