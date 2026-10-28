package app.simpletemplate.shared.sync

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import app.simpletemplate.shared.ApiException
import app.simpletemplate.shared.data.LocalStore
import app.simpletemplate.shared.db.Outbox_operation
import app.simpletemplate.shared.db.Sync_conflict
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class SyncState(
    val running: Boolean = false,
    val lastSyncedAt: Instant? = null,
    val lastReport: SyncReport? = null,
    /** Why the last attempt did not finish (offline, refused), for the Status page. */
    val problem: String? = null,
)

/**
 * Keeps one organisation workspace in step with the platform: a sync when the workspace opens,
 * a few seconds after local changes, every few minutes while the app is open, and on demand.
 * One sync at a time. The local workspace has none (contract §1).
 */
class WorkspaceSync(
    private val store: LocalStore,
    private val orgId: Uuid,
    val engine: SyncEngine,
    /** Moves files (none when the app keeps no files). */
    private val attachments: Attachments? = null,
    /** The platform wiped this workspace (the membership ended): its data is gone from the phone. */
    private val onRemoved: (Uuid) -> Unit = {},
) {
    private val mutex = Mutex()
    private val state = MutableStateFlow(SyncState())
    val status: StateFlow<SyncState> = state.asStateFlow()
    private var job: Job? = null

    /** Changes waiting to be sent from this workspace. */
    fun pending(): Flow<Long> =
        store.db.syncQueries.pendingCountFor(orgId.toString()).asFlow().mapToOne(store.dispatcher)

    suspend fun rejected(): List<Outbox_operation> = withContext(store.dispatcher) { engine.rejected(orgId) }

    suspend fun conflicts(): List<Sync_conflict> = withContext(store.dispatcher) { engine.conflicts(orgId) }

    /** Drops a refused change and brings the platform's records back in its place. */
    suspend fun discardRejected(opId: String) {
        withContext(store.dispatcher) { engine.discardRejected(opId, orgId) }
        syncNow()
    }

    suspend fun keepMine(conflict: Sync_conflict) = withContext(store.dispatcher) { engine.keepMine(conflict) }

    suspend fun useTheirs(conflict: Sync_conflict) = withContext(store.dispatcher) { engine.useTheirs(conflict) }

    suspend fun syncNow(): SyncReport? = mutex.withLock {
        state.value = state.value.copy(running = true, problem = null)
        try {
            val report = engine.sync(orgId)
            if (orgId.toString() in report.wiped) {
                state.value = SyncState(lastSyncedAt = Clock.System.now(), lastReport = report)
                onRemoved(orgId)
                return@withLock report
            }
            val transferProblem = attachments?.let { a ->
                runCatching { a.transfer(orgId) }.fold(
                    onSuccess = { it.problems.firstOrNull() },
                    onFailure = { e -> if (e is CancellationException) throw e; "Files not transferred: ${e::class.simpleName}" },
                )
            }
            state.value = SyncState(lastSyncedAt = Clock.System.now(), lastReport = report, problem = transferProblem)
            report
        } catch (e: ApiException) {
            state.value = state.value.copy(running = false, problem = "The platform answered ${e.status}: ${e.detail}")
            null
        } catch (e: CancellationException) {
            state.value = state.value.copy(running = false)
            throw e
        } catch (e: Exception) {
            val problem = if (e is kotlinx.io.IOException || e is HttpRequestTimeoutException) {
                "Not synced: can't reach the platform (${e::class.simpleName})"
            } else {
                // Something on this phone: say what, so it can be reported
                "Not synced: ${e::class.simpleName}: ${e.message?.take(300)}"
            }
            state.value = state.value.copy(running = false, problem = problem)
            null
        }
    }

    /** Starts the automatic syncs for as long as [scope] lives. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { syncNow() }
            launch {
                pending().distinctUntilChanged().drop(1).debounce(3.seconds).collect { if (it > 0) syncNow() }
            }
            while (true) {
                delay(5.minutes)
                syncNow()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
