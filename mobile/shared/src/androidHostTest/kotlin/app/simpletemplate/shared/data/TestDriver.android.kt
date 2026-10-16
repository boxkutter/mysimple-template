package app.simpletemplate.shared.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.simpletemplate.shared.db.SimpleTemplateDatabase

actual fun inMemoryDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SimpleTemplateDatabase.Schema.create(it) }
