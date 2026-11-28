package xyz.etkn.mobile.ui.screens.connect

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.model.ServerIdentity
import xyz.etkn.mobile.data.session.SavedAccount
import xyz.etkn.mobile.data.session.StoredSessions
import xyz.etkn.mobile.graph
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.components.EtknLoading

private sealed interface Step {
    data object Server : Step
    data class Login(val server: ServerIdentity, val username: String = "") : Step
}

@Composable
fun ConnectScreen(onDone: (() -> Unit)? = null, onCancel: (() -> Unit)? = null) {
    val graph = LocalContext.current.graph
    val stored by graph.sessions.accounts.collectAsStateWithLifecycle(initialValue = StoredSessions())
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(Step.Server) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val surface = MaterialTheme.colorScheme.surface
    val glow = MaterialTheme.colorScheme.primaryContainer
    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(0f to glow.copy(alpha = 0.55f), 0.55f to surface, 1f to surface)),
    ) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (onCancel != null) {
                Box(Modifier.fillMaxWidth()) {
                    IconButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            Text("ETKN", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(40.dp))
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState is Step.Login
                    (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                label = "connect-step",
                modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
            ) { current ->
                when (current) {
                    Step.Server -> ServerStep(
                        initial = stored.lastServer.orEmpty(),
                        accounts = stored.accounts,
                        busy = busy,
                        error = error,
                        onSubmit = { address ->
                            busy = true; error = null
                            scope.launch {
                                runCatching { graph.sessions.resolveServer(address) }
                                    .onSuccess { step = Step.Login(it) }
                                    .onFailure { error = it.message ?: "连接失败" }
                                busy = false
                            }
                        },
                        onAccount = { account ->
                            busy = true; error = null
                            scope.launch {
                                val ok = runCatching { graph.sessions.switchTo(account) }.getOrDefault(false)
                                if (ok) onDone?.invoke()
                                else step = Step.Login(ServerIdentity(account.serverUrl, account.serverName, account.serverId), account.userName)
                                busy = false
                            }
                        },
                        onForget = { account -> scope.launch { graph.sessions.forget(account) } },
                    )
                    is Step.Login -> LoginStep(
                        server = current.server,
                        initialUser = current.username,
                        busy = busy,
                        error = error,
                        onBack = { error = null; step = Step.Server },
                        onSubmit = { user, password ->
                            busy = true; error = null
                            scope.launch {
                                runCatching { graph.sessions.login(current.server, user, password) }
                                    .onSuccess { onDone?.invoke() }
                                    .onFailure { error = it.message ?: "登录失败" }
                                busy = false
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerStep(
    initial: String,
    accounts: List<SavedAccount>,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onAccount: (SavedAccount) -> Unit,
    onForget: (SavedAccount) -> Unit,
) {
    var address by rememberSaveable(initial) { mutableStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (accounts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { account -> AccountRow(account, enabled = !busy, onClick = { onAccount(account) }, onForget = { onForget(account) }) }
            }
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("服务器地址") },
            placeholder = { Text("http://192.168.1.10:8097") },
            leadingIcon = { Icon(Icons.Rounded.Dns, null) },
            singleLine = true,
            enabled = !busy,
            isError = error != null,
            supportingText = if (error != null) { { Text(error) } } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) onSubmit(address) }),
            shape = Corner.card,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryAction("连接", busy, enabled = address.isNotBlank()) { onSubmit(address) }
    }
}

@Composable
private fun AccountRow(account: SavedAccount, enabled: Boolean, onClick: () -> Unit, onForget: () -> Unit) {
    Surface(
        shape = Corner.panel,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clip(Corner.panel).clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(account.userName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    account.serverName + if (account.sealedToken == null) " · 需要密码" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onForget, enabled = enabled) { Icon(Icons.Rounded.Close, "移除") }
        }
    }
}

@Composable
private fun LoginStep(
    server: ServerIdentity,
    initialUser: String,
    busy: Boolean,
    error: String?,
    onBack: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var user by rememberSaveable(server.id) { mutableStateOf(initialUser) }
    var password by rememberSaveable(server.id) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            shape = Corner.pill,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.clip(Corner.pill).clickable(enabled = !busy, onClick = onBack),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, Modifier.size(18.dp))
                Text(server.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
        }
        OutlinedTextField(
            value = user, onValueChange = { user = it },
            label = { Text("用户名") }, singleLine = true, enabled = !busy,
            leadingIcon = { Icon(Icons.Rounded.Person, null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            shape = Corner.card, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("密码") }, singleLine = true, enabled = !busy,
            isError = error != null,
            supportingText = if (error != null) { { Text(error) } } else null,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (visible) "隐藏密码" else "显示密码")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (user.isNotBlank()) onSubmit(user, password) }),
            shape = Corner.card, modifier = Modifier.fillMaxWidth(),
        )
        PrimaryAction("登录", busy, enabled = user.isNotBlank()) { onSubmit(user, password) }
    }
}

@Composable
private fun PrimaryAction(label: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
    ) {
        if (busy) EtknLoading(Modifier.size(28.dp), size = 5.dp, tint = MaterialTheme.colorScheme.onPrimary)
        else Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
