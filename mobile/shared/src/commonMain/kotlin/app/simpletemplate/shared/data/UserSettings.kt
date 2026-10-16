package app.simpletemplate.shared.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * The app lock. [appLockOn]: the lock chosen by the user (the app can also require it, see
 * AppLockGate); [appLockMinutes]: away time before it asks again (5, 15, 30, 60).
 */
data class LockSettings(
    val appLockOn: Boolean = false,
    val appLockMinutes: Int = 15,
)

/**
 * The signed-in user's own settings on this device. Personal, so not synced. Each setting is one
 * key; add the app's own settings the same way.
 */
class UserSettings internal constructor(private val store: LocalStore, private val userId: Uuid) {
    private val queries = store.db.userSettingQueries

    fun observeLockSettings(): Flow<LockSettings> =
        observe(LOCK).map { value ->
            value?.split(',')?.let { p -> runCatching { LockSettings(p[0] == "on", p[1].toInt()) }.getOrNull() } ?: LockSettings()
        }

    suspend fun setLockSettings(s: LockSettings) = save(LOCK, "${if (s.appLockOn) "on" else "off"},${s.appLockMinutes}")

    /** Test mode (debug builds only): the app lock is off so the app can be driven for testing. */
    fun observeTestMode(): Flow<Boolean> = observe(TEST_MODE).map { it == "on" }

    suspend fun setTestMode(on: Boolean) = save(TEST_MODE, if (on) "on" else "off")

    private fun observe(key: String): Flow<String?> =
        queries.setting(userId.toString(), key).asFlow().mapToOneOrNull(store.dispatcher)

    private suspend fun save(key: String, value: String) = withContext(store.dispatcher) {
        queries.saveSetting(userId.toString(), key, value)
    }

    private companion object {
        const val LOCK = "app_lock"
        const val TEST_MODE = "test_mode"
    }
}
