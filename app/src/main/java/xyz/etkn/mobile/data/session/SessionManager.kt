package xyz.etkn.mobile.data.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.api.EtknApi
import xyz.etkn.mobile.data.api.ServerProbe
import xyz.etkn.mobile.data.model.ServerIdentity
import xyz.etkn.mobile.data.model.UserInfo
import xyz.etkn.mobile.data.net.EtknException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface SessionState {
    data object Restoring : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val session: ActiveSession) : SessionState
}

/**
 * Owns the active account.
 *
 * Persistence policy (mirrors the TV client):
 * - private accounts: token in memory only, nothing written to disk;
 * - administrators: token persisted only while the server-side `remember_admin_account` is on;
 * - everyone else: Keystore-sealed token.
 */
class SessionManager(
    private val store: SessionStore,
    private val probe: ServerProbe,
    private val scope: CoroutineScope,
) {
    lateinit var api: EtknApi

    private val generation = AtomicLong(0)
    private val memoryTokens = ConcurrentHashMap<String, String>()
    private val _state = MutableStateFlow<SessionState>(SessionState.Restoring)
    val state: StateFlow<SessionState> = _state.asStateFlow()
    val accounts = store.flow

    val current: ActiveSession?
        get() = (state.value as? SessionState.SignedIn)?.session

    fun require(): ActiveSession = current ?: throw EtknException.AuthExpired()

    fun restore() {
        scope.launch {
            val saved = store.read()
            // 用户口径（2026-11-22）：设了默认账号就优先用它冷启动；没设则沿用上次的 activeKey。
            val key = saved.defaultKey?.takeIf { k -> saved.accounts.any { it.key == k } } ?: saved.activeKey
            val account = saved.accounts.firstOrNull { it.key == key }
            val token = account?.sealedToken?.let(SecureBox::decrypt)
            if (account == null || token == null) {
                _state.value = SessionState.SignedOut
                return@launch
            }
            // Optimistic: show cached UI immediately, then verify the token in the background.
            activate(account.serverUrl, account.serverId, account.serverName, token, account.toUserInfo())
            verify(account.key)
        }
    }

    private fun verify(key: String) {
        scope.launch {
            val active = current ?: return@launch
            if (active.key != key) return@launch
            try {
                val me = api.me(active.server, active.token)
                if (me.id != active.userId) throw EtknException.AuthExpired()
                val remember = me.isAdmin && !me.privateAccount && runCatching { api.rememberAdmin(active.server, active.token) }.getOrDefault(false)
                if (current?.generation != active.generation) return@launch
                _state.value = SessionState.SignedIn(
                    active.copy(
                        userName = me.name.ifEmpty { active.userName },
                        isAdmin = me.isAdmin,
                        privateAccount = me.privateAccount,
                        canRefreshMetadata = me.canRefreshMetadata,
                        canDeleteMedia = me.canDeleteMedia,
                        imageTag = me.imageTag,
                    ),
                )
                persist(active.server, active.serverId, active.serverName, active.token, me, remember)
            } catch (expired: EtknException.AuthExpired) {
                if (current?.key == key) expire()
            } catch (_: Exception) {
                // Offline: keep the optimistic session; requests will surface errors per screen.
            }
        }
    }

    suspend fun resolveServer(address: String): ServerIdentity = probe.resolve(address)

    suspend fun login(server: ServerIdentity, username: String, password: String) {
        val (token, user) = api.authenticate(server.url, username.trim(), password)
        val remember = user.isAdmin && !user.privateAccount && runCatching { api.rememberAdmin(server.url, token) }.getOrDefault(false)
        persist(server.url, server.id, server.name, token, user, remember)
        activate(server.url, server.id, server.name, token, user)
    }

    /** 用户口径（2026-11-22）：设/取消冷启动默认账号。key=null 表示恢复「跟随上次」。 */
    suspend fun setDefaultAccount(key: String?) = store.update { it.copy(defaultKey = key) }

    /** Switch to a stored account; returns false when it needs a password. */
    suspend fun switchTo(account: SavedAccount): Boolean {
        val token = memoryTokens[account.key] ?: account.sealedToken?.let(SecureBox::decrypt) ?: return false
        val me = try {
            api.me(account.serverUrl, token)
        } catch (expired: EtknException.AuthExpired) {
            store.update { s -> s.copy(accounts = s.accounts.map { if (it.key == account.key) it.copy(sealedToken = null) else it }) }
            return false
        }
        if (me.id != account.userId) return false
        activate(account.serverUrl, account.serverId, account.serverName, token, me)
        store.update { it.copy(activeKey = account.key) }
        return true
    }

    suspend fun logout() {
        val active = current ?: return
        api.logout(active)
        memoryTokens.remove(active.key)
        store.update { s ->
            s.copy(
                accounts = s.accounts.map { if (it.key == active.key) it.copy(sealedToken = null) else it },
                activeKey = null,
            )
        }
        generation.incrementAndGet()
        _state.value = SessionState.SignedOut
    }

    suspend fun forget(account: SavedAccount) {
        memoryTokens.remove(account.key)
        store.update { s -> s.copy(accounts = s.accounts.filterNot { it.key == account.key }) }
    }

    /** Called by any layer that receives a 401 for the active session. */
    fun expire() {
        val active = current ?: return
        memoryTokens.remove(active.key)
        scope.launch {
            store.update { s ->
                s.copy(accounts = s.accounts.map { if (it.key == active.key) it.copy(sealedToken = null) else it }, activeKey = null)
            }
        }
        generation.incrementAndGet()
        _state.value = SessionState.SignedOut
    }

    private fun activate(server: String, serverId: String, serverName: String, token: String, user: UserInfo) {
        val session = ActiveSession(
            server = server,
            serverId = serverId,
            serverName = serverName,
            userId = user.id,
            userName = user.name,
            token = token,
            isAdmin = user.isAdmin,
            privateAccount = user.privateAccount,
            canRefreshMetadata = user.canRefreshMetadata,
            canDeleteMedia = user.canDeleteMedia,
            imageTag = user.imageTag,
            generation = generation.incrementAndGet(),
        )
        memoryTokens[session.key] = token
        _state.value = SessionState.SignedIn(session)
    }

    private suspend fun persist(
        server: String,
        serverId: String,
        serverName: String,
        token: String,
        user: UserInfo,
        rememberAdmin: Boolean,
    ) {
        val keepToken = !user.privateAccount && (!user.isAdmin || rememberAdmin)
        val key = "$serverId|${user.id}"
        if (user.privateAccount) {
            // Private accounts leave no trace on disk.
            store.update { s -> s.copy(accounts = s.accounts.filterNot { it.key == key }, activeKey = null, lastServer = server) }
            return
        }
        val sealed = if (keepToken) SecureBox.encrypt(token) else null
        store.update { s ->
            val entry = SavedAccount(
                serverUrl = server,
                serverId = serverId,
                serverName = serverName,
                userId = user.id,
                userName = user.name,
                sealedToken = sealed,
                isAdmin = user.isAdmin,
                imageTag = user.imageTag,
                lastUsed = System.currentTimeMillis(),
            )
            s.copy(
                accounts = listOf(entry) + s.accounts.filterNot { it.key == key },
                activeKey = if (keepToken) key else null,
                lastServer = server,
            )
        }
    }

    private fun SavedAccount.toUserInfo() = UserInfo(
        id = userId, name = userName, isAdmin = isAdmin, privateAccount = false,
        canRefreshMetadata = false, canDeleteMedia = false, imageTag = imageTag,
    )
}
