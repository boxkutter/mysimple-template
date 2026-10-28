package app.simpletemplate.shared.data

import app.cash.sqldelight.db.SqlDriver
import app.simpletemplate.shared.db.SimpleTemplateDatabase
import app.simpletemplate.shared.db.Outbox_operation
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Fresh in-memory database with the schema applied. */
expect fun inMemoryDriver(): SqlDriver

class FixedClock(var now: Instant = Instant.parse("2026-10-05T09:00:00Z")) : Clock {
    override fun now(): Instant = now
}

fun testStore(clock: Clock = FixedClock()): LocalStore {
    val driver = inMemoryDriver()
    return LocalStore(
        db = SimpleTemplateDatabase(driver),
        driver = driver,
        clock = clock,
        newId = Uuid::generateV7,
        dispatcher = Dispatchers.Unconfined,
    )
}

fun LocalStore.outboxOps(): List<Outbox_operation> = db.syncQueries.pending(1000).executeAsList()

val Outbox_operation.fields: JsonObject get() = Json.parseToJsonElement(payload).jsonObject
