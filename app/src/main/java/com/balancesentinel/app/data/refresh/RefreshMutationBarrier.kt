package com.balancesentinel.app.data.refresh

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/** Shared lock joining Room account mutations to refresh persistence. */
object RefreshMutationBarrier {
    private val permit = Semaphore(1, true)
    private val invalidators = ConcurrentHashMap<String, () -> Unit>()
    private val commitDepth: ThreadLocal<Int> = ThreadLocal.withInitial { 0 }

    fun register(accountId: String, invalidate: () -> Unit) {
        invalidators[accountId] = invalidate
    }

    fun <T> withRefreshCommit(block: () -> T): T {
        if ((commitDepth.get() ?: 0) > 0) return block()
        permit.acquire()
        commitDepth.set(1)
        return try {
            block()
        } finally {
            commitDepth.remove()
            permit.release()
        }
    }

    /** Suspending counterpart used by Room-backed refresh commits. */
    suspend fun <T> withRefreshCommitSuspend(block: suspend () -> T): T {
        if (coroutineContext[PermitKey] != null) return block()
        return withContext(Dispatchers.IO + PermitElement) {
            permit.acquire()
            try {
                block()
            } finally {
                permit.release()
            }
        }
    }

    suspend fun <T> withAccountMutation(
        accountId: String?,
        invalidate: (String) -> Unit = {},
        block: suspend () -> T
    ): T =
        withContext(Dispatchers.IO) {
            permit.acquire()
            try {
                accountId?.let { id ->
                    invalidators[id]?.invoke()
                    invalidate(id)
                }
                block()
            } finally {
                permit.release()
            }
        }

    private object PermitKey : CoroutineContext.Key<PermitElement>

    private object PermitElement : CoroutineContext.Element {
        override val key: CoroutineContext.Key<*> = PermitKey
    }
}
