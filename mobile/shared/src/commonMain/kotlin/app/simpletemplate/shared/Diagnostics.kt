package app.simpletemplate.shared

/** Resolves a host name with the platform resolver, for connection diagnostics. */
expect suspend fun resolveHost(host: String): Result<List<String>>

/** Human-readable error including the cause chain (e.g. timeout → socket error). */
fun Throwable.describe(): String = generateSequence(this) { it.cause }
    .take(4)
    .joinToString(" ← ") { "${it::class.simpleName}: ${it.message ?: "-"}" }
