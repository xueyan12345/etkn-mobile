package xyz.etkn.mobile.data.session

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import xyz.etkn.mobile.data.net.EtknJson

private val Context.sessionDataStore by preferencesDataStore(name = "etkn_session")

@Serializable
data class SavedAccount(
    val serverUrl: String,
    val serverId: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    /** Keystore-sealed token; null means the account needs its password again. */
    val sealedToken: String? = null,
    val isAdmin: Boolean = false,
    val imageTag: String? = null,
    val lastUsed: Long = 0,
) {
    val key: String get() = "$serverId|$userId"
}

@Serializable
data class StoredSessions(
    val accounts: List<SavedAccount> = emptyList(),
    val activeKey: String? = null,
    val lastServer: String? = null,
    /**
     * 用户口径（2026-11-22）：冷启动默认登录哪个账号。
     * null = 沿用上次退出时的 activeKey（老行为）。
     */
    val defaultKey: String? = null,
)

/** A signed-in account. Tokens only live in memory here; disk copies are sealed. */
@Immutable
data class ActiveSession(
    val server: String,
    val serverId: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val token: String,
    val isAdmin: Boolean,
    val privateAccount: Boolean,
    val canRefreshMetadata: Boolean = false,
    val canDeleteMedia: Boolean = false,
    val imageTag: String? = null,
    /** Bumped on every sign-in / switch so stale responses can be discarded. */
    val generation: Long,
) {
    val key: String get() = "$serverId|$userId"
}

class SessionStore(private val context: Context) {
    private val stateKey = stringPreferencesKey("sessions")

    val flow = context.sessionDataStore.data.map { prefs ->
        prefs[stateKey]?.let { runCatching { EtknJson.decodeFromString(StoredSessions.serializer(), it) }.getOrNull() }
            ?: StoredSessions()
    }

    suspend fun read(): StoredSessions = flow.first()

    suspend fun update(transform: (StoredSessions) -> StoredSessions) {
        context.sessionDataStore.edit { prefs ->
            val current = prefs[stateKey]?.let {
                runCatching { EtknJson.decodeFromString(StoredSessions.serializer(), it) }.getOrNull()
            } ?: StoredSessions()
            prefs[stateKey] = EtknJson.encodeToString(StoredSessions.serializer(), transform(current))
        }
    }
}
