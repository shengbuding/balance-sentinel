package com.balancesentinel.app.data.refresh

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

internal enum class RefreshDiagnosticStage {
    RUN_CREATED,
    RUN_AWAIT_STARTED,
    RUN_AWAIT_COMPLETED,
    RUN_FINISH_STARTED,
    RUN_FINISHED,
    RUN_CANCELLED,
    RUN_FAILED,
    ACCOUNT_STARTED,
    FETCH_STARTED,
    FETCH_RETURNED,
    COMMIT_BARRIER_WAIT,
    COMMIT_BARRIER_ENTERED,
    ACCOUNT_LOCK_WAIT,
    ACCOUNT_LOCK_ENTERED,
    DATA_MUTATION_WAIT,
    DATA_MUTATION_ENTERED,
    ROOM_COMMIT_STARTED,
    ROOM_COMMIT_COMPLETED,
    ACCOUNT_TERMINAL_WRITE_STARTED,
    ACCOUNT_TERMINAL_RECORDED,
    ACCOUNT_COMPLETED,
    ACCOUNT_CANCELLED,
    ACCOUNT_FAILED
}

internal data class RefreshDiagnosticEvent(
    val sequence: Long,
    val timestamp: Long,
    val stage: RefreshDiagnosticStage,
    val runId: String?,
    val accountId: String?,
    val trigger: RefreshTrigger?,
    val generation: Long?,
    val previousStageElapsedMs: Long?,
    val detail: String?
)

/** Bounded, metadata-only refresh stage tracker used by exported debug reports. */
internal object RefreshDiagnostics {
    private const val MAX_EVENTS = 400
    private const val MAX_REPORT_EVENTS = 200
    private const val MAX_ACTIVE_STAGES = 200
    private const val MAX_DETAIL_LENGTH = 96

    private val lock = Any()
    private val sequence = AtomicLong()
    private val events = ArrayDeque<RefreshDiagnosticEvent>(MAX_EVENTS)
    private val activeStages = linkedMapOf<String, RefreshDiagnosticEvent>()

    fun record(
        stage: RefreshDiagnosticStage,
        runId: String? = null,
        accountId: String? = null,
        trigger: RefreshTrigger? = null,
        generation: Long? = null,
        timestamp: Long = System.currentTimeMillis(),
        detail: String? = null,
        terminal: Boolean = false
    ) {
        runCatching {
            synchronized(lock) {
                val key = activeKey(runId, accountId)
                val previous = key?.let(activeStages::get)
                val event = RefreshDiagnosticEvent(
                    sequence = sequence.incrementAndGet(),
                    timestamp = timestamp,
                    stage = stage,
                    runId = runId,
                    accountId = accountId,
                    trigger = trigger ?: previous?.trigger,
                    generation = generation ?: previous?.generation,
                    previousStageElapsedMs = previous?.let {
                        (timestamp - it.timestamp).coerceAtLeast(0L)
                    },
                    detail = detail
                        ?.replace('\n', ' ')
                        ?.replace('\r', ' ')
                        ?.take(MAX_DETAIL_LENGTH)
                )
                if (events.size == MAX_EVENTS) events.removeFirst()
                events.addLast(event)
                if (key != null) {
                    if (terminal) {
                        activeStages.remove(key)
                    } else {
                        if (key !in activeStages && activeStages.size == MAX_ACTIVE_STAGES) {
                            activeStages.remove(activeStages.entries.first().key)
                        }
                        activeStages[key] = event
                    }
                }
            }
        }
    }

    fun toReportText(now: Long = System.currentTimeMillis()): String {
        val active: List<RefreshDiagnosticEvent>
        val recent: List<RefreshDiagnosticEvent>
        val retainedEventCount: Int
        synchronized(lock) {
            active = activeStages.values.sortedBy { it.timestamp }
            recent = events.toList().asReversed().take(MAX_REPORT_EVENTS)
            retainedEventCount = events.size
        }
        return buildString {
            appendLine("  activeStages=${active.size}")
            active.forEachIndexed { index, event ->
                appendLine("  active[$index] ${event.toLine(now)}")
            }
            appendLine("  recentEvents=${recent.size} retained=$retainedEventCount limit=$MAX_EVENTS")
            recent.forEachIndexed { index, event ->
                appendLine("  event[$index] ${event.toLine(now)}")
            }
            if (active.isEmpty() && recent.isEmpty()) {
                appendLine("  (no refresh stage events in the current process)")
            }
        }
    }

    internal fun snapshot(): Pair<List<RefreshDiagnosticEvent>, List<RefreshDiagnosticEvent>> =
        synchronized(lock) { activeStages.values.toList() to events.toList() }

    internal fun resetForTests() {
        synchronized(lock) {
            events.clear()
            activeStages.clear()
            sequence.set(0L)
        }
    }

    private fun activeKey(runId: String?, accountId: String?): String? = when {
        runId != null && accountId != null -> "account:$runId:$accountId"
        runId != null -> "run:$runId"
        accountId != null -> "account:-:$accountId"
        else -> null
    }

    private fun RefreshDiagnosticEvent.toLine(now: Long): String = buildString {
        append("seq=$sequence stage=$stage")
        append(" timestampMs=$timestamp ageMs=${(now - timestamp).coerceAtLeast(0L)}")
        append(" runId=${runId ?: "-"} accountId=${accountId ?: "-"}")
        append(" trigger=${trigger ?: "-"} generation=${generation ?: "-"}")
        append(" previousStageElapsedMs=${previousStageElapsedMs ?: "-"}")
        detail?.let { append(" detail=$it") }
    }
}
