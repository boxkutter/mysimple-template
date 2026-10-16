package app.simpletemplate.shared.data

import app.cash.sqldelight.db.SqlDriver
import app.simpletemplate.shared.db.SimpleTemplateDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The on-device database plus what every repository needs to write to it. The database is the
 * source of truth on the device (local-first); the platform opens it (encrypted on Android).
 */
class LocalStore(
    val db: SimpleTemplateDatabase,
    internal val clock: Clock = Clock.System,
    internal val newId: () -> Uuid = Uuid::generateV7,
    internal val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal val outbox = Outbox(db.syncQueries, clock, newId)

    internal fun nowMillis(): Long = clock.now().toEpochMilliseconds()

    companion object {
        fun create(driver: SqlDriver, clock: Clock = Clock.System): LocalStore =
            LocalStore(SimpleTemplateDatabase(driver), clock)
    }
}

/** The organisation the signed-in user works for; owns everything they create. */
data class Tenant(val orgId: Uuid)
